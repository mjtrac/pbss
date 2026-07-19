/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.scanner.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds all scanner configuration, read from application.properties.
 * Values can be overridden at runtime by ADMINISTRATOR via /config page.
 */
@Component
public class ScannerConfig {

    @Value("${scanner.backend:naps2}")
    public String backend;

    @Value("${scanner.naps2.path:/Applications/NAPS2.app/Contents/MacOS/naps2}")
    public String naps2Path;

    @Value("${scanner.scanimage.path:/usr/bin/scanimage}")
    public String scanimagePath;

    @Value("${scanner.custom.command:}")
    public String customCommand;

    @Value("${scanner.naps2.profile:}")
    public String naps2Profile;

    @Value("${scanner.naps2.device:}")
    public String naps2Device;

    @Value("${scanner.output.dir:${user.home}/pbss/test-harness/images/scanned}")
    public String outputDir;

    @Value("${scanner.dpi:300}")
    public int dpi;

    @Value("${scanner.duplex:true}")
    public boolean duplex;

    @Value("${scanner.source:feeder}")
    public String source;

    @Value("${scanner.filename.prefix:ballot_scan_}")
    public String filenamePrefix;

    @Value("${scanner.batch-log.dir:}")
    public String batchLogDir;

    @Value("${app.login-title:bScanner — Election Ballot Scanner}")
    public String loginTitle;

    // ── Start/end notes → optional flag-page printing ──────────────────────
    // Off by default. A printing failure must never stop scanning — see
    // FlagPagePrinter, which swallows and logs every exception itself.
    @Value("${scanner.notes.print-flag-pages:false}")
    public boolean printFlagPages;

    /** Printer name (as returned by PrintServiceLookup); blank = system default printer. */
    @Value("${scanner.notes.printer-name:}")
    public String printerName;

    /**
     * True only for backends where dpi/duplex are actually passed through to
     * the scan command (see ScanService.buildNaps2Command/buildScanimageCommand/
     * buildCustomCommand) — a UI showing these controls for a backend that
     * silently ignores them would be misleading.
     */
    public boolean supportsDpi() {
        return "naps2".equalsIgnoreCase(backend) || "scanimage".equalsIgnoreCase(backend);
    }

    /** Only NAPS2's command building actually branches on duplex (see buildNaps2Command). */
    public boolean supportsDuplex() {
        return "naps2".equalsIgnoreCase(backend);
    }
}
