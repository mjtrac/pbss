/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.repository;

import com.mjtrac.counter.entity.VoteOpportunity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface VoteOpportunityRepository extends JpaRepository<VoteOpportunity, Long> {
    java.util.List<com.mjtrac.counter.entity.VoteOpportunity> findByBallotImage_Id(Long ballotImageId);
    java.util.List<com.mjtrac.counter.entity.VoteOpportunity> findByContest_Id(Long contestId);
}
