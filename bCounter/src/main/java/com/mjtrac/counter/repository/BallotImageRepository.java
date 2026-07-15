/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.repository;

import com.mjtrac.counter.entity.BallotImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface BallotImageRepository extends JpaRepository<BallotImage, Long> {
    Optional<BallotImage> findByImagePath(String imagePath);
}
