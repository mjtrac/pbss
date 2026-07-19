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
package com.mjtrac.ballot.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.util.BallotDimensions;
import com.mjtrac.ballot.util.MeasurementUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates a multi-page PDF ballot and automatically writes the corresponding
 * XML and YAML bounding-box reports to the application's working directory.
 *
 * ── AUTO-EXPORT ──────────────────────────────────────────────────────────────
 * After every successful PDF generation, two files are written to the directory
 * specified by ${ballot.export.dir} (defaults to ".") using the filename pattern:
 *   ballot-<combinationId>-bboxes.xml
 *   ballot-<combinationId>-bboxes.yaml
 *
 * ── RANKED-CHOICE LAYOUT ─────────────────────────────────────────────────────
 * Ranked-choice layout is triggered per-contest when
 * contest.votingMethod == RANKED_CHOICE, regardless of the template's global
 * vote-indicator style.  For ranked-choice contests:
 *   - Rank boxes are drawn next to each candidate (up to MAX_RANK_BOXES = 5).
 *   - The box nearest the candidate name is widest (FIRST_RANK_BOX_W = 22pt)
 *     to indicate it is the first choice; remaining boxes are narrower (10pt).
 *   - The contest instruction is overridden to explain the box layout.
 *   - The per-contest NUMBER_FIELD indicator style is suppressed for non-RC contests.
 *
 * ── BOUNDING BOXES ───────────────────────────────────────────────────────────
 * Every ContestPosition and CandidatePosition now carries widthInches and
 * heightInches so the export files describe full bounding rectangles.
 */
@Service
public class BallotGenerationService {

    private static final Logger log =
        LoggerFactory.getLogger(BallotGenerationService.class);

    private final ContestAssignmentService assignmentService;
    private final BallotLayoutService      layoutService;
    private final BarcodeService           barcodeService;

    /** Files written by the most recent autoExport call — read by the controller. */
    private volatile List<String> lastWrittenFiles = new java.util.ArrayList<>();

    public List<String> getLastWrittenFiles() { return lastWrittenFiles; }
    private final PrintLogService          printLogService;
    private final ExportService            exportService;
    private final BallotTranslationService translationService;

    @Value("${ballot.export.dir:${user.home}/pbss_data/ballot_templates}")
    private String exportDir;

    @Value("${ballot.export.format:yaml}")
    private String exportFormat;

    public BallotGenerationService(ContestAssignmentService assignmentService,
                                   BallotLayoutService layoutService,
                                   BarcodeService barcodeService,
                                   PrintLogService printLogService,
                                   ExportService exportService,
                                   BallotTranslationService translationService) {
        this.assignmentService   = assignmentService;
        this.layoutService       = layoutService;
        this.barcodeService      = barcodeService;
        this.printLogService     = printLogService;
        this.exportService       = exportService;
        this.translationService  = translationService;
    }

    // ── Layout constants ───────────────────────────────────────────────────
    private static final float OVAL_WIDTH      = 22f;
    private static final float OVAL_HEIGHT     = 11f;
    private static final float ARROW_WIDTH     = 30f;
    /** Total width of the connect-dots indicator (both markers + gap between points). */
    private static final float CONNECT_DOTS_WIDTH  = 40f;
    /** Radius of the circular half of each marker (4pt diameter). */
    private static final float CONNECT_DOTS_DOT_R  = 2f;
    private static final float HEADER_ZONE_PT  = 90f;
    private static final float CBOX_INDENT     = 4f;
    private static final float LINE_GAP        = 2f;

    /** Max rank boxes drawn per candidate in ranked-choice contests. */
    private static final int   MAX_RANK_BOXES       = 5;
    /** Width of the first (highest priority) rank box — wider to indicate primacy. */
    private static final float FIRST_RANK_BOX_W     = 22f;
    /** Width of subsequent (lower priority) rank boxes. */
    private static final float OTHER_RANK_BOX_W     = 12f;
    /** Gap between consecutive rank boxes. */
    private static final float RANK_BOX_GAP         = 5f;
    /** Extra gap between rank-1 (wide) box and rank-2 (narrow), to visually
     *  separate the first-choice box from the rest. */
    private static final float RANK_BOX_GAP_AFTER_1 = 8f;
    /** Minimum gap between indicators and column border on either side. */
    private static final float IND_BORDER_GAP       = 4f;

    // Corner registration marks — placed directly above/below the bbox.
    // TL mark is a rectangle (wider) to identify orientation; others are squares.
    private static final float MARK_SQ_W  = 9f;   // square mark width & height
    private static final float MARK_RECT_W = 18f;  // TL rectangle mark width
    private static final float MARK_H     = 9f;   // all marks same height
    private static final float MARK_GAP   = 6f;   // gap between bbox border and mark

    // ══════════════════════════════════════════════════════════════════════
    // ENTRY POINT
    // ══════════════════════════════════════════════════════════════════════

    /** Generate ballot in default language (English). */
    public byte[] generateBallot(BallotCombination combination,
                                  BallotDesignTemplate template,
                                  User user,
                                  int copies) throws Exception {
        return generateBallot(combination, template, user, copies, "en");
    }

    /** Generate ballot in the specified language code (e.g. "es", "zh"). */
    public byte[] generateBallot(BallotCombination combination,
                                  BallotDesignTemplate template,
                                  User user,
                                  int copies,
                                  String languageCode) throws Exception {
        BallotTranslationService.Translator tr = translationService.forLanguage(
            languageCode != null ? languageCode : "en");
        _translator.set(tr);
        _template.set(template);
        try {
            return generateBallotCore(combination, template, user, copies);
        } finally {
            _translator.remove();
            _template.remove();
        }
    }

    /**
     * ThreadLocal storage for per-call state.
     * Using ThreadLocal rather than instance fields ensures that concurrent
     * calls to generateBallot() from different HTTP threads cannot corrupt
     * each other's translator or template — each thread has its own copy.
     */
    private static final ThreadLocal<BallotTranslationService.Translator>
        _translator = new ThreadLocal<>();
    private static final ThreadLocal<BallotDesignTemplate>
        _template   = new ThreadLocal<>();

    private BallotTranslationService.Translator tx() {
        BallotTranslationService.Translator t = _translator.get();
        return t != null ? t : translationService.forLanguage("en");
    }

    private byte[] generateBallotCore(BallotCombination combination,
                                  BallotDesignTemplate template,
                                  User user,
                                  int copies) throws Exception {

        if (combination == null || template == null || user == null)
            throw new IllegalArgumentException(
                "combination, template, and user must not be null");

        _template.set(template);

        List<Contest> contests = assignmentService.resolveContestsForPrecinct(
            combination.getRegion().getId(),
            combination.getElection().getId());

        if (contests.isEmpty())
            log.warn("No contests for combination id=" + combination.getId());

        // Scaled whitespace
        float aboveGroupLabel  = template.getGroupingLabelFontSize() * 0.8f;
        float belowGroupLabel  = template.getGroupingLabelFontSize() * 0.4f;
        float interContestGap  = Math.max(4f, template.getContestTitleFontSize() * 0.9f);
        float wrappedLineExtra = template.getCandidateNameFontSize()  * 0.25f;
        float rowSpacing       = template.getCandidateNameFontSize()  + OVAL_HEIGHT + 2f;

        float pw = template.getPaperSize().widthPt;
        float ph = template.getPaperSize().heightPt;

        float bbLeft   = template.getMarginLeftPt() + 5f;
        float bbRight  = pw - template.getMarginRightPt() - 5f;
        float bbBottom = template.getMarginBottomPt() + 5f;

        // Worst-case content height: use computed header zone height, not the constant.
        float worstZoneH     = computeHeaderZoneHeight(template, pw, true);
        float worstCaseBbTop = ph - template.getMarginTopPt() - 14f - worstZoneH
                               - (MARK_GAP + MARK_H + MARK_GAP);  // clearance for marks

        float colWidth  = (bbRight - bbLeft - 10f) / template.getColumns();
        float textWidth = colWidth - CBOX_INDENT * 2f - 8f;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Per-page layout: map from pageNumber -> list of ContestPositions
        Map<Integer, List<BallotDimensions.ContestPosition>> pageContests = new java.util.LinkedHashMap<>();
        Map<Integer, double[]> pageContentArea  = new java.util.LinkedHashMap<>(); // [offsetL, offsetT, w, h]
        Map<Integer, double[][]> pageCornerMarks = new java.util.LinkedHashMap<>();  // [TL,TR,BR,BL] in inches
        Map<Integer, double[]>   pageBarcodeCentre = new java.util.LinkedHashMap<>(); // [x, y] inches from image top-left
        Map<Integer, double[][]> pagePageMarks   = new java.util.LinkedHashMap<>();  // [PTL,PTR] in inches

        // Pre-validate: any contest too tall for a fresh page
        float maxContentH = worstCaseBbTop - bbBottom;
        for (Contest contest : contests) {
            float h = estimateContestHeight(contest, template, textWidth,
                                           rowSpacing, wrappedLineExtra,
                                           aboveGroupLabel, belowGroupLabel);
            if (h > maxContentH)
                throw new IllegalStateException(
                    "Contest too large for this ballot size: \"" + contest.getTitle() +
                    "\" needs " + String.format("%.0f", h) +
                    " pt but page content area is only " +
                    String.format("%.0f", maxContentH) + " pt tall.");
        }

        try (PdfWriter   writer = new PdfWriter(baos);
             PdfDocument pdf    = new PdfDocument(writer)) {

            float[]    state        = new float[4]; // [0]=bbTop [1]=colX [2]=initY [3]=bbBottom
            PdfCanvas  canvas;
            int   pageNum  = 1;
            int   col      = 0;
            float bbTop;

            double[] bcCentre = new double[2];
            double[][] pm1    = new double[2][2];
            canvas = openPage(pdf, pw, ph, pageNum, combination, template,
                              bbLeft, bbRight, bbBottom, state, bcCentre, pm1);
            pageBarcodeCentre.put(pageNum, bcCentre);
            System.out.println("PM1 DEBUG page=" + pageNum + " pm1[0]=" + java.util.Arrays.toString(pm1[0]) + " pm1[1]=" + java.util.Arrays.toString(pm1[1]));
            pagePageMarks.put(pageNum, pm1);
            bbTop    = state[0];
            float currentX = state[1];
            float currentY = state[2];
            float effectiveBbBottom = state[3];
            col = 0;
            // Record content area for page 1: [offsetLeft, offsetTop, width, height]
            pageContentArea.put(pageNum, new double[]{
                MeasurementUtil.ptToInches(bbLeft),
                MeasurementUtil.ptToInches(ph - bbTop),
                MeasurementUtil.ptToInches(bbRight - bbLeft),
                MeasurementUtil.ptToInches(bbTop - effectiveBbBottom)
            });
            pageCornerMarks.put(pageNum,
                computeCornerMarks(bbLeft, bbTop, bbRight, effectiveBbBottom, ph));
            pageContests.put(pageNum, new ArrayList<>());

            for (Contest contest : contests) {

                float contestH = estimateContestHeight(contest, template, textWidth,
                    rowSpacing, wrappedLineExtra, aboveGroupLabel, belowGroupLabel);

                if (currentY - contestH < effectiveBbBottom + 4f) {
                    if (col < template.getColumns() - 1) {
                        col++;
                        currentX += colWidth;
                        currentY  = bbTop - template.getContestTitleFontSize() - 15f;
                    } else {
                        pageNum++;
                        col = 0;
                        double[] bcCentre2 = new double[2];
                        double[][] pm2     = new double[2][2];
                        canvas = openPage(pdf, pw, ph, pageNum, combination, template,
                                          bbLeft, bbRight, bbBottom, state, bcCentre2, pm2);
                        pageBarcodeCentre.put(pageNum, bcCentre2);
                        pagePageMarks.put(pageNum, pm2);
                        bbTop    = state[0];
                        currentX = state[1];
                        currentY = state[2];
                        effectiveBbBottom = state[3];
                        pageContentArea.put(pageNum, new double[]{
                            MeasurementUtil.ptToInches(bbLeft),
                            MeasurementUtil.ptToInches(ph - bbTop),
                            MeasurementUtil.ptToInches(bbRight - bbLeft),
                            MeasurementUtil.ptToInches(bbTop - effectiveBbBottom)
                        });
                        pageCornerMarks.put(pageNum,
                            computeCornerMarks(bbLeft, bbTop, bbRight, effectiveBbBottom, ph));
                        pageContests.put(pageNum, new ArrayList<>());
                    }
                }

                float drawnBottom   = currentY;
                float contestStartY = currentY;
                List<BallotDimensions.CandidatePosition> candPositions = new ArrayList<>();

                final float pageBbTop = bbTop;
                canvas.setFillColor(ColorConstants.BLACK)
                      .setStrokeColor(ColorConstants.BLACK);

                // ── Grouping label ────────────────────────────────────────
                if (contest.isPrintGroupingLabel() &&
                        contest.getGroupingLabel() != null &&
                        !contest.getGroupingLabel().isBlank()) {
                    currentY -= aboveGroupLabel;
                    currentY = drawWrappedText(canvas,
                        contest.getGroupingLabel(), textWidth,
                        font(template.isGroupingLabelBold(),
                             template.isGroupingLabelItalic(),
                             template.isGroupingLabelAltFont()),
                        template.getGroupingLabelFontSize(),
                        currentX + CBOX_INDENT, currentY);
                    currentY -= belowGroupLabel;
                    contestStartY = currentY;
                    drawnBottom   = currentY;
                }

                // ── Contest box top — determined BEFORE any content is drawn ─
                // The grouping label (if any) is outside the box; contestStartY
                // is already updated past it.
                //
                // PDF text coordinates: moveText(x, y) places the BASELINE at y.
                // The visible cap-height of the font extends approximately
                // fontSize * 0.75 ABOVE the baseline.
                //
                // So to place the first title line fully inside the box:
                //   cboxT = top border (at contestStartY)
                //   title baseline = cboxT - CBOX_TOP_PAD - titleFontSize * 0.75
                //                  (pad above caps + cap height itself)
                final float CBOX_TOP_PAD    = 3f;
                final float CBOX_BOTTOM_PAD = 4f;
                float titleFontSize = template.getContestTitleFontSize();
                // Use actual font ascender (distance from baseline to cap-top)
                // rather than the approximate 0.75 factor, so indicator Y positions
                // in the YAML are not shifted by approximation error.
                PdfFont titleFont = font(template.isContestTitleBold(),
                                         template.isContestTitleItalic(),
                                         template.isContestTitleAltFont());
                float titleAscender = titleFont.getFontProgram().getFontMetrics()
                                          .getTypoAscender() * titleFontSize / 1000f;
                float cboxT  = contestStartY;
                currentY     = cboxT - CBOX_TOP_PAD - titleAscender;
                drawnBottom  = currentY;

                // ── Contest title ─────────────────────────────────────────
                canvas.setFillColor(ColorConstants.BLACK)
                      .setStrokeColor(ColorConstants.BLACK);
                currentY = drawWrappedText(canvas,
                    contest.getTitle(), textWidth,
                    font(template.isContestTitleBold(), template.isContestTitleItalic(),
                    template.isContestTitleAltFont()),
                    template.getContestTitleFontSize(),
                    currentX + CBOX_INDENT, currentY);
                drawnBottom = currentY;

                // ── Instruction ───────────────────────────────────────────
                boolean isRankedChoice =
                    contest.getVotingMethod() == Contest.VotingMethod.RANKED_CHOICE;
                String instrText = isRankedChoice
                    ? buildRankedChoiceInstruction(contest)
                    : buildInstruction(contest);
                currentY = drawWrappedText(canvas,
                    instrText, textWidth,
                    font(template.isInstructionBold(), template.isInstructionItalic(),
                    template.isInstructionAltFont()),
                    template.getInstructionFontSize(),
                    currentX + CBOX_INDENT, currentY - LINE_GAP);
                drawnBottom = currentY;

                // ── Preamble ──────────────────────────────────────────────
                if (contest.isPrintPreamble() &&
                        contest.getPreamble() != null &&
                        !contest.getPreamble().isBlank()) {
                    currentY = drawWrappedText(canvas,
                        contest.getPreamble(), textWidth,
                        font(template.isPreambleBold(), template.isPreambleItalic(),
                        template.isPreambleAltFont()),
                        template.getPreambleFontSize(),
                        currentX + CBOX_INDENT, currentY - LINE_GAP);
                    drawnBottom = currentY;
                }

                // ── Candidates ────────────────────────────────────────────
                float indW  = effectiveIndicatorWidth(template, contest);
                // nameW: available width for candidate name.
                // In indRight mode, leave IND_BORDER_GAP on the right between
                // the last indicator and the column border.
                // In default mode, leave IND_BORDER_GAP on the left so indicators
                // don't butt against the left column border.
                float borderGap = IND_BORDER_GAP;
                float nameW = textWidth - indW - 4f - borderGap;
                // indicatorsRight applies to ALL contest types (not just RCV)
                boolean indRight = template.isRcvIndicatorsRight();

                // When rank numbers are shown, reserve space above the first
                // candidate row for the labels: labelFontSize + 4pt gap below them.
                // This shifts the ENTIRE first row (indicators + name) down together
                // so they remain vertically aligned with each other.
                final float RCV_LABEL_GAP = 4f;
                boolean firstCandidate = true;

                for (Candidate candidate : contest.getCandidates()) {
                    // For the first RCV candidate with rank numbers, push the row
                    // down to make room for labels above the indicators.
                    if (isRankedChoice && firstCandidate
                            && template.isRcvShowRankNumbers()) {
                        currentY -= template.getRcvRankNumberFontPt() + RCV_LABEL_GAP;
                    }

                    float targetX = currentX + CBOX_INDENT + 2f;
                    float targetY = currentY - OVAL_HEIGHT;

                    // ── Compute x positions for indicator and name ──────────
                    float indX, nameX;
                    if (indRight) {
                        // Indicators to the right, name left.
                        // Leave borderGap between last indicator and right column border.
                        indX  = targetX + nameW + 4f;
                        nameX = targetX;
                    } else {
                        // Indicators left, name right. Leave borderGap on left side.
                        indX  = targetX + borderGap;
                        nameX = indX + indW + 4f;
                    }

                    canvas.setFillColor(ColorConstants.BLACK)
                          .setStrokeColor(ColorConstants.BLACK);
                    float drawnIndW = drawEffectiveVoteTarget(canvas, template, contest,
                                                               indX, targetY);

                    // ── Rank-number labels above first candidate's boxes ────
                    if (isRankedChoice && firstCandidate
                            && template.isRcvShowRankNumbers()) {
                        try {
                            drawRankNumberLabels(canvas, template, contest,
                                                 indX, targetY);
                        } catch (Exception e) {
                            log.warn("Could not draw rank number labels: {}", e.getMessage());
                        }
                    }
                    firstCandidate = false;
                    float fontSize = template.getCandidateNameFontSize();

                    // Build name string (write-in candidates show name only; line drawn below)
                    String displayName = candidate.isWriteIn()
                        ? buildWriteInLabel(candidate)
                        : buildInlineName(candidate);

                    List<String> nameLines = wrapText(displayName, nameW, fontSize);
                    boolean singleNoNote = nameLines.size() == 1
                        && !(candidate.isPrintExplanatoryText()
                             && candidate.getExplanatoryText() != null
                             && !candidate.getExplanatoryText().isBlank());

                    float nameY;
                    if (singleNoNote) {
                        float ovalMidY = targetY + OVAL_HEIGHT / 2f;
                        nameY = ovalMidY - fontSize * 0.15f;
                    } else {
                        nameY = currentY - 2f;
                    }

                    PdfFont candFont = font(template.isCandidateNameBold(),
                                           template.isCandidateNameItalic(),
                                           template.isCandidateNameAltFont());
                    for (int li = 0; li < nameLines.size(); li++) {
                        float lineY = nameY - li * (fontSize + LINE_GAP);
                        float lineX;
                        if (indRight) {
                            // Pixel-perfect right-justify using iText font metrics.
                            // getWidth() returns width in text space units (1/1000 of fontSize).
                            float lineW = candFont.getWidth(nameLines.get(li), fontSize);
                            lineW = Math.min(lineW, nameW - 2f);
                            lineX = targetX + nameW - lineW - 2f;
                        } else {
                            lineX = nameX;
                        }
                        canvas.setFillColor(ColorConstants.BLACK)
                              .beginText()
                              .setFontAndSize(candFont, fontSize)
                              .moveText(lineX, lineY)
                              .showText(nameLines.get(li))
                              .endText();
                        drawnBottom = Math.min(drawnBottom, lineY);
                    }

                    float nameTotalH = nameLines.size() * (fontSize + LINE_GAP);
                    float extraGap   = nameLines.size() > 1 ? wrappedLineExtra : 0f;
                    // For RCV contests: when name wraps, add extra spacing so indicators
                    // don't crowd the next candidate's row
                    if (isRankedChoice && nameLines.size() > 1) extraGap += 2f;
                    currentY -= Math.max(rowSpacing, nameTotalH) + extraGap;

                    // ── Explanatory note (just below name, before next row) ───
                    if (candidate.isPrintExplanatoryText() &&
                            candidate.getExplanatoryText() != null &&
                            !candidate.getExplanatoryText().isBlank()) {
                        float noteStartY = nameY - nameTotalH + LINE_GAP * 0.5f;
                        float noteX = indRight ? targetX : nameX;
                        currentY = drawWrappedText(canvas,
                            candidate.getExplanatoryText(), nameW,
                            font(template.isCandidateNoteBold(),
                                 template.isCandidateNoteItalic(),
                                 template.isCandidateNoteAltFont()),
                            template.getCandidateNoteFontSize(),
                            noteX, noteStartY, indRight);
                        currentY -= template.getCandidateNoteFontSize() * 0.5f;
                        drawnBottom = currentY;
                    }

                    // ── Write-in line (below the candidate name row) ──────────
                    // Add one full row of spacing between the "Write-In:" label
                    // and the write line, matching the inter-candidate gap.
                    // The line is centered in the full text column width.
                    if (candidate.isWriteIn()) {
                        currentY -= rowSpacing;   // extra gap matching inter-candidate spacing
                        float lineY      = currentY + fontSize * 0.5f;
                        float lineLen    = textWidth * 0.65f;   // 65% of column width
                        float colCentreX = currentX + CBOX_INDENT + textWidth / 2f;
                        float lineLeft   = colCentreX - lineLen / 2f;
                        float lineRight  = colCentreX + lineLen / 2f;
                        canvas.setStrokeColor(ColorConstants.BLACK).setLineWidth(0.5f)
                              .moveTo(lineLeft, lineY)
                              .lineTo(lineRight, lineY).stroke();
                        drawnBottom = Math.min(drawnBottom, lineY - LINE_GAP);
                        currentY = lineY - LINE_GAP * 2f;
                    }

                    drawnBottom = Math.min(drawnBottom, targetY);

                    // Bounding box: record each indicator box separately.
                    // For ranked-choice contests, each rank box gets its own entry.
                    // For other indicator styles, one entry covers the whole indicator.
                    if (contest.getVotingMethod() == Contest.VotingMethod.RANKED_CHOICE) {
                        final float RANK_INSET_X = 2f;
                        final float RANK_INSET_Y = 3f;
                        int n = rankBoxCount(contest);
                        float rankX = indX;
                        // Iterate in draw order: indRight → rank 1,2…N; else → rank N…1
                        for (int i = 0; i < n; i++) {
                            int rank = indRight ? (i + 1) : (n - i);
                            float bw = (rank == 1) ? FIRST_RANK_BOX_W : OTHER_RANK_BOX_W;
                            double rLeft = MeasurementUtil.ptToInches(rankX + RANK_INSET_X);
                            double rTop  = MeasurementUtil.ptToInches(ph - (targetY + OVAL_HEIGHT - RANK_INSET_Y));
                            double rW    = MeasurementUtil.ptToInches(bw - RANK_INSET_X - RANK_INSET_Y);
                            double rH    = MeasurementUtil.ptToInches(OVAL_HEIGHT - RANK_INSET_Y * 2);
                            candPositions.add(new BallotDimensions.CandidatePosition(
                                candidate.getId(),
                                candidate.getRecordName() + " (Rank " + rank + ")",
                                candidate.isWriteIn(),
                                rLeft, rTop, rW, rH,
                                template.getVoteIndicatorStyle().name()));
                            rankX += bw + (indRight ? (rank == 1 ? RANK_BOX_GAP_AFTER_1 : RANK_BOX_GAP)
                                                     : (rank == 2 ? RANK_BOX_GAP_AFTER_1 : RANK_BOX_GAP));
                        }
                    } else if (template.getVoteIndicatorStyle()
                            == BallotDesignTemplate.VoteIndicatorStyle.CONNECT_DOTS) {
                        // CONNECT_DOTS: sample the center 10% of the gap between
                        // the inner ends of the two leader lines.
                        // Both dots shift inward by SHIFT=9pt; leaders extend 9pt inward.
                        // Leader inner ends:
                        //   left:  indX + 2r + SHIFT + LINE
                        //   right: indX + WIDTH - 2r - SHIFT - LINE
                        final float r     = CONNECT_DOTS_DOT_R;
                        final float SHIFT = 8f;
                        final float LINE  = 2f;
                        float lLeaderEnd = indX + 2 * r + SHIFT + LINE;
                        float rLeaderEnd = indX + CONNECT_DOTS_WIDTH - 2 * r - SHIFT - LINE;
                        float gapW    = rLeaderEnd - lLeaderEnd;
                        float sampleW = Math.max(2f, gapW * 0.10f);
                        float sampleX = lLeaderEnd + (gapW - sampleW) / 2f;
                        final float INDICATOR_INSET_START = 3f;
                        final float INDICATOR_INSET_END   = 3f;
                        // Vertical: use only 1pt inset top/bottom so the sampling
                        // zone is tall enough to catch voter lines drawn up to 4pt
                        // above or below the dot midline.
                        // Zone height = OVAL_HEIGHT - 2pt = 9pt → ±4.5pt from midY.
                        final float CD_V_INSET = 1f;
                        double cdOffLeft = MeasurementUtil.ptToInches(sampleX);
                        double cdOffTop  = MeasurementUtil.ptToInches(
                            ph - (targetY + OVAL_HEIGHT - CD_V_INSET));
                        double cdW_in    = MeasurementUtil.ptToInches(sampleW);
                        double cdH_in    = MeasurementUtil.ptToInches(
                            OVAL_HEIGHT - 2 * CD_V_INSET);
                        candPositions.add(new BallotDimensions.CandidatePosition(
                            candidate.getId(), candidate.getRecordName(),
                            candidate.isWriteIn(),
                            cdOffLeft, cdOffTop, cdW_in, cdH_in,
                            "CONNECT_DOTS"));
                    } else {
                        // Inset the sampling region from the oval border on all sides.
                        // INSET_START applies to the left edge and top edge.
                        // INSET_END applies to the right edge and bottom edge.
                        // At 300 DPI, 1pt = 4.17px. The oval stroke is 0.5pt (~2px).
                        // 3pt inset gives ~1pt clearance beyond the stroke on top/left.
                        final float INDICATOR_INSET_START = 3f;
                        final float INDICATOR_INSET_END   = 3f;
                        double indOffLeft = MeasurementUtil.ptToInches(
                            indX + INDICATOR_INSET_START);
                        double indOffTop  = MeasurementUtil.ptToInches(
                            ph - (targetY + OVAL_HEIGHT - INDICATOR_INSET_START));
                        double indW_in    = MeasurementUtil.ptToInches(
                            drawnIndW - INDICATOR_INSET_START - INDICATOR_INSET_END);
                        double indH_in    = MeasurementUtil.ptToInches(
                            OVAL_HEIGHT - INDICATOR_INSET_START - INDICATOR_INSET_END);
                        candPositions.add(new BallotDimensions.CandidatePosition(
                            candidate.getId(), candidate.getRecordName(),
                            candidate.isWriteIn(),
                            indOffLeft, indOffTop, indW_in, indH_in,
                            template.getVoteIndicatorStyle().name()));
                    }
                }

                // ── Postamble ─────────────────────────────────────────────
                if (contest.isPrintPostamble() &&
                        contest.getPostamble() != null &&
                        !contest.getPostamble().isBlank()) {
                    currentY = drawWrappedText(canvas,
                        contest.getPostamble(), textWidth,
                        font(template.isPostambleBold(), template.isPostambleItalic(),
                        template.isPostambleAltFont()),
                        template.getPostambleFontSize(),
                        currentX + CBOX_INDENT, currentY - LINE_GAP);
                    drawnBottom = currentY;
                }

                // ── Contest bounding box ──────────────────────────────────
                // cboxT was set before content was drawn; cboxB computed from
                // the lowest drawn element with bottom padding.
                float cboxL = currentX;
                float cboxR = currentX + colWidth - 8f;
                float cboxB = drawnBottom - CBOX_BOTTOM_PAD;
                canvas.setStrokeColor(ColorConstants.BLACK).setLineWidth(0.75f)
                      .rectangle(cboxL, cboxB, cboxR - cboxL, cboxT - cboxB).stroke();

                double cOffLeft = MeasurementUtil.ptToInches(cboxL);
                double cOffTop  = MeasurementUtil.ptToInches(ph - cboxT);
                double cW       = MeasurementUtil.ptToInches(cboxR - cboxL);
                double cH       = MeasurementUtil.ptToInches(cboxT - cboxB);

                String cType = contest.getVotingMethod() != null
                    ? contest.getVotingMethod().name() : "PLURALITY";
                int maxVotes = contest.getMaxChoices();
                BallotDimensions.ContestPosition cp = new BallotDimensions.ContestPosition(
                    contest.getId(), contest.getRecordTitle(), cType, maxVotes, pageNum,
                    cOffLeft, cOffTop, cW, cH, candPositions);
                pageContests.computeIfAbsent(pageNum, k -> new ArrayList<>()).add(cp);

                currentY = cboxB - interContestGap;
            }
        }

        printLogService.record(user, combination, template.getPaperSize().name(), copies);
        // Build PageLayout list from per-page maps
        List<BallotDimensions.PageLayout> pages = new ArrayList<>();
        for (Map.Entry<Integer, List<BallotDimensions.ContestPosition>> entry
                : pageContests.entrySet()) {
            int pn = entry.getKey();
            double[] ca = pageContentArea.getOrDefault(pn, new double[]{0, 0, 0, 0});
            // ca = [offsetLeft, offsetTop, width, height]
            double[][] cm = pageCornerMarks.getOrDefault(pn,
                new double[][]{{0,0},{0,0},{0,0},{0,0}});
            double[][] pm = pagePageMarks.getOrDefault(pn, null);
            pages.add(new BallotDimensions.PageLayout(pn,
                ca.length >= 4 ? ca[0] : 0, ca.length >= 4 ? ca[1] : 0,
                ca.length >= 4 ? ca[2] : ca[0], ca.length >= 4 ? ca[3] : ca[1],
                java.util.Collections.unmodifiableList(entry.getValue()), cm,
                pageBarcodeCentre.getOrDefault(pn, new double[]{0, 0}),
                pm));
        }
        layoutService.storeLayout(combination.getId(), pages);
        byte[] pdfBytes = baos.toByteArray();
        List<String> writtenFiles = autoExport(combination.getId(), combination, pdfBytes);
        this.lastWrittenFiles = writtenFiles;
        return pdfBytes;
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTO-EXPORT  — writes XML and YAML to exportDir after every generation
    // ══════════════════════════════════════════════════════════════════════

    /** Writes per-page PDF/XML/YAML files and returns the list of filenames written. */
    List<String> autoExport(Long combinationId, BallotCombination combo, byte[] pdfBytes) {
        List<String> written = new java.util.ArrayList<>();
        try {
            Path dir = Paths.get(exportDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            log.info("Ballot output directory: " + dir);

            // Count pages in the generated PDF
            int pageCount;
            try (PdfDocument countDoc = new PdfDocument(
                     new PdfReader(new java.io.ByteArrayInputStream(pdfBytes)))) {
                pageCount = countDoc.getNumberOfPages();
            }

            if (pageCount == 1) {
                // Single-page ballot — write one set of files
                String meta = encodeBallotMetadata(combo, 1).replace('|', '_');
                String safe = "ballot_" + meta;
                Files.write(dir.resolve(safe + ".pdf"), pdfBytes);
                String absBase = dir.toAbsolutePath() + "/" + safe;
                if ("xml".equalsIgnoreCase(exportFormat) || "both".equalsIgnoreCase(exportFormat)) {
                    Files.writeString(dir.resolve(safe + ".xml"),
                        exportService.exportOffsetReportXml(combinationId,
                            ExportService.MeasurementUnit.INCHES));
                    written.add(absBase + ".xml");
                }
                if (!"xml".equalsIgnoreCase(exportFormat) || "both".equalsIgnoreCase(exportFormat)) {
                    Files.writeString(dir.resolve(safe + ".yaml"),
                        exportService.exportOffsetReportYaml(combinationId,
                            ExportService.MeasurementUnit.INCHES, 1));
                    written.add(absBase + ".yaml");
                }
                log.info("Ballot files written: format=" + exportFormat + " base=" + absBase);
                written.add(absBase + ".pdf");
            } else {
                // Multi-page ballot — write one PDF + YAML + XML per page
                for (int pageNum = 1; pageNum <= pageCount; pageNum++) {
                    String meta = encodeBallotMetadata(combo, pageNum).replace('|', '_');
                    String safe = "ballot_" + meta;

                    // Extract single page from the full PDF
                    java.io.ByteArrayOutputStream pageOut = new java.io.ByteArrayOutputStream();
                    try (PdfDocument src = new PdfDocument(
                             new PdfReader(new java.io.ByteArrayInputStream(pdfBytes)));
                         PdfDocument dst = new PdfDocument(new PdfWriter(pageOut))) {
                        src.copyPagesTo(pageNum, pageNum, dst);
                    }
                    Files.write(dir.resolve(safe + ".pdf"), pageOut.toByteArray());

                    // Per-page YAML and XML (only indicators on this page)
                    Files.writeString(dir.resolve(safe + ".xml"),
                        exportService.exportOffsetReportXml(combinationId,
                            ExportService.MeasurementUnit.INCHES, pageNum));
                    Files.writeString(dir.resolve(safe + ".yaml"),
                        exportService.exportOffsetReportYaml(combinationId,
                            ExportService.MeasurementUnit.INCHES, pageNum));
                    String absBase = dir.toAbsolutePath() + "/" + safe;
                    log.info("Ballot files written to " + absBase + ".{pdf,xml,yaml}");
                    written.add(absBase + ".pdf");
                    written.add(absBase + ".xml");
                    written.add(absBase + ".yaml");
                }
            }
        } catch (Exception e) {
            log.warn("Could not write ballot files for combination " +
                combinationId + ": " + e.getMessage());
        }
        return written;
    }

    // ══════════════════════════════════════════════════════════════════════
    // RANKED-CHOICE INDICATOR HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns the number of rank boxes for a ranked-choice contest.
     * Caps at MAX_RANK_BOXES (5).  For large elections (many candidates)
     * where maxRankChoices is 0 (unlimited), also caps at 5.
     */
    private int rankBoxCount(Contest contest) {
        int configured = contest.getMaxRankChoices();
        int fromCandidates = Math.min(contest.getCandidates().size(), MAX_RANK_BOXES);
        if (configured > 0) return Math.min(configured, MAX_RANK_BOXES);
        return fromCandidates > 0 ? fromCandidates : MAX_RANK_BOXES;
    }

    /**
     * Total width in pts for all rank boxes in a ranked-choice contest.
     * Wide box (rank 1, closest to name) = FIRST_RANK_BOX_W; others = OTHER_RANK_BOX_W.
     */
    private float rankedChoiceIndicatorWidth(Contest contest) {
        int n = rankBoxCount(contest);
        if (n <= 0) return FIRST_RANK_BOX_W;
        // rank-1 box + extra gap + (n-1) other boxes with standard gaps
        return FIRST_RANK_BOX_W + RANK_BOX_GAP_AFTER_1
             + (n - 1) * (OTHER_RANK_BOX_W + RANK_BOX_GAP);
    }

    /**
     * Returns the effective indicator width for a contest, using ranked-choice
     * sizing when the contest is RANKED_CHOICE regardless of template style.
     */
    private float effectiveIndicatorWidth(BallotDesignTemplate tmpl, Contest contest) {
        if (contest.getVotingMethod() == Contest.VotingMethod.RANKED_CHOICE)
            return rankedChoiceIndicatorWidth(contest);
        return indicatorWidth(tmpl.getVoteIndicatorStyle(), contest);
    }

    /**
     * Draws the vote target for a contest, using ranked-choice boxes when
     * the contest is RANKED_CHOICE, otherwise the template's configured style.
     * Returns the actual width drawn (pts) for bounding-box recording.
     */
    private float drawEffectiveVoteTarget(PdfCanvas canvas,
                                           BallotDesignTemplate tmpl,
                                           Contest contest,
                                           float x, float y) {
        if (contest.getVotingMethod() == Contest.VotingMethod.RANKED_CHOICE) {
            return drawRankedChoiceBoxes(canvas, tmpl, contest, x, y);
        }
        drawVoteTarget(canvas, tmpl.getVoteIndicatorStyle(), contest, x, y, false);
        return indicatorWidth(tmpl.getVoteIndicatorStyle(), contest);
    }

    /**
     * Draws ranked-choice rank boxes for one candidate row.
     *
     * DEFAULT layout (rcvIndicatorsRight=false):
     *   [ N ]…[ 2 ][  1  ]  Candidate Name
     *   rank-1 (widest) is rightmost, closest to the name.
     *
     * RIGHT-SIDE layout (rcvIndicatorsRight=true):
     *   Candidate Name  [  1  ][ 2 ]…[ N ]
     *   rank-1 (widest) is leftmost, still closest to the name.
     *   In this mode x is the LEFT edge of the indicator group
     *   (caller has already placed it to the right of the name).
     *
     * Returns the total width drawn (pts).
     */
    private float drawRankedChoiceBoxes(PdfCanvas canvas,
                                         BallotDesignTemplate tmpl,
                                         Contest contest,
                                         float x, float y) {
        float   lineW = tmpl.getRcvBoxLineWidthPt();
        boolean dash  = tmpl.isIndicatorDashed();
        canvas.saveState();
        canvas.setStrokeColor(ColorConstants.BLACK).setLineWidth(lineW);
        if (dash)
            canvas.setLineDash(new float[]{INDICATOR_DASH_ON, INDICATOR_DASH_OFF}, 0f);
        int   n    = rankBoxCount(contest);
        float curX = x;
        boolean right = tmpl.isRcvIndicatorsRight();

        if (right) {
            // rank-1 first (leftmost, closest to name), then rank-2…N
            for (int rank = 1; rank <= n; rank++) {
                float bw  = (rank == 1) ? FIRST_RANK_BOX_W : OTHER_RANK_BOX_W;
                canvas.rectangle(curX, y, bw, OVAL_HEIGHT).stroke();
                // Extra gap after rank-1 to visually separate it from rank-2
                curX += bw + (rank == 1 ? RANK_BOX_GAP_AFTER_1 : RANK_BOX_GAP);
            }
        } else {
            // rank-N first (leftmost), rank-1 last (rightmost, closest to name)
            for (int rank = n; rank >= 1; rank--) {
                float bw  = (rank == 1) ? FIRST_RANK_BOX_W : OTHER_RANK_BOX_W;
                canvas.rectangle(curX, y, bw, OVAL_HEIGHT).stroke();
                // Extra gap before rank-1 (it's the next box when rank==2)
                curX += bw + (rank == 2 ? RANK_BOX_GAP_AFTER_1 : RANK_BOX_GAP);
            }
        }
        canvas.restoreState();
        // Subtract the trailing gap that was added after the last box
        boolean right2 = tmpl.isRcvIndicatorsRight();
        // In right mode last box drawn is rank-N (narrow), gap was RANK_BOX_GAP
        // In left  mode last box drawn is rank-1 (wide),  gap was RANK_BOX_GAP_AFTER_1
        float trailingGap = right2 ? RANK_BOX_GAP : RANK_BOX_GAP_AFTER_1;
        return curX - x - trailingGap;
    }

    /**
     * Draws rank-number labels ("1", "2", … "N") centered above each rank box.
     * Called only for the first candidate in a ranked-choice contest.
     * Labels are drawn at rcvRankNumberFontPt, centered above each box.
     *
     * @param x      left edge of the indicator group (same as passed to drawRankedChoiceBoxes)
     * @param y      bottom of the indicator row in PDF coords
     */
    private void drawRankNumberLabels(PdfCanvas canvas,
                                       BallotDesignTemplate tmpl,
                                       Contest contest,
                                       float x, float y) throws Exception {
        // Gap between bottom of label text and top of the indicator box below.
        // Must match the RCV_LABEL_GAP used when offsetting currentY in the
        // candidate loop — so labels and indicators align correctly.
        final float RCV_LABEL_GAP_ABOVE = 4f;
        float fontSize = tmpl.getRcvRankNumberFontPt();
        PdfFont numFont = font(false, false);
        int   n    = rankBoxCount(contest);
        float curX = x;
        boolean right = tmpl.isRcvIndicatorsRight();

        // Determine order: same order as drawRankedChoiceBoxes
        int[] ranks = new int[n];
        if (right) {
            for (int i = 0; i < n; i++) ranks[i] = i + 1;       // 1,2,3…N
        } else {
            for (int i = 0; i < n; i++) ranks[i] = n - i;        // N,N-1…1
        }

        for (int i = 0; i < n; i++) {
            int   rank  = ranks[i];
            float bw    = (rank == 1) ? FIRST_RANK_BOX_W : OTHER_RANK_BOX_W;
            String label = "#" + rank;
            float labelW  = numFont.getWidth(label, fontSize);
            float labelX  = curX + (bw - labelW) / 2f;
            float labelY  = y + OVAL_HEIGHT + RCV_LABEL_GAP_ABOVE;
            canvas.setFillColor(ColorConstants.BLACK)
                  .beginText()
                  .setFontAndSize(numFont, fontSize)
                  .moveText(labelX, labelY)
                  .showText(label)
                  .endText();
            // Match gap logic from drawRankedChoiceBoxes
            if (right) {
                curX += bw + (rank == 1 ? RANK_BOX_GAP_AFTER_1 : RANK_BOX_GAP);
            } else {
                curX += bw + (rank == 2 ? RANK_BOX_GAP_AFTER_1 : RANK_BOX_GAP);
            }
        }
    }

    /**
     * Builds the instruction text for a ranked-choice contest.
     * If the contest has a custom instruction, that is used verbatim.
     * Otherwise generates a standard explanation of the box layout.
     */
    private String buildRankedChoiceInstruction(Contest contest) {
        if (contest.getInstructions() != null && !contest.getInstructions().isBlank())
            return contest.getInstructions();
        int n = rankBoxCount(contest);
        return tx().rankedChoiceInstruction();
    }

    // ══════════════════════════════════════════════════════════════════════
    // PAGE MANAGEMENT
    // ══════════════════════════════════════════════════════════════════════


    /**
     * Calculates the required header zone height in points from all contributing
     * elements: QR/barcode height, headline (with wrapping), body text paragraphs
     * (with wrapping and blank-line separators), and padding.
     * Returns at least HEADER_ZONE_PT to preserve a minimum header area.
     */
    /**
     * Measures the actual rendered height of the header HTML by performing a
     * dry-run layout into a throwaway in-memory PDF at the correct column width.
     * Returns the true occupied height in points, or -1 if measurement fails.
     *
     * This is called from computeHeaderZoneHeight() so the header zone is sized
     * precisely to the content — no more clipping or excess whitespace.
     */
    private float measureHeaderHeight(String wrappedHtml, float width) {
        try {
            java.io.ByteArrayOutputStream dummy = new java.io.ByteArrayOutputStream();
            com.itextpdf.kernel.pdf.PdfWriter  dw  =
                new com.itextpdf.kernel.pdf.PdfWriter(dummy);
            com.itextpdf.kernel.pdf.PdfDocument dpdf =
                new com.itextpdf.kernel.pdf.PdfDocument(dw);

            // Tall throwaway page — content must not be clipped
            float tallH = 10000f;
            com.itextpdf.kernel.geom.PageSize ps =
                new com.itextpdf.kernel.geom.PageSize(width, tallH);
            dpdf.addNewPage(ps);

            com.itextpdf.html2pdf.ConverterProperties props =
                new com.itextpdf.html2pdf.ConverterProperties();

            // Use Document.add() for each element and read how far down the
            // renderer has moved after all elements are added.
            // iText Y goes UP from the bottom; the renderer starts at the top
            // of the page (Y = tallH - topMargin) and moves downward.
            com.itextpdf.layout.Document ddoc =
                new com.itextpdf.layout.Document(dpdf, ps);
            ddoc.setMargins(0, 0, 0, 0);

            java.util.List<com.itextpdf.layout.element.IBlockElement> els =
                com.itextpdf.html2pdf.HtmlConverter.convertToElements(wrappedHtml, props)
                    .stream()
                    .filter(e -> e instanceof com.itextpdf.layout.element.IBlockElement)
                    .map(e -> (com.itextpdf.layout.element.IBlockElement) e)
                    .toList();

            for (com.itextpdf.layout.element.IBlockElement el : els)
                ddoc.add(el);

            // getCurrentArea() returns the remaining free area on the page.
            // Its top edge = where the next element would start = bottom of last element.
            // Consumed height = tallH - currentArea.top
            float currentTop = ddoc.getRenderer()
                .getCurrentArea().getBBox().getTop();
            ddoc.close();   // also closes dpdf

            // currentTop is now the Y of the bottom of the last rendered element
            // (in iText's upward coordinate system).
            // Consumed height = tallH - currentTop.
            // Add a descender allowance: ~25% of the body font size (9pt → ~2.25pt),
            // plus a small general margin. Use 8pt to be safe.
            float consumed = tallH - currentTop + 8f;
            return consumed > 8f ? consumed : -1f;

        } catch (Exception e) {
            log.debug("measureHeaderHeight failed (will use estimate): {}", e.getMessage());
            return -1f;
        }
    }

    private float computeHeaderZoneHeight(BallotDesignTemplate tmpl,
                                           float pw, boolean codeAtRight) {
        float qrH = tmpl.getBarcodeHeightPt() + 8f;

        String html = tmpl.getHeaderHtml();
        if (html == null || html.isBlank())
            return Math.max(HEADER_ZONE_PT, qrH);

        // Build the same wrapped HTML that drawHeaderZone will use,
        // substituting placeholder token values for measurement purposes.
        // Token values don't affect layout height (same font/size regardless).
        String measured = html
            .replace("{electionName}",     "Election Name")
            .replace("{jurisdictionName}", "Jurisdiction")
            .replace("{regionName}",       "Region")
            .replace("{partyName}",        "Party")
            .replace("{ballotTypeName}",   "Ballot Type")
            .replace("{indicatorName}",    "oval")
            .replace("{pageNum}",          "1")
            // Apply the same Quill class → style conversion as drawHeaderZone
            .replace("class=\"ql-align-center\"",  "style=\"text-align:center\"")
            .replace("class=\"ql-align-right\"",   "style=\"text-align:right\"")
            .replace("class=\"ql-align-justify\"", "style=\"text-align:justify\"")
            .replaceAll("<p>\\s*</p>", "")
            .replaceAll("<p>\\s*&nbsp;\\s*</p>", "")
            .stripTrailing();

        // Width available for header text = page width minus QR code and margins
        float headerW = codeAtRight
            ? pw - tmpl.getBarcodeHeightPt() - tmpl.getMarginRightPt() - 8f
                 - tmpl.getMarginLeftPt()
            : pw - tmpl.getBarcodeHeightPt() - tmpl.getMarginLeftPt() - 8f
                 - tmpl.getMarginRightPt();
        headerW = Math.max(72f, headerW);   // at least 1"

        String wrapped = String.format(
            "<html><head><style>"
            + "body{font-family:Helvetica,Arial,sans-serif;font-size:9pt;line-height:1.4;}"
            + "p{margin:0;padding:0;font-size:9pt;}"
            + "strong{font-weight:bold;}em{font-style:italic;}"
            + "</style></head><body style=\"margin:0;padding:0;width:%.1fpt;\">%s</body></html>",
            headerW, measured);

        // Try precise measurement first
        float measured_h = measureHeaderHeight(wrapped, headerW);
        if (measured_h > 0f)
            return Math.max(HEADER_ZONE_PT, Math.max(qrH, measured_h));

        // Fallback: estimate from paragraph count
        int paraCount = 0;
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("<p[^>]*>").matcher(html);
        while (m.find()) paraCount++;
        float estimatedH = paraCount * 12.6f + 8f;
        return Math.max(HEADER_ZONE_PT, Math.max(qrH, estimatedH));
    }

    /**
     * Estimates wrapped line count for text at fontSize in a column of maxWidthPt.
     * Uses 0.55×fontSize as average character width (Helvetica approximation).
     */
    private int estimateWrappedLines(String text, float maxWidthPt, float fontSize) {
        if (text == null || text.isBlank()) return 1;
        int charsPerLine = Math.max(1, (int)(maxWidthPt / (fontSize * 0.55f)));
        String[] words = text.split("\s+");
        int lines = 1, lineLen = 0;
        for (String w : words) {
            int wl = w.length();
            if (lineLen > 0 && lineLen + 1 + wl > charsPerLine) {
                lines++; lineLen = wl;
            } else {
                lineLen += (lineLen > 0 ? 1 : 0) + wl;
            }
        }
        return lines;
    }

    private PdfCanvas openPage(PdfDocument pdf, float pw, float ph, int pageNum,
                                BallotCombination combo, BallotDesignTemplate tmpl,
                                float bbLeft, float bbRight, float bbBottom,
                                float[] state) throws Exception {
        return openPage(pdf, pw, ph, pageNum, combo, tmpl, bbLeft, bbRight,
                        bbBottom, state, null, null);
    }

    private PdfCanvas openPage(PdfDocument pdf, float pw, float ph, int pageNum,
                                BallotCombination combo, BallotDesignTemplate tmpl,
                                float bbLeft, float bbRight, float bbBottom,
                                float[] state, double[] barcodeCentreOut) throws Exception {
        return openPage(pdf, pw, ph, pageNum, combo, tmpl, bbLeft, bbRight,
                        bbBottom, state, barcodeCentreOut, null);
    }

    private PdfCanvas openPage(PdfDocument pdf, float pw, float ph, int pageNum,
                                BallotCombination combo, BallotDesignTemplate tmpl,
                                float bbLeft, float bbRight, float bbBottom,
                                float[] state, double[] barcodeCentreOut,
                                double[][] pageMarksOut) throws Exception {

        PdfCanvas canvas = new PdfCanvas(pdf.addNewPage(new PageSize(pw, ph)));
        // ── Barcode / code placement ─────────────────────────────────────
        // Every side gets its own uniquely-encoded QR code (with pageNum).
        // The QR code is right-justified at the margin within the header zone.
        // No linear barcode is drawn — QR alone provides reliable identification.
        String bcPos  = tmpl.getBarcodePosition() != null
                        ? tmpl.getBarcodePosition() : "TOP_LEFT";
        boolean codeAtTop    = !bcPos.startsWith("BOTTOM");
        boolean codeAtRight  = bcPos.endsWith("RIGHT");

        // Code zone height: large enough to hold QR code AND all header text
        int qrSz = (int) Math.min(tmpl.getBarcodeHeightPt(), HEADER_ZONE_PT - 20f);
        float zoneH = computeHeaderZoneHeight(tmpl, pw, codeAtRight);

        // Vertical position of code zone top
        float codeZoneTop, codeZoneBottom;
        if (codeAtTop) {
            codeZoneTop    = ph - tmpl.getMarginTopPt() - 14f;   // just below metadata line
            codeZoneBottom = codeZoneTop - zoneH;
        } else {
            codeZoneBottom = tmpl.getMarginBottomPt() + 5f;
            codeZoneTop    = codeZoneBottom + zoneH;
        }

        // Content bounding box: always clears the code zone.
        // For the registration marks (MARK_H=9pt) to fit with 1/8" (9pt) gaps:
        //   above marks → marks → below marks → bbTop
        //   total clearance = 9 + 9 + 9 = 27pt below codeZoneBottom.
        // Same logic applies when codes are at bottom.
        float markClearance = MARK_GAP + MARK_H + MARK_GAP;  // = 9+9+9 = 27pt
        float bbTop, bbBottom2;
        if (codeAtTop) {
            bbTop     = codeZoneBottom - markClearance;
            bbBottom2 = bbBottom;
        } else {
            bbTop     = ph - tmpl.getMarginTopPt() - 22f;  // slim header space
            bbBottom2 = codeZoneTop + markClearance;
        }

        // Draw codes on every page
        String bcData = encodeBallotMetadata(combo, pageNum);
        float[] codeRegion = drawBarcodesSideBySide(canvas, bcData, tmpl,
            pw, ph, codeAtRight, codeZoneTop, qrSz);
        // codeRegion = [left, right, top, bottom] of the code block
        // Store QR centre in output param if provided
        if (barcodeCentreOut != null && barcodeCentreOut.length >= 2) {
            barcodeCentreOut[0] = MeasurementUtil.ptToInches(codeRegion[0] + qrSz / 2f);
            barcodeCentreOut[1] = MeasurementUtil.ptToInches(ph - (codeZoneTop + codeZoneBottom) / 2f);
        }

        // Header text: on the opposite horizontal side from the codes
        float textLeft, textRight;
        if (codeAtRight) {
            textLeft  = tmpl.getMarginLeftPt() + 5f;
            textRight = codeRegion[0] - 8f;
        } else {
            textLeft  = codeRegion[1] + 8f;
            textRight = pw - tmpl.getMarginRightPt() - 5f;
        }

        // ── Page-level orientation marks: two 18×9pt rectangles at the top ──
        // Sit just inside the top margin at the left and right page edges,
        // above the metadata line.  Both are 18×9pt (same as TL content mark)
        // so TL remains the sole asymmetric shape for orientation detection.
        float pageMarkW  = MARK_RECT_W;           // 18pt wide
        float pageMarkH  = MARK_H;                // 9pt tall
        // Place marks just inside the top margin — below the margin line.
        // In PDF coords (origin=bottom): top margin line is at ph - marginTop.
        // The extra PAGE_MARK_DROP (1/4") prevents PTL/PTR being clipped by
        // printers and scanners that cannot image the very top of the page.
        // Page marks sit near the physical top of the page, not relative to the
        // content margin — so they work correctly even with large header templates.
        final float PAGE_MARK_DROP = 18f;   // 1/4" extra distance from top edge
        float pageMarkCY = ph - MARK_GAP - pageMarkH / 2f - PAGE_MARK_DROP;
        // Align horizontally with the content-box TL/TR marks (bbLeft/bbRight = margin+5pt)
        float ptlCX = bbLeft  + pageMarkW / 2f;
        float ptrCX = bbRight - pageMarkW / 2f;
        canvas.setFillColor(ColorConstants.BLACK);
        drawRectMark(canvas, ptlCX, pageMarkCY, pageMarkW, pageMarkH);  // PTL
        drawRectMark(canvas, ptrCX, pageMarkCY, pageMarkW, pageMarkH);  // PTR

        // Store page mark centres for YAML export (page-absolute inches from top-left)
        if (pageMarksOut != null && pageMarksOut.length >= 2) {
            log.debug("PAGE MARKS DEBUG: ph={} marginTop={} MARK_GAP={} pageMarkH={} pageMarkCY={} result={}",
                ph, tmpl.getMarginTopPt(), MARK_GAP, pageMarkH, pageMarkCY,
                MeasurementUtil.ptToInches(ph - pageMarkCY));
            pageMarksOut[0] = new double[]{
                MeasurementUtil.ptToInches(ptlCX),
                MeasurementUtil.ptToInches(ph - pageMarkCY)
            };
            pageMarksOut[1] = new double[]{
                MeasurementUtil.ptToInches(ptrCX),
                MeasurementUtil.ptToInches(ph - pageMarkCY)
            };
        }

        // Metadata line (page 1: full detail; other pages: slim)
        if (pageNum == 1) {
            drawBallotHeader(canvas, pdf, combo, tmpl, pw, ph, textLeft, textRight,
                             codeZoneTop, codeZoneBottom, pageNum);
        } else {
            drawSlimHeader(canvas, pdf, combo, tmpl, pw, ph, pageNum, textLeft, textRight,
                           codeZoneTop, codeZoneBottom);
        }

        // Content bounding box (heavy border)
        canvas.setStrokeColor(ColorConstants.BLACK).setLineWidth(1.5f)
              .rectangle(bbLeft, bbBottom2, bbRight - bbLeft, bbTop - bbBottom2).stroke();

        // Corner registration marks — placed directly above/below the bbox.
        // All marks are vertically centred in the gap above/below the border.
        // TL is a rectangle (wider) to uniquely identify orientation:
        //   if TL mark is in the image top half → upright; bottom half → flip 180°.
        // Marks sit horizontally aligned with the left/right bbox edges.
        // In PDF coords (Y up): mark y-centre = bbTop + MARK_GAP + MARK_H/2 (above)
        //                                        bbBottom2 - MARK_GAP - MARK_H/2 (below)
        float mh    = MARK_H;
        float myCentreTop = bbTop    + MARK_GAP + mh / 2f;   // above bbox
        float myCentreBot = bbBottom2 - MARK_GAP - mh / 2f;  // below bbox
        canvas.setFillColor(ColorConstants.BLACK);
        // TL: rectangle, above-left  (RECT_W wide — orientation indicator)
        drawRectMark(canvas, bbLeft  + MARK_RECT_W / 2f, myCentreTop, MARK_RECT_W, mh);
        // TR: square, above-right
        drawRectMark(canvas, bbRight - MARK_SQ_W  / 2f, myCentreTop, MARK_SQ_W,   mh);
        // BR: square, below-right
        drawRectMark(canvas, bbRight - MARK_SQ_W  / 2f, myCentreBot, MARK_SQ_W,   mh);
        // BL: square, below-left
        drawRectMark(canvas, bbLeft  + MARK_SQ_W  / 2f, myCentreBot, MARK_SQ_W,   mh);

        // bbTop is used below for state assignment

        state[0] = bbTop;
        state[1] = bbLeft + 5f;
        state[2] = bbTop - tmpl.getContestTitleFontSize() - 15f;  // 5pt baseline + 10pt header gap
        state[3] = bbBottom2;   // actual bottom (may be above margin when codes are at bottom)
        return canvas;
    }

    // ══════════════════════════════════════════════════════════════════════
    // TEXT PRIMITIVE
    // ══════════════════════════════════════════════════════════════════════

    private float drawWrappedText(PdfCanvas canvas, String text, float maxWidthPt,
                                   PdfFont f, float fontSize,
                                   float x, float startY) throws Exception {
        return drawWrappedText(canvas, text, maxWidthPt, f, fontSize, x, startY, false);
    }

    /** Right-justify each wrapped line within maxWidthPt when rightAlign=true. */
    private float drawWrappedText(PdfCanvas canvas, String text, float maxWidthPt,
                                   PdfFont f, float fontSize,
                                   float x, float startY,
                                   boolean rightAlign) throws Exception {
        if (text == null || text.isBlank()) return startY;
        canvas.setFillColor(ColorConstants.BLACK);
        float y = startY;
        for (String line : wrapText(text, maxWidthPt, fontSize)) {
            float lineX = x;
            if (rightAlign) {
                float lineW = f.getWidth(line, fontSize);
                lineX = x + maxWidthPt - lineW - 2f;
            }
            canvas.beginText().setFontAndSize(f, fontSize)
                  .moveText(lineX, y).showText(line).endText();
            y -= (fontSize + LINE_GAP);
        }
        return y;
    }

    // ══════════════════════════════════════════════════════════════════════
    // FONT
    // ══════════════════════════════════════════════════════════════════════

    private PdfFont font(boolean bold, boolean italic) throws Exception {
        return font(bold, italic, false);
    }

    private PdfFont font(boolean bold, boolean italic, boolean altFont) throws Exception {
        BallotDesignTemplate tmpl = _template.get();
        String name = (tmpl != null) ? tmpl.fontName(bold, italic, altFont)
                                      : BallotDesignTemplate.fontName(bold, italic,
                                            BallotDesignTemplate.FontFamily.HELVETICA);
        return PdfFontFactory.createFont(name);
    }

    // ══════════════════════════════════════════════════════════════════════
    // HEADER ZONE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Draws a QR code only — right-justified at the right margin when codeAtRight,
     * or left-justified at the left margin otherwise.
     * No linear barcode is drawn; the QR code alone is used for identification.
     *
     * Returns float[4] = {codeLeft, codeRight, zoneTop, codeBottom}
     * so the caller can position header text on the opposite side.
     */
    private float[] drawBarcodesSideBySide(PdfCanvas canvas, String data,
                                            BallotDesignTemplate tmpl,
                                            float pw, float ph,
                                            boolean codeAtRight,
                                            float zoneTop, int qrSz) throws Exception {
        // QR code only — right-justified at the margin (or left-justified if codeAtRight=false)
        float codeLeft;
        if (codeAtRight) {
            codeLeft = pw - tmpl.getMarginRightPt() - 5f - qrSz;
        } else {
            codeLeft = tmpl.getMarginLeftPt() + 5f;
        }

        float codeY = zoneTop - 4f - qrSz;  // top-aligned within zone

        MultiFormatWriter writer = new MultiFormatWriter();
        BitMatrix qrMatrix = writer.encode(data, BarcodeFormat.QR_CODE, qrSz, qrSz);
        drawBitMatrix(canvas, qrMatrix, codeLeft, codeY, qrSz, qrSz);

        return new float[]{ codeLeft, codeLeft + qrSz, zoneTop, codeY };
    }

    private void drawBitMatrix(PdfCanvas canvas, BitMatrix m,
                                float x, float y, int w, int h) {
        float mw = (float) w / m.getWidth(), mh = (float) h / m.getHeight();
        canvas.setFillColor(ColorConstants.BLACK);
        for (int row = 0; row < m.getHeight(); row++)
            for (int col = 0; col < m.getWidth(); col++)
                if (m.get(col, row))
                    canvas.rectangle(x + col * mw,
                        y + (m.getHeight() - row - 1) * mh, mw, mh).fill();
    }

    /**
     * Renders the header zone HTML into the fixed rectangular area to the side
     * of the barcodes, using iText html2pdf for full layout flexibility.
     *
     * The HTML may use inline CSS, tables, images (as data URIs), and any
     * standard HTML elements. The following tokens are replaced before rendering:
     *   {electionName}, {jurisdictionName}, {regionName}, {partyName},
     *   {ballotTypeName}, {indicatorName}, {pageNum}
     */
    private void drawHeaderZone(com.itextpdf.kernel.pdf.PdfDocument pdfDoc,
                                 BallotCombination combo,
                                 BallotDesignTemplate tmpl,
                                 float left, float bottom, float width, float height,
                                 int pageNum) throws Exception {
        String html = tmpl.getHeaderHtml();
        if (html == null || html.isBlank()) return;

        // Convert Quill-generated CSS classes to inline styles so iText
        // html2pdf can render them. Quill writes alignment as class="ql-align-center"
        // rather than style="text-align:center", which html2pdf ignores.
        html = html
            .replace("class=\"ql-align-center\"", "style=\"text-align:center\"")
            .replace("class=\"ql-align-right\"",  "style=\"text-align:right\"")
            .replace("class=\"ql-align-justify\"", "style=\"text-align:justify\"")
            // Quill sometimes combines existing style with class — handle both
            .replace(" class=\"ql-align-center\"", " style=\"text-align:center\"")
            .replace(" class=\"ql-align-right\"",  " style=\"text-align:right\"")
            .replace(" class=\"ql-align-justify\"", " style=\"text-align:justify\"")
            // Remove empty paragraphs that cause excess vertical whitespace
            .replaceAll("<p>\\s*</p>", "")
            .replaceAll("<p>\\s*&nbsp;\\s*</p>", "")
            .replaceAll("<p>\\s*\\*\\s*</p>", "")
            .replaceAll("<p>\\s*</p>\\s*$", "")
            .stripTrailing();

        // Token substitution
        String party     = combo.getParty() != null ? combo.getParty().getName() : "Nonpartisan";
        String indName   = switch (tmpl.getVoteIndicatorStyle()) {
            case OVAL         -> "oval";
            case BOX     -> "box";
            case ARROW        -> "arrow";
            case NUMBER_FIELD -> "number box";
            case CONNECT_DOTS -> "connection line";
        };
        html = html
            .replace("{electionName}",     combo.getElection().getName())
            .replace("{jurisdictionName}", combo.getRegion().getJurisdiction().getName())
            .replace("{regionName}",       combo.getRegion().getName())
            .replace("{partyName}",        party)
            .replace("{ballotTypeName}",   combo.getBallotType().getName())
            .replace("{indicatorName}",    indName)
            .replace("{pageNum}",          String.valueOf(pageNum));

        // Wrap in a sized container so html2pdf knows the available area
        String wrapped = String.format(
            "<html><head><style>"+ "body{font-family:Helvetica,Arial,sans-serif;font-size:9pt;line-height:1.4;}"+ "p{margin:0;padding:0;font-size:9pt;}"+ "strong{font-weight:bold;}"+ "em{font-style:italic;}"+ "</style></head><body style=\"margin:0;padding:0;width:%.1fpt;\">%s</body></html>",
            width, html);

        com.itextpdf.html2pdf.ConverterProperties props =
            new com.itextpdf.html2pdf.ConverterProperties();

        // Render into the zone rectangle on the correct page
        com.itextpdf.kernel.geom.Rectangle zone =
            new com.itextpdf.kernel.geom.Rectangle(left, bottom, width, height);

        com.itextpdf.layout.Document doc =
            new com.itextpdf.layout.Document(pdfDoc,
                new com.itextpdf.kernel.geom.PageSize(
                    pdfDoc.getPage(pageNum).getPageSize()));
        doc.setMargins(0, 0, 0, 0);

        java.util.List<com.itextpdf.layout.element.IBlockElement> elements =
            com.itextpdf.html2pdf.HtmlConverter.convertToElements(wrapped, props)
                .stream()
                .filter(e -> e instanceof com.itextpdf.layout.element.IBlockElement)
                .map(e -> (com.itextpdf.layout.element.IBlockElement) e)
                .toList();

        com.itextpdf.layout.element.Div container =
            new com.itextpdf.layout.element.Div();
        container.setFixedPosition(pageNum, left, bottom, width);
        container.setHeight(height);
        container.setMargin(0).setPadding(0);
        container.setProperty(
            com.itextpdf.layout.properties.Property.OVERFLOW_Y,
            com.itextpdf.layout.properties.OverflowPropertyValue.HIDDEN);

        for (com.itextpdf.layout.element.IBlockElement el : elements) {
            container.add(el);
        }

        doc.add(container);
        doc.flush();
        // Do NOT close doc — it shares the PdfDocument with the canvas
    }

    // ══════════════════════════════════════════════════════════════════════
    // METADATA HEADERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Draw a filled rectangular registration mark centred at (cx, cy).
     * TL mark uses w=MARK_RECT_W (rectangle) to indicate orientation;
     * all others use w=h=MARK_SQ_W (square).
     */
    private void drawRectMark(PdfCanvas canvas, float cx, float cy,
                               float w, float h) {
        canvas.rectangle(cx - w/2f, cy - h/2f, w, h).fill();
    }

    /**
     * Compute the page-absolute positions (inches from image top-left) of
     * the four corner registration mark centres.
     * Order: TL, TR, BR, BL.
     *
     * PDF coords have Y=0 at bottom. Image coords have Y=0 at top.
     * mo = offset from bbox corner to mark centre.
     */
    /**
     * Compute page-absolute positions (inches, from image top-left) of the
     * four corner registration mark centres.
     *
     * Marks are directly above/below the bbox; their x-centres align with
     * the bbox left/right edges (offset by half their own width inward).
     * y-centre = bbTop + MARK_GAP + MARK_H/2 (above) in PDF coords.
     * In image coords (Y flipped): y_img = (ph - y_pdf) / 72.
     *
     * TL uses MARK_RECT_W; others use MARK_SQ_W.
     */
    private double[][] computeCornerMarks(float bbLeft, float bbTop,
                                           float bbRight, float bbBottom, float ph) {
        float mh = MARK_H;
        // PDF y-centres
        float pdfYTop = bbTop    + MARK_GAP + mh / 2f;  // above bbox
        float pdfYBot = bbBottom - MARK_GAP - mh / 2f;  // below bbox
        // Image y-coords (flip)
        double imgYTop = MeasurementUtil.ptToInches(ph - pdfYTop);
        double imgYBot = MeasurementUtil.ptToInches(ph - pdfYBot);
        // X-centres (horizontal centres aligned with bbox edges, offset inward by half mark width)
        double tlX = MeasurementUtil.ptToInches(bbLeft  + MARK_RECT_W / 2f);
        double trX = MeasurementUtil.ptToInches(bbRight - MARK_SQ_W  / 2f);
        double blX = MeasurementUtil.ptToInches(bbLeft  + MARK_SQ_W  / 2f);
        double brX = MeasurementUtil.ptToInches(bbRight - MARK_SQ_W  / 2f);
        // Order: TL, TR, BR, BL — each {x, y}
        return new double[][]{
            {tlX, imgYTop},  // TL (rectangle)
            {trX, imgYTop},  // TR (square)
            {brX, imgYBot},  // BR (square)
            {blX, imgYBot}   // BL (square)
        };
    }

    /**
     * Full metadata header for page 1.
     * textLeft/textRight bound the horizontal area that clears the code block.
     * The header zone text (headline + body) is placed in that area.
     */
    private void drawBallotHeader(PdfCanvas canvas,
                                   com.itextpdf.kernel.pdf.PdfDocument pdfDoc,
                                   BallotCombination combo,
                                   BallotDesignTemplate tmpl, float pw, float ph,
                                   float textLeft, float textRight,
                                   float zoneTop, float zoneBottom,
                                   int pageNum) throws Exception {
        // Single-line metadata above the zone — centred between page margins
        String party = combo.getParty() != null ? combo.getParty().getName() : "Nonpartisan";
        String hdr   = String.format("%s  |  %s  |  %s  |  %s  |  %s",
            combo.getRegion().getJurisdiction().getName(), combo.getRegion().getName(),
            party, combo.getBallotType().getName(), combo.getElection().getName());
        {
            PdfFont hdrFont = font(false, false, tmpl.isHeaderAltFont());
            float   hdrSz   = tmpl.getHeaderFontSize();
            float   hdrW    = hdrFont.getWidth(hdr, hdrSz);
            float   centreX = (pw - hdrW) / 2f;
            float   hdrY    = ph - tmpl.getMarginTopPt() + 4f;
            canvas.setFillColor(ColorConstants.BLACK)
                  .beginText().setFontAndSize(hdrFont, hdrSz)
                  .moveText(centreX, hdrY)
                  .showText(hdr).endText();
        }
        drawHeaderZone(pdfDoc, combo, tmpl,
            textLeft, zoneBottom, textRight - textLeft, zoneTop - zoneBottom,
            pageNum);
    }

    private void drawSlimHeader(PdfCanvas canvas,
                                 com.itextpdf.kernel.pdf.PdfDocument pdfDoc,
                                 BallotCombination combo,
                                 BallotDesignTemplate tmpl, float pw, float ph, int page,
                                 float textLeft, float textRight,
                                 float zoneTop, float zoneBottom) throws Exception {
        // Brief metadata line above zone — centred between page margins
        {
            PdfFont slimFont = font(false, false, tmpl.isHeaderAltFont());
            float   slimSz   = tmpl.getHeaderFontSize();
            String  slimTxt  = combo.getElection().getName() + "   —   Page " + page;
            float   slimW    = slimFont.getWidth(slimTxt, slimSz);
            float   slimX    = (pw - slimW) / 2f;
            canvas.setFillColor(ColorConstants.BLACK)
                  .beginText().setFontAndSize(slimFont, slimSz)
                  .moveText(slimX, ph - tmpl.getMarginTopPt() + 4f)
                  .showText(slimTxt).endText();
        }
        drawHeaderZone(pdfDoc, combo, tmpl,
            textLeft, zoneBottom, textRight - textLeft, zoneTop - zoneBottom,
            page);
    }

    // ══════════════════════════════════════════════════════════════════════
    // NON-RANKED VOTE TARGET
    // ══════════════════════════════════════════════════════════════════════

    // Oval/checkbox stroke style: dashed, thin, mid-gray so the indicator
    // region is visually clear but does not contribute significant dark pixels
    // to the vote-detection sampling.
    private static final float  INDICATOR_LINE_WIDTH = 0.5f; // thinnest visible in print
    private static final float  INDICATOR_DASH_ON    = 2f;    // 2pt dash
    private static final float  INDICATOR_DASH_OFF   = 3f;    // 3pt gap
    private static final float  INDICATOR_GRAY       = 0.45f; // just above 50% = mid-gray

    private void drawVoteTarget(PdfCanvas canvas,
                                 BallotDesignTemplate.VoteIndicatorStyle style,
                                 Contest contest, float x, float y,
                                 boolean indRight) {
        BallotDesignTemplate tmpl = _template.get();
        float lineW  = (tmpl != null) ? tmpl.getIndicatorLineWidthPt() : 0.5f;
        boolean dash = (tmpl == null) || tmpl.isIndicatorDashed();

        switch (style) {
            case OVAL, BOX -> {
                canvas.saveState();
                canvas.setStrokeColor(new com.itextpdf.kernel.colors.DeviceGray(INDICATOR_GRAY));
                canvas.setLineWidth(lineW);
                if (dash)
                    canvas.setLineDash(new float[]{INDICATOR_DASH_ON, INDICATOR_DASH_OFF}, 0f);
                if (style == BallotDesignTemplate.VoteIndicatorStyle.OVAL)
                    canvas.ellipse(x, y, x + OVAL_WIDTH, y + OVAL_HEIGHT).stroke();
                else
                    canvas.rectangle(x, y, OVAL_WIDTH, OVAL_HEIGHT).stroke();
                canvas.restoreState();
            }
            case ARROW -> {
                ArrowIndicatorDrawer.draw(canvas, x, y, OVAL_WIDTH, OVAL_HEIGHT);
            }
            case CONNECT_DOTS -> {
                // Connect-dots indicator: two half-circle/half-triangle markers.
                //
                // Both dots move INWARD by SHIFT (8pt) from their
                // respective edges, so:
                //   - The outer dot (near column border) moves away from the border
                //   - The inner dot (near candidate name) moves away from the name
                //   - The gap between the tips SHRINKS by 2×SHIFT = 16pt
                //
                // This gives the voter a shorter line to draw while keeping
                // both markers visually separated from the ballot borders.
                //
                // Each tip has a 1/8" (9pt) horizontal leader line extending
                // from the triangle vertex toward the center of the gap,
                // guiding the voter where to draw.
                //
                // Geometry constants:
                //   r     = CONNECT_DOTS_DOT_R = 2pt  (circle radius)
                //   WIDTH = CONNECT_DOTS_WIDTH  = 40pt (total allocated width)
                //   SHIFT = 8pt  (each dot moves inward this far from its edge)
                //   LINE  = 9pt  (leader line length from each tip toward center)
                //
                // Left  dot: circle centre at x + r + SHIFT
                //            tip at x + 2r + SHIFT (points right)
                // Right dot: circle centre at x + WIDTH - r - SHIFT
                //            tip at x + WIDTH - 2r - SHIFT (points left)
                // Gap between tips: WIDTH - 4r - 2×SHIFT

                final float r     = CONNECT_DOTS_DOT_R;
                final float SHIFT = 8f;     // dots shifted inward from each edge
                final float LINE  = 2f;     // 2pt leader line from each tip
                final float midY  = y + OVAL_HEIGHT / 2f;

                float lCx  = x + r + SHIFT;
                float rCx  = x + CONNECT_DOTS_WIDTH - r - SHIFT;
                float lTip = x + 2 * r + SHIFT;
                float rTip = x + CONNECT_DOTS_WIDTH - 2 * r - SHIFT;

                canvas.saveState();
                canvas.setFillColor(new com.itextpdf.kernel.colors.DeviceGray(INDICATOR_GRAY));
                canvas.setStrokeColor(new com.itextpdf.kernel.colors.DeviceGray(INDICATOR_GRAY));
                canvas.setLineWidth(1.0f);
                canvas.setLineDash(new float[0], 0f); // always solid

                // ── Left marker: semicircle left + triangle pointing right ──
                canvas.moveTo(lCx, midY + r);
                canvas.arc(lCx - r, midY - r, lCx + r, midY + r, 90, 180);
                canvas.lineTo(lTip, midY);
                canvas.closePath();
                canvas.fillStroke();

                // ── Right marker: semicircle right + triangle pointing left ──
                canvas.moveTo(rCx, midY + r);
                canvas.arc(rCx - r, midY - r, rCx + r, midY + r, 90, -180);
                canvas.lineTo(rTip, midY);
                canvas.closePath();
                canvas.fillStroke();

                // ── Leader lines: 1/8" from each tip toward center ──────────
                canvas.moveTo(lTip, midY).lineTo(lTip + LINE, midY).stroke();
                canvas.moveTo(rTip, midY).lineTo(rTip - LINE, midY).stroke();

                canvas.restoreState();
            }
            case NUMBER_FIELD -> {
                // Fallback: draw ordinary rank boxes using indicator settings
                float rcvW = (tmpl != null) ? tmpl.getRcvBoxLineWidthPt() : 0.5f;
                canvas.saveState();
                canvas.setStrokeColor(ColorConstants.BLACK).setLineWidth(rcvW);
                if (dash)
                    canvas.setLineDash(new float[]{INDICATOR_DASH_ON, INDICATOR_DASH_OFF}, 0f);
                int n = rankBoxCount(contest);
                float curX = x;
                for (int rank = n; rank >= 1; rank--) {
                    float bw = (rank == 1) ? FIRST_RANK_BOX_W : OTHER_RANK_BOX_W;
                    canvas.rectangle(curX, y, bw, OVAL_HEIGHT).stroke();
                    curX += bw + RANK_BOX_GAP;
                }
                canvas.restoreState();
            }
        }
    }

    private float indicatorWidth(BallotDesignTemplate.VoteIndicatorStyle style,
                                  Contest contest) {
        return switch (style) {
            case OVAL, BOX   -> OVAL_WIDTH;
            case ARROW            -> ARROW_WIDTH;
            case CONNECT_DOTS     -> CONNECT_DOTS_WIDTH;
            case NUMBER_FIELD     -> rankedChoiceIndicatorWidth(contest);
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // HEIGHT ESTIMATION
    // ══════════════════════════════════════════════════════════════════════

    private float estimateContestHeight(Contest contest, BallotDesignTemplate tmpl,
                                         float textWidth, float rowSpacing,
                                         float wrappedLineExtra,
                                         float aboveGroupLabel,
                                         float belowGroupLabel) throws Exception {
        float h = 0f;
        // Use same ascender approximation as drawing code.
        // getTypoAscender() not available here without a canvas, so use
        // the standard Helvetica ascender ratio of ~0.718 as the estimate.
        final float CBOX_TOP_PAD_EST    = 3f + tmpl.getContestTitleFontSize() * 0.718f;
        final float CBOX_BOTTOM_PAD_EST = 4f;
        h += CBOX_TOP_PAD_EST;
        if (contest.isPrintGroupingLabel() &&
                contest.getGroupingLabel() != null &&
                !contest.getGroupingLabel().isBlank()) {
            h += aboveGroupLabel;
            h += wrapText(contest.getGroupingLabel(), textWidth,
                          tmpl.getGroupingLabelFontSize()).size()
                 * (tmpl.getGroupingLabelFontSize() + LINE_GAP);
            h += belowGroupLabel;
        }
        h += wrapText(contest.getTitle(), textWidth, tmpl.getContestTitleFontSize()).size()
             * (tmpl.getContestTitleFontSize() + LINE_GAP) + LINE_GAP;
        // Use the actual instruction string so the estimate matches draw time exactly
        String instrForEst = contest.getVotingMethod() == Contest.VotingMethod.RANKED_CHOICE
            ? buildRankedChoiceInstruction(contest)
            : buildInstruction(contest);
        h += wrapText(instrForEst, textWidth, tmpl.getInstructionFontSize()).size()
             * (tmpl.getInstructionFontSize() + LINE_GAP) + LINE_GAP;
        if (contest.isPrintPreamble() &&
                contest.getPreamble() != null && !contest.getPreamble().isBlank())
            h += wrapText(contest.getPreamble(), textWidth, tmpl.getPreambleFontSize()).size()
                 * (tmpl.getPreambleFontSize() + LINE_GAP) + LINE_GAP;

        float indW  = effectiveIndicatorWidth(tmpl, contest);
        float nameW = textWidth - indW - 4f;
        boolean isRcv = contest.getVotingMethod() == Contest.VotingMethod.RANKED_CHOICE;
        // If rank numbers are shown, the first candidate row needs extra space above it
        if (isRcv && tmpl.isRcvShowRankNumbers()) {
            h += tmpl.getRcvRankNumberFontPt() + 4f;  // labelFontSize + RCV_LABEL_GAP
        }
        for (Candidate c : contest.getCandidates()) {
            int nl = wrapText(buildInlineName(c), nameW, tmpl.getCandidateNameFontSize()).size();
            h += Math.max(rowSpacing, nl * (tmpl.getCandidateNameFontSize() + LINE_GAP))
                 + (nl > 1 ? wrappedLineExtra : 0f);
            if (c.isPrintExplanatoryText() && c.getExplanatoryText() != null)
                h += wrapText(c.getExplanatoryText(), nameW, tmpl.getCandidateNoteFontSize()).size()
                     * (tmpl.getCandidateNoteFontSize() + LINE_GAP);
        }
        if (contest.isPrintPostamble() &&
                contest.getPostamble() != null && !contest.getPostamble().isBlank())
            h += wrapText(contest.getPostamble(), textWidth, tmpl.getPostambleFontSize()).size()
                 * (tmpl.getPostambleFontSize() + LINE_GAP) + LINE_GAP;
        h += CBOX_BOTTOM_PAD_EST;  // space below last element inside box
        h += 12f;
        return h;
    }

    // ══════════════════════════════════════════════════════════════════════
    // TEXT HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private List<String> wrapText(String text, float maxWidthPt, float fontSize) {
        if (text == null || text.isBlank()) return List.of("");
        int maxChars = Math.max(8, (int)(maxWidthPt / (fontSize * 0.52f)));
        List<String> lines = new ArrayList<>();
        for (String para : text.split("\\n")) {
            String[] words = para.split("\\s+");
            StringBuilder cur = new StringBuilder();
            for (String w : words) {
                if (w.isEmpty()) continue;
                if (cur.length() > 0 && cur.length() + 1 + w.length() > maxChars) {
                    lines.add(cur.toString()); cur = new StringBuilder(w);
                } else { if (cur.length() > 0) cur.append(' '); cur.append(w); }
            }
            if (cur.length() > 0) lines.add(cur.toString());
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    /**
     * The inline text for a non-write-in candidate: prefix + name + suffix + party.
     * For write-in candidates use buildWriteInLabel() to get just the label text;
     * the fill line is drawn separately below the candidate row.
     */
    private String buildInlineName(Candidate c) {
        StringBuilder sb = new StringBuilder();
        if (c.isPrintPrefixText() && c.getPrefixText() != null && !c.getPrefixText().isBlank())
            sb.append(c.getPrefixText().trim()).append(' ');
        sb.append(c.getName());
        if (c.isPrintSuffixText() && c.getSuffixText() != null && !c.getSuffixText().isBlank())
            sb.append(' ').append(c.getSuffixText().trim());
        if (c.getPartyAffiliation() != null && !c.getPartyAffiliation().isBlank())
            sb.append("  (").append(c.getPartyAffiliation()).append(')');
        return sb.toString();
    }

    /**
     * Label text for a write-in candidate slot.
     * Returns "Write-in: " followed by party if set; the actual fill line is
     * drawn as a horizontal rule below this text row.
     */
    private String buildWriteInLabel(Candidate c) {
        // languageCode is not available at this call site without refactor;
        // use the label from the contest candidate name as-is for now.
        // Full translation is applied when the Translator is passed through.
        String label = tx().writeIn();
        if (c.getPartyAffiliation() != null && !c.getPartyAffiliation().isBlank())
            label += "  (" + c.getPartyAffiliation() + ")";
        return label;
    }

    private String buildInstruction(Contest contest) {
        if (contest.getInstructions() != null && !contest.getInstructions().isBlank())
            return contest.getInstructions();
        return switch (contest.getVotingMethod()) {
            case RANKED_CHOICE -> buildRankedChoiceInstruction(contest);
            case APPROVAL      -> tx().voteForAllApprove();
            case MEASURE       -> tx().voteForMeasure();
            default            -> contest.getMaxChoices() == 1
                                  ? tx().voteForOne()
                                  : tx().voteForUpTo(contest.getMaxChoices());
        };
    }

    private String encodeBallotMetadata(BallotCombination combo, int page) {
        return String.join("|",
            String.valueOf(combo.getRegion().getJurisdiction().getId()),
            String.valueOf(combo.getRegion().getId()),
            combo.getParty() != null ? String.valueOf(combo.getParty().getId()) : "0",
            String.valueOf(combo.getBallotType().getId()),
            String.valueOf(combo.getElection().getId()),
            String.valueOf(page));
    }
}
