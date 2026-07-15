/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package com.mjtrac.counter.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors the bounding-box report produced by the election-ballot-system.
 *
 * COORDINATE SYSTEM:
 * ──────────────────
 * ALL offsets are PAGE-ABSOLUTE inches from the image upper-left corner (0,0).
 * The image origin matches GIMP's (0,0) = top-left of the scanned image.
 * There is NO nesting and NO summing required:
 *
 *   indicator position = (indicator.offsetLeft, indicator.offsetTop) in inches
 *   from the page top-left, at the image's actual DPI.
 *
 * To convert to pixels: px = round(offsetLeft * imageDpi)
 *                        py = round(offsetTop  * imageDpi)
 *
 * All measurements are in inches.  pageNumber is 1-based.
 */
public class BboxReport {

    /**
     * All layout data for one printed side (page).
     * contentAreaOffsetLeft/Top: page-absolute position of the heavy outer border,
     * in inches from image top-left (0,0).
     * All contest and indicator coordinates are also page-absolute — the content
     * area offset does NOT need to be added to them.
     */
    public static class PageLayout {
        public int    pageNumber;
        public String sourceFile = null;  // path to YAML/XML file this was loaded from
        public double contentAreaOffsetLeft;
        public double contentAreaOffsetTop;
        public double contentAreaWidth;
        public double contentAreaHeight;
        /** Expected QR barcode centre, page-absolute inches. 0 if not in YAML. */
        public double barcodeCentreX;
        public double barcodeCentreY;
        public List<ContestBox> contests = new ArrayList<>();
        /**
         * Content-box corner registration mark centres, page-absolute inches.
         * Order: TL[0], TR[1], BR[2], BL[3].  Each is {x, y}.
         * Null or empty if the report predates corner marks.
         */
        public double[][] cornerMarks;   // [4][2]: TL, TR, BR, BL

        /**
         * Page-level orientation mark centres, page-absolute inches.
         * Order: PTL[0] (left), PTR[1] (right).  Each is {x, y}.
         * These two 18x9pt rectangles sit just inside the top margin.
         * Null if the report predates page-level marks (older ballots).
         */
        public double[][] pageMarks;     // [2][2]: PTL, PTR
    }

    /**
     * One contest's bounding box.
     * offsetLeft/Top are page-absolute inches from image top-left (0,0).
     */
    public static class ContestBox {
        public Long   id;
        public String title;
        public String contestType = "PLURALITY";
        public int    maxVotes    = 1;
        public int    page;
        /** Offset from the content area upper-left, in inches. */
        public double offsetLeft;
        public double offsetTop;
        public double width;
        public double height;
        public List<IndicatorBox> indicators = new ArrayList<>();
    }

    /**
     * One vote indicator (oval, checkbox, rank box, etc.).
     *
     * offsetLeft/Top are PAGE-ABSOLUTE inches from the image top-left (0,0).
     * No summing with contest or content-area offsets is needed or correct.
     *
     * For ranked-choice contests the name includes a suffix like " (Rank 1)".
     */
    public static class IndicatorBox {
        public Long    candidateId;
        public String  candidateName;
        public boolean writeIn = false;
        /** Page-absolute offset in inches from image top-left (0,0). */
        public double  offsetLeft;
        public double  offsetTop;
        public double  width;
        public double  height;
        /** Indicator style as set in bBuilder: OVAL, CHECKBOX, ARROW, etc.
         *  Defaults to OVAL if absent from the YAML. */
        public String  indicatorStyle = "OVAL";
    }

    // ── Result types ──────────────────────────────────────────────────────────

    /** Result of scanning one ballot image. */
    public static class ScanResult {
        public String  imagePath;
        public String  imageName;
        public int     pageNumber;
        public String  barcodeData;
        public boolean barcodeDecoded;
        public boolean cornersFound;
        public boolean homographyValid;
        public String  errorMessage;
        /** Pixels-per-inch derived from the detected corner span / expected box size.
         *  Should match imageDpi if corner detection is correct. */
        public double  detectedDpiX = 0;
        public double  detectedDpiY = 0;
        /** DPI detected from image metadata or heuristic. */
        public double  imageDpi = 0;
        public List<MarkingResult> markings = new ArrayList<>();
        /** True if the ballot image was detected as upside-down and auto-rotated 180°. */
        public boolean wasRotated;

        /** Detected content bounding box corners in image pixels (origin = image top-left). */
        public int bboxTLx, bboxTLy;   // top-left corner
        public int bboxTRx, bboxTRy;   // top-right corner
        public int bboxBRx, bboxBRy;   // bottom-right corner
        public int bboxBLx, bboxBLy;   // bottom-left corner
        /** DPI of the canonical (warped) image produced during scanning. */
        public int warpDpi;
        /** Canonical content area dimensions in inches. */
        public double contentAreaWidth;
        public double contentAreaHeight;

        /** True if ScribbleDetectionService flagged ink outside all indicator zones. */
        public boolean scribbleFlagged;
        /** Suspicious (unexpected-ink) pixel count from ScribbleDetectionService. */
        public int scribblePixels;
        /** Path to the saved outline image highlighting the suspicious region, if any. */
        public String scribbleOutlinePath;

        /** Convenience: number of indicators counted as marked. */
        public int markedCount() {
            int n = 0; for (MarkingResult m : markings) if (m.marked) n++; return n;
        }
        /** Convenience: true if no indicators were marked. */
        public boolean noneMarked() { return markedCount() == 0; }
        /** Convenience: true if this result has an error. */
        public boolean hasError() { return errorMessage != null; }
    }

    /** Dark-pixel analysis for one indicator box in one image. */
    public static class MarkingResult {
        public Long    contestId;
        public String  contestTitle;
        public String  contestType = "PLURALITY";
        public int     maxVotes    = 1;
        public boolean writeIn     = false;
        public Long    candidateId;
        public String  candidateName;
        public double  darkPct;
        public int     darkPixels;
        public int     totalPixels;
        public double  meanIntensity;
        public boolean marked;
        /**
         * absLeft/absTop: position in canonical warped-image pixels
         * (origin = content area top-left in the warped/corrected image).
         * Used for sampling — do not display as image coordinates.
         */
        public int     absLeft;
        public int     absTop;
        /** Indicator box dimensions in pixels (in the warped image). */
        public int     width;
        public int     height;
        /**
         * imageX/imageY: position in ORIGINAL IMAGE pixels
         * (origin = image top-left, matching GIMP coordinates).
         * Computed as: detected_TL + (absLeft, absTop) scaled by actual DPI.
         */
        public int     imageX;
        public int     imageY;
        /**
         * Left and right edges of the contest column in original image pixels.
         * Used by VoteTallyService to crop write-in images to exactly the column
         * width below the indicator, regardless of indicator side (left or right).
         * Set by MarkerAnalysisService from ContestBox.offsetLeft/width.
         */
        public int     contestBoxImageLeft  = -1;
        public int     contestBoxImageRight = -1;
    }

    /** Accumulated totals for one candidate across all processed images. */
    public static class CandidateTally {
        public String contestTitle;
        public Long   candidateId;
        public String candidateName;
        public int    voteCount;
        public int    ballotsChecked;
    }
}
