/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.viewer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;

/**
 * Security configuration for the bViewer UI (port 8082).
 *
 * Applies only to requests arriving on viewer.server.port (8082).
 * Counter requests on port 8081 are handled by CounterSecurityConfig.
 *
 * Uses a simple in-memory user store — credentials can be overridden via
 * viewer.username and viewer.password in application.properties.
 */
@Configuration
@Order(1)
public class ViewerSecurityConfig {

    @Value("${viewer.username:admin}")
    private String viewerUsername;

    @Value("${viewer.password:ChangeMe123!}")
    private String viewerPassword;

    @Bean
    @Order(1)
    public SecurityFilterChain viewerFilterChain(HttpSecurity http) throws Exception {
        // Build user store inline — no @Bean to avoid PasswordEncoder conflicts
        // with CounterSecurityConfig's encoder bean.
        org.springframework.security.crypto.password.PasswordEncoder enc =
            new BCryptPasswordEncoder();
        UserDetails user = User.withUsername(viewerUsername)
            .password(enc.encode(viewerPassword))
            .roles("VIEWER")
            .build();
        org.springframework.security.provisioning.InMemoryUserDetailsManager udm =
            new org.springframework.security.provisioning.InMemoryUserDetailsManager(user);

        http
            .securityMatcher("/viewer/**", "/css/**", "/favicon.ico")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/viewer/login",
                                 "/css/**", "/favicon.ico").permitAll()
                .anyRequest().authenticated()
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
            .userDetailsService(udm)
            .sessionManagement(sm -> sm
                .sessionFixation().changeSessionId()
            );
        return http.build();
    }
}
