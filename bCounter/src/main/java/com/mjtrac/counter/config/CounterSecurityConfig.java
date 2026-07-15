/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 */
package com.mjtrac.counter.config;

import com.mjtrac.counter.service.AuditLogService;
import com.mjtrac.counter.service.CounterUserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.*;

/**
 * Spring Security configuration for the Election Counter.
 *
 * All routes require authentication. The login page (/login) is publicly
 * accessible and styled distinctly from the ballot generator's login.
 *
 * Route access is role-gated:
 *   /admin/**   — ADMIN only (user and role management)
 *   /account/** — any authenticated counter-store user (self-service password change)
 *   everything else — ADMIN or COUNTER_OPERATOR
 *
 * /viewer/** is explicitly permitted here so ViewerSecurityConfig's chain
 * (which shares the same CounterUserService) can handle it instead.
 */
@Configuration
@EnableWebSecurity
@org.springframework.core.annotation.Order(2)
public class CounterSecurityConfig {

    private final CounterUserService userService;
    private final AuditLogService    auditLog;
    private final PasswordEncoder    passwordEncoder;

    public CounterSecurityConfig(CounterUserService userService,
                                  AuditLogService auditLog,
                                  PasswordEncoder passwordEncoder) {
        this.userService    = userService;
        this.auditLog       = auditLog;
        this.passwordEncoder = passwordEncoder;
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Explicitly wire our UserDetailsService to avoid ambiguity with ViewerSecurityConfig
        http.authenticationProvider(authenticationProvider());
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/favicon.ico").permitAll()
                // Let the viewer security chain handle all /viewer/** requests
                .requestMatchers("/viewer/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/account/**").authenticated()
                // /progress is polled from the viewer UI (port 8082) as a same-origin
                // fetch, so VIEWER must be able to read scan status too.
                .requestMatchers("/progress").hasAnyRole("ADMIN", "COUNTER_OPERATOR", "VIEWER")
                // Full results (including RCV runoff rounds and scribble reports) are
                // reachable from the viewer side too — same path, same controller,
                // just also permitted for VIEWER since it's a same-origin navigation
                // regardless of which port served the page.
                .requestMatchers("/results", "/rcv-report", "/scribble-report", "/scribble-image")
                    .hasAnyRole("ADMIN", "COUNTER_OPERATOR", "VIEWER")
                .anyRequest().hasAnyRole("ADMIN", "COUNTER_OPERATOR")
            )
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .successHandler(loginSuccessHandler())
                .failureHandler(loginFailureHandler())
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .addLogoutHandler((request, response, auth) -> {
                    if (auth != null)
                        auditLog.log("LOGOUT", auth.getName(), null);
                })
                .permitAll()
            );
        return http.build();
    }

    private DaoAuthenticationProvider authenticationProvider() {
        var provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
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
