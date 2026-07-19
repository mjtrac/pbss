/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Election;
import com.mjtrac.ballot.model.Election.ElectionType;
import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.repository.ElectionRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Consumer;

@Component
class ElectionPanel extends SimpleCrudPanel<Election> {

    private final ElectionRepository repo;
    private final JurisdictionRepository jurisdictionRepo;

    ElectionPanel(ElectionRepository repo, JurisdictionRepository jurisdictionRepo) {
        super("Elections", new String[]{"ID", "Jurisdiction", "Name", "Date", "Type", "Uniform Ballot"},
            e -> new Object[]{e.getId(),
                e.getJurisdiction() != null ? e.getJurisdiction().getName() : "",
                e.getName(), e.getElectionDate(), e.getElectionType(), e.isUniformBallot()});
        this.repo = repo;
        this.jurisdictionRepo = jurisdictionRepo;
    }

    @Override List<Election> loadAll() { return repo.findAll(); }

    @Override void save(Election entity) { repo.save(entity); }

    @Override void delete(Election entity) { repo.deleteById(entity.getId()); }

    @Override JComponent buildForm(Election existing, Consumer<Election> onSave) {
        Election e = existing != null ? existing : new Election();

        JComboBox<Jurisdiction> jurisdictionCombo = JurisdictionCombo.build(jurisdictionRepo, e.getJurisdiction());
        JTextField nameField = new JTextField(e.getName(), 24);
        JTextField dateField = new JTextField(e.getElectionDate() != null ? e.getElectionDate().toString() : "", 24);
        JComboBox<ElectionType> typeCombo = new JComboBox<>(ElectionType.values());
        if (e.getElectionType() != null) typeCombo.setSelectedItem(e.getElectionType());
        JCheckBox uniformCheck = new JCheckBox("Uniform ballot (same for every voter)", e.isUniformBallot());

        JPanel grid = fieldGrid();
        addField(grid, 0, "Jurisdiction:", jurisdictionCombo);
        addField(grid, 1, "Name:", nameField);
        addField(grid, 2, "Date (yyyy-mm-dd):", dateField);
        addField(grid, 3, "Type:", typeCombo);
        addField(grid, 4, "", uniformCheck);

        return formShell(existing == null ? "New Election" : "Edit Election", grid,
            () -> {
                if (!dateField.getText().isBlank()) {
                    try {
                        e.setElectionDate(LocalDate.parse(dateField.getText().trim()));
                    } catch (DateTimeParseException ex) {
                        JOptionPane.showMessageDialog(grid, "Date must be yyyy-mm-dd, e.g. 2026-11-03");
                        return;
                    }
                } else {
                    e.setElectionDate(null);
                }
                e.setJurisdiction((Jurisdiction) jurisdictionCombo.getSelectedItem());
                e.setName(nameField.getText());
                e.setElectionType((ElectionType) typeCombo.getSelectedItem());
                e.setUniformBallot(uniformCheck.isSelected());
                onSave.accept(e);
            },
            () -> SwingUtilities.getWindowAncestor(grid).dispose());
    }
}
