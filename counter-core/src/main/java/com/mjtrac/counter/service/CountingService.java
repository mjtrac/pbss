/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 *
 * Extracted from the removed CountController's private runScanLoop()/
 * runScanPass() methods, which is where this logic actually lived in the web
 * version — unlike the rest of the service layer (which was already
 * UI-agnostic), the counting workflow's real business logic was embedded
 * directly in a @Controller. This is a faithful, near-verbatim port: the
 * parallel-worker pool, retry/ConcurrentModificationException handling,
 * multi-pass rescanning, and periodic-report logic are unchanged. Two
 * deliberate differences from the original:
 *   1. The 1000-image "pauseForResults" cooperative pause existed so a
 *      browser polling /progress could show a milestone results page. A
 *      JavaFX Timeline-polled progress panel doesn't need that — it's always
 *      live — so the FX progress screen clears pauseForResults immediately
 *      instead of the browser needing to call POST /resume.
 *   2. username comes from AuthContext.getCurrentUser() instead of
 *      @AuthenticationPrincipal.
 */
package com.mjtrac.counter.service;

import com.mjtrac.counter.model.BboxReport;
import com.mjtrac.counter.model.BboxReport.ScanResult;
import com.mjtrac.counter.model.ScanSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CountingService {

    private static final Logger log = LoggerFactory.getLogger(CountingService.class);

    private final BboxReportLoader loader;
    private final ScannerService scanner;
    private final VoteTallyService voteTally;
    private final AuditLogService auditLog;
    private final VoteRecordService voteRecord;
    private final ScribbleDetectionService scribbleDetection;

    @Value("${scanner.max-review-before-stop:10}")
    private int maxReviewBeforeStop;

    @Value("${scanner.parallel-threads:0}")
    private int parallelThreads;

    private final Executor taskExecutor;
    private volatile ScanSession session = new ScanSession();

    public CountingService(BboxReportLoader loader, ScannerService scanner,
                            VoteTallyService voteTally, AuditLogService auditLog,
                            VoteRecordService voteRecord, ScribbleDetectionService scribbleDetection) {
        this.loader = loader;
        this.scanner = scanner;
        this.voteTally = voteTally;
        this.auditLog = auditLog;
        this.voteRecord = voteRecord;
        this.scribbleDetection = scribbleDetection;
        org.springframework.core.task.SimpleAsyncTaskExecutor ex =
            new org.springframework.core.task.SimpleAsyncTaskExecutor("scan-");
        ex.setConcurrencyLimit(1);
        this.taskExecutor = ex;
    }

    public ScanSession getSession() {
        return session;
    }

    public String getReportOutputDir() {
        return voteTally.getReportOutputDir();
    }

    private int resolvedThreads() {
        if (parallelThreads > 0) return parallelThreads;
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }

    /** Mirrors CountController's POST /start validation + report/image loading. Throws with a user-facing message on failure. */
    public void startNewSession(String imageFolder, String reportFolder, int threshold, double darkPct,
                                 int dpi, boolean debugCoordinates, String debugOutputFolder,
                                 double assumedPaperWidthIn) throws Exception {
        ScanSession s = new ScanSession();
        s.imageFolder = imageFolder != null ? imageFolder.trim() : "";
        s.reportFolder = reportFolder != null ? reportFolder.trim() : "";
        s.threshold = threshold;
        s.darkPctMin = darkPct;
        s.dpi = dpi;
        s.debugCoordinates = debugCoordinates;
        s.debugOutputFolder = debugOutputFolder != null ? debugOutputFolder.trim() : "";
        s.assumedPaperWidthIn = assumedPaperWidthIn > 0 ? assumedPaperWidthIn : 8.5;

        if (s.imageFolder.isBlank()) {
            throw new IllegalArgumentException("Image folder is required.");
        }
        Path imgDir = Paths.get(s.imageFolder);
        if (!Files.isDirectory(imgDir)) {
            throw new IllegalArgumentException("Image folder not found: " + s.imageFolder);
        }

        Path reportDir = s.reportFolder.isBlank() ? Paths.get(".") : Paths.get(s.reportFolder);
        if (!Files.isDirectory(reportDir)) {
            throw new IllegalArgumentException("Report folder not found: " + s.reportFolder);
        }

        s.layouts = loader.loadAllFromFolder(reportDir);
        Path xmlPath = loader.findXml(reportDir);
        Path yamlPath = loader.findYaml(reportDir);
        s.xmlReportPath = xmlPath != null ? xmlPath.toString() : "(none)";
        s.yamlReportPath = yamlPath != null ? yamlPath.toString() : "(none)";
        if (s.layouts.isEmpty()) {
            throw new IllegalArgumentException("Report loaded but contains no page layouts.");
        }

        s.imageQueue = voteRecord.findImagesRecursive(imgDir);
        if (s.imageQueue.isEmpty()) {
            throw new IllegalArgumentException("No image files found in: " + s.imageFolder
                + " (looking for .png, .jpg, .tif, .bmp)");
        }

        this.session = s;
    }

    public void startScan(String username) {
        if (session.scanning) {
            throw new IllegalStateException("Scan already in progress");
        }
        session.scanning = true;
        taskExecutor.execute(() -> runScanLoop(session, username));
    }

    public void resumeScan(String username) {
        if (!session.isStarted()) {
            throw new IllegalStateException("No session to resume.");
        }
        if (session.scanning) {
            return;
        }
        session.stopRequested = false;
        session.scanError = null;
        session.scanning = true;
        taskExecutor.execute(() -> runScanLoop(session, username));
    }

    public void stopScan() {
        session.stopRequested = true;
        session.pauseForResults = false;
    }

    /** Clears the milestone pause immediately — see class comment. */
    public void clearPause() {
        session.pauseForResults = false;
    }

    public void finish(String username) throws Exception {
        List<ScanResult> empty = Collections.emptyList();
        voteTally.processTally(!session.results.isEmpty() ? session.results : empty,
            session.imageFolder != null ? session.imageFolder : ".", session);

        if (!session.reviewRequired.isEmpty()) {
            Path reviewPath = Paths.get(
                session.imageFolder != null ? session.imageFolder : ".", "review_required.txt");
            Files.writeString(reviewPath, String.join("\n", session.reviewRequired) + "\n");
        }
        if (username != null) {
            auditLog.log("FINISH", username, session.processed() + " images, folder: " + session.imageFolder);
        }
    }

    public void reset() {
        this.session = new ScanSession();
    }

    public void newElection() {
        session.stopRequested = true;
        this.session = new ScanSession();
        voteRecord.clearAllData();
        scribbleDetection.clearAll();
    }

    // ── Scan loop — ported near-verbatim from CountController ──────────────────

    private void runScanLoop(ScanSession session, String username) {
        int nThreads = resolvedThreads();
        log.info("Starting parallel scan: {} worker thread(s)", nThreads);

        Set<String> allQueued = new HashSet<>();
        for (Path p : session.imageQueue) allQueued.add(p.toString());

        int passNumber = 1;
        try {
            do {
                int passStart = session.currentIndex;
                log.info("Pass {}: scanning {} image(s)", passNumber,
                    session.imageQueue.size() - (passNumber == 1 ? 0 : passStart));
                session.passNumber = passNumber;

                runScanPass(session, username);

                if (session.stopRequested) break;

                List<Path> newFiles = new ArrayList<>();
                final int currentPass = passNumber;
                try {
                    Files.walk(Paths.get(session.imageFolder))
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase();
                            if (name.startsWith("writein_")) return false;
                            return (name.endsWith(".png") || name.endsWith(".jpg")
                                || name.endsWith(".jpeg") || name.endsWith(".tif")
                                || name.endsWith(".tiff"));
                        })
                        .filter(p -> {
                            boolean isNew = !allQueued.contains(p.toString());
                            if (!isNew) {
                                log.warn("Pass {}: previously queued file still uncounted"
                                    + " — requeuing: {}", currentPass, p.getFileName());
                            }
                            return true;
                        })
                        .forEach(newFiles::add);
                } catch (Exception ex) {
                    log.warn("Pass {} folder walk failed: {}", passNumber, ex.getMessage());
                }

                if (newFiles.isEmpty()) {
                    log.info("Pass {}: no new files found — scan complete", passNumber);
                    break;
                }
                log.info("Pass {}: saving pass results snapshot before pass {}", passNumber, passNumber + 1);
                voteTally.writePassReport(session.imageFolder, session, passNumber);

                log.info("Pass {}: {} new file(s) found — starting pass {}",
                    passNumber, newFiles.size(), passNumber + 1);
                for (Path p : newFiles) {
                    session.imageQueue.add(p);
                    allQueued.add(p.toString());
                }
                passNumber++;

            } while (!session.stopRequested);

            voteTally.processTally(session.results, session.imageFolder, session);
            voteTally.processFinalTally(session.imageFolder, session);
            session.tallyDone = true;

        } catch (Exception ex) {
            log.error("Scan loop failed: " + ex.getMessage());
            session.scanError = ex.getMessage();
        } finally {
            session.scanning = false;
            if (session.stopRequested) {
                log.info("Scan stopped by user after {} images.", session.processed());
            }
            if (!session.reviewRequired.isEmpty()) {
                try {
                    Path reviewPath = Paths.get(voteTally.getReportOutputDir(), "review_required.txt");
                    Files.writeString(reviewPath, String.join("\n", session.reviewRequired) + "\n");
                    log.info("Wrote {} review-required entries to {}", session.reviewRequired.size(), reviewPath);
                } catch (Exception e) {
                    log.warn("Could not write review_required.txt: {}", e.getMessage());
                }
            }
        }
    }

    private void runScanPass(ScanSession session, String username) throws Exception {
        int nThreads = resolvedThreads();
        ExecutorService pool = Executors.newFixedThreadPool(nThreads,
            r -> { Thread t = new Thread(r, "scan-" + Thread.currentThread().getId()); t.setDaemon(true); return t; });

        int passStartIdx = session.currentIndex;
        int total = session.imageQueue.size();

        AtomicInteger nextIndex = new AtomicInteger(passStartIdx);
        ConcurrentLinkedQueue<Object[]> completedQueue = new ConcurrentLinkedQueue<>();
        List<Object[]> retryList = new ArrayList<>();
        List<Object[]> scanRetryList = Collections.synchronizedList(new ArrayList<>());

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < nThreads; t++) {
            futures.add(pool.submit(() -> {
                while (true) {
                    if (session.stopRequested) break;
                    int idx = nextIndex.getAndIncrement();
                    if (idx >= total) break;
                    Path imagePath = session.imageQueue.get(idx);
                    session.submittedCount.updateAndGet(v -> Math.max(v, idx + 1));
                    try {
                        ScanResult result = scanner.scanOne(imagePath, session);
                        completedQueue.add(new Object[]{imagePath, result, idx});
                    } catch (ConcurrentModificationException cme) {
                        log.warn("CME scanning " + imagePath.getFileName()
                            + " — queuing for re-scan after main pass", cme);
                        scanRetryList.add(new Object[]{imagePath, idx});
                        ScanResult placeholder = new ScanResult();
                        placeholder.imagePath = imagePath.toString();
                        placeholder.imageName = imagePath.getFileName().toString();
                        placeholder.errorMessage = "CME_RETRY";
                        completedQueue.add(new Object[]{imagePath, placeholder, idx});
                    } catch (Exception e) {
                        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        log.error("Worker exception on " + imagePath.getFileName()
                            + ": " + e.getClass().getName() + ": " + msg, e);
                        ScanResult err = new ScanResult();
                        err.imagePath = imagePath.toString();
                        err.imageName = imagePath.getFileName().toString();
                        err.errorMessage = "Unexpected scan error: " + msg;
                        completedQueue.add(new Object[]{imagePath, err, idx});
                    }
                }
            }));
        }

        int written = passStartIdx;
        try {
            while (written < total && !session.stopRequested) {
                Object[] item = completedQueue.poll();
                if (item == null) { Thread.sleep(5); continue; }

                Path imagePath = (Path) item[0];
                ScanResult result = (ScanResult) item[1];
                String imageName = imagePath.getFileName().toString();

                session.currentImagePath = imagePath.toString();
                session.currentIndex = ++written;
                session.passWritten = written - passStartIdx;
                session.results.add(result);
                auditLog.log("SCAN", username, imageName);

                try {
                    Point2D[] corners = cornersOf(result);
                    if (result.errorMessage != null) {
                        if ("CME_RETRY".equals(result.errorMessage)) {
                            log.debug("Writer: skipping CME placeholder for {}", imageName);
                        } else {
                            session.reviewRequired.add(imagePath.toAbsolutePath() + " — " + result.errorMessage);
                            log.warn("Flagged for review: " + imageName + " — " + result.errorMessage);
                            try {
                                Path reviewPath = imagePath.resolveSibling(imagePath.getFileName() + ".review");
                                Files.move(imagePath, reviewPath, StandardCopyOption.REPLACE_EXISTING);
                            } catch (Exception renameEx) {
                                log.warn("Could not rename to .review: {}", renameEx.getMessage());
                            }
                            if (maxReviewBeforeStop > 0 && session.reviewRequired.size() >= maxReviewBeforeStop) {
                                session.scanError = "Scan halted: " + session.reviewRequired.size()
                                    + " ballots required manual review (limit " + maxReviewBeforeStop
                                    + "). Fix the issue and rescan uncounted images.";
                                session.stopRequested = true;
                            }
                        }
                    } else {
                        var pStatus = voteRecord.persist(result, imagePath, session.threshold, corners,
                            result.contentAreaWidth, result.contentAreaHeight,
                            result.warpDpi > 0 ? result.warpDpi : session.dpi, session);
                        if (pStatus == VoteRecordService.PersistStatus.RACE_SKIP) {
                            retryList.add(new Object[]{imagePath, result});
                        } else if (pStatus == VoteRecordService.PersistStatus.TRUE_DUPLICATE) {
                            log.info("Pass {}: skipping already-counted ballot: {}", session.passNumber, imageName);
                        }
                        int rptInterval = voteTally.getReportInterval();
                        if (rptInterval > 0 && written % rptInterval == 0) {
                            try {
                                voteTally.writePeriodicReport(session.results, session.imageFolder, session);
                            } finally {
                                session.results.clear();
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.error("DB persist failed for " + imageName + ": " + ex.getMessage());
                    session.scanError = "DB error on " + imageName + ": " + ex.getMessage();
                }

                // Milestone pause: unlike the browser version (which waited up to
                // 10s for a POST /resume), the FX progress screen's polling Timeline
                // clears this immediately — see CountingService's class comment.
                if (session.currentIndex % 1000 == 0) {
                    session.pauseForResults = true;
                    long deadline = System.currentTimeMillis() + 10_000;
                    while (session.pauseForResults && System.currentTimeMillis() < deadline) {
                        try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                    }
                }
            }

            if (!retryList.isEmpty()) {
                log.info("Retrying " + retryList.size() + " race-skipped ballot(s)");
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                for (Object[] retryItem : retryList) {
                    Path retryPath = (Path) retryItem[0];
                    ScanResult retryResult = (ScanResult) retryItem[1];
                    try {
                        voteRecord.persist(retryResult, retryPath, session.threshold, cornersOf(retryResult),
                            retryResult.contentAreaWidth, retryResult.contentAreaHeight,
                            retryResult.warpDpi > 0 ? retryResult.warpDpi : session.dpi, session);
                    } catch (Exception ex) {
                        log.error("Retry failed for " + retryPath.getFileName() + ": " + ex.getMessage());
                    }
                }
            }

            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }

            if (!scanRetryList.isEmpty()) {
                log.info("Re-scanning " + scanRetryList.size() + " image(s) that hit ConcurrentModificationException");
                for (Object[] retryItem : scanRetryList) {
                    Path retryPath = (Path) retryItem[0];
                    String imageName = retryPath.getFileName().toString();
                    ScanResult result;
                    try {
                        result = scanner.scanOne(retryPath, session);
                    } catch (Exception e2) {
                        String msg = e2.getMessage() != null ? e2.getMessage() : e2.getClass().getSimpleName();
                        result = new ScanResult();
                        result.imagePath = retryPath.toString();
                        result.imageName = imageName;
                        result.errorMessage = "Re-scan failed: " + msg;
                    }

                    session.currentImagePath = retryPath.toString();
                    session.currentIndex = ++written;
                    session.results.add(result);
                    auditLog.log("SCAN", username, imageName);

                    if (result.errorMessage != null) {
                        session.reviewRequired.add(retryPath.toAbsolutePath() + " — " + result.errorMessage);
                        try {
                            Path reviewPath = retryPath.resolveSibling(imageName + ".review");
                            Files.move(retryPath, reviewPath, StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception renameEx) {
                            log.warn("Could not rename to .review: {}", renameEx.getMessage());
                        }
                    } else {
                        try {
                            voteRecord.persist(result, retryPath, session.threshold, cornersOf(result),
                                result.contentAreaWidth, result.contentAreaHeight,
                                result.warpDpi > 0 ? result.warpDpi : session.dpi, session);
                        } catch (Exception ex) {
                            log.error("Persist failed after re-scan for " + imageName + ": " + ex.getMessage());
                        }
                    }
                }
            }

            Object[] item;
            while ((item = completedQueue.poll()) != null) {
                Path imagePath = (Path) item[0];
                ScanResult result = (ScanResult) item[1];
                String imageName = imagePath.getFileName().toString();
                session.currentImagePath = imagePath.toString();
                session.currentIndex = ++written;
                session.results.add(result);
                auditLog.log("SCAN", username, imageName);
                log.warn("Late-arriving result processed in drain: {}", imageName);
                try {
                    voteRecord.persist(result, imagePath, session.threshold, cornersOf(result),
                        result.contentAreaWidth, result.contentAreaHeight,
                        result.warpDpi > 0 ? result.warpDpi : session.dpi, session);
                } catch (Exception ex) {
                    log.error("Drain persist failed for {}: {}", imageName, ex.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Point2D[] cornersOf(ScanResult result) {
        if (!result.cornersFound) return null;
        return new Point2D[]{
            new Point2D(result.bboxTLx, result.bboxTLy),
            new Point2D(result.bboxTRx, result.bboxTRy),
            new Point2D(result.bboxBRx, result.bboxBRy),
            new Point2D(result.bboxBLx, result.bboxBLy),
        };
    }
}
