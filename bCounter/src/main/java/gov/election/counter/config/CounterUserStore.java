/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 */
package gov.election.counter.config;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Simple in-memory user store for the election counter.
 * Users are stored as username → BCrypt-hashed password.
 * The default admin account is seeded at startup.
 *
 * This is intentionally lightweight — no database required.
 * Passwords are BCrypt-hashed using Spring Security's encoder.
 */
@Component
public class CounterUserStore {

    /** username → BCrypt hash */
    private final Map<String, String> users = new ConcurrentHashMap<>();

    public CounterUserStore() {
        // Default admin seeded by CounterDataInitializer at startup
        // (password encoded there via BCryptPasswordEncoder)
    }

    /** Returns the BCrypt-hashed password for the given username, or null if not found. */
    public String getHash(String username) {
        return users.get(username);
    }

    public boolean exists(String username) {
        return users.containsKey(username);
    }

    /**
     * Add or update a user. The password must already be BCrypt-hashed.
     */
    public void putHash(String username, String bcryptHash) {
        users.put(username, bcryptHash);
    }

    public void remove(String username) {
        users.remove(username);
    }

    public Iterable<String> usernames() {
        return users.keySet();
    }
}
