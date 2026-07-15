/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package com.mjtrac.counter.model;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds the state of one scanning session, stored in the HTTP session.
 * A session begins when the user submits the configuration form and ends
 * when all images in the folder have been processed or the user resets.
 */
public class ScanSession {

    // ── Configuration (set on form submit) ────────────────────────────────────

    /** Folder containing scanned ballot images. */
    public String imageFolder;
    /** Path of the image currently being scanned (for progress display). */
    public volatile String  currentImagePath = "";
    /** True while the background scan thread is running. */
    public volatile boolean scanning         = false;
    /** Set by scan thread at every 1000 images; cleared by /progress poll. */
    public volatile boolean pauseForResults  = false;
    /** Set by user clicking Stop; scan thread checks this and exits early. */
    public volatile boolean stopRequested    = false;
    /** Set if the scan thread fails with an exception. */
    public volatile String  scanError        = null;
    public volatile boolean tallyDone        = false;  // set after processTally() completes
    public volatile int     passNumber       = 1;      // current scan pass
    public volatile int     passWritten      = 0;      // images written this pass

    /**
     * Count of images picked up by worker threads (submitted to scan).
     * Updated atomically as workers claim images. Used by processed() so
     * the UI counter advances immediately when threads start an image,
     * preventing the end-of-scan stall where in-flight images make the
     * counter appear frozen.
     */
    public final AtomicInteger submittedCount = new AtomicInteger(0);
    /** Paths of images already in the DB (found during this session). Thread-safe list. */
    public java.util.List<String> duplicatePaths =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /** Paths of images that failed corner detection and require manual review. */
    public java.util.List<String> reviewRequired =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Folder containing XML/YAML bounding-box reports. */
    public String reportFolder;

    /** Darkness threshold: pixels below this value are counted as dark. */
    public int threshold = 128;

    /** Minimum percentage of dark pixels to declare an indicator "marked".
     *  Set to 20% by default: a blank oval/checkbox with a 1pt stroke produces
     *  roughly 12% darkness in the sampling region due to the stroke pixels.
     *  20% requires the voter to have partially filled the shape, not just
     *  the stroke border alone.  Adjust down if legitimate light marks are missed. */
    // Minimum dark-pixel percentage to count as marked.
    // Set to 5% to handle bilinear interpolation blending at warp edges
    // and slight luminance reduction through the distortion pipeline.
    public double darkPctMin = 8.0;

    /** Expected scan resolution in DPI (used as warp resolution and fallback). */
    public int dpi = 300;

    /**
     * Assumed paper width in inches, used for the DPI heuristic when no
     * DPI metadata is found in the image file.
     * Common values: 8.5 (US Letter/Legal), 11.0 (Tabloid), 8.27 (A4), 11.69 (A3).
     * Default: 8.5 inches.
     */
    public double assumedPaperWidthIn = 8.5;

    // ── Runtime state ──────────────────────────────────────────────────────────

    /** Sorted list of image file paths to process. */
    public List<Path> imageQueue = new ArrayList<>();

    /** Index of the next image to process (0-based). */
    public int currentIndex = 0;

    /** Loaded bounding-box layout pages from XML or YAML. */
    public List<BboxReport.PageLayout> layouts = new ArrayList<>();

    /** Path to the XML report that was loaded. */
    public String xmlReportPath;

    /** Path to the YAML report that was loaded. */
    public String yamlReportPath;

    /** All scan results accumulated so far. */
    public List<BboxReport.ScanResult> results = new ArrayList<>();

    /** Accumulated vote tallies, keyed by "contestTitle|candidateName". */
    public Map<String, BboxReport.CandidateTally> tallies = new LinkedHashMap<>();

    /**
     * Overvoted tallies — same structure as tallies but counts votes from
     * ballots where the contest was overvoted.  Populated by VoteTallyService
     * after all images are scanned.  These votes are NOT included in tallies.
     */
    public Map<String, BboxReport.CandidateTally> overvoteTallies = new LinkedHashMap<>();

    /**
     * Set of "contestTitle|imageName" pairs that were flagged as overvoted.
     * Used by VoteTallyService to move votes from tallies → overvoteTallies.
     */
    public java.util.Set<String> overvotedKeys = new java.util.LinkedHashSet<>();

    // ── Debug / coordinate-verification mode ─────────────────────────────────

    /**
     * When true, after processing each image the scanner writes an
     * _adjusted.yaml file containing all bounding-box coordinates
     * transformed from the theoretical (PDF) coordinate system into the
     * actual pixel coordinates found in the scanned image.
     *
     * This lets you visually verify that the perspective transform is
     * correctly locating each vote indicator on the real ballot scan.
     * Disable this flag once you are confident in the transforms, to
     * avoid the extra file I/O on each image.
     *
     * Default: false (disabled).
     */
    public boolean debugCoordinates = false;

    /**
     * Folder where adjusted YAML debug files are written.
     * Defaults to the same folder as the source images.
     * Ignored when debugCoordinates is false.
     */
    public String debugOutputFolder = "";

    /** Error messages at the session level (e.g., no report file found). */
    public String sessionError;

    // ── Computed properties ────────────────────────────────────────────────────

    public boolean isStarted()   { return !imageQueue.isEmpty(); }
    public boolean isComplete()  { return isStarted() && currentIndex >= imageQueue.size(); }
    public int     totalImages() { return imageQueue.size(); }
    /** Returns images written OR in-flight — whichever is higher. */
    public int     processed()   { return Math.min(
        Math.max(currentIndex, submittedCount.get()), imageQueue.size()); }

    public Path nextImage() {
        if (currentIndex < imageQueue.size()) return imageQueue.get(currentIndex);
        return null;
    }

    public double progressPct() {
        if (imageQueue.isEmpty()) return 0.0;
        return 100.0 * processed() / totalImages();
    }

    /** Update or create a tally entry for one marking result. */
    public void recordMarking(BboxReport.MarkingResult m) {
        String key = m.contestTitle + "|" + m.candidateName;
        BboxReport.CandidateTally t = tallies.computeIfAbsent(key, k -> {
            BboxReport.CandidateTally ct = new BboxReport.CandidateTally();
            ct.contestTitle   = m.contestTitle;
            ct.candidateId    = m.candidateId;
            ct.candidateName  = m.candidateName;
            ct.voteCount      = 0;
            ct.ballotsChecked = 0;
            return ct;
        });
        t.ballotsChecked++;
        if (m.marked) t.voteCount++;
    }
}
