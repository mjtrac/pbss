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
import com.mjtrac.ballot.service.UserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Set;

@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserService            userService;
    private final UserRepository         userRepo;
    private final PrintLogRepository     printLogRepo;
    private final JurisdictionRepository jurisdictionRepo;

    public AdminController(UserService userService,
                           UserRepository userRepo,
                           PrintLogRepository printLogRepo,
                           JurisdictionRepository jurisdictionRepo) {
        this.userService      = userService;
        this.userRepo         = userRepo;
        this.printLogRepo     = printLogRepo;
        this.jurisdictionRepo = jurisdictionRepo;
    }

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("users",     userRepo.findAll());
        // Only show logs whose BallotCombination still exists in the DB
        model.addAttribute("printLogs", printLogRepo.findAllWithValidCombination());
        return "admin/dashboard";
    }

    @GetMapping("/users/new")
    public String newUserForm(Model model) {
        model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
        model.addAttribute("roles",         User.Role.values());
        return "admin/user-form";
    }

    @PostMapping("/users")
    public String createUser(@RequestParam(required = false) String username,
                             @RequestParam(required = false) String password,
                             @RequestParam(required = false) Set<User.Role> roles,
                             @RequestParam(required = false) Long jurisdictionId,
                             Model model,
                             RedirectAttributes ra) {

        if (username == null || username.isBlank()) {
            model.addAttribute("error", "Username is required.");
            model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
            model.addAttribute("roles", User.Role.values());
            return "admin/user-form";
        }
        if (password == null || password.length() < 12) {
            model.addAttribute("error", "Password is required and must be at least 12 characters.");
            model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
            model.addAttribute("roles", User.Role.values());
            return "admin/user-form";
        }
        if (roles == null || roles.isEmpty()) {
            model.addAttribute("error", "Please select at least one role.");
            model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
            model.addAttribute("roles", User.Role.values());
            return "admin/user-form";
        }
        if (jurisdictionId == null) {
            model.addAttribute("error", "Please select a jurisdiction.");
            model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
            model.addAttribute("roles", User.Role.values());
            return "admin/user-form";
        }

        Jurisdiction jur = jurisdictionRepo.findById(jurisdictionId)
            .orElseThrow(() -> new IllegalArgumentException("Jurisdiction not found"));

        try {
            userService.createUser(username.trim(), password, roles, jur);
            ra.addFlashAttribute("success", "Created user \"" + username.trim() + "\".");
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("jurisdictions", jurisdictionRepo.findAll());
            model.addAttribute("roles", User.Role.values());
            return "admin/user-form";
        }
        return "redirect:/admin";
    }

    @PostMapping("/users/{id}/toggle")
    public String toggleUser(@PathVariable Long id, RedirectAttributes ra) {
        User user = userRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
        userService.setEnabled(id, !user.isEnabled());
        ra.addFlashAttribute("success",
            (user.isEnabled() ? "Disabled" : "Enabled") + " user \"" + user.getUsername() + "\".");
        return "redirect:/admin";
    }
}
