/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.scanner.config;

import com.mjtrac.scanner.service.AuditLogService;
import com.mjtrac.scanner.service.ScannerUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.*;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ScannerUserDetailsService userDetailsService;
    private final AuditLogService auditLog;

    public SecurityConfig(ScannerUserDetailsService userDetailsService, AuditLogService auditLog) {
        this.userDetailsService = userDetailsService;
        this.auditLog = auditLog;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public DaoAuthenticationProvider authProvider() {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(userDetailsService);
        p.setPasswordEncoder(passwordEncoder());
        return p;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authenticationProvider(authProvider())
            .authorizeHttpRequests(auth -> auth
                // Admin-only: config and user management
                .requestMatchers("/config/**", "/users/**").hasRole("ADMINISTRATOR")
                // Operators and admins: scanning
                .requestMatchers("/", "/scan/**", "/status/**").hasAnyRole("ADMINISTRATOR", "OPERATOR")
                .requestMatchers("/login", "/css/**").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(loginSuccessHandler())
                .failureHandler(loginFailureHandler())
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .addLogoutHandler((request, response, auth) -> {
                    if (auth != null)
                        auditLog.log("LOGOUT", auth.getName(), null);
                })
                .permitAll()
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/scan/start", "/scan/stop", "/scan/end-note")
            );
        return http.build();
    }

    private AuthenticationSuccessHandler loginSuccessHandler() {
        return (request, response, auth) -> {
            auditLog.log("LOGIN", auth.getName(), null);
            response.sendRedirect("/");
        };
    }

    private AuthenticationFailureHandler loginFailureHandler() {
        return (request, response, ex) -> {
            String attempted = request.getParameter("username");
            auditLog.log("LOGIN_FAILED", attempted != null ? attempted : "(unknown)", null);
            response.sendRedirect("/login?error");
        };
    }
}
