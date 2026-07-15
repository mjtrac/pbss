/*
 * Copyright (C) 2026 Mitch Trachtenberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.mjtrac.ballot.controller;

import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.repository.*;
import com.mjtrac.ballot.service.BallotGenerationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.transaction.Transactional;
import java.util.List;

/**
 * Temporary seeding endpoint: adds sample cheese/federal contests to the
 * initial seeded election, assigns them to both precinct groups, and
 * triggers ballot generation.
 *
 * POST /setup/seed-contests
 *
 * This button is visible on the dashboard and idempotent (checks whether
 * contests already exist before creating them).
 */
@Controller
@RequestMapping("/setup/seed-contests")
@PreAuthorize("hasRole('ADMIN')")
public class SeedContestsController {

    private final ElectionRepository             electionRepo;
    private final RegionRepository               regionRepo;
    private final ContestRepository              contestRepo;
    private final CandidateRepository            candidateRepo;
    private final BallotCombinationRepository    combinationRepo;
    private final BallotDesignTemplateRepository templateRepo;
    private final UserRepository                 userRepo;
    private final BallotGenerationService        ballotService;

    public SeedContestsController(
            ElectionRepository electionRepo,
            RegionRepository regionRepo,
            ContestRepository contestRepo,
            CandidateRepository candidateRepo,
            BallotCombinationRepository combinationRepo,
            BallotDesignTemplateRepository templateRepo,
            UserRepository userRepo,
            BallotGenerationService ballotService) {
        this.electionRepo  = electionRepo;
        this.regionRepo    = regionRepo;
        this.contestRepo   = contestRepo;
        this.candidateRepo = candidateRepo;
        this.combinationRepo = combinationRepo;
        this.templateRepo  = templateRepo;
        this.userRepo      = userRepo;
        this.ballotService = ballotService;
    }

    @PostMapping
    @Transactional
    public String seed(@AuthenticationPrincipal UserDetails userDetails,
                       RedirectAttributes ra) throws Exception {

        // ── Find the initial seeded election ─────────────────────────────
        Election election = electionRepo.findAll().stream()
            .filter(e -> e.getName().contains("Sample General Election"))
            .findFirst()
            .orElse(electionRepo.findAll().isEmpty() ? null : electionRepo.findAll().get(0));

        if (election == null) {
            ra.addFlashAttribute("error", "No election found. Run the initial seed first.");
            return "redirect:/";
        }

        // ── Find the precinct groups (g12 and g3) ────────────────────────
        List<Region> groups = regionRepo
            .findByJurisdictionIdAndRegionTypeOrderByName(
                election.getJurisdiction().getId(),
                Region.RegionType.PRECINCT_GROUP);

        if (groups.size() < 2) {
            ra.addFlashAttribute("error",
                "Expected at least 2 precinct groups (g12, g3). Found: " + groups.size());
            return "redirect:/";
        }
        // Both groups — assign all new contests to both
        List<Region> allGroups = List.copyOf(groups);

        // ── Idempotency guard ────────────────────────────────────────────
        boolean alreadySeeded = contestRepo.findByElectionId(election.getId())
            .stream().anyMatch(c -> "Mayor".equalsIgnoreCase(c.getTitle())
                                 || "Big Cheese".equalsIgnoreCase(c.getTitle()));
        if (alreadySeeded) {
            ra.addFlashAttribute("success",
                "Sample contests already seeded. Triggering ballot generation.");
            return generateAndRedirect(election, userDetails, ra);
        }

        // ── Fix: put "Sample Contest" (Alice/Bob) into grouping ALICE AND BOB ──
        contestRepo.findByElectionId(election.getId()).stream()
            .filter(c -> "Sample Contest".equalsIgnoreCase(c.getTitle()))
            .findFirst()
            .ifPresent(c -> {
                c.setGroupingLabel("ALICE AND BOB");
                c.setPrintGroupingLabel(true);
                c.setDisplayOrder(1);
                contestRepo.save(c);
            });

        int order = 10; // start after the existing contest (displayOrder 1)

        // ── Big Cheese contest ───────────────────────────────────────────
        Contest bigCheese = new Contest();
        bigCheese.setElection(election);
        bigCheese.setTitle("Big Cheese");
        bigCheese.setPrintableTitle("Big Cheese");
        bigCheese.setRecordTitle("Big Cheese");
        bigCheese.setVotingMethod(Contest.VotingMethod.PLURALITY);
        bigCheese.setMaxChoices(1);
        bigCheese.setDisplayOrder(order++);
        bigCheese.setInstructions("Vote for one");
        bigCheese.setGroupingLabel("CHEESES");
        bigCheese.setPrintGroupingLabel(true);
        bigCheese.setAssignedRegions(allGroups);
        bigCheese = contestRepo.save(bigCheese);

        addCandidate(bigCheese, "American",                          null,          false, 1);
        addCandidate(bigCheese, "Brie With a Very Long String to Wrap", null,       false, 2);
        addCandidate(bigCheese, "Cheddar",       "Better version of American",      false, 3);
        addCandidate(bigCheese, "Swiss",                             null,          false, 4);
        addWriteIn  (bigCheese,                                                           5);

        // ── Little Cheeses contest (max 2 votes) ─────────────────────────
        Contest littleCheese = new Contest();
        littleCheese.setElection(election);
        littleCheese.setTitle("Little Cheeses");
        littleCheese.setPrintableTitle("Little Cheeses");
        littleCheese.setRecordTitle("Little Cheeses");
        littleCheese.setVotingMethod(Contest.VotingMethod.PLURALITY);
        littleCheese.setMaxChoices(2);
        littleCheese.setDisplayOrder(order++);
        littleCheese.setInstructions("Vote for up to two");
        // No grouping label — continues under CHEESES section implicitly
        littleCheese.setAssignedRegions(allGroups);
        littleCheese = contestRepo.save(littleCheese);

        addCandidate(littleCheese, "Little American",                         null, false, 1);
        addCandidate(littleCheese, "Little Brie With a Very Long String to Wrap", null, false, 2);
        addCandidate(littleCheese, "Little Cheddar", "Better version of American", false, 3);
        addCandidate(littleCheese, "Little Swiss",                            null, false, 4);
        addWriteIn  (littleCheese,                                                        5);

        // ── President of the United States (Ranked Choice) ──────────────
        Contest president = new Contest();
        president.setElection(election);
        president.setTitle("President of the United States");
        president.setPrintableTitle("President of the United States");
        president.setRecordTitle("President of the United States");
        president.setVotingMethod(Contest.VotingMethod.RANKED_CHOICE);
        president.setMaxRankChoices(4);
        president.setDisplayOrder(order++);
        president.setGroupingLabel("FEDERAL");
        president.setPrintGroupingLabel(true);
        president.setAssignedRegions(allGroups);
        president = contestRepo.save(president);

        addCandidate(president, "George Washington", null, false, 1);
        addCandidate(president, "John Adams",        null, false, 2);
        addCandidate(president, "Thomas Jefferson",  null, false, 3);
        addCandidate(president, "James Madison",     null, false, 4);
        addWriteIn  (president,                                   5);

        // ── Representative in Congress contest ───────────────────────────
        Contest congress = new Contest();
        congress.setElection(election);
        congress.setTitle("Representative in Congress");
        congress.setPrintableTitle("Representative in Congress");
        congress.setRecordTitle("Representative in Congress");
        congress.setVotingMethod(Contest.VotingMethod.PLURALITY);
        congress.setMaxChoices(1);
        congress.setDisplayOrder(order++);
        congress.setInstructions("Vote for one");
        // No grouping label — continues under FEDERAL section
        congress.setAssignedRegions(allGroups);
        congress = contestRepo.save(congress);

        addCandidate(congress, "Alice Smith",    null, false, 1);
        addCandidate(congress, "Bill Jones",     null, false, 2);
        addCandidate(congress, "Chuck E Cheese", null, false, 3);
        addCandidate(congress, "Dorothy Johnson",null, false, 4);
        addWriteIn  (congress,                               5);

        // ── Mayor (Approval voting) ───────────────────────────────────────
        Contest mayor = new Contest();
        mayor.setElection(election);
        mayor.setTitle("Mayor");
        mayor.setPrintableTitle("Mayor");
        mayor.setRecordTitle("Mayor");
        mayor.setVotingMethod(Contest.VotingMethod.APPROVAL);
        mayor.setDisplayOrder(order++);
        mayor.setInstructions("Mark all candidates you approve of");
        mayor.setGroupingLabel("LOCAL");
        mayor.setPrintGroupingLabel(true);
        mayor.setAssignedRegions(allGroups);
        mayor = contestRepo.save(mayor);

        addCandidate(mayor, "Bill de Blasio",  null, false, 1);
        addCandidate(mayor, "Eric Adams",      null, false, 2);
        addCandidate(mayor, "Zohran Mamdani",  null, false, 3);

        ra.addFlashAttribute("success",
            "Sample contests added: Big Cheese, Little Cheeses, " +
            "President of the United States (Ranked Choice), " +
            "Representative in Congress, Mayor (Approval). " +
            "All assigned to both precinct groups. Generating ballot…");

        return generateAndRedirect(election, userDetails, ra);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void addCandidate(Contest contest, String name, String note,
                               boolean writeIn, int order) {
        Candidate c = new Candidate();
        c.setContest(contest);
        c.setName(name);
        c.setPrintableName(name);
        c.setRecordName(name);
        c.setWriteIn(writeIn);
        c.setDisplayOrder(order);
        if (note != null) {
            c.setExplanatoryText(note);
            c.setPrintExplanatoryText(true);
        }
        candidateRepo.save(c);
    }

    private void addWriteIn(Contest contest, int order) {
        Candidate wi = new Candidate();
        wi.setContest(contest);
        wi.setName("Write-In");
        wi.setPrintableName("Write-In");
        wi.setRecordName("Write-In");
        wi.setWriteIn(true);
        wi.setDisplayOrder(order);
        candidateRepo.save(wi);
    }

    private String generateAndRedirect(Election election,
                                        UserDetails userDetails,
                                        RedirectAttributes ra) throws Exception {
        // Find any combination for this election and generate its PDF
        List<BallotCombination> combos = combinationRepo.findByElectionId(election.getId());

        if (combos.isEmpty()) {
            ra.addFlashAttribute("error",
                "Contests added but no ballot combination found to generate.");
            return "redirect:/print";
        }

        BallotDesignTemplate template = templateRepo
            .findFirstByElectionIdOrderByIdAsc(election.getId())
            .orElse(null);
        if (template == null) {
            ra.addFlashAttribute("error",
                "Contests added but no ballot design template found.");
            return "redirect:/print";
        }

        User user = userRepo.findByUsername(userDetails.getUsername()).orElse(null);

        // Generate the first combination and redirect to print page
        // so the user sees the PDF selector and can download
        ra.addFlashAttribute("generatedForCombo", combos.get(0).getId());
        // Trigger generation (writes YAML/XML side effects)
        try {
            ballotService.generateBallot(combos.get(0), template, user, 1);
        } catch (Exception ex) {
            ra.addFlashAttribute("warning",
                "Contests added. Ballot generation failed: " + ex.getMessage() +
                " — go to Print Ballot to generate manually.");
        }

        return "redirect:/print";
    }
}
