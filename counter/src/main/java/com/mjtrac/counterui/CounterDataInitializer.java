/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counterui;

import com.mjtrac.counter.entity.CounterUser;
import com.mjtrac.counter.repository.CounterUserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Seeds a default admin account on first startup — without this, a
 * genuinely new user (an empty or freshly-reset counter_results.db, and
 * bCounter/blCounter never having run first to seed one) could never sign
 * in at all, since counter has no login screen of its own to create the
 * first account. Mirrors bCounter's own CounterDataInitializer; this one
 * goes through CounterUserRepository + PasswordEncoder directly (no
 * CounterUserService here — see counter-core/README.md's "What's
 * deliberately not in it").
 *
 * Default credentials:
 *   username : admin
 *   password : ChangeMe123!
 *
 * Change the password by creating a new account and disabling this one,
 * or edit counter_user directly — this app has no account-management
 * screen of its own (see counter/README.md).
 */
@Component
class CounterDataInitializer implements ApplicationRunner {

    private final CounterUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    CounterDataInitializer(CounterUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.findByUsername("admin").isEmpty()) {
            CounterUser admin = new CounterUser();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode("ChangeMe123!"));
            admin.setEnabled(true);
            admin.setRoles(Set.of(CounterUser.Role.ADMIN));
            userRepository.save(admin);
            System.out.println("counter: default admin user created (username=admin)");
        }
    }
}
