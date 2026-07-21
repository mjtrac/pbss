/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies RegionPanel/PartyPanel's "Use Single Region/Party" shortcuts
 * against a real, populated data graph — the actual risk being tested:
 * BallotCombination.region is a required (non-nullable) foreign key,
 * Contest.assignedRegions is a many-to-many, and Region.members is a
 * self-referential many-to-many join table, so a naive delete of an
 * in-use region would throw a foreign-key violation rather than silently
 * corrupt anything. Same reasoning as BuilderEndToEndTest for why the
 * test-only @SpringBootApplication scans only com.mjtrac.ballot.
 *
 * PartyPanel/RegionPanel are constructed directly (not injected as Spring
 * beans) since they're normally built by MainFrame's own component scan,
 * which this test config deliberately avoids.
 */
@SpringBootTest(classes = RegionPartyShortcutTest.TestConfig.class)
class RegionPartyShortcutTest {

    @SpringBootApplication(scanBasePackages = "com.mjtrac.ballot")
    @EntityScan("com.mjtrac.ballot.model")
    static class TestConfig {
    }

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideDb(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
    }

    @Autowired JurisdictionRepository jurisdictionRepo;
    @Autowired RegionRepository regionRepo;
    @Autowired PartyRepository partyRepo;
    @Autowired ElectionRepository electionRepo;
    @Autowired BallotTypeRepository ballotTypeRepo;
    @Autowired ContestRepository contestRepo;
    @Autowired CandidateRepository candidateRepo;
    @Autowired BallotCombinationRepository combinationRepo;

    @Test
    void replaceRegionsWithSingleReassignsContestsAndCombinationsThenDeletesOldOnes() {
        Jurisdiction jurisdiction = jurisdictionRepo.save(newJurisdiction("Region Shortcut County"));
        Election election = newElection(jurisdiction, "Region Shortcut Election");
        election = electionRepo.save(election);
        BallotType ballotType = new BallotType();
        ballotType.setJurisdiction(jurisdiction);
        ballotType.setName("Precinct");
        ballotType = ballotTypeRepo.save(ballotType);

        Region precinct1 = newRegion(jurisdiction, "Precinct 1");
        precinct1 = regionRepo.save(precinct1);
        Region precinct2 = newRegion(jurisdiction, "Precinct 2");
        precinct2 = regionRepo.save(precinct2);

        // A precinct group whose members list will need clearing before delete.
        Region group = newRegion(jurisdiction, "District A");
        group.setRegionType(Region.RegionType.PRECINCT_GROUP);
        group.setMembers(List.of(precinct1, precinct2));
        group = regionRepo.save(group);

        // A contest assigned to two of the soon-to-be-deleted regions.
        Contest contest = new Contest();
        contest.setElection(election);
        contest.setTitle("Mayor");
        contest.setMaxChoices(1);
        contest.setVotingMethod(Contest.VotingMethod.PLURALITY);
        contest.setAssignedRegions(List.of(precinct1, precinct2));
        contest = contestRepo.save(contest);

        // A ballot combination pointing at one of the soon-to-be-deleted regions.
        BallotCombination combo = new BallotCombination();
        combo.setRegion(precinct1);
        combo.setBallotType(ballotType);
        combo.setElection(election);
        combo = combinationRepo.save(combo);

        RegionPanel panel = new RegionPanel(regionRepo, jurisdictionRepo, contestRepo, combinationRepo);
        // Scoped to just this test's own rows, not regionRepo.findAll() —
        // this test class shares one Spring context/DB across both @Test
        // methods (JUnit doesn't guarantee method order), same reasoning
        // as BuilderEndToEndTest's distinct-jurisdiction-names comment.
        List<Region> existing = List.of(precinct1, precinct2, group);

        Region single = panel.replaceRegionsWithSingle(existing, jurisdiction);

        assertThat(regionRepo.findById(precinct1.getId())).as("old region deleted").isEmpty();
        assertThat(regionRepo.findById(precinct2.getId())).as("old region deleted").isEmpty();
        assertThat(regionRepo.findById(group.getId())).as("old region deleted").isEmpty();
        assertThat(regionRepo.findById(single.getId())).as("new single region persisted").isPresent();

        Contest reloadedContest = contestRepo.findById(contest.getId()).orElseThrow();
        assertThat(reloadedContest.getAssignedRegions())
            .as("contest repointed to the new single region")
            .extracting(Region::getId).containsExactly(single.getId());

        BallotCombination reloadedCombo = combinationRepo.findById(combo.getId()).orElseThrow();
        assertThat(reloadedCombo.getRegion().getId())
            .as("combination repointed to the new single region")
            .isEqualTo(single.getId());
    }

    @Test
    void replacePartiesWithSingleReassignsCombinationsThenDeletesOldOnes() {
        Jurisdiction jurisdiction = jurisdictionRepo.save(newJurisdiction("Party Shortcut County"));
        Election election = electionRepo.save(newElection(jurisdiction, "Party Shortcut Election"));
        BallotType ballotType = new BallotType();
        ballotType.setJurisdiction(jurisdiction);
        ballotType.setName("Precinct");
        ballotType = ballotTypeRepo.save(ballotType);
        Region region = regionRepo.save(newRegion(jurisdiction, "Precinct 1"));

        Party democrat = new Party();
        democrat.setJurisdiction(jurisdiction);
        democrat.setName("Democrat");
        democrat = partyRepo.save(democrat);
        Party republican = new Party();
        republican.setJurisdiction(jurisdiction);
        republican.setName("Republican");
        republican = partyRepo.save(republican);

        BallotCombination combo = new BallotCombination();
        combo.setRegion(region);
        combo.setParty(democrat);
        combo.setBallotType(ballotType);
        combo.setElection(election);
        combo = combinationRepo.save(combo);

        PartyPanel panel = new PartyPanel(partyRepo, jurisdictionRepo, combinationRepo);
        // Scoped to just this test's own rows — see the region test's
        // comment on why (shared context/DB across both @Test methods).
        List<Party> existing = List.of(democrat, republican);

        Party single = panel.replacePartiesWithSingle(existing, jurisdiction);

        assertThat(partyRepo.findById(democrat.getId())).as("old party deleted").isEmpty();
        assertThat(partyRepo.findById(republican.getId())).as("old party deleted").isEmpty();

        BallotCombination reloadedCombo = combinationRepo.findById(combo.getId()).orElseThrow();
        assertThat(reloadedCombo.getParty().getId())
            .as("combination repointed to the new single party")
            .isEqualTo(single.getId());
    }

    private static Jurisdiction newJurisdiction(String name) {
        Jurisdiction j = new Jurisdiction();
        j.setName(name);
        return j;
    }

    private static Election newElection(Jurisdiction jurisdiction, String name) {
        Election e = new Election();
        e.setJurisdiction(jurisdiction);
        e.setName(name);
        e.setElectionType(Election.ElectionType.GENERAL);
        return e;
    }

    private static Region newRegion(Jurisdiction jurisdiction, String name) {
        Region r = new Region();
        r.setJurisdiction(jurisdiction);
        r.setName(name);
        r.setRegionType(Region.RegionType.SINGLE_PRECINCT);
        return r;
    }
}
