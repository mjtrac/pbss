/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 */
package gov.election.counter.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the login page for the Election Counter.
 */
@Controller
public class LoginController {

    @Value("${viewer.server.port:8082}")
    private int viewerPort;

    @GetMapping("/login")
    public String loginPage(
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String logout,
            Model model,
            HttpServletRequest request) {
        // On the viewer port, redirect /login to /viewer/login
        if (request.getLocalPort() == viewerPort) {
            String suffix = (logout != null) ? "?logout" : (error != null) ? "?error" : "";
            return "redirect:/viewer/login" + suffix;
        }
        if (error  != null) model.addAttribute("error",  "Invalid username or password.");
        if (logout != null) model.addAttribute("message", "You have been logged out.");
        return "login";
    }
}
