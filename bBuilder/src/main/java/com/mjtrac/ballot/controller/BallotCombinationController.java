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
package com.mjtrac.ballot.controller;

import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.repository.*;
import com.mjtrac.ballot.service.ContestAssignmentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;

/**
 * CRUD for Ballot Combinations.
 *
 * A Ballot Combination defines exactly what a specific voter receives:
 *   Election + SinglePrecinct + Party (optional) + Ballot Type
 *
 * When the user selects an election from the dropdown, the page reloads via GET
 * to /data/ballot-combinations/for-election?electionId=X, which populates
 * the SinglePrecinct, Party, and Ballot Type dropdowns from that election's
 * jurisdiction.  No data is saved during this reload.
 */
@Controller
@RequestMapping("/data/ballot-combinations")
@PreAuthorize("hasAnyRole('ADMIN','DATA_ENTRY')")
public class BallotCombinationController {

    private final BallotCombinationRepository combinationRepo;
    private final ElectionRepository          electionRepo;
    private final RegionRepository            regionRepo;
    private final PartyRepository             partyRepo;
    private final BallotTypeRepository        ballotTypeRepo;
    private final ContestAssignmentService    assignmentService;

    public BallotCombinationController(BallotCombinationRepository combinationRepo,
                                       ElectionRepository electionRepo,
                                       RegionRepository regionRepo,
                                       PartyRepository partyRepo,
                                       BallotTypeRepository ballotTypeRepo,
                                       ContestAssignmentService assignmentService) {
        this.combinationRepo   = combinationRepo;
        this.electionRepo      = electionRepo;
        this.regionRepo        = regionRepo;
        this.partyRepo         = partyRepo;
        this.ballotTypeRepo    = ballotTypeRepo;
        this.assignmentService = assignmentService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("combinations", combinationRepo.findAll());
        return "data/ballot-combinations/list";
    }

    /**
     * New form. If elections exist, pre-loads the first election's jurisdiction
     * data so dropdowns are populated immediately without requiring a reload.
     */
    @GetMapping("/new")
    public String newForm(Model model) {
        List<Election> elections = electionRepo.findAll();
        // Pre-load first election's data so the form is usable immediately
        if (!elections.isEmpty()) {
            return loadFormForElection(elections.get(0).getId(), null, model);
        }
        model.addAttribute("elections",    elections);
        model.addAttribute("combination",  new BallotCombination());
        model.addAttribute("precincts",    List.of());
        model.addAttribute("parties",      List.of());
        model.addAttribute("ballotTypes",  List.of());
        model.addAttribute("formTitle",    "New Ballot Combination");
        return "data/ballot-combinations/form";
    }

    /**
     * Reload the form for a specific election.
     * Called via GET when the user changes the election dropdown.
     * No data is saved.
     */
    @GetMapping("/for-election")
    public String forElection(@RequestParam(required = false) Long electionId,
                              @RequestParam(required = false) Long id,
                              Model model) {
        if (electionId == null) {
            return newForm(model);
        }
        return loadFormForElection(electionId, id, model);
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        BallotCombination combo = combinationRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Ballot combination not found: " + id));

        Long jurId = jurIdFor(combo.getElection());
        loadDropdowns(jurId, model);

        if (combo.getRegion() != null && combo.getElection() != null) {
            model.addAttribute("resolvedContests",
                assignmentService.resolveContestsForPrecinct(
                    combo.getRegion().getId(), combo.getElection().getId()));
        }

        model.addAttribute("combination", combo);
        model.addAttribute("elections",   electionRepo.findAll());
        model.addAttribute("formTitle",   "Edit Ballot Combination");
        return "data/ballot-combinations/form";
    }

    @PostMapping("/save")
    public String save(@RequestParam(required = false) Long id,
                       @RequestParam(required = false) Long electionId,
                       @RequestParam(required = false) Long regionId,
                       @RequestParam(required = false) Long partyId,
                       @RequestParam(required = false) Long ballotTypeId,
                       RedirectAttributes ra,
                       Model model) {

        if (electionId == null) {
            return returnToForm(id, null, "Please select an election.", model);
        }
        if (regionId == null) {
            return returnToForm(id, electionId, "Please select a SinglePrecinct.", model);
        }
        if (ballotTypeId == null) {
            return returnToForm(id, electionId, "Please select a ballot type.", model);
        }

        Election election = electionRepo.findById(electionId).orElse(null);
        if (election == null) {
            return returnToForm(id, null, "Election not found — please choose again.", model);
        }

        Region region = regionRepo.findById(regionId).orElse(null);
        if (region == null) {
            return returnToForm(id, electionId, "Region not found — please choose again.", model);
        }
        if (!region.isSinglePrecinct()) {
            return returnToForm(id, electionId,
                "\"" + region.getName() + "\" is a PrecinctGroup, not a SinglePrecinct. " +
                "Ballot combinations must use a SinglePrecinct.", model);
        }

        BallotType ballotType = ballotTypeRepo.findById(ballotTypeId).orElse(null);
        if (ballotType == null) {
            return returnToForm(id, electionId, "Ballot type not found — please choose again.", model);
        }

        if (partyId == null) {
            return returnToForm(id, electionId,
                "Please select a party. For general or nonpartisan elections, " +
                "select \"Everyone\" (go to Setup > Parties if it is missing).", model);
        }
        Party party = partyRepo.findById(partyId).orElse(null);
        if (party == null) {
            return returnToForm(id, electionId, "Selected party not found — please choose again.", model);
        }

        BallotCombination combo = (id != null)
            ? combinationRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Combination not found: " + id))
            : new BallotCombination();

        combo.setElection(election);
        combo.setRegion(region);
        combo.setParty(party);
        combo.setBallotType(ballotType);

        BallotCombination saved = combinationRepo.save(combo);
        ra.addFlashAttribute("success",
            (id != null ? "Updated" : "Created") + " ballot combination.");
        return "redirect:/data/ballot-combinations/" + saved.getId() + "/edit";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        BallotCombination combo = combinationRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Combination not found: " + id));
        try {
            combinationRepo.delete(combo);
            ra.addFlashAttribute("success", "Deleted ballot combination.");
        } catch (Exception e) {
            ra.addFlashAttribute("error",
                "Cannot delete: this combination has print log records attached.");
        }
        return "redirect:/data/ballot-combinations";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String loadFormForElection(Long electionId, Long existingId, Model model) {
        BallotCombination combo = (existingId != null)
            ? combinationRepo.findById(existingId).orElse(new BallotCombination())
            : new BallotCombination();

        Optional<Election> elOpt = electionRepo.findById(electionId);
        elOpt.ifPresent(combo::setElection);
        Long jurId = elOpt.map(this::jurIdFor).orElse(null);

        loadDropdowns(jurId, model);
        model.addAttribute("combination", combo);
        model.addAttribute("elections",   electionRepo.findAll());
        model.addAttribute("formTitle",   existingId != null
            ? "Edit Ballot Combination" : "New Ballot Combination");
        return "data/ballot-combinations/form";
    }

    private void loadDropdowns(Long jurId, Model model) {
        model.addAttribute("precincts",
            jurId != null
                ? regionRepo.findByJurisdictionIdAndRegionTypeOrderByName(
                    jurId, Region.RegionType.SINGLE_PRECINCT)
                : List.of());
        model.addAttribute("parties",
            jurId != null ? partyRepo.findByJurisdictionIdOrderByName(jurId)     : List.of());
        model.addAttribute("ballotTypes",
            jurId != null ? ballotTypeRepo.findByJurisdictionIdOrderByName(jurId) : List.of());
    }

    private Long jurIdFor(Election election) {
        return (election != null && election.getJurisdiction() != null)
            ? election.getJurisdiction().getId() : null;
    }

    private String returnToForm(Long id, Long electionId, String error, Model model) {
        BallotCombination combo = (id != null)
            ? combinationRepo.findById(id).orElse(new BallotCombination())
            : new BallotCombination();
        Long jurId = electionId != null
            ? electionRepo.findById(electionId).map(this::jurIdFor).orElse(null)
            : jurIdFor(combo.getElection());

        loadDropdowns(jurId, model);
        model.addAttribute("combination", combo);
        model.addAttribute("elections",   electionRepo.findAll());
        model.addAttribute("formTitle",   id != null ? "Edit Ballot Combination" : "New Ballot Combination");
        model.addAttribute("error",       error);
        return "data/ballot-combinations/form";
    }
}
