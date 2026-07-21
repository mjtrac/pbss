/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.BallotLanguage;
import com.mjtrac.ballot.model.Contest;
import com.mjtrac.ballot.model.ContestTranslation;
import com.mjtrac.ballot.repository.BallotLanguageRepository;
import com.mjtrac.ballot.repository.ContestTranslationRepository;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Per-language contest text — title, instructions, preamble, postamble,
 * grouping label — one tab per active language for the contest's
 * jurisdiction. Capability bBuilder exposes via LanguageController's
 * /data/contests/{id}/translate route (contest-translate.html); builder
 * never had this screen at all before.
 */
final class ContestTranslationDialog {

    private ContestTranslationDialog() {}

    static void show(Window owner, Contest contest, BallotLanguageRepository languageRepo,
                      ContestTranslationRepository translationRepo) {
        JDialog dialog = new JDialog(owner, "Translations — " + contest.getTitle(), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setName("contestTranslationDialog");

        Long jurisdictionId = contest.getElection() != null && contest.getElection().getJurisdiction() != null
            ? contest.getElection().getJurisdiction().getId() : null;
        List<BallotLanguage> languages = jurisdictionId != null
            ? languageRepo.findByJurisdictionIdOrderByDisplayOrderAsc(jurisdictionId) : List.of();

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        if (languages.isEmpty()) {
            root.add(new JLabel("No languages defined for this contest's jurisdiction yet — add some under Languages first."),
                BorderLayout.CENTER);
            JButton close = new JButton("Close");
            close.addActionListener(e -> dialog.dispose());
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(close);
            root.add(buttons, BorderLayout.SOUTH);
            dialog.getContentPane().add(root);
            dialog.setSize(420, 160);
            dialog.setLocationRelativeTo(owner);
            dialog.setVisible(true);
            return;
        }

        JTabbedPane tabs = new JTabbedPane();
        tabs.setName("languageTabs");
        record Fields(JTextField title, JTextArea instructions, JTextArea preamble, JTextArea postamble, JTextField groupingLabel) {}
        java.util.Map<String, Fields> byLanguage = new java.util.LinkedHashMap<>();

        for (BallotLanguage lang : languages) {
            ContestTranslation existing = translationRepo
                .findByContestIdAndLanguageCode(contest.getId(), lang.getLanguageCode())
                .orElse(null);

            JTextField title = new JTextField(existing != null ? existing.getTitle() : "", 24);
            JTextArea instructions = SimpleCrudPanel.wrappingTextArea(existing != null ? existing.getInstructions() : "", 2, 24);
            JTextArea preamble = SimpleCrudPanel.wrappingTextArea(existing != null ? existing.getPreamble() : "", 2, 24);
            JTextArea postamble = SimpleCrudPanel.wrappingTextArea(existing != null ? existing.getPostamble() : "", 2, 24);
            JTextField groupingLabel = new JTextField(existing != null ? existing.getGroupingLabel() : "", 20);
            title.setName("titleField_" + lang.getLanguageCode());
            instructions.setName("instructionsArea_" + lang.getLanguageCode());
            preamble.setName("preambleArea_" + lang.getLanguageCode());
            postamble.setName("postambleArea_" + lang.getLanguageCode());
            groupingLabel.setName("groupingLabelField_" + lang.getLanguageCode());
            byLanguage.put(lang.getLanguageCode(), new Fields(title, instructions, preamble, postamble, groupingLabel));

            JPanel grid = SimpleCrudPanel.fieldGrid();
            int row = 0;
            SimpleCrudPanel.addField(grid, row++, "Title:", title);
            SimpleCrudPanel.addField(grid, row++, "Instructions:", new JScrollPane(instructions));
            SimpleCrudPanel.addField(grid, row++, "Preamble:", new JScrollPane(preamble));
            SimpleCrudPanel.addField(grid, row++, "Postamble:", new JScrollPane(postamble));
            SimpleCrudPanel.addField(grid, row++, "Grouping Label:", groupingLabel);
            tabs.addTab(lang.getLanguageName() + " (" + lang.getLanguageCode() + ")", grid);
        }
        root.add(tabs, BorderLayout.CENTER);

        JButton save = new JButton("Save All");
        JButton close = new JButton("Close");
        save.setName("saveAllButton");
        close.setName("closeButton");
        save.addActionListener(e -> {
            for (var entry : byLanguage.entrySet()) {
                String code = entry.getKey();
                Fields f = entry.getValue();
                ContestTranslation tr = translationRepo.findByContestIdAndLanguageCode(contest.getId(), code)
                    .orElseGet(() -> {
                        ContestTranslation t = new ContestTranslation();
                        t.setContest(contest);
                        t.setLanguageCode(code);
                        return t;
                    });
                tr.setTitle(f.title().getText());
                tr.setInstructions(f.instructions().getText());
                tr.setPreamble(f.preamble().getText());
                tr.setPostamble(f.postamble().getText());
                tr.setGroupingLabel(f.groupingLabel().getText());
                translationRepo.save(tr);
            }
            dialog.dispose();
        });
        close.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(close);
        buttons.add(save);
        root.add(buttons, BorderLayout.SOUTH);

        dialog.getContentPane().add(root);
        dialog.setSize(560, 480);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }
}
