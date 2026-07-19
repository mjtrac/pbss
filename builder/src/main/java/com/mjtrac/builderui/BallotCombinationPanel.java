/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.model.Region.RegionType;
import com.mjtrac.ballot.repository.*;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

@Component
class BallotCombinationPanel extends SimpleCrudPanel<BallotCombination> {

    private final BallotCombinationRepository repo;
    private final RegionRepository regionRepo;
    private final PartyRepository partyRepo;
    private final BallotTypeRepository ballotTypeRepo;
    private final ElectionRepository electionRepo;

    BallotCombinationPanel(BallotCombinationRepository repo, RegionRepository regionRepo,
                            PartyRepository partyRepo, BallotTypeRepository ballotTypeRepo,
                            ElectionRepository electionRepo) {
        super("Ballot Combinations", new String[]{"ID", "Precinct", "Party", "Ballot Type", "Election"},
            c -> new Object[]{c.getId(),
                c.getRegion() != null ? c.getRegion().getName() : "",
                c.getParty() != null ? c.getParty().getName() : "(nonpartisan)",
                c.getBallotType() != null ? c.getBallotType().getName() : "",
                c.getElection() != null ? c.getElection().getName() : ""});
        this.repo = repo;
        this.regionRepo = regionRepo;
        this.partyRepo = partyRepo;
        this.ballotTypeRepo = ballotTypeRepo;
        this.electionRepo = electionRepo;
    }

    @Override List<BallotCombination> loadAll() { return repo.findAll(); }

    @Override void save(BallotCombination entity) { repo.save(entity); }

    @Override void delete(BallotCombination entity) { repo.deleteById(entity.getId()); }

    @Override JComponent buildForm(BallotCombination existing, Consumer<BallotCombination> onSave) {
        BallotCombination c = existing != null ? existing : new BallotCombination();

        JComboBox<Region> regionCombo = new JComboBox<>();
        for (Region r : regionRepo.findAll()) {
            if (r.getRegionType() == RegionType.SINGLE_PRECINCT) regionCombo.addItem(r);
        }
        regionCombo.setRenderer(nameRenderer(o -> ((Region) o).getName()));
        selectById(regionCombo, c.getRegion());

        JComboBox<Party> partyCombo = new JComboBox<>();
        partyCombo.addItem(null);
        for (Party p : partyRepo.findAll()) partyCombo.addItem(p);
        partyCombo.setRenderer(nameRenderer(o -> o == null ? "(nonpartisan)" : ((Party) o).getName()));
        selectById(partyCombo, c.getParty());

        JComboBox<BallotType> typeCombo = new JComboBox<>();
        for (BallotType t : ballotTypeRepo.findAll()) typeCombo.addItem(t);
        typeCombo.setRenderer(nameRenderer(o -> ((BallotType) o).getName()));
        selectById(typeCombo, c.getBallotType());

        JComboBox<Election> electionCombo = new JComboBox<>();
        for (Election el : electionRepo.findAll()) electionCombo.addItem(el);
        electionCombo.setRenderer(nameRenderer(o -> ((Election) o).getName()));
        selectById(electionCombo, c.getElection());

        JPanel grid = fieldGrid();
        addField(grid, 0, "Precinct:", regionCombo);
        addField(grid, 1, "Party:", partyCombo);
        addField(grid, 2, "Ballot Type:", typeCombo);
        addField(grid, 3, "Election:", electionCombo);

        return formShell(existing == null ? "New Ballot Combination" : "Edit Ballot Combination", grid,
            () -> {
                c.setRegion((Region) regionCombo.getSelectedItem());
                c.setParty((Party) partyCombo.getSelectedItem());
                c.setBallotType((BallotType) typeCombo.getSelectedItem());
                c.setElection((Election) electionCombo.getSelectedItem());
                onSave.accept(c);
            },
            () -> SwingUtilities.getWindowAncestor(grid).dispose());
    }

    private static <E> ListCellRenderer<E> nameRenderer(java.util.function.Function<Object, String> nameOf) {
        return (list, value, index, isSelected, hasFocus) -> {
            JLabel label = new JLabel(nameOf.apply(value));
            label.setOpaque(true);
            label.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            return label;
        };
    }

    private static <E> void selectById(JComboBox<E> combo, Object target) {
        if (target == null) return;
        for (int i = 0; i < combo.getItemCount(); i++) {
            E item = combo.getItemAt(i);
            if (item != null && idOf(item).equals(idOf(target))) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    private static Long idOf(Object entity) {
        try {
            return (Long) entity.getClass().getMethod("getId").invoke(entity);
        } catch (Exception e) {
            return null;
        }
    }
}
