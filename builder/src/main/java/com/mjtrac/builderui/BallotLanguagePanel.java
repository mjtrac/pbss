/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.BallotLanguage;
import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.repository.BallotLanguageRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.util.List;
import java.util.function.Consumer;

@Component
class BallotLanguagePanel extends SimpleCrudPanel<BallotLanguage> {

    private final BallotLanguageRepository repo;
    private final JurisdictionRepository jurisdictionRepo;

    BallotLanguagePanel(BallotLanguageRepository repo, JurisdictionRepository jurisdictionRepo) {
        super("Languages", new String[]{"ID", "Jurisdiction", "Code", "Name", "Order"},
            l -> new Object[]{l.getId(),
                l.getJurisdiction() != null ? l.getJurisdiction().getName() : "",
                l.getLanguageCode(), l.getLanguageName(), l.getDisplayOrder()});
        this.repo = repo;
        this.jurisdictionRepo = jurisdictionRepo;
    }

    @Override List<BallotLanguage> loadAll() { return repo.findAll(); }

    @Override void save(BallotLanguage entity) { repo.save(entity); }

    @Override void delete(BallotLanguage entity) { repo.deleteById(entity.getId()); }

    @Override JComponent buildForm(BallotLanguage existing, Consumer<BallotLanguage> onSave) {
        BallotLanguage l = existing != null ? existing : new BallotLanguage();

        JComboBox<Jurisdiction> jurisdictionCombo = JurisdictionCombo.build(jurisdictionRepo, l.getJurisdiction());
        JTextField codeField = new JTextField(l.getLanguageCode(), 24);
        JTextField nameField = new JTextField(l.getLanguageName(), 24);
        JSpinner orderSpinner = new JSpinner(new SpinnerNumberModel(l.getDisplayOrder(), 0, 999, 1));
        codeField.setName("codeField");
        nameField.setName("nameField");
        orderSpinner.setName("orderSpinner");

        JPanel grid = fieldGrid();
        addField(grid, 0, "Jurisdiction:", jurisdictionCombo);
        addField(grid, 1, "Language Code (IETF, e.g. es):", codeField);
        addField(grid, 2, "Language Name:", nameField);
        addField(grid, 3, "Display Order:", orderSpinner);

        return formShell(existing == null ? "New Language" : "Edit Language", grid,
            () -> {
                l.setJurisdiction((Jurisdiction) jurisdictionCombo.getSelectedItem());
                l.setLanguageCode(codeField.getText());
                l.setLanguageName(nameField.getText());
                l.setDisplayOrder((Integer) orderSpinner.getValue());
                onSave.accept(l);
            },
            () -> SwingUtilities.getWindowAncestor(grid).dispose());
    }
}
