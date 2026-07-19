/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.scanner.controller;

import com.mjtrac.scanner.config.ScannerConfig;
import com.mjtrac.scanner.model.ScanSession;
import com.mjtrac.scanner.service.ScanService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
public class MainController {

    private final ScanService   scanService;
    private final ScannerConfig config;

    public MainController(ScanService scanService, ScannerConfig config) {
        this.scanService = scanService;
        this.config      = config;
    }

    // ── Home / scan page ──────────────────────────────────────────────────────

    @GetMapping("/")
    public String home(Model model) {
        ScanSession s = scanService.getSession();
        model.addAttribute("session",    s);
        model.addAttribute("config",     config);
        model.addAttribute("loginTitle", config.loginTitle);
        return "index";
    }

    // ── Scan control ──────────────────────────────────────────────────────────

    @PostMapping("/scan/start")
    @ResponseBody
    public Map<String, Object> startScan(
            @RequestParam(required = false, defaultValue = "") String comment) {
        try {
            scanService.startScan(comment.trim());
            return Map.of("status", "started");
        } catch (IllegalStateException e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @PostMapping("/scan/stop")
    @ResponseBody
    public Map<String, Object> stopScan() {
        scanService.stopScan();
        return Map.of("status", "stopped");
    }

    @PostMapping("/scan/end-note")
    @ResponseBody
    public Map<String, Object> saveEndNote(@RequestParam String note) {
        if (note == null || note.isBlank()) {
            return Map.of("status", "error", "message", "Note is empty.");
        }
        scanService.saveEndNote(note.trim());
        return Map.of("status", "saved");
    }

    // ── Progress polling ──────────────────────────────────────────────────────

    @GetMapping("/status")
    @ResponseBody
    public Map<String, Object> status() {
        ScanSession s = scanService.getSession();
        return Map.of(
            "scanning",      s.scanning,
            "complete",      s.complete,
            "imagesScanned", s.imagesScanned,
            "lastFile",      s.lastFile != null ? s.lastFile : "",
            "error",         s.error    != null ? s.error    : "",
            "elapsedMs",     s.startedAt > 0
                             ? (s.completedAt > 0
                                ? s.completedAt - s.startedAt
                                : System.currentTimeMillis() - s.startedAt)
                             : 0
        );
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
                        @RequestParam(required = false) String logout,
                        Model model) {
        model.addAttribute("loginTitle", config.loginTitle);
        if (error  != null) model.addAttribute("error",  "Invalid username or password.");
        if (logout != null) model.addAttribute("logout", "You have been logged out.");
        return "login";
    }
}
