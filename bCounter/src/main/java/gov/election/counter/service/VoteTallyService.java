/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 */
package gov.election.counter.service;

import gov.election.counter.model.BboxReport;
import gov.election.counter.model.BboxReport.ScanResult;
import gov.election.counter.model.BboxReport.MarkingResult;
import gov.election.counter.model.BboxReport.CandidateTally;
import gov.election.counter.model.ScanSession;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tallies votes across all scanned images, enforces overvote rules,
 * captures write-in images, and writes summary YAML and overvote reports.
 *
 * OUTPUT FILES (written to the JVM working directory):
 *   vote_summary.yaml   — per-contest totals
 *   overvote_report.txt — list of overvoted image/contest pairs
 *
 * Write-in images are written as:
 *   writein_{imageStem}_{contestId}.{ext}
 *   writein_{imageStem}_{contestId}_2.{ext}  (multiple write-ins per contest)
 */
@Service
public class VoteTallyService {

    private static final Logger log =
        LoggerFactory.getLogger(VoteTallyService.class);

    private final RcvTabulationService rcvService;
    private final ArloExportService     arloService;
    private final ResultsQueryService   resultsQuery;

    public VoteTallyService(RcvTabulationService rcvService,
                            ArloExportService arloService,
                            ResultsQueryService resultsQuery) {
        this.rcvService   = rcvService;
        this.arloService  = arloService;
        this.resultsQuery = resultsQuery;
    }

    /** Set at the start of each processTally call; used by helper methods. */
    private String currentImageFolder = ".";

    /** Directory where results_report.html, overvote_report.txt, and review_required.txt
     *  are written. Configured via reports.output.dir in application.properties. */
    @org.springframework.beans.factory.annotation.Value("${reports.output.dir:${user.dir}}")
    private String reportOutputDir;

    /** How often (images scanned) to write results_report.html during a scan. */
    @org.springframework.beans.factory.annotation.Value("${reports.interval:500}")
    private int reportInterval;

    /** Per-image, per-contest vote summary (internal). */
    private static class ImageContestResult {
        String imageName;
        long   contestId;
        String contestTitle;
        String contestType;
        int    maxVotes;
        boolean overvoted = false;
        List<String> markedCandidates = new ArrayList<>();
        String partyId  = "0";   // from barcode field 2
        String regionId = "0";   // from barcode field 1 (precinct/region)
    }

    /**
     * Process a completed list of scan results:
     *  1. Detect overvotes in PLURALITY/MEASURE contests
     *  2. Write overvote_report.txt
     *  3. Write vote_summary.yaml
     *  4. Extract write-in images
     *
     * @param results     all scan results for this session
     * @param imageFolder folder where ballot images live
     */
    public void processTally(List<ScanResult> results, String imageFolder,
                              ScanSession session) {
        if (results == null || results.isEmpty()) return;
        this.currentImageFolder = (imageFolder != null && !imageFolder.isBlank())
            ? imageFolder : ".";

        // Ensure report output directory exists
        try {
            Files.createDirectories(Paths.get(reportOutputDir));
        } catch (Exception ex) {
            log.warn("Could not create reports dir {}: {}", reportOutputDir, ex.getMessage());
        }

        List<ImageContestResult> icResults = buildImageContestResults(results);
        correctSessionTallies(icResults, session);
        writeOvervoteReport(icResults);
        writeVoteSummary(icResults, session);
        // Only write results_report if we have actual results
        // (session.results is cleared after each image, so this may be empty
        //  at end-of-scan; the last periodic write is the authoritative report)
        if (!results.isEmpty()) {
            writeResultsReport(icResults, session);
        }
        extractWriteIns(results, imageFolder);
    }

    /**
    /**
     * Called at the end of each pass before the next pass starts.
     * Copies the current results_report.html to passN_results_report.html
     * as a snapshot of all votes counted so far.
     */
    public void writePassReport(String imageFolder, ScanSession session, int passNumber) {
        if (reportOutputDir == null || reportOutputDir.isBlank()) {
            reportOutputDir = (session != null && session.reportFolder != null
                               && !session.reportFolder.isBlank())
                ? session.reportFolder : System.getProperty("user.dir");
        }
        try {
            java.nio.file.Path src = java.nio.file.Paths.get(
                reportOutputDir, "results_report.html");
            java.nio.file.Path dst = java.nio.file.Paths.get(
                reportOutputDir, "pass" + passNumber + "_results_report.html");
            if (java.nio.file.Files.exists(src)) {
                java.nio.file.Files.copy(src, dst,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("Pass {} snapshot written: {}", passNumber, dst.toAbsolutePath());
            }
        } catch (Exception ex) {
            log.warn("Could not write pass {} snapshot: {}", passNumber, ex.getMessage());
        }
    }

    /**
     * Called once at the very end of a complete scan - after all passes and
     * after the in-memory results list has been cleared. Runs DB-driven
     * reports: RCV tabulation and Arlo RLA exports.
     */
    public void processFinalTally(String imageFolder, ScanSession session) {
        if (reportOutputDir == null || reportOutputDir.isBlank()) {
            reportOutputDir = (session != null && session.reportFolder != null
                               && !session.reportFolder.isBlank())
                ? session.reportFolder : System.getProperty("user.dir");
        }
        log.info("Running final tally (RCV + Arlo) — reportOutputDir={}", reportOutputDir);
        // Write overvote_report.txt from DB
        try {
            writeDbOvervoteReport();
        } catch (Exception ex) {
            log.warn("Could not write final overvote report: {}", ex.getMessage());
        }
        // Write final results_report.html from DB — covers ALL passes
        try {
            writeDbResultsReport(session);
            log.info("Final results report written from DB");
        } catch (Exception ex) {
            log.warn("Could not write final results report: {}", ex.getMessage());
        }
        // RCV tabulation — reads directly from DB, independent of session.results
        try {
            var rcvResults = rcvService.tabulateAndWrite(reportOutputDir);
            if (!rcvResults.isEmpty()) {
                log.info("RCV tabulation complete: {} contest(s) tabulated", rcvResults.size());
            } else {
                log.info("RCV tabulation: no ranked-choice contests found");
            }
        } catch (Exception ex) {
            log.warn("RCV tabulation failed: {}", ex.getMessage(), ex);
        }
        // Arlo RLA export — reads directly from DB
        try {
            arloService.export(reportOutputDir);
        } catch (Exception ex) {
            log.warn("Arlo export failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Called periodically during a scan (every reportInterval images) and at
     * scan end.  Writes results_report.html based on the current DB contents.
     * Also used by ScanController to trigger periodic writes.
     */
    public void writePeriodicReport(List<ScanResult> results, String imageFolder,
                                     ScanSession session) {
        if (results == null || results.isEmpty()) return;
        this.currentImageFolder = (imageFolder != null && !imageFolder.isBlank())
            ? imageFolder : ".";
        try { Files.createDirectories(Paths.get(reportOutputDir)); }
        catch (Exception ex) { /* best effort */ }
        List<ImageContestResult> icResults = buildImageContestResults(results);
        correctSessionTallies(icResults, session);
        writeResultsReport(icResults, session);
    }

    public int getReportInterval() { return reportInterval; }
    public String getReportOutputDir() { return reportOutputDir; }

    // ── Build per-image per-contest results ───────────────────────────────────

    private List<ImageContestResult> buildImageContestResults(List<ScanResult> results) {
        List<ImageContestResult> out = new ArrayList<>();
        for (ScanResult sr : results) {
            if (sr.markings == null || sr.markings.isEmpty()) continue;

            // Group by contestId
            Map<Long, List<MarkingResult>> byContest = new LinkedHashMap<>();
            for (MarkingResult mr : sr.markings) {
                Long key = mr.contestId != null ? mr.contestId : -1L;
                byContest.computeIfAbsent(key, k -> new ArrayList<>()).add(mr);
            }

            // Parse party and region from barcode (format: jur|region|party|type|election|page)
            String[] bcFields = (sr.barcodeData != null)
                ? sr.barcodeData.split("\\|", -1) : new String[0];
            String barcodeParty  = bcFields.length > 2 ? bcFields[2] : "0";
            String barcodeRegion = bcFields.length > 1 ? bcFields[1] : "0";

            for (Map.Entry<Long, List<MarkingResult>> e : byContest.entrySet()) {
                List<MarkingResult> marks = e.getValue();
                if (marks.isEmpty()) continue;
                MarkingResult first = marks.get(0);

                ImageContestResult icr = new ImageContestResult();
                icr.imageName    = sr.imageName;
                icr.contestId    = first.contestId != null ? first.contestId : -1L;
                icr.contestTitle = first.contestTitle;
                icr.contestType  = first.contestType != null ? first.contestType : "PLURALITY";
                icr.maxVotes     = first.maxVotes > 0 ? first.maxVotes : 1;
                icr.partyId      = barcodeParty;
                icr.regionId     = barcodeRegion;

                List<MarkingResult> marked = new ArrayList<>();
                for (MarkingResult mr : marks)
                    if (mr.marked) marked.add(mr);

                // Overvote check only applies to first-past-the-post contest types
                // where each candidate has exactly ONE indicator box.
                // RANKED_CHOICE has multiple indicator boxes per candidate (one per rank),
                // so overvote logic is not meaningful at the raw indicator level.
                // We also skip the check if contestType was not present in the YAML
                // (defaults to PLURALITY) since we cannot reliably distinguish between
                // "explicitly PLURALITY" and "defaulted to PLURALITY".
                // A contest is only considered overvo​ted when:
                //   - contestType is PLURALITY or MEASURE  AND
                //   - maxVotes > 0  AND
                //   - The number of UNIQUE candidate IDs marked exceeds maxVotes
                //     (using unique IDs avoids false positives from ranked-choice
                //      multi-box indicators that share a candidateId)
                boolean fptp = "PLURALITY".equals(icr.contestType)
                            || "MEASURE".equals(icr.contestType);
                if (fptp && icr.maxVotes > 0) {
                    // Count unique candidate IDs that are marked
                    long uniqueMarked = marked.stream()
                        .map(m -> m.candidateId)
                        .distinct()
                        .count();
                    if (uniqueMarked > icr.maxVotes) {
                        icr.overvoted = true;
                        log.warn(
                            "OVERVOTE: {} / \"{}\" — {} unique candidates marked, max {}",
                            sr.imageName, icr.contestTitle,
                            uniqueMarked, icr.maxVotes);
                    } else {
                        for (MarkingResult mr : marked)
                            icr.markedCandidates.add(
                                mr.candidateName != null ? mr.candidateName : "(unknown)");
                    }
                } else {
                    for (MarkingResult mr : marked)
                        icr.markedCandidates.add(
                            mr.candidateName != null ? mr.candidateName : "(unknown)");
                }
                out.add(icr);
            }
        }
        return out;
    }

    // ── Correct session tallies (move overvoted votes to overvoteTallies) ───────

    /**
     * For every contest/image pair flagged as overvoted, move those votes from
     * session.tallies into session.overvoteTallies so the live report shows
     * only valid votes.
     */
    private void correctSessionTallies(List<ImageContestResult> icResults,
                                        ScanSession session) {
        if (session == null) return;

        // Collect overvoted (contestTitle, imageName) pairs
        for (ImageContestResult r : icResults) {
            if (!r.overvoted) continue;
            session.overvotedKeys.add(r.contestTitle + "|" + r.imageName);
        }

        if (session.overvotedKeys.isEmpty()) return;

        // We need to know which candidate markings came from overvoted images.
        // Walk session.results; for each overvoted image+contest, move the
        // candidate tallies from tallies → overvoteTallies.
        for (ScanResult sr : session.results) {
            if (sr.markings == null) continue;

            // Group markings by contest for this image
            Map<String, List<MarkingResult>> byContest = new LinkedHashMap<>();
            for (MarkingResult mr : sr.markings) {
                String ct = mr.contestTitle != null ? mr.contestTitle : "";
                byContest.computeIfAbsent(ct, k -> new ArrayList<>()).add(mr);
            }

            for (Map.Entry<String, List<MarkingResult>> e : byContest.entrySet()) {
                String ovKey = e.getKey() + "|" + sr.imageName;
                if (!session.overvotedKeys.contains(ovKey)) continue;

                // Move each marked candidate's tally entry to overvoteTallies
                for (MarkingResult mr : e.getValue()) {
                    if (!mr.marked) continue;
                    String tallyKey = mr.contestTitle + "|" + mr.candidateName;

                    CandidateTally src = session.tallies.get(tallyKey);
                    if (src == null) continue;

                    // Decrement from main tally
                    src.voteCount = Math.max(0, src.voteCount - 1);

                    // Add to overvote tally
                    CandidateTally ov = session.overvoteTallies.computeIfAbsent(
                        tallyKey, k -> {
                            CandidateTally ct2 = new CandidateTally();
                            ct2.contestTitle  = mr.contestTitle;
                            ct2.candidateId   = mr.candidateId;
                            ct2.candidateName = mr.candidateName;
                            ct2.voteCount     = 0;
                            ct2.ballotsChecked = 0;
                            return ct2;
                        });
                    ov.voteCount++;
                    ov.ballotsChecked++;
                }
            }
        }
        log.info("Session tallies corrected for" + session.overvotedKeys.size()
            + " overvoted contest/image pair(s).");
    }

    // ── Overvote report ───────────────────────────────────────────────────────

    private void writeOvervoteReport(List<ImageContestResult> icResults) {
        List<ImageContestResult> overvotes = icResults.stream()
            .filter(r -> r.overvoted).toList();
        Path out = Paths.get(reportOutputDir, "overvote_report.txt");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {
            pw.println("OVERVOTE REPORT");
            pw.println("===============");
            pw.println();
            if (overvotes.isEmpty()) {
                pw.println("No overvotes detected.");
            } else {
                pw.printf("Total overvoted ballot/contest pairs: %d%n%n", overvotes.size());
                for (ImageContestResult r : overvotes) {
                    pw.printf("Image  : %s%n", r.imageName);
                    pw.printf("Contest: %s (id=%d)%n", r.contestTitle, r.contestId);
                    pw.printf("Type   : %s  Max votes: %d%n%n", r.contestType, r.maxVotes);
                }
            }
        } catch (IOException ex) {
            log.warn("Could not write overvote_report.txt:" + ex.getMessage());
            return;
        }
        log.info("Overvote report →" + out.toAbsolutePath());
    }

    // ── Vote summary YAML ─────────────────────────────────────────────────────

    private void writeVoteSummary(List<ImageContestResult> icResults, ScanSession session) {
        // ── Aggregate data structures ─────────────────────────────────────────
        // Keys: contestId → ...
        Map<Long, Map<String, Object>>            meta         = new LinkedHashMap<>();
        Map<Long, Map<String, Integer>>           totalTally   = new LinkedHashMap<>();
        Map<Long, Integer>                        overvoteCt   = new LinkedHashMap<>();
        Map<Long, List<String>>                   imageDetail  = new LinkedHashMap<>();
        // Per-party:   contestId → partyId → candidateName → count
        Map<Long, Map<String, Map<String,Integer>>> partyTally  = new LinkedHashMap<>();
        // Per-region:  contestId → regionId → candidateName → count
        Map<Long, Map<String, Map<String,Integer>>> regionTally = new LinkedHashMap<>();

        for (ImageContestResult r : icResults) {
            meta.computeIfAbsent(r.contestId, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title",       r.contestTitle);
                m.put("contestType", r.contestType);
                m.put("maxVotes",    r.maxVotes);
                return m;
            });

            if (r.overvoted) {
                overvoteCt.merge(r.contestId, 1, Integer::sum);
                imageDetail.computeIfAbsent(r.contestId, k -> new ArrayList<>())
                    .add(r.imageName + " [OVERVOTE]");
            } else {
                // Overall tally
                Map<String,Integer> tt = totalTally.computeIfAbsent(
                    r.contestId, k -> new LinkedHashMap<>());
                for (String c : r.markedCandidates) tt.merge(c, 1, Integer::sum);

                // Per-party tally
                Map<String,Map<String,Integer>> pt = partyTally.computeIfAbsent(
                    r.contestId, k -> new LinkedHashMap<>());
                Map<String,Integer> ptInner = pt.computeIfAbsent(
                    r.partyId, k -> new LinkedHashMap<>());
                for (String c : r.markedCandidates) ptInner.merge(c, 1, Integer::sum);

                // Per-region tally
                Map<String,Map<String,Integer>> rt = regionTally.computeIfAbsent(
                    r.contestId, k -> new LinkedHashMap<>());
                Map<String,Integer> rtInner = rt.computeIfAbsent(
                    r.regionId, k -> new LinkedHashMap<>());
                for (String c : r.markedCandidates) rtInner.merge(c, 1, Integer::sum);

                if (!r.markedCandidates.isEmpty())
                    imageDetail.computeIfAbsent(r.contestId, k -> new ArrayList<>())
                        .add(r.imageName + " (party=" + r.partyId
                            + " region=" + r.regionId + "): "
                            + String.join(", ", r.markedCandidates));
            }
        }

        Path out = Paths.get(currentImageFolder, "vote_summary.yaml");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {
            pw.println("# Vote Summary");
            pw.println("# Barcode fields: jurisdictionId|regionId|partyId|ballotTypeId|electionId|page");
            pw.println("contests:");
            for (Long cid : meta.keySet()) {
                Map<String, Object> m = meta.get(cid);
                pw.printf("- id: %d%n", cid);
                pw.printf("  title: \"%s\"%n", esc(m.get("title").toString()));
                pw.printf("  contestType: %s%n", m.get("contestType"));
                pw.printf("  maxVotes: %d%n", m.get("maxVotes"));
                pw.printf("  overvotedBallots: %d%n", overvoteCt.getOrDefault(cid, 0));

                // Overall totals
                pw.println("  totalVotes:");
                writeTally(pw, totalTally.getOrDefault(cid, Map.of()), "    ");

                // Overvoted votes (not counted, shown separately)
                if (session != null && !session.overvoteTallies.isEmpty()) {
                    // Collect entries for this contest
                    Map<String,Integer> ovTally = new LinkedHashMap<>();
                    for (Map.Entry<String,CandidateTally> oe
                            : session.overvoteTallies.entrySet()) {
                        CandidateTally ct = oe.getValue();
                        if (ct.contestTitle != null
                                && ct.contestTitle.equals(m.get("title"))
                                && ct.voteCount > 0) {
                            ovTally.put(ct.candidateName, ct.voteCount);
                        }
                    }
                    if (!ovTally.isEmpty()) {
                        pw.println("  overvotedChoices:");
                        writeTally(pw, ovTally, "    ");
                    }
                }

                // Per-party breakdown
                Map<String,Map<String,Integer>> pt = partyTally.getOrDefault(cid, Map.of());
                if (!pt.isEmpty()) {
                    pw.println("  byParty:");
                    for (Map.Entry<String,Map<String,Integer>> pe : pt.entrySet()) {
                        pw.printf("    - partyId: %s%n", pe.getKey());
                        pw.println("      votes:");
                        writeTally(pw, pe.getValue(), "        ");
                    }
                }

                // Per-region (precinct) breakdown
                Map<String,Map<String,Integer>> rt = regionTally.getOrDefault(cid, Map.of());
                if (!rt.isEmpty()) {
                    pw.println("  byRegion:");
                    for (Map.Entry<String,Map<String,Integer>> re : rt.entrySet()) {
                        pw.printf("    - regionId: %s%n", re.getKey());
                        pw.println("      votes:");
                        writeTally(pw, re.getValue(), "        ");
                    }
                }

                // Per-image detail
                List<String> imgs = imageDetail.getOrDefault(cid, List.of());
                if (!imgs.isEmpty()) {
                    pw.println("  imageDetail:");
                    for (String line : imgs)
                        pw.printf("    - \"%s\"%n", esc(line));
                }
            }
        } catch (IOException ex) {
            log.warn("Could not write vote_summary.yaml:" + ex.getMessage());
            return;
        }
        log.info("Vote summary →" + out.toAbsolutePath());
    }

    // ── DB-driven overvote report ────────────────────────────────────────────

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    private void writeDbOvervoteReport() throws Exception {
        // Query: ballot images with overvoted vote_opportunity rows
        List<Object[]> rows = resultsQuery.overvotedPairs();

        Path out = Paths.get(reportOutputDir, "overvote_report.txt");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {
            pw.println("OVERVOTE REPORT");
            pw.println("===============");
            pw.println();
            if (rows.isEmpty()) {
                pw.println("No overvotes detected.");
            } else {
                pw.printf("Total overvoted ballot/contest pairs: %d%n%n", rows.size());
                for (Object[] row : rows) {
                    Object[] r = row;
                    pw.printf("Image  : %s%n",   r[0]);
                    pw.printf("Contest: %s (id=%s)%n", r[1], r[2]);
                    pw.printf("Type   : %s  Max votes: %s%n%n", r[3], r[4]);
                }
            }
        }
        log.info("Overvote report written: {}", out.toAbsolutePath());
    }

    // ── DB-driven final results report ───────────────────────────────────────

    /**
     * Writes results_report.html using data from the DB via ResultsQueryService.
     * Used at end of scan to ensure ALL passes are included.
     */
    private void writeDbResultsReport(ScanSession session) throws Exception {
        List<ResultsQueryService.VoteRow> rows = resultsQuery.votesByContest();
        if (rows.isEmpty()) return;

        // Group by contest
        java.util.Map<String, java.util.Map<String, Long>> byContest =
            new java.util.LinkedHashMap<>();
        for (ResultsQueryService.VoteRow r : rows) {
            byContest.computeIfAbsent(r.contest, k -> new java.util.LinkedHashMap<>())
                .merge(r.candidate, r.voted + r.overvoted, Long::sum);
        }

        // Re-use writeResultsReport by building synthetic ImageContestResult list
        // Actually simpler: just overwrite the file with a DB-sourced version
        java.nio.file.Path out = java.nio.file.Paths.get(reportOutputDir, "results_report.html");
        String ts = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy  h:mm a"));
        long totalBallots = resultsQuery.totalBallotImages();

        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                java.nio.file.Files.newBufferedWriter(out))) {
            pw.println("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">");
            pw.println("<title>bCounter Results Report</title>");
            pw.println("<style>");
            pw.println("body{font-family:Arial,sans-serif;font-size:11pt;max-width:900px;margin:2rem auto;color:#111}");
            pw.println("h1{font-size:16pt;border-bottom:3px solid #1a2744;padding-bottom:.4rem;color:#1a2744}");
            pw.println(".meta{color:#555;font-size:10pt;margin:.5rem 0 1.5rem}");
            pw.println("h2{font-size:13pt;margin:2rem 0 .2rem;color:#1a2744}");
            pw.println("table{border-collapse:collapse;width:100%;margin-bottom:1rem}");
            pw.println("th{text-align:left;background:#1a2744;color:#fff;padding:.3rem .6rem;font-size:10pt}");
            pw.println("th.r{text-align:right}td{padding:.25rem .6rem;border-bottom:1px solid #ddd;font-size:10.5pt}");
            pw.println("td.r{text-align:right;font-variant-numeric:tabular-nums}");
            pw.println("tr.leader td{background:#eff6ff;font-weight:bold}");
            pw.println("@media print{body{margin:1cm}}");
            pw.println("</style></head><body>");
            pw.printf("<h1>bCounter Results Report</h1>%n");
            pw.printf("<p class=\"meta\">Generated: %s"
                + " &nbsp;|&nbsp; Ballots scanned: %,d"
                + " &nbsp;|&nbsp; Image folder: %s</p>%n",
                ts, totalBallots,
                session != null ? session.imageFolder : "");

            for (java.util.Map.Entry<String, java.util.Map<String, Long>> ce
                    : byContest.entrySet()) {
                pw.printf("<h2>%s</h2>%n", ce.getKey()
                    .replace("&","&amp;").replace("<","&lt;"));
                pw.println("<table><tr><th>Candidate / Option</th>"
                    + "<th class=\"r\">Votes / Marks</th></tr>");
                long maxVotes = ce.getValue().values().stream()
                    .max(Long::compare).orElse(0L);
                for (java.util.Map.Entry<String, Long> cand : ce.getValue().entrySet()) {
                    boolean leader = cand.getValue() == maxVotes && maxVotes > 0;
                    pw.printf("<tr%s><td>%s</td><td class=\"r\">%,d</td></tr>%n",
                        leader ? " class=\"leader\"" : "",
                        cand.getKey().replace("&","&amp;").replace("<","&lt;"),
                        cand.getValue());
                }
                pw.println("</table>");
            }
            pw.println("</body></html>");
        }
        log.info("Final results_report.html written from DB: {}", out.toAbsolutePath());
    }

    // ── Printable HTML results report ────────────────────────────────────────

    /**
     * Writes results_report.html to the working directory (where bCounter
     * was launched from).  The file is a self-contained printable HTML page
     * showing vote totals by contest, matching what /results displays.
     */
    private void writeResultsReport(List<ImageContestResult> icResults,
                                     ScanSession session) {
        // Aggregate: contestTitle → candidateName → vote count
        java.util.Map<String, java.util.Map<String,Integer>> totals =
            new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> overvotes = new java.util.LinkedHashMap<>();
        int totalBallots = 0;
        java.util.Set<String> seenImages = new java.util.HashSet<>();

        for (ImageContestResult r : icResults) {
            if (seenImages.add(r.imageName)) totalBallots++;
            java.util.Map<String,Integer> ct = totals.computeIfAbsent(
                r.contestTitle, k -> new java.util.LinkedHashMap<>());
            if (r.overvoted) {
                overvotes.merge(r.contestTitle, 1, Integer::sum);
            } else {
                for (String c : r.markedCandidates) ct.merge(c, 1, Integer::sum);
            }
        }

        Path out = Paths.get(reportOutputDir, "results_report.html");
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String ts = now.format(java.time.format.DateTimeFormatter
            .ofPattern("MMMM d, yyyy  h:mm a"));

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {
            pw.println("<!DOCTYPE html>");
            pw.println("<html lang=\"en\"><head><meta charset=\"UTF-8\">");
            pw.println("<title>bCounter Results Report</title>");
            pw.println("<style>");
            pw.println("  body { font-family: Arial, sans-serif; font-size: 11pt;");
            pw.println("         max-width: 900px; margin: 2rem auto; color: #111; }");
            pw.println("  h1   { font-size: 16pt; border-bottom: 2px solid #1a2744;");
            pw.println("         padding-bottom: .4rem; color: #1a2744; }");
            pw.println("  h2   { font-size: 12pt; margin: 1.5rem 0 .3rem;");
            pw.println("         color: #1a2744; }");
            pw.println("  table { border-collapse: collapse; width: 100%;");
            pw.println("          margin-bottom: 1rem; }");
            pw.println("  th   { text-align: left; background: #1a2744; color: #fff;");
            pw.println("         padding: .3rem .6rem; font-size: 10pt; }");
            pw.println("  td   { padding: .25rem .6rem; border-bottom: 1px solid #ddd; }");
            pw.println("  tr:last-child td { border-bottom: 2px solid #1a2744; }");
            pw.println("  .num { text-align: right; }");
            pw.println("  .ov  { color: #b45309; font-size: 9pt; }");
            pw.println("  .meta { color: #555; font-size: 10pt; margin-bottom: 1.5rem; }");
            pw.println("  @media print { body { margin: 1cm; } }");
            pw.println("</style></head><body>");

            pw.printf("<h1>bCounter Results Report</h1>%n");
            pw.printf("<p class=\"meta\">Generated: %s &nbsp;|&nbsp; " +
                      "Ballots scanned: %d &nbsp;|&nbsp; " +
                      "Image folder: %s</p>%n",
                h(ts), totalBallots, h(currentImageFolder));

            for (java.util.Map.Entry<String, java.util.Map<String,Integer>> ce
                    : totals.entrySet()) {
                String contest = ce.getKey();
                java.util.Map<String,Integer> cands = ce.getValue();
                int ov = overvotes.getOrDefault(contest, 0);

                pw.printf("<h2>%s</h2>%n", h(contest));
                pw.println("<table>");
                pw.println("  <tr><th>Candidate / Option</th>" +
                           "<th class=\"num\">Votes / Marks</th></tr>");

                cands.entrySet().stream()
                    .sorted(java.util.Map.Entry.<String,Integer>comparingByValue()
                        .reversed())
                    .forEach(e -> pw.printf(
                        "  <tr><td>%s</td><td class=\"num\">%,d</td></tr>%n",
                        h(e.getKey()), e.getValue()));

                if (ov > 0) {
                    pw.printf("  <tr><td class=\"ov\">Overvoted ballots</td>" +
                              "<td class=\"num ov\">%d</td></tr>%n", ov);
                }
                pw.println("</table>");
            }

            pw.println("</body></html>");
            log.info("Results report written: " + out.toAbsolutePath());
        } catch (Exception ex) {
            log.warn("Could not write results_report.html: " + ex.getMessage());
        }
    }

    private static String h(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void writeTally(PrintWriter pw, Map<String,Integer> tally, String indent) {
        if (tally.isEmpty()) {
            pw.println(indent + "# (no valid votes)");
        } else {
            tally.entrySet().stream()
                .sorted(Map.Entry.<String,Integer>comparingByValue().reversed())
                .forEach(e -> pw.printf("%s\"%s\": %d%n",
                    indent, esc(e.getKey()), e.getValue()));
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Write-in image extraction ─────────────────────────────────────────────

    private void extractWriteIns(List<ScanResult> results, String imageFolder) {
        for (ScanResult sr : results) {
            if (sr.markings == null) continue;

            // Collect write-in indicators where the marker oval is genuinely filled.
            // Use a higher threshold (20%) than the standard darkPctMin to avoid
            // extracting images for blank write-in slots whose oval border alone
            // contributes ~8-9% darkness without any voter marking.
            final double WRITE_IN_DARK_THRESHOLD = 20.0;
            Map<Long, List<MarkingResult>> writeIns = new LinkedHashMap<>();
            for (MarkingResult mr : sr.markings) {
                if (mr.writeIn && mr.darkPct >= WRITE_IN_DARK_THRESHOLD) {
                    Long key = mr.contestId != null ? mr.contestId : -1L;
                    writeIns.computeIfAbsent(key, k -> new ArrayList<>()).add(mr);
                }
            }
            if (writeIns.isEmpty()) continue;

            Path imgPath = Paths.get(imageFolder, sr.imageName);
            if (!Files.exists(imgPath)) {
                log.warn("Write-in: image not found:" + imgPath);
                continue;
            }
            BufferedImage img;
            try { img = ImageIO.read(imgPath.toFile()); }
            catch (IOException ex) {
                log.warn("Write-in: cannot read" + imgPath + ": " + ex.getMessage());
                continue;
            }
            if (img == null) continue;

            String ext  = getExtension(sr.imageName);
            String stem = sr.imageName.contains(".")
                ? sr.imageName.substring(0, sr.imageName.lastIndexOf('.'))
                : sr.imageName;
            int dpi = sr.imageDpi > 0 ? (int)sr.imageDpi : 300;

            for (Map.Entry<Long, List<MarkingResult>> e : writeIns.entrySet()) {
                long cid = e.getKey();
                List<MarkingResult> wis = e.getValue();
                for (int wi = 0; wi < wis.size(); wi++) {
                    String suffix = wis.size() > 1 ? "_" + (wi + 1) : "";
                    String outName = Paths.get(currentImageFolder,
                        "writein_" + stem + "_" + cid + suffix + "." + ext).toString();
                    saveWriteInRegion(img, wis.get(wi), dpi, outName, ext);
                }
            }
        }
    }

    private void saveWriteInRegion(BufferedImage img, MarkingResult mr,
                                   int dpi, String outName, String ext) {
        // Capture the write-in area: start at the left of the oval, extend 3" to
        // the right to include the write-in fill line, and capture 0.6" of height
        // starting 2px ABOVE the indicator top so the full candidate row is visible.
        int margin = Math.max(2, dpi / 150);   // ~2px above oval top
        int x       = Math.max(0, mr.imageX);
        int y       = Math.max(0, mr.imageY - margin);
        int regionW = Math.min((int)(3.0 * dpi), img.getWidth()  - x);
        int regionH = Math.min((int)(0.6 * dpi), img.getHeight() - y);
        if (regionW <= 0 || regionH <= 0) return;
        try {
            BufferedImage crop = img.getSubimage(x, y, regionW, regionH);
            String fmt = "jpeg".equals(ext) || "jpg".equals(ext) ? "JPEG" : "PNG";
            ImageIO.write(crop, fmt, new java.io.File(outName));
            log.info("Write-in image saved:" + outName);
        } catch (Exception ex) {
            log.warn("Write-in image failed (" + outName + "): " + ex.getMessage());
        }
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "png";
    }
}
