/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.repository.ElectionRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import org.assertj.swing.data.TableCell;
import org.assertj.swing.finder.JOptionPaneFinder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** New -> Edit -> Delete -> Refresh round trip for the Elections screen. */
class ElectionCrudGuiTest extends AbstractBuilderGuiTest {

    @Test
    void newEditDeleteRefreshCycle() {
        JurisdictionRepository jurisdictionRepo = bean(JurisdictionRepository.class);
        ElectionRepository repo = bean(ElectionRepository.class);

        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Test County");
        jurisdictionRepo.save(jurisdiction);

        robotAction(() -> {
            window.menuItem("ElectionsMenuItem").click();

            window.button("ElectionsNewButton").click();
            window.comboBox("jurisdictionCombo").selectItem(0);
            window.textBox("nameField").setText("General Election");
            window.textBox("dateField").setText("2026-11-03");
            window.comboBox("typeCombo").selectItem("GENERAL");
            window.checkBox("uniformCheck").check();
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).extracting("name").containsExactly("General Election");
        assertThat(repo.findAll().get(0).isUniformBallot()).isTrue();

        robotAction(() -> {
            window.table("ElectionsTable").cell(TableCell.row(0).column(2)).doubleClick();
            window.textBox("nameField").setText("Renamed Election");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).extracting("name").containsExactly("Renamed Election");

        robotAction(() -> {
            window.table("ElectionsTable").selectRows(0);
            window.button("ElectionsDeleteButton").click();
        });
        robotAction(() -> JOptionPaneFinder.findOptionPane().using(window.robot()).yesButton().click());
        assertThat(repo.findAll()).isEmpty();

        robotAction(() -> window.button("ElectionsRefreshButton").click());
        assertThat(window.table("ElectionsTable").target().getRowCount()).isEqualTo(0);
    }
}
