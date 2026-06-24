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

import gov.election.ballot.model.Election;
import gov.election.ballot.model.Jurisdiction;
import gov.election.ballot.repository.ElectionRepository;
import gov.election.ballot.repository.JurisdictionRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

@Controller
@RequestMapping("/data/elections")
@PreAuthorize("hasAnyRole('ADMIN','DATA_ENTRY')")
public class ElectionController {

    private final ElectionRepository     electionRepo;
    private final JurisdictionRepository jurisdictionRepo;

    public ElectionController(ElectionRepository electionRepo,
                              JurisdictionRepository jurisdictionRepo) {
        this.electionRepo     = electionRepo;
        this.jurisdictionRepo = jurisdictionRepo;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("elections",     electionRepo.findAll());
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        model.addAttribute("electionTypes", Election.ElectionType.values());
        return "data/elections/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("election",      new Election());
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        model.addAttribute("electionTypes", Election.ElectionType.values());
        model.addAttribute("formTitle",     "New Election");
        return "data/elections/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Election e = electionRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Election not found: " + id));
        model.addAttribute("election",      e);
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        model.addAttribute("electionTypes", Election.ElectionType.values());
        model.addAttribute("formTitle",     "Edit Election: " + e.getName());
        return "data/elections/form";
    }

    @PostMapping("/save")
    public String save(@RequestParam(required = false) Long   id,
                       @RequestParam(required = false) String name,
                       @RequestParam(required = false) Long   jurisdictionId,
                       @RequestParam(required = false) String electionDate,
                       @RequestParam(required = false) String electionType,
                       @RequestParam(defaultValue = "false") boolean uniformBallot,
                       Model model,
                       RedirectAttributes ra) {

        if (name == null || name.isBlank()) {
            return returnToForm(id, "Election name is required.", model);
        }
        if (jurisdictionId == null) {
            return returnToForm(id, "Please select a jurisdiction.", model);
        }
        if (electionType == null || electionType.isBlank()) {
            return returnToForm(id, "Please select an election type.", model);
        }

        Jurisdiction jurisdiction = jurisdictionRepo.findById(jurisdictionId).orElse(null);
        if (jurisdiction == null) {
            return returnToForm(id, "Selected jurisdiction not found — please choose again.", model);
        }

        Election.ElectionType type;
        try {
            type = Election.ElectionType.valueOf(electionType);
        } catch (IllegalArgumentException ex) {
            return returnToForm(id, "Unknown election type: " + electionType, model);
        }

        Election election = (id != null)
            ? electionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Election not found: " + id))
            : new Election();

        election.setName(name.trim());
        election.setJurisdiction(jurisdiction);
        election.setElectionType(type);
        election.setUniformBallot(uniformBallot);
        election.setElectionDate(
            (electionDate != null && !electionDate.isBlank()) ? LocalDate.parse(electionDate) : null);

        electionRepo.save(election);
        ra.addFlashAttribute("success",
            (id != null ? "Updated" : "Created") + " election \"" + election.getName() + "\".");
        return "redirect:/data/elections";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        Election e = electionRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Election not found: " + id));
        try {
            electionRepo.delete(e);
            ra.addFlashAttribute("success", "Deleted election \"" + e.getName() + "\".");
        } catch (Exception ex) {
            ra.addFlashAttribute("error",
                "Cannot delete \"" + e.getName() + "\": it still has contests, ballot " +
                "combinations, or print records linked to it. Remove those first.");
        }
        return "redirect:/data/elections";
    }

    private String returnToForm(Long id, String error, Model model) {
        Election e = (id != null)
            ? electionRepo.findById(id).orElse(new Election())
            : new Election();
        model.addAttribute("election",      e);
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        model.addAttribute("electionTypes", Election.ElectionType.values());
        model.addAttribute("formTitle",     id != null ? "Edit Election" : "New Election");
        model.addAttribute("error",         error);
        return "data/elections/form";
    }
}
