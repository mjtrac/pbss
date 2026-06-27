/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package gov.election.scanner.config;

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

    @Value("${scanner.output.dir:${user.home}/bSuite/test-harness/images/scanned}")
    public String outputDir;

    @Value("${scanner.dpi:300}")
    public int dpi;

    @Value("${scanner.duplex:true}")
    public boolean duplex;

    @Value("${scanner.source:feeder}")
    public String source;

    @Value("${scanner.filename.prefix:ballot_scan_}")
    public String filenamePrefix;

    @Value("${app.login-title:bScanner — Election Ballot Scanner}")
    public String loginTitle;
}
