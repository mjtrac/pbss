/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.scanner.controller;

import com.mjtrac.scanner.config.ScannerConfig;
import com.mjtrac.scanner.entity.ScannerUser;
import com.mjtrac.scanner.repository.ScannerUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/users")
public class UsersController {

    private final ScannerUserRepository repo;
    private final PasswordEncoder       encoder;
    private final ScannerConfig         config;

    public UsersController(ScannerUserRepository repo,
                           PasswordEncoder encoder,
                           ScannerConfig config) {
        this.repo    = repo;
        this.encoder = encoder;
        this.config  = config;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users",      repo.findAll());
        model.addAttribute("loginTitle", config.loginTitle);
        return "users";
    }

    @PostMapping("/add")
    public String addUser(@RequestParam String username,
                          @RequestParam String password,
                          @RequestParam String role,
                          RedirectAttributes ra) {
        if (repo.findByUsername(username).isPresent()) {
            ra.addFlashAttribute("error", "Username already exists: " + username);
            return "redirect:/users";
        }
        if (!role.equals("ADMINISTRATOR") && !role.equals("OPERATOR")) {
            ra.addFlashAttribute("error", "Invalid role: " + role);
            return "redirect:/users";
        }
        repo.save(new ScannerUser(username, encoder.encode(password), role));
        ra.addFlashAttribute("success", "User " + username + " created.");
        return "redirect:/users";
    }

    @PostMapping("/password")
    public String changePassword(@RequestParam Long id,
                                 @RequestParam String password,
                                 RedirectAttributes ra) {
        repo.findById(id).ifPresent(u -> {
            u.setPasswordHash(encoder.encode(password));
            repo.save(u);
        });
        ra.addFlashAttribute("success", "Password updated.");
        return "redirect:/users";
    }

    @PostMapping("/delete")
    public String deleteUser(@RequestParam Long id, RedirectAttributes ra) {
        if (repo.count() <= 1) {
            ra.addFlashAttribute("error", "Cannot delete the last user.");
            return "redirect:/users";
        }
        repo.deleteById(id);
        ra.addFlashAttribute("success", "User deleted.");
        return "redirect:/users";
    }
}
