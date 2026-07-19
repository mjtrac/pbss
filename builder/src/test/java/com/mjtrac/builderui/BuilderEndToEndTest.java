/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.repository.*;
import com.mjtrac.ballot.service.BallotGenerationService;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real, end-to-end verification through this module's own wiring: builds a
 * full data graph via the same repositories the Swing screens use (not
 * hand-constructed POJOs like bBuilder's own BallotGenerationServiceTest),
 * against an isolated temp SQLite DB, then generates a real PDF — proving
 * the CRUD/cascade save-and-reload path the screens rely on actually works
 * with the shared builder-core engine, not just that everything compiles.
 *
 * Uses a test-only @SpringBootApplication scanning only com.mjtrac.ballot
 * (not com.mjtrac.builderui) so building the test context doesn't also
 * construct MainFrame (a real JFrame) as a side effect — same reasoning as
 * counter's CountingServiceIntegrationTest.
 */
@SpringBootTest(classes = BuilderEndToEndTest.TestConfig.class)
class BuilderEndToEndTest {

    @SpringBootApplication(scanBasePackages = "com.mjtrac.ballot")
    @EntityScan("com.mjtrac.ballot.model")
    static class TestConfig {
    }

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideDb(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
        registry.add("ballot.export.dir", () -> tempDir.resolve("export").toString());
    }

    @Autowired JurisdictionRepository jurisdictionRepo;
    @Autowired RegionRepository regionRepo;
    @Autowired ElectionRepository electionRepo;
    @Autowired BallotTypeRepository ballotTypeRepo;
    @Autowired ContestRepository contestRepo;
    @Autowired CandidateRepository candidateRepo;
    @Autowired BallotCombinationRepository combinationRepo;
    @Autowired BallotDesignTemplateRepository templateRepo;
    @Autowired UserRepository userRepo;
    @Autowired BallotLanguageRepository languageRepo;
    @Autowired ContestTranslationRepository contestTranslationRepo;
    @Autowired CandidateTranslationRepository candidateTranslationRepo;
    @Autowired BallotGenerationService ballotService;

    @Test
    void fullCrudGraphProducesRealPdf() throws Exception {
        Jurisdiction jurisdiction = jurisdictionRepo.save(newJurisdiction());

        Region precinct = new Region();
        precinct.setJurisdiction(jurisdiction);
        precinct.setName("Precinct 7");
        precinct.setRegionType(Region.RegionType.SINGLE_PRECINCT);
        precinct = regionRepo.save(precinct);

        Election election = new Election();
        election.setJurisdiction(jurisdiction);
        election.setName("Builder Test Election");
        election.setElectionType(Election.ElectionType.GENERAL);
        election = electionRepo.save(election);

        BallotType ballotType = new BallotType();
        ballotType.setJurisdiction(jurisdiction);
        ballotType.setName("Precinct");
        ballotType = ballotTypeRepo.save(ballotType);

        Contest contest = new Contest();
        contest.setElection(election);
        contest.setTitle("Mayor");
        contest.setMaxChoices(1);
        contest.setVotingMethod(Contest.VotingMethod.PLURALITY);
        contest.setAssignedRegions(List.of(precinct));

        Candidate alice = new Candidate();
        alice.setName("Alice Johnson");
        alice.setDisplayOrder(1);
        alice.setContest(contest);
        Candidate bob = new Candidate();
        bob.setName("Bob Williams");
        bob.setDisplayOrder(2);
        bob.setContest(contest);
        contest.setCandidates(List.of(alice, bob));
        contestRepo.save(contest);

        BallotCombination combination = new BallotCombination();
        combination.setRegion(precinct);
        combination.setBallotType(ballotType);
        combination.setElection(election);
        combination = combinationRepo.save(combination);

        BallotDesignTemplate template = new BallotDesignTemplate();
        template.setElection(election);
        template.setPaperSize(BallotDesignTemplate.PaperSize.LETTER_8_5x11);
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.OVAL);
        template.setColumns(1);
        template = templateRepo.save(template);

        User printedBy = new User();
        printedBy.setUsername("builder-test");
        printedBy.setPasswordHash("unused");
        printedBy.setRoles(Set.of(User.Role.ADMIN));
        printedBy = userRepo.save(printedBy);

        // Reload everything the way the Print screen does — by id, through
        // the repositories — rather than reusing the in-memory objects, to
        // actually exercise the save-then-load round trip.
        BallotCombination reloadedCombo = combinationRepo.findById(combination.getId()).orElseThrow();
        BallotDesignTemplate reloadedTemplate = templateRepo.findById(template.getId()).orElseThrow();
        User reloadedUser = userRepo.findById(printedBy.getId()).orElseThrow();

        byte[] pdf = ballotService.generateBallot(reloadedCombo, reloadedTemplate, reloadedUser, 1);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    /**
     * Covers the workflow the new builder screens added: full Contest field
     * set (grouping label, preamble, postamble, explanatory text — not just
     * title/method/choices), the bulk-candidate-table's full field set
     * (prefix/suffix/explanatory text), and both translation flows —
     * exactly the save-then-reload operations ContestPanel,
     * ContestCandidatesDialog, ContestTranslationDialog, and
     * CandidateTranslationDialog perform. No Swing involved (that needs a
     * real screen — see the desktop GUI-automation harness's notes on the
     * macOS Accessibility permission this sandbox lacks); this proves the
     * repository-level operations those dialogs call are correct.
     */
    @Test
    void contestCandidatesAndTranslationsRoundTrip() {
        // Distinct name from newJurisdiction() — jurisdictions.name is
        // unique and both tests share one Spring context/DB for the class.
        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Translation Test County");
        jurisdiction = jurisdictionRepo.save(jurisdiction);

        BallotLanguage spanish = new BallotLanguage();
        spanish.setJurisdiction(jurisdiction);
        spanish.setLanguageCode("es");
        spanish.setLanguageName("Spanish");
        languageRepo.save(spanish);

        Election election = new Election();
        election.setJurisdiction(jurisdiction);
        election.setName("Translation Test Election");
        election.setElectionType(Election.ElectionType.GENERAL);
        election = electionRepo.save(election);

        Region precinct = new Region();
        precinct.setJurisdiction(jurisdiction);
        precinct.setName("Precinct 9");
        precinct.setRegionType(Region.RegionType.SINGLE_PRECINCT);
        precinct = regionRepo.save(precinct);

        Contest contest = new Contest();
        contest.setElection(election);
        contest.setTitle("Measure A");
        contest.setVotingMethod(Contest.VotingMethod.PLURALITY);
        contest.setMaxChoices(1);
        contest.setGroupingLabel("Measures");
        contest.setPrintGroupingLabel(true);
        contest.setPreamble("Shall the following be adopted?");
        contest.setPrintPreamble(true);
        contest.setPostamble("A YES vote approves; a NO vote rejects.");
        contest.setPrintPostamble(true);
        contest.setExplanatoryText("See voter guide page 4.");
        contest.setPrintExplanatoryText(true);
        contest.setExplanatoryTextLocation("BELOW_TITLE");
        contest = contestRepo.save(contest);
        // ContestRegionsDialog's save: re-set assignedRegions, re-save the contest.
        contest.setAssignedRegions(List.of(precinct));
        contest = contestRepo.save(contest);

        // ContestCandidatesDialog's save: mutate contest.getCandidates(), re-save the contest.
        Candidate yes = new Candidate();
        yes.setName("Yes");
        yes.setDisplayOrder(1);
        yes.setPrefixText("Vote ");
        yes.setPrintPrefixText(true);
        yes.setSuffixText(" on Measure A");
        yes.setPrintSuffixText(true);
        yes.setExplanatoryText("Approves the measure.");
        yes.setPrintExplanatoryText(true);
        yes.setContest(contest);
        contest.getCandidates().clear();
        contest.getCandidates().add(yes);
        contest = contestRepo.save(contest);

        Contest reloadedContest = contestRepo.findById(contest.getId()).orElseThrow();
        assertThat(reloadedContest.getGroupingLabel()).isEqualTo("Measures");
        assertThat(reloadedContest.getPreamble()).isEqualTo("Shall the following be adopted?");
        assertThat(reloadedContest.getPostamble()).isEqualTo("A YES vote approves; a NO vote rejects.");
        assertThat(reloadedContest.getExplanatoryText()).isEqualTo("See voter guide page 4.");
        assertThat(reloadedContest.getAssignedRegions()).extracting(Region::getId).containsExactly(precinct.getId());
        assertThat(reloadedContest.getCandidates()).hasSize(1);
        Candidate reloadedYes = reloadedContest.getCandidates().get(0);
        assertThat(reloadedYes.getPrefixText()).isEqualTo("Vote ");
        assertThat(reloadedYes.getSuffixText()).isEqualTo(" on Measure A");
        assertThat(reloadedYes.getExplanatoryText()).isEqualTo("Approves the measure.");

        // ContestTranslationDialog's save.
        ContestTranslation contestEs = new ContestTranslation();
        contestEs.setContest(reloadedContest);
        contestEs.setLanguageCode("es");
        contestEs.setTitle("Medida A");
        contestEs.setPreamble("¿Debe adoptarse lo siguiente?");
        contestTranslationRepo.save(contestEs);

        // CandidateTranslationDialog's save.
        CandidateTranslation candidateEs = new CandidateTranslation();
        candidateEs.setCandidate(reloadedYes);
        candidateEs.setLanguageCode("es");
        candidateEs.setName("Sí");
        candidateTranslationRepo.save(candidateEs);

        assertThat(contestTranslationRepo.findByContestIdAndLanguageCode(reloadedContest.getId(), "es"))
            .isPresent()
            .get().extracting(ContestTranslation::getTitle).isEqualTo("Medida A");
        assertThat(candidateTranslationRepo.findByCandidateIdAndLanguageCode(reloadedYes.getId(), "es"))
            .isPresent()
            .get().extracting(CandidateTranslation::getName).isEqualTo("Sí");
    }

    private static Jurisdiction newJurisdiction() {
        Jurisdiction j = new Jurisdiction();
        j.setName("Builder Test County");
        return j;
    }
}
