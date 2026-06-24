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
package gov.election.ballot.model;

import jakarta.persistence.*;

/**
 * Design/layout parameters for PDF ballot generation.
 * One or more templates per election (allows alternative layouts e.g. large-header).
 *
 * TEXT STYLES: For each text type there is a font size, a bold flag, and an
 * italic flag.  Combining bold+italic produces bold-italic.  The six text types
 * and their roles on the printed ballot are:
 *
 *   groupingLabel  — section header above a contest ("FEDERAL", "STATE", etc.)
 *   contestTitle   — the contest name ("U.S. Representative, District 2")
 *   instruction    — per-contest instruction line ("Vote for one")
 *   preamble       — text between the instruction and the first candidate
 *   candidateName  — each candidate / option name
 *   prefixSuffix   — text before/after a candidate name on the same line
 *   candidateNote  — explanatory text for a candidate (printed below the name)
 *   postamble      — text after the last candidate in a contest
 *   header         — the one-line ballot metadata at the very top of the page
 */
@Entity
@Table(name = "ballot_design_templates")
public class BallotDesignTemplate {

    public enum PaperSize {
        LETTER_8_5x11      (612f,     792f),
        LEGAL_8_5x14       (612f,    1008f),
        HALF_LETTER_8_5x5_5(612f,     396f),
        HALF_LEGAL_8_5x7   (612f,     504f),
        A4                 (595.276f, 841.89f),
        A3                 (841.89f, 1190.55f),
        A5                 (419.528f, 595.276f);

        public final float widthPt;
        public final float heightPt;
        PaperSize(float w, float h) { this.widthPt = w; this.heightPt = h; }
    }

    public enum VoteIndicatorStyle {
        OVAL, CHECKBOX, ARROW, NUMBER_FIELD
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Election election;

    @Enumerated(EnumType.STRING)
    private PaperSize paperSize = PaperSize.LETTER_8_5x11;

    @Enumerated(EnumType.STRING)
    private VoteIndicatorStyle voteIndicatorStyle = VoteIndicatorStyle.OVAL;

    // ── Margins ───────────────────────────────────────────────
    // 36pt (0.5 inch) margins. Corner registration marks are placed directly
    // above/below the content bounding box within the top/bottom margins.
    private float marginTopPt    = 36f;
    private float marginBottomPt = 36f;
    private float marginLeftPt   = 36f;
    private float marginRightPt  = 36f;

    private int columns = 3;

    // ── Font sizes (all defaults raised +2 from previous values) ──────────
    /** Section header above a contest: "FEDERAL", "STATE", etc. */
    private float groupingLabelFontSize   = 11f;

    /** The contest title. */
    private float contestTitleFontSize    = 11f;

    /** Per-contest instruction line ("Vote for one"). */
    private float instructionFontSize     =  9f;

    /** Preamble text: between instruction and first candidate. */
    private float preambleFontSize        =  9f;

    /** Each candidate/option name. */
    private float candidateNameFontSize   = 10f;

    /** Prefix/suffix text on the same line as the candidate name. */
    private float prefixSuffixFontSize    =  9f;

    /** Candidate explanatory note, printed below the name. */
    private float candidateNoteFontSize   =  8f;

    /** Postamble text: after the last candidate in a contest. */
    private float postambleFontSize       =  9f;

    /** One-line ballot metadata at the top of the page. */
    private float headerFontSize          =  9f;

    // ── Bold flags ────────────────────────────────────────────
    private boolean groupingLabelBold  = true;
    private boolean contestTitleBold   = true;
    private boolean instructionBold    = false;
    private boolean preambleBold       = false;
    private boolean candidateNameBold  = false;
    private boolean prefixSuffixBold   = false;
    private boolean candidateNoteBold  = false;
    private boolean postambleBold      = false;

    // ── Italic flags ──────────────────────────────────────────
    private boolean groupingLabelItalic  = false;
    private boolean contestTitleItalic   = false;
    private boolean instructionItalic    = true;
    private boolean preambleItalic       = false;
    private boolean candidateNameItalic  = false;
    private boolean prefixSuffixItalic   = true;
    private boolean candidateNoteItalic  = true;
    private boolean postambleItalic      = false;

    // ── Barcode ───────────────────────────────────────────────
    private String barcodePosition = "TOP_RIGHT";
    private float  barcodeWidthPt  = 90f;
    private float  barcodeHeightPt = 36f;

    private boolean multiSheet = false;

    // ── Header zone text (editable) ───────────────────────────
    /**
     * Large bold headline printed at the top of the header zone,
     * next to the barcodes. Defaults to "OFFICIAL BALLOT".
     * May be left blank to suppress the headline.
     */
    @Column(columnDefinition = "TEXT")
    private String headerHeadline = "OFFICIAL BALLOT";

    private float headerHeadlineFontSize = 13f;

    /**
     * Smaller body text printed beneath the headline in the header zone.
     * Use \n to separate paragraphs.  Supports the tokens:
     *   {electionName}    — replaced with the election name
     *   {jurisdictionName}— replaced with the jurisdiction name
     *   {indicatorName}   — replaced with oval/box/arrow/number box
     * Defaults to a standard "How to vote" instruction block.
     */
    @Column(columnDefinition = "TEXT")
    private String headerBodyText =
        "{jurisdictionName}\n{electionName}\n\nHOW TO VOTE:\n" +
        "To vote, completely fill in the {indicatorName} next to your choice.";

    private float headerBodyFontSize = 9f;

    // ── Getters & Setters ─────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Election getElection() { return election; }
    public void setElection(Election election) { this.election = election; }

    public PaperSize getPaperSize() { return paperSize; }
    public void setPaperSize(PaperSize p) { this.paperSize = p; }

    public VoteIndicatorStyle getVoteIndicatorStyle() { return voteIndicatorStyle; }
    public void setVoteIndicatorStyle(VoteIndicatorStyle v) { this.voteIndicatorStyle = v; }

    public float getMarginTopPt()    { return marginTopPt; }
    public void  setMarginTopPt(float v)    { this.marginTopPt = v; }
    public float getMarginBottomPt() { return marginBottomPt; }
    public void  setMarginBottomPt(float v) { this.marginBottomPt = v; }
    public float getMarginLeftPt()   { return marginLeftPt; }
    public void  setMarginLeftPt(float v)   { this.marginLeftPt = v; }
    public float getMarginRightPt()  { return marginRightPt; }
    public void  setMarginRightPt(float v)  { this.marginRightPt = v; }

    public int  getColumns() { return columns; }
    public void setColumns(int c) { this.columns = c; }

    // font sizes
    public float getGroupingLabelFontSize()  { return groupingLabelFontSize; }
    public void  setGroupingLabelFontSize(float v) { this.groupingLabelFontSize = v; }
    public float getContestTitleFontSize()   { return contestTitleFontSize; }
    public void  setContestTitleFontSize(float v)  { this.contestTitleFontSize = v; }
    public float getInstructionFontSize()    { return instructionFontSize; }
    public void  setInstructionFontSize(float v)   { this.instructionFontSize = v; }
    public float getPreambleFontSize()       { return preambleFontSize; }
    public void  setPreambleFontSize(float v)      { this.preambleFontSize = v; }
    public float getCandidateNameFontSize()  { return candidateNameFontSize; }
    public void  setCandidateNameFontSize(float v) { this.candidateNameFontSize = v; }
    public float getPrefixSuffixFontSize()   { return prefixSuffixFontSize; }
    public void  setPrefixSuffixFontSize(float v)  { this.prefixSuffixFontSize = v; }
    public float getCandidateNoteFontSize()  { return candidateNoteFontSize; }
    public void  setCandidateNoteFontSize(float v) { this.candidateNoteFontSize = v; }
    public float getPostambleFontSize()      { return postambleFontSize; }
    public void  setPostambleFontSize(float v)     { this.postambleFontSize = v; }
    public float getHeaderFontSize()         { return headerFontSize; }
    public void  setHeaderFontSize(float v)        { this.headerFontSize = v; }

    // bold
    public boolean isGroupingLabelBold()  { return groupingLabelBold; }
    public void    setGroupingLabelBold(boolean v) { this.groupingLabelBold = v; }
    public boolean isContestTitleBold()   { return contestTitleBold; }
    public void    setContestTitleBold(boolean v)  { this.contestTitleBold = v; }
    public boolean isInstructionBold()    { return instructionBold; }
    public void    setInstructionBold(boolean v)   { this.instructionBold = v; }
    public boolean isPreambleBold()       { return preambleBold; }
    public void    setPreambleBold(boolean v)      { this.preambleBold = v; }
    public boolean isCandidateNameBold()  { return candidateNameBold; }
    public void    setCandidateNameBold(boolean v) { this.candidateNameBold = v; }
    public boolean isPrefixSuffixBold()   { return prefixSuffixBold; }
    public void    setPrefixSuffixBold(boolean v)  { this.prefixSuffixBold = v; }
    public boolean isCandidateNoteBold()  { return candidateNoteBold; }
    public void    setCandidateNoteBold(boolean v) { this.candidateNoteBold = v; }
    public boolean isPostambleBold()      { return postambleBold; }
    public void    setPostambleBold(boolean v)     { this.postambleBold = v; }

    // italic
    public boolean isGroupingLabelItalic()  { return groupingLabelItalic; }
    public void    setGroupingLabelItalic(boolean v) { this.groupingLabelItalic = v; }
    public boolean isContestTitleItalic()   { return contestTitleItalic; }
    public void    setContestTitleItalic(boolean v)  { this.contestTitleItalic = v; }
    public boolean isInstructionItalic()    { return instructionItalic; }
    public void    setInstructionItalic(boolean v)   { this.instructionItalic = v; }
    public boolean isPreambleItalic()       { return preambleItalic; }
    public void    setPreambleItalic(boolean v)      { this.preambleItalic = v; }
    public boolean isCandidateNameItalic()  { return candidateNameItalic; }
    public void    setCandidateNameItalic(boolean v) { this.candidateNameItalic = v; }
    public boolean isPrefixSuffixItalic()   { return prefixSuffixItalic; }
    public void    setPrefixSuffixItalic(boolean v)  { this.prefixSuffixItalic = v; }
    public boolean isCandidateNoteItalic()  { return candidateNoteItalic; }
    public void    setCandidateNoteItalic(boolean v) { this.candidateNoteItalic = v; }
    public boolean isPostambleItalic()      { return postambleItalic; }
    public void    setPostambleItalic(boolean v)     { this.postambleItalic = v; }

    // header zone text
    public String getHeaderHeadline()           { return headerHeadline; }
    public void   setHeaderHeadline(String v)        { this.headerHeadline = v; }
    public float  getHeaderHeadlineFontSize()   { return headerHeadlineFontSize; }
    public void   setHeaderHeadlineFontSize(float v) { this.headerHeadlineFontSize = v; }
    public String getHeaderBodyText()           { return headerBodyText; }
    public void   setHeaderBodyText(String v)        { this.headerBodyText = v; }
    public float  getHeaderBodyFontSize()       { return headerBodyFontSize; }
    public void   setHeaderBodyFontSize(float v)     { this.headerBodyFontSize = v; }

    // barcode
    public String getBarcodePosition() { return barcodePosition; }
    public void   setBarcodePosition(String v) { this.barcodePosition = v; }
    public float  getBarcodeWidthPt()  { return barcodeWidthPt; }
    public void   setBarcodeWidthPt(float v)   { this.barcodeWidthPt = v; }
    public float  getBarcodeHeightPt() { return barcodeHeightPt; }
    public void   setBarcodeHeightPt(float v)  { this.barcodeHeightPt = v; }

    public boolean isMultiSheet() { return multiSheet; }
    public void    setMultiSheet(boolean v) { this.multiSheet = v; }

    /**
     * Returns the iText font name for a given bold/italic combination.
     * Standard Helvetica family covers all four variants.
     */
    public static String fontName(boolean bold, boolean italic) {
        if (bold && italic) return com.itextpdf.io.font.constants.StandardFonts.HELVETICA_BOLDOBLIQUE;
        if (bold)           return com.itextpdf.io.font.constants.StandardFonts.HELVETICA_BOLD;
        if (italic)         return com.itextpdf.io.font.constants.StandardFonts.HELVETICA_OBLIQUE;
        return com.itextpdf.io.font.constants.StandardFonts.HELVETICA;
    }
}
