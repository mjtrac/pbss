/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewerui;

import com.mjtrac.counter.entity.CounterUser;
import org.springframework.stereotype.Component;

/** Holds the signed-in user for the lifetime of the process — same role as blCounter's AuthContext. */
@Component
class AuthContext {

    private CounterUser currentUser;

    CounterUser getCurrentUser() { return currentUser; }

    void setCurrentUser(CounterUser user) { this.currentUser = user; }

    void clear() { this.currentUser = null; }

    boolean isSignedIn() { return currentUser != null; }
}
