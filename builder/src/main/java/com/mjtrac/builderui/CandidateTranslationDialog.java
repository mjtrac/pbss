/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.BallotLanguage;
import com.mjtrac.ballot.model.Candidate;
import com.mjtrac.ballot.model.CandidateTranslation;
import com.mjtrac.ballot.model.Contest;
import com.mjtrac.ballot.repository.BallotLanguageRepository;
import com.mjtrac.ballot.repository.CandidateTranslationRepository;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Per-language candidate text — name and explanatory text — one tab per
 * active language for the candidate's contest's jurisdiction. Mirrors
 * bBuilder's /data/candidates/{id}/translate route. Only usable for a
 * candidate that's already been saved (has an id) — new, not-yet-saved
 * rows in the bulk candidates table don't have one yet.
 */
final class CandidateTranslationDialog {

    private CandidateTranslationDialog() {}

    static void show(Window owner, Candidate candidate, Contest contest,
                      BallotLanguageRepository languageRepo, CandidateTranslationRepository translationRepo) {
        if (candidate.getId() == null) {
            JOptionPane.showMessageDialog(owner,
                "Save this candidate first (Save & Continue), then reopen Translations for it.",
                "Not saved yet", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JDialog dialog = new JDialog(owner, "Translations — " + candidate.getName(), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setName("candidateTranslationDialog");

        Long jurisdictionId = contest.getElection() != null && contest.getElection().getJurisdiction() != null
            ? contest.getElection().getJurisdiction().getId() : null;
        List<BallotLanguage> languages = jurisdictionId != null
            ? languageRepo.findByJurisdictionIdOrderByDisplayOrderAsc(jurisdictionId) : List.of();

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        if (languages.isEmpty()) {
            root.add(new JLabel("No languages defined for this jurisdiction yet — add some under Languages first."),
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
        record Fields(JTextField name, JTextArea explanatoryText) {}
        java.util.Map<String, Fields> byLanguage = new java.util.LinkedHashMap<>();

        for (BallotLanguage lang : languages) {
            CandidateTranslation existing = translationRepo
                .findByCandidateIdAndLanguageCode(candidate.getId(), lang.getLanguageCode())
                .orElse(null);

            JTextField name = new JTextField(existing != null ? existing.getName() : "", 24);
            JTextArea explanatoryText = SimpleCrudPanel.wrappingTextArea(existing != null ? existing.getExplanatoryText() : "", 2, 24);
            name.setName("nameField_" + lang.getLanguageCode());
            explanatoryText.setName("explanatoryTextArea_" + lang.getLanguageCode());
            byLanguage.put(lang.getLanguageCode(), new Fields(name, explanatoryText));

            JPanel grid = SimpleCrudPanel.fieldGrid();
            int row = 0;
            SimpleCrudPanel.addField(grid, row++, "Name:", name);
            SimpleCrudPanel.addField(grid, row++, "Explanatory Text:", new JScrollPane(explanatoryText));
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
                CandidateTranslation tr = translationRepo.findByCandidateIdAndLanguageCode(candidate.getId(), code)
                    .orElseGet(() -> {
                        CandidateTranslation t = new CandidateTranslation();
                        t.setCandidate(candidate);
                        t.setLanguageCode(code);
                        return t;
                    });
                tr.setName(f.name().getText());
                tr.setExplanatoryText(f.explanatoryText().getText());
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
        dialog.setSize(520, 320);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }
}
