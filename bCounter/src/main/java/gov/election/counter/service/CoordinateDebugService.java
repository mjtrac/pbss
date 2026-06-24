/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package gov.election.counter.service;

import gov.election.counter.model.BboxReport.*;
import gov.election.counter.model.ScanSession;
import gov.election.counter.service.CornerDetectionService.Point2D;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes an adjusted YAML file after each ballot image is processed,
 * showing every bounding-box coordinate transformed from the theoretical
 * (PDF-generated) coordinate system into the actual pixel coordinate
 * system of the scanned image.
 *
 * PURPOSE:
 * --------
 * The ballot PDF is generated at a precise theoretical size (e.g. 8.5 × 11 in).
 * When a physical ballot is scanned, the image may be:
 *   - Slightly scaled (scanner DPI is approximate)
 *   - Rotated or skewed (paper not perfectly straight)
 *   - Perspective-distorted (flatbed lid not closed, or camera scan)
 *
 * This service computes where each theoretical bounding box actually lands
 * on the scanned image, and writes that as a YAML file so you can:
 *   1. Load the adjusted YAML alongside the image in any image viewer or
 *      script and verify boxes are correctly positioned.
 *   2. Detect systematic errors in corner detection or the homography.
 *
 * COORDINATE TRANSFORM:
 * ---------------------
 * The homography H maps image pixels → canonical rect (used for the warp).
 * Its inverse H⁻¹ maps canonical rect → image pixels (used here).
 *
 * For each theoretical point (x_in, y_in) in inches:
 *   x_canonical = x_in * dpi          (convert to canonical pixels)
 *   y_canonical = y_in * dpi
 *   [x_img, y_img] = H⁻¹ × [x_canonical, y_canonical]
 *
 * The output YAML expresses all coordinates in IMAGE PIXELS (not inches)
 * since that is most directly useful for visual verification.
 *
 * ADDITIVE NESTING:
 * -----------------
 * The input YAML has additive offsets (contest offset is relative to
 * content area, indicator offset is relative to contest). The adjusted
 * YAML outputs ABSOLUTE image pixel coordinates for every element,
 * so that each box can be drawn independently on the image without
 * needing to sum parent offsets.
 *
 * DISABLING:
 * ----------
 * Set session.debugCoordinates = false (the default) to skip this step
 * entirely. The code remains in the package but produces no output and
 * adds no measurable overhead.
 */
@Service
public class CoordinateDebugService {

    private static final Logger log =
        LoggerFactory.getLogger(CoordinateDebugService.class);

    private final HomographyService homographyService;

    public CoordinateDebugService(HomographyService homographyService) {
        this.homographyService = homographyService;
    }

    /**
     * Write an adjusted YAML file for one scanned image.
     *
     * The file is written to session.debugOutputFolder (or the image folder
     * if debugOutputFolder is blank), with the same stem as the image plus
     * "_adjusted.yaml".
     *
     * @param imagePath       path to the scanned image file
     * @param yamlSourcePath  path to the YAML file used for layout data
     * @param layout          the PageLayout selected for this image
     * @param detectedCorners the four content-box corners found in the image,
     *                        or null if corner detection failed
     * @param session         the current scan session (for dpi and flags)
     */
    public void writeAdjustedYaml(Path imagePath,
                                   String yamlSourcePath,
                                   PageLayout layout,
                                   Point2D[] detectedCorners,
                                   ScanSession session) {
        String imageStem = stemOf(imagePath);
        Path outDir = outputDir(imagePath, session);

        // ── Log which YAML file is being used ──────────────────────────────────
        log.info(
            "Image: {}  —  using YAML: {}  —  writing adjusted coords to: {}",
            imagePath.getFileName(),
            yamlSourcePath != null ? yamlSourcePath : "(unknown)",
            outDir.resolve(imageStem + "_adjusted.yaml"));

        // ── Compute the canonical → image transform ────────────────────────────
        // If corners were found, compute the real homography inverse.
        // If not, fall back to a pure scale transform (identity with DPI scale).
        double[] Hinv = null;
        if (detectedCorners != null) {
            Hinv = homographyService.computeCanonicalToImageTransform(
                detectedCorners, layout.contentAreaWidth, layout.contentAreaHeight,
                session.dpi);
        }

        if (Hinv == null) {
            log.warn("No valid transform available; adjusted YAML will use scaled coords only.");
        }

        // ── Build the output YAML structure ────────────────────────────────────
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sourceYaml",    yamlSourcePath != null ? yamlSourcePath : "(unknown)");
        root.put("sourceImage",   imagePath.toString());
        root.put("pageNumber",    layout.pageNumber);
        root.put("dpi",           session.dpi);
        root.put("cornersFound",  detectedCorners != null);
        root.put("transformType", Hinv != null ? "perspective_homography" : "scale_only");
        root.put("note",
            "All coordinates are in IMAGE PIXELS (origin = image top-left). " +
            "Absolute positions: no nesting — each box is self-contained.");

        // ── Content area ──────────────────────────────────────────────────────
        // Canonical origin is (0,0) in the warped image.
        // Page-absolute position = contentAreaOffsetLeft/Top.
        Map<String, Object> contentAreaMap = transformBox(
            0, 0, layout.contentAreaWidth, layout.contentAreaHeight,
            layout.contentAreaOffsetLeft, layout.contentAreaOffsetTop,
            Hinv, session.dpi, "content area bounding box");
        root.put("ballotContentArea", contentAreaMap);

        // ── Contests and indicators ────────────────────────────────────────────
        // Both contest.offsetLeft/Top AND indicator.offsetLeft/Top are relative
        // to the content area upper-left (same origin).  Do NOT add them together.
        // MarkerAnalysisService samples at: px = round(ind.offsetLeft * dpi)
        // The contest offset is only used for grouping/display, not for sampling.
        List<Map<String, Object>> contestList = new ArrayList<>();
        for (ContestBox contest : layout.contests) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id",    contest.id);
            cm.put("title", contest.title);

            // All coords are page-absolute inches from (0,0).
            // Canonical (warped) coords = page-absolute minus content-area offset.
            double cCanonL = contest.offsetLeft - layout.contentAreaOffsetLeft;
            double cCanonT = contest.offsetTop  - layout.contentAreaOffsetTop;
            cm.put("boundingBox", transformBox(
                cCanonL, cCanonT,           // canonical (warped image)
                contest.width, contest.height,
                contest.offsetLeft, contest.offsetTop,  // page-absolute for display
                Hinv, session.dpi, contest.title));

            List<Map<String, Object>> indList = new ArrayList<>();
            for (IndicatorBox ind : contest.indicators) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("candidateId",   ind.candidateId);
                im.put("candidateName", ind.candidateName);

                // Canonical = page-absolute minus content-area offset
                double iCanonL = ind.offsetLeft - layout.contentAreaOffsetLeft;
                double iCanonT = ind.offsetTop  - layout.contentAreaOffsetTop;
                im.put("indicator", transformBox(
                    iCanonL, iCanonT,       // canonical
                    ind.width, ind.height,
                    ind.offsetLeft, ind.offsetTop,  // page-absolute
                    Hinv, session.dpi, ind.candidateName));
                indList.add(im);
            }
            cm.put("candidates", indList);
            contestList.add(cm);
        }
        root.put("contests", contestList);

        // ── Write the file ─────────────────────────────────────────────────────
        try {
            Files.createDirectories(outDir);
            Path outFile = outDir.resolve(imageStem + "_adjusted.yaml");
            Files.writeString(outFile, toYaml(root, 0));
            log.info("Adjusted YAML written:" + outFile);
        } catch (Exception e) {
            log.warn("Could not write adjusted YAML:" + e.getMessage());
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Transform a bounding box from theoretical (inches) to image pixels.
     *
     * The box is defined by its upper-left corner (leftIn, topIn) and size
     * (widthIn, heightIn), all in inches.  All four corners are transformed
     * through the homography to account for perspective distortion, which
     * means the output box may be a general quadrilateral rather than an
     * axis-aligned rectangle.
     *
     * The output map contains:
     *   topLeft, topRight, bottomRight, bottomLeft — each {x, y} in image pixels
     *   boundingRect — the axis-aligned bounding rectangle of the four corners
     *                  {x, y, width, height} in image pixels
     *
     * @param leftIn   theoretical left edge in inches (absolute, from page origin)
     * @param topIn    theoretical top edge in inches (absolute, from page origin)
     * @param widthIn  box width in inches
     * @param heightIn box height in inches
     * @param Hinv     canonical→image homography matrix (may be null → scale only)
     * @param dpi      pixels per inch
     * @param label    human-readable label for logging
     */
    /**
     * Transform a bounding box to image pixel coordinates.
     *
     * @param canonLeft  left edge in content-area-relative inches (passed to H⁻¹)
     * @param canonTop   top edge in content-area-relative inches  (passed to H⁻¹)
     * @param widthIn    box width in inches
     * @param heightIn   box height in inches
     * @param theoLeft   full-page absolute left in inches (for display only)
     * @param theoTop    full-page absolute top in inches  (for display only)
     * @param Hinv       canonical→image homography (may be null)
     * @param dpi        pixels per inch
     * @param label      human-readable label for logging
     */
    private Map<String, Object> transformBox(double canonLeft, double canonTop,
                                              double widthIn, double heightIn,
                                              double theoLeft, double theoTop,
                                              double[] Hinv, int dpi,
                                              String label) {
        // Convert canonical (content-area-relative) to pixels for H⁻¹
        double cxL = canonLeft           * dpi;
        double cyT = canonTop            * dpi;
        double cxR = (canonLeft + widthIn)  * dpi;
        double cyB = (canonTop  + heightIn) * dpi;

        // Transform each corner through H⁻¹ to get image pixel coordinates
        double[][] corners = {
            transformPt(Hinv, cxL, cyT, dpi, canonLeft,           canonTop),           // TL
            transformPt(Hinv, cxR, cyT, dpi, canonLeft + widthIn, canonTop),           // TR
            transformPt(Hinv, cxR, cyB, dpi, canonLeft + widthIn, canonTop + heightIn),// BR
            transformPt(Hinv, cxL, cyB, dpi, canonLeft,           canonTop + heightIn) // BL
        };

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("theoreticalLeft_in",  fmt(theoLeft));
        out.put("theoreticalTop_in",   fmt(theoTop));
        out.put("theoreticalWidth_in", fmt(widthIn));
        out.put("theoreticalHeight_in",fmt(heightIn));
        out.put("topLeft",     ptMap(corners[0]));
        out.put("topRight",    ptMap(corners[1]));
        out.put("bottomRight", ptMap(corners[2]));
        out.put("bottomLeft",  ptMap(corners[3]));

        // Axis-aligned bounding rect of the four image-space corners
        double minX = min4(corners[0][0], corners[1][0], corners[2][0], corners[3][0]);
        double minY = min4(corners[0][1], corners[1][1], corners[2][1], corners[3][1]);
        double maxX = max4(corners[0][0], corners[1][0], corners[2][0], corners[3][0]);
        double maxY = max4(corners[0][1], corners[1][1], corners[2][1], corners[3][1]);

        Map<String, Object> br = new LinkedHashMap<>();
        br.put("x",      fmt(minX));
        br.put("y",      fmt(minY));
        br.put("width",  fmt(maxX - minX));
        br.put("height", fmt(maxY - minY));
        out.put("boundingRect", br);
        return out;
    }

    /**
     * Transform a single canonical-pixel point to image pixels.
     * Falls back to pure DPI scaling if Hinv is null.
     */
    private double[] transformPt(double[] Hinv,
                                  double cx, double cy,
                                  int dpi, double xIn, double yIn) {
        if (Hinv != null) {
            return homographyService.transformPoint(Hinv, cx, cy);
        }
        // Fallback: assume no distortion, just scale inches → pixels
        return new double[]{ xIn * dpi, yIn * dpi };
    }

    private Map<String, Object> ptMap(double[] xy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", fmt(xy[0]));
        m.put("y", fmt(xy[1]));
        return m;
    }

    private String stemOf(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private Path outputDir(Path imagePath, ScanSession session) {
        if (session.debugOutputFolder != null && !session.debugOutputFolder.isBlank()) {
            return Paths.get(session.debugOutputFolder);
        }
        return imagePath.getParent() != null ? imagePath.getParent() : Paths.get(".");
    }

    private double min4(double a, double b, double c, double d) {
        return Math.min(Math.min(a, b), Math.min(c, d));
    }
    private double max4(double a, double b, double c, double d) {
        return Math.max(Math.max(a, b), Math.max(c, d));
    }
    private String fmt(double v) {
        return String.format("%.2f", v);
    }

    // ── Minimal YAML serialiser ────────────────────────────────────────────────
    // SnakeYAML is available in the classpath but using it directly for nested
    // LinkedHashMaps sometimes reorders keys.  This hand-rolled serialiser
    // preserves insertion order, which makes the debug file much easier to read.

    @SuppressWarnings("unchecked")
    private String toYaml(Object obj, int indent) {
        String pad = "  ".repeat(indent);
        StringBuilder sb = new StringBuilder();
        if (obj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
                String key = String.valueOf(e.getKey());
                Object val = e.getValue();
                if (val instanceof Map || val instanceof List) {
                    sb.append(pad).append(key).append(":\n");
                    sb.append(toYaml(val, indent + 1));
                } else {
                    sb.append(pad).append(key).append(": ").append(yamlScalar(val)).append("\n");
                }
            }
        } else if (obj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map || item instanceof List) {
                    // First key of first entry gets the "- " prefix
                    String block = toYaml(item, indent + 1);
                    String[] lines = block.split("\n", 2);
                    if (lines.length > 0) {
                        sb.append(pad).append("- ").append(lines[0].stripLeading()).append("\n");
                        if (lines.length > 1) sb.append(lines[1]).append("\n");
                    }
                } else {
                    sb.append(pad).append("- ").append(yamlScalar(item)).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String yamlScalar(Object v) {
        if (v == null) return "null";
        String s = String.valueOf(v);
        // Quote strings that look ambiguous
        if (s.contains(":") || s.contains("#") || s.isEmpty() ||
                s.equals("true") || s.equals("false") || s.equals("null")) {
            return "\"" + s.replace("\"", "\\\"") + "\"";
        }
        return s;
    }
}
