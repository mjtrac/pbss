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
        for (Election el : electionRepo.findAll()) electionCombo.addItem(el);
        electionCombo.setRenderer(labelRenderer(o -> ((Election) o).getName()));
        selectById(electionCombo, c.getElection());

        JTextField titleField = new JTextField(c.getTitle(), 24);
        JComboBox<VotingMethod> methodCombo = new JComboBox<>(VotingMethod.values());
        if (c.getVotingMethod() != null) methodCombo.setSelectedItem(c.getVotingMethod());
        JSpinner maxChoicesSpinner = new JSpinner(new SpinnerNumberModel(Math.max(c.getMaxChoices(), 1), 1, 20, 1));
        JSpinner maxRankSpinner = new JSpinner(new SpinnerNumberModel(c.getMaxRankChoices(), 0, 20, 1));
        JSpinner displayOrderSpinner = new JSpinner(new SpinnerNumberModel(c.getDisplayOrder(), 0, 999, 1));
        JTextArea instructionsArea = new JTextArea(c.getInstructions(), 2, 24);

        JTextField groupingLabelField = new JTextField(c.getGroupingLabel(), 20);
        JCheckBox printGroupingLabel = new JCheckBox("", c.isPrintGroupingLabel());

        JTextArea preambleArea = new JTextArea(c.getPreamble(), 2, 24);
        JCheckBox printPreamble = new JCheckBox("", c.isPrintPreamble());

        JTextArea postambleArea = new JTextArea(c.getPostamble(), 2, 24);
        JCheckBox printPostamble = new JCheckBox("", c.isPrintPostamble());

        JTextArea explanatoryTextArea = new JTextArea(c.getExplanatoryText(), 2, 24);
        JCheckBox printExplanatoryText = new JCheckBox("", c.isPrintExplanatoryText());
        JTextField explanatoryLocationField = new JTextField(c.getExplanatoryTextLocation(), 20);

        JPanel grid = fieldGrid();
        int row = 0;
        addField(grid, row++, "Election:", electionCombo);
        addField(grid, row++, "Title:", titleField);
        addField(grid, row++, "Voting Method:", methodCombo);
        addField(grid, row++, "Max Choices:", maxChoicesSpinner);
        addField(grid, row++, "Max Rank Choices (0 = not ranked-choice):", maxRankSpinner);
        addField(grid, row++, "Display Order:", displayOrderSpinner);
        addField(grid, row++, "Instructions:", new JScrollPane(instructionsArea));
        addField(grid, row++, "Grouping Label:", groupingLabelField);
        addField(grid, row++, "Print Grouping Label:", printGroupingLabel);
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
        manageCandidatesBtn.setEnabled(!isNew);
        assignRegionsBtn.setEnabled(!isNew);
        translationsBtn.setEnabled(!isNew);

        JPanel subScreenButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        subScreenButtons.add(manageCandidatesBtn);
        subScreenButtons.add(assignRegionsBtn);
        subScreenButtons.add(translationsBtn);
        addField(grid, row++, "Related:", subScreenButtons);

        JScrollPane scroller = new JScrollPane(grid);
        scroller.setPreferredSize(new Dimension(620, 520));
        JPanel scrollerWrap = new JPanel(new BorderLayout());
        scrollerWrap.add(scroller, BorderLayout.CENTER);

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
                // grid (not `this`, the ContestPanel) is the component
                // actually inside this dialog's window.
                Frame owner = (Frame) SwingUtilities.getWindowAncestor(grid);
                ContestCandidatesDialog.show(owner, c, repo, languageRepo, candidateTranslationRepo,
                    () -> ContestRegionsDialog.show(owner, c, regionRepo, repo, () -> {}));
            },
            () -> SwingUtilities.getWindowAncestor(grid).dispose());

        manageCandidatesBtn.addActionListener(e ->
            ContestCandidatesDialog.show((Frame) SwingUtilities.getWindowAncestor(grid), c, repo,
                languageRepo, candidateTranslationRepo, () -> {}));
        assignRegionsBtn.addActionListener(e ->
            ContestRegionsDialog.show((Frame) SwingUtilities.getWindowAncestor(grid), c, regionRepo, repo, () -> {}));
        translationsBtn.addActionListener(e ->
            ContestTranslationDialog.show((Frame) SwingUtilities.getWindowAncestor(grid), c, languageRepo, contestTranslationRepo));

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
