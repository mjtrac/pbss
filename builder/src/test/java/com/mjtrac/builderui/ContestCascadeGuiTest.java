/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Election;
import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.model.Region;
import com.mjtrac.ballot.repository.ContestRepository;
import com.mjtrac.ballot.repository.ElectionRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.RegionRepository;
import org.assertj.swing.data.TableCell;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the bug fixed in commit 0c4ca5b: saving a new
 * Contest used to resolve the Candidates/Regions dialog owner from a
 * component whose window had already been disposed by the save callback
 * (SimpleCrudPanel.openForm() disposes the per-edit JDialog as part of
 * onSave.accept()), so ContestCandidatesDialog/ContestRegionsDialog were
 * built with an already-disposed owner and silently failed to display —
 * "Manage Candidates doesn't lead to anything" after saving a contest. The
 * fix captures the dialog owner from the persistent ContestPanel itself,
 * before that dispose runs (see ContestPanel.java's `stableOwner`).
 *
 * Also covers the other, unrelated code path that opens these same dialogs
 * — the "Manage Candidates"/"Assign Regions" buttons on an already-saved
 * Contest reopened by double-click — so a future regression in either path
 * gets caught, not just the one this bug happened to hit.
 */
class ContestCascadeGuiTest extends AbstractBuilderGuiTest {

    @Test
    void savingNewContestCascadesIntoCandidatesThenRegionsDialogs() {
        JurisdictionRepository jurisdictionRepo = bean(JurisdictionRepository.class);
        ElectionRepository electionRepo = bean(ElectionRepository.class);
        RegionRepository regionRepo = bean(RegionRepository.class);
        ContestRepository contestRepo = bean(ContestRepository.class);

        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Test County");
        jurisdiction = jurisdictionRepo.save(jurisdiction);

        Election election = new Election();
        election.setJurisdiction(jurisdiction);
        election.setName("Test Election");
        election.setElectionType(Election.ElectionType.GENERAL);
        electionRepo.save(election);

        Region precinct = new Region();
        precinct.setJurisdiction(jurisdiction);
        precinct.setName("Precinct 1");
        precinct.setRegionType(Region.RegionType.SINGLE_PRECINCT);
        regionRepo.save(precinct);

        robotAction(() -> {
            window.menuItem("ContestsMenuItem").click();
            window.button("ContestsNewButton").click();

            window.comboBox("electionCombo").selectItem(0);
            window.textBox("titleField").setText("Mayor");
            window.spinner("maxChoicesSpinner").enterText("1");
            window.button("saveButton").click();

            // This is exactly what silently failed before the fix: saving
            // the contest must cascade straight into the Candidates dialog.
            window.dialog("candidatesDialog").requireVisible();
            window.button("addCandidateButton").click();
            assertThat(window.dialog("candidatesDialog").table("candidatesTable").target().getRowCount())
                .as("exactly one candidate after exactly one Add Candidate click")
                .isEqualTo(1);
            window.dialog("candidatesDialog").button("saveContinueButton").click();

            // Candidates' save/close must cascade into the Regions dialog.
            window.dialog("regionsDialog").requireVisible();
            window.dialog("regionsDialog").list("regionList").selectItem(0);
            window.dialog("regionsDialog").button("saveButton").click();
        });

        assertThat(contestRepo.findAll()).hasSize(1);
        var saved = contestRepo.findAll().get(0);
        assertThat(saved.getTitle()).isEqualTo("Mayor");
        // Regression coverage for a second, closely-related bug this test
        // caught: contestRepo.save(contest) discarding its return value
        // meant the candidate added above stayed "transient" in memory even
        // after being inserted, so ContestRegionsDialog's own save(contest)
        // moments later re-inserted it a second time — every candidate
        // silently duplicated on the ordinary create-contest workflow. See
        // ContestCandidatesDialog's save handler for the fix.
        assertThat(saved.getCandidates()).hasSize(1);
        assertThat(saved.getAssignedRegions()).extracting(Region::getName).containsExactly("Precinct 1");
    }

    @Test
    void reopeningExistingContestReachesCandidatesAndRegionsButtons() {
        JurisdictionRepository jurisdictionRepo = bean(JurisdictionRepository.class);
        ElectionRepository electionRepo = bean(ElectionRepository.class);
        RegionRepository regionRepo = bean(RegionRepository.class);
        ContestRepository contestRepo = bean(ContestRepository.class);

        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Test County");
        jurisdiction = jurisdictionRepo.save(jurisdiction);

        Election election = new Election();
        election.setJurisdiction(jurisdiction);
        election.setName("Test Election");
        election.setElectionType(Election.ElectionType.GENERAL);
        electionRepo.save(election);

        Region precinct = new Region();
        precinct.setJurisdiction(jurisdiction);
        precinct.setName("Precinct 1");
        precinct.setRegionType(Region.RegionType.SINGLE_PRECINCT);
        regionRepo.save(precinct);

        robotAction(() -> {
            window.menuItem("ContestsMenuItem").click();
            window.button("ContestsNewButton").click();
            window.comboBox("electionCombo").selectItem(0);
            window.textBox("titleField").setText("Measure A");
            window.spinner("maxChoicesSpinner").enterText("1");
            window.button("saveButton").click();

            // Dismiss the auto-cascade from the save above so we can
            // reopen the same contest by double-click instead.
            window.dialog("candidatesDialog").button("closeButton").click();
            window.dialog("regionsDialog").button("closeButton").click();
        });
        assertThat(contestRepo.findAll()).hasSize(1);

        robotAction(() -> {
            window.table("ContestsTable").cell(TableCell.row(0).column(2)).doubleClick();
            window.button("manageCandidatesButton").requireEnabled();
            window.button("manageCandidatesButton").click();
            window.dialog("candidatesDialog").requireVisible();
            window.dialog("candidatesDialog").button("closeButton").click();

            window.button("assignRegionsButton").requireEnabled();
            window.button("assignRegionsButton").click();
            window.dialog("regionsDialog").requireVisible();
            window.dialog("regionsDialog").button("closeButton").click();

            window.button("translationsButton").requireEnabled();
        });
    }
}
