/*
 * Copyright (C) 2026 Mitch Trachtenberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package gov.election.ballot.service;

import gov.election.ballot.model.Jurisdiction;
import gov.election.ballot.model.User;
import gov.election.ballot.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implements Spring Security's UserDetailsService for form-login authentication.
 * Also provides user management methods (create, update password, enable/disable).
 */
@Service
public class UserService implements UserDetailsService {

    private final UserRepository  userRepo;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo        = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepo.findByUsername(username)
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
    public User createUser(String username, String rawPassword,
                           Set<User.Role> roles, Jurisdiction jurisdiction) {
        if (userRepo.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists: " + username);
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRoles(roles);
        user.setJurisdiction(jurisdiction);
        user.setEnabled(true);
        return userRepo.save(user);
    }

    public java.util.Optional<User> findByUsername(String username) {
        return userRepo.findByUsername(username);
    }

    @Transactional
    public void changePassword(Long userId, String newRawPassword) {
        User user = userRepo.findById(userId)
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
        User user = userRepo.findById(userId)
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
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setEnabled(enabled);
        userRepo.save(user);
    }
}
