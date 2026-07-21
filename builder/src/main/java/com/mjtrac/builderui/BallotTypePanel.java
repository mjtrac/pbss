/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.BallotType;
import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.repository.BallotTypeRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.util.List;
import java.util.function.Consumer;

@Component
class BallotTypePanel extends SimpleCrudPanel<BallotType> {

    private final BallotTypeRepository repo;
    private final JurisdictionRepository jurisdictionRepo;

    BallotTypePanel(BallotTypeRepository repo, JurisdictionRepository jurisdictionRepo) {
        super("Ballot Types", new String[]{"ID", "Jurisdiction", "Name", "Description"},
            t -> new Object[]{t.getId(),
                t.getJurisdiction() != null ? t.getJurisdiction().getName() : "",
                t.getName(), t.getDescription()});
        this.repo = repo;
        this.jurisdictionRepo = jurisdictionRepo;
    }

    @Override List<BallotType> loadAll() { return repo.findAll(); }

    @Override void save(BallotType entity) { repo.save(entity); }

    @Override void delete(BallotType entity) { repo.deleteById(entity.getId()); }

    @Override JComponent buildForm(BallotType existing, Consumer<BallotType> onSave) {
        BallotType t = existing != null ? existing : new BallotType();

        JComboBox<Jurisdiction> jurisdictionCombo = JurisdictionCombo.build(jurisdictionRepo, t.getJurisdiction());
        JTextField nameField = new JTextField(t.getName(), 24);
        JTextField descField = new JTextField(t.getDescription(), 24);
        nameField.setName("nameField");
        descField.setName("descField");

        JPanel grid = fieldGrid();
        addField(grid, 0, "Jurisdiction:", jurisdictionCombo);
        addField(grid, 1, "Name:", nameField);
        addField(grid, 2, "Description:", descField);

        return formShell(existing == null ? "New Ballot Type" : "Edit Ballot Type", grid,
            () -> {
                t.setJurisdiction((Jurisdiction) jurisdictionCombo.getSelectedItem());
                t.setName(nameField.getText());
                t.setDescription(descField.getText());
                onSave.accept(t);
            },
            () -> SwingUtilities.getWindowAncestor(grid).dispose());
    }
}
