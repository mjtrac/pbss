/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.counter.repository;

import gov.election.counter.entity.BallotImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface BallotImageRepository extends JpaRepository<BallotImage, Long> {
    Optional<BallotImage> findByImagePath(String imagePath);
}
