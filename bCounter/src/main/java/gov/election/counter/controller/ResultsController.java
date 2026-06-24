/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.counter.controller;

import gov.election.counter.model.ScanSession;
import gov.election.counter.service.ResultsQueryService;
import gov.election.counter.service.ResultsQueryService.VoteRow;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpSession;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Serves the /results page — queries the database and presents
 * vote totals by precinct, party, contest, and candidate.
 */
@Controller
public class ResultsController {

    private static final String SESSION_KEY = "scanSession";

    private final ResultsQueryService queryService;
    private final gov.election.counter.service.VoteTallyService voteTally;

    public ResultsController(ResultsQueryService queryService,
                             gov.election.counter.service.VoteTallyService voteTally) {
        this.queryService = queryService;
        this.voteTally    = voteTally;
    }

    @GetMapping("/results")
    public String results(HttpSession httpSession, Model model,
                          @AuthenticationPrincipal UserDetails userDetails) {
        ScanSession session = (ScanSession) httpSession.getAttribute(SESSION_KEY);

        boolean scanning  = session != null && session.isStarted() && !session.isComplete();
        boolean hasMore   = session != null && !session.isComplete();
        int processed     = session != null ? session.processed()    : 0;
        int total         = session != null ? session.totalImages()  : 0;

        model.addAttribute("scanning",  scanning);
        model.addAttribute("hasMore",   hasMore);
        model.addAttribute("processed", processed);
        model.addAttribute("total",     total);
        model.addAttribute("username",
            userDetails != null ? userDetails.getUsername() : "");

        // ── Query and group results (guard against empty/missing DB) ──────────
        try {
            model.addAttribute("byContest",
                groupByContest(queryService.votesByContest()));
            model.addAttribute("byPrecinct",
                groupByContest(queryService.votesByContestAndPrecinct()));
            model.addAttribute("byParty",
                groupByContest(queryService.votesByContestAndParty()));
            model.addAttribute("totalVotes",     queryService.totalVotesCast());
            model.addAttribute("totalBallots",   queryService.totalBallotImages());
            model.addAttribute("overvotedCount", queryService.totalOvervoted());
        } catch (Exception e) {
            model.addAttribute("byContest",    new java.util.LinkedHashMap<>());
            model.addAttribute("byPrecinct",   new java.util.LinkedHashMap<>());
            model.addAttribute("byParty",      new java.util.LinkedHashMap<>());
            model.addAttribute("totalVotes",     0);
            model.addAttribute("totalBallots",   0);
            model.addAttribute("overvotedCount", 0);
            model.addAttribute("dbMessage",
                "No scan results available yet. Run a scan first.");
            model.addAttribute("reportPending", false);
        }

        // Check whether results_report.html has been written yet
        java.nio.file.Path reportFile = java.nio.file.Paths.get(
            voteTally.getReportOutputDir(), "results_report.html");
        boolean reportExists = java.nio.file.Files.exists(reportFile);
        model.addAttribute("reportExists",  reportExists);
        model.addAttribute("reportInterval", voteTally.getReportInterval());
        model.addAttribute("reportOutputDir", voteTally.getReportOutputDir());
        if (!model.containsAttribute("reportPending"))
            model.addAttribute("reportPending", !reportExists && scanning);

        return "results";
    }

    /** Group a flat VoteRow list by contest, preserving insertion order. */
    private Map<String, List<VoteRow>> groupByContest(List<VoteRow> rows) {
        Map<String, List<VoteRow>> map = new LinkedHashMap<>();
        for (VoteRow r : rows)
            map.computeIfAbsent(r.contest, k -> new ArrayList<>()).add(r);
        return map;
    }
}
