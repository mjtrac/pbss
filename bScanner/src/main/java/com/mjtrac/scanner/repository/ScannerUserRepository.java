/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.scanner.repository;

import com.mjtrac.scanner.entity.ScannerUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ScannerUserRepository extends JpaRepository<ScannerUser, Long> {
    Optional<ScannerUser> findByUsername(String username);
}
