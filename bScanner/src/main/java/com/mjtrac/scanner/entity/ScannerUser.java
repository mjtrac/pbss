/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.scanner.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "scanner_users")
public class ScannerUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    /** ADMINISTRATOR or OPERATOR */
    @Column(nullable = false)
    private String role;

    public ScannerUser() {}

    public ScannerUser(String username, String passwordHash, String role) {
        this.username     = username;
        this.passwordHash = passwordHash;
        this.role         = role;
    }

    public Long   getId()           { return id; }
    public String getUsername()     { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole()         { return role; }

    public void setUsername(String v)     { this.username = v; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public void setRole(String v)         { this.role = v; }
}
