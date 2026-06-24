/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package gov.election.counter.controller;

import gov.election.counter.model.BboxReport;
import gov.election.counter.model.BboxReport.*;
import gov.election.counter.model.ScanSession;
import gov.election.counter.service.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpSession;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import gov.election.counter.service.AuditLogService;

/**
 * Handles all web UI interactions for the ballot scanning session.
 *
 * Session flow:
 *   GET  /          → configuration form
 *   POST /start     → validate inputs, load report, enumerate images → redirect /scan
 *   GET  /scan      → if more images: process next, show result; if done: redirect /report
 *   GET  /report    → show tallies and full results
 *   POST /reset     → clear session → redirect /
 */
@Controller
public class ScanController {

    private static final Logger log =
        LoggerFactory.getLogger(ScanController.class);

    private final Executor taskExecutor;

    private static final String SESSION_KEY = "scanSession";

    private final BboxReportLoader  loader;
    private final ScannerService    scanner;

    private final VoteTallyService   voteTally;
    private final AuditLogService    auditLog;
    /** Stop scanning after this many ballots require manual review. 0 = no limit. */
    @Value("${scanner.max-review-before-stop:10}")
    private int maxReviewBeforeStop;

    /** Number of ballot images to scan in parallel. Defaults to half available cores. */
    @Value("${scanner.parallel-threads:0}")
    private int parallelThreads;

    private int resolvedThreads() {
        if (parallelThreads > 0) return parallelThreads;
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }

    private final VoteRecordService  voteRecord;

    public ScanController(BboxReportLoader loader, ScannerService scanner,
                          VoteTallyService voteTally, AuditLogService auditLog,
                          VoteRecordService voteRecord) {
        this.loader       = loader;
        this.scanner      = scanner;
        this.voteTally    = voteTally;
        this.auditLog     = auditLog;
        this.voteRecord   = voteRecord;
        // Use Spring's SimpleAsyncTaskExecutor so beans called from the
        // background scan thread (e.g. VoteRecordService) have a proper
        // Spring application context and @Transactional support.
        org.springframework.core.task.SimpleAsyncTaskExecutor ex =
            new org.springframework.core.task.SimpleAsyncTaskExecutor("scan-");
        ex.setConcurrencyLimit(1);
        this.taskExecutor = ex;
    }

    // ── Configuration form ─────────────────────────────────────────────────────

    @GetMapping("/")
    public String index(HttpSession httpSession, Model model) {
        ScanSession session = getOrCreate(httpSession);
        model.addAttribute("ss", session);
        model.addAttribute("hasSession", session.isStarted());
        // Show viewer link if a previous scan DB exists
        java.nio.file.Path dbPath = java.nio.file.Paths.get(
            System.getProperty("user.dir"), "counter_results.db");
        model.addAttribute("dbExists", java.nio.file.Files.exists(dbPath));
        model.addAttribute("viewerUrl", "http://localhost:${viewer.server.port:8082}/viewer/");
        return "index";
    }

    // ── Start scan session ─────────────────────────────────────────────────────

    @PostMapping("/start")
    public String start(
            @RequestParam(required = false) String imageFolder,
            @RequestParam(required = false) String reportFolder,
            @RequestParam(defaultValue = "128") int threshold,
            @RequestParam(defaultValue = "8")   double darkPct,
            @RequestParam(defaultValue = "200") int dpi,
            @RequestParam(defaultValue = "false") boolean debugCoordinates,
            @RequestParam(required = false) String debugOutputFolder,
            @RequestParam(defaultValue = "8.5") double assumedPaperWidthIn,
            HttpSession httpSession,
            RedirectAttributes ra) {

        ScanSession session = new ScanSession();
        session.imageFolder       = imageFolder       != null ? imageFolder.trim()       : "";
        session.reportFolder      = reportFolder      != null ? reportFolder.trim()      : "";
        session.threshold         = threshold;
        session.darkPctMin        = darkPct;
        session.dpi               = dpi;
        session.debugCoordinates      = debugCoordinates;
        session.debugOutputFolder     = debugOutputFolder != null ? debugOutputFolder.trim() : "";
        session.assumedPaperWidthIn   = assumedPaperWidthIn > 0 ? assumedPaperWidthIn : 8.5;

        // ── Validate image folder ───────────────────────────────────────────────
        if (session.imageFolder.isBlank()) {
            ra.addFlashAttribute("error", "Image folder is required.");
            return "redirect:/";
        }
        Path imgDir = Paths.get(session.imageFolder);
        if (!Files.isDirectory(imgDir)) {
            ra.addFlashAttribute("error",
                "Image folder not found: " + session.imageFolder);
            return "redirect:/";
        }

        // ── Locate report files ─────────────────────────────────────────────────
        Path reportDir = session.reportFolder.isBlank()
            ? Paths.get(".") : Paths.get(session.reportFolder);
        if (!Files.isDirectory(reportDir)) {
            ra.addFlashAttribute("error",
                "Report folder not found: " + session.reportFolder);
            return "redirect:/";
        }

        try {
            // Load ALL YAML/XML files in the folder, merging pages from each.
            // This handles multi-page ballots where page 1 and page 2 each have
            // their own ballot_..._1.yaml and ballot_..._2.yaml files.
            session.layouts = loader.loadAllFromFolder(reportDir);

            // Also record the first YAML/XML path for adjusted-YAML output reference
            Path xmlPath  = loader.findXml(reportDir);
            Path yamlPath = loader.findYaml(reportDir);
            session.xmlReportPath  = xmlPath  != null ? xmlPath.toString()  : "(none)";
            session.yamlReportPath = yamlPath != null ? yamlPath.toString() : "(none)";

            if (session.layouts.isEmpty()) {
                ra.addFlashAttribute("error", "Report loaded but contains no page layouts.");
                return "redirect:/";
            }
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Failed to load report: " + e.getMessage());
            return "redirect:/";
        }

        // ── Enumerate images (recursive tree walk, skip .counted) ───────────────
        try {
            session.imageQueue = voteRecord.findImagesRecursive(imgDir);
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Failed to read image folder: " + e.getMessage());
            return "redirect:/";
        }

        if (session.imageQueue.isEmpty()) {
            ra.addFlashAttribute("error",
                "No image files found in: " + session.imageFolder +
                " (looking for .png, .jpg, .tif, .bmp)");
            return "redirect:/";
        }

        httpSession.setAttribute(SESSION_KEY, session);
        // Go to /scan which starts the background thread then redirects to /scanning
        return "redirect:/scan";
    }

    // ── Scanning progress page (shown while /scan loop runs) ─────────────────

    @GetMapping("/scanning")
    public String scanningPage(HttpSession httpSession, Model model) {
        ScanSession session = getSession(httpSession);
        if (session == null || !session.isStarted()) return "redirect:/";
        model.addAttribute("ss", session);
        return "scanning";
    }

    // ── Run scan ───────────────────────────────────────────────────────────────
    // Scans ALL remaining images in one continuous server-side loop.
    // The browser shows a simple progress page that polls GET /progress.
    // At every 1000 scans and at completion, /results is shown.

    @GetMapping("/scan")
    public String scan(HttpSession httpSession,
                       @AuthenticationPrincipal UserDetails userDetails) {
        ScanSession session = getSession(httpSession);
        if (session == null || !session.isStarted()) return "redirect:/";

        // If already scanning asynchronously, just show the progress page
        if (session.scanning) return "redirect:/scanning";

        // Start scan loop in background thread so the browser isn't blocked
        session.scanning = true;
        final String username = userDetails != null ? userDetails.getUsername() : "(system)";
        taskExecutor.execute(() -> runScanLoop(session, username));

        return "redirect:/scanning";
    }

    /** Runs in a background thread — scans all images, updates session state.
     *
     * Multi-pass: after the initial scan completes, the folder tree is walked
     * again to find any new files added since the scan started.  Passes repeat
     * until a walk finds no new files, then processTally() is called once.
     *
     * PARALLELISM STRATEGY:
     *   - resolvedThreads() workers scan images concurrently (CPU-bound work:
     *     corner detection, homography warp, mark analysis).
     *   - A single writer on the calling thread serializes all DB writes and
     *     session state updates, avoiding SQLite write contention.
     */
    private void runScanLoop(ScanSession session, String username) {
        int nThreads = resolvedThreads();
        log.info("Starting parallel scan: {} worker thread(s)", nThreads);

        // Track all paths ever queued across all passes to detect new files
        java.util.Set<String> allQueued = new java.util.HashSet<>();
        for (Path p : session.imageQueue) allQueued.add(p.toString());

        int passNumber = 1;
        try {
            do {
                int passStart = session.currentIndex;
                log.info("Pass {}: scanning {} image(s)", passNumber,
                    session.imageQueue.size() - (passNumber == 1 ? 0 : passStart));
                session.passNumber = passNumber;

                runScanPass(session, username, allQueued);

                if (session.stopRequested) break;

                // Walk tree for new files not yet queued, OR files that were
                // queued but never successfully counted (still .png after the pass)
                java.util.List<Path> newFiles = new java.util.ArrayList<>();
                final int currentPass = passNumber; // effectively final for lambda capture
                try {
                    java.nio.file.Files.walk(java.nio.file.Paths.get(session.imageFolder))
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase();
                            return (name.endsWith(".png") || name.endsWith(".jpg")
                                 || name.endsWith(".jpeg") || name.endsWith(".tif")
                                 || name.endsWith(".tiff"));
                        })
                        .filter(p -> {
                            boolean isNew = !allQueued.contains(p.toString());
                            if (!isNew) {
                                // Was queued previously but still exists uncounted
                                log.warn("Pass {}: previously queued file still uncounted"
                                    + " — requeuing: {}", currentPass, p.getFileName());
                            }
                            return true; // always include; persist() handles duplicates
                        })
                        .forEach(newFiles::add);
                } catch (Exception ex) {
                    log.warn("Pass {} folder walk failed: {}", passNumber, ex.getMessage());
                }

                if (newFiles.isEmpty()) {
                    log.info("Pass {}: no new files found — scan complete", passNumber);
                    break;
                }
                // Save a snapshot of results before starting the next pass
                log.info("Pass {}: saving pass results snapshot before pass {}",
                    passNumber, passNumber + 1);
                voteTally.writePassReport(session.imageFolder, session, passNumber);

                log.info("Pass {}: {} new file(s) found — starting pass {}",
                    passNumber, newFiles.size(), passNumber + 1);
                for (Path p : newFiles) {
                    session.imageQueue.add(p);
                    allQueued.add(p.toString());
                }
                passNumber++;

            } while (!session.stopRequested);

            // All passes done — run periodic-style tally for final results report
            voteTally.processTally(session.results, session.imageFolder, session);
            // Run DB-driven final tally (RCV + Arlo) — session.results is empty by now
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
                    java.nio.file.Path reviewPath = java.nio.file.Paths.get(
                        voteTally.getReportOutputDir(), "review_required.txt");
                    java.nio.file.Files.writeString(reviewPath,
                        String.join("\n", session.reviewRequired) + "\n");
                    log.info("Wrote {} review-required entries to {}",
                        session.reviewRequired.size(), reviewPath);
                } catch (Exception e) {
                    log.warn("Could not write review_required.txt: {}", e.getMessage());
                }
            }
        }
    }

    /** Runs one pass of the scan loop over session.imageQueue entries not yet written. */
    private void runScanPass(ScanSession session, String username,
                              java.util.Set<String> allQueued) throws Exception {

        int nThreads = resolvedThreads();
        ExecutorService pool = Executors.newFixedThreadPool(nThreads,
            r -> { Thread t = new Thread(r, "scan-" + Thread.currentThread().getId()); t.setDaemon(true); return t; });

        // This pass covers indices from current written count to end of queue
        int passStartIdx = session.currentIndex;
        int total        = session.imageQueue.size();

        AtomicInteger nextIndex = new AtomicInteger(passStartIdx);
        ConcurrentLinkedQueue<Object[]> completedQueue = new ConcurrentLinkedQueue<>();
        java.util.List<Object[]> retryList = new java.util.ArrayList<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < nThreads; t++) {
            futures.add(pool.submit(() -> {
                while (true) {
                    if (session.stopRequested) break;
                    int idx = nextIndex.getAndIncrement();
                    if (idx >= total) break;
                    Path imagePath = session.imageQueue.get(idx);
                    try {
                        ScanResult result = scanner.scanOne(imagePath, session);
                        completedQueue.add(new Object[]{imagePath, result, idx});
                    } catch (Exception e) {
                        String msg = e.getMessage() != null ? e.getMessage()
                                   : e.getClass().getSimpleName();
                        log.error("Worker exception on " + imagePath.getFileName()
                            + ": " + e.getClass().getName() + ": " + msg);
                        if (e.getCause() != null)
                            log.error("  Caused by: " + e.getCause());
                        ScanResult err = new ScanResult();
                        err.imagePath    = imagePath.toString();
                        err.imageName    = imagePath.getFileName().toString();
                        err.errorMessage = "Unexpected scan error: " + msg;
                        completedQueue.add(new Object[]{imagePath, err, idx});
                    }
                }
            }));
        }

        // Writer loop: drain completedQueue, update session, persist to DB
        // Runs on the current (background) thread — only thread touching the DB
        int written = passStartIdx;
        try {
            while (written < total && !session.stopRequested) {
                Object[] item = completedQueue.poll();
                if (item == null) { Thread.sleep(5); continue; }

                Path imagePath  = (Path) item[0];
                ScanResult result = (ScanResult) item[1];
                String imageName  = imagePath.getFileName().toString();

                session.currentImagePath = imagePath.toString();
                session.currentIndex     = ++written;
                session.passWritten      = written - passStartIdx;
                session.results.add(result);
                auditLog.log("SCAN", username, imageName);

                try {
                    gov.election.counter.service.CornerDetectionService.Point2D[] corners = null;
                    if (result.cornersFound) {
                        corners = new gov.election.counter.service.CornerDetectionService.Point2D[]{
                            new gov.election.counter.service.CornerDetectionService.Point2D(result.bboxTLx, result.bboxTLy),
                            new gov.election.counter.service.CornerDetectionService.Point2D(result.bboxTRx, result.bboxTRy),
                            new gov.election.counter.service.CornerDetectionService.Point2D(result.bboxBRx, result.bboxBRy),
                            new gov.election.counter.service.CornerDetectionService.Point2D(result.bboxBLx, result.bboxBLy),
                        };
                    }
                    if (result.errorMessage != null) {
                        session.reviewRequired.add(imagePath.toAbsolutePath().toString()
                            + " — " + result.errorMessage);
                        log.warn("Flagged for review: " + imageName
                            + " — " + result.errorMessage);
                        if (maxReviewBeforeStop > 0
                                && session.reviewRequired.size() >= maxReviewBeforeStop) {
                            log.error("Stopping scan: "
                                + session.reviewRequired.size()
                                + " ballots flagged for review (limit="
                                + maxReviewBeforeStop + "). Check YAML and image quality.");
                            session.scanError = "Scan halted: "
                                + session.reviewRequired.size()
                                + " ballots required manual review (limit "
                                + maxReviewBeforeStop
                                + "). Fix the issue and rescan uncounted images.";
                            session.stopRequested = true;
                        }
                    } else {
                        var pStatus = voteRecord.persist(result, imagePath, session.threshold,
                            corners, result.contentAreaWidth, result.contentAreaHeight,
                            result.warpDpi > 0 ? result.warpDpi : session.dpi, session);
                        if (pStatus == gov.election.counter.service.VoteRecordService.PersistStatus.RACE_SKIP) {
                            retryList.add(new Object[]{imagePath, result});
                        } else if (pStatus == gov.election.counter.service.VoteRecordService.PersistStatus.TRUE_DUPLICATE) {
                            log.info("Pass {}: skipping already-counted ballot: {}",
                                session.passNumber, imageName);
                        }
                        // Periodic results report
                        int rptInterval = voteTally.getReportInterval();
                        if (rptInterval > 0 && written % rptInterval == 0) {
                            log.info("Writing periodic results report at {} images", written);
                            voteTally.writePeriodicReport(
                                session.results, session.imageFolder, session);
                        }
                    }
                } catch (Exception ex) {
                    log.error("DB persist failed for " + imageName + ": "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    if (ex.getCause() != null)
                        log.error("  Caused by: " + ex.getCause().getMessage());
                    session.scanError = "DB error on " + imageName + ": " + ex.getMessage();
                }

                session.results.clear();

                // Pause briefly at every 1000 images so /results can be shown
                // (the /progress endpoint triggers the browser redirect)
                if (session.currentIndex % 1000 == 0) {
                    session.pauseForResults = true;
                    // Wait up to 10 seconds for the browser to pick up the pause
                    long deadline = System.currentTimeMillis() + 10_000;
                    while (session.pauseForResults && System.currentTimeMillis() < deadline) {
                        try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                    }
                }
            }
            // ── Retry race-skipped ballots ────────────────────────────────
            if (!retryList.isEmpty()) {
                log.info("Retrying " + retryList.size() + " race-skipped ballot(s)");
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                for (Object[] retryItem : retryList) {
                    Path retryPath = (Path) retryItem[0];
                    ScanResult retryResult = (ScanResult) retryItem[1];
                    try {
                        gov.election.counter.service.CornerDetectionService.Point2D[] rc = null;
                        if (retryResult.cornersFound) {
                            rc = new gov.election.counter.service.CornerDetectionService.Point2D[]{
                                new gov.election.counter.service.CornerDetectionService.Point2D(retryResult.bboxTLx, retryResult.bboxTLy),
                                new gov.election.counter.service.CornerDetectionService.Point2D(retryResult.bboxTRx, retryResult.bboxTRy),
                                new gov.election.counter.service.CornerDetectionService.Point2D(retryResult.bboxBRx, retryResult.bboxBRy),
                                new gov.election.counter.service.CornerDetectionService.Point2D(retryResult.bboxBLx, retryResult.bboxBLy),
                            };
                        }
                        var retryStatus = voteRecord.persist(
                            retryResult, retryPath, session.threshold, rc,
                            retryResult.contentAreaWidth, retryResult.contentAreaHeight,
                            retryResult.warpDpi > 0 ? retryResult.warpDpi : session.dpi,
                            session);
                        if (retryStatus == gov.election.counter.service.VoteRecordService
                                .PersistStatus.SAVED) {
                            log.info("Retry succeeded: " + retryPath.getFileName());
                        } else {
                            log.warn("Retry confirmed duplicate (race winner already saved): "
                                + retryPath.getFileName() + " [" + retryStatus + "]");
                        }
                    } catch (Exception ex) {
                        log.error("Retry failed for " + retryPath.getFileName()
                            + ": " + ex.getMessage());
                    }
                }
            }
            // Pass complete — outer loop will check for new files
            // Wait for all workers to finish, then drain any remaining items
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
            // Drain any items that arrived after the writer loop exited
            Object[] item;
            while ((item = completedQueue.poll()) != null) {
                Path imagePath  = (Path) item[0];
                ScanResult result = (ScanResult) item[1];
                String imageName  = imagePath.getFileName().toString();
                session.currentImagePath = imagePath.toString();
                session.currentIndex     = ++written;
                session.results.add(result);
                auditLog.log("SCAN", username, imageName);
                log.warn("Late-arriving result processed in drain: {}", imageName);
                try {
                    gov.election.counter.service.CornerDetectionService.Point2D[] corners = null;
                    if (result.cornersFound) {
                        corners = new gov.election.counter.service.CornerDetectionService.Point2D[]{
                            new gov.election.counter.service.CornerDetectionService.Point2D(result.bboxTLx, result.bboxTLy),
                            new gov.election.counter.service.CornerDetectionService.Point2D(result.bboxTRx, result.bboxTRy),
                            new gov.election.counter.service.CornerDetectionService.Point2D(result.bboxBRx, result.bboxBRy),
                            new gov.election.counter.service.CornerDetectionService.Point2D(result.bboxBLx, result.bboxBLy),
                        };
                    }
                    voteRecord.persist(result, imagePath, session.threshold,
                        corners, result.contentAreaWidth, result.contentAreaHeight,
                        result.warpDpi > 0 ? result.warpDpi : session.dpi, session);
                } catch (Exception ex) {
                    log.error("Drain persist failed for {}: {}", imageName, ex.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ── Progress polling (lightweight — just path + counts) ─────────────────

    @GetMapping("/progress")
    @org.springframework.web.bind.annotation.ResponseBody
    public java.util.Map<String, Object> progress(HttpSession httpSession) {
        ScanSession session = getSession(httpSession);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (session == null) {
            m.put("started", false);
        } else {
            m.put("started",       session.isStarted());
            // complete = all images dispatched AND post-scan tally finished
            m.put("complete",      session.isComplete() && session.tallyDone);
            m.put("scanning",      session.scanning);
            m.put("pauseForResults", session.pauseForResults);
            m.put("processed",     session.processed());
            m.put("total",         session.totalImages());
            m.put("current",       session.currentImagePath != null ? session.currentImagePath : "");
            m.put("yamlSource",    session.yamlReportPath  != null ? session.yamlReportPath  : "");
            m.put("error",         session.scanError != null ? session.scanError : "");
            m.put("stopped",       session.stopRequested && !session.scanning);
            m.put("passNumber",    session.passNumber);
            m.put("reportOutputDir", voteTally.getReportOutputDir());
            m.put("passWritten",   session.passWritten);
            m.put("duplicates",    new java.util.ArrayList<>(session.duplicatePaths));
            m.put("reviewRequired", new java.util.ArrayList<>(session.reviewRequired));
        }
        return m;
    }

    // ── Report ─────────────────────────────────────────────────────────────────

    @GetMapping("/report")
    public String report(HttpSession httpSession, Model model) {
        ScanSession session = getSession(httpSession);
        if (session == null || !session.isStarted()) return "redirect:/";

        // Group tallies by contest for easier display
        Map<String, List<CandidateTally>> byContest = new LinkedHashMap<>();
        for (CandidateTally t : session.tallies.values()) {
            byContest.computeIfAbsent(t.contestTitle, k -> new ArrayList<>()).add(t);
        }
        // Group overvote tallies by contest
        Map<String, List<CandidateTally>> byContestOvervote = new LinkedHashMap<>();
        for (CandidateTally t : session.overvoteTallies.values()) {
            if (t.voteCount > 0)
                byContestOvervote.computeIfAbsent(t.contestTitle, k -> new ArrayList<>()).add(t);
        }

        long errorCount = session.results.stream()
            .filter(r -> r.errorMessage != null).count();
        model.addAttribute("ss",         session);
        model.addAttribute("byContest",        byContest);
        model.addAttribute("byContestOvervote", byContestOvervote);
        model.addAttribute("duplicatePaths",    new java.util.ArrayList<>(session.duplicatePaths));
        model.addAttribute("errorCount", errorCount);
        return "report";
    }

    // ── Resume after results milestone ────────────────────────────────────────

    @org.springframework.web.bind.annotation.PostMapping("/resume")
    @org.springframework.web.bind.annotation.ResponseBody
    public String resume(HttpSession httpSession) {
        ScanSession session = getSession(httpSession);
        if (session != null) session.pauseForResults = false;
        return "ok";
    }

    // ── Stop scan early ───────────────────────────────────────────────────────

    @org.springframework.web.bind.annotation.PostMapping("/stop")
    @org.springframework.web.bind.annotation.ResponseBody
    public String stop(HttpSession httpSession) {
        ScanSession session = getSession(httpSession);
        if (session != null) {
            session.stopRequested = true;
            session.pauseForResults = false;  // unblock if paused at milestone
        }
        return "ok";
    }

    // ── Finish (write output files now) ───────────────────────────────────────

    @PostMapping("/finish")
    public String finish(HttpSession httpSession,
                         @AuthenticationPrincipal UserDetails userDetails,
                         RedirectAttributes ra) {
        ScanSession session = getSession(httpSession);
        String folder = session != null ? session.imageFolder : null;
        try {
            java.util.List<gov.election.counter.model.BboxReport.ScanResult> empty =
                java.util.Collections.emptyList();
            voteTally.processTally(
                session != null ? session.results : empty,
                folder != null ? folder : ".",
                session);

            // Write review_required.txt listing ballots that could not be processed
            if (session != null && !session.reviewRequired.isEmpty()) {
                java.nio.file.Path reviewPath = java.nio.file.Paths.get(
                    folder != null ? folder : ".", "review_required.txt");
                java.nio.file.Files.writeString(reviewPath,
                    String.join("\n", session.reviewRequired) + "\n");
                log.info("Wrote " + session.reviewRequired.size()
                    + " review-required entries to " + reviewPath);
            }

            if (userDetails != null)
                auditLog.log("FINISH", userDetails.getUsername(),
                    (session != null ? session.processed() : 0)
                    + " images, folder: " + folder);
            ra.addFlashAttribute("success",
                folder != null
                    ? "Results saved to: " + folder
                    : "Results saved.");
        } catch (Exception ex) {
            ra.addFlashAttribute("error",
                "Could not write results: " + ex.getMessage());
        }
        return "redirect:/results";
    }

    // ── Reset ──────────────────────────────────────────────────────────────────

    @PostMapping("/reset")
    public String reset(HttpSession httpSession) {
        httpSession.removeAttribute(SESSION_KEY);
        return "redirect:/";
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private ScanSession getOrCreate(HttpSession s) {
        ScanSession session = (ScanSession) s.getAttribute(SESSION_KEY);
        return session != null ? session : new ScanSession();
    }

    private ScanSession getSession(HttpSession s) {
        return (ScanSession) s.getAttribute(SESSION_KEY);
    }
}
