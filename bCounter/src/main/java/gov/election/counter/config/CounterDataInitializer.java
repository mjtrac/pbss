/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 */
package gov.election.counter.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the default admin user on first startup.
 *
 * Default credentials:
 *   username : admin
 *   password : ChangeMe123!
 *
 * Change the password via the admin UI (if implemented) or by calling
 * CounterUserStore.putHash() with a freshly encoded password.
 */
@Component
public class CounterDataInitializer implements ApplicationRunner {

    private final CounterUserStore userStore;
    private final PasswordEncoder  passwordEncoder;

    public CounterDataInitializer(CounterUserStore userStore,
                                   PasswordEncoder passwordEncoder) {
        this.userStore       = userStore;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!userStore.exists("admin")) {
            userStore.putHash("admin", passwordEncoder.encode("ChangeMe123!"));
            System.out.println("Counter: default admin user created (username=admin)");
        }
    }
}
