/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * bBuilder — licensed under the GNU General Public License v3.
 */
package gov.election.ballot.controller;

import gov.election.ballot.model.User;
import gov.election.ballot.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Self-service account management — available to all authenticated users.
 * Currently supports password change only; user/role management is admin-only.
 */
@Controller
@RequestMapping("/account")
public class AccountController {

    private final UserService userService;

    public AccountController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/password")
    public String passwordForm() {
        return "account/password";
    }

    @PostMapping("/password")
    public String changePassword(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam String currentPassword,
            @RequestParam String newPassword,
            @RequestParam String confirmPassword,
            RedirectAttributes ra) {

        if (!newPassword.equals(confirmPassword)) {
            ra.addFlashAttribute("error", "New passwords do not match.");
            return "redirect:/account/password";
        }
        if (newPassword.length() < 8) {
            ra.addFlashAttribute("error", "New password must be at least 8 characters.");
            return "redirect:/account/password";
        }

        User user = userService.findByUsername(principal.getUsername())
            .orElseThrow(() -> new IllegalStateException("Logged-in user not found"));

        boolean ok = userService.changeOwnPassword(user.getId(), currentPassword, newPassword);
        if (!ok) {
            ra.addFlashAttribute("error", "Current password is incorrect.");
            return "redirect:/account/password";
        }

        ra.addFlashAttribute("success", "Password changed successfully.");
        return "redirect:/account/password";
    }
}
