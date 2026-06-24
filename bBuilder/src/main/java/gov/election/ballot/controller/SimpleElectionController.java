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
package gov.election.ballot.controller;

import gov.election.ballot.model.*;
import gov.election.ballot.repository.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

/**
 * Quick Election Setup — creates a fully print-ready election in one form submit.
 *
 * Everything needed to generate and print a ballot is created automatically:
 *   - An Election (name defaults to "Election")
 *   - A Party named "All" (created if none exists for this jurisdiction)
 *   - A SinglePrecinct region named "All" (created if none exists)
 *   - A BallotType named "Precinct" (created if none exists)
 *   - A BallotDesignTemplate with defaults
 *   - A BallotCombination linking all of the above
 *   - A Contest named "EditMe" with two candidates "EditMe1" and "EditMe2",
 *     with explanatory text prompting the user to update names and settings
 *
 * After creation the user is redirected directly to the contest edit screen.
 */
@Controller
@RequestMapping("/setup/simple-election")
@PreAuthorize("hasAnyRole('ADMIN','DATA_ENTRY')")
public class SimpleElectionController {
    private static final java.util.List<gov.election.ballot.model.BallotDesignTemplate.VoteIndicatorStyle>
        SUPPORTED_INDICATOR_STYLES = java.util.Arrays.stream(
            gov.election.ballot.model.BallotDesignTemplate.VoteIndicatorStyle.values())
        .filter(s -> s != gov.election.ballot.model.BallotDesignTemplate.VoteIndicatorStyle.ARROW
                  && s != gov.election.ballot.model.BallotDesignTemplate.VoteIndicatorStyle.NUMBER_FIELD)
        .toList();


    private final JurisdictionRepository         jurisdictionRepo;
    private final ElectionRepository             electionRepo;
    private final RegionRepository               regionRepo;
    private final PartyRepository                partyRepo;
    private final BallotTypeRepository           ballotTypeRepo;
    private final BallotCombinationRepository    combinationRepo;
    private final BallotDesignTemplateRepository templateRepo;
    private final ContestRepository              contestRepo;
    private final CandidateRepository            candidateRepo;

    public SimpleElectionController(
            JurisdictionRepository jurisdictionRepo,
            ElectionRepository electionRepo,
            RegionRepository regionRepo,
            PartyRepository partyRepo,
            BallotTypeRepository ballotTypeRepo,
            BallotCombinationRepository combinationRepo,
            BallotDesignTemplateRepository templateRepo,
            ContestRepository contestRepo,
            CandidateRepository candidateRepo) {
        this.jurisdictionRepo = jurisdictionRepo;
        this.electionRepo     = electionRepo;
        this.regionRepo       = regionRepo;
        this.partyRepo        = partyRepo;
        this.ballotTypeRepo   = ballotTypeRepo;
        this.combinationRepo  = combinationRepo;
        this.templateRepo     = templateRepo;
        this.contestRepo      = contestRepo;
        this.candidateRepo    = candidateRepo;
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("jurisdictions",   jurisdictionRepo.findAll());
        model.addAttribute("electionTypes",   Election.ElectionType.values());
        model.addAttribute("paperSizes",      BallotDesignTemplate.PaperSize.values());
        model.addAttribute("indicatorStyles", SUPPORTED_INDICATOR_STYLES);
        return "setup/simple-election";
    }

    @PostMapping("/create")
    public String create(
            @RequestParam(required = false) Long   jurisdictionId,
            @RequestParam(required = false, defaultValue = "Election") String electionName,
            @RequestParam(required = false) String electionDate,
            @RequestParam(required = false) String electionType,
            @RequestParam(required = false) String paperSize,
            @RequestParam(required = false) String voteIndicatorStyle,
            @RequestParam(defaultValue = "2") int columns,
            RedirectAttributes ra,
            Model model) {

        // ── Validate required inputs ──────────────────────────────────────
        if (jurisdictionId == null) {
            return error("Please select a jurisdiction.", model);
        }
        if (paperSize == null || paperSize.isBlank()) {
            return error("Please select a paper size.", model);
        }
        if (voteIndicatorStyle == null || voteIndicatorStyle.isBlank()) {
            return error("Please select a vote indicator style.", model);
        }

        Jurisdiction jur = jurisdictionRepo.findById(jurisdictionId).orElse(null);
        if (jur == null) {
            return error("Jurisdiction not found — please choose again.", model);
        }

        String name = (electionName == null || electionName.isBlank()) ? "Election" : electionName.trim();

        // ── Party: find "All", or create it ──────────────────────────────
        Party party = partyRepo.findByJurisdictionIdOrderByName(jurisdictionId)
            .stream()
            .filter(p -> "All".equalsIgnoreCase(p.getName()))
            .findFirst()
            .orElseGet(() -> {
                Party p = new Party();
                p.setJurisdiction(jur);
                p.setName("All");
                p.setAbbreviation("ALL");
                return partyRepo.save(p);
            });

        // ── SinglePrecinct region: find "All", or create it ───────────────
        Region precinct = regionRepo
            .findByJurisdictionIdAndRegionTypeOrderByName(
                jurisdictionId, Region.RegionType.SINGLE_PRECINCT)
            .stream()
            .filter(r -> "All".equalsIgnoreCase(r.getName()))
            .findFirst()
            .orElseGet(() -> {
                Region r = new Region();
                r.setJurisdiction(jur);
                r.setName("All");
                r.setRegionType(Region.RegionType.SINGLE_PRECINCT);
                r.setDescription("Default single-precinct region created by Quick Setup");
                return regionRepo.save(r);
            });

        // ── BallotType: find "Precinct", or create it ─────────────────────
        BallotType ballotType = ballotTypeRepo.findByJurisdictionIdOrderByName(jurisdictionId)
            .stream()
            .filter(bt -> "Precinct".equalsIgnoreCase(bt.getName()))
            .findFirst()
            .orElseGet(() -> {
                BallotType bt = new BallotType();
                bt.setJurisdiction(jur);
                bt.setName("Precinct");
                bt.setDescription("Default ballot type created by Quick Setup");
                return ballotTypeRepo.save(bt);
            });

        // ── Election ───────────────────────────────────────────────────────
        Election election = new Election();
        election.setJurisdiction(jur);
        election.setName(name);
        election.setUniformBallot(true);
        election.setElectionType(
            electionType != null && !electionType.isBlank()
                ? Election.ElectionType.valueOf(electionType)
                : Election.ElectionType.GENERAL);
        if (electionDate != null && !electionDate.isBlank()) {
            try { election.setElectionDate(LocalDate.parse(electionDate)); }
            catch (Exception ignored) {}
        }
        election = electionRepo.save(election);

        // ── BallotDesignTemplate (if not already present for this election) ─
        if (templateRepo.findFirstByElectionIdOrderByIdAsc(election.getId()).isEmpty()) {
            BallotDesignTemplate tmpl = new BallotDesignTemplate();
            tmpl.setElection(election);
            tmpl.setPaperSize(BallotDesignTemplate.PaperSize.valueOf(paperSize));
            BallotDesignTemplate.VoteIndicatorStyle style =
                BallotDesignTemplate.VoteIndicatorStyle.valueOf(voteIndicatorStyle);
            if (style == BallotDesignTemplate.VoteIndicatorStyle.ARROW
                    || style == BallotDesignTemplate.VoteIndicatorStyle.NUMBER_FIELD) {
                ra.addFlashAttribute("error", style.name() + " indicator style is not yet available.");
                return "redirect:/setup/simple-election";
            }
            tmpl.setVoteIndicatorStyle(style);
            tmpl.setColumns(Math.max(1, Math.min(5, columns)));
            templateRepo.save(tmpl);
        }

        // ── BallotCombination ─────────────────────────────────────────────
        BallotCombination combo = new BallotCombination();
        combo.setElection(election);
        combo.setRegion(precinct);
        combo.setParty(party);
        combo.setBallotType(ballotType);
        combo = combinationRepo.save(combo);

        // ── Sample contest with placeholder candidates ─────────────────────
        Contest contest = new Contest();
        contest.setElection(election);
        contest.setTitle("EditMe");
        contest.setVotingMethod(Contest.VotingMethod.PLURALITY);
        contest.setMaxChoices(1);
        contest.setDisplayOrder(1);
        contest.setInstructions("Vote for one");
        contest.setAssignedRegions(List.of(precinct));

        final String editNote =
            "EDIT THIS CONTEST: Change the contest title, adjust Max Choices and " +
            "Voting Method (Plurality, Ranked Choice, etc.) as needed, then " +
            "rename or replace the candidates below.";
        contest.setExplanatoryText(editNote);
        contest.setPrintExplanatoryText(false); // don't print the admin note on the ballot

        contest = contestRepo.save(contest);

        Candidate c1 = new Candidate();
        c1.setContest(contest);
        c1.setName("EditMe1");
        c1.setDisplayOrder(1);
        c1.setExplanatoryText("Replace this candidate name with the actual candidate.");
        c1.setPrintExplanatoryText(false);
        candidateRepo.save(c1);

        Candidate c2 = new Candidate();
        c2.setContest(contest);
        c2.setName("EditMe2");
        c2.setDisplayOrder(2);
        c2.setExplanatoryText("Replace this candidate name with the actual candidate.");
        c2.setPrintExplanatoryText(false);
        candidateRepo.save(c2);

        ra.addFlashAttribute("success",
            "Quick setup complete for \"" + election.getName() + "\". " +
            "Edit the contest name and candidates below, then go to Print Ballot to test.");
        return "redirect:/data/contests/" + contest.getId() + "/edit";
    }

    private String error(String message, Model model) {
        model.addAttribute("error",           message);
        model.addAttribute("jurisdictions",   jurisdictionRepo.findAll());
        model.addAttribute("electionTypes",   Election.ElectionType.values());
        model.addAttribute("paperSizes",      BallotDesignTemplate.PaperSize.values());
        model.addAttribute("indicatorStyles", SUPPORTED_INDICATOR_STYLES);
        return "setup/simple-election";
    }
}
