/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.viewer.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class ViewerLoginController {

    @GetMapping("/viewer/login")
    public String login(@RequestParam(required = false) String error,
                        @RequestParam(required = false) String logout,
                        Model model) {
        if (error  != null) model.addAttribute("error",   "Invalid username or password.");
        if (logout != null) model.addAttribute("message", "You have been logged out.");
        return "viewer/login";
    }
}
