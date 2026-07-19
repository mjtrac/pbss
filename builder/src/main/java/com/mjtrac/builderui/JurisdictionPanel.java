/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.util.List;
import java.util.function.Consumer;

@Component
class JurisdictionPanel extends SimpleCrudPanel<Jurisdiction> {

    private final JurisdictionRepository repo;

    JurisdictionPanel(JurisdictionRepository repo) {
        super("Jurisdictions", new String[]{"ID", "Name", "Address", "Contact Email"},
            j -> new Object[]{j.getId(), j.getName(), j.getAddress(), j.getContactEmail()});
        this.repo = repo;
    }

    @Override List<Jurisdiction> loadAll() { return repo.findAll(); }

    @Override void save(Jurisdiction entity) { repo.save(entity); }

    @Override void delete(Jurisdiction entity) { repo.deleteById(entity.getId()); }

    @Override JComponent buildForm(Jurisdiction existing, Consumer<Jurisdiction> onSave) {
        Jurisdiction j = existing != null ? existing : new Jurisdiction();

        JTextField nameField = new JTextField(j.getName(), 24);
        JTextField addressField = new JTextField(j.getAddress(), 24);
        JTextField emailField = new JTextField(j.getContactEmail(), 24);
        JTextArea instructionsArea = new JTextArea(j.getGeneralVotingInstructions(), 4, 24);

        JPanel grid = fieldGrid();
        addField(grid, 0, "Name:", nameField);
        addField(grid, 1, "Address:", addressField);
        addField(grid, 2, "Contact Email:", emailField);
        addField(grid, 3, "Voting Instructions:", new JScrollPane(instructionsArea));

        JDialog[] holder = new JDialog[1];
        JPanel shell = formShell(existing == null ? "New Jurisdiction" : "Edit Jurisdiction", grid,
            () -> {
                j.setName(nameField.getText());
                j.setAddress(addressField.getText());
                j.setContactEmail(emailField.getText());
                j.setGeneralVotingInstructions(instructionsArea.getText());
                onSave.accept(j);
            },
            () -> SwingUtilities.getWindowAncestor(grid).dispose());
        return shell;
    }
}
