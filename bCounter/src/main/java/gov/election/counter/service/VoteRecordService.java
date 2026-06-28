/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.counter.service;

import gov.election.counter.entity.*;
import gov.election.counter.service.HomographyService;
import gov.election.counter.service.CornerDetectionService.Point2D;
import gov.election.counter.entity.VoteOpportunity.VoteStatus;
import gov.election.counter.model.BboxReport.*;
import gov.election.counter.model.ScanSession;
import gov.election.counter.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists each ballot image's scan results to the database immediately
 * after it is scanned, then releases the data from memory.
 *
 * This keeps the counter's memory footprint O(1) regardless of how many
 * images are processed, supporting millions of vote opportunities.
 *
 * Also handles:
 *  - Walking subdirectories to find images (tree mode)
 *  - Renaming processed images with a ".counted" extension
 *  - Overvote detection and status assignment per FPTP rules
 */
@Service
public class VoteRecordService {

    /** Result of a persist() call — used by ScanController to decide on retry. */
    public enum PersistStatus {
        /** Ballot saved successfully. */
        SAVED,
        /** Ballot skipped — parallel race condition; safe to retry after delay. */
        RACE_SKIP,
        /** Ballot is a true duplicate (already in DB from a prior run). */
        TRUE_DUPLICATE,
        /** Unexpected error — logged separately. */
        ERROR
    }

    private static final Logger log =
        LoggerFactory.getLogger(VoteRecordService.class);

    private final TransactionTemplate          txTemplate;
    private final HomographyService          homographyService;
    private final BarcodeRecordRepository   barcodeRepo;
    private final BallotImageRepository     imageRepo;
    private final ContestRecordRepository   contestRepo;
    private final CandidateRecordRepository candidateRepo;
    private final VoteOpportunityRepository voteRepo;

    public VoteRecordService(PlatformTransactionManager txManager,
                              HomographyService homographyService,
                              BarcodeRecordRepository barcodeRepo,
                              BallotImageRepository imageRepo,
                              ContestRecordRepository contestRepo,
                              CandidateRecordRepository candidateRepo,
                              VoteOpportunityRepository voteRepo) {
        this.txTemplate        = new TransactionTemplate(txManager);
        this.homographyService = homographyService;
        this.barcodeRepo   = barcodeRepo;
        this.imageRepo     = imageRepo;
        this.contestRepo   = contestRepo;
        this.candidateRepo = candidateRepo;
        this.voteRepo      = voteRepo;
    }

    /**
     * Persist one completed ScanResult to the database.
     * Called immediately after each image is scanned so results can be
     * discarded from the ScanSession to keep memory usage flat.
     *
     * @param result    the completed scan result
     * @param imagePath full path of the image file
     * @param threshold darkness threshold used for this scan
     */
    public PersistStatus persist(ScanResult result, Path imagePath, int threshold,
                         CornerDetectionService.Point2D[] corners,
                         double contentAreaWidth, double contentAreaHeight,
                         int warpDpi, ScanSession session) {
        if (result == null) return PersistStatus.ERROR;

        // Wrap entire persist in TransactionTemplate so @Transactional works
        // from background threads not managed by Spring's own executor.
        PersistStatus[] statusHolder = { PersistStatus.SAVED };
        txTemplate.execute(txStatus -> {
            statusHolder[0] = doPersist(result, imagePath, threshold, corners,
                contentAreaWidth, contentAreaHeight, warpDpi, session);
            return null;
        });
        return statusHolder[0];
    }

    private PersistStatus doPersist(ScanResult result, Path imagePath, int threshold,
                            CornerDetectionService.Point2D[] corners,
                            double contentAreaWidth, double contentAreaHeight,
                            int warpDpi, ScanSession session) {

        // ── Barcode ───────────────────────────────────────────────────────────
        String rawBarcode = result.barcodeData != null ? result.barcodeData : "";
        BarcodeRecord barcode = barcodeRepo.findByRawData(rawBarcode)
            .orElseGet(() -> {
                BarcodeRecord b = new BarcodeRecord();
                b.parseFromRaw(rawBarcode);
                return barcodeRepo.save(b);
            });

        // ── Ballot image — atomic insert with duplicate detection ───────────
        // Build the entity first, then attempt a single INSERT.
        // If the unique constraint on image_path fires (parallel worker race),
        // we catch the exception, log it, and skip — no silent data loss.
        String absPath = imagePath.toAbsolutePath().toString();

        BallotImage img = new BallotImage();
        img.setImagePath(absPath);
        img.setImageName(imagePath.getFileName().toString());
        img.setCountedAt(LocalDateTime.now());
        img.setDpi(result.imageDpi > 0 ? (int)result.imageDpi : 300);
        img.setPageNumber(result.pageNumber);
        img.setCornersFound(result.cornersFound);
        img.setWasRotated(result.wasRotated);
        img.setWarpDpi(warpDpi);
        img.setCanonicalWidth((int)Math.round(contentAreaWidth  * warpDpi));
        img.setCanonicalHeight((int)Math.round(contentAreaHeight * warpDpi));
        img.setBarcode(barcode);
        if (corners != null && corners.length == 4) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (i > 0) sb.append(',');
                sb.append(String.format("%.2f,%.2f", corners[i].x(), corners[i].y()));
            }
            img.setCornerMarks(sb.toString());
        }

        BallotImage image;
        try {
            image = imageRepo.save(img);
        } catch (Exception ex) {
            // Unique constraint violation: another thread already inserted this path.
            // Check if it was truly a duplicate or an unexpected error.
            java.util.Optional<BallotImage> existing = imageRepo.findByImagePath(absPath);
            if (existing.isPresent()) {
                log.warn("Duplicate ballot skipped (parallel race) — already in database: "
                    + absPath);
                if (session != null) session.duplicatePaths.add(absPath);
                return PersistStatus.RACE_SKIP;
            } else {
                log.error("Unexpected DB error saving ballot image " + absPath
                    + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                return PersistStatus.ERROR;
            }
        }

        if (result.markings == null || result.markings.isEmpty()) return PersistStatus.SAVED;

        // ── Determine overvoted contests ──────────────────────────────────────
        // Group marked indicators by contestId; apply FPTP overvote rule
        Map<Long, List<MarkingResult>> byContest = new LinkedHashMap<>();
        for (MarkingResult mr : result.markings) {
            Long key = mr.contestId != null ? mr.contestId : -1L;
            byContest.computeIfAbsent(key, k -> new ArrayList<>()).add(mr);
        }

        Set<Long> overvotedContestIds = new HashSet<>();
        // For RCV: track which rank positions have more than one marked candidate.
        // Key: contestId → set of overvoted rank numbers.
        // A candidate name like "Alexandria Washington (Rank 1)" encodes the rank.
        Map<Long, Set<Integer>> rcvOvervotedRanks = new HashMap<>();

        for (Map.Entry<Long, List<MarkingResult>> e : byContest.entrySet()) {
            MarkingResult first = e.getValue().get(0);
            String cType = first.contestType != null ? first.contestType : "PLURALITY";
            Long contestKey = e.getKey();

            if ("RANKED_CHOICE".equals(cType)) {
                // Detect duplicate rank positions among marked candidates
                Map<Integer, Integer> rankCount = new HashMap<>();
                java.util.regex.Pattern rankPat =
                    java.util.regex.Pattern.compile("\\(Rank (\\d+)\\)$");
                for (MarkingResult mr : e.getValue()) {
                    if (!mr.marked) continue;
                    java.util.regex.Matcher m = rankPat.matcher(
                        mr.candidateName != null ? mr.candidateName : "");
                    if (m.find()) {
                        int rank = Integer.parseInt(m.group(1));
                        rankCount.merge(rank, 1, Integer::sum);
                    }
                }
                Set<Integer> badRanks = new HashSet<>();
                for (Map.Entry<Integer, Integer> re : rankCount.entrySet()) {
                    if (re.getValue() > 1) {
                        badRanks.add(re.getKey());
                        log.warn("RCV overvote on contest {} image {}: "
                            + "rank {} marked by {} candidates",
                            contestKey, result.imageName, re.getKey(), re.getValue());
                    }
                }
                if (!badRanks.isEmpty()) {
                    rcvOvervotedRanks.put(contestKey, badRanks);
                    overvotedContestIds.add(contestKey);
                }
            } else {
                // FPTP / MEASURE / APPROVAL overvote check
                boolean fptp = "PLURALITY".equals(cType) || "MEASURE".equals(cType);
                if (!fptp) continue;
                int maxVotes = first.maxVotes > 0 ? first.maxVotes : 1;
                long uniqueMarked = e.getValue().stream()
                    .filter(m -> m.marked)
                    .map(m -> m.candidateId)
                    .distinct().count();
                if (uniqueMarked > maxVotes)
                    overvotedContestIds.add(contestKey);
            }
        }

        // ── Persist each indicator ────────────────────────────────────────────
        for (MarkingResult mr : result.markings) {
            // Find or create contest record
            String cType = mr.contestType != null ? mr.contestType : "PLURALITY";
            String cTitle = mr.contestTitle != null ? mr.contestTitle : "(unknown)";
            ContestRecord contest = contestRepo
                .findByContestTitleAndContestType(cTitle, cType)
                .orElseGet(() -> {
                    ContestRecord c = new ContestRecord();
                    c.setContestTitle(cTitle);
                    c.setContestType(cType);
                    c.setMaxVotes(mr.maxVotes);
                    return contestRepo.save(c);
                });

            // Determine status
            // Only physically marked candidates get OVERVOTED status in an overvoted
            // contest — unmarked candidates in the same contest stay UNMARKED since
            // they were not actually filled in by the voter.
            Long contestKey = mr.contestId != null ? mr.contestId : -1L;
            VoteStatus status;
            if (overvotedContestIds.contains(contestKey) && mr.marked) {
                // For RCV: only mark OVERVOTED if this candidate is at an overvoted rank
                Set<Integer> badRanks = rcvOvervotedRanks.get(contestKey);
                if (badRanks != null) {
                    // Extract rank from candidate name
                    java.util.regex.Matcher rm = java.util.regex.Pattern
                        .compile("\\(Rank (\\d+)\\)$")
                        .matcher(mr.candidateName != null ? mr.candidateName : "");
                    int rank = rm.find() ? Integer.parseInt(rm.group(1)) : -1;
                    status = (rank > 0 && badRanks.contains(rank))
                        ? VoteStatus.OVERVOTED : VoteStatus.VOTED;
                } else {
                    status = VoteStatus.OVERVOTED;
                }
            } else if (mr.marked) {
                status = VoteStatus.VOTED;
            } else {
                status = VoteStatus.UNMARKED;
            }

            // Find or create CandidateRecord for this contest+name
            String cName = mr.candidateName != null ? mr.candidateName : "(unknown)";
            ContestRecord finalContest = contest;
            CandidateRecord candidate = candidateRepo
                .findByContestAndCandidateName(finalContest, cName)
                .orElseGet(() -> {
                    CandidateRecord c = new CandidateRecord();
                    c.setContest(finalContest);
                    c.setCandidateName(cName);
                    c.setBallotCandidateId(mr.candidateId);
                    c.setWriteIn(mr.writeIn);
                    return candidateRepo.save(c);
                });

            VoteOpportunity vo = new VoteOpportunity();
            vo.setBallotImage(image);
            vo.setContest(contest);
            vo.setCandidate(candidate);
            vo.setAbsLeft(mr.absLeft);
            vo.setAbsTop(mr.absTop);
            vo.setIndicatorWidth(mr.width);
            vo.setIndicatorHeight(mr.height);
            vo.setWarpDpi(warpDpi);
            vo.setImageX(mr.imageX);
            vo.setImageY(mr.imageY);
            vo.setThreshold(threshold);
            vo.setDarkPct(mr.darkPct);
            vo.setDarkPixels(mr.darkPixels);
            vo.setTotalPixels(mr.totalPixels);
            vo.setMeanIntensity(mr.meanIntensity);
            vo.setVoteStatus(status);
            voteRepo.save(vo);
        }

        // ── Rename image to mark it as counted ────────────────────────────────
        try {
            Path counted = imagePath.resolveSibling(
                imagePath.getFileName().toString() + ".counted");
            Files.move(imagePath, counted, StandardCopyOption.ATOMIC_MOVE);
            log.info("Renamed to: " + counted.getFileName());
        } catch (Exception ex) {
            log.warn("Could not rename " + imagePath.getFileName()
                + " — continuing: " + ex.getMessage());
        }
        return PersistStatus.SAVED;
    }

    /**
     * Walk a folder tree recursively and collect all image files,
     * skipping any that already have a ".counted" extension.
     */
    public List<Path> findImagesRecursive(Path root) throws Exception {
        List<Path> images = new ArrayList<>();
        Set<String> exts = Set.of(".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp");
        Files.walk(root)
            .filter(Files::isRegularFile)
            .filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                if (name.endsWith(".counted")) return false;
                if (name.startsWith("writein_")) return false;
                int dot = name.lastIndexOf('.');
                return dot >= 0 && exts.contains(name.substring(dot));
            })
            .sorted()
            .forEach(images::add);
        return images;
    }

    /**
     * Delete all scan data from the database for a clean new-election start.
     * Deletes in FK-safe order: VoteOpportunity → BallotImage → BarcodeRecord
     * → CandidateRecord → ContestRecord.
     */
    @org.springframework.transaction.annotation.Transactional
    public void clearAllData() {
        voteRepo.deleteAll();
        imageRepo.deleteAll();
        barcodeRepo.deleteAll();
        candidateRepo.deleteAll();
        contestRepo.deleteAll();
        log.info("All election data cleared for new election.");
    }
}
