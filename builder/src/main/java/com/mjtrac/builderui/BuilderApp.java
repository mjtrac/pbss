/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.swing.*;

/**
 * Standalone Swing ballot-design tool — full CRUD (jurisdictions,
 * elections, parties, ballot types, languages, regions, ballot
 * combinations, ballot design templates, contests/candidates) plus PDF
 * generation, on top of the shared builder-core engine. No login — same
 * pragmatic choice as counter/scanner, unlike bBuilder/blBuilder. The
 * Admin screen still manages User records (shared `users` table, so
 * accounts created here work when signing into bBuilder/blBuilder).
 *
 * No @EnableJpaRepositories here — builder-core's own DatabaseConfig
 * already declares it for com.mjtrac.ballot.repository (component-scanned
 * via scanBasePackages below); duplicating it throws
 * BeanDefinitionOverrideException. @EntityScan is still needed since
 * entities live outside this class's own package.
 */
@SpringBootApplication(scanBasePackages = {"com.mjtrac.builderui", "com.mjtrac.ballot"})
@EntityScan("com.mjtrac.ballot.model")
public class BuilderApp {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(BuilderApp.class)
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
