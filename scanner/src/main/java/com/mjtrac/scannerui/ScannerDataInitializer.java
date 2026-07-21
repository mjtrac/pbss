/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.scannerui;

import com.mjtrac.scanner.entity.ScannerUser;
import com.mjtrac.scanner.repository.ScannerUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds a default admin account on first startup — without this, a
 * genuinely new user (an empty or freshly-reset scanner.db, and
 * bScanner/blScanner never having run first to seed one) could never sign
 * in at all. Mirrors bScanner's own DataInitializer.
 */
@Component
class ScannerDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScannerDataInitializer.class);

    private final ScannerUserRepository repo;
    private final PasswordEncoder encoder;

    ScannerDataInitializer(ScannerUserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repo.count() == 0) {
            String pwd = "ChangeMe123!";
            repo.save(new ScannerUser("admin", encoder.encode(pwd), "ADMINISTRATOR"));
            log.warn("Created default admin user — password: {}", pwd);
            log.warn("Change the admin password immediately.");
        }
    }
}
