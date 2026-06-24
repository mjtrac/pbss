/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 */
package gov.election.counter.config;

import gov.election.counter.service.AuditLogService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.*;

/**
 * Spring Security configuration for the Election Counter.
 *
 * All routes require authentication. The login page (/login) is publicly
 * accessible and styled distinctly from the ballot generator's login.
 */
@Configuration
@EnableWebSecurity
@org.springframework.core.annotation.Order(2)
public class CounterSecurityConfig {

    private final CounterUserStore userStore;
    private final AuditLogService  auditLog;

    public CounterSecurityConfig(CounterUserStore userStore,
                                  AuditLogService auditLog) {
        this.userStore = userStore;
        this.auditLog  = auditLog;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            String hash = userStore.getHash(username);
            if (hash == null)
                throw new UsernameNotFoundException("User not found: " + username);
            return User.withUsername(username)
                       .password(hash)
                       .roles("USER")
                       .build();
        };
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
                .anyRequest().authenticated()
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

    private org.springframework.security.authentication.dao.DaoAuthenticationProvider authenticationProvider() {
        var provider = new org.springframework.security.authentication.dao.DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService());
        provider.setPasswordEncoder(passwordEncoder());
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
