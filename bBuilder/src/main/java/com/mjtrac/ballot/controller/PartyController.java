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

import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.model.Party;
import com.mjtrac.ballot.repository.BallotCombinationRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.PartyRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/data/parties")
@PreAuthorize("hasAnyRole('ADMIN','DATA_ENTRY')")
public class PartyController {

    private final PartyRepository             partyRepo;
    private final JurisdictionRepository      jurisdictionRepo;
    private final BallotCombinationRepository combinationRepo;

    public PartyController(PartyRepository partyRepo,
                           JurisdictionRepository jurisdictionRepo,
                           BallotCombinationRepository combinationRepo) {
        this.partyRepo       = partyRepo;
        this.jurisdictionRepo = jurisdictionRepo;
        this.combinationRepo  = combinationRepo;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("parties",       partyRepo.findAll());
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        return "data/parties/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("party",         new Party());
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        model.addAttribute("formTitle",     "New Party");
        return "data/parties/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Party p = partyRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Party not found: " + id));
        model.addAttribute("party",         p);
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        model.addAttribute("formTitle",     "Edit Party: " + p.getName());
        return "data/parties/form";
    }

    @PostMapping("/save")
    public String save(@RequestParam(required = false) Long   id,
                       @RequestParam(required = false) String name,
                       @RequestParam(required = false) Long   jurisdictionId,
                       @RequestParam(required = false) String abbreviation,
                       Model model,
                       RedirectAttributes ra) {

        if (name == null || name.isBlank()) {
            return returnToForm(id, "Party name is required.", model);
        }
        if (jurisdictionId == null) {
            return returnToForm(id, "Please select a jurisdiction.", model);
        }

        Jurisdiction j = jurisdictionRepo.findById(jurisdictionId).orElse(null);
        if (j == null) {
            return returnToForm(id, "Selected jurisdiction not found — please choose again.", model);
        }

        Party p = (id != null)
            ? partyRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("Party not found: " + id))
            : new Party();

        p.setName(name.trim());
        p.setJurisdiction(j);
        p.setAbbreviation(abbreviation != null && !abbreviation.isBlank() ? abbreviation.trim() : null);
        partyRepo.save(p);

        ra.addFlashAttribute("success",
            (id != null ? "Updated" : "Created") + " party \"" + p.getName() + "\".");
        return "redirect:/data/parties";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        Party p = partyRepo.findById(id).orElse(null);
        if (p == null) {
            ra.addFlashAttribute("error", "Party not found (already deleted?).");
            return "redirect:/data/parties";
        }
        String name = p.getName();
        try {
            var combos = combinationRepo.findByPartyId(id);
            if (!combos.isEmpty()) {
                combinationRepo.deleteAll(combos);
            }
            partyRepo.delete(p);
            ra.addFlashAttribute("success",
                "Deleted party \"" + name + "\"" +
                (combos.isEmpty() ? "." :
                 " and " + combos.size() + " ballot combination(s) that referenced it."));
        } catch (Exception e) {
            ra.addFlashAttribute("error",
                "Could not delete \"" + name + "\": " + e.getMessage());
        }
        return "redirect:/data/parties";
    }

    private String returnToForm(Long id, String error, Model model) {
        Party p = (id != null) ? partyRepo.findById(id).orElse(new Party()) : new Party();
        model.addAttribute("party",         p);
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        model.addAttribute("formTitle",     id != null ? "Edit Party" : "New Party");
        model.addAttribute("error",         error);
        return "data/parties/form";
    }
}
