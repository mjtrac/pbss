/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.repository.JurisdictionRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** New -> Edit -> Delete -> Refresh round trip for the Jurisdictions screen. */
class JurisdictionCrudGuiTest extends AbstractBuilderGuiTest {

    @Test
    void newEditDeleteRefreshCycle() {
        JurisdictionRepository repo = bean(JurisdictionRepository.class);

        robotAction(() -> {
            window.menuItem("JurisdictionsMenuItem").click();

            window.button("JurisdictionsNewButton").click();
            window.textBox("nameField").setText("Test County");
            window.textBox("addressField").setText("100 Main St");
            window.textBox("emailField").setText("clerk@testcounty.gov");
            window.textBox("instructionsArea").setText("Mark your ballot in blue or black ink.");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).extracting("name").containsExactly("Test County");
        assertThat(window.table("JurisdictionsTable").target().getRowCount()).isEqualTo(1);

        robotAction(() -> {
            window.table("JurisdictionsTable").cell(org.assertj.swing.data.TableCell.row(0).column(1)).doubleClick();
            window.textBox("nameField").setText("Renamed County");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).extracting("name").containsExactly("Renamed County");

        robotAction(() -> {
            window.table("JurisdictionsTable").selectRows(0);
            window.button("JurisdictionsDeleteButton").click();
        });
        robotAction(() -> org.assertj.swing.finder.JOptionPaneFinder.findOptionPane().using(window.robot()).yesButton().click());
        assertThat(repo.findAll()).isEmpty();

        robotAction(() -> window.button("JurisdictionsRefreshButton").click());
        assertThat(window.table("JurisdictionsTable").target().getRowCount()).isEqualTo(0);
    }
}
