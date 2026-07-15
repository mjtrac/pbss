/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.controller;

import com.mjtrac.counter.model.ScanSession;
import com.mjtrac.counter.service.ResultsQueryService;
import com.mjtrac.counter.service.ResultsQueryService.VoteRow;
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
    private final com.mjtrac.counter.service.VoteTallyService voteTally;

    public ResultsController(ResultsQueryService queryService,
                             com.mjtrac.counter.service.VoteTallyService voteTally) {
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

        // Check whether rcv_report.html has been written
        java.nio.file.Path rcvFile = java.nio.file.Paths.get(
            voteTally.getReportOutputDir(), "rcv_report.html");
        boolean rcvExists = java.nio.file.Files.exists(rcvFile);
        model.addAttribute("rcvExists", rcvExists);

        // Scribble detection summary
        try {
            long scribbledCount = queryService.totalScribbled();
            model.addAttribute("scribbledCount", scribbledCount);
            java.nio.file.Path scribbleFile = java.nio.file.Paths.get(
                voteTally.getReportOutputDir(), "scribble_report.html");
            model.addAttribute("scribbleReportExists",
                java.nio.file.Files.exists(scribbleFile));
        } catch (Exception e) {
            model.addAttribute("scribbledCount", 0L);
            model.addAttribute("scribbleReportExists", false);
        }

        return "results";
    }

    /** Serves the raw scribble_report.html for embedding in the results page. */
    @GetMapping(value = "/scribble-report",
                produces = org.springframework.http.MediaType.TEXT_HTML_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public org.springframework.http.ResponseEntity<String> scribbleReport() {
        java.nio.file.Path scribbleFile = java.nio.file.Paths.get(
            voteTally.getReportOutputDir(), "scribble_report.html");
        if (!java.nio.file.Files.exists(scribbleFile))
            return org.springframework.http.ResponseEntity.notFound().build();
        try {
            String html = java.nio.file.Files.readString(scribbleFile);
            return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.TEXT_HTML)
                .body(html);
        } catch (java.io.IOException e) {
            return org.springframework.http.ResponseEntity
                .internalServerError().build();
        }
    }

    /**
     * Serves the red-outlined scribble image for a given ballot image id,
     * for inline embedding (thumbnail/full view) in scribble_report.html.
     */
    @GetMapping("/scribble-image")
    public org.springframework.http.ResponseEntity<byte[]> scribbleImage(
            @org.springframework.web.bind.annotation.RequestParam Long id) {
        String outlinePath = queryService.scribbledBallots().stream()
            .filter(r -> r.getImageId() == id)
            .map(ResultsQueryService.ScribbleRow::getOutlineImagePath)
            .findFirst()
            .orElse(null);

        if (outlinePath == null || outlinePath.isBlank())
            return org.springframework.http.ResponseEntity.notFound().build();

        java.nio.file.Path file = java.nio.file.Paths.get(outlinePath);
        if (!java.nio.file.Files.exists(file))
            return org.springframework.http.ResponseEntity.notFound().build();

        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file);
            return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.IMAGE_PNG)
                .cacheControl(org.springframework.http.CacheControl.noCache())
                .body(bytes);
        } catch (java.io.IOException e) {
            return org.springframework.http.ResponseEntity.internalServerError().build();
        }
    }

    /** Serves the raw rcv_report.html for embedding in the results page. */
    @GetMapping(value = "/rcv-report",
                produces = org.springframework.http.MediaType.TEXT_HTML_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public org.springframework.http.ResponseEntity<String> rcvReport() {
        java.nio.file.Path rcvFile = java.nio.file.Paths.get(
            voteTally.getReportOutputDir(), "rcv_report.html");
        if (!java.nio.file.Files.exists(rcvFile))
            return org.springframework.http.ResponseEntity.notFound().build();
        try {
            String html = java.nio.file.Files.readString(rcvFile);
            return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.TEXT_HTML)
                .body(html);
        } catch (java.io.IOException e) {
            return org.springframework.http.ResponseEntity
                .internalServerError().build();
        }
    }

    /** Group a flat VoteRow list by contest, preserving insertion order. */
    private Map<String, List<VoteRow>> groupByContest(List<VoteRow> rows) {
        Map<String, List<VoteRow>> map = new LinkedHashMap<>();
        for (VoteRow r : rows)
            map.computeIfAbsent(r.contest, k -> new ArrayList<>()).add(r);
        return map;
    }
}
