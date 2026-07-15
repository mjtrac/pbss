/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewer.config;

import com.mjtrac.counter.service.CounterUserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for the bViewer UI (port 8082).
 *
 * Applies only to requests under /viewer/**. Everything else (port 8081)
 * is handled by CounterSecurityConfig.
 *
 * Shares CounterUserService with the counter chain — the same user store
 * backs both logins, differentiated by role:
 *   /viewer/account/** — any authenticated counter-store user (self-service password change)
 *   everything else under /viewer/** — ADMIN or VIEWER
 */
@Configuration
@Order(1)
public class ViewerSecurityConfig {

    private final CounterUserService userService;
    private final PasswordEncoder    passwordEncoder;

    public ViewerSecurityConfig(CounterUserService userService, PasswordEncoder passwordEncoder) {
        this.userService     = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain viewerFilterChain(HttpSecurity http) throws Exception {
        var provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userService);
        provider.setPasswordEncoder(passwordEncoder);

        http
            .securityMatcher("/viewer/**", "/css/**", "/favicon.ico")
            .authenticationProvider(provider)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/viewer/login",
                                 "/css/**", "/favicon.ico").permitAll()
                .requestMatchers("/viewer/account/**").authenticated()
                .anyRequest().hasAnyRole("ADMIN", "VIEWER")
            )
            .formLogin(form -> form
                .loginPage("/viewer/login")
                .loginProcessingUrl("/viewer/login")
                .defaultSuccessUrl("/viewer/", true)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/viewer/logout")
                .logoutSuccessUrl("/viewer/login?logout")
                .permitAll()
            )
            .sessionManagement(sm -> sm
                .sessionFixation().changeSessionId()
            );
        return http.build();
    }
}
