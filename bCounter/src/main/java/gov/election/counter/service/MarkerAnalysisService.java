/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package gov.election.counter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import gov.election.counter.model.BboxReport.*;
import gov.election.counter.service.CornerDetectionService.Point2D;
import gov.election.counter.service.HomographyService;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;

/**
 * Analyses the darkened pixel fraction within each indicator bounding box.
 *
 * COORDINATE SYSTEM (simplified — all page-absolute):
 *
 * All offsets in the YAML/XML report are PAGE-ABSOLUTE inches from the
 * image top-left (0,0).  No summing of parent offsets is needed.
 *
 * To find the indicator in the WARPED image:
 *   The warp maps the detected content-area corners to a canonical rectangle
 *   that starts at (0,0).  The content-area's own page-absolute position is
 *   contentAreaOffsetLeft / contentAreaOffsetTop.
 *   So the indicator's position WITHIN the warped image is:
 *     warpedX = (indicator.offsetLeft - page.contentAreaOffsetLeft) * dpi
 *     warpedY = (indicator.offsetTop  - page.contentAreaOffsetTop)  * dpi
 *
 * To report the indicator's position in the ORIGINAL IMAGE (matching GIMP):
 *     imageX = round(indicator.offsetLeft * imageDpi)
 *     imageY = round(indicator.offsetTop  * imageDpi)
 *   where imageDpi is the DPI read from the image file metadata (or inferred).
 */
@Service
public class MarkerAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(MarkerAnalysisService.class);


    private final HomographyService homographyService;

    public MarkerAnalysisService(HomographyService homographyService) {
        this.homographyService = homographyService;
    }

    /**
     * Analyse one indicator box.
     *
     * @param warped       perspective-corrected content-area image
     * @param page         page layout with content area offset in page-absolute inches
     * @param contest      contest containing this indicator
     * @param indicator    the indicator to sample
     * @param warpDpi      pixels per inch of the warped (canonical) image
     * @param imageDpi     actual DPI of the original scanned image
     * @param threshold    luminance below which a pixel is dark
     * @param darkPctMin   minimum dark% to declare marked
     * @param Hinv         canonical→image homography (may be null)
     * @param detectedTL   detected top-left corner of content box (may be null)
     */
    public MarkingResult analyse(BufferedImage warped,
                                  PageLayout page,
                                  ContestBox contest,
                                  IndicatorBox indicator,
                                  int warpDpi,
                                  double imageDpi,
                                  int threshold,
                                  double darkPctMin,
                                  double[] Hinv,
                                  Point2D detectedTL) {
        return analyse(warped, page, contest, indicator, warpDpi, imageDpi,
                       threshold, darkPctMin, Hinv, detectedTL, null, null);
    }

    public MarkingResult analyse(BufferedImage warped,
                                  PageLayout page,
                                  ContestBox contest,
                                  IndicatorBox indicator,
                                  int warpDpi,
                                  double imageDpi,
                                  int threshold,
                                  double darkPctMin,
                                  double[] Hinv,
                                  Point2D detectedTL,
                                  BufferedImage originalImage,
                                  HomographyService homographyService) {
        MarkingResult result = new MarkingResult();
        result.contestTitle  = contest.title;
        result.candidateId   = indicator.candidateId;
        result.candidateName = indicator.candidateName;

        // ── Warped-image pixel position ─────────────────────────────────────
        // Subtract the content-area offset to get content-area-relative coords,
        // then multiply by warpDpi.
        double relLeft = indicator.offsetLeft - page.contentAreaOffsetLeft;
        double relTop  = indicator.offsetTop  - page.contentAreaOffsetTop;
        int px = (int) Math.round(relLeft * warpDpi);
        int py = (int) Math.round(relTop  * warpDpi);
        int pw = Math.max(1, (int) Math.round(indicator.width  * warpDpi));
        int ph = Math.max(1, (int) Math.round(indicator.height * warpDpi));

        result.absLeft = px;
        result.absTop  = py;
        result.width   = pw;
        result.height  = ph;

        // ── Patch-warp mode: warp only this indicator's pixels ───────────────
        // originalImage != null signals patch-warp mode.
        // We warp the indicator region directly from the original scan using Hinv,
        // then sample from the resulting small patch starting at (0,0).
        if (originalImage != null && homographyService != null && Hinv != null) {
            warped = homographyService.warpIndicatorPatch(
                originalImage, Hinv, px, py, pw, ph);
            px = 0; py = 0;
            // result.absLeft/Top already set above (canonical position)
        }

        // ── Absolute image pixel position (matches GIMP) ────────────────────
        // All coordinates in the YAML are page-absolute inches from (0,0).
        // Multiply by imageDpi to get image pixels.
        result.imageX = (int) Math.round(indicator.offsetLeft * imageDpi);
        result.imageY = (int) Math.round(indicator.offsetTop  * imageDpi);

        // ── Arrow indicator: use central-zone presence detection ────────────
        // Arrow-style indicators use two inward-pointing triangles at the
        // left/right edges; the voter marks the empty centre zone.
        // Analysis ignores dark percentage and instead checks for any dark
        // pixel in a zone 1/4 the size of the bounding box, centred on it.
        if ("ARROW".equalsIgnoreCase(indicator.indicatorStyle)) {
            boolean marked = ArrowIndicatorAnalyzer.isMarked(warped, px, py, pw, ph, threshold);
            result.marked        = marked;
            result.darkPct       = marked ? 100.0 : 0.0;  // binary: 100% or 0%
            result.darkPixels    = 0;
            result.totalPixels   = 0;
            result.meanIntensity = 128.0;
            log.info("[ARROW] {}/{}: {}  {}",
                contest.title, indicator.candidateName,
                marked ? "VOTED" : "unmarked",
                ArrowIndicatorAnalyzer.zoneDescription(px, py, pw, ph));
            return result;
        }

        // ── Sample dark pixels from the warped image ────────────────────────
        int imgW = warped.getWidth(), imgH = warped.getHeight();
        int x0 = Math.max(0, px),         y0 = Math.max(0, py);
        int x1 = Math.min(imgW, px + pw), y1 = Math.min(imgH, py + ph);

        if (x0 >= x1 || y0 >= y1) {
            result.darkPct       = 0.0;
            result.darkPixels    = 0;
            result.totalPixels   = 0;
            result.meanIntensity = 255.0;
            result.marked        = false;
            return result;
        }

        long darkCount = 0, totalCount = 0, intensitySum = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int lum = luminance(warped.getRGB(x, y));
                intensitySum += lum;
                totalCount++;
                if (lum < threshold) darkCount++;
            }
        }

        result.darkPixels    = (int) darkCount;
        result.totalPixels   = (int) totalCount;
        result.darkPct       = totalCount > 0 ? 100.0 * darkCount / totalCount : 0.0;
        result.meanIntensity = totalCount > 0 ? (double) intensitySum / totalCount : 255.0;
        result.marked        = result.darkPct >= darkPctMin;

        // ── Edge-trim retry for borderline coverage (5–10%) ─────────────────
        // A dark% in this range may indicate that the bounding box captured only
        // part of the filled oval — e.g. due to slight misalignment or scanning
        // skew.  Shrinking each edge inward by 1px removes border pixels that
        // may be white page, concentrating the sample on the oval interior.
        // If the trimmed region then meets the darkPctMin threshold, accept it
        // as a voter's mark.
        if (!result.marked && result.darkPct >= 5.0 && result.darkPct < 10.0) {
            int tx0 = x0 + 1, ty0 = y0 + 1;
            int tx1 = x1 - 1, ty1 = y1 - 1;
            if (tx0 < tx1 && ty0 < ty1) {
                long tDark = 0, tTotal = 0, tIntensity = 0;
                for (int y = ty0; y < ty1; y++) {
                    for (int x = tx0; x < tx1; x++) {
                        int lum = luminance(warped.getRGB(x, y));
                        tIntensity += lum;
                        tTotal++;
                        if (lum < threshold) tDark++;
                    }
                }
                if (tTotal > 0) {
                    double trimmedPct = 100.0 * tDark / tTotal;
                    if (trimmedPct >= darkPctMin) {
                        result.marked        = true;
                        result.darkPct       = trimmedPct;
                        result.darkPixels    = (int) tDark;
                        result.totalPixels   = (int) tTotal;
                        result.meanIntensity = (double) tIntensity / tTotal;
                    }
                }
            }
        }

        return result;
    }

    private int luminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8)  & 0xFF;
        int b =  argb        & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }
}
