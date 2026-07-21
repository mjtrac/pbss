/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.BallotCombination;
import com.mjtrac.ballot.model.Contest;
import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.model.Region;
import com.mjtrac.ballot.model.Region.RegionType;
import com.mjtrac.ballot.repository.BallotCombinationRepository;
import com.mjtrac.ballot.repository.ContestRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.RegionRepository;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
class RegionPanel extends SimpleCrudPanel<Region> {

    private static final String SINGLE_REGION_NAME = "All Precincts";

    private final RegionRepository repo;
    private final JurisdictionRepository jurisdictionRepo;
    private final ContestRepository contestRepo;
    private final BallotCombinationRepository combinationRepo;

    RegionPanel(RegionRepository repo, JurisdictionRepository jurisdictionRepo,
                ContestRepository contestRepo, BallotCombinationRepository combinationRepo) {
        super("Regions", new String[]{"ID", "Jurisdiction", "Name", "Type", "Group Type"},
            r -> new Object[]{r.getId(),
                r.getJurisdiction() != null ? r.getJurisdiction().getName() : "",
                r.getName(), r.getRegionType(), r.getGroupType()});
        this.repo = repo;
        this.jurisdictionRepo = jurisdictionRepo;
        this.contestRepo = contestRepo;
        this.combinationRepo = combinationRepo;

        JButton singleRegionBtn = new JButton("Use Single Region");
        singleRegionBtn.setName("useSingleRegionButton");
        singleRegionBtn.setToolTipText("Replace all regions with one \"" + SINGLE_REGION_NAME + "\" region — the common case for a single-precinct election");
        singleRegionBtn.addActionListener(e -> useSingleRegion());
        addToolbarButton(singleRegionBtn);
    }

    @Override protected void onFirstOpenEmpty() {
        int choice = JOptionPane.showConfirmDialog(this,
            "No regions defined yet. If every voter gets an identical ballot, you only need "
            + "one — set up a single\n\"" + SINGLE_REGION_NAME + "\" region now? (You can always add more later.)",
            "Quick Setup", JOptionPane.YES_NO_OPTION);
        if (choice == JOptionPane.YES_OPTION) useSingleRegion();
    }

    private void useSingleRegion() {
        List<Region> existing = repo.findAll();
        if (existing.size() == 1) {
            JOptionPane.showMessageDialog(this,
                "Already using a single region (\"" + existing.get(0).getName() + "\") — nothing to do.");
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
            String names = existing.stream().map(Region::getName).collect(Collectors.joining("\n  • ", "\n  • ", ""));
            int confirm = JOptionPane.showConfirmDialog(this,
                "This will replace " + existing.size() + " existing region(s):" + names
                + "\n\nAny Contest assigned to one of these will be reassigned to the new single "
                + "\"" + SINGLE_REGION_NAME + "\" region, any Ballot Combination using one will be "
                + "reassigned too, and precinct-group memberships will be cleared — then the old "
                + "region(s) will be permanently deleted."
                + "\n\nThis cannot be undone. Continue?",
                "Replace With Single Region", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
        }

        replaceRegionsWithSingle(existing, jurisdiction);
        refresh();
    }

    /**
     * Core logic, no dialogs — split out from useSingleRegion() so a test
     * can exercise the reassign-then-delete path directly. Order matters:
     * membership join rows must be cleared before any deleteById(), and
     * Contest.assignedRegions / BallotCombination.region must be
     * repointed before their old targets are deleted, or this throws a
     * foreign-key violation instead of silently corrupting anything.
     */
    Region replaceRegionsWithSingle(List<Region> existing, Jurisdiction jurisdiction) {
        Region single = new Region();
        single.setJurisdiction(jurisdiction);
        single.setName(SINGLE_REGION_NAME);
        single.setRegionType(RegionType.SINGLE_PRECINCT);
        single = repo.save(single);

        if (!existing.isEmpty()) {
            Set<Long> oldIds = existing.stream().map(Region::getId).collect(Collectors.toSet());

            // Clear precinct-group membership join rows first — deleting a
            // region that's still listed as another group's member (or that
            // still lists members of its own) would otherwise violate the
            // region_members join table's foreign keys.
            for (Region r : existing) {
                if (!r.getMembers().isEmpty()) {
                    r.setMembers(new ArrayList<>());
                    repo.save(r);
                }
            }

            for (BallotCombination combo : combinationRepo.findAll()) {
                if (combo.getRegion() != null && oldIds.contains(combo.getRegion().getId())) {
                    combo.setRegion(single);
                    combinationRepo.save(combo);
                }
            }

            for (Contest c : contestRepo.findAll()) {
                boolean referencesOld = c.getAssignedRegions().stream().anyMatch(r -> oldIds.contains(r.getId()));
                if (referencesOld) {
                    List<Region> replacement = new ArrayList<>();
                    replacement.add(single);
                    c.setAssignedRegions(replacement);
                    contestRepo.save(c);
                }
            }

            for (Region old : existing) repo.deleteById(old.getId());
        }
        return single;
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
        nameField.setName("nameField");
        typeCombo.setName("typeCombo");
        groupTypeField.setName("groupTypeField");
        descField.setName("descField");

        DefaultListModel<Region> memberModel = new DefaultListModel<>();
        JList<Region> memberList = new JList<>(memberModel);
        memberList.setName("memberList");
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
