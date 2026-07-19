/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.model.Region;
import com.mjtrac.ballot.model.Region.RegionType;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.RegionRepository;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Component
class RegionPanel extends SimpleCrudPanel<Region> {

    private final RegionRepository repo;
    private final JurisdictionRepository jurisdictionRepo;

    RegionPanel(RegionRepository repo, JurisdictionRepository jurisdictionRepo) {
        super("Regions", new String[]{"ID", "Jurisdiction", "Name", "Type", "Group Type"},
            r -> new Object[]{r.getId(),
                r.getJurisdiction() != null ? r.getJurisdiction().getName() : "",
                r.getName(), r.getRegionType(), r.getGroupType()});
        this.repo = repo;
        this.jurisdictionRepo = jurisdictionRepo;
    }

    @Override List<Region> loadAll() { return repo.findAll(); }

    @Override void save(Region entity) { repo.save(entity); }

    @Override void delete(Region entity) { repo.deleteById(entity.getId()); }

    @Override JComponent buildForm(Region existing, Consumer<Region> onSave) {
        Region r = existing != null ? existing : new Region();
        if (r.getRegionType() == null) r.setRegionType(RegionType.SINGLE_PRECINCT);

        JComboBox<Jurisdiction> jurisdictionCombo = JurisdictionCombo.build(jurisdictionRepo, r.getJurisdiction());
        JTextField nameField = new JTextField(r.getName(), 24);
        JComboBox<RegionType> typeCombo = new JComboBox<>(RegionType.values());
        typeCombo.setSelectedItem(r.getRegionType());
        JTextField groupTypeField = new JTextField(r.getGroupType(), 24);
        JTextField descField = new JTextField(r.getDescription(), 24);

        DefaultListModel<Region> memberModel = new DefaultListModel<>();
        JList<Region> memberList = new JList<>(memberModel);
        memberList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        memberList.setCellRenderer((list, value, index, isSelected, hasFocus) ->
            new JLabel(value.getName()) {{ setOpaque(true);
                setBackground(isSelected ? list.getSelectionBackground() : list.getBackground()); }});
        Runnable reloadMembers = () -> {
            memberModel.clear();
            Jurisdiction j = (Jurisdiction) jurisdictionCombo.getSelectedItem();
            if (j == null) return;
            for (Region candidate : repo.findByJurisdictionIdAndRegionTypeOrderByName(j.getId(), RegionType.SINGLE_PRECINCT)) {
                if (existing == null || !candidate.getId().equals(existing.getId())) {
                    memberModel.addElement(candidate);
                }
            }
            for (int i = 0; i < memberModel.size(); i++) {
                if (r.getMembers().contains(memberModel.get(i))) {
                    memberList.addSelectionInterval(i, i);
                }
            }
        };
        reloadMembers.run();
        jurisdictionCombo.addActionListener(e -> reloadMembers.run());

        JPanel grid = fieldGrid();
        addField(grid, 0, "Jurisdiction:", jurisdictionCombo);
        addField(grid, 1, "Name:", nameField);
        addField(grid, 2, "Type:", typeCombo);
        addField(grid, 3, "Group Type (city/water district/etc.):", groupTypeField);
        addField(grid, 4, "Description:", descField);
        addField(grid, 5, "Members (Precinct Groups only):", new JScrollPane(memberList));

        return formShell(existing == null ? "New Region" : "Edit Region", grid,
            () -> {
                r.setJurisdiction((Jurisdiction) jurisdictionCombo.getSelectedItem());
                r.setName(nameField.getText());
                r.setRegionType((RegionType) typeCombo.getSelectedItem());
                r.setGroupType(groupTypeField.getText());
                r.setDescription(descField.getText());
                if (r.getRegionType() == RegionType.PRECINCT_GROUP) {
                    r.setMembers(new ArrayList<>(memberList.getSelectedValuesList()));
                } else {
                    r.setMembers(new ArrayList<>());
                }
                onSave.accept(r);
            },
            () -> SwingUtilities.getWindowAncestor(grid).dispose());
    }
}
