/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.BallotDesignTemplate;
import com.mjtrac.ballot.model.BallotDesignTemplate.FontFamily;
import com.mjtrac.ballot.model.BallotDesignTemplate.PaperSize;
import com.mjtrac.ballot.model.BallotDesignTemplate.VoteIndicatorStyle;
import com.mjtrac.ballot.model.Election;
import com.mjtrac.ballot.repository.BallotDesignTemplateRepository;
import com.mjtrac.ballot.repository.ElectionRepository;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Full field coverage for BallotDesignTemplate (~40 fields: per-element
 * bold/italic/font-size/alt-font, header HTML, indicator/barcode/RCV
 * layout) — every setting bBuilder's own form.html exposes, as plain form
 * controls (checkboxes for booleans, spinners for numbers). The one thing
 * deliberately NOT replicated is a WYSIWYG visual preview/canvas — this is
 * still a plain settings form, same spirit as bBuilder's own header-HTML
 * field (raw text, no live-rendered editor). headerHeadline/
 * headerHeadlineFontSize/headerBodyText/headerBodyFontSize are excluded:
 * the entity defines them but exposes no getters/setters at all (dead
 * fields, confirmed by grep — not a UI omission, there's nothing to bind).
 * barcodePosition is also excluded: bBuilder's own form hardcodes it to
 * TOP_RIGHT "for scanning reliability" rather than exposing it.
 */
@Component
class BallotDesignTemplatePanel extends SimpleCrudPanel<BallotDesignTemplate> {

    // ARROW and NUMBER_FIELD are obsolete as a *selectable template style* —
    // NUMBER_FIELD is applied automatically per-contest for ranked-choice
    // regardless of the template's own style (see BallotGenerationService),
    // and ARROW has no remaining new-template use case. Both keep working
    // for rendering and counting (BallotGenerationService, MarkerAnalysisService)
    // and any already-saved template using one — this only hides them from
    // the picker for new selections, mirroring bBuilder's own
    // BallotDesignTemplateController.SUPPORTED_INDICATOR_STYLES.
    private static final VoteIndicatorStyle[] SUPPORTED_INDICATOR_STYLES =
        java.util.Arrays.stream(VoteIndicatorStyle.values())
            .filter(s -> s != VoteIndicatorStyle.ARROW && s != VoteIndicatorStyle.NUMBER_FIELD)
            .toArray(VoteIndicatorStyle[]::new);

    private final BallotDesignTemplateRepository repo;
    private final ElectionRepository electionRepo;

    BallotDesignTemplatePanel(BallotDesignTemplateRepository repo, ElectionRepository electionRepo) {
        super("Ballot Design Templates", new String[]{"ID", "Election", "Paper Size", "Columns", "Indicator Style"},
            t -> new Object[]{t.getId(),
                t.getElection() != null ? t.getElection().getName() : "",
                t.getPaperSize(), t.getColumns(), t.getVoteIndicatorStyle()});
        this.repo = repo;
        this.electionRepo = electionRepo;
    }

    @Override List<BallotDesignTemplate> loadAll() { return repo.findAll(); }

    @Override void save(BallotDesignTemplate entity) { repo.save(entity); }

    @Override void delete(BallotDesignTemplate entity) { repo.deleteById(entity.getId()); }

    /** One row of the Font Sizes and Styles table: size + optional bold/italic + alt-font. */
    private record TypeRow(String label,
                            Function<BallotDesignTemplate, Float> getSize, BiConsumer<BallotDesignTemplate, Float> setSize,
                            Function<BallotDesignTemplate, Boolean> getBold, BiConsumer<BallotDesignTemplate, Boolean> setBold,
                            Function<BallotDesignTemplate, Boolean> getItalic, BiConsumer<BallotDesignTemplate, Boolean> setItalic,
                            Function<BallotDesignTemplate, Boolean> getAlt, BiConsumer<BallotDesignTemplate, Boolean> setAlt) {
    }

    private static List<TypeRow> typeRows() {
        List<TypeRow> rows = new ArrayList<>();
        rows.add(new TypeRow("Grouping Label",
            BallotDesignTemplate::getGroupingLabelFontSize, BallotDesignTemplate::setGroupingLabelFontSize,
            BallotDesignTemplate::isGroupingLabelBold, BallotDesignTemplate::setGroupingLabelBold,
            BallotDesignTemplate::isGroupingLabelItalic, BallotDesignTemplate::setGroupingLabelItalic,
            BallotDesignTemplate::isGroupingLabelAltFont, BallotDesignTemplate::setGroupingLabelAltFont));
        rows.add(new TypeRow("Contest Title",
            BallotDesignTemplate::getContestTitleFontSize, BallotDesignTemplate::setContestTitleFontSize,
            BallotDesignTemplate::isContestTitleBold, BallotDesignTemplate::setContestTitleBold,
            BallotDesignTemplate::isContestTitleItalic, BallotDesignTemplate::setContestTitleItalic,
            BallotDesignTemplate::isContestTitleAltFont, BallotDesignTemplate::setContestTitleAltFont));
        rows.add(new TypeRow("Instructions",
            BallotDesignTemplate::getInstructionFontSize, BallotDesignTemplate::setInstructionFontSize,
            BallotDesignTemplate::isInstructionBold, BallotDesignTemplate::setInstructionBold,
            BallotDesignTemplate::isInstructionItalic, BallotDesignTemplate::setInstructionItalic,
            BallotDesignTemplate::isInstructionAltFont, BallotDesignTemplate::setInstructionAltFont));
        rows.add(new TypeRow("Preamble",
            BallotDesignTemplate::getPreambleFontSize, BallotDesignTemplate::setPreambleFontSize,
            BallotDesignTemplate::isPreambleBold, BallotDesignTemplate::setPreambleBold,
            BallotDesignTemplate::isPreambleItalic, BallotDesignTemplate::setPreambleItalic,
            BallotDesignTemplate::isPreambleAltFont, BallotDesignTemplate::setPreambleAltFont));
        rows.add(new TypeRow("Candidate Name",
            BallotDesignTemplate::getCandidateNameFontSize, BallotDesignTemplate::setCandidateNameFontSize,
            BallotDesignTemplate::isCandidateNameBold, BallotDesignTemplate::setCandidateNameBold,
            BallotDesignTemplate::isCandidateNameItalic, BallotDesignTemplate::setCandidateNameItalic,
            BallotDesignTemplate::isCandidateNameAltFont, BallotDesignTemplate::setCandidateNameAltFont));
        rows.add(new TypeRow("Prefix/Suffix",
            BallotDesignTemplate::getPrefixSuffixFontSize, BallotDesignTemplate::setPrefixSuffixFontSize,
            BallotDesignTemplate::isPrefixSuffixBold, BallotDesignTemplate::setPrefixSuffixBold,
            BallotDesignTemplate::isPrefixSuffixItalic, BallotDesignTemplate::setPrefixSuffixItalic,
            BallotDesignTemplate::isPrefixSuffixAltFont, BallotDesignTemplate::setPrefixSuffixAltFont));
        rows.add(new TypeRow("Candidate Note",
            BallotDesignTemplate::getCandidateNoteFontSize, BallotDesignTemplate::setCandidateNoteFontSize,
            BallotDesignTemplate::isCandidateNoteBold, BallotDesignTemplate::setCandidateNoteBold,
            BallotDesignTemplate::isCandidateNoteItalic, BallotDesignTemplate::setCandidateNoteItalic,
            BallotDesignTemplate::isCandidateNoteAltFont, BallotDesignTemplate::setCandidateNoteAltFont));
        rows.add(new TypeRow("Postamble",
            BallotDesignTemplate::getPostambleFontSize, BallotDesignTemplate::setPostambleFontSize,
            BallotDesignTemplate::isPostambleBold, BallotDesignTemplate::setPostambleBold,
            BallotDesignTemplate::isPostambleItalic, BallotDesignTemplate::setPostambleItalic,
            BallotDesignTemplate::isPostambleAltFont, BallotDesignTemplate::setPostambleAltFont));
        // Header has no bold/italic accessors on the entity — size + alt-font only.
        rows.add(new TypeRow("Header",
            BallotDesignTemplate::getHeaderFontSize, BallotDesignTemplate::setHeaderFontSize,
            null, null,
            null, null,
            BallotDesignTemplate::isHeaderAltFont, BallotDesignTemplate::setHeaderAltFont));
        return rows;
    }

    private JComponent buildTypographyTable(BallotDesignTemplate t, List<Runnable> applyActions) {
        JPanel table = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 6, 2, 6);
        c.anchor = GridBagConstraints.WEST;

        String[] headers = {"", "Size (pt)", "Bold", "Italic", "Alt Font"};
        for (int col = 0; col < headers.length; col++) {
            c.gridx = col; c.gridy = 0;
            JLabel h = new JLabel(headers[col]);
            h.setFont(h.getFont().deriveFont(Font.BOLD));
            table.add(h, c);
        }

        int rowIdx = 1;
        for (TypeRow r : typeRows()) {
            c.gridx = 0; c.gridy = rowIdx;
            table.add(new JLabel(r.label()), c);

            String rowId = "typeRow" + r.label().replaceAll("[^A-Za-z0-9]", "");

            JSpinner size = new JSpinner(new SpinnerNumberModel(r.getSize().apply(t).floatValue(), 4f, 72f, 0.5f));
            size.setName(rowId + "Size");
            c.gridx = 1;
            table.add(size, c);
            applyActions.add(() -> r.setSize().accept(t, ((Number) size.getValue()).floatValue()));

            if (r.getBold() != null) {
                JCheckBox bold = new JCheckBox("", r.getBold().apply(t));
                bold.setName(rowId + "Bold");
                c.gridx = 2;
                table.add(bold, c);
                applyActions.add(() -> r.setBold().accept(t, bold.isSelected()));
            }
            if (r.getItalic() != null) {
                JCheckBox italic = new JCheckBox("", r.getItalic().apply(t));
                italic.setName(rowId + "Italic");
                c.gridx = 3;
                table.add(italic, c);
                applyActions.add(() -> r.setItalic().accept(t, italic.isSelected()));
            }
            JCheckBox alt = new JCheckBox("", r.getAlt().apply(t));
            alt.setName(rowId + "AltFont");
            c.gridx = 4;
            table.add(alt, c);
            applyActions.add(() -> r.setAlt().accept(t, alt.isSelected()));

            rowIdx++;
        }
        return table;
    }

    @Override JComponent buildForm(BallotDesignTemplate existing, Consumer<BallotDesignTemplate> onSave) {
        BallotDesignTemplate t = existing != null ? existing : new BallotDesignTemplate();
        List<Runnable> applyActions = new ArrayList<>();

        JComboBox<Election> electionCombo = new JComboBox<>();
        electionCombo.setName("electionCombo");
        for (Election el : electionRepo.findAll()) electionCombo.addItem(el);
        electionCombo.setRenderer((list, value, index, isSelected, hasFocus) -> {
            JLabel l = new JLabel(value != null ? value.getName() : "");
            l.setOpaque(true);
            l.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            return l;
        });
        if (t.getElection() != null) {
            for (int i = 0; i < electionCombo.getItemCount(); i++) {
                if (electionCombo.getItemAt(i).getId().equals(t.getElection().getId())) {
                    electionCombo.setSelectedIndex(i);
                    break;
                }
            }
        }

        JComboBox<PaperSize> paperCombo = new JComboBox<>(PaperSize.values());
        paperCombo.setSelectedItem(t.getPaperSize());
        JComboBox<VoteIndicatorStyle> indicatorCombo = new JComboBox<>(SUPPORTED_INDICATOR_STYLES);
        indicatorCombo.setSelectedItem(t.getVoteIndicatorStyle());
        JComboBox<FontFamily> primaryFontCombo = new JComboBox<>(FontFamily.values());
        primaryFontCombo.setSelectedItem(t.getFontFamilyPrimary());
        JComboBox<FontFamily> altFontCombo = new JComboBox<>(FontFamily.values());
        altFontCombo.setSelectedItem(t.getFontFamilyAlternate());
        JSpinner columnsSpinner = new JSpinner(new SpinnerNumberModel(t.getColumns(), 1, 6, 1));
        JSpinner marginTop = new JSpinner(new SpinnerNumberModel(t.getMarginTopPt(), 0f, 200f, 1f));
        JSpinner marginBottom = new JSpinner(new SpinnerNumberModel(t.getMarginBottomPt(), 0f, 200f, 1f));
        JSpinner marginLeft = new JSpinner(new SpinnerNumberModel(t.getMarginLeftPt(), 0f, 200f, 1f));
        JSpinner marginRight = new JSpinner(new SpinnerNumberModel(t.getMarginRightPt(), 0f, 200f, 1f));
        paperCombo.setName("paperCombo");
        indicatorCombo.setName("indicatorCombo");
        primaryFontCombo.setName("primaryFontCombo");
        altFontCombo.setName("altFontCombo");
        columnsSpinner.setName("columnsSpinner");
        marginTop.setName("marginTop");
        marginBottom.setName("marginBottom");
        marginLeft.setName("marginLeft");
        marginRight.setName("marginRight");

        JComponent typographyTable = buildTypographyTable(t, applyActions);

        // ── Indicator layout ────────────────────────────────────────────
        JSpinner indicatorLineWidth = new JSpinner(new SpinnerNumberModel(t.getIndicatorLineWidthPt(), 0.1f, 5f, 0.1f));
        JCheckBox indicatorDashed = new JCheckBox("", t.isIndicatorDashed());

        // ── Barcode (position is fixed TOP_RIGHT in bBuilder too — not exposed) ──
        JSpinner barcodeWidth = new JSpinner(new SpinnerNumberModel(t.getBarcodeWidthPt(), 0f, 300f, 1f));
        JSpinner barcodeHeight = new JSpinner(new SpinnerNumberModel(t.getBarcodeHeightPt(), 0f, 300f, 1f));
        JCheckBox multiSheet = new JCheckBox("", t.isMultiSheet());

        // ── Ranked-choice options ────────────────────────────────────────
        JCheckBox rcvIndicatorsRight = new JCheckBox("", t.isRcvIndicatorsRight());
        JCheckBox rcvShowRankNumbers = new JCheckBox("", t.isRcvShowRankNumbers());
        JSpinner rcvRankNumberFont = new JSpinner(new SpinnerNumberModel(t.getRcvRankNumberFontPt(), 4f, 24f, 0.5f));
        JSpinner rcvBoxLineWidth = new JSpinner(new SpinnerNumberModel(t.getRcvBoxLineWidthPt(), 0.1f, 5f, 0.1f));

        JTextArea headerHtmlArea = new JTextArea(t.getHeaderHtml(), 6, 30);
        indicatorLineWidth.setName("indicatorLineWidth");
        indicatorDashed.setName("indicatorDashed");
        barcodeWidth.setName("barcodeWidth");
        barcodeHeight.setName("barcodeHeight");
        multiSheet.setName("multiSheet");
        rcvIndicatorsRight.setName("rcvIndicatorsRight");
        rcvShowRankNumbers.setName("rcvShowRankNumbers");
        rcvRankNumberFont.setName("rcvRankNumberFont");
        rcvBoxLineWidth.setName("rcvBoxLineWidth");
        headerHtmlArea.setName("headerHtmlArea");

        JPanel grid = fieldGrid();
        int row = 0;
        addField(grid, row++, "Election:", electionCombo);
        addField(grid, row++, "Paper Size:", paperCombo);
        addField(grid, row++, "Columns:", columnsSpinner);
        addField(grid, row++, "Vote Indicator Style:", indicatorCombo);
        addField(grid, row++, "Primary Font:", primaryFontCombo);
        addField(grid, row++, "Alternate Font:", altFontCombo);
        addField(grid, row++, "Margin Top (pt):", marginTop);
        addField(grid, row++, "Margin Bottom (pt):", marginBottom);
        addField(grid, row++, "Margin Left (pt):", marginLeft);
        addField(grid, row++, "Margin Right (pt):", marginRight);
        addField(grid, row++, "Font Sizes and Styles:", typographyTable);
        addField(grid, row++, "Indicator Line Width (pt):", indicatorLineWidth);
        addField(grid, row++, "Indicator Dashed:", indicatorDashed);
        addField(grid, row++, "Barcode Width (pt, 0 = QR only):", barcodeWidth);
        addField(grid, row++, "Barcode/QR Height (pt):", barcodeHeight);
        addField(grid, row++, "Multi-Sheet Ballot:", multiSheet);
        addField(grid, row++, "RCV: Indicators on Right:", rcvIndicatorsRight);
        addField(grid, row++, "RCV: Show Rank Numbers:", rcvShowRankNumbers);
        addField(grid, row++, "RCV Rank Number Font (pt):", rcvRankNumberFont);
        addField(grid, row++, "RCV Box Line Width (pt):", rcvBoxLineWidth);

        JButton previewHeaderButton = new JButton("Preview in Browser");
        previewHeaderButton.setName("previewHeaderButton");
        previewHeaderButton.addActionListener(e -> previewHeaderInBrowser(
            headerHtmlArea.getText(),
            (Election) electionCombo.getSelectedItem(),
            (VoteIndicatorStyle) indicatorCombo.getSelectedItem()));
        JPanel headerFieldPanel = new JPanel(new BorderLayout(4, 4));
        headerFieldPanel.add(new JScrollPane(headerHtmlArea), BorderLayout.CENTER);
        JPanel headerButtonRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        headerButtonRow.add(previewHeaderButton);
        headerFieldPanel.add(headerButtonRow, BorderLayout.SOUTH);
        addField(grid, row++, "Header HTML:", headerFieldPanel);

        JScrollPane scroller = new JScrollPane(grid);
        scroller.setPreferredSize(new Dimension(680, 560));

        return formShell(existing == null ? "New Ballot Design Template" : "Edit Ballot Design Template", wrapScrollable(scroller),
            () -> {
                t.setElection((Election) electionCombo.getSelectedItem());
                t.setPaperSize((PaperSize) paperCombo.getSelectedItem());
                t.setColumns((Integer) columnsSpinner.getValue());
                t.setVoteIndicatorStyle((VoteIndicatorStyle) indicatorCombo.getSelectedItem());
                t.setFontFamilyPrimary((FontFamily) primaryFontCombo.getSelectedItem());
                t.setFontFamilyAlternate((FontFamily) altFontCombo.getSelectedItem());
                t.setMarginTopPt(((Number) marginTop.getValue()).floatValue());
                t.setMarginBottomPt(((Number) marginBottom.getValue()).floatValue());
                t.setMarginLeftPt(((Number) marginLeft.getValue()).floatValue());
                t.setMarginRightPt(((Number) marginRight.getValue()).floatValue());
                for (Runnable apply : applyActions) apply.run();
                t.setIndicatorLineWidthPt(((Number) indicatorLineWidth.getValue()).floatValue());
                t.setIndicatorDashed(indicatorDashed.isSelected());
                t.setBarcodeWidthPt(((Number) barcodeWidth.getValue()).floatValue());
                t.setBarcodeHeightPt(((Number) barcodeHeight.getValue()).floatValue());
                t.setMultiSheet(multiSheet.isSelected());
                t.setRcvIndicatorsRight(rcvIndicatorsRight.isSelected());
                t.setRcvShowRankNumbers(rcvShowRankNumbers.isSelected());
                t.setRcvRankNumberFontPt(((Number) rcvRankNumberFont.getValue()).floatValue());
                t.setRcvBoxLineWidthPt(((Number) rcvBoxLineWidth.getValue()).floatValue());
                t.setHeaderHtml(headerHtmlArea.getText());
                onSave.accept(t);
            },
            () -> SwingUtilities.getWindowAncestor(grid).dispose());
    }

    /**
     * Renders the header field's actual HTML/CSS in the system's real
     * browser (full CSS support) rather than an embedded Swing component —
     * JEditorPane's HTML renderer only supports HTML 3.2 and minimal CSS,
     * which would show a misleadingly plain preview of content that uses
     * anything beyond basic tags. Substitutes the same {token} placeholders
     * and Quill-editor class-to-style conversion BallotGenerationService
     * applies at real render time (see its computeHeaderZoneHeight()),
     * using the actually-selected election/jurisdiction/indicator where
     * available so the preview reflects this template, not a generic one.
     */
    private void previewHeaderInBrowser(String headerHtml, Election election, VoteIndicatorStyle indicatorStyle) {
        try {
            String electionName = election != null ? election.getName() : "Election Name";
            String jurisdictionName = election != null && election.getJurisdiction() != null
                ? election.getJurisdiction().getName() : "Jurisdiction";
            String indicatorName = indicatorStyle != null
                ? indicatorStyle.name().toLowerCase().replace('_', ' ') : "oval";

            String body = (headerHtml == null ? "" : headerHtml)
                .replace("{electionName}", electionName)
                .replace("{jurisdictionName}", jurisdictionName)
                .replace("{regionName}", "Region")
                .replace("{partyName}", "Party")
                .replace("{ballotTypeName}", "Ballot Type")
                .replace("{indicatorName}", indicatorName)
                .replace("{pageNum}", "1")
                .replace("class=\"ql-align-center\"", "style=\"text-align:center\"")
                .replace("class=\"ql-align-right\"", "style=\"text-align:right\"")
                .replace("class=\"ql-align-justify\"", "style=\"text-align:justify\"");

            String doc = "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>Header preview</title></head>"
                + "<body>" + body + "</body></html>";

            Path file = Files.createTempFile("pbss-header-preview-", ".html");
            Files.writeString(file, doc);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(file.toUri());
            } else {
                JOptionPane.showMessageDialog(this, "Wrote preview to " + file + " — open it in a browser manually.");
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not preview header: " + ex.getMessage(),
                "Preview Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** formShell's Save/Cancel buttons need to stay outside the scroll area. */
    private static JPanel wrapScrollable(JScrollPane scroller) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(scroller, BorderLayout.CENTER);
        return p;
    }
}
