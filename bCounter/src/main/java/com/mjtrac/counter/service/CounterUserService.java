/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.service;

import com.mjtrac.counter.entity.CounterUser;
import com.mjtrac.counter.repository.CounterUserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implements Spring Security's UserDetailsService for both the counter
 * (port 8081) and viewer (port 8082) login forms — a single CounterUser
 * store backs both, with access differentiated by role.
 *
 * Also provides user management (create, password change, role grants).
 */
@Service
public class CounterUserService implements UserDetailsService {

    private final CounterUserRepository userRepo;
    private final PasswordEncoder       passwordEncoder;

    public CounterUserService(CounterUserRepository userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo        = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        CounterUser user = userRepo.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        var authorities = user.getRoles().stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
            .collect(Collectors.toList());

        return new org.springframework.security.core.userdetails.User(
            user.getUsername(),
            user.getPasswordHash(),
            user.isEnabled(),
            true, true, true,
            authorities
        );
    }

    @Transactional
    public CounterUser createUser(String username, String rawPassword, Set<CounterUser.Role> roles) {
        if (userRepo.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists: " + username);
        }
        CounterUser user = new CounterUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRoles(roles);
        user.setEnabled(true);
        return userRepo.save(user);
    }

    public Optional<CounterUser> findByUsername(String username) {
        return userRepo.findByUsername(username);
    }

    /** Admin-driven password reset — no current-password check. */
    @Transactional
    public void changePassword(Long userId, String newRawPassword) {
        CounterUser user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setPasswordHash(passwordEncoder.encode(newRawPassword));
        userRepo.save(user);
    }

    /**
     * Self-service password change — verifies current password before updating.
     * Returns true on success, false if currentPassword is incorrect.
     */
    @Transactional
    public boolean changeOwnPassword(Long userId, String currentRawPassword, String newRawPassword) {
        CounterUser user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        if (!passwordEncoder.matches(currentRawPassword, user.getPasswordHash())) {
            return false;
        }
        user.setPasswordHash(passwordEncoder.encode(newRawPassword));
        userRepo.save(user);
        return true;
    }

    @Transactional
    public void setEnabled(Long userId, boolean enabled) {
        CounterUser user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setEnabled(enabled);
        userRepo.save(user);
    }

    /** Grants/denies roles by replacing a user's role set outright. */
    @Transactional
    public void updateRoles(Long userId, Set<CounterUser.Role> roles) {
        CounterUser user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setRoles(roles);
        userRepo.save(user);
    }
}
