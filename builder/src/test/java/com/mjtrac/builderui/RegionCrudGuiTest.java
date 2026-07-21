/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.RegionRepository;
import org.assertj.swing.data.TableCell;
import org.assertj.swing.finder.JOptionPaneFinder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * New -> Edit -> Delete -> Refresh round trip for the Regions screen, plus
 * the "Use Single Region" toolbar button — see PartyCrudGuiTest's class
 * comment for why this is exercised via the explicit button rather than the
 * implicit onFirstOpenEmpty() startup dialog.
 */
class RegionCrudGuiTest extends AbstractBuilderGuiTest {

    @Test
    void newEditDeleteRefreshCycle() {
        JurisdictionRepository jurisdictionRepo = bean(JurisdictionRepository.class);
        RegionRepository repo = bean(RegionRepository.class);

        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Test County");
        jurisdictionRepo.save(jurisdiction);

        robotAction(() -> {
            window.menuItem("RegionsMenuItem").click();

            window.button("RegionsNewButton").click();
            window.comboBox("jurisdictionCombo").selectItem(0);
            window.textBox("nameField").setText("Precinct 1");
            window.comboBox("typeCombo").selectItem("SINGLE_PRECINCT");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).extracting("name").containsExactly("Precinct 1");

        robotAction(() -> {
            window.table("RegionsTable").cell(TableCell.row(0).column(2)).doubleClick();
            window.textBox("nameField").setText("Precinct One");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).extracting("name").containsExactly("Precinct One");

        robotAction(() -> {
            window.table("RegionsTable").selectRows(0);
            window.button("RegionsDeleteButton").click();
        });
        robotAction(() -> JOptionPaneFinder.findOptionPane().using(window.robot()).yesButton().click());
        assertThat(repo.findAll()).isEmpty();

        robotAction(() -> window.button("RegionsRefreshButton").click());
        assertThat(window.table("RegionsTable").target().getRowCount()).isEqualTo(0);
    }

    @Test
    void useSingleRegionReplacesExistingRegions() {
        JurisdictionRepository jurisdictionRepo = bean(JurisdictionRepository.class);
        RegionRepository repo = bean(RegionRepository.class);

        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Test County");
        jurisdictionRepo.save(jurisdiction);

        robotAction(() -> {
            window.menuItem("RegionsMenuItem").click();
            window.button("RegionsNewButton").click();
            window.comboBox("jurisdictionCombo").selectItem(0);
            window.textBox("nameField").setText("Precinct 1");
            window.comboBox("typeCombo").selectItem("SINGLE_PRECINCT");
            window.button("saveButton").click();

            window.button("RegionsNewButton").click();
            window.comboBox("jurisdictionCombo").selectItem(0);
            window.textBox("nameField").setText("Precinct 2");
            window.comboBox("typeCombo").selectItem("SINGLE_PRECINCT");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).hasSize(2);

        robotAction(() -> window.button("useSingleRegionButton").click());
        robotAction(() -> JOptionPaneFinder.findOptionPane().using(window.robot()).yesButton().click());

        assertThat(repo.findAll()).extracting("name").containsExactly("All Precincts");
    }
}
