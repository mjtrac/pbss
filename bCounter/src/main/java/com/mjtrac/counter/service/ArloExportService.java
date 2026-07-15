/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 *
 * Generates the two files required by Arlo (VotingWorks) for
 * Risk-Limiting Audits:
 *
 *   ballot_manifest.csv  — batch-level ballot counts
 *   cvr_export.csv       — Cast Vote Record (one row per ballot page 1)
 *
 * Arlo documentation: https://voting.works/arlo/
 * CVR format: Arlo "generic" CSV format
 *
 * Limitations:
 *   - Only page 1 ballots are included in the CVR (page 2+ contests are
 *     merged onto the page 1 row since they belong to the same physical ballot)
 *   - Write-in votes are recorded as "WRITE-IN" (Arlo ignores them for RLA)
 *   - Ranked-choice contests use "CandidateName (Rank N)" notation per Arlo
 *     conventions
 */
package com.mjtrac.counter.service;

import com.mjtrac.counter.entity.*;
import com.mjtrac.counter.entity.VoteOpportunity.VoteStatus;
import com.mjtrac.counter.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

@Service
public class ArloExportService {

    private static final Logger log = LoggerFactory.getLogger(ArloExportService.class);

    private final BallotImageRepository     imageRepo;
    private final VoteOpportunityRepository voRepo;
    private final ContestRecordRepository   contestRepo;

    public ArloExportService(BallotImageRepository     imageRepo,
                              VoteOpportunityRepository voRepo,
                              ContestRecordRepository   contestRepo) {
        this.imageRepo   = imageRepo;
        this.voRepo      = voRepo;
        this.contestRepo = contestRepo;
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Write ballot_manifest.csv and cvr_export.csv to the given directory.
     * Called from VoteTallyService.processTally() at end of scan.
     */
    @Transactional(readOnly = true)
    public void export(String reportOutputDir) {
        try {
            Path dir = Paths.get(reportOutputDir);
            Files.createDirectories(dir);
            writeManifest(dir);
            writeCvr(dir);
        } catch (Exception ex) {
            log.warn("Arlo export failed: {}", ex.getMessage());
        }
    }

    // ── Ballot Manifest ───────────────────────────────────────────────────────

    /**
     * ballot_manifest.csv — one row per batch (region × ballot-type combination).
     *
     * Arlo expects:
     *   Container, Batch Name, Number of Ballots
     *
     * We map:
     *   Container  = jurisdiction_id
     *   Batch Name = region_id + "-" + ballot_type_id
     *   Count      = number of page-1 ballot_image rows for that combination
     */
    private void writeManifest(Path dir) throws IOException {
        // Only count page 1 — each physical ballot has one page-1 image
        List<BallotImage> page1 = imageRepo.findAll().stream()
            .filter(b -> b.getPageNumber() == 1)
            .collect(Collectors.toList());

        // Group by jurisdiction → batch
        // Batch = regionId + "-" + ballotTypeId  (from barcode)
        Map<String, Map<String, Long>> byJuris = new LinkedHashMap<>();
        for (BallotImage img : page1) {
            BarcodeRecord bc = img.getBarcode();
            if (bc == null) continue;
            String juris = coalesce(bc.getJurisdictionId(), "1");
            String batch = coalesce(bc.getRegionId(), "?")
                         + "-" + coalesce(bc.getBallotTypeId(), "1");
            byJuris.computeIfAbsent(juris, k -> new LinkedHashMap<>())
                   .merge(batch, 1L, Long::sum);
        }

        Path out = dir.resolve("ballot_manifest.csv");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {
            pw.println("Container,Batch Name,Number of Ballots");
            for (var jurisEntry : byJuris.entrySet()) {
                String juris = jurisEntry.getKey();
                for (var batchEntry : jurisEntry.getValue().entrySet()) {
                    pw.printf("%s,%s,%d%n",
                        csvEscape(juris),
                        csvEscape(batchEntry.getKey()),
                        batchEntry.getValue());
                }
            }
        }
        log.info("Arlo ballot manifest written: {}", out.toAbsolutePath());
    }

    // ── CVR Export ────────────────────────────────────────────────────────────

    /**
     * cvr_export.csv — one row per physical ballot (page 1 only; page 2+ votes
     * are merged onto the same row).
     *
     * Arlo generic CSV header:
     *   CvrNumber, TabulatorNum, BatchId, RecordId, BallotType,
     *   PrecinctPortion, [ContestName...], ...
     *
     * Contest cells:
     *   PLURALITY / APPROVAL: comma-separated voted candidate names
     *   RANKED_CHOICE:        "CandidateName (1)", "CandidateName (2)", etc.
     *   Overvoted:            "OVERVOTE"
     *   Unmarked:             "" (empty)
     *   Write-in:             "WRITE-IN"
     */
    private void writeCvr(Path dir) throws IOException {
        // All contests in the order they appear in the DB
        List<ContestRecord> contests = contestRepo.findAll().stream()
            .sorted(Comparator.comparing(ContestRecord::getId))
            .collect(Collectors.toList());

        if (contests.isEmpty()) {
            log.info("No contests found — skipping CVR export");
            return;
        }

        // Build a map: ballotImageId → list of VoteOpportunity
        // We need all VOs to build per-ballot rows
        Map<Long, List<VoteOpportunity>> voByImage = new LinkedHashMap<>();
        for (VoteOpportunity vo : voRepo.findAll()) {
            voByImage.computeIfAbsent(
                vo.getBallotImage().getId(), k -> new ArrayList<>()).add(vo);
        }

        // Build a map: page-1 imageId → list of imageIds for same physical ballot
        // Physical ballot = same barcode raw_data, different page numbers
        // We group ballot_image rows by barcode.raw_data (strips page from barcode)
        Map<String, List<BallotImage>> byBallotKey = new LinkedHashMap<>();
        for (BallotImage img : imageRepo.findAll().stream()
                .sorted(Comparator.comparing(BallotImage::getId))
                .collect(Collectors.toList())) {
            BarcodeRecord bc = img.getBarcode();
            if (bc == null) continue;
            // Key excludes page number so all pages of one ballot group together
            String key = coalesce(bc.getJurisdictionId(), "0")
                + "|" + coalesce(bc.getRegionId(), "0")
                + "|" + coalesce(bc.getPartyId(), "0")
                + "|" + coalesce(bc.getBallotTypeId(), "0")
                + "|" + coalesce(bc.getElectionId(), "0")
                + "|" + img.getImageName().replaceAll("_c\\d+\\.png.*$", "");
            byBallotKey.computeIfAbsent(key, k -> new ArrayList<>()).add(img);
        }

        Path out = dir.resolve("cvr_export.csv");
        int cvrNum = 0;
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {

            // Header row
            pw.print("CvrNumber,TabulatorNum,BatchId,RecordId,BallotType,PrecinctPortion");
            for (ContestRecord c : contests) {
                pw.print("," + csvEscape(c.getContestTitle()));
            }
            pw.println();

            for (var entry : byBallotKey.entrySet()) {
                List<BallotImage> pages = entry.getValue();
                // Use the page-1 image as the anchor; skip if none found
                BallotImage page1 = pages.stream()
                    .filter(i -> i.getPageNumber() == 1)
                    .findFirst().orElse(pages.get(0));

                BarcodeRecord bc = page1.getBarcode();
                cvrNum++;

                String tabulatorNum = "1";   // single station; extend for multi-station
                String batchId      = bc != null
                    ? coalesce(bc.getRegionId(), "?") + "-"
                      + coalesce(bc.getBallotTypeId(), "1")
                    : "unknown";
                String recordId     = String.valueOf(page1.getId());
                String ballotType   = bc != null
                    ? "J" + coalesce(bc.getJurisdictionId(), "?")
                      + "-P" + coalesce(bc.getPartyId(), "0")
                      + "-T" + coalesce(bc.getBallotTypeId(), "1")
                    : "unknown";
                String precinct     = bc != null
                    ? "Region-" + coalesce(bc.getRegionId(), "?")
                    : "unknown";

                // Collect all VOs for this physical ballot (all pages)
                List<VoteOpportunity> allVos = new ArrayList<>();
                for (BallotImage pg : pages) {
                    allVos.addAll(voByImage.getOrDefault(pg.getId(), List.of()));
                }

                // Group VOs by contest
                Map<Long, List<VoteOpportunity>> byContest = new LinkedHashMap<>();
                for (VoteOpportunity vo : allVos) {
                    byContest.computeIfAbsent(
                        vo.getContest().getId(), k -> new ArrayList<>()).add(vo);
                }

                pw.print(cvrNum + "," + tabulatorNum + ","
                    + csvEscape(batchId) + "," + recordId + ","
                    + csvEscape(ballotType) + "," + csvEscape(precinct));

                for (ContestRecord contest : contests) {
                    pw.print(",");
                    List<VoteOpportunity> cvos = byContest.getOrDefault(
                        contest.getId(), List.of());
                    pw.print(csvEscape(formatContestCell(contest, cvos)));
                }
                pw.println();
            }
        }
        log.info("Arlo CVR export written: {} ({} ballots)",
            out.toAbsolutePath(), cvrNum);
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    /**
     * Format one contest cell for the CVR.
     *
     * PLURALITY/MEASURE:  voted candidate names joined by "|" (Arlo multi-vote)
     * RANKED_CHOICE:      voted candidate names with rank suffix "(1)", "(2)"...
     * OVERVOTE:           "OVERVOTE"
     * UNMARKED:           "" (empty string)
     */
    private String formatContestCell(ContestRecord contest,
                                      List<VoteOpportunity> vos) {
        if (vos.isEmpty()) return "";

        boolean isRcv = "RANKED_CHOICE".equals(contest.getContestType());

        // Check for overvote — any VO with OVERVOTED status
        boolean overvoted = vos.stream()
            .anyMatch(v -> VoteStatus.OVERVOTED == v.getVoteStatus());
        if (overvoted) return "OVERVOTE";

        // Collect voted VOs
        List<VoteOpportunity> voted = vos.stream()
            .filter(v -> VoteStatus.VOTED == v.getVoteStatus())
            .collect(Collectors.toList());

        if (voted.isEmpty()) return "";

        if (isRcv) {
            // For RCV: candidate names already include "(Rank N)" suffix
            // Convert to Arlo format: strip "Rank " → just the number
            // e.g. "Alexandria Washington (Rank 1)" → "Alexandria Washington (1)"
            return voted.stream()
                .sorted(Comparator.comparing(v ->
                    extractRank(v.getCandidate().getCandidateName())))
                .map(v -> {
                    String name = v.getCandidate().getCandidateName();
                    // Convert "(Rank N)" to "(N)"
                    return name.replaceAll("\\(Rank (\\d+)\\)$", "($1)");
                })
                .collect(Collectors.joining("|"));
        } else {
            // PLURALITY / APPROVAL / MEASURE
            return voted.stream()
                .map(v -> {
                    String name = v.getCandidate().getCandidateName();
                    return v.getCandidate().isWriteIn() ? "WRITE-IN" : name;
                })
                .collect(Collectors.joining("|"));
        }
    }

    private int extractRank(String candidateName) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\\(Rank (\\d+)\\)$").matcher(candidateName);
        return m.find() ? Integer.parseInt(m.group(1)) : 99;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String coalesce(String s, String fallback) {
        return (s != null && !s.isBlank()) ? s : fallback;
    }
}
