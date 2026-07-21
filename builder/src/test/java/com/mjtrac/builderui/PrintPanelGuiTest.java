/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.BallotCombination;
import com.mjtrac.ballot.model.BallotDesignTemplate;
import com.mjtrac.ballot.model.BallotType;
import com.mjtrac.ballot.model.Election;
import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.model.Region;
import com.mjtrac.ballot.model.User;
import com.mjtrac.ballot.repository.BallotCombinationRepository;
import com.mjtrac.ballot.repository.BallotDesignTemplateRepository;
import com.mjtrac.ballot.repository.BallotTypeRepository;
import com.mjtrac.ballot.repository.ElectionRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.RegionRepository;
import com.mjtrac.ballot.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PrintPanel is not a SimpleCrudPanel screen — its own form + two buttons.
 * "Generate PDF" is exercised for real (a pure file-generation action).
 * "Open Output Folder" is deliberately NOT clicked: it calls
 * Desktop.getDesktop().open(...), which pops a real native Finder window
 * with no in-JVM way to close it headlessly — same category of exclusion as
 * MainFrameNavigationGuiTest's Home > Exit. Its presence/enabled state is
 * asserted instead.
 */
class PrintPanelGuiTest extends AbstractBuilderGuiTest {

    @Test
    void generatePdfWritesARealFile() {
        JurisdictionRepository jurisdictionRepo = bean(JurisdictionRepository.class);
        RegionRepository regionRepo = bean(RegionRepository.class);
        BallotTypeRepository ballotTypeRepo = bean(BallotTypeRepository.class);
        ElectionRepository electionRepo = bean(ElectionRepository.class);
        BallotCombinationRepository combinationRepo = bean(BallotCombinationRepository.class);
        BallotDesignTemplateRepository templateRepo = bean(BallotDesignTemplateRepository.class);
        UserRepository userRepo = bean(UserRepository.class);

        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Test County");
        jurisdiction = jurisdictionRepo.save(jurisdiction);

        Region precinct = new Region();
        precinct.setJurisdiction(jurisdiction);
        precinct.setName("Precinct 1");
        precinct.setRegionType(Region.RegionType.SINGLE_PRECINCT);
        precinct = regionRepo.save(precinct);

        BallotType ballotType = new BallotType();
        ballotType.setJurisdiction(jurisdiction);
        ballotType.setName("Precinct Ballot");
        ballotType = ballotTypeRepo.save(ballotType);

        Election election = new Election();
        election.setJurisdiction(jurisdiction);
        election.setName("Test Election");
        election.setElectionType(Election.ElectionType.GENERAL);
        election = electionRepo.save(election);

        BallotCombination combination = new BallotCombination();
        combination.setRegion(precinct);
        combination.setBallotType(ballotType);
        combination.setElection(election);
        combinationRepo.save(combination);

        BallotDesignTemplate template = new BallotDesignTemplate();
        template.setElection(election);
        template.setPaperSize(BallotDesignTemplate.PaperSize.LETTER_8_5x11);
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.OVAL);
        template.setColumns(1);
        templateRepo.save(template);

        User printedBy = new User();
        printedBy.setUsername("print-test");
        printedBy.setPasswordHash("unused");
        printedBy.setRoles(Set.of(User.Role.ADMIN));
        userRepo.save(printedBy);

        robotAction(() -> {
            window.menuItem("PrintMenuItem").click();
            window.comboBox("combinationCombo").selectItem(0);
            window.comboBox("templateCombo").selectItem(0);
            window.comboBox("userCombo").selectItem(0);
            window.button("generateButton").click();
        });

        // "Generated N byte PDF ... saved to <path>" on success; assert the
        // message reflects success rather than one of showError()'s paths.
        String message = window.label("messageLabel").text();
        assertThat(message).as("PrintPanel message after Generate PDF: " + message)
            .startsWith("Generated");

        File exportDir = tempDir.resolve("export").toFile();
        File[] pdfs = exportDir.listFiles((dir, name) -> name.endsWith(".pdf"));
        assertThat(pdfs).as("a PDF should have been written under " + exportDir).isNotEmpty();

        // Deliberately not clicked (see class Javadoc): assert it exists
        // and is reachable instead of triggering its real Desktop.open().
        window.button("openFolderButton").requireVisible().requireEnabled();
    }
}
