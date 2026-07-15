/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.repository;

import com.mjtrac.counter.entity.CounterUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CounterUserRepository extends JpaRepository<CounterUser, Long> {
    Optional<CounterUser> findByUsername(String username);
    boolean existsByUsername(String username);
}
