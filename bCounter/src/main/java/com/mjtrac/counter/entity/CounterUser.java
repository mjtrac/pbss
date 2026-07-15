/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.entity;

import jakarta.persistence.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Application user shared by the Election Counter (port 8081) and the
 * embedded bViewer (port 8082).
 *
 * Roles:
 *   ADMIN            — full access: manage users and roles, plus counter and viewer
 *   COUNTER_OPERATOR — configure, start, and stop scanning/counting on 8081
 *   VIEWER           — review scanned ballot images on 8082
 */
@Entity
@Table(name = "counter_user")
public class CounterUser {

    public enum Role { ADMIN, COUNTER_OPERATOR, VIEWER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /** BCrypt-hashed password. NEVER store or log plaintext passwords. */
    @Column(nullable = false)
    private String passwordHash;

    private boolean enabled = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "counter_user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    private Set<Role> roles = new HashSet<>();

    // ── Getters & Setters ─────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }
}
