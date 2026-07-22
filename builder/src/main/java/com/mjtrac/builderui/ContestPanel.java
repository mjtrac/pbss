/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Contest;
import com.mjtrac.ballot.model.Contest.VotingMethod;
import com.mjtrac.ballot.model.Election;
import com.mjtrac.ballot.repository.BallotLanguageRepository;
import com.mjtrac.ballot.repository.CandidateTranslationRepository;
import com.mjtrac.ballot.repository.ContestRepository;
import com.mjtrac.ballot.repository.ContestTranslationRepository;
import com.mjtrac.ballot.repository.ElectionRepository;
import com.mjtrac.ballot.repository.RegionRepository;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * The largest screen — Contest fields only. Candidates and region
 * assignment are separate screens (ContestCandidatesDialog,
 * ContestRegionsDialog): every Contest save (new or edit) opens Candidates
 * first, then Regions, once the dialog closes; both stay reachable
 * afterward via the buttons below, including for an already-saved contest
 * reopened by double-clicking its row. Matches bBuilder's own web behavior
 * of only showing region-assignment/candidate-management once a contest
 * has an id, just as separate dialogs instead of page sections.
 */
@Component
class ContestPanel extends SimpleCrudPanel<Contest> {

    private final ContestRepository repo;
    private final ElectionRepository electionRepo;
    private final RegionRepository regionRepo;
    private final BallotLanguageRepository languageRepo;
    private final ContestTranslationRepository contestTranslationRepo;
    private final CandidateTranslationRepository candidateTranslationRepo;

    ContestPanel(ContestRepository repo, ElectionRepository electionRepo, RegionRepository regionRepo,
                 BallotLanguageRepository languageRepo, ContestTranslationRepository contestTranslationRepo,
                 CandidateTranslationRepository candidateTranslationRepo) {
        super("Contests", new String[]{"ID", "Election", "Title", "Voting Method", "Max Choices", "Candidates", "Regions"},
            c -> new Object[]{c.getId(),
                c.getElection() != null ? c.getElection().getName() : "",
                c.getTitle(), c.getVotingMethod(), c.getMaxChoices(),
                c.getCandidates().size(), c.getAssignedRegions().size()});
        this.repo = repo;
        this.electionRepo = electionRepo;
        this.regionRepo = regionRepo;
        this.languageRepo = languageRepo;
        this.contestTranslationRepo = contestTranslationRepo;
        this.candidateTranslationRepo = candidateTranslationRepo;
    }

    @Override List<Contest> loadAll() { return repo.findAll(); }

    @Override void save(Contest entity) { repo.save(entity); }

    @Override void delete(Contest entity) { repo.deleteById(entity.getId()); }

    @Override JComponent buildForm(Contest existing, Consumer<Contest> onSave) {
        Contest c = existing != null ? existing : new Contest();
        boolean isNew = existing == null;

        JComboBox<Election> electionCombo = new JComboBox<>();
        electionCombo.setName("electionCombo");
        for (Election el : electionRepo.findAll()) electionCombo.addItem(el);
        electionCombo.setRenderer(labelRenderer(o -> ((Election) o).getName()));
        selectById(electionCombo, c.getElection());

        JTextField titleField = new JTextField(c.getTitle(), 24);
        JComboBox<VotingMethod> methodCombo = new JComboBox<>(VotingMethod.values());
        if (c.getVotingMethod() != null) methodCombo.setSelectedItem(c.getVotingMethod());
        JSpinner maxChoicesSpinner = new JSpinner(new SpinnerNumberModel(Math.max(c.getMaxChoices(), 1), 1, 20, 1));
        JSpinner maxRankSpinner = new JSpinner(new SpinnerNumberModel(c.getMaxRankChoices(), 0, 20, 1));
        JSpinner displayOrderSpinner = new JSpinner(new SpinnerNumberModel(c.getDisplayOrder(), 0, 999, 1));
        // 60 columns (was 24) — wide enough that a typical instructional
        // sentence doesn't wrap after 3-4 words; still just 2 rows tall, so
        // a genuinely long paragraph scrolls rather than growing the form.
        JTextArea instructionsArea = wrappingTextArea(c.getInstructions(), 2, 60);
        titleField.setName("titleField");
        methodCombo.setName("methodCombo");
        maxChoicesSpinner.setName("maxChoicesSpinner");
        maxRankSpinner.setName("maxRankSpinner");
        displayOrderSpinner.setName("displayOrderSpinner");
        instructionsArea.setName("instructionsArea");

        JTextField groupingLabelField = new JTextField(c.getGroupingLabel(), 20);
        JCheckBox printGroupingLabel = new JCheckBox("", c.isPrintGroupingLabel());
        groupingLabelField.setName("groupingLabelField");
        printGroupingLabel.setName("printGroupingLabel");

        JTextArea preambleArea = wrappingTextArea(c.getPreamble(), 2, 60);
        JCheckBox printPreamble = new JCheckBox("", c.isPrintPreamble());
        preambleArea.setName("preambleArea");
        printPreamble.setName("printPreamble");

        JTextArea postambleArea = wrappingTextArea(c.getPostamble(), 2, 60);
        JCheckBox printPostamble = new JCheckBox("", c.isPrintPostamble());
        postambleArea.setName("postambleArea");
        printPostamble.setName("printPostamble");

        JTextArea explanatoryTextArea = wrappingTextArea(c.getExplanatoryText(), 2, 60);
        JCheckBox printExplanatoryText = new JCheckBox("", c.isPrintExplanatoryText());
        JTextField explanatoryLocationField = new JTextField(c.getExplanatoryTextLocation(), 20);
        explanatoryTextArea.setName("explanatoryTextArea");
        printExplanatoryText.setName("printExplanatoryText");
        explanatoryLocationField.setName("explanatoryLocationField");

        JPanel grid = fieldGrid();
        int row = 0;
        addField(grid, row++, "Election:", electionCombo);
        addField(grid, row++, "Title:", titleField);
        addField(grid, row++, "Voting Method:", methodCombo);
        addField(grid, row++, "Max Choices:", maxChoicesSpinner);
        addField(grid, row++, "Max Rank Choices (0 = not ranked-choice):", maxRankSpinner);
        addField(grid, row++, "Grouping Label:", groupingLabelField);
        addField(grid, row++, "Print Grouping Label:", printGroupingLabel);
        addField(grid, row++, "Display Order:", displayOrderSpinner);
        addField(grid, row++, "Instructions:", new JScrollPane(instructionsArea));
        addField(grid, row++, "Preamble:", new JScrollPane(preambleArea));
        addField(grid, row++, "Print Preamble:", printPreamble);
        addField(grid, row++, "Postamble:", new JScrollPane(postambleArea));
        addField(grid, row++, "Print Postamble:", printPostamble);
        addField(grid, row++, "Explanatory Text:", new JScrollPane(explanatoryTextArea));
        addField(grid, row++, "Print Explanatory Text:", printExplanatoryText);
        addField(grid, row++, "Explanatory Text Location:", explanatoryLocationField);

        JButton manageCandidatesBtn = new JButton(
            isNew ? "Manage Candidates (save first)" : "Manage Candidates (" + c.getCandidates().size() + ")");
        JButton assignRegionsBtn = new JButton(
            isNew ? "Assign Regions (save first)" : "Assign Regions (" + c.getAssignedRegions().size() + ")");
        JButton translationsBtn = new JButton(isNew ? "Translations (save first)" : "Translations");
        manageCandidatesBtn.setName("manageCandidatesButton");
        assignRegionsBtn.setName("assignRegionsButton");
        translationsBtn.setName("translationsButton");
        manageCandidatesBtn.setEnabled(!isNew);
        assignRegionsBtn.setEnabled(!isNew);
        translationsBtn.setEnabled(!isNew);

        JPanel subScreenButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        subScreenButtons.add(manageCandidatesBtn);
        subScreenButtons.add(assignRegionsBtn);
        subScreenButtons.add(translationsBtn);
        addField(grid, row++, "Related:", subScreenButtons);

        JScrollPane scroller = new JScrollPane(grid);
        // Widened from 620: that fixed width used to be wider than the grid's
        // own content, so it was setting the visible/dialog size. Now that
        // the preamble/postamble/instructions/explanatory text areas are
        // wider (60 columns, see wrappingTextArea() calls above), the grid's
        // own preferred width (942px) exceeds the old cap — which didn't
        // grow the dialog, it just hid the extra width behind a horizontal
        // scrollbar inside this same fixed viewport, silently undoing the
        // point of widening those fields. 960 comfortably covers the grid's
        // current content plus scrollbar/border allowance.
        scroller.setPreferredSize(new Dimension(960, 520));
        JPanel scrollerWrap = new JPanel(new BorderLayout());
        scrollerWrap.add(scroller, BorderLayout.CENTER);

        // Resolved from this (persistent, standing) panel — NOT from grid,
        // whose own window is the per-edit JDialog that SimpleCrudPanel's
        // openForm() disposes as part of onSave.accept() below. Deriving the
        // cascade's owner from grid *after* that dispose meant
        // ContestCandidatesDialog/ContestRegionsDialog were being built with
        // an already-disposed owner and silently failed to display — the
        // real cause of "Manage Candidates doesn't lead to anything" after
        // saving a contest. This panel itself is never disposed by that, so
        // its window ancestor stays valid across the whole save+cascade.
        Window stableOwner = SwingUtilities.getWindowAncestor(this);

        JComponent form = formShell(isNew ? "New Contest" : "Edit Contest", scrollerWrap,
            () -> {
                c.setElection((Election) electionCombo.getSelectedItem());
                c.setTitle(titleField.getText());
                c.setVotingMethod((VotingMethod) methodCombo.getSelectedItem());
                c.setMaxChoices((Integer) maxChoicesSpinner.getValue());
                c.setMaxRankChoices((Integer) maxRankSpinner.getValue());
                c.setDisplayOrder((Integer) displayOrderSpinner.getValue());
                c.setInstructions(instructionsArea.getText());
                c.setGroupingLabel(groupingLabelField.getText());
                c.setPrintGroupingLabel(printGroupingLabel.isSelected());
                c.setPreamble(preambleArea.getText());
                c.setPrintPreamble(printPreamble.isSelected());
                c.setPostamble(postambleArea.getText());
                c.setPrintPostamble(printPostamble.isSelected());
                c.setExplanatoryText(explanatoryTextArea.getText());
                c.setPrintExplanatoryText(printExplanatoryText.isSelected());
                c.setExplanatoryTextLocation(explanatoryLocationField.getText());
                onSave.accept(c);

                // Once saved, the contest definitely has an id — cascade
                // straight into candidates, then regions, matching the
                // explicit request ("once saved, should open new screens").
                ContestCandidatesDialog.show(stableOwner, c, repo, languageRepo, candidateTranslationRepo,
                    () -> ContestRegionsDialog.show(stableOwner, c, regionRepo, repo, () -> {}));
            },
            () -> SwingUtilities.getWindowAncestor(grid).dispose());

        // getWindowAncestor(grid) here is the "Edit Contest" JDialog itself
        // (grid lives inside it) — a java.awt.Dialog, not a Frame. The
        // dialogs' show() methods take a Window (not Frame) owner
        // specifically so this cast-free call works for both this path and
        // stableOwner's Frame above; an earlier (Frame) cast here threw
        // ClassCastException on every click, for every already-saved
        // contest reopened by double-click — caught by
        // ContestCascadeGuiTest.reopeningExistingContestReachesCandidatesAndRegionsButtons.
        manageCandidatesBtn.addActionListener(e ->
            ContestCandidatesDialog.show(SwingUtilities.getWindowAncestor(grid), c, repo,
                languageRepo, candidateTranslationRepo, () -> {}));
        assignRegionsBtn.addActionListener(e ->
            ContestRegionsDialog.show(SwingUtilities.getWindowAncestor(grid), c, regionRepo, repo, () -> {}));
        translationsBtn.addActionListener(e ->
            ContestTranslationDialog.show(SwingUtilities.getWindowAncestor(grid), c, languageRepo, contestTranslationRepo));

        return form;
    }

    private static <E> ListCellRenderer<E> labelRenderer(java.util.function.Function<Object, String> nameOf) {
        return (list, value, index, isSelected, hasFocus) -> {
            JLabel l = new JLabel(value != null ? nameOf.apply(value) : "");
            l.setOpaque(true);
            l.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            return l;
        };
    }

    private static <E> void selectById(JComboBox<E> combo, Object target) {
        if (target == null) return;
        for (int i = 0; i < combo.getItemCount(); i++) {
            E item = combo.getItemAt(i);
            if (item != null && idOf(item).equals(idOf(target))) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    private static Long idOf(Object entity) {
        try {
            return (Long) entity.getClass().getMethod("getId").invoke(entity);
        } catch (Exception e) {
            return null;
        }
    }
}
