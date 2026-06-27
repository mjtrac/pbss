/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package gov.election.scanner.controller;

import gov.election.scanner.config.ScannerConfig;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/config")
public class ConfigController {

    private final ScannerConfig config;

    public ConfigController(ScannerConfig config) {
        this.config = config;
    }

    @GetMapping
    public String configPage(Model model) {
        model.addAttribute("config", config);
        model.addAttribute("loginTitle", config.loginTitle);
        return "config";
    }

    @PostMapping
    public String saveConfig(
            @RequestParam String backend,
            @RequestParam String naps2Path,
            @RequestParam String scanimagePath,
            @RequestParam(required = false, defaultValue = "") String customCommand,
            @RequestParam String outputDir,
            @RequestParam int dpi,
            @RequestParam(required = false, defaultValue = "false") boolean duplex,
            @RequestParam String source,
            @RequestParam String filenamePrefix,
            @RequestParam(required = false, defaultValue = "") String naps2Profile,
            @RequestParam(required = false, defaultValue = "") String naps2Device,
            RedirectAttributes ra) {

        config.backend       = backend;
        config.naps2Path     = naps2Path;
        config.scanimagePath = scanimagePath;
        config.customCommand = customCommand;
        config.outputDir     = outputDir;
        config.dpi           = dpi;
        config.duplex        = duplex;
        config.source        = source;
        config.filenamePrefix = filenamePrefix;
        config.naps2Profile  = naps2Profile;
        config.naps2Device   = naps2Device;

        ra.addFlashAttribute("success", "Configuration saved.");
        return "redirect:/config";
    }
}
