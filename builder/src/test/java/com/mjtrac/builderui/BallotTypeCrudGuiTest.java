/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.repository.BallotTypeRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import org.assertj.swing.data.TableCell;
import org.assertj.swing.finder.JOptionPaneFinder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** New -> Edit -> Delete -> Refresh round trip for the Ballot Types screen. */
class BallotTypeCrudGuiTest extends AbstractBuilderGuiTest {

    @Test
    void newEditDeleteRefreshCycle() {
        JurisdictionRepository jurisdictionRepo = bean(JurisdictionRepository.class);
        BallotTypeRepository repo = bean(BallotTypeRepository.class);

        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Test County");
        jurisdictionRepo.save(jurisdiction);

        robotAction(() -> {
            window.menuItem("BallotTypesMenuItem").click();

            window.button("BallotTypesNewButton").click();
            window.comboBox("jurisdictionCombo").selectItem(0);
            window.textBox("nameField").setText("Precinct Ballot");
            window.textBox("descField").setText("Standard precinct ballot");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).extracting("name").containsExactly("Precinct Ballot");

        robotAction(() -> {
            window.table("BallotTypesTable").cell(TableCell.row(0).column(2)).doubleClick();
            window.textBox("nameField").setText("Renamed Ballot");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).extracting("name").containsExactly("Renamed Ballot");

        robotAction(() -> {
            window.table("BallotTypesTable").selectRows(0);
            window.button("BallotTypesDeleteButton").click();
        });
        robotAction(() -> JOptionPaneFinder.findOptionPane().using(window.robot()).yesButton().click());
        assertThat(repo.findAll()).isEmpty();

        robotAction(() -> window.button("BallotTypesRefreshButton").click());
        assertThat(window.table("BallotTypesTable").target().getRowCount()).isEqualTo(0);
    }
}
