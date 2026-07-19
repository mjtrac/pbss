/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.repository.JurisdictionRepository;

import javax.swing.*;
import java.awt.*;

/** Nearly every entity in this app belongs to a Jurisdiction — one shared combo-box builder. */
final class JurisdictionCombo {

    private JurisdictionCombo() {}

    static JComboBox<Jurisdiction> build(JurisdictionRepository repo, Jurisdiction selected) {
        JComboBox<Jurisdiction> combo = new JComboBox<>();
        for (Jurisdiction j : repo.findAll()) combo.addItem(j);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean hasFocus) {
                String text = value instanceof Jurisdiction j ? j.getName() : "";
                return super.getListCellRendererComponent(list, text, index, isSelected, hasFocus);
            }
        });
        if (selected != null) {
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (combo.getItemAt(i).getId().equals(selected.getId())) {
                    combo.setSelectedIndex(i);
                    break;
                }
            }
        }
        return combo;
    }
}
