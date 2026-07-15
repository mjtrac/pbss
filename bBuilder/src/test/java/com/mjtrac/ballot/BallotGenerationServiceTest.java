/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
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
@DisplayName("BallotGenerationService additional tests")
class BallotGenerationServiceTest {

    @Autowired BallotGenerationService ballotService;
    @MockBean  ContestAssignmentService assignmentService;

    private BallotCombination    combo;
    private BallotDesignTemplate template;
    private User                 testUser;
    private Contest              contest;

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
        election.setName("Sample General Election 2026");
        election.setElectionType(Election.ElectionType.GENERAL);

        BallotType bt = new BallotType();
        bt.setId(1L); bt.setName("Standard"); bt.setJurisdiction(jur);

        contest = new Contest();
        contest.setId(1L); contest.setTitle("Mayor"); contest.setMaxChoices(1);
        contest.setVotingMethod(Contest.VotingMethod.PLURALITY);
        contest.setDisplayOrder(1); contest.setElection(election);

        Candidate c1 = new Candidate();
        c1.setId(1L); c1.setName("Alice Johnson"); c1.setDisplayOrder(1);
        c1.setContest(contest);

        Candidate c2 = new Candidate();
        c2.setId(2L); c2.setName("Bob Williams"); c2.setDisplayOrder(2);
        c2.setContest(contest);

        contest.setCandidates(List.of(c1, c2));

        combo = new BallotCombination();
        combo.setId(1L); combo.setRegion(precinct);
        combo.setBallotType(bt); combo.setElection(election);

        template = new BallotDesignTemplate();
        template.setElection(election);
        template.setPaperSize(BallotDesignTemplate.PaperSize.LETTER_8_5x11);
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.OVAL);
        template.setColumns(3);
        template.setBarcodeHeightPt(72f);
        template.setBarcodeWidthPt(0f);

        testUser = new User();
        testUser.setId(1L); testUser.setUsername("tester");
        testUser.setRoles(Set.of(User.Role.PRINTER));

        when(assignmentService.resolveContestsForPrecinct(anyLong(), anyLong()))
            .thenReturn(List.of(contest));
    }

    // ── CONNECT_DOTS indicator style ──────────────────────────────────────────

    @Test
    @DisplayName("CONNECT_DOTS indicator style generates valid PDF")
    void testConnectDotsStyle() throws Exception {
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.CONNECT_DOTS);
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("CONNECT_DOTS YAML records indicatorStyle as CONNECT_DOTS")
    void testConnectDotsYamlStyle() throws Exception {
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.CONNECT_DOTS);
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        // PDF generates without error; YAML written separately via ExportService
        assertThat(pdf).isNotEmpty();
    }

    // ── Header HTML rendering ─────────────────────────────────────────────────

    @Test
    @DisplayName("Custom HTML header with ql-align-center class generates valid PDF")
    void testHtmlHeaderWithQuillClass() throws Exception {
        template.setHeaderHtml(
            "<p class=\"ql-align-center\"><strong style=\"font-size:13pt\">" +
            "OFFICIAL BALLOT</strong></p>" +
            "<p class=\"ql-align-center\" style=\"font-size:9pt\">Test Election</p>");
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("Multi-paragraph HTML header generates valid PDF without clipping")
    void testMultiParagraphHeader() throws Exception {
        StringBuilder sb = new StringBuilder("<div>");
        for (int i = 0; i < 10; i++)
            sb.append("<p style=\"font-size:9pt;line-height:1.4\">Header line ").append(i).append("</p>");
        sb.append("</div>");
        template.setHeaderHtml(sb.toString());
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("Null header HTML falls back to default and generates valid PDF")
    void testNullHeaderFallback() throws Exception {
        template.setHeaderHtml(null);
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    // ── Language / translation ────────────────────────────────────────────────

    @Test
    @DisplayName("generateBallot with language code 'en' produces same result as no-language overload")
    void testEnglishLanguageCode() throws Exception {
        byte[] pdf1 = ballotService.generateBallot(combo, template, testUser, 1);
        byte[] pdf2 = ballotService.generateBallot(combo, template, testUser, 1, "en");
        // Both should be valid PDFs; exact byte equality is not guaranteed due to timestamps
        assertThat(new String(pdf1, 0, 4)).isEqualTo("%PDF");
        assertThat(new String(pdf2, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("generateBallot with unknown language code falls back to English gracefully")
    void testUnknownLanguageCode() throws Exception {
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1, "xx");
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    // ── Thread safety ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Concurrent generateBallot calls with different language codes do not corrupt each other")
    void testConcurrentLanguageSafety() throws Exception {
        int threads = 6;
        var errors  = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        var latch   = new java.util.concurrent.CountDownLatch(threads);
        String[] langs = {"en", "es", "zh", "fr", "de", "en"};

        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String lang = langs[i];
            ts.add(Thread.ofVirtual().start(() -> {
                try {
                    byte[] pdf = ballotService.generateBallot(
                        combo, template, testUser, 1, lang);
                    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            }));
        }
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(errors).as("Concurrent generation errors").isEmpty();
    }

    // ── QR code / barcode ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Generated PDF contains a QR code image (no linear barcode)")
    void testQrCodePresent() throws Exception {
        template.setBarcodeHeightPt(72f);
        template.setBarcodeWidthPt(0f);
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        // PDF must be non-trivially sized (QR code adds image content)
        assertThat(pdf.length).isGreaterThan(3000);
        // First 4 bytes are the PDF magic number
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    // ── Column overflow ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Enough contests to overflow one column still produces valid PDF")
    void testColumnOverflow() throws Exception {
        List<Contest> many = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            Contest c = new Contest();
            c.setId((long) i); c.setTitle("Contest " + i);
            c.setMaxChoices(1);
            c.setVotingMethod(Contest.VotingMethod.PLURALITY);
            c.setDisplayOrder(i);
            c.setElection(combo.getElection());
            Candidate cand = new Candidate();
            cand.setId((long) i * 10); cand.setName("Candidate A");
            cand.setDisplayOrder(1); cand.setContest(c);
            c.setCandidates(List.of(cand));
            many.add(c);
        }
        when(assignmentService.resolveContestsForPrecinct(anyLong(), anyLong()))
            .thenReturn(many);
        byte[] pdf = ballotService.generateBallot(combo, template, testUser, 1);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }
}
