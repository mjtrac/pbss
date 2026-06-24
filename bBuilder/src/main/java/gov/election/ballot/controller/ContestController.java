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
import gov.election.ballot.service.ContestAssignmentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

/**
 * CRUD for Contests, Candidates, and region assignment.
 *
 * Candidate party affiliation is a free-text ballot label (e.g. "Democratic"),
 * not a FK to the Party table. The Party table is used only for BallotCombination
 * to distinguish which ballot variant a voter receives in a primary.
 */
@Controller
@RequestMapping("/data/contests")
@PreAuthorize("hasAnyRole('ADMIN','DATA_ENTRY')")
public class ContestController {

    private final ContestRepository        contestRepo;
    private final ElectionRepository       electionRepo;
    private final CandidateRepository      candidateRepo;
    private final RegionRepository         regionRepo;
    private final JurisdictionRepository   jurisdictionRepo;
    private final ContestAssignmentService assignmentService;

    public ContestController(ContestRepository contestRepo,
                             ElectionRepository electionRepo,
                             CandidateRepository candidateRepo,
                             RegionRepository regionRepo,
                             JurisdictionRepository jurisdictionRepo,
                             ContestAssignmentService assignmentService) {
        this.contestRepo       = contestRepo;
        this.electionRepo      = electionRepo;
        this.candidateRepo     = candidateRepo;
        this.regionRepo        = regionRepo;
        this.jurisdictionRepo  = jurisdictionRepo;
        this.assignmentService = assignmentService;
    }

    // ── Contest list ──────────────────────────────────────────────────────────

    @GetMapping
    public String list(Model model) {
        model.addAttribute("contests",  contestRepo.findAll());
        model.addAttribute("elections", electionRepo.findAll());
        return "data/contests";
    }

    // ── New contest form — pre-select first election ──────────────────────────

    @GetMapping("/new")
    public String newForm(Model model) {
        List<Election> elections = electionRepo.findAll();
        Contest contest = new Contest();

        // Pre-select the first available election to save a click
        if (!elections.isEmpty()) {
            contest.setElection(elections.get(0));
        }

        Long jurId = contest.getElection() != null
                     && contest.getElection().getJurisdiction() != null
            ? contest.getElection().getJurisdiction().getId() : null;

        model.addAttribute("contest",       contest);
        model.addAttribute("elections",     elections);
        model.addAttribute("allRegions",    jurId != null
            ? regionRepo.findByJurisdictionIdOrderByName(jurId) : List.of());
        model.addAttribute("votingMethods", Contest.VotingMethod.values());
        model.addAttribute("formTitle",     "New Contest");
        return "data/contest-form";
    }

    // ── Edit contest form ─────────────────────────────────────────────────────

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Contest contest = contestRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Contest not found: " + id));
        List<Region> regions = (contest.getElection() != null
                                && contest.getElection().getJurisdiction() != null)
            ? regionRepo.findByJurisdictionIdOrderByName(
                contest.getElection().getJurisdiction().getId())
            : regionRepo.findAll();
        model.addAttribute("contest",       contest);
        model.addAttribute("elections",     electionRepo.findAll());
        model.addAttribute("allRegions",    regions);
        model.addAttribute("votingMethods", Contest.VotingMethod.values());
        model.addAttribute("formTitle",     "Edit Contest: " + contest.getTitle());
        return "data/contest-form";
    }

    // ── Save contest ──────────────────────────────────────────────────────────

    @PostMapping("/save")
    public String save(@RequestParam(required = false) Long   id,
                       @RequestParam(required = false) Long   electionId,
                       @RequestParam(required = false) String title,
                       @RequestParam(required = false) String printableTitle,
                       @RequestParam(required = false) String recordTitle,
                       @RequestParam(required = false) String votingMethod,
                       @RequestParam(defaultValue = "1")  int maxChoices,
                       @RequestParam(defaultValue = "0")  int displayOrder,
                       @RequestParam(defaultValue = "0")  int maxRankChoices,
                       @RequestParam(required = false) String instructions,
                       @RequestParam(required = false) String groupingLabel,
                       @RequestParam(defaultValue = "false") boolean printGroupingLabel,
                       @RequestParam(required = false) String preamble,
                       @RequestParam(defaultValue = "false") boolean printPreamble,
                       @RequestParam(required = false) String postamble,
                       @RequestParam(defaultValue = "false") boolean printPostamble,
                       @RequestParam(required = false) String explanatoryText,
                       @RequestParam(defaultValue = "false") boolean printExplanatoryText,
                       @RequestParam(required = false) String explanatoryTextLocation,
                       RedirectAttributes ra,
                       Model model) {

        if (electionId == null) {
            return returnToForm(id, null, "Please select an election.", model);
        }
        // Accept either printableTitle (new field) or legacy title param
        String resolvedTitle = (printableTitle != null && !printableTitle.isBlank())
            ? printableTitle.trim()
            : (title != null ? title.trim() : "");
        if (resolvedTitle.isBlank()) {
            return returnToForm(id, electionId, "Contest title is required.", model);
        }
        if (votingMethod == null || votingMethod.isBlank()) {
            return returnToForm(id, electionId, "Please select a voting method.", model);
        }

        Election election = electionRepo.findById(electionId).orElse(null);
        if (election == null) {
            return returnToForm(id, null, "Selected election not found.", model);
        }

        Contest.VotingMethod method;
        try {
            method = Contest.VotingMethod.valueOf(votingMethod);
        } catch (IllegalArgumentException e) {
            return returnToForm(id, electionId, "Unknown voting method: " + votingMethod, model);
        }

        Contest contest = (id != null)
            ? contestRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Contest not found: " + id))
            : new Contest();

        contest.setElection(election);
        contest.setTitle(resolvedTitle);
        contest.setPrintableTitle(resolvedTitle);
        if (recordTitle != null && !recordTitle.isBlank())
            contest.setRecordTitle(recordTitle.trim());
        else
            contest.setRecordTitle(resolvedTitle);  // default record title = printable title
        contest.setVotingMethod(method);
        contest.setMaxChoices(maxChoices);
        contest.setDisplayOrder(displayOrder);
        contest.setMaxRankChoices(method == Contest.VotingMethod.RANKED_CHOICE ? maxRankChoices : 0);
        contest.setInstructions(instructions);
        contest.setGroupingLabel(groupingLabel);
        contest.setPrintGroupingLabel(printGroupingLabel);
        contest.setPreamble(preamble);
        contest.setPrintPreamble(printPreamble);
        contest.setPostamble(postamble);
        contest.setPrintPostamble(printPostamble);
        contest.setExplanatoryText(explanatoryText);
        contest.setPrintExplanatoryText(printExplanatoryText);
        contest.setExplanatoryTextLocation(explanatoryTextLocation);

        Contest saved = contestRepo.save(contest);
        ra.addFlashAttribute("success", "Saved contest \"" + saved.getTitle() + "\".");
        return "redirect:/data/contests/" + saved.getId() + "/edit";
    }

    // ── Region assignment ─────────────────────────────────────────────────────

    @PostMapping("/{id}/assign-regions")
    public String assignRegions(@PathVariable Long id,
                                @RequestParam(required = false) List<Long> regionIds,
                                RedirectAttributes ra) {
        Contest contest = contestRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Contest not found: " + id));
        List<Region> assigned = (regionIds != null && !regionIds.isEmpty())
            ? regionRepo.findAllById(regionIds)
            : new ArrayList<>();
        contest.setAssignedRegions(assigned);
        contestRepo.save(contest);
        ra.addFlashAttribute("success",
            "Updated region assignment for \"" + contest.getTitle() +
            "\" (" + assigned.size() + " region(s)).");
        return "redirect:/data/contests/" + id + "/edit";
    }

    // ── Candidate / option list for a contest ─────────────────────────────────

    /**
     * Dedicated candidate management page for a contest.
     * URL: /data/contests/{id}/candidates
     */
    @GetMapping("/{id}/candidates")
    public String candidateList(@PathVariable Long id, Model model) {
        Contest contest = contestRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Contest not found: " + id));
        model.addAttribute("contest",   contest);
        model.addAttribute("candidate", new Candidate()); // blank form
        model.addAttribute("formTitle", "Candidates — " + contest.getTitle());
        return "data/candidates";
    }

    // ── Add candidate ─────────────────────────────────────────────────────────

    @PostMapping("/{id}/candidates")
    public String addCandidate(@PathVariable Long id,
                               @RequestParam(required = false) String name,
                               @RequestParam(required = false) String printableName,
                               @RequestParam(required = false) String recordName,
                               @RequestParam(defaultValue = "false") boolean writeIn,
                               @RequestParam(required = false) String partyAffiliation,
                               @RequestParam(required = false) String prefixText,
                               @RequestParam(defaultValue = "false") boolean printPrefixText,
                               @RequestParam(required = false) String suffixText,
                               @RequestParam(defaultValue = "false") boolean printSuffixText,
                               @RequestParam(defaultValue = "1")  int displayOrder,
                               @RequestParam(required = false) String explanatoryText,
                               @RequestParam(defaultValue = "false") boolean printExplanatoryText,
                               RedirectAttributes ra) {

        String resolvedName = (printableName != null && !printableName.isBlank())
            ? printableName.trim()
            : (name != null ? name.trim() : "");
        if (resolvedName.isBlank()) {
            ra.addFlashAttribute("error",
                "Name is required (e.g. the candidate's name, or YES / NO for a measure).");
            return "redirect:/data/contests/" + id + "/candidates";
        }

        Contest contest = contestRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Contest not found: " + id));

        Candidate c = new Candidate();
        c.setContest(contest);
        c.setName(resolvedName);
        c.setPrintableName(resolvedName);
        if (recordName != null && !recordName.isBlank())
            c.setRecordName(recordName.trim());
        else
            c.setRecordName(resolvedName);  // default record name = printable name
        c.setWriteIn(writeIn);
        c.setPartyAffiliation(
            partyAffiliation != null && !partyAffiliation.isBlank()
                ? partyAffiliation.trim() : null);
        c.setPrefixText(prefixText != null && !prefixText.isBlank() ? prefixText.trim() : null);
        c.setPrintPrefixText(printPrefixText);
        c.setSuffixText(suffixText != null && !suffixText.isBlank() ? suffixText.trim() : null);
        c.setPrintSuffixText(printSuffixText);
        c.setDisplayOrder(displayOrder);
        c.setExplanatoryText(
            explanatoryText != null && !explanatoryText.isBlank()
                ? explanatoryText.trim() : null);
        c.setPrintExplanatoryText(printExplanatoryText);
        candidateRepo.save(c);

        ra.addFlashAttribute("success", "Added \"" + c.getName() + "\".");
        return "redirect:/data/contests/" + id + "/candidates";
    }

    // ── Edit candidate form ───────────────────────────────────────────────────

    @GetMapping("/{contestId}/candidates/{candidateId}/edit")
    public String editCandidateForm(@PathVariable Long contestId,
                                    @PathVariable Long candidateId,
                                    Model model) {
        Contest contest = contestRepo.findById(contestId)
            .orElseThrow(() -> new IllegalArgumentException("Contest not found: " + contestId));
        Candidate candidate = candidateRepo.findById(candidateId)
            .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));
        model.addAttribute("contest",   contest);
        model.addAttribute("candidate", candidate);
        model.addAttribute("formTitle", "Edit Candidate — " + contest.getTitle());
        return "data/candidates";
    }

    // ── Update candidate ──────────────────────────────────────────────────────

    @PostMapping("/{contestId}/candidates/{candidateId}/save")
    public String saveCandidate(@PathVariable Long contestId,
                                @PathVariable Long candidateId,
                                @RequestParam(required = false) String name,
                                @RequestParam(required = false) String printableName,
                                @RequestParam(required = false) String recordName,
                                @RequestParam(defaultValue = "false") boolean writeIn,
                                @RequestParam(required = false) String partyAffiliation,
                                @RequestParam(required = false) String prefixText,
                                @RequestParam(defaultValue = "false") boolean printPrefixText,
                                @RequestParam(required = false) String suffixText,
                                @RequestParam(defaultValue = "false") boolean printSuffixText,
                                @RequestParam(defaultValue = "1")  int displayOrder,
                                @RequestParam(required = false) String explanatoryText,
                                @RequestParam(defaultValue = "false") boolean printExplanatoryText,
                                RedirectAttributes ra) {

        String resolvedName = (printableName != null && !printableName.isBlank())
            ? printableName.trim()
            : (name != null ? name.trim() : "");
        if (resolvedName.isBlank()) {
            ra.addFlashAttribute("error", "Name is required.");
            return "redirect:/data/contests/" + contestId
                   + "/candidates/" + candidateId + "/edit";
        }

        Candidate c = candidateRepo.findById(candidateId)
            .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));

        c.setName(resolvedName);
        c.setPrintableName(resolvedName);
        if (recordName != null && !recordName.isBlank())
            c.setRecordName(recordName.trim());
        else
            c.setRecordName(resolvedName);  // default record name = printable name
        c.setWriteIn(writeIn);
        c.setPartyAffiliation(
            partyAffiliation != null && !partyAffiliation.isBlank()
                ? partyAffiliation.trim() : null);
        c.setPrefixText(prefixText != null && !prefixText.isBlank() ? prefixText.trim() : null);
        c.setPrintPrefixText(printPrefixText);
        c.setSuffixText(suffixText != null && !suffixText.isBlank() ? suffixText.trim() : null);
        c.setPrintSuffixText(printSuffixText);
        c.setDisplayOrder(displayOrder);
        c.setExplanatoryText(
            explanatoryText != null && !explanatoryText.isBlank()
                ? explanatoryText.trim() : null);
        c.setPrintExplanatoryText(printExplanatoryText);
        candidateRepo.save(c);

        ra.addFlashAttribute("success", "Updated \"" + c.getName() + "\".");
        return "redirect:/data/contests/" + contestId + "/candidates";
    }

    // ── Delete candidate ──────────────────────────────────────────────────────

    @PostMapping("/{contestId}/candidates/{candidateId}/delete")
    public String deleteCandidate(@PathVariable Long contestId,
                                  @PathVariable Long candidateId,
                                  RedirectAttributes ra) {
        Candidate c = candidateRepo.findById(candidateId)
            .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));
        String name = c.getName();
        candidateRepo.delete(c);
        ra.addFlashAttribute("success", "Removed \"" + name + "\".");
        return "redirect:/data/contests/" + contestId + "/candidates";
    }

    // ── Delete contest ────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        Contest contest = contestRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Contest not found: " + id));
        String title = contest.getTitle();
        contestRepo.delete(contest);
        ra.addFlashAttribute("success", "Deleted contest \"" + title + "\".");
        return "redirect:/data/contests";
    }

    // ── Coverage preview ──────────────────────────────────────────────────────

    @GetMapping("/{id}/preview")
    public String previewCoverage(@PathVariable Long id, Model model) {
        Contest contest = contestRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Contest not found: " + id));

        List<Region> allPrecincts = (contest.getElection() != null
                                    && contest.getElection().getJurisdiction() != null)
            ? regionRepo.findByJurisdictionIdAndRegionTypeOrderByName(
                contest.getElection().getJurisdiction().getId(),
                Region.RegionType.SINGLE_PRECINCT)
            : regionRepo.findAll().stream().filter(Region::isSinglePrecinct).toList();

        List<Region> covered  = new ArrayList<>();
        List<Region> excluded = new ArrayList<>();
        for (Region precinct : allPrecincts) {
            boolean covers = assignmentService
                .resolveContestsForPrecinct(precinct.getId(), contest.getElection().getId())
                .stream().anyMatch(c -> c.getId().equals(contest.getId()));
            if (covers) covered.add(precinct); else excluded.add(precinct);
        }

        model.addAttribute("contest",          contest);
        model.addAttribute("coveredPrecincts",  covered);
        model.addAttribute("excludedPrecincts", excluded);
        return "data/contest-preview";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String returnToForm(Long id, Long electionId, String error, Model model) {
        Contest contest = (id != null)
            ? contestRepo.findById(id).orElse(new Contest())
            : new Contest();

        Long jurId = null;
        if (electionId != null) {
            jurId = electionRepo.findById(electionId)
                .map(e -> e.getJurisdiction() != null ? e.getJurisdiction().getId() : null)
                .orElse(null);
        } else if (contest.getElection() != null
                   && contest.getElection().getJurisdiction() != null) {
            jurId = contest.getElection().getJurisdiction().getId();
        }

        model.addAttribute("contest",       contest);
        model.addAttribute("elections",     electionRepo.findAll());
        model.addAttribute("allRegions",    jurId != null
            ? regionRepo.findByJurisdictionIdOrderByName(jurId) : List.of());
        model.addAttribute("votingMethods", Contest.VotingMethod.values());
        model.addAttribute("formTitle",     id != null ? "Edit Contest" : "New Contest");
        model.addAttribute("error",         error);
        return "data/contest-form";
    }
}
