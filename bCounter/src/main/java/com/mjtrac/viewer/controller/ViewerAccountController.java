/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewer.controller;

import com.mjtrac.counter.entity.CounterUser;
import com.mjtrac.counter.service.CounterUserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Self-service account management for the viewer side (port 8082).
 * Shares CounterUserService with the counter side — same underlying
 * account, just a differently-styled page reachable from /viewer/**.
 */
@Controller
@RequestMapping("/viewer/account")
public class ViewerAccountController {

    private final CounterUserService userService;

    public ViewerAccountController(CounterUserService userService) {
        this.userService = userService;
    }

    @GetMapping("/password")
    public String passwordForm() {
        return "viewer/account/password";
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
            return "redirect:/viewer/account/password";
        }
        if (newPassword.length() < 8) {
            ra.addFlashAttribute("error", "New password must be at least 8 characters.");
            return "redirect:/viewer/account/password";
        }

        CounterUser user = userService.findByUsername(principal.getUsername())
            .orElseThrow(() -> new IllegalStateException("Logged-in user not found"));

        boolean ok = userService.changeOwnPassword(user.getId(), currentPassword, newPassword);
        if (!ok) {
            ra.addFlashAttribute("error", "Current password is incorrect.");
            return "redirect:/viewer/account/password";
        }

        ra.addFlashAttribute("success", "Password changed successfully.");
        return "redirect:/viewer/account/password";
    }
}
