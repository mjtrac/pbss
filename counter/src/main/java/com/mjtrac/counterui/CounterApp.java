/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counterui;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import javax.swing.*;

/**
 * Standalone Swing control panel for just the counting engine — folders in,
 * start/stop, results out, gated by a CounterUser login (same accounts
 * blCounter/viewer use — see LoginDialog) with every attempt written to
 * AuditLogService. Headless Spring Boot underneath, driving the same
 * CountingService blCounter's JavaFX shell uses, copied here (along with
 * its entity/repository/model/service dependencies) so this module has no
 * build-time dependency on blCounter. scanBasePackages already covers
 * com.mjtrac.counter's config/repository/service/entity sub-packages, so
 * PasswordEncoderConfig, CounterUserRepository, and AuditLogService needed
 * no new wiring here for login support.
 */
@SpringBootApplication(scanBasePackages = {"com.mjtrac.counterui", "com.mjtrac.counter"})
@EntityScan("com.mjtrac.counter.entity")
@EnableJpaRepositories("com.mjtrac.counter.repository")
public class CounterApp {

    public static void main(String[] args) {
        // Spring Boot sets java.awt.headless=true by default (server-side
        // deployment assumption) — must be turned off explicitly or every
        // AWT/Swing window constructor throws HeadlessException.
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(CounterApp.class)
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
