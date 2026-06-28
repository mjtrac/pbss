/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter -- licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package gov.election.counter.service;

import gov.election.counter.model.BboxReport.PageLayout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locates the four corners of the ballot content bounding box.
 *
 * ALGORITHM:
 *
 *   Step 1 -- Orientation.
 *     Scan upward from image bottom for a long horizontal dark run in the
 *     centre third.  If not found, ballot is upside-down -- rotate 180deg
 *     and restart.
 *
 *   Step 2 -- Follow the bottom border line to its endpoints.
 *     Starting from the qualifying run centre, walk leftward and rightward
 *     column by column, tracking the line's y position as it tilts.
 *     At each column scan a small vertical window to stay on the line.
 *
 *   Step 3 -- Follow the vertical side lines upward from each endpoint.
 *     The bbox has vertical border lines at its left and right edges,
 *     perpendicular to the bottom line.  Walk upward from each bottom
 *     endpoint, tracking x as the line tilts, to confirm the corner.
 *
 *   Step 4 -- Derive BL and BR mark centres.
 *     The orientation marks sit below the bottom line endpoints.
 *     Gap from bottom edge of border line to top edge of mark = 22px at 300dpi.
 *     Mark centre = endpoint_y + GAP_PX + markH/2.
 *     The mark x position is the same as the endpoint x (adjusted for tilt).
 *
 *   Step 5 -- Find TL and TR using YAML hints.
 *
 *   Step 6 -- Convert mark centres to bbox corners and sanity-check.
 *
 * DEBUG LOGGING:
 *   Every ballot logs: line y-centre, endpoints, all four mark centres,
 *   and whether the ballot was flipped.
 */
@Service
public class CornerDetectionService {

    private static final Logger log =
        LoggerFactory.getLogger(CornerDetectionService.class);

    @Value("${corner.detection.force-bottom-edge-fallback:false}")
    private boolean forceBottomEdgeFallback;

    public record Point2D(double x, double y) {}

    // Mark dimensions in inches (from BallotGenerationService: 9pt and 18pt)
    private static final double MARK_H_IN      = 9.0  / 72.0;   // ~0.125"
    private static final double MARK_SQ_W_IN   = 9.0  / 72.0;
    private static final double MARK_RECT_W_IN = 18.0 / 72.0;

    // Gap from bottom edge of border line to top edge of orientation mark
    // Measured from GIMP: y=4031 (line bottom) to y=4053 (mark top) = 22px at 300dpi
    private static final double MARK_BELOW_GAP_IN = 22.0 / 300.0;  // ~0.073"

    // Minimum dark-column run to qualify as the bbox border line (centre-third scan).
    // A "dark column" is one where any pixel in a vertical band is dark.
    // Using a band handles rotated lines that cross a row diagonally.
    private static final int MIN_BORDER_RUN_PX = 200;  // columns in centre-third band

    // Vertical band half-height for the column-dark test (px each side of test row)
    private static final int SEED_BAND_HALF = 8;

    // Search zone: how far from image bottom to look for the border line.
    // 1.5" handles up to ~10 deg rotation plus 80px distort-ballots padding.
    private static final double EDGE_NEAR_IN = 0.15;
    private static final double EDGE_FAR_IN  = 1.5;

    // Line-following: vertical window to search for line continuation (px each side)
    private static final int TRACK_HALF = 8;  // match SEED_BAND_HALF for tilted lines

    // Tolerance for YAML-hint searches (TL/TR)
    private static final double TOLERANCE_IN = 0.65;

    // -------------------------------------------------------------------------

    /** Overload without barcode offset. */
    public Point2D[] findContentBoxCorners(BufferedImage[] imageHolder,
                                            int dpi,
                                            double expectedWidthIn,
                                            double expectedHeightIn,
                                            PageLayout layout) {
        return findContentBoxCorners(imageHolder, dpi, expectedWidthIn,
            expectedHeightIn, layout, 0, 0);
    }

    public Point2D[] findContentBoxCorners(BufferedImage[] imageHolder,
                                            int dpi,
                                            double expectedWidthIn,
                                            double expectedHeightIn,
                                            PageLayout layout,
                                            double bcOffsetX, double bcOffsetY) {
        return findContentBoxCorners(imageHolder, dpi, expectedWidthIn,
            expectedHeightIn, layout, bcOffsetX, bcOffsetY, false);
    }

    private Point2D[] findContentBoxCorners(BufferedImage[] imageHolder,
                                             int dpi,
                                             double expectedWidthIn,
                                             double expectedHeightIn,
                                             PageLayout layout,
                                             double bcOffsetX, double bcOffsetY,
                                             boolean alreadyFlipped) {
        BufferedImage image = imageHolder[0];
        int w = image.getWidth(), h = image.getHeight();
        boolean[] dark = makeDark(image, w, h);

        // Step 1: find a qualifying run near the image bottom
        int[] seed = findBorderLineSeed(dark, w, h, dpi);
        if (seed == null) {
            if (alreadyFlipped) {
                log.error("No bottom border line found even after flipping");
                throw new RuntimeException("no bottom border line found");
            }
            log.debug("Bottom border line NOT found -- ballot appears upside-down, rotating 180deg");
            imageHolder[0] = rotateImage(image, Math.PI);
            return findContentBoxCorners(imageHolder, dpi, expectedWidthIn,
                expectedHeightIn, layout, 0, 0, true);  // zero offset: was from pre-flip coords
        }

        if (alreadyFlipped)
            log.debug("Ballot was flipped 180deg before processing");
        else
            log.debug("Ballot orientation: upright (no flip needed)");

        // seed = [seedX, seedY] -- centre of the qualifying run
        int seedX = seed[0], seedY = seed[1];

        // Find the actual dark pixel y at seedX within the band,
        // so followLine starts on a pixel that is truly dark.
        int actualSeedY = seedY;
        outer:
        for (int dy = 0; dy <= SEED_BAND_HALF; dy++) {
            for (int sign : new int[]{0, 1, -1}) {
                int ty = seedY + sign * dy;
                if (ty >= 0 && ty < h && dark[ty * w + seedX]) {
                    actualSeedY = ty;
                    break outer;
                }
            }
        }
        log.debug("Border line seed: ({},{}) actualY={}",
        seedX, seedY, actualSeedY);

        // Step 2: follow the line left and right from the seed
        int[] leftEnd  = followLine(dark, w, h, seedX, actualSeedY, -1); // walk left
        int[] rightEnd = followLine(dark, w, h, seedX, actualSeedY,  1); // walk right

        log.debug(
            "Bottom border line: seedY={}  left=({},{})  right=({},{})",
            seedY, leftEnd[0], leftEnd[1], rightEnd[0], rightEnd[1]);

        // Step 3: follow vertical side lines upward from each endpoint
        // (currently used for confirmation; endpoints already established)
        // The side lines run perpendicular to the bottom line.
        // For now we use the bottom endpoints directly for mark derivation.

        // Step 4: derive BL and BR mark centres
        // Mark sits below the line endpoint.
        // gap = MARK_BELOW_GAP_IN from bottom of line to top of mark.
        // mark centre y = endpoint_y + gap_px + markH_px/2
        int gapPx    = (int) Math.round(MARK_BELOW_GAP_IN * dpi);
        int markHPx  = Math.max(4, (int)(MARK_H_IN * dpi));
        int markSqPx = Math.max(4, (int)(MARK_SQ_W_IN * dpi));
        int markRtPx = Math.max(6, (int)(MARK_RECT_W_IN * dpi));
        int tolPx    = (int)(TOLERANCE_IN * dpi);

        int blCentreY = leftEnd[1]  + gapPx + markHPx / 2;
        int brCentreY = rightEnd[1] + gapPx + markHPx / 2;

        // For x: search a small window around the endpoint x
        // The mark may be offset slightly due to tilt
        int blSearchX = leftEnd[0];
        int brSearchX = rightEnd[0];

        double[] bl = findPeakBlob(dark, w, h,
            Math.max(0, blSearchX - markSqPx),
            Math.min(w, blSearchX + markSqPx * 2),
            Math.max(0, blCentreY - markHPx),
            Math.min(h, blCentreY + markHPx),
            markSqPx / 2, markHPx / 2);

        double[] br = findPeakBlob(dark, w, h,
            Math.max(0, brSearchX - markSqPx * 2),
            Math.min(w, brSearchX + markSqPx),
            Math.max(0, brCentreY - markHPx),
            Math.min(h, brCentreY + markHPx),
            markSqPx / 2, markHPx / 2);

        if (bl != null)
            log.debug(
                "BL orientation mark: centre=({},{}) blobW={} blobH={}",
                bl[0], bl[1], bl[2], bl[3]);
        else
            log.warn(
                "BL orientation mark: NOT FOUND (expected near x={} y={})",
                blSearchX, blCentreY);

        if (br != null)
            log.debug(
                "BR orientation mark: centre=({},{}) blobW={} blobH={}",
                br[0], br[1], br[2], br[3]);
        else
            log.warn(
                "BR orientation mark: NOT FOUND (expected near x={} y={})",
                brSearchX, brCentreY);

        // Orientation check: if the RIGHT bottom mark is significantly wider than
        // the LEFT bottom mark, the rectangle mark (normally TL) is now at
        // bottom-right -- ballot is upside-down.
        // Rectangle mark is ~2x the width of a square mark (aspect >= 1.6).
        if (!alreadyFlipped && bl != null && br != null) {
            double blAspect = bl[2] / Math.max(1, bl[3]);
            double brAspect = br[2] / Math.max(1, br[3]);
            log.debug(
                "Bottom mark aspects: BL={} BR={} (rect threshold=1.6)",
                blAspect, brAspect);
            // Rectangle mark (TL in upright orientation) has aspect ~2x square marks.
            // If it appears at bottom-right → ballot is upside-down.
            // If it appears at bottom-left → ballot is upside-down AND mirrored
            //   (shouldn't happen with physical scanning but handle defensively).
            boolean brIsRect = brAspect >= 1.6 && brAspect > blAspect * 1.4;
            boolean blIsRect = blAspect >= 1.6 && blAspect > brAspect * 1.4;
            if (brIsRect || blIsRect) {
                log.debug(
                    "{} mark is rectangle -- ballot is upside-down, rotating 180deg",
                    brIsRect ? "BR" : "BL");
                imageHolder[0] = rotateImage(image, Math.PI);
                return findContentBoxCorners(imageHolder, dpi, expectedWidthIn,
                    expectedHeightIn, layout, 0, 0, true);  // zero offset: was from pre-flip coords
            }
            // If neither bottom mark is a rectangle, the ballot may still be
            // upside-down if both marks are small (the actual BL/BR square marks
            // are at the top in upright orientation — they should NOT be at the bottom).
            // Detect this: if TL/TR hints are in the lower half of the image,
            // the ballot is probably upside-down.
            if (layout != null && layout.cornerMarks != null) {
                double tlHintY = layout.cornerMarks[0][1] * dpi;  // expected TL y in pixels
                double imageMidY = imageHolder[0].getHeight() / 2.0;
                if (tlHintY > imageMidY) {
                    log.debug("TL hint is in lower half of image -- ballot is upside-down, rotating 180deg");
                    imageHolder[0] = rotateImage(image, Math.PI);
                    return findContentBoxCorners(imageHolder, dpi, expectedWidthIn,
                        expectedHeightIn, layout, 0, 0, true);  // zero offset: was from pre-flip coords
                }
            }
        }

        // ── Step 4b: Compute tilt from BL/BR, project to find PTL/PTR ──────────
        //
        // Rather than relying on absolute YAML Y positions for TL/TR (which fail
        // for large-header ballots where content starts much lower than standard),
        // we use the physical page geometry:
        //
        //  1. BL and BR give us the bottom axis — from these we compute tilt angle
        //     and left/right X positions projected along the tilted axis.
        //  2. We project upward along the left/right tilted verticals to the top
        //     of the page to find PTL/PTR.
        //  3. Once PTL/PTR and BL/BR are found, we interpolate to find TL/TR
        //     using the YAML fractional position (TL_frac = (TL.y-PTL.y)/(BL.y-PTL.y)).
        //  4. A vertical-line sanity check confirms dark pixels exist between
        //     PTL→BL and PTR→BR on the page border.
        //
        // PTL/PTR upside-down check: if what we find near the TOP looks like
        // LARGE rectangle marks (aspect ratio ≥ 1.6, same as TL mark), and we
        // have not already flipped, it means we found PTL/PTR at the BOTTOM —
        // ballot is upside down → flip and restart.

        double[] ptl = null, ptr = null;
        boolean haveBothBottomMarks = (bl != null && br != null);

        if (haveBothBottomMarks && layout != null
                && layout.pageMarks != null && layout.pageMarks.length >= 2) {

            // ── Compute tilt from BL→BR ──────────────────────────────────────
            // The tilt angle of the bottom line gives us the page orientation.
            double blX = bl[0], blY = bl[1];
            double brX = br[0], brY = br[1];
            double tiltAngle = Math.atan2(brY - blY, brX - blX); // radians, typically tiny
            double cosT = Math.cos(tiltAngle), sinT = Math.sin(tiltAngle);
            // Unit vectors along (horizontal) and perpendicular (vertical, upward) axes
            double axH_x = cosT,  axH_y = sinT;   // along bottom line, left→right
            double axV_x = sinT,  axV_y = -cosT;  // perpendicular, pointing UP

            log.debug("Tilt angle: {}° from BL({},{}) BR({},{})",
                String.format("%.3f", Math.toDegrees(tiltAngle)), (int)blX, (int)blY, (int)brX, (int)brY);

            // ── Project to expected PTL/PTR positions ────────────────────────
            // YAML gives PTL and BL Y positions; their difference in inches
            // tells us how far up (along the tilted vertical) PTL is from BL.
            double yamlPtlY = layout.pageMarks[0][1];   // inches from top
            double yamlBlY  = layout.cornerMarks[3][1]; // inches from top
            double yamlBrY  = layout.cornerMarks[2][1];
            double yamlPtrY = layout.pageMarks[1][1];

            // Distance from BL up to PTL along the page (inches)
            double blToPtlIn = yamlBlY - yamlPtlY;  // positive = upward on page
            double brToPtrIn = yamlBrY - yamlPtrY;
            double blToPtlPx = blToPtlIn * dpi;
            double brToPtrPx = brToPtrIn * dpi;

            // Predicted PTL/PTR centres (project upward along tilted vertical)
            int ptlPredX = (int)Math.round(blX + axV_x * blToPtlPx);
            int ptlPredY = (int)Math.round(blY + axV_y * blToPtlPx);
            int ptrPredX = (int)Math.round(brX + axV_x * brToPtrPx);
            int ptrPredY = (int)Math.round(brY + axV_y * brToPtrPx);

            log.debug("PTL predicted: ({},{})  PTR predicted: ({},{})",
                ptlPredX, ptlPredY, ptrPredX, ptrPredY);

            // Search for PTL/PTR in a tight window around predicted positions
            int pageTolPx = (int)(0.30 * dpi);
            ptl = findPeakBlob(dark, w, h,
                Math.max(0, ptlPredX - pageTolPx), Math.min(w, ptlPredX + pageTolPx),
                Math.max(0, ptlPredY - pageTolPx), Math.min(h, ptlPredY + pageTolPx),
                markRtPx / 2, markHPx / 2);
            ptr = findPeakBlob(dark, w, h,
                Math.max(0, ptrPredX - pageTolPx), Math.min(w, ptrPredX + pageTolPx),
                Math.max(0, ptrPredY - pageTolPx), Math.min(h, ptrPredY + pageTolPx),
                markRtPx / 2, markHPx / 2);

            if (ptl != null) log.debug("PTL found: ({},{})", (int)ptl[0], (int)ptl[1]);
            else             log.debug("PTL not found near ({},{})", ptlPredX, ptlPredY);
            if (ptr != null) log.debug("PTR found: ({},{})", (int)ptr[0], (int)ptr[1]);
            else             log.debug("PTR not found near ({},{})", ptrPredX, ptrPredY);

            // PTL/PTR upside-down check removed — PTL/PTR are always 18x9pt
            // rectangles (aspect ~2.0) so aspect ratio cannot distinguish them
            // from TL. Upside-down detection is handled earlier via BL/BR aspect
            // ratio and TL hint position checks.

            // Vertical line sanity check: confirm the content box border line
            // exists just above the BL/BR marks.
            // BL/BR centres are (gapPx + markHPx/2) below the border line.
            // Start check just above the mark top edge and scan a short distance.
            int markAbovePx = gapPx + markHPx;
            int vertCheckPx = (int)(0.10 * dpi);
            int vertWindowPx = (int)(0.05 * dpi);
            if (ptl != null) {
                int darkCount = 0;
                int startY = (int)Math.round(blY) - gapPx - markHPx / 2; // border line Y
                for (int dy = 0; dy <= vertCheckPx; dy++) {
                    int sy = startY - dy;
                    int sx = (int)Math.round(blX);
                    if (sy < 0) break;
                    for (int dx = -vertWindowPx; dx <= vertWindowPx; dx++) {
                        int px = sx + dx;
                        if (px >= 0 && px < w && dark[sy * w + px]) { darkCount++; break; }
                    }
                }
                if (darkCount == 0) {
                    log.debug("Left vertical sanity check failed (0/{}) discarding PTL", vertCheckPx);
                    ptl = null;
                } else {
                    log.debug("Left vertical sanity check passed ({}/{})", darkCount, vertCheckPx);
                }
            }
            if (ptr != null) {
                int darkCount = 0;
                int startY = (int)Math.round(brY) - gapPx - markHPx / 2; // border line Y
                for (int dy = 0; dy <= vertCheckPx; dy++) {
                    int sy = startY - dy;
                    int sx = (int)Math.round(brX);
                    if (sy < 0) break;
                    for (int dx = -vertWindowPx; dx <= vertWindowPx; dx++) {
                        int px = sx + dx;
                        if (px >= 0 && px < w && dark[sy * w + px]) { darkCount++; break; }
                    }
                }
                if (darkCount == 0) {
                    log.debug("Right vertical sanity check failed (0/{}) discarding PTR", vertCheckPx);
                    ptr = null;
                } else {
                    log.debug("Right vertical sanity check passed ({}/{})", darkCount, vertCheckPx);
                }
            }
        } // end if (haveBothBottomMarks)

        // ── Step 5: Find TL and TR ────────────────────────────────────────────
        //
        // If PTL/PTR and BL/BR are all detected and verified, interpolate TL/TR
        // positions using the YAML fraction along the left/right page sides.
        // This correctly handles any header height because we measure from
        // actual detected mark positions, not absolute YAML Y coordinates.
        //
        // Fall back to YAML hint search if PTL/PTR unavailable.

        if (layout == null || layout.cornerMarks == null || layout.cornerMarks.length < 4) {
            log.warn("No YAML corner hints -- cannot find TL/TR marks");
            return null;
        }

        double[][] hints = layout.cornerMarks;
        if (bcOffsetX != 0 || bcOffsetY != 0) {
            hints = new double[4][2];
            for (int i = 0; i < 4; i++) {
                hints[i][0] = layout.cornerMarks[i][0] + bcOffsetX / dpi;
                hints[i][1] = layout.cornerMarks[i][1] + bcOffsetY / dpi;
            }
        }

        int tlCx, tlCy, trCx, trCy;
        int smallTolPx = (int)(0.20 * dpi); // tight window for geometrically predicted positions

        if (ptl != null && ptr != null && bl != null && br != null
                && layout.pageMarks != null && layout.pageMarks.length >= 2) {
            // Geometric prediction disabled — interpolation unreliable for
            // large-header ballots where PTL/PTR fraction differs significantly.
            // PTL/PTR stored in YAML for future use.

            // YAML fractional positions: how far TL/TR are from PTL/PTR toward BL/BR
            double yamlPtlY = layout.pageMarks[0][1];
            double yamlBlY  = layout.cornerMarks[3][1];
            double yamlTlY  = layout.cornerMarks[0][1];
            double yamlPtrY = layout.pageMarks[1][1];
            double yamlBrY  = layout.cornerMarks[2][1];
            double yamlTrY  = layout.cornerMarks[1][1];

            double tlFrac = (yamlBlY - yamlPtlY) > 0
                ? (yamlTlY - yamlPtlY) / (yamlBlY - yamlPtlY) : 0.5;
            double trFrac = (yamlBrY - yamlPtrY) > 0
                ? (yamlTrY - yamlPtrY) / (yamlBrY - yamlPtrY) : 0.5;

            // Interpolate along detected left side (PTL→BL) and right side (PTR→BR)
            tlCx = (int)Math.round(ptl[0] + tlFrac * (bl[0] - ptl[0]));
            tlCy = (int)Math.round(ptl[1] + tlFrac * (bl[1] - ptl[1]));
            trCx = (int)Math.round(ptr[0] + trFrac * (br[0] - ptr[0]));
            trCy = (int)Math.round(ptr[1] + trFrac * (br[1] - ptr[1]));

            log.debug("TL/TR predicted from PTL+BL / PTR+BR geometry: TL=({},{}) TR=({},{})",
                tlCx, tlCy, trCx, trCy);

        } else {
            // Fall back to YAML hint positions with full tolerance
            tlCx = (int)(hints[0][0] * dpi);
            tlCy = (int)(hints[0][1] * dpi);
            trCx = (int)(hints[1][0] * dpi);
            trCy = (int)(hints[1][1] * dpi);
            smallTolPx = tolPx;
            log.debug("TL/TR from YAML hints (PTL/PTR unavailable): TL=({},{}) TR=({},{})",
                tlCx, tlCy, trCx, trCy);
        }

        double[] tl = findPeakBlob(dark, w, h,
            Math.max(0, tlCx - smallTolPx), Math.min(w, tlCx + smallTolPx),
            Math.max(0, tlCy - smallTolPx), Math.min(h, tlCy + smallTolPx),
            markRtPx / 2, markHPx / 2);

        double[] tr = findPeakBlob(dark, w, h,
            Math.max(0, trCx - smallTolPx), Math.min(w, trCx + smallTolPx),
            Math.max(0, trCy - smallTolPx), Math.min(h, trCy + smallTolPx),
            markSqPx / 2, markHPx / 2);

        log.debug("TL search=({},{})±{}  TR search=({},{})±{}",
            tlCx, tlCy, smallTolPx, trCx, trCy, smallTolPx);
        if (tl != null)
            log.debug(
                "TL orientation mark: centre=({},{}) blobW={} blobH={}",
                tl[0], tl[1], tl[2], tl[3]);
        else
            log.warn("TL orientation mark: NOT FOUND");
        if (tr != null)
            log.debug(
                "TR orientation mark: centre=({},{}) blobW={} blobH={}",
                tr[0], tr[1], tr[2], tr[3]);
        else
            log.warn("TR orientation mark: NOT FOUND");

        // Assemble marks [TL, TR, BR, BL]
        Point2D[] marks = new Point2D[4];
        if (tl != null) marks[0] = new Point2D(tl[0], tl[1]);
        if (tr != null) marks[1] = new Point2D(tr[0], tr[1]);
        if (br != null) marks[2] = new Point2D(br[0], br[1]);
        if (bl != null) marks[3] = new Point2D(bl[0], bl[1]);

        int hits = 0;
        for (Point2D p : marks) if (p != null) hits++;
        if (hits < 2) {
            String missing = (bl == null && br == null) ? "no orientation markers found below bottom line"
                           : (bl == null)               ? "BL orientation marker not found"
                           : (br == null)               ? "BR orientation marker not found"
                           :                              "fewer than 2 total marks found";
            log.error("Corner detection failed:" + missing);
            // Store specific message for the UI via a thread-local or throw
            throw new RuntimeException(missing);
        }

        // Infer missing marks via parallelogram rule
        if (hits < 4) {
            log.debug(
                "Found {}/4 marks -- inferring missing via parallelogram", hits);
            if (marks[0] == null && marks[1] != null && marks[2] != null && marks[3] != null)
                marks[0] = new Point2D(marks[3].x() + marks[1].x() - marks[2].x(),
                                       marks[3].y() + marks[1].y() - marks[2].y());
            if (marks[1] == null && marks[0] != null && marks[2] != null && marks[3] != null)
                marks[1] = new Point2D(marks[0].x() + marks[2].x() - marks[3].x(),
                                       marks[0].y() + marks[2].y() - marks[3].y());
            if (marks[2] == null && marks[0] != null && marks[1] != null && marks[3] != null)
                marks[2] = new Point2D(marks[1].x() + marks[3].x() - marks[0].x(),
                                       marks[1].y() + marks[3].y() - marks[0].y());
            if (marks[3] == null && marks[0] != null && marks[1] != null && marks[2] != null)
                marks[3] = new Point2D(marks[0].x() + marks[2].x() - marks[1].x(),
                                       marks[0].y() + marks[2].y() - marks[1].y());
        }

        // Step 6: convert to bbox corners and sanity-check
        Point2D[] corners = marksToBboxCorners(marks, dpi, layout);
        if (corners == null) {
            log.error("Sanity check failed -- flagging for review");
            return null;
        }

        log.debug(
            "Corners: TL({},{}) TR({},{}) BR({},{}) BL({},{})",
            corners[0].x(), corners[0].y(), corners[1].x(), corners[1].y(),
            corners[2].x(), corners[2].y(), corners[3].x(), corners[3].y());

        return corners;
    }

    // =========================================================================
    // Step 1: find a qualifying dark run near the image bottom
    // =========================================================================

    /**
     * Scan upward from the image bottom for a long horizontal dark run
     * in the centre third of the image width.
     *
     * Uses a band-based test: for each candidate row y, each column x in the
     * centre third is considered "dark" if ANY pixel in the vertical band
     * [y - SEED_BAND_HALF, y + SEED_BAND_HALF] is dark at that x.
     * This makes the seed search robust to rotated lines that cross each
     * row at a shallow angle (producing only a few dark pixels per row).
     *
     * We scan upward from the image bottom and return the FIRST (lowest) row
     * where a qualifying run of dark columns is found — i.e. the bottom edge
     * of the border line.
     *
     * @return [seedX, seedY] centre of the qualifying run, or null if not found.
     */
    private int[] findBorderLineSeed(boolean[] dark, int w, int h, int dpi) {
        int nearPx = (int)(EDGE_NEAR_IN * dpi);
        int farPx  = (int)(EDGE_FAR_IN  * dpi);
        int leftBound  = w / 3;
        int rightBound = 2 * w / 3;

        for (int y = h - nearPx - 1; y >= h - farPx; y--) {
            if (y < 0) break;
            int y0 = Math.max(0,   y - SEED_BAND_HALF);
            int y1 = Math.min(h-1, y + SEED_BAND_HALF);

            // Find longest run of "dark columns" in the centre third
            int bestRunLen = 0, bestRunL = -1, bestRunR = -1;
            int runLen = 0, runL = -1;
            for (int x = leftBound; x <= rightBound && x < w; x++) {
                // Column x is dark if any pixel in the band is dark
                boolean colDark = false;
                for (int by = y0; by <= y1 && !colDark; by++) {
                    if (dark[by * w + x]) colDark = true;
                }
                if (colDark) {
                    if (runL < 0) runL = x;
                    runLen++;
                } else {
                    if (runLen > bestRunLen) {
                        bestRunLen = runLen; bestRunL = runL; bestRunR = x - 1;
                    }
                    runLen = 0; runL = -1;
                }
            }
            if (runLen > bestRunLen) {
                bestRunLen = runLen; bestRunL = runL;
                bestRunR = Math.min(rightBound, w - 1);
            }
            if (bestRunLen >= MIN_BORDER_RUN_PX) {
                int seedX = (bestRunL + bestRunR) / 2;
                log.debug(
                    "Border line seed found: y={} seedX={} runLen={} (band±{}px)",
                    y, seedX, bestRunLen, SEED_BAND_HALF);
                return new int[]{seedX, y};
            }
        }
        return null;
    }

    // =========================================================================
    // Step 2: follow the line from seed to its endpoint
    // =========================================================================

    /**
     * Follow a horizontal dark line from (startX, startY) in the given
     * x-direction (+1 = right, -1 = left), tracking y as the line tilts.
     * At each column, scan a small vertical window centred on the current y
     * to find the dark pixel.  Stop when no dark pixel is found in the window
     * (end of line) or when we reach the image edge.
     *
     * @return [endX, endY] -- the last column where the line was found.
     */
    private int[] followLine(boolean[] dark, int w, int h,
                              int startX, int startY, int dir) {
        int curY   = startY;
        int lastX  = startX;
        int lastY  = startY;

        for (int x = startX + dir; x >= 0 && x < w; x += dir) {
            // Search vertical window around current y
            int bestY   = -1;
            int bestDist = Integer.MAX_VALUE;
            int y0 = Math.max(0, curY - TRACK_HALF);
            int y1 = Math.min(h - 1, curY + TRACK_HALF);
            for (int y = y0; y <= y1; y++) {
                if (dark[y * w + x]) {
                    int dist = Math.abs(y - curY);
                    if (dist < bestDist) { bestDist = dist; bestY = y; }
                }
            }
            if (bestY < 0) break; // line ended
            curY  = bestY;
            lastX = x;
            lastY = bestY;
        }
        return new int[]{lastX, lastY};
    }

    // =========================================================================
    // Step 6: convert mark centres to bbox corners + sanity check
    // =========================================================================

    /**
     * Compute bbox corners from detected mark positions using the ballot's
     * local coordinate system derived from the marks themselves.
     *
     * Instead of applying a fixed rectilinear offset from mark to bbox corner
     * (which fails under shear/perspective because the offset direction is wrong),
     * we:
     *   1. Build two unit vectors from the detected marks:
     *      - rightVec: direction from TL mark to TR mark (the "right" axis)
     *      - downVec:  direction from TL mark to BL mark (the "down" axis)
     *   2. Express each mark→bbox corner offset in the design coordinate system
     *      as fractions of the design width and height.
     *   3. Apply those fractions along the detected rightVec/downVec axes.
     *
     * This correctly handles rotation, shear, and mild perspective.
     */
    private Point2D[] marksToBboxCorners(Point2D[] marks, int dpi, PageLayout layout) {
        if (marks == null || layout == null) return null;

        double caL = layout.contentAreaOffsetLeft;
        double caT = layout.contentAreaOffsetTop;
        double caR = caL + layout.contentAreaWidth;
        double caB = caT + layout.contentAreaHeight;
        double[][] bboxCorners = {
            {caL, caT}, {caR, caT}, {caR, caB}, {caL, caB}
        };
        double[][] origMarks = layout.cornerMarks;
        if (origMarks == null || origMarks.length < 4) {
            log.warn("No original corner marks in layout");
            return null;
        }

        for (int i = 0; i < 4; i++) {
            if (marks[i] == null) {
                log.error("Mark[" + i + "] null in bbox conversion");
                return null;
            }
        }

        // Design dimensions in inches
        // origMarks order: [TL, TR, BR, BL]
        double designW = origMarks[1][0] - origMarks[0][0]; // TR.x - TL.x
        double designH = origMarks[3][1] - origMarks[0][1]; // BL.y - TL.y

        if (Math.abs(designW) < 0.01 || Math.abs(designH) < 0.01) {
            log.warn("Design dimensions too small -- falling back to rectilinear offsets");
            Point2D[] corners = new Point2D[4];
            for (int i = 0; i < 4; i++) {
                double dxIn = bboxCorners[i][0] - origMarks[i][0];
                double dyIn = bboxCorners[i][1] - origMarks[i][1];
                corners[i] = new Point2D(marks[i].x() + dxIn * dpi, marks[i].y() + dyIn * dpi);
            }
            return corners;
        }

        // Build local axes from detected mark positions (in image pixels).
        // Use TL→TR for the right axis and TL→BL for the down axis.
        // Average left-side and top-side measurements for robustness.
        double tlx = marks[0].x(), tly = marks[0].y();
        double trx = marks[1].x(), try_ = marks[1].y();
        double blx = marks[3].x(), bly = marks[3].y();
        double brx = marks[2].x(), bry = marks[2].y();

        // Right vector (pixels per design-inch in the horizontal direction)
        double rxPerIn = ((trx - tlx) + (brx - blx)) / 2.0 / designW;
        double ryPerIn = ((try_ - tly) + (bry - bly)) / 2.0 / designW;

        // Down vector (pixels per design-inch in the vertical direction)
        double dxPerIn = ((blx - tlx) + (brx - trx)) / 2.0 / designH;
        double dyPerIn = ((bly - tly) + (bry - try_)) / 2.0 / designH;

        log.debug(
            "Local axes: right=({},{})px/in down=({},{})px/in",
            rxPerIn, ryPerIn, dxPerIn, dyPerIn);

        // For each mark, compute its bbox corner by adding the design offset
        // expressed in the detected local coordinate system.
        Point2D[] corners = new Point2D[4];
        for (int i = 0; i < 4; i++) {
            // Design offset from mark to bbox corner in inches
            double offRightIn = bboxCorners[i][0] - origMarks[i][0]; // along right axis
            double offDownIn  = bboxCorners[i][1] - origMarks[i][1]; // along down axis

            corners[i] = new Point2D(
                marks[i].x() + offRightIn * rxPerIn + offDownIn * dxPerIn,
                marks[i].y() + offRightIn * ryPerIn + offDownIn * dyPerIn);
        }

        log.debug(
            "Bbox corners from marks: TL({},{}) TR({},{}) BR({},{}) BL({},{})",
            corners[0].x(), corners[0].y(), corners[1].x(), corners[1].y(),
            corners[2].x(), corners[2].y(), corners[3].x(), corners[3].y());

        // Sanity check
        double topLen   = dist(corners[0], corners[1]);
        double rightLen = dist(corners[1], corners[2]);
        double botLen   = dist(corners[2], corners[3]);
        double leftLen  = dist(corners[3], corners[0]);

        boolean sidesOk =
            topLen > 0 && botLen > 0 && rightLen > 0 && leftLen > 0 &&
            Math.abs(topLen - botLen)    / Math.max(topLen,   botLen) < 0.20 &&
            Math.abs(rightLen - leftLen) / Math.max(rightLen, leftLen) < 0.20;

        boolean sizeOk = topLen / dpi > 3.0 && rightLen / dpi > 4.0;

        double detectedW = (topLen + botLen) / 2.0 / dpi;
        double detectedH = (rightLen + leftLen) / 2.0 / dpi;
        boolean dimOk = true;
        if (layout.contentAreaWidth > 0 && layout.contentAreaHeight > 0) {
            dimOk = Math.abs(detectedW - layout.contentAreaWidth)  < 1.0 &&
                    Math.abs(detectedH - layout.contentAreaHeight) < 1.0;
            if (!dimOk)
                log.warn(
                    "Dimension mismatch: {}x{}in detected, expected {}x{}in",
                    detectedW, detectedH,
                    layout.contentAreaWidth, layout.contentAreaHeight);
        }

        double[] topV   = {corners[1].x()-corners[0].x(), corners[1].y()-corners[0].y()};
        double[] rightV = {corners[2].x()-corners[1].x(), corners[2].y()-corners[1].y()};
        double coseTR   = Math.abs((topV[0]*rightV[0] + topV[1]*rightV[1])
                                   / (topLen * rightLen));
        boolean anglesOk = coseTR < 0.34;

        if (!sidesOk || !anglesOk || !sizeOk || !dimOk) {
            log.warn(
                "Sanity check failed: sides={} angles={} size={} dim={} ({}x{}in detected)",
                sidesOk, anglesOk, sizeOk, dimOk, detectedW, detectedH);
            return null;
        }

        double angleDeg = Math.toDegrees(Math.atan2(topV[1], topV[0]));
        log.debug(
            "Sanity check passed: {}x{}in at {}deg",
            detectedW, detectedH, angleDeg);

        return corners;
    }

    private double dist(Point2D a, Point2D b) {
        double dx = b.x()-a.x(), dy = b.y()-a.y();
        return Math.sqrt(dx*dx+dy*dy);
    }

    // =========================================================================
    // Core blob detection
    // =========================================================================

    private double[] findPeakBlob(boolean[] dark, int imgW, int imgH,
                                    int x0, int x1, int y0, int y1,
                                    int winW, int winH) {
        x0 = Math.max(0, x0); x1 = Math.min(imgW, x1);
        y0 = Math.max(0, y0); y1 = Math.min(imgH, y1);
        if (x1 - x0 < winW || y1 - y0 < winH) return null;

        int bestCount = 0, bestX = -1, bestY = -1;
        for (int y = y0; y <= y1 - winH; y++) {
            for (int x = x0; x <= x1 - winW; x++) {
                int cnt = 0;
                for (int dy = 0; dy < winH; dy++)
                    for (int dx = 0; dx < winW; dx++)
                        if (dark[(y+dy)*imgW + (x+dx)]) cnt++;
                if (cnt > bestCount) { bestCount = cnt; bestX = x; bestY = y; }
            }
        }
        if (bestCount < winW * winH / 4) return null;

        int bx0 = x1, bx1 = x0, by0 = y1, by1 = y0;
        long sumX = 0, sumY = 0, total = 0;
        for (int y = Math.max(y0, bestY - winH); y < Math.min(y1, bestY + 2*winH); y++) {
            for (int x = Math.max(x0, bestX - winW); x < Math.min(x1, bestX + 2*winW); x++) {
                if (dark[y*imgW+x]) {
                    if (x < bx0) bx0 = x; if (x > bx1) bx1 = x;
                    if (y < by0) by0 = y; if (y > by1) by1 = y;
                    sumX += x; sumY += y; total++;
                }
            }
        }
        if (total == 0) return null;
        return new double[]{
            (double)sumX / total, (double)sumY / total,
            bx1 - bx0 + 1.0,     by1 - by0 + 1.0
        };
    }

    // =========================================================================
    // Image helpers
    // =========================================================================

    private boolean[] makeDark(BufferedImage img, int w, int h) {
        int[] gray = toGrayscalePixels(img, w, h);
        int otsu = computeOtsuThreshold(gray);
        boolean[] dark = new boolean[w * h];
        for (int i = 0; i < gray.length; i++) dark[i] = gray[i] < otsu;
        return dark;
    }

    private int[] toGrayscalePixels(BufferedImage img, int w, int h) {
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int[] gray = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int r = (px[i] >> 16) & 0xFF;
            int g = (px[i] >>  8) & 0xFF;
            int b =  px[i]        & 0xFF;
            gray[i] = (r * 77 + g * 150 + b * 29) >> 8;
        }
        return gray;
    }

    private int computeOtsuThreshold(int[] gray) {
        int[] hist = new int[256];
        for (int v : gray) hist[v & 0xFF]++;
        long total = gray.length, sum = 0;
        for (int i = 0; i < 256; i++) sum += (long)i * hist[i];
        long sumB = 0, wB = 0;
        double maxVar = 0;
        int thresh = 128;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            long wF = total - wB;
            if (wF == 0) break;
            sumB += (long)t * hist[t];
            double mB = (double)sumB / wB;
            double mF = (double)(sum - sumB) / wF;
            double var = (double)wB * wF * (mB - mF) * (mB - mF);
            if (var > maxVar) { maxVar = var; thresh = t; }
        }
        return thresh;
    }

    private BufferedImage rotateImage(BufferedImage src, double angleRad) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = dst.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                           java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.translate(w / 2.0, h / 2.0);
        g.rotate(angleRad);
        g.translate(-w / 2.0, -h / 2.0);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }
}
