/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.scanner.service;

import com.mjtrac.scanner.entity.ScannerUser;
import com.mjtrac.scanner.repository.ScannerUserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ScannerUserDetailsService implements UserDetailsService {

    private final ScannerUserRepository repo;

    public ScannerUserDetailsService(ScannerUserRepository repo) {
        this.repo = repo;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        ScannerUser u = repo.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        return new org.springframework.security.core.userdetails.User(
            u.getUsername(), u.getPasswordHash(),
            List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole())));
    }
}
