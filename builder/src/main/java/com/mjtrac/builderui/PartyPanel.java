/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.model.Party;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.PartyRepository;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.util.List;
import java.util.function.Consumer;

@Component
class PartyPanel extends SimpleCrudPanel<Party> {

    private final PartyRepository repo;
    private final JurisdictionRepository jurisdictionRepo;

    PartyPanel(PartyRepository repo, JurisdictionRepository jurisdictionRepo) {
        super("Parties", new String[]{"ID", "Jurisdiction", "Name", "Abbreviation"},
            p -> new Object[]{p.getId(),
                p.getJurisdiction() != null ? p.getJurisdiction().getName() : "",
                p.getName(), p.getAbbreviation()});
        this.repo = repo;
        this.jurisdictionRepo = jurisdictionRepo;
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
