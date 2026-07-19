/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.scannerui;

import com.mjtrac.scanner.entity.ScannerUser;
import org.springframework.stereotype.Component;

/** Holds the signed-in operator for the lifetime of the process — same role as counter's AuthContext. */
@Component
class AuthContext {

    private ScannerUser currentUser;

    ScannerUser getCurrentUser() { return currentUser; }

    void setCurrentUser(ScannerUser user) { this.currentUser = user; }

    void clear() { this.currentUser = null; }

    boolean isSignedIn() { return currentUser != null; }
}
