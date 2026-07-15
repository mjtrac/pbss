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

import com.mjtrac.ballot.model.BallotType;
import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.repository.BallotCombinationRepository;
import com.mjtrac.ballot.repository.BallotTypeRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/data/ballot-types")
@PreAuthorize("hasAnyRole('ADMIN','DATA_ENTRY')")
public class BallotTypeController {

    private final BallotTypeRepository        ballotTypeRepo;
    private final JurisdictionRepository      jurisdictionRepo;
    private final BallotCombinationRepository combinationRepo;

    public BallotTypeController(BallotTypeRepository ballotTypeRepo,
                                JurisdictionRepository jurisdictionRepo,
                                BallotCombinationRepository combinationRepo) {
        this.ballotTypeRepo   = ballotTypeRepo;
        this.jurisdictionRepo = jurisdictionRepo;
        this.combinationRepo  = combinationRepo;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("ballotTypes",   ballotTypeRepo.findAll());
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        return "data/ballot-types/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("ballotType",    new BallotType());
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        model.addAttribute("formTitle",     "New Ballot Type");
        return "data/ballot-types/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        BallotType bt = ballotTypeRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Ballot type not found: " + id));
        model.addAttribute("ballotType",    bt);
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        model.addAttribute("formTitle",     "Edit Ballot Type: " + bt.getName());
        return "data/ballot-types/form";
    }

    @PostMapping("/save")
    public String save(@RequestParam(required = false) Long   id,
                       @RequestParam(required = false) String name,
                       @RequestParam(required = false) Long   jurisdictionId,
                       @RequestParam(required = false) String description,
                       Model model,
                       RedirectAttributes ra) {

        if (name == null || name.isBlank()) {
            return returnToForm(id, "Ballot type name is required (e.g. Precinct, Mail-In, Absentee).", model);
        }
        if (jurisdictionId == null) {
            return returnToForm(id, "Please select a jurisdiction.", model);
        }

        Jurisdiction j = jurisdictionRepo.findById(jurisdictionId).orElse(null);
        if (j == null) {
            return returnToForm(id, "Selected jurisdiction not found — please choose again.", model);
        }

        BallotType bt = (id != null)
            ? ballotTypeRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ballot type not found: " + id))
            : new BallotType();

        bt.setName(name.trim());
        bt.setJurisdiction(j);
        bt.setDescription(description);
        ballotTypeRepo.save(bt);

        ra.addFlashAttribute("success",
            (id != null ? "Updated" : "Created") + " ballot type \"" + bt.getName() + "\".");
        return "redirect:/data/ballot-types";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        BallotType bt = ballotTypeRepo.findById(id).orElse(null);
        if (bt == null) {
            ra.addFlashAttribute("error", "Ballot type not found (already deleted?).");
            return "redirect:/data/ballot-types";
        }
        String name = bt.getName();
        try {
            var combos = combinationRepo.findByBallotTypeId(id);
            if (!combos.isEmpty()) {
                combinationRepo.deleteAll(combos);
            }
            ballotTypeRepo.delete(bt);
            ra.addFlashAttribute("success",
                "Deleted ballot type \"" + name + "\"" +
                (combos.isEmpty() ? "." :
                 " and " + combos.size() + " ballot combination(s) that referenced it."));
        } catch (Exception e) {
            ra.addFlashAttribute("error",
                "Could not delete \"" + name + "\": " + e.getMessage());
        }
        return "redirect:/data/ballot-types";
    }

    private String returnToForm(Long id, String error, Model model) {
        BallotType bt = (id != null) ? ballotTypeRepo.findById(id).orElse(new BallotType()) : new BallotType();
        model.addAttribute("ballotType",    bt);
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        model.addAttribute("formTitle",     id != null ? "Edit Ballot Type" : "New Ballot Type");
        model.addAttribute("error",         error);
        return "data/ballot-types/form";
    }
}
