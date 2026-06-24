/*
 * Copyright (C) 2026 Mitch Trachtenberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package gov.election.ballot;

import gov.election.ballot.model.*;
import gov.election.ballot.service.BallotGenerationService;
import gov.election.ballot.service.ContestAssignmentService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("sqlite")
class BallotGenerationTest {

    @Autowired BallotGenerationService ballotService;
    @MockBean  ContestAssignmentService assignmentService;

    private BallotCombination    combo;
    private BallotDesignTemplate template;
    private User                 testUser;
    private Contest              contest;

    @BeforeEach
    void setUp() {
        Jurisdiction jur = new Jurisdiction();
        jur.setId(1L);
        jur.setName("Test County");
        jur.setGeneralVotingInstructions("Fill in the oval completely.");

        // SINGLE_PRECINCT region
        Region precinct = new Region();
        precinct.setId(1L);
        precinct.setJurisdiction(jur);
        precinct.setName("Precinct 1");
        precinct.setRegionType(Region.RegionType.SINGLE_PRECINCT);

        Election election = new Election();
        election.setId(1L);
        election.setJurisdiction(jur);
        election.setName("Test General Election 2026");
        election.setElectionType(Election.ElectionType.GENERAL);

        BallotType bt = new BallotType();
        bt.setId(1L);
        bt.setName("Precinct");
        bt.setJurisdiction(jur);

        contest = new Contest();
        contest.setId(1L);
        contest.setTitle("Mayor");
        contest.setMaxChoices(1);
        contest.setVotingMethod(Contest.VotingMethod.PLURALITY);
        contest.setDisplayOrder(1);
        contest.setElection(election);

        Candidate c1 = new Candidate();
        c1.setId(1L); c1.setName("Alice Johnson"); c1.setDisplayOrder(1); c1.setContest(contest);

        Candidate c2 = new Candidate();
        c2.setId(2L); c2.setName("Bob Williams"); c2.setDisplayOrder(2); c2.setContest(contest);

        Candidate wi = new Candidate();
        wi.setId(3L); wi.setName("Write-In"); wi.setWriteIn(true); wi.setDisplayOrder(3); wi.setContest(contest);

        contest.setCandidates(List.of(c1, c2, wi));

        combo = new BallotCombination();
        combo.setId(1L);
        combo.setRegion(precinct);
        combo.setBallotType(bt);
        combo.setElection(election);

        template = new BallotDesignTemplate();
        template.setElection(election);
        template.setPaperSize(BallotDesignTemplate.PaperSize.LETTER_8_5x11);
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.OVAL);
        template.setColumns(3);

        Set<User.Role> roles = new HashSet<>();
        roles.add(User.Role.PRINTER);
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("tester");
        testUser.setRoles(roles);

        when(assignmentService.resolveContestsForPrecinct(anyLong(), anyLong()))
            .thenReturn(List.of(contest));
    }

    @Test
    @DisplayName("generateBallot returns non-empty PDF bytes starting with %PDF")
    void testGenerateReturnsPdf() throws Exception {
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("generateBallot calls ContestAssignmentService with correct region and election IDs")
    void testAssignmentServiceIsInvoked() throws Exception {
        ballotService.generateBallot(combo, template, testUser, 1);
        verify(assignmentService).resolveContestsForPrecinct(
            combo.getRegion().getId(), combo.getElection().getId());
    }

    @Test
    @DisplayName("generateBallot produces PDF even when no contests are assigned")
    void testEmptyContestList() throws Exception {
        when(assignmentService.resolveContestsForPrecinct(anyLong(), anyLong()))
            .thenReturn(List.of());
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("generateBallot throws IllegalArgumentException when combination is null")
    void testNullCombinationThrows() {
        assertThatThrownBy(() -> ballotService.generateBallot(null, template, testUser, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("generateBallot throws IllegalArgumentException when template is null")
    void testNullTemplateThrows() {
        assertThatThrownBy(() -> ballotService.generateBallot(combo, null, testUser, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("generateBallot throws IllegalArgumentException when user is null")
    void testNullUserThrows() {
        assertThatThrownBy(() -> ballotService.generateBallot(combo, template, null, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("LEGAL paper size generates valid PDF")
    void testLegalPaperSize() throws Exception {
        template.setPaperSize(BallotDesignTemplate.PaperSize.LEGAL_8_5x14);
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("A4 paper size generates valid PDF")
    void testA4PaperSize() throws Exception {
        template.setPaperSize(BallotDesignTemplate.PaperSize.A4);
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("Ranked-choice voting method with NUMBER_FIELD style generates valid PDF")
    void testRankedChoice() throws Exception {
        contest.setVotingMethod(Contest.VotingMethod.RANKED_CHOICE);
        contest.setMaxRankChoices(3);
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.NUMBER_FIELD);
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(pdf).isNotEmpty();
    }

    @Test
    @DisplayName("CHECKBOX vote indicator style generates valid PDF")
    void testCheckboxStyle() throws Exception {
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.CHECKBOX);
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("Multiple contests on a ballot render without error")
    void testMultipleContests() throws Exception {
        Contest c2 = new Contest();
        c2.setId(2L); c2.setTitle("City Council"); c2.setMaxChoices(2);
        c2.setVotingMethod(Contest.VotingMethod.PLURALITY); c2.setDisplayOrder(2);
        c2.setElection(combo.getElection()); c2.setCandidates(List.of());

        when(assignmentService.resolveContestsForPrecinct(anyLong(), anyLong()))
            .thenReturn(List.of(contest, c2));

        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("Uniform ballot election flag does not break PDF generation")
    void testUniformBallot() throws Exception {
        combo.getElection().setUniformBallot(true);
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("Candidate with explanatory text generates valid PDF")
    void testCandidateExplanatoryText() throws Exception {
        contest.getCandidates().get(0).setExplanatoryText("Incumbent since 2018");
        contest.getCandidates().get(0).setPrintExplanatoryText(true);
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }
}
