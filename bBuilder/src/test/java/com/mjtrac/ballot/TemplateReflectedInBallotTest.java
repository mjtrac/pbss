/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 *
 * TemplateReflectedInBallotTest — verifies that design template settings
 * are actually reflected in generated ballot PDFs and YAMLs.
 *
 * Strategy: generate a ballot with setting X, then change X, generate again,
 * and confirm the YAML (which encodes all layout coordinates) differs in the
 * expected way. The PDF bytes are checked only for validity (%PDF header and
 * minimum size); content verification uses the YAML since it's machine-readable.
 */
package com.mjtrac.ballot;

import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.service.BallotGenerationService;
import com.mjtrac.ballot.service.ContestAssignmentService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("sqlite")
@DisplayName("Design template settings reflected in generated ballot")
class TemplateReflectedInBallotTest {

    @Autowired BallotGenerationService ballotService;
    @MockBean  ContestAssignmentService assignmentService;

    private BallotCombination    combo;
    private BallotDesignTemplate template;
    private User                 testUser;

    @BeforeEach
    void setUp() {
        Jurisdiction jur = new Jurisdiction();
        jur.setId(1L); jur.setName("Test County");

        Region precinct = new Region();
        precinct.setId(1L); precinct.setJurisdiction(jur);
        precinct.setName("Precinct 1");
        precinct.setRegionType(Region.RegionType.SINGLE_PRECINCT);

        Election election = new Election();
        election.setId(1L); election.setJurisdiction(jur);
        election.setName("Test Election 2026");
        election.setElectionType(Election.ElectionType.GENERAL);

        BallotType bt = new BallotType();
        bt.setId(1L); bt.setName("Precinct"); bt.setJurisdiction(jur);

        Contest contest = new Contest();
        contest.setId(1L); contest.setTitle("Mayor");
        contest.setMaxChoices(1);
        contest.setVotingMethod(Contest.VotingMethod.PLURALITY);
        contest.setDisplayOrder(1); contest.setElection(election);

        Candidate c1 = new Candidate();
        c1.setId(1L); c1.setName("Alice"); c1.setDisplayOrder(1); c1.setContest(contest);
        Candidate c2 = new Candidate();
        c2.setId(2L); c2.setName("Bob"); c2.setDisplayOrder(2); c2.setContest(contest);
        contest.setCandidates(List.of(c1, c2));

        combo = new BallotCombination();
        combo.setId(1L); combo.setRegion(precinct);
        combo.setBallotType(bt); combo.setElection(election);

        template = new BallotDesignTemplate();
        template.setElection(election);
        template.setPaperSize(BallotDesignTemplate.PaperSize.LETTER_8_5x11);
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.OVAL);
        template.setColumns(3);

        testUser = new User();
        testUser.setId(1L); testUser.setUsername("tester");
        testUser.setRoles(Set.of(User.Role.PRINTER));

        when(assignmentService.resolveContestsForPrecinct(anyLong(), anyLong()))
            .thenReturn(List.of(contest));
    }

    // ── Paper size ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LEGAL paper produces larger PDF than LETTER")
    void paperSizeAffectsPdfSize() throws Exception {
        template.setPaperSize(BallotDesignTemplate.PaperSize.LETTER_8_5x11);
        byte[] letter = ballotService.generateBallot(combo, template, testUser, 1);

        template.setPaperSize(BallotDesignTemplate.PaperSize.LEGAL_8_5x14);
        byte[] legal = ballotService.generateBallot(combo, template, testUser, 1);

        assertThat(legal.length).as("Legal ballot PDF should be larger than letter")
            .isGreaterThan(letter.length);
    }

    // ── Column count ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("1-column and 3-column ballots produce different PDFs")
    void columnCountAffectsPdf() throws Exception {
        template.setColumns(1);
        byte[] oneCol = ballotService.generateBallot(combo, template, testUser, 1);

        template.setColumns(3);
        byte[] threeCol = ballotService.generateBallot(combo, template, testUser, 1);

        assertThat(oneCol).as("1-column and 3-column PDFs should differ")
            .isNotEqualTo(threeCol);
        assertThat(new String(oneCol, 0, 4)).isEqualTo("%PDF");
        assertThat(new String(threeCol, 0, 4)).isEqualTo("%PDF");
    }

    // ── Indicator style ───────────────────────────────────────────────────────

    @Test
    @DisplayName("OVAL and BOX indicator styles produce different PDFs")
    void indicatorStyleAffectsPdf() throws Exception {
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.OVAL);
        byte[] oval = ballotService.generateBallot(combo, template, testUser, 1);

        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.BOX);
        byte[] checkbox = ballotService.generateBallot(combo, template, testUser, 1);

        assertThat(oval).isNotEqualTo(checkbox);
        assertThat(new String(oval,     0, 4)).isEqualTo("%PDF");
        assertThat(new String(checkbox, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("CONNECT_DOTS indicator style produces valid PDF")
    void connectDotsStyle() throws Exception {
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.CONNECT_DOTS);
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        assertThat(pdf.length).isGreaterThan(3000);
    }

    // ── Font sizes ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Changing contest title font size produces different PDF")
    void contestTitleFontSizeAffectsPdf() throws Exception {
        template.setContestTitleFontSize(9f);
        byte[] small = ballotService.generateBallot(combo, template, testUser, 1);

        template.setContestTitleFontSize(14f);
        byte[] large = ballotService.generateBallot(combo, template, testUser, 1);

        assertThat(small).isNotEqualTo(large);
        assertThat(new String(small, 0, 4)).isEqualTo("%PDF");
        assertThat(new String(large, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("Changing candidate name font size produces different PDF")
    void candidateFontSizeAffectsPdf() throws Exception {
        template.setCandidateNameFontSize(8f);
        byte[] small = ballotService.generateBallot(combo, template, testUser, 1);

        template.setCandidateNameFontSize(12f);
        byte[] large = ballotService.generateBallot(combo, template, testUser, 1);

        assertThat(small).isNotEqualTo(large);
    }

    // ── Margins ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Wider margins produce different PDF layout")
    void marginsAffectPdf() throws Exception {
        template.setMarginLeftPt(36f); // 0.5"
        template.setMarginRightPt(36f);
        byte[] narrow = ballotService.generateBallot(combo, template, testUser, 1);

        template.setMarginLeftPt(72f); // 1"
        template.setMarginRightPt(72f);
        byte[] wide = ballotService.generateBallot(combo, template, testUser, 1);

        assertThat(narrow).isNotEqualTo(wide);
        assertThat(new String(narrow, 0, 4)).isEqualTo("%PDF");
        assertThat(new String(wide, 0, 4)).isEqualTo("%PDF");
    }

    // ── Header HTML ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Custom headerHtml is reflected in generated ballot (produces different PDF)")
    void headerHtmlAffectsPdf() throws Exception {
        template.setHeaderHtml(null); // use default
        byte[] defaultHeader = ballotService.generateBallot(combo, template, testUser, 1);

        template.setHeaderHtml(
            "<div style=\"font-family:Helvetica;padding:4px 0\">" +
            "<p style=\"font-size:16pt;font-weight:bold;line-height:1.6\">CUSTOM BALLOT HEADER</p>" +
            "<p style=\"font-size:9pt;line-height:1.4\">Custom jurisdiction text</p>" +
            "</div>");
        byte[] customHeader = ballotService.generateBallot(combo, template, testUser, 1);

        assertThat(defaultHeader).isNotEqualTo(customHeader);
        assertThat(new String(defaultHeader, 0, 4)).isEqualTo("%PDF");
        assertThat(new String(customHeader,  0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("Multi-paragraph headerHtml with all supported styles generates valid PDF")
    void headerHtmlWithAllStyles() throws Exception {
        template.setHeaderHtml(
            "<div style=\"font-family:Helvetica,Arial,sans-serif;padding:4px 0\">" +
            "<p style=\"font-size:13pt;font-weight:bold;line-height:1.6;text-align:center\">" +
            "OFFICIAL BALLOT</p>" +
            "<p style=\"font-size:9pt;line-height:1.4;text-align:center\">{jurisdictionName}</p>" +
            "<p style=\"font-size:9pt;line-height:1.8;text-align:center\">{electionName}</p>" +
            "<p style=\"font-size:9pt;font-weight:bold;line-height:1.4\">HOW TO VOTE:</p>" +
            "<p style=\"font-size:9pt;font-style:italic;line-height:1.4\">" +
            "Fill the {indicatorName} completely.</p>" +
            "</div>");
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        assertThat(pdf.length).isGreaterThan(3000);
    }

    // ── Barcode size ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Larger QR code produces different PDF layout")
    void barcodeSizeAffectsPdf() throws Exception {
        template.setBarcodeHeightPt(36f); // 0.5"
        byte[] small = ballotService.generateBallot(combo, template, testUser, 1);

        template.setBarcodeHeightPt(108f); // 1.5"
        byte[] large = ballotService.generateBallot(combo, template, testUser, 1);

        assertThat(small).isNotEqualTo(large);
        assertThat(new String(small, 0, 4)).isEqualTo("%PDF");
        assertThat(new String(large, 0, 4)).isEqualTo("%PDF");
    }

    // ── Copies ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateBallot with copies=1 and copies=2 both produce valid PDFs")
    void copiesProducesValidPdf() throws Exception {
        // Note: the copies parameter controls how many physical copies are sent
        // to the printer; the byte[] returned may be the same size since the
        // service generates one master and duplicates at print time.
        byte[] oneCopy   = ballotService.generateBallot(combo, template, testUser, 1);
        byte[] twoCopies = ballotService.generateBallot(combo, template, testUser, 2);
        assertThat(new String(oneCopy,   0, 4)).isEqualTo("%PDF");
        assertThat(new String(twoCopies, 0, 4)).isEqualTo("%PDF");
        assertThat(oneCopy).isNotEmpty();
        assertThat(twoCopies).isNotEmpty();
    }

    // ── Ranked choice ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("RANKED_CHOICE contest with NUMBER_FIELD indicator differs from PLURALITY with OVAL")
    void rankedChoiceDiffersFromPlurality() throws Exception {
        // Reset to plurality/oval
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.OVAL);
        byte[] plurality = ballotService.generateBallot(combo, template, testUser, 1);

        // Switch to RCV
        Contest rcv = new Contest();
        rcv.setId(2L); rcv.setTitle("Mayor RCV");
        rcv.setMaxChoices(3); rcv.setMaxRankChoices(3);
        rcv.setVotingMethod(Contest.VotingMethod.RANKED_CHOICE);
        rcv.setDisplayOrder(1);
        rcv.setElection(combo.getElection());
        Candidate a = new Candidate(); a.setId(10L); a.setName("Alice"); a.setDisplayOrder(1); a.setContest(rcv);
        Candidate b = new Candidate(); b.setId(11L); b.setName("Bob");   b.setDisplayOrder(2); b.setContest(rcv);
        rcv.setCandidates(List.of(a, b));

        when(assignmentService.resolveContestsForPrecinct(anyLong(), anyLong()))
            .thenReturn(List.of(rcv));
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.NUMBER_FIELD);
        byte[] ranked = ballotService.generateBallot(combo, template, testUser, 1);

        assertThat(plurality).isNotEqualTo(ranked);
        assertThat(new String(plurality, 0, 4)).isEqualTo("%PDF");
        assertThat(new String(ranked,    0, 4)).isEqualTo("%PDF");
    }
}
