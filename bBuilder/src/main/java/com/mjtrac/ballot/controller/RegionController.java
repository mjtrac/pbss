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
import com.mjtrac.ballot.model.Region;
import com.mjtrac.ballot.repository.BallotCombinationRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.RegionRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

/**
 * CRUD for Regions.
 *
 * Two kinds:
 *   SINGLE_PRECINCT  — individual voting precinct; voters are registered to one.
 *                      Ballot combinations are keyed to SinglePrecincts.
 *   PRECINCT_GROUP   — named grouping of multiple SinglePrecincts (District, City, etc.).
 *                      Assigning a contest to a PrecinctGroup fans out automatically
 *                      to all member SinglePrecincts.
 */
@Controller
@RequestMapping("/data/regions")
@PreAuthorize("hasAnyRole('ADMIN','DATA_ENTRY')")
public class RegionController {

    private final RegionRepository            regionRepo;
    private final JurisdictionRepository      jurisdictionRepo;
    private final BallotCombinationRepository combinationRepo;

    public RegionController(RegionRepository regionRepo,
                            JurisdictionRepository jurisdictionRepo,
                            BallotCombinationRepository combinationRepo) {
        this.regionRepo       = regionRepo;
        this.jurisdictionRepo = jurisdictionRepo;
        this.combinationRepo  = combinationRepo;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("regions",       regionRepo.findAll());
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        return "data/regions/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("region",        new Region());
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        model.addAttribute("allSinglePrecincts", List.of());
        model.addAttribute("formTitle",     "New Region");
        return "data/regions/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Region region = regionRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Region not found: " + id));

        List<Region> singles = region.getJurisdiction() != null
            ? regionRepo.findByJurisdictionIdAndRegionTypeOrderByName(
                region.getJurisdiction().getId(), Region.RegionType.SINGLE_PRECINCT)
            : List.of();

        model.addAttribute("region",              region);
        model.addAttribute("jurisdictions",       jurisdictionRepo.findAll());
        model.addAttribute("allSinglePrecincts",  singles);
        model.addAttribute("formTitle",           "Edit Region: " + region.getName());
        return "data/regions/form";
    }

    @PostMapping("/save")
    public String save(@RequestParam(required = false) Long   id,
                       @RequestParam(required = false) String name,
                       @RequestParam(required = false) Long   jurisdictionId,
                       @RequestParam(required = false) String regionType,
                       @RequestParam(required = false) String groupType,
                       @RequestParam(required = false) String description,
                       Model model,
                       RedirectAttributes ra) {

        if (name == null || name.isBlank()) {
            return returnToForm(id, jurisdictionId, "Name is required.", model);
        }
        if (jurisdictionId == null) {
            return returnToForm(id, null, "Please select a master jurisdiction.", model);
        }
        if (regionType == null || regionType.isBlank()) {
            return returnToForm(id, jurisdictionId,
                "Please select a region type (SinglePrecinct or PrecinctGroup).", model);
        }

        Jurisdiction j = jurisdictionRepo.findById(jurisdictionId).orElse(null);
        if (j == null) {
            return returnToForm(id, jurisdictionId,
                "Selected jurisdiction not found — please choose again.", model);
        }

        Region.RegionType type;
        try {
            type = Region.RegionType.valueOf(regionType);
        } catch (IllegalArgumentException e) {
            return returnToForm(id, jurisdictionId, "Invalid region type: " + regionType, model);
        }

        Region region = (id != null)
            ? regionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Region not found: " + id))
            : new Region();

        region.setName(name.trim());
        region.setJurisdiction(j);
        region.setRegionType(type);
        region.setDescription(description);

        if (type == Region.RegionType.SINGLE_PRECINCT) {
            region.setGroupType(null);
            region.getMembers().clear();
        } else {
            region.setGroupType(groupType != null && !groupType.isBlank() ? groupType : null);
        }

        Region saved = regionRepo.save(region);
        ra.addFlashAttribute("success",
            (id != null ? "Updated" : "Created") + " region \"" + saved.getName() + "\".");
        return "redirect:/data/regions/" + saved.getId() + "/edit";
    }

    /**
     * For PrecinctGroup regions: set which SinglePrecincts belong to this group.
     * Replaces the existing member list entirely.
     */
    @PostMapping("/{id}/assign-members")
    public String assignMembers(@PathVariable Long id,
                                @RequestParam(required = false) List<Long> memberIds,
                                RedirectAttributes ra) {
        Region group = regionRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Region not found: " + id));

        if (group.isSinglePrecinct()) {
            ra.addFlashAttribute("error",
                "\"" + group.getName() + "\" is a SinglePrecinct and cannot have members. " +
                "Only PrecinctGroup regions have member lists.");
            return "redirect:/data/regions/" + id + "/edit";
        }

        List<Region> members = new ArrayList<>();
        if (memberIds != null && !memberIds.isEmpty()) {
            members = regionRepo.findAllById(memberIds).stream()
                .filter(Region::isSinglePrecinct)
                .toList();
        }
        group.setMembers(members);
        regionRepo.save(group);

        ra.addFlashAttribute("success",
            "Updated member list for \"" + group.getName() +
            "\" (" + members.size() + " SinglePrecinct(s)).");
        return "redirect:/data/regions/" + id + "/edit";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        Region region = regionRepo.findById(id).orElse(null);
        if (region == null) {
            ra.addFlashAttribute("error", "Region not found (already deleted?).");
            return "redirect:/data/regions";
        }
        String name = region.getName();
        try {
            var combos = combinationRepo.findByRegionIdOrderByElectionId(id);
            if (!combos.isEmpty()) {
                combinationRepo.deleteAll(combos);
            }
            regionRepo.delete(region);
            ra.addFlashAttribute("success",
                "Deleted region \"" + name + "\"" +
                (combos.isEmpty() ? "." :
                 " and " + combos.size() + " ballot combination(s) that referenced it."));
        } catch (Exception e) {
            ra.addFlashAttribute("error",
                "Could not delete \"" + name + "\": " + e.getMessage());
        }
        return "redirect:/data/regions";
    }

    private String returnToForm(Long id, Long jurisdictionId, String error, Model model) {
        Region region = (id != null)
            ? regionRepo.findById(id).orElse(new Region())
            : new Region();

        List<Region> singles = List.of();
        if (jurisdictionId != null) {
            singles = regionRepo.findByJurisdictionIdAndRegionTypeOrderByName(
                jurisdictionId, Region.RegionType.SINGLE_PRECINCT);
        } else if (region.getJurisdiction() != null) {
            singles = regionRepo.findByJurisdictionIdAndRegionTypeOrderByName(
                region.getJurisdiction().getId(), Region.RegionType.SINGLE_PRECINCT);
        }

        model.addAttribute("region",             region);
        model.addAttribute("jurisdictions",      jurisdictionRepo.findAll());
        model.addAttribute("allSinglePrecincts", singles);
        model.addAttribute("formTitle",          id != null ? "Edit Region" : "New Region");
        model.addAttribute("error",              error);
        return "data/regions/form";
    }
}
