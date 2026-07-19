/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counterui;

import com.mjtrac.counter.entity.CounterUser;
import org.springframework.stereotype.Component;

/** Holds the signed-in operator for the lifetime of the process — same role as viewer's AuthContext. */
@Component
class AuthContext {

    private CounterUser currentUser;

    CounterUser getCurrentUser() { return currentUser; }

    void setCurrentUser(CounterUser user) { this.currentUser = user; }

    void clear() { this.currentUser = null; }

    boolean isSignedIn() { return currentUser != null; }
}
