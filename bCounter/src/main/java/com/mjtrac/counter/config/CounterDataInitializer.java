/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 */
package com.mjtrac.counter.config;

import com.mjtrac.counter.entity.CounterUser;
import com.mjtrac.counter.service.CounterUserService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Seeds the default admin user on first startup.
 *
 * Default credentials:
 *   username : admin
 *   password : ChangeMe123!
 *
 * Change the password via /account/password once logged in, or create
 * additional users with COUNTER_OPERATOR / VIEWER roles via /admin.
 */
@Component
public class CounterDataInitializer implements ApplicationRunner {

    private final CounterUserService userService;

    public CounterDataInitializer(CounterUserService userService) {
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userService.findByUsername("admin").isEmpty()) {
            userService.createUser("admin", "ChangeMe123!", Set.of(CounterUser.Role.ADMIN));
            System.out.println("Counter: default admin user created (username=admin)");
        }
    }
}
