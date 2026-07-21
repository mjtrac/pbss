/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.BallotType;
import com.mjtrac.ballot.model.Election;
import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.model.Region;
import com.mjtrac.ballot.repository.BallotCombinationRepository;
import com.mjtrac.ballot.repository.BallotTypeRepository;
import com.mjtrac.ballot.repository.ElectionRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.RegionRepository;
import org.assertj.swing.finder.JOptionPaneFinder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * New -> Edit -> Delete -> Refresh round trip for the Ballot Combinations
 * screen. Its combos hold real entities (Region/Party/BallotType/Election),
 * not enums, so AssertJ-Swing's default cell reader (which formats via
 * String.valueOf(), not this app's custom ListCellRenderers) can't match
 * them by display text — selection is by index instead throughout.
 */
class BallotCombinationCrudGuiTest extends AbstractBuilderGuiTest {

    @Test
    void newEditDeleteRefreshCycle() {
        JurisdictionRepository jurisdictionRepo = bean(JurisdictionRepository.class);
        RegionRepository regionRepo = bean(RegionRepository.class);
        BallotTypeRepository ballotTypeRepo = bean(BallotTypeRepository.class);
        ElectionRepository electionRepo = bean(ElectionRepository.class);
        BallotCombinationRepository repo = bean(BallotCombinationRepository.class);

        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Test County");
        jurisdiction = jurisdictionRepo.save(jurisdiction);

        Region precinct = new Region();
        precinct.setJurisdiction(jurisdiction);
        precinct.setName("Precinct 1");
        precinct.setRegionType(Region.RegionType.SINGLE_PRECINCT);
        regionRepo.save(precinct);

        BallotType ballotType = new BallotType();
        ballotType.setJurisdiction(jurisdiction);
        ballotType.setName("Precinct Ballot");
        ballotTypeRepo.save(ballotType);

        Election election = new Election();
        election.setJurisdiction(jurisdiction);
        election.setName("Test Election");
        election.setElectionType(Election.ElectionType.GENERAL);
        electionRepo.save(election);

        robotAction(() -> {
            window.menuItem("BallotCombinationsMenuItem").click();

            window.button("BallotCombinationsNewButton").click();
            window.comboBox("regionCombo").selectItem(0);
            window.comboBox("typeCombo").selectItem(0);
            window.comboBox("electionCombo").selectItem(0);
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).hasSize(1);
        assertThat(repo.findAll().get(0).getRegion().getName()).isEqualTo("Precinct 1");

        robotAction(() -> {
            window.table("BallotCombinationsTable").selectRows(0);
            window.button("BallotCombinationsEditButton").click();
            window.comboBox("regionCombo").selectItem(0);
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).hasSize(1);

        robotAction(() -> {
            window.table("BallotCombinationsTable").selectRows(0);
            window.button("BallotCombinationsDeleteButton").click();
        });
        robotAction(() -> JOptionPaneFinder.findOptionPane().using(window.robot()).yesButton().click());
        assertThat(repo.findAll()).isEmpty();

        robotAction(() -> window.button("BallotCombinationsRefreshButton").click());
        assertThat(window.table("BallotCombinationsTable").target().getRowCount()).isEqualTo(0);
    }
}
