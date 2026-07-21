/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.BallotCombination;
import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.model.Party;
import com.mjtrac.ballot.repository.BallotCombinationRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.PartyRepository;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
class PartyPanel extends SimpleCrudPanel<Party> {

    private static final String SINGLE_PARTY_NAME = "Nonpartisan";

    private final PartyRepository repo;
    private final JurisdictionRepository jurisdictionRepo;
    private final BallotCombinationRepository combinationRepo;

    PartyPanel(PartyRepository repo, JurisdictionRepository jurisdictionRepo,
               BallotCombinationRepository combinationRepo) {
        super("Parties", new String[]{"ID", "Jurisdiction", "Name", "Abbreviation"},
            p -> new Object[]{p.getId(),
                p.getJurisdiction() != null ? p.getJurisdiction().getName() : "",
                p.getName(), p.getAbbreviation()});
        this.repo = repo;
        this.jurisdictionRepo = jurisdictionRepo;
        this.combinationRepo = combinationRepo;

        JButton singlePartyBtn = new JButton("Use Single Party");
        singlePartyBtn.setToolTipText("Replace all parties with one \"" + SINGLE_PARTY_NAME + "\" party — the common case for a general/nonpartisan election");
        singlePartyBtn.addActionListener(e -> useSingleParty());
        addToolbarButton(singlePartyBtn);
    }

    @Override protected void onFirstOpenEmpty() {
        int choice = JOptionPane.showConfirmDialog(this,
            "No parties defined yet. Most elections only need one — set up a single\n"
            + "\"" + SINGLE_PARTY_NAME + "\" party now? (You can always add more later.)",
            "Quick Setup", JOptionPane.YES_NO_OPTION);
        if (choice == JOptionPane.YES_OPTION) useSingleParty();
    }

    private void useSingleParty() {
        List<Party> existing = repo.findAll();
        if (existing.size() == 1) {
            JOptionPane.showMessageDialog(this,
                "Already using a single party (\"" + existing.get(0).getName() + "\") — nothing to do.");
            return;
        }

        Jurisdiction jurisdiction = existing.isEmpty()
            ? jurisdictionRepo.findAll().stream().findFirst().orElse(null)
            : existing.get(0).getJurisdiction();
        if (jurisdiction == null) {
            JOptionPane.showMessageDialog(this, "Create a Jurisdiction first.", "Cannot Continue", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (!existing.isEmpty()) {
            String names = existing.stream().map(Party::getName).collect(Collectors.joining("\n  • ", "\n  • ", ""));
            int confirm = JOptionPane.showConfirmDialog(this,
                "This will replace " + existing.size() + " existing part(y/ies):" + names
                + "\n\nAny Ballot Combination currently using one of these will be reassigned to "
                + "the new single \"" + SINGLE_PARTY_NAME + "\" party, then the old part(y/ies) will "
                + "be permanently deleted."
                + "\n\nThis cannot be undone. Continue?",
                "Replace With Single Party", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
        }

        replacePartiesWithSingle(existing, jurisdiction);
        refresh();
    }

    /**
     * Core logic, no dialogs — split out from useSingleParty() so a test
     * can exercise the reassign-then-delete path (the real risk: Party has
     * no equals()/hashCode() override, so old.getId() comparisons must be
     * done by id, not object identity) without a blocking JOptionPane.
     */
    Party replacePartiesWithSingle(List<Party> existing, Jurisdiction jurisdiction) {
        Party single = new Party();
        single.setJurisdiction(jurisdiction);
        single.setName(SINGLE_PARTY_NAME);
        single = repo.save(single);

        if (!existing.isEmpty()) {
            Set<Long> oldIds = existing.stream().map(Party::getId).collect(Collectors.toSet());
            for (BallotCombination combo : combinationRepo.findAll()) {
                Party p = combo.getParty();
                if (p != null && oldIds.contains(p.getId())) {
                    combo.setParty(single);
                    combinationRepo.save(combo);
                }
            }
            for (Party old : existing) repo.deleteById(old.getId());
        }
        return single;
    }

    @Override List<Party> loadAll() { return repo.findAll(); }

    @Override void save(Party entity) { repo.save(entity); }

    @Override void delete(Party entity) { repo.deleteById(entity.getId()); }

    @Override JComponent buildForm(Party existing, Consumer<Party> onSave) {
        Party p = existing != null ? existing : new Party();

        JComboBox<Jurisdiction> jurisdictionCombo = JurisdictionCombo.build(jurisdictionRepo, p.getJurisdiction());
        JTextField nameField = new JTextField(p.getName(), 24);
        JTextField abbrevField = new JTextField(p.getAbbreviation(), 24);

        JPanel grid = fieldGrid();
        addField(grid, 0, "Jurisdiction:", jurisdictionCombo);
        addField(grid, 1, "Name:", nameField);
        addField(grid, 2, "Abbreviation:", abbrevField);

        return formShell(existing == null ? "New Party" : "Edit Party", grid,
            () -> {
                p.setJurisdiction((Jurisdiction) jurisdictionCombo.getSelectedItem());
                p.setName(nameField.getText());
                p.setAbbreviation(abbrevField.getText());
                onSave.accept(p);
            },
            () -> SwingUtilities.getWindowAncestor(grid).dispose());
    }
}
