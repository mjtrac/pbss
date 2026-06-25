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
package gov.election.ballot.service;

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
import gov.election.ballot.model.*;
import gov.election.ballot.util.BallotDimensions;
import gov.election.ballot.util.MeasurementUtil;
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

    @Value("${ballot.export.dir:${user.home}/bBuilder_ballots}")
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
    private static final float HEADER_ZONE_PT  = 90f;
    private static final float CBOX_INDENT     = 4f;
    private static final float LINE_GAP        = 2f;

    /** Max rank boxes drawn per candidate in ranked-choice contests. */
    private static final int   MAX_RANK_BOXES       = 5;
    /** Width of the first (highest priority) rank box — wider to indicate primacy. */
    private static final float FIRST_RANK_BOX_W     = 22f;
    /** Width of subsequent (lower priority) rank boxes. */
    private static final float OTHER_RANK_BOX_W     = 12f;
    /** Gap between rank boxes. */
    private static final float RANK_BOX_GAP         = 3f;

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
        this._currentTranslator = translationService.forLanguage(
            languageCode != null ? languageCode : "en");
        try {
            return generateBallotCore(combination, template, user, copies);
        } finally {
            this._currentTranslator = null;
        }
    }

    /** Active translator for the current generateBallot call (thread-safe via Spring prototype or single-thread use). */
    private BallotTranslationService.Translator _currentTranslator;

    private BallotTranslationService.Translator tx() {
        return _currentTranslator != null ? _currentTranslator
               : translationService.forLanguage("en");
    }

    private byte[] generateBallotCore(BallotCombination combination,
                                  BallotDesignTemplate template,
                                  User user,
                                  int copies) throws Exception {

        if (combination == null || template == null || user == null)
            throw new IllegalArgumentException(
                "combination, template, and user must not be null");

        List<Contest> contests = assignmentService.resolveContestsForPrecinct(
            combination.getRegion().getId(),
            combination.getElection().getId());

        if (contests.isEmpty())
            log.warn("No contests for combination id=" + combination.getId());

        // Scaled whitespace
        float aboveGroupLabel  = template.getGroupingLabelFontSize() * 0.8f;
        float belowGroupLabel  = template.getGroupingLabelFontSize() * 0.4f;
        float interContestGap  = template.getContestTitleFontSize()  * 0.9f;
        float wrappedLineExtra = template.getCandidateNameFontSize()  * 0.25f;
        float rowSpacing       = template.getCandidateNameFontSize()  + OVAL_HEIGHT + 2f;

        float pw = template.getPaperSize().widthPt;
        float ph = template.getPaperSize().heightPt;

        float bbLeft   = template.getMarginLeftPt() + 5f;
        float bbRight  = pw - template.getMarginRightPt() - 5f;
        float bbBottom = template.getMarginBottomPt() + 5f;

        // Worst-case content height (codes at top, metadata line above):
        // used to pre-validate that no contest is too tall for any page.
        float worstCaseBbTop = ph - template.getMarginTopPt() - 14f - HEADER_ZONE_PT
                               - (MARK_GAP + MARK_H + MARK_GAP);  // clearance for marks

        float colWidth  = (bbRight - bbLeft - 10f) / template.getColumns();
        float textWidth = colWidth - CBOX_INDENT * 2f - 8f;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Per-page layout: map from pageNumber -> list of ContestPositions
        Map<Integer, List<BallotDimensions.ContestPosition>> pageContests = new java.util.LinkedHashMap<>();
        Map<Integer, double[]> pageContentArea  = new java.util.LinkedHashMap<>(); // [offsetL, offsetT, w, h]
        Map<Integer, double[][]> pageCornerMarks = new java.util.LinkedHashMap<>();  // [TL,TR,BR,BL] in inches
        Map<Integer, double[]>   pageBarcodeCentre = new java.util.LinkedHashMap<>(); // [x, y] inches from image top-left

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
            canvas = openPage(pdf, pw, ph, pageNum, combination, template,
                              bbLeft, bbRight, bbBottom, state, bcCentre);
            pageBarcodeCentre.put(pageNum, bcCentre);
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
                        currentY  = bbTop - template.getContestTitleFontSize() - 5f;
                    } else {
                        pageNum++;
                        col = 0;
                        double[] bcCentre2 = new double[2];
                        canvas = openPage(pdf, pw, ph, pageNum, combination, template,
                                          bbLeft, bbRight, bbBottom, state, bcCentre2);
                        pageBarcodeCentre.put(pageNum, bcCentre2);
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
                             template.isGroupingLabelItalic()),
                        template.getGroupingLabelFontSize(),
                        currentX + CBOX_INDENT, currentY);
                    currentY -= belowGroupLabel;
                    contestStartY = currentY;
                    drawnBottom   = currentY;
                }

                // ── Contest title ─────────────────────────────────────────
                canvas.setFillColor(ColorConstants.BLACK)
                      .setStrokeColor(ColorConstants.BLACK);
                currentY = drawWrappedText(canvas,
                    contest.getTitle(), textWidth,
                    font(template.isContestTitleBold(), template.isContestTitleItalic()),
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
                    font(template.isInstructionBold(), template.isInstructionItalic()),
                    template.getInstructionFontSize(),
                    currentX + CBOX_INDENT, currentY - LINE_GAP);
                drawnBottom = currentY;

                // ── Preamble ──────────────────────────────────────────────
                if (contest.isPrintPreamble() &&
                        contest.getPreamble() != null &&
                        !contest.getPreamble().isBlank()) {
                    currentY = drawWrappedText(canvas,
                        contest.getPreamble(), textWidth,
                        font(template.isPreambleBold(), template.isPreambleItalic()),
                        template.getPreambleFontSize(),
                        currentX + CBOX_INDENT, currentY - LINE_GAP);
                    drawnBottom = currentY;
                }

                // ── Candidates ────────────────────────────────────────────
                float indW  = effectiveIndicatorWidth(template, contest);
                float nameW = textWidth - indW - 4f;

                for (Candidate candidate : contest.getCandidates()) {
                    float targetX = currentX + CBOX_INDENT + 2f;
                    float targetY = currentY - OVAL_HEIGHT;

                    canvas.setFillColor(ColorConstants.BLACK)
                          .setStrokeColor(ColorConstants.BLACK);
                    float drawnIndW = drawEffectiveVoteTarget(canvas, template, contest,
                                                               targetX, targetY);

                    float nameX = targetX + drawnIndW + 4f;
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

                    // For single-line candidates with no note: vertically centre
                    // the text baseline within the oval height.
                    // Oval occupies targetY to targetY+OVAL_HEIGHT.
                    // Font baseline visually sits ~0.25*fontSize below cap-top.
                    // Centre: oval_midY + fontSize*0.25
                    float nameY;
                    if (singleNoNote) {
                        float ovalMidY = targetY + OVAL_HEIGHT / 2f;
                        // Baseline sits ~0.7 of cap-height above the visual glyph centre.
                        // Subtract 0.15*fontSize so the visual centre of the text
                        // aligns with the oval centre rather than riding above it.
                        nameY = ovalMidY - fontSize * 0.15f;
                    } else {
                        nameY = currentY - 2f;
                    }

                    PdfFont candFont = font(template.isCandidateNameBold(),
                                           template.isCandidateNameItalic());
                    for (int li = 0; li < nameLines.size(); li++) {
                        float lineY = nameY - li * (fontSize + LINE_GAP);
                        canvas.setFillColor(ColorConstants.BLACK)
                              .beginText()
                              .setFontAndSize(candFont, fontSize)
                              .moveText(nameX, lineY)
                              .showText(nameLines.get(li))
                              .endText();
                        drawnBottom = Math.min(drawnBottom, lineY);
                    }

                    float nameTotalH = nameLines.size() * (fontSize + LINE_GAP);
                    float extraGap = nameLines.size() > 1 ? wrappedLineExtra : 0f;
                    currentY -= Math.max(rowSpacing, nameTotalH) + extraGap;

                    // ── Explanatory note (just below name, before next row) ───
                    if (candidate.isPrintExplanatoryText() &&
                            candidate.getExplanatoryText() != null &&
                            !candidate.getExplanatoryText().isBlank()) {
                        // Start note just below the bottom name line
                        float noteStartY = nameY - nameTotalH + LINE_GAP * 0.5f;
                        currentY = drawWrappedText(canvas,
                            candidate.getExplanatoryText(), nameW,
                            font(template.isCandidateNoteBold(),
                                 template.isCandidateNoteItalic()),
                            template.getCandidateNoteFontSize(),
                            nameX, noteStartY);
                        // Add extra gap after a note so the next candidate stands apart
                        currentY -= template.getCandidateNoteFontSize() * 0.5f;
                        drawnBottom = currentY;
                    }

                    // ── Write-in line (below the candidate name row) ──────────
                    if (candidate.isWriteIn()) {
                        float lineY = currentY + fontSize * 0.5f;
                        float lineLeft  = nameX;
                        float lineRight = nameX + nameW - 10f;
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
                        // Record each rank box individually.
                        // Rank boxes are drawn left-to-right: rank N (narrow) down to rank 1 (wide).
                        // offsetTop is from the content-box upper-left to the TOP of the box:
                        //   pageBbTop - (targetY + OVAL_HEIGHT)
                        // because targetY is the BOTTOM of the box in PDF coords (Y increases upward).
                        // Record each rank box with a 3pt inset on each side
                        // (matching the oval/checkbox inset) so sampling is well inside
                        // the stroke border of the rank box.
                        final float RANK_INSET_X = 2f;  // 1pt less than y-inset to align left edge
                        final float RANK_INSET_Y = 3f;
                        int n = rankBoxCount(contest);
                        float rankX = targetX;
                        for (int rank = n; rank >= 1; rank--) {
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
                            rankX += bw + RANK_BOX_GAP;
                        }
                    } else {
                        // offsetTop = distance from content-box upper-left to TOP of indicator.
                        // targetY is the bottom edge in PDF coords; top = targetY + OVAL_HEIGHT.
                        // Record the indicator position with a 3pt inset on each side.
                        // The 1pt stroke of an oval contributes ~12% darkness by itself
                        // (stroke pixels ≈ circumference * strokeWidth / area).
                        // A 3pt inset clears the 0.5pt inner stroke edge with 2.5pt margin,
                        // ensuring sampling is well inside the oval interior only.
                        final float INDICATOR_INSET_START = 2f;  // left/top: 2pt inset
                        final float INDICATOR_INSET_END   = 3f;  // right/bottom: 3pt inset
                        double indOffLeft = MeasurementUtil.ptToInches(targetX     + INDICATOR_INSET_START);
                        double indOffTop  = MeasurementUtil.ptToInches(ph - (targetY + OVAL_HEIGHT - INDICATOR_INSET_START));
                        double indW_in    = MeasurementUtil.ptToInches(drawnIndW   - INDICATOR_INSET_START - INDICATOR_INSET_END);
                        double indH_in    = MeasurementUtil.ptToInches(OVAL_HEIGHT - INDICATOR_INSET_START - INDICATOR_INSET_END);
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
                        font(template.isPostambleBold(), template.isPostambleItalic()),
                        template.getPostambleFontSize(),
                        currentX + CBOX_INDENT, currentY - LINE_GAP);
                    drawnBottom = currentY;
                }

                // ── Contest bounding box ──────────────────────────────────
                float cboxL = currentX;
                float cboxR = currentX + colWidth - 8f;
                float cboxT = contestStartY + template.getContestTitleFontSize();
                float cboxB = drawnBottom - LINE_GAP;
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
            pages.add(new BallotDimensions.PageLayout(pn,
                ca.length >= 4 ? ca[0] : 0, ca.length >= 4 ? ca[1] : 0,
                ca.length >= 4 ? ca[2] : ca[0], ca.length >= 4 ? ca[3] : ca[1],
                java.util.Collections.unmodifiableList(entry.getValue()), cm,
                pageBarcodeCentre.getOrDefault(pn, new double[]{0, 0})));
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
        return FIRST_RANK_BOX_W + (n - 1) * (OTHER_RANK_BOX_W + RANK_BOX_GAP);
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
            return drawRankedChoiceBoxes(canvas, contest, x, y);
        }
        drawVoteTarget(canvas, tmpl.getVoteIndicatorStyle(), contest, x, y);
        return indicatorWidth(tmpl.getVoteIndicatorStyle(), contest);
    }

    /**
     * Draws ranked-choice rank boxes for one candidate row.
     * The widest box (rank 1 = first choice) is drawn RIGHTMOST, closest to
     * the candidate name.  Lower-priority ranks are drawn to the left, narrower.
     *
     * Layout (left to right, widths not to scale):
     *   [ 5 ] [ 4 ] [ 3 ] [ 2 ] [  1  ]   candidate name →
     *   nar    nar   nar   nar   wide
     *
     * Returns the total width drawn.
     */
    private float drawRankedChoiceBoxes(PdfCanvas canvas, Contest contest,
                                         float x, float y) {
        // Boxes drawn left-to-right: narrow (lower ranks) first, wide (rank 1) last.
        // The widest box is rightmost, closest to the candidate name.
        // Layout:  [ 5 ][ 4 ][ 3 ][ 2 ][  1  ] CandidateName
        //           nar  nar  nar  nar   wide
        canvas.setStrokeColor(ColorConstants.BLACK).setLineWidth(1f);
        int n = rankBoxCount(contest);
        float curX = x;
        // Draw boxes n down to 2 (narrow), then box 1 (wide)
        for (int rank = n; rank >= 1; rank--) {
            float bw = (rank == 1) ? FIRST_RANK_BOX_W : OTHER_RANK_BOX_W;
            canvas.rectangle(curX, y, bw, OVAL_HEIGHT).stroke();
            curX += bw + RANK_BOX_GAP;
        }
        return curX - x - RANK_BOX_GAP; // total width excluding trailing gap
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

    private PdfCanvas openPage(PdfDocument pdf, float pw, float ph, int pageNum,
                                BallotCombination combo, BallotDesignTemplate tmpl,
                                float bbLeft, float bbRight, float bbBottom,
                                float[] state) throws Exception {
        return openPage(pdf, pw, ph, pageNum, combo, tmpl, bbLeft, bbRight,
                        bbBottom, state, null);
    }

    private PdfCanvas openPage(PdfDocument pdf, float pw, float ph, int pageNum,
                                BallotCombination combo, BallotDesignTemplate tmpl,
                                float bbLeft, float bbRight, float bbBottom,
                                float[] state, double[] barcodeCentreOut) throws Exception {

        PdfCanvas canvas = new PdfCanvas(pdf.addNewPage(new PageSize(pw, ph)));
        // ── Barcode / code placement ─────────────────────────────────────
        // Every side gets its own uniquely-encoded QR + Code128 pair (with pageNum).
        // Codes are placed side-by-side (QR left, Code128 right) in the zone
        // indicated by barcodePosition.  The content bounding box is shrunk on the
        // appropriate side so it always clears the codes.
        String bcPos  = tmpl.getBarcodePosition() != null
                        ? tmpl.getBarcodePosition() : "TOP_LEFT";
        boolean codeAtTop    = !bcPos.startsWith("BOTTOM");
        boolean codeAtRight  = bcPos.endsWith("RIGHT");

        // Code zone height (QR height; Code128 is beside it, same height)
        int qrSz = (int) Math.min(tmpl.getBarcodeHeightPt(), HEADER_ZONE_PT - 20f);
        float zoneH = HEADER_ZONE_PT;  // full header zone for text; QR sits within it

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

        // Metadata line (page 1: full detail; other pages: slim)
        if (pageNum == 1) {
            drawBallotHeader(canvas, combo, tmpl, ph, textLeft, textRight,
                             codeZoneTop, codeZoneBottom);
        } else {
            drawSlimHeader(canvas, combo, tmpl, ph, pageNum, textLeft, textRight,
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
        state[2] = bbTop - tmpl.getContestTitleFontSize() - 5f;
        state[3] = bbBottom2;   // actual bottom (may be above margin when codes are at bottom)
        return canvas;
    }

    // ══════════════════════════════════════════════════════════════════════
    // TEXT PRIMITIVE
    // ══════════════════════════════════════════════════════════════════════

    private float drawWrappedText(PdfCanvas canvas, String text, float maxWidthPt,
                                   PdfFont f, float fontSize,
                                   float x, float startY) throws Exception {
        if (text == null || text.isBlank()) return startY;
        canvas.setFillColor(ColorConstants.BLACK);
        float y = startY;
        for (String line : wrapText(text, maxWidthPt, fontSize)) {
            canvas.beginText().setFontAndSize(f, fontSize)
                  .moveText(x, y).showText(line).endText();
            y -= (fontSize + LINE_GAP);
        }
        return y;
    }

    // ══════════════════════════════════════════════════════════════════════
    // FONT
    // ══════════════════════════════════════════════════════════════════════

    private PdfFont font(boolean bold, boolean italic) throws Exception {
        return PdfFontFactory.createFont(BallotDesignTemplate.fontName(bold, italic));
    }

    // ══════════════════════════════════════════════════════════════════════
    // HEADER ZONE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Draws QR code and Code128 barcode side-by-side in the code zone.
     * QR is always on the left of the pair; Code128 is to its right.
     * The pair is placed flush against the left or right page margin
     * depending on codeAtRight.
     *
     * Returns float[4] = {pairLeft, pairRight, zoneTop, zoneBottom}
     * so the caller can position text on the opposite side.
     */
    private float[] drawBarcodesSideBySide(PdfCanvas canvas, String data,
                                            BallotDesignTemplate tmpl,
                                            float pw, float ph,
                                            boolean codeAtRight,
                                            float zoneTop, int qrSz) throws Exception {
        int bcWidth  = (int) tmpl.getBarcodeWidthPt();
        int bcHeight = qrSz;   // make barcode same height as QR for clean alignment
        float gap    = 6f;     // gap between QR and Code128
        float pairW  = qrSz + gap + bcWidth;

        float pairLeft;
        if (codeAtRight) {
            pairLeft = pw - tmpl.getMarginRightPt() - 5f - pairW;
        } else {
            pairLeft = tmpl.getMarginLeftPt() + 5f;
        }

        float codeY  = zoneTop - 4f - qrSz;  // top-aligned within zone

        MultiFormatWriter writer = new MultiFormatWriter();
        // QR code (left of pair)
        BitMatrix qrMatrix = writer.encode(data, BarcodeFormat.QR_CODE, qrSz, qrSz);
        drawBitMatrix(canvas, qrMatrix, pairLeft, codeY, qrSz, qrSz);
        // Code128 (right of pair, same height as QR)
        BitMatrix bcMatrix = writer.encode(data, BarcodeFormat.CODE_128, bcWidth, bcHeight);
        drawBitMatrix(canvas, bcMatrix, pairLeft + qrSz + gap, codeY, bcWidth, bcHeight);

        return new float[]{ pairLeft, pairLeft + pairW, zoneTop, codeY };
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

    private void drawHeaderZoneText(PdfCanvas canvas, BallotCombination combo,
                                     BallotDesignTemplate tmpl,
                                     float left, float right,
                                     float zoneTop, float zoneBottom) throws Exception {
        float aw = right - left, y = zoneTop - 4f;
        String indName = switch (tmpl.getVoteIndicatorStyle()) {
            case OVAL -> "oval"; case CHECKBOX -> "box";
            case ARROW -> "arrow"; case NUMBER_FIELD -> "number box";
        };
        Function<String, String> tok = s ->
            s.replace("{electionName}",     combo.getElection().getName())
             .replace("{jurisdictionName}", combo.getRegion().getJurisdiction().getName())
             .replace("{indicatorName}",    indName);

        String headline = tmpl.getHeaderHeadline();
        if (headline != null && !headline.isBlank()) {
            y -= tmpl.getHeaderHeadlineFontSize();
            y = drawWrappedText(canvas, tok.apply(headline.trim()),
                aw, font(true, false), tmpl.getHeaderHeadlineFontSize(), left, y);
            y -= 3f;
        }
        String body = tmpl.getHeaderBodyText();
        if (body != null && !body.isBlank()) {
            PdfFont bf = font(false, false);
            float bSz  = tmpl.getHeaderBodyFontSize();
            for (String para : tok.apply(body).split("\\\\n|\\n")) {
                if (y < zoneBottom + bSz) break;
                if (para.isBlank()) { y -= bSz; continue; }
                y = drawWrappedText(canvas, para, aw, bf, bSz, left, y);
            }
        }
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
    private void drawBallotHeader(PdfCanvas canvas, BallotCombination combo,
                                   BallotDesignTemplate tmpl, float ph,
                                   float textLeft, float textRight,
                                   float zoneTop, float zoneBottom) throws Exception {
        // Single-line metadata above the zone (always full-width, above the codes)
        String party = combo.getParty() != null ? combo.getParty().getName() : "Nonpartisan";
        String hdr   = String.format("%s  |  %s  |  %s  |  %s  |  %s",
            combo.getRegion().getJurisdiction().getName(), combo.getRegion().getName(),
            party, combo.getBallotType().getName(), combo.getElection().getName());
        canvas.setFillColor(ColorConstants.BLACK)
              .beginText().setFontAndSize(font(false, false), tmpl.getHeaderFontSize())
              .moveText(tmpl.getMarginLeftPt() + 18f, ph - tmpl.getMarginTopPt() + 4f)
              .showText(hdr).endText();
        // Headline + body instruction text in the zone, on the side away from the codes
        drawHeaderZoneText(canvas, combo, tmpl, textLeft, textRight, zoneTop, zoneBottom);
    }

    /**
     * Slim header for page 2+: election name and page number only, on the side away from codes.
     */
    private void drawSlimHeader(PdfCanvas canvas, BallotCombination combo,
                                 BallotDesignTemplate tmpl, float ph, int page,
                                 float textLeft, float textRight,
                                 float zoneTop, float zoneBottom) throws Exception {
        // Brief metadata line above zone
        canvas.setFillColor(ColorConstants.BLACK)
              .beginText().setFontAndSize(font(false, false), tmpl.getHeaderFontSize())
              .moveText(tmpl.getMarginLeftPt() + 18f, ph - tmpl.getMarginTopPt() + 4f)
              .showText(combo.getElection().getName() + "   —   Page " + page)
              .endText();
        // Also draw a scaled-down instruction zone on page 2+ (page number encoded in QR)
        drawHeaderZoneText(canvas, combo, tmpl, textLeft, textRight, zoneTop, zoneBottom);
    }

    // ══════════════════════════════════════════════════════════════════════
    // NON-RANKED VOTE TARGET
    // ══════════════════════════════════════════════════════════════════════

    // Oval/checkbox stroke style: dashed, thin, mid-gray so the indicator
    // region is visually clear but does not contribute significant dark pixels
    // to the vote-detection sampling.
    private static final float  INDICATOR_LINE_WIDTH = 0.25f; // thinnest visible in print
    private static final float  INDICATOR_DASH_ON    = 2f;    // 2pt dash
    private static final float  INDICATOR_DASH_OFF   = 3f;    // 3pt gap
    private static final float  INDICATOR_GRAY       = 0.45f; // just above 50% = mid-gray

    private void drawVoteTarget(PdfCanvas canvas,
                                 BallotDesignTemplate.VoteIndicatorStyle style,
                                 Contest contest, float x, float y) {
        switch (style) {
            case OVAL, CHECKBOX -> {
                canvas.saveState();
                canvas.setStrokeColor(new com.itextpdf.kernel.colors.DeviceGray(INDICATOR_GRAY));
                canvas.setLineWidth(INDICATOR_LINE_WIDTH);
                canvas.setLineDash(new float[]{INDICATOR_DASH_ON, INDICATOR_DASH_OFF}, 0f);
                if (style == BallotDesignTemplate.VoteIndicatorStyle.OVAL)
                    canvas.ellipse(x, y, x + OVAL_WIDTH, y + OVAL_HEIGHT).stroke();
                else
                    canvas.rectangle(x, y, OVAL_WIDTH, OVAL_HEIGHT).stroke();
                canvas.restoreState();
            }
            case ARROW -> {
                // Draw two inward-pointing triangles using the oval bounding box dimensions
                ArrowIndicatorDrawer.draw(canvas, x, y, OVAL_WIDTH, OVAL_HEIGHT);
            }
            case NUMBER_FIELD -> {
                // Fallback: draw ordinary rank boxes using contest's configured count
                drawRankedChoiceBoxes(canvas, contest, x, y);
            }
        }
    }

    private float indicatorWidth(BallotDesignTemplate.VoteIndicatorStyle style,
                                  Contest contest) {
        return switch (style) {
            case OVAL, CHECKBOX -> OVAL_WIDTH;
            case ARROW          -> ARROW_WIDTH;
            case NUMBER_FIELD   -> rankedChoiceIndicatorWidth(contest);
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
