/*
 * Copyright (C) 2026 Mitch Trachtenberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.mjtrac.ballot.util;

import java.util.List;

/**
 * Data structures holding the measured position and bounding box of each
 * element on a generated ballot, relative to the content bounding-box
 * upper-left corner of the page on which the element appears.
 *
 * All measurements are in inches.  pageNumber is 1-based.
 *
 * PageLayout: per-page record holding the outer content-area bounding box
 *   (the heavy border drawn around all contest columns) and all contests
 *   that appear on that page.
 *
 * ContestPosition: the visible box drawn around one contest's content.
 *
 * CandidatePosition: the vote indicator(s) drawn next to one candidate.
 */
public class BallotDimensions {

    /** All layout data for one printed side (page). */
    /**
     * Layout data for one printed side (page).
     * contentAreaOffsetLeft/Top: position of the ballot content bounding box
     *   upper-left corner measured from the page upper-left corner, in inches.
     * contentAreaWidth/Height: dimensions of the heavy outer border box.
     * All contest/indicator offsets are relative to the content area upper-left.
     */
    public static class PageLayout {
        private final int    pageNumber;
        private final double contentAreaOffsetLeftInches;
        private final double contentAreaOffsetTopInches;
        private final double contentAreaWidthInches;
        private final double contentAreaHeightInches;
        private final List<ContestPosition> contests;
        /**
         * Content-box corner registration mark centres, page-absolute inches.
         * Order: TL, TR, BR, BL (clockwise from top-left). Each entry is {x, y}.
         */
        private final double[][] cornerMarksInches;
        /** QR barcode centre, page-absolute inches from image top-left {x, y}. */
        private final double[]   barcodeCentreInches;
        /**
         * Page-level orientation mark centres, page-absolute inches.
         * Order: PTL[0] (left), PTR[1] (right). Each entry is {x, y}.
         * These 18x9pt rectangles sit just inside the top margin.
         * Null for ballots generated before this feature was added.
         */
        private final double[][] pageMarksInches;

        /** Convenience constructor without barcode centre or page marks. */
        public PageLayout(int pageNumber,
                          double contentAreaOffsetLeftInches,
                          double contentAreaOffsetTopInches,
                          double contentAreaWidthInches,
                          double contentAreaHeightInches,
                          List<ContestPosition> contests,
                          double[][] cornerMarksInches) {
            this(pageNumber, contentAreaOffsetLeftInches, contentAreaOffsetTopInches,
                 contentAreaWidthInches, contentAreaHeightInches, contests,
                 cornerMarksInches, null, null);
        }
        /** Convenience constructor without page marks. */
        public PageLayout(int pageNumber,
                          double contentAreaOffsetLeftInches,
                          double contentAreaOffsetTopInches,
                          double contentAreaWidthInches,
                          double contentAreaHeightInches,
                          List<ContestPosition> contests,
                          double[][] cornerMarksInches,
                          double[]   barcodeCentreInches) {
            this(pageNumber, contentAreaOffsetLeftInches, contentAreaOffsetTopInches,
                 contentAreaWidthInches, contentAreaHeightInches, contests,
                 cornerMarksInches, barcodeCentreInches, null);
        }
        /** Full constructor including page-level marks. */
        public PageLayout(int pageNumber,
                          double contentAreaOffsetLeftInches,
                          double contentAreaOffsetTopInches,
                          double contentAreaWidthInches,
                          double contentAreaHeightInches,
                          List<ContestPosition> contests,
                          double[][] cornerMarksInches,
                          double[]   barcodeCentreInches,
                          double[][] pageMarksInches) {
            this.pageNumber                  = pageNumber;
            this.contentAreaOffsetLeftInches = contentAreaOffsetLeftInches;
            this.contentAreaOffsetTopInches  = contentAreaOffsetTopInches;
            this.contentAreaWidthInches      = contentAreaWidthInches;
            this.contentAreaHeightInches     = contentAreaHeightInches;
            this.contests                    = contests;
            this.cornerMarksInches           = cornerMarksInches;
            this.barcodeCentreInches         = barcodeCentreInches;
            this.pageMarksInches             = pageMarksInches;
        }
        public int       getPageNumber()                    { return pageNumber; }
        public double    getContentAreaOffsetLeftInches()   { return contentAreaOffsetLeftInches; }
        public double    getContentAreaOffsetTopInches()    { return contentAreaOffsetTopInches; }
        public double    getContentAreaWidthInches()        { return contentAreaWidthInches; }
        public double    getContentAreaHeightInches()       { return contentAreaHeightInches; }
        public List<ContestPosition> getContests()          { return contests; }
        public double[][] getCornerMarksInches()            { return cornerMarksInches; }
        public double[]   getBarcodeCentreInches()          { return barcodeCentreInches; }
        public double[][] getPageMarksInches()              { return pageMarksInches; }
    }

    public static class ContestPosition {
        private final Long   contestId;
        private final String contestTitle;
        private final String contestType;    // PLURALITY, RANKED_CHOICE, APPROVAL, MEASURE
        private final int    maxVotes;       // max votes allowed (0 = unlimited/non-FPTP)
        private final int    pageNumber;
        private final double offsetFromLeftInches;
        private final double offsetFromTopInches;
        private final double widthInches;
        private final double heightInches;
        private final List<CandidatePosition> candidates;

        public ContestPosition(Long contestId, String contestTitle,
                               String contestType, int maxVotes,
                               int pageNumber,
                               double offsetFromLeftInches, double offsetFromTopInches,
                               double widthInches, double heightInches,
                               List<CandidatePosition> candidates) {
            this.contestId            = contestId;
            this.contestTitle         = contestTitle;
            this.contestType          = contestType;
            this.maxVotes             = maxVotes;
            this.pageNumber           = pageNumber;
            this.offsetFromLeftInches = offsetFromLeftInches;
            this.offsetFromTopInches  = offsetFromTopInches;
            this.widthInches          = widthInches;
            this.heightInches         = heightInches;
            this.candidates           = candidates;
        }

        public Long   getContestId()           { return contestId; }
        public String getContestTitle()         { return contestTitle; }
        public String getContestType()          { return contestType; }
        public int    getMaxVotes()             { return maxVotes; }
        public int    getPageNumber()           { return pageNumber; }
        public double getOffsetFromLeftInches() { return offsetFromLeftInches; }
        public double getOffsetFromTopInches()  { return offsetFromTopInches; }
        public double getWidthInches()          { return widthInches; }
        public double getHeightInches()         { return heightInches; }
        public List<CandidatePosition> getCandidates() { return candidates; }
    }

    public static class CandidatePosition {
        private final Long    candidateId;
        private final String  candidateName;
        private final boolean writeIn;
        private final double  indicatorOffsetFromLeftInches;
        private final double  indicatorOffsetFromTopInches;
        private final double  indicatorWidthInches;
        private final double  indicatorHeightInches;
        private final String  indicatorStyle;  // e.g. "OVAL", "BOX", "ARROW"

        public CandidatePosition(Long candidateId, String candidateName,
                                 boolean writeIn,
                                 double indicatorOffsetFromLeftInches,
                                 double indicatorOffsetFromTopInches,
                                 double indicatorWidthInches,
                                 double indicatorHeightInches,
                                 String indicatorStyle) {
            this.candidateId                   = candidateId;
            this.candidateName                 = candidateName;
            this.writeIn                       = writeIn;
            this.indicatorOffsetFromLeftInches = indicatorOffsetFromLeftInches;
            this.indicatorOffsetFromTopInches  = indicatorOffsetFromTopInches;
            this.indicatorWidthInches          = indicatorWidthInches;
            this.indicatorHeightInches         = indicatorHeightInches;
            this.indicatorStyle                = indicatorStyle;
        }

        public Long   getCandidateId()                   { return candidateId; }
        public String  getCandidateName()                { return candidateName; }
        public boolean isWriteIn()                       { return writeIn; }
        public double getIndicatorOffsetFromLeftInches() { return indicatorOffsetFromLeftInches; }
        public double getIndicatorOffsetFromTopInches()  { return indicatorOffsetFromTopInches; }
        public double getIndicatorWidthInches()          { return indicatorWidthInches; }
        public double getIndicatorHeightInches()         { return indicatorHeightInches; }
        public String getIndicatorStyle()                { return indicatorStyle; }
    }
}
