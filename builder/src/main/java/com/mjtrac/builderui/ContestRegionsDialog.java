/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Contest;
import com.mjtrac.ballot.model.Region;
import com.mjtrac.ballot.repository.ContestRepository;
import com.mjtrac.ballot.repository.RegionRepository;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;

/**
 * Precinct / precinct-group assignment for one contest — its own screen,
 * opened automatically after a Contest save and reachable again via
 * "Assign Regions" on the Contest edit form. Mirrors bBuilder's inline
 * region-assignment section (checkboxes split by SINGLE_PRECINCT vs
 * PRECINCT_GROUP), just as a separate dialog instead of a page section.
 */
final class ContestRegionsDialog {

    private ContestRegionsDialog() {}

    static void show(Frame owner, Contest contest, RegionRepository regionRepo,
                      ContestRepository contestRepo, Runnable onClose) {
        JDialog dialog = new JDialog(owner, "Assign Regions — " + contest.getTitle(), true);

        DefaultListModel<Region> regionModel = new DefaultListModel<>();
        JList<Region> regionList = new JList<>(regionModel);
        regionList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        regionList.setCellRenderer((list, value, index, isSelected, hasFocus) -> {
            JLabel l = new JLabel(value.getDisplayName()
                + (value.getRegionType() == Region.RegionType.PRECINCT_GROUP ? "  (group)" : ""));
            l.setOpaque(true);
            l.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            return l;
        });
        for (Region r : regionRepo.findAll()) regionModel.addElement(r);
        for (int i = 0; i < regionModel.size(); i++) {
            if (contest.getAssignedRegions().contains(regionModel.get(i))) regionList.addSelectionInterval(i, i);
        }

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(new JLabel("Precincts and precinct groups \"" + contest.getTitle() + "\" appears on:"), BorderLayout.NORTH);
        content.add(new JScrollPane(regionList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton save = new JButton("Save");
        JButton close = new JButton("Close");
        buttons.add(close);
        buttons.add(save);

        JPanel root = new JPanel(new BorderLayout());
        root.add(content, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        save.addActionListener(e -> {
            contest.setAssignedRegions(new ArrayList<>(regionList.getSelectedValuesList()));
            contestRepo.save(contest);
            dialog.dispose();
            onClose.run();
        });
        close.addActionListener(e -> {
            dialog.dispose();
            onClose.run();
        });

        dialog.getContentPane().add(root);
        dialog.setSize(420, 480);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }
}
