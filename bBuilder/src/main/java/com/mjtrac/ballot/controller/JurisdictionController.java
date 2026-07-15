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
import com.mjtrac.ballot.repository.JurisdictionRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/jurisdictions")
@PreAuthorize("hasRole('ADMIN')")
public class JurisdictionController {

    private final JurisdictionRepository jurisdictionRepo;

    public JurisdictionController(JurisdictionRepository jurisdictionRepo) {
        this.jurisdictionRepo = jurisdictionRepo;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        return "admin/jurisdictions/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("jurisdiction", new Jurisdiction());
        model.addAttribute("formTitle", "New Jurisdiction");
        return "admin/jurisdictions/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Jurisdiction j = jurisdictionRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Jurisdiction not found: " + id));
        model.addAttribute("jurisdiction", j);
        model.addAttribute("formTitle", "Edit Jurisdiction: " + j.getName());
        return "admin/jurisdictions/form";
    }

    @PostMapping("/save")
    public String save(@RequestParam(required = false) Long   id,
                       @RequestParam(required = false) String name,
                       @RequestParam(required = false) String address,
                       @RequestParam(required = false) String contactEmail,
                       @RequestParam(required = false) String generalVotingInstructions,
                       Model model,
                       RedirectAttributes ra) {

        if (name == null || name.isBlank()) {
            return returnToForm(id, "Jurisdiction name is required.", model);
        }

        Jurisdiction j = (id != null)
            ? jurisdictionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Jurisdiction not found: " + id))
            : new Jurisdiction();

        j.setName(name.trim());
        j.setAddress(address);
        j.setContactEmail(contactEmail);
        j.setGeneralVotingInstructions(generalVotingInstructions);
        jurisdictionRepo.save(j);

        ra.addFlashAttribute("success",
            (id != null ? "Updated" : "Created") + " jurisdiction \"" + j.getName() + "\".");
        return "redirect:/admin/jurisdictions";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        Jurisdiction j = jurisdictionRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Jurisdiction not found: " + id));
        // Never allow deleting the last jurisdiction — it can be edited instead
        if (jurisdictionRepo.count() <= 1) {
            ra.addFlashAttribute("error",
                "Cannot delete the only jurisdiction. Edit it to update its details instead.");
            return "redirect:/admin/jurisdictions";
        }
        try {
            jurisdictionRepo.delete(j);
            ra.addFlashAttribute("success", "Deleted jurisdiction \"" + j.getName() + "\".");
        } catch (Exception e) {
            ra.addFlashAttribute("error",
                "Cannot delete \"" + j.getName() + "\": it still has elections, " +
                "regions, or other records linked to it. Remove those first.");
        }
        return "redirect:/admin/jurisdictions";
    }

    private String returnToForm(Long id, String error, Model model) {
        Jurisdiction j = (id != null)
            ? jurisdictionRepo.findById(id).orElse(new Jurisdiction())
            : new Jurisdiction();
        model.addAttribute("jurisdiction", j);
        model.addAttribute("formTitle", id != null ? "Edit Jurisdiction" : "New Jurisdiction");
        model.addAttribute("error", error);
        return "admin/jurisdictions/form";
    }
}
