/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 */
package com.mjtrac.counter.controller;

import com.mjtrac.counter.entity.CounterUser;
import com.mjtrac.counter.repository.CounterUserRepository;
import com.mjtrac.counter.service.AuditLogService;
import com.mjtrac.counter.service.CounterUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Set;

/**
 * User and role management for the counter/viewer shared account store.
 * ADMIN-only.
 */
@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final CounterUserService    userService;
    private final CounterUserRepository userRepo;
    private final AuditLogService       auditLog;

    @Value("${server.port:8081}")
    private int counterPort;

    @Value("${viewer.server.port:8082}")
    private int viewerPort;

    public AdminController(CounterUserService userService,
                            CounterUserRepository userRepo,
                            AuditLogService auditLog) {
        this.userService = userService;
        this.userRepo    = userRepo;
        this.auditLog    = auditLog;
    }

    /** So user-form.html's role descriptions show the actual configured ports, not hardcoded ones. */
    @ModelAttribute
    public void addPorts(Model model) {
        model.addAttribute("counterPort", counterPort);
        model.addAttribute("viewerPort", viewerPort);
    }

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("users", userRepo.findAll());
        return "admin/dashboard";
    }

    @GetMapping("/users/new")
    public String newUserForm(Model model) {
        model.addAttribute("roles", CounterUser.Role.values());
        return "admin/user-form";
    }

    @PostMapping("/users")
    public String createUser(@AuthenticationPrincipal UserDetails principal,
                              @RequestParam(required = false) String username,
                              @RequestParam(required = false) String password,
                              @RequestParam(required = false) Set<CounterUser.Role> roles,
                              Model model,
                              RedirectAttributes ra) {

        if (username == null || username.isBlank()) {
            model.addAttribute("error", "Username is required.");
            model.addAttribute("roles", CounterUser.Role.values());
            return "admin/user-form";
        }
        if (password == null || password.length() < 12) {
            model.addAttribute("error", "Password is required and must be at least 12 characters.");
            model.addAttribute("roles", CounterUser.Role.values());
            return "admin/user-form";
        }
        if (roles == null || roles.isEmpty()) {
            model.addAttribute("error", "Please select at least one role.");
            model.addAttribute("roles", CounterUser.Role.values());
            return "admin/user-form";
        }

        try {
            userService.createUser(username.trim(), password, roles);
            auditLog.log("USER_CREATED", principal.getUsername(), username.trim() + " roles=" + roles);
            ra.addFlashAttribute("success", "Created user \"" + username.trim() + "\".");
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("roles", CounterUser.Role.values());
            return "admin/user-form";
        }
        return "redirect:/admin";
    }

    @PostMapping("/users/{id}/toggle")
    public String toggleUser(@AuthenticationPrincipal UserDetails principal,
                              @PathVariable Long id, RedirectAttributes ra) {
        CounterUser user = userRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
        userService.setEnabled(id, !user.isEnabled());
        auditLog.log(user.isEnabled() ? "USER_DISABLED" : "USER_ENABLED",
            principal.getUsername(), user.getUsername());
        ra.addFlashAttribute("success",
            (user.isEnabled() ? "Disabled" : "Enabled") + " user \"" + user.getUsername() + "\".");
        return "redirect:/admin";
    }

    @PostMapping("/users/{id}/roles")
    public String updateRoles(@AuthenticationPrincipal UserDetails principal,
                               @PathVariable Long id,
                               @RequestParam(required = false) Set<CounterUser.Role> roles,
                               RedirectAttributes ra) {
        CounterUser user = userRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Set<CounterUser.Role> newRoles = roles == null ? Set.of() : roles;
        if (newRoles.isEmpty()) {
            ra.addFlashAttribute("error", "A user must have at least one role.");
            return "redirect:/admin";
        }
        userService.updateRoles(id, newRoles);
        auditLog.log("ROLES_UPDATED", principal.getUsername(),
            user.getUsername() + " -> " + newRoles);
        ra.addFlashAttribute("success", "Updated roles for \"" + user.getUsername() + "\".");
        return "redirect:/admin";
    }

    @PostMapping("/users/{id}/reset-password")
    public String resetPassword(@AuthenticationPrincipal UserDetails principal,
                                 @PathVariable Long id,
                                 @RequestParam String newPassword,
                                 RedirectAttributes ra) {
        CounterUser user = userRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (newPassword == null || newPassword.length() < 8) {
            ra.addFlashAttribute("error", "New password must be at least 8 characters.");
            return "redirect:/admin";
        }
        userService.changePassword(id, newPassword);
        auditLog.log("PASSWORD_RESET", principal.getUsername(), user.getUsername());
        ra.addFlashAttribute("success", "Password reset for \"" + user.getUsername() + "\".");
        return "redirect:/admin";
    }
}
