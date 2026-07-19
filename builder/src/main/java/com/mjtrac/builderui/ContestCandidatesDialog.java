/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Candidate;
import com.mjtrac.ballot.model.Contest;
import com.mjtrac.ballot.repository.BallotLanguageRepository;
import com.mjtrac.ballot.repository.CandidateTranslationRepository;
import com.mjtrac.ballot.repository.ContestRepository;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Bulk candidate entry/editing for one contest — every candidate at once in
 * an editable table, not a one-at-a-time form (per explicit request). Opens
 * automatically after a Contest save, and is reachable again afterward via
 * "Manage Candidates" on the Contest edit form (double-click a Contest row
 * to get there). Full Candidate field set — this is the capability gap
 * bBuilder's own dedicated /contests/{id}/candidates page covers.
 *
 * Saves by re-saving the owning Contest (candidates is
 * @OneToMany(cascade=ALL, orphanRemoval=true) on Contest), same pattern
 * ContestPanel already used for its old inline candidate sub-table.
 */
final class ContestCandidatesDialog {

    private ContestCandidatesDialog() {}

    static void show(Frame owner, Contest contest, ContestRepository contestRepo,
                      BallotLanguageRepository languageRepo, CandidateTranslationRepository candidateTranslationRepo,
                      Runnable onClose) {
        JDialog dialog = new JDialog(owner, "Candidates — " + contest.getTitle(), true);

        List<Candidate> working = new ArrayList<>(contest.getCandidates());
        CandidateTableModel model = new CandidateTableModel(working);
        JTable table = new JTable(model);
        table.setRowHeight(24);

        JButton addBtn = new JButton("Add Candidate");
        JButton removeBtn = new JButton("Remove Selected");
        JButton translationsBtn = new JButton("Translations for Selected");
        addBtn.addActionListener(e -> {
            Candidate c = new Candidate();
            c.setName("New Candidate");
            c.setDisplayOrder(working.size() + 1);
            working.add(c);
            model.fireTableDataChanged();
        });
        removeBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                working.remove(row);
                model.fireTableDataChanged();
            }
        });
        translationsBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                CandidateTranslationDialog.show(owner, working.get(row), contest, languageRepo, candidateTranslationRepo);
            }
        });
        JPanel tableButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        tableButtons.add(addBtn);
        tableButtons.add(removeBtn);
        tableButtons.add(translationsBtn);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(new JLabel("Every candidate/option for \"" + contest.getTitle() + "\":"), BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(tableButtons, BorderLayout.SOUTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton save = new JButton("Save & Continue");
        JButton close = new JButton("Close");
        buttons.add(close);
        buttons.add(save);

        JPanel root = new JPanel(new BorderLayout());
        root.add(content, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        save.addActionListener(e -> {
            for (Candidate c : working) c.setContest(contest);
            contest.getCandidates().clear();
            contest.getCandidates().addAll(working);
            contestRepo.save(contest);
            dialog.dispose();
            onClose.run();
        });
        close.addActionListener(e -> {
            dialog.dispose();
            onClose.run();
        });

        dialog.getContentPane().add(root);
        dialog.setSize(760, 420);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /** Editable table: every Candidate field bBuilder's own candidate form exposes. */
    private static class CandidateTableModel extends AbstractTableModel {
        private final List<Candidate> candidates;
        private final String[] cols = {
            "Name", "Write-In", "Party", "Order",
            "Prefix Text", "Print Prefix", "Suffix Text", "Print Suffix",
            "Explanatory Text", "Print Explanatory"
        };

        CandidateTableModel(List<Candidate> candidates) { this.candidates = candidates; }

        @Override public int getRowCount() { return candidates.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int col) { return cols[col]; }
        @Override public boolean isCellEditable(int row, int col) { return true; }
        @Override public Class<?> getColumnClass(int col) {
            return (col == 1 || col == 5 || col == 7 || col == 9) ? Boolean.class : Object.class;
        }

        @Override public Object getValueAt(int row, int col) {
            Candidate c = candidates.get(row);
            return switch (col) {
                case 0 -> c.getName();
                case 1 -> c.isWriteIn();
                case 2 -> c.getPartyAffiliation();
                case 3 -> c.getDisplayOrder();
                case 4 -> c.getPrefixText();
                case 5 -> c.isPrintPrefixText();
                case 6 -> c.getSuffixText();
                case 7 -> c.isPrintSuffixText();
                case 8 -> c.getExplanatoryText();
                case 9 -> c.isPrintExplanatoryText();
                default -> "";
            };
        }

        @Override public void setValueAt(Object value, int row, int col) {
            Candidate c = candidates.get(row);
            switch (col) {
                case 0 -> c.setName((String) value);
                case 1 -> c.setWriteIn((Boolean) value);
                case 2 -> c.setPartyAffiliation((String) value);
                case 3 -> {
                    try {
                        c.setDisplayOrder(Integer.parseInt(value.toString()));
                    } catch (NumberFormatException ignored) {
                        // leave unchanged on bad input
                    }
                }
                case 4 -> c.setPrefixText((String) value);
                case 5 -> c.setPrintPrefixText((Boolean) value);
                case 6 -> c.setSuffixText((String) value);
                case 7 -> c.setPrintSuffixText((Boolean) value);
                case 8 -> c.setExplanatoryText((String) value);
                case 9 -> c.setPrintExplanatoryText((Boolean) value);
            }
            fireTableCellUpdated(row, col);
        }
    }
}
