/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.scannerui;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.swing.*;

/**
 * Standalone Swing control panel for just the scanner-driving engine —
 * output folder in, start/stop, start/end notes, results out, gated by a
 * ScannerUser login (LoginDialog) with every attempt written to
 * scanner-core's AuditLogService. Headless Spring Boot underneath driving
 * ScanService, copied here (via scanner-core) so this module has no
 * build-time dependency on bScanner/blScanner. PasswordEncoder is defined
 * locally, not shared via scanner-core — same as blScanner's own
 * ScannerFxApplication and bScanner's SecurityConfig, both of which also
 * define their own bean rather than sharing one (unlike the counter family,
 * where it lives in counter-core).
 */
@SpringBootApplication(scanBasePackages = {"com.mjtrac.scannerui", "com.mjtrac.scanner"})
@EntityScan("com.mjtrac.scanner.entity")
@EnableJpaRepositories("com.mjtrac.scanner.repository")
public class ScannerApp {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    public static void main(String[] args) {
        // Must run before the Spring context is built below, not after: the
        // context eagerly constructs MainFrame as part of bean
        // initialization, so a look-and-feel installed afterward has
        // nothing left to affect. (The old post-hoc
        // UIManager.setLookAndFeel() call this replaces ran too late to
        // matter for the same reason — it only affects components created
        // after it runs, and MainFrame was already fully built by then.)
        PbssTheme.install();

        // Spring Boot sets java.awt.headless=true by default — must be
        // turned off explicitly or every AWT/Swing window constructor
        // throws HeadlessException.
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ScannerApp.class)
            .web(WebApplicationType.NONE)
            .headless(false)
            .run(args);

        SwingUtilities.invokeLater(() -> ctx.getBean(MainFrame.class).start());
    }
}
