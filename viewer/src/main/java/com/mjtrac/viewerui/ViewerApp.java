/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewerui;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import javax.swing.*;

/**
 * Standalone Swing ballot image viewer. Headless Spring Boot underneath
 * (JPA + password hashing only, same pattern as blScanner/blBuilder/
 * blCounter) — but the UI is plain Swing, not JavaFX/WebView, specifically
 * to avoid the WebView rendering limitation documented in
 * blCounter/README.md. Reads the same counter_results.db bCounter/blCounter
 * write to; never writes to it (see application.properties' ddl-auto).
 */
@SpringBootApplication(scanBasePackages = {"com.mjtrac.viewerui", "com.mjtrac.counter", "com.mjtrac.viewer"})
@EntityScan("com.mjtrac.counter.entity")
@EnableJpaRepositories("com.mjtrac.counter.repository")
public class ViewerApp {

    public static void main(String[] args) {
        // Spring Boot sets java.awt.headless=true by default (server-side
        // deployment assumption) — must be turned off explicitly or every
        // AWT/Swing window constructor throws HeadlessException.
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ViewerApp.class)
            .web(WebApplicationType.NONE)
            .headless(false)
            .run(args);

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Fall back to the default cross-platform look and feel.
            }
            ctx.getBean(MainFrame.class).start();
        });
    }
}
