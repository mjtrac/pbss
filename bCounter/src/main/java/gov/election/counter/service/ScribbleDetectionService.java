/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.counter.service;

import gov.election.counter.model.BboxReport.PageLayout;
import gov.election.counter.model.BboxReport.ContestBox;
import gov.election.counter.model.BboxReport.IndicatorBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.BasicStroke;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects unexpected marks (scribbles, annotations) on ballot images after
 * perspective correction.
 *
 * ALGORITHM:
 *   For each barcode seen during a scan run, we maintain a per-pixel accumulator
 *   (a byte[] mask the same dimensions as the corrected content area) that tracks
 *   which pixels have been dark on ANY prior ballot with the same barcode.
 *
 *   Before comparing a new ballot against the accumulator we build a "normative
 *   mask" that unions:
 *     (a) the accumulated dark pixels from prior ballots, dilated by
 *         scanner.scribble-dilation-in inches (default 1/16") — this absorbs
 *         normal printing variation and ink spread
 *     (b) all vote indicator regions from the YAML — pre-darkened so that even
 *         a heavily marked oval never triggers a scribble alert
 *
 *   Dark pixels in the new image that fall OUTSIDE the normative mask are counted.
 *   If that count exceeds scanner.scribble-threshold-px, the ballot is flagged.
 *
 * THREAD SAFETY:
 *   The accumulator map is ConcurrentHashMap; individual byte[] accumulators are
 *   written only from the single writer thread in ScanController (the parallel
 *   section only reads finalWarped).  If parallel write access is ever needed,
 *   synchronize on the accumulator array.
 *
 * MEMORY:
 *   Each accumulator is width × height bytes where dimensions = contentArea × warpDpi.
 *   At 300 DPI a full letter content area (~6.8" × 7.5") ≈ 2040 × 2250 = ~4.6 MB.
 *   With dozens of ballot types total memory is well within JVM heap.
 *
 * RESET:
 *   Call clearAll() between elections or at /new-election.
 *   The accumulator automatically resets when bCounter restarts.
 */
@Service
public class ScribbleDetectionService {

    private static final Logger log =
        LoggerFactory.getLogger(ScribbleDetectionService.class);

    @Value("${scanner.scribble-dilation-in:0.0625}")
    private double dilationIn;          // 1/16" default

    @Value("${scanner.scribble-threshold-px:50}")
    private int thresholdPx;            // suspicious pixels needed to flag

    @Value("${scanner.scribble-detection:false}")
    private boolean enabled;

    @Value("${scanner.scribble-outline-images:true}")
    private boolean outlineImagesEnabled;

    @Value("${scanner.scribble-outline-dir:}")
    private String outlineDir;   // blank = same folder as the source image

    /** Minimum cluster size (pixels) to draw a box for — filters single-pixel noise. */
    @Value("${scanner.scribble-cluster-min-px:8}")
    private int clusterMinPx;

    /**
     * Extra padding added to each indicator bounding box before masking,
     * in inches.  Default 1/8" (0.125") to cover the gap between the YAML
     * bounding box and the actual printed indicator region, and to absorb
     * sloppy marks that overflow the box slightly.
     */
    @Value("${scanner.scribble-indicator-pad-in:0.125}")
    private double indicatorPadIn;

    /** luminance threshold below which a pixel is "dark" — mirrors ScannerService */
    private static final int DARK_THRESHOLD = 128;

    /**
     * Per-barcode accumulated dark-pixel masks.
     * Key: barcodeData string (e.g. "1|1|1|1|1|1")
     * Value: byte[width * height], 1 = ever dark on a prior ballot, 0 = not seen dark
     * Dimensions: [warpW, warpH] stored alongside in AccumulatorEntry.
     */
    private final Map<String, AccumulatorEntry> accumulators = new ConcurrentHashMap<>();

    // ── Public API ─────────────────────────────────────────────────────────

    public boolean isEnabled() { return enabled; }

    /**
     * Analyse the corrected ballot image for unexpected marks.
     *
     * @param warped       perspective-corrected content-area image (full page warp)
     * @param barcodeData  barcode string — used as accumulator key
     * @param layout       page layout providing indicator regions
     * @param warpDpi      DPI of the warped image
     * @param imagePath    path to the original scanned image file — used to derive
     *                     the outline image filename; pass null to skip outline output
     * @return             result containing pixel count, flag, and outline image path
     */
    public ScribbleResult analyse(BufferedImage warped,
                                   String barcodeData,
                                   PageLayout layout,
                                   int warpDpi,
                                   Path imagePath) {
        if (!enabled) return ScribbleResult.DISABLED;
        if (warped == null || warped.getWidth() <= 1) {
            // patch-warp mode produces a 1×1 placeholder — should not reach here
            log.warn("Scribble detection called with degenerate image (patch-warp mode?)");
            return ScribbleResult.DISABLED;
        }

        int w = warped.getWidth();
        int h = warped.getHeight();
        String key = barcodeData != null ? barcodeData : "(unknown)";

        // ── Build the dark-pixel mask for this image ───────────────────────
        boolean[] dark = buildDarkMask(warped, w, h);

        // ── Retrieve or create accumulator ─────────────────────────────────
        AccumulatorEntry entry = accumulators.computeIfAbsent(key,
            k -> new AccumulatorEntry(w, h));

        // If dimensions changed (shouldn't happen for same barcode), reset
        if (entry.w != w || entry.h != h) {
            log.warn("Accumulator dimension mismatch for barcode {}; resetting", key);
            entry = new AccumulatorEntry(w, h);
            accumulators.put(key, entry);
        }

        // ── Build normative mask, compare, and update — all synchronized ──────
        // Synchronize on the entry so that concurrent threads scanning different
        // images with the same barcode don't race on the seeded flag or mask.
        synchronized (entry) {

        // ── First ballot for this barcode: seed the accumulator, don't flag ──
        // The accumulator starts empty, so the first ballot would have every
        // printed character, line, and border flagged as suspicious — exactly
        // what the ballot generator produced.  Use the first ballot only to
        // establish the baseline; begin comparing from the second ballot onward.
        if (!entry.seeded) {
            updateAccumulator(entry, dark);
            entry.seeded = true;
            log.debug("Scribble check barcode={}: first ballot — seeding accumulator, "
                + "not flagging", key);
            return ScribbleResult.DISABLED;
        }

        // ── Build normative mask ────────────────────────────────────────────
        // Union of: dilated accumulator + all indicator regions
        boolean[] normative = buildNormativeMask(entry, dark, layout, w, h, warpDpi);

        // ── Find suspicious pixels and cluster them into boxes ──────────────
        boolean[] suspiciousMask = new boolean[w * h];
        int suspicious = 0;
        for (int i = 0; i < w * h; i++) {
            if (dark[i] && !normative[i]) {
                suspiciousMask[i] = true;
                suspicious++;
            }
        }

        boolean flagged = suspicious >= thresholdPx;
        if (flagged) {
            log.warn("Scribble detected on barcode={}: {} suspicious pixels (threshold={})",
                key, suspicious, thresholdPx);
        } else {
            log.debug("Scribble check barcode={}: {} suspicious pixels (threshold={})",
                key, suspicious, thresholdPx);
        }

        // ── Draw red outline boxes around suspicious clusters ───────────────
        String outlinePath = null;
        if (flagged && outlineImagesEnabled && imagePath != null) {
            List<int[]> clusters = findClusters(suspiciousMask, w, h, clusterMinPx);
            if (!clusters.isEmpty()) {
                outlinePath = saveOutlineImage(warped, clusters, imagePath);
            } else {
                log.debug("Scribble flagged but no cluster met clusterMinPx={} "
                    + "(scattered noise rather than a contiguous mark)", clusterMinPx);
            }
        }

        // ── Update accumulator with this ballot's dark pixels ───────────────
        // Done AFTER comparison so this ballot doesn't whitelist its own scribbles.
        updateAccumulator(entry, dark);

        return new ScribbleResult(suspicious, flagged, outlinePath);

        } // end synchronized (entry)
    }

    /** Convenience overload for callers that don't need the outline image. */
    public ScribbleResult analyse(BufferedImage warped,
                                   String barcodeData,
                                   PageLayout layout,
                                   int warpDpi) {
        return analyse(warped, barcodeData, layout, warpDpi, null);
    }

    /** Clear all accumulators — call at new-election or session reset. */
    public void clearAll() {
        accumulators.clear();
        log.info("Scribble detection accumulators cleared");
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Extract a flat boolean dark-pixel mask from the warped image.
     * true = pixel luminance below DARK_THRESHOLD.
     */
    private boolean[] buildDarkMask(BufferedImage img, int w, int h) {
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        boolean[] dark = new boolean[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8)  & 0xFF;
            int b =  rgb        & 0xFF;
            int lum = (r * 299 + g * 587 + b * 114) / 1000;
            dark[i] = lum < DARK_THRESHOLD;
        }
        return dark;
    }

    /**
     * Build the normative mask: pixels that are "expected" to be dark.
     * = dilated accumulator  UNION  all indicator bounding boxes
     */
    private boolean[] buildNormativeMask(AccumulatorEntry entry,
                                          boolean[] currentDark,
                                          PageLayout layout,
                                          int w, int h, int warpDpi) {
        // Start with a dilated copy of the accumulator
        boolean[] normative = dilate(entry.mask, w, h,
            (int) Math.round(dilationIn * warpDpi));

        // Pre-darken all indicator bounding boxes from the YAML, expanded
        // by indicatorPadIn on each side to cover the gap between the YAML
        // bounding box and the actual printed indicator, and to absorb
        // sloppy marks that overflow the box slightly.
        if (layout != null) {
            int padPx = (int) Math.round(indicatorPadIn * warpDpi);
            for (ContestBox contest : layout.contests) {
                for (IndicatorBox ind : contest.indicators) {
                    // Indicator positions in the warped image are content-area-relative
                    int ix = (int) Math.round(
                        (ind.offsetLeft - layout.contentAreaOffsetLeft) * warpDpi);
                    int iy = (int) Math.round(
                        (ind.offsetTop  - layout.contentAreaOffsetTop)  * warpDpi);
                    int iw = Math.max(1, (int) Math.round(ind.width  * warpDpi));
                    int ih = Math.max(1, (int) Math.round(ind.height * warpDpi));

                    // Expand by padding on all four sides
                    int x0 = Math.max(0, ix - padPx);
                    int y0 = Math.max(0, iy - padPx);
                    int x1 = Math.min(w, ix + iw + padPx);
                    int y1 = Math.min(h, iy + ih + padPx);

                    for (int y = y0; y < y1; y++) {
                        for (int x = x0; x < x1; x++) {
                            normative[y * w + x] = true;
                        }
                    }
                }
            }
        }
        return normative;
    }

    /**
     * Dilate a boolean mask by radius pixels using two separable 1D passes
     * (horizontal then vertical).  O(n × r) rather than O(n × r²).
     *
     * A pixel becomes true in the output if any pixel within radius in its
     * row (horizontal pass) or column (vertical pass) was true in the input.
     * This is equivalent to a square structuring element dilation.
     */
    private boolean[] dilate(byte[] mask, int w, int h, int radius) {
        if (radius <= 0) {
            // No dilation — just convert byte[] to boolean[]
            boolean[] out = new boolean[w * h];
            for (int i = 0; i < mask.length && i < out.length; i++)
                out[i] = mask[i] != 0;
            return out;
        }

        // ── Horizontal pass ────────────────────────────────────────────────
        // For each row, slide a window of width (2*radius+1).
        // Use a running count of true values in the window.
        boolean[] hPass = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int rowBase = y * w;
            int count = 0;
            // Prime the window for x=0
            for (int x = 0; x <= Math.min(radius, w - 1); x++)
                if (mask[rowBase + x] != 0) count++;
            for (int x = 0; x < w; x++) {
                // Add right edge of window
                int addX = x + radius;
                if (addX < w && mask[rowBase + addX] != 0) count++;
                // Remove left edge that just fell out
                int removeX = x - radius - 1;
                if (removeX >= 0 && mask[rowBase + removeX] != 0) count--;
                hPass[rowBase + x] = (count > 0);
            }
        }

        // ── Vertical pass ──────────────────────────────────────────────────
        boolean[] vPass = new boolean[w * h];
        for (int x = 0; x < w; x++) {
            int count = 0;
            // Prime window for y=0
            for (int y = 0; y <= Math.min(radius, h - 1); y++)
                if (hPass[y * w + x]) count++;
            for (int y = 0; y < h; y++) {
                int addY = y + radius;
                if (addY < h && hPass[addY * w + x]) count++;
                int removeY = y - radius - 1;
                if (removeY >= 0 && hPass[removeY * w + x]) count--;
                vPass[y * w + x] = (count > 0);
            }
        }
        return vPass;
    }

    /**
     * OR the current ballot's dark pixels into the accumulator mask.
     * Called after comparison so this ballot's scribbles don't whitelist themselves.
     */
    private void updateAccumulator(AccumulatorEntry entry, boolean[] dark) {
        for (int i = 0; i < entry.mask.length && i < dark.length; i++) {
            if (dark[i]) entry.mask[i] = 1;
        }
    }

    /**
     * Find connected components ("clusters") of true pixels in a boolean mask
     * using iterative 4-connectivity flood fill (BFS, not recursive — avoids
     * stack overflow on large contiguous regions like a long scribble line).
     *
     * @return list of bounding boxes as {x0, y0, x1, y1} (exclusive x1/y1),
     *         filtered to clusters with at least minPixels pixels
     */
    private List<int[]> findClusters(boolean[] mask, int w, int h, int minPixels) {
        boolean[] visited = new boolean[w * h];
        List<int[]> clusters = new ArrayList<>();
        Deque<Integer> queue = new ArrayDeque<>();

        for (int start = 0; start < w * h; start++) {
            if (!mask[start] || visited[start]) continue;

            // BFS flood fill from this seed pixel
            int minX = start % w, maxX = minX;
            int minY = start / w, maxY = minY;
            int count = 0;

            queue.clear();
            queue.add(start);
            visited[start] = true;

            while (!queue.isEmpty()) {
                int idx = queue.poll();
                int x = idx % w, y = idx / w;
                count++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;

                // 4-connected neighbours
                if (x > 0     && mask[idx - 1] && !visited[idx - 1]) { visited[idx - 1] = true; queue.add(idx - 1); }
                if (x < w - 1 && mask[idx + 1] && !visited[idx + 1]) { visited[idx + 1] = true; queue.add(idx + 1); }
                if (y > 0     && mask[idx - w] && !visited[idx - w]) { visited[idx - w] = true; queue.add(idx - w); }
                if (y < h - 1 && mask[idx + w] && !visited[idx + w]) { visited[idx + w] = true; queue.add(idx + w); }
            }

            if (count >= minPixels) {
                clusters.add(new int[]{minX, minY, maxX + 1, maxY + 1});
            }
        }
        return clusters;
    }

    /**
     * Draw red outline boxes around each suspicious cluster on a COPY of the
     * warped image, then save it to disk.  The original scanned image is
     * never modified.
     *
     * Output filename: {imageStem}_scribble.png in the configured outline
     * directory (scanner.scribble-outline-dir), or alongside the source
     * image if that property is blank.
     *
     * @return absolute path to the saved outline image, or null on failure
     */
    private String saveOutlineImage(BufferedImage warped, List<int[]> clusters,
                                     Path imagePath) {
        try {
            BufferedImage copy = new BufferedImage(
                warped.getWidth(), warped.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = copy.createGraphics();
            g.drawImage(warped, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.RED);
            g.setStroke(new BasicStroke(3f));

            // Small padding around each cluster so the box doesn't clip the mark
            int pad = 4;
            for (int[] box : clusters) {
                int x0 = Math.max(0, box[0] - pad);
                int y0 = Math.max(0, box[1] - pad);
                int x1 = Math.min(copy.getWidth(),  box[2] + pad);
                int y1 = Math.min(copy.getHeight(), box[3] + pad);
                g.drawRect(x0, y0, x1 - x0, y1 - y0);
            }
            g.dispose();

            String stem = stemOf(imagePath);
            Path outDir = outlineImagesDir(imagePath);
            java.nio.file.Files.createDirectories(outDir);
            Path outFile = outDir.resolve(stem + "_scribble.png");

            ImageIO.write(copy, "PNG", new File(outFile.toString()));
            log.info("Scribble outline image saved: {} ({} cluster(s))",
                outFile.toAbsolutePath(), clusters.size());
            return outFile.toAbsolutePath().toString();

        } catch (IOException e) {
            log.warn("Could not save scribble outline image for {}: {}",
                imagePath.getFileName(), e.getMessage());
            return null;
        }
    }

    private String stemOf(Path imagePath) {
        String name = imagePath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private Path outlineImagesDir(Path imagePath) {
        if (outlineDir != null && !outlineDir.isBlank()) {
            return Paths.get(outlineDir);
        }
        Path parent = imagePath.getParent();
        return parent != null ? parent : Paths.get(".");
    }

    // ── Inner types ────────────────────────────────────────────────────────

    private static class AccumulatorEntry {
        final int w, h;
        final byte[] mask;   // 1 = ever dark on a prior ballot, 0 = not seen
        /** False until the first ballot has been used to seed the accumulator.
         *  The first ballot is never flagged — it establishes the baseline of
         *  what normally-printed content looks like for this barcode. */
        boolean seeded = false;

        AccumulatorEntry(int w, int h) {
            this.w    = w;
            this.h    = h;
            this.mask = new byte[w * h];
        }
    }

    /** Result returned to ScannerService and stored on ScanResult. */
    public record ScribbleResult(
        int     suspiciousPixels,
        boolean flagged,
        String  outlineImagePath   // null if not flagged, drawing disabled, or save failed
    ) {
        /** Convenience constructor for callers not using the outline feature. */
        public ScribbleResult(int suspiciousPixels, boolean flagged) {
            this(suspiciousPixels, flagged, null);
        }
        /** Sentinel for when detection is disabled or not applicable. */
        static final ScribbleResult DISABLED = new ScribbleResult(0, false, null);
    }
}
