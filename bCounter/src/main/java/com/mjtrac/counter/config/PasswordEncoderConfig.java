/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 */
package com.mjtrac.counter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Standalone home for the shared PasswordEncoder bean.
 *
 * Kept separate from CounterSecurityConfig/ViewerSecurityConfig: both of
 * those depend on CounterUserService, which itself needs a PasswordEncoder.
 * Defining the bean inside either security config would create a circular
 * dependency (config -> service -> config's own bean method).
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
