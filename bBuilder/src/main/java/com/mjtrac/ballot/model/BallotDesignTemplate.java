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
package com.mjtrac.ballot.model;

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
        OVAL, BOX, ARROW, NUMBER_FIELD, CONNECT_DOTS
    }

    /**
     * Font family used for all text on the ballot.
     * Only the 14 standard PDF fonts are used — no embedding required.
     */
    public enum FontFamily {
        HELVETICA  ("Helvetica — clean sans-serif (default)"),
        TIMES      ("Times Roman — traditional serif"),
        COURIER    ("Courier — monospace");

        public final String label;
        FontFamily(String label) { this.label = label; }
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Election election;

    @Enumerated(EnumType.STRING)
    private PaperSize paperSize = PaperSize.LETTER_8_5x11;

    /** Primary font family — used for all text types unless altFont is checked. */
    @Enumerated(EnumType.STRING)
    private FontFamily fontFamilyPrimary   = FontFamily.HELVETICA;

    /** Alternate font family — used for text types where the altFont flag is true. */
    @Enumerated(EnumType.STRING)
    private FontFamily fontFamilyAlternate = FontFamily.TIMES;

    // ── Per-text-type alternate-font flags ────────────────────────────────
    // false = use fontFamilyPrimary; true = use fontFamilyAlternate
    private boolean groupingLabelAltFont = false;
    private boolean contestTitleAltFont  = false;
    private boolean instructionAltFont   = false;
    private boolean preambleAltFont      = false;
    private boolean candidateNameAltFont = false;
    private boolean prefixSuffixAltFont  = false;
    private boolean candidateNoteAltFont = false;
    private boolean postambleAltFont     = false;
    private boolean headerAltFont        = false;

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
    private float  barcodeWidthPt  = 0f;    // 0 = no linear barcode (QR only)
    private float  barcodeHeightPt = 72f;   // QR code size: 72pt = 1" (doubled from 36pt)

    private boolean multiSheet = false;

    // ── Ranked-choice layout options ──────────────────────────
    /**
     * When true, indicators appear to the RIGHT of the candidate name,
     * with rank-1 box closest to the name (natural left-to-right reading:
     * name → rank-1 → rank-2 → … → rank-N).
     * When false (default), indicators appear to the LEFT of the name
     * in the traditional layout (rank-N … rank-2 → rank-1 → name).
     */
    private boolean rcvIndicatorsRight = false;

    /**
     * When true, draw small rank-number labels (e.g. "1", "2", "3") centered
     * above each rank box on the FIRST candidate row of each ranked-choice
     * contest.  Helps voters understand the box sequence without reading
     * the full instruction.
     */
    private boolean rcvShowRankNumbers = false;

    /**
     * Font size (pt) for the rank-number labels drawn above the rank boxes.
     * Only used when rcvShowRankNumbers is true.
     */
    private float rcvRankNumberFontPt = 7f;

    /**
     * Stroke width (pt) for all vote indicators (oval, checkbox, RCV boxes).
     * Default 0.25 pt — thin enough to be clear but not contribute dark pixels
     * that confuse optical scan sampling.
     */
    private float   indicatorLineWidthPt = 0.5f;

    /**
     * When true, vote indicators are drawn with a dashed stroke.
     * Dashing makes it visually clear the indicator is a pre-printed guide
     * rather than a voter mark.  Default true.
     */
    private boolean indicatorDashed = true;

    /**
     * Stroke width (pt) for ranked-choice indicator boxes specifically.
     * Overrides indicatorLineWidthPt for RCV boxes only.
     * Default 0.5 pt — slightly heavier than ovals since boxes need clear edges.
     */
    private float   rcvBoxLineWidthPt = 0.5f;

    // ── Header zone text (editable) ───────────────────────────
    /**
     * HTML content for the header zone — the area to the left of the barcodes
     * at the top of each ballot page (excluding the single metadata line and
     * the orientation marks, which are always drawn automatically).
     *
     * Full HTML + inline CSS is supported via iText html2pdf.
     * Images may be embedded as data URIs: {@code <img src="data:image/png;base64,..."/>}
     *
     * Tokens replaced at print time:
     *   {electionName}     — election name
     *   {jurisdictionName} — jurisdiction name
     *   {regionName}       — precinct/region name
     *   {partyName}        — party name (or "Nonpartisan")
     *   {ballotTypeName}   — ballot type name
     *   {indicatorName}    — oval / box / arrow / number box
     *   {pageNum}          — page number
     */
    @Column(columnDefinition = "TEXT")
    private String headerHtml = DEFAULT_HEADER_HTML;
    public static final String DEFAULT_HEADER_HTML =
	"<div style=\"font-family:Helvetica,Arial,sans-serif;padding:4px 0\">" +
	"<p style=\"font-size:13pt;font-weight:bold;line-height:1.6\">OFFICIAL BALLOT</p>" +
	"<p style=\"font-size:9pt;line-height:1.4\">{jurisdictionName}</p>" +
	"<p style=\"font-size:9pt;line-height:1.8\">{electionName}</p>" +
	"<p style=\"font-size:9pt;font-weight:bold;line-height:1.4\">HOW TO VOTE:</p>" +
	"<p style=\"font-size:9pt;line-height:1.4\">To vote, completely fill in the {indicatorName} next to your choice.</p>" +
    "</div>";
    

    // ── Getters & Setters ─────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Election getElection() { return election; }
    public void setElection(Election election) { this.election = election; }

    public PaperSize getPaperSize() { return paperSize; }
    public void setPaperSize(PaperSize p) { this.paperSize = p; }

    public FontFamily getFontFamilyPrimary()              { return fontFamilyPrimary   != null ? fontFamilyPrimary   : FontFamily.HELVETICA; }
    public void       setFontFamilyPrimary(FontFamily f)  { this.fontFamilyPrimary   = f; }
    public FontFamily getFontFamilyAlternate()            { return fontFamilyAlternate != null ? fontFamilyAlternate : FontFamily.TIMES; }
    public void       setFontFamilyAlternate(FontFamily f){ this.fontFamilyAlternate = f; }

    // alt-font flags
    public boolean isGroupingLabelAltFont()          { return groupingLabelAltFont; }
    public void    setGroupingLabelAltFont(boolean v){ this.groupingLabelAltFont = v; }
    public boolean isContestTitleAltFont()           { return contestTitleAltFont; }
    public void    setContestTitleAltFont(boolean v) { this.contestTitleAltFont  = v; }
    public boolean isInstructionAltFont()            { return instructionAltFont; }
    public void    setInstructionAltFont(boolean v)  { this.instructionAltFont   = v; }
    public boolean isPreambleAltFont()               { return preambleAltFont; }
    public void    setPreambleAltFont(boolean v)     { this.preambleAltFont      = v; }
    public boolean isCandidateNameAltFont()          { return candidateNameAltFont; }
    public void    setCandidateNameAltFont(boolean v){ this.candidateNameAltFont = v; }
    public boolean isPrefixSuffixAltFont()           { return prefixSuffixAltFont; }
    public void    setPrefixSuffixAltFont(boolean v) { this.prefixSuffixAltFont  = v; }
    public boolean isCandidateNoteAltFont()          { return candidateNoteAltFont; }
    public void    setCandidateNoteAltFont(boolean v){ this.candidateNoteAltFont = v; }
    public boolean isPostambleAltFont()              { return postambleAltFont; }
    public void    setPostambleAltFont(boolean v)    { this.postambleAltFont     = v; }
    public boolean isHeaderAltFont()                 { return headerAltFont; }
    public void    setHeaderAltFont(boolean v)       { this.headerAltFont        = v; }

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
    // ── Legacy columns retained for DB compatibility ─────────────────────────
    // These fields were replaced by headerHtml but the DB columns still exist
    // as NOT NULL. They are kept here so Hibernate includes them in INSERT
    // statements with their default values. No application code uses them.
    @Column(name = "header_headline")
    private String headerHeadline = "OFFICIAL BALLOT";

    @Column(name = "header_headline_font_size")
    private float headerHeadlineFontSize = 13f;

    @Column(name = "header_body_text", columnDefinition = "TEXT")
    private String headerBodyText = "";

    @Column(name = "header_body_font_size")
    private float headerBodyFontSize = 9f;

    public String getHeaderHtml() {
        return headerHtml != null ? headerHtml : DEFAULT_HEADER_HTML;
    }
    public void setHeaderHtml(String v) { this.headerHtml = v; }

    // barcode
    public String getBarcodePosition() { return barcodePosition; }
    public void   setBarcodePosition(String v) { this.barcodePosition = v; }
    public float  getBarcodeWidthPt()  { return barcodeWidthPt; }
    public void   setBarcodeWidthPt(float v)   { this.barcodeWidthPt = v; }
    public float  getBarcodeHeightPt() { return barcodeHeightPt; }
    public void   setBarcodeHeightPt(float v)  { this.barcodeHeightPt = v; }

    public boolean isMultiSheet() { return multiSheet; }
    public void    setMultiSheet(boolean v) { this.multiSheet = v; }

    // rcv layout
    public boolean isRcvIndicatorsRight()       { return rcvIndicatorsRight; }
    public void    setRcvIndicatorsRight(boolean v)   { this.rcvIndicatorsRight = v; }
    public boolean isRcvShowRankNumbers()       { return rcvShowRankNumbers; }
    public void    setRcvShowRankNumbers(boolean v)   { this.rcvShowRankNumbers = v; }
    public float   getRcvRankNumberFontPt()     { return rcvRankNumberFontPt; }
    public void    setRcvRankNumberFontPt(float v)    { this.rcvRankNumberFontPt = v; }
    public float   getRcvBoxLineWidthPt()             { return rcvBoxLineWidthPt; }
    public void    setRcvBoxLineWidthPt(float v)      { this.rcvBoxLineWidthPt = v; }
    public float   getIndicatorLineWidthPt()          { return indicatorLineWidthPt > 0 ? indicatorLineWidthPt : 0.5f; }
    public void    setIndicatorLineWidthPt(float v)   { this.indicatorLineWidthPt = v; }
    public boolean isIndicatorDashed()                { return indicatorDashed; }
    public void    setIndicatorDashed(boolean v)      { this.indicatorDashed = v; }

    /**
     * Returns the iText font name using primary or alternate family.
     * @param altFont true = use fontFamilyAlternate, false = use fontFamilyPrimary
     */
    public String fontName(boolean bold, boolean italic, boolean altFont) {
        FontFamily fam = altFont ? getFontFamilyAlternate() : getFontFamilyPrimary();
        return fontNameForFamily(bold, italic, fam);
    }

    /** Convenience overload — always uses primary font family. */
    public String fontName(boolean bold, boolean italic) {
        return fontName(bold, italic, false);
    }

    private static String fontNameForFamily(boolean bold, boolean italic, FontFamily fam) {
        return switch (fam) {
            case TIMES -> {
                if (bold && italic) yield com.itextpdf.io.font.constants.StandardFonts.TIMES_BOLDITALIC;
                if (bold)           yield com.itextpdf.io.font.constants.StandardFonts.TIMES_BOLD;
                if (italic)         yield com.itextpdf.io.font.constants.StandardFonts.TIMES_ITALIC;
                yield com.itextpdf.io.font.constants.StandardFonts.TIMES_ROMAN;
            }
            case COURIER -> {
                if (bold && italic) yield com.itextpdf.io.font.constants.StandardFonts.COURIER_BOLDOBLIQUE;
                if (bold)           yield com.itextpdf.io.font.constants.StandardFonts.COURIER_BOLD;
                if (italic)         yield com.itextpdf.io.font.constants.StandardFonts.COURIER_OBLIQUE;
                yield com.itextpdf.io.font.constants.StandardFonts.COURIER;
            }
            default -> {  // HELVETICA
                if (bold && italic) yield com.itextpdf.io.font.constants.StandardFonts.HELVETICA_BOLDOBLIQUE;
                if (bold)           yield com.itextpdf.io.font.constants.StandardFonts.HELVETICA_BOLD;
                if (italic)         yield com.itextpdf.io.font.constants.StandardFonts.HELVETICA_OBLIQUE;
                yield com.itextpdf.io.font.constants.StandardFonts.HELVETICA;
            }
        };
    }

    /** Static version for callers without a template instance. */
    public static String fontName(boolean bold, boolean italic, FontFamily family) {
        return fontNameForFamily(bold, italic, family != null ? family : FontFamily.HELVETICA);
    }
}
