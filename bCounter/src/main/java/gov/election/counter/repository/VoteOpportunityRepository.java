/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.counter.repository;

import gov.election.counter.entity.VoteOpportunity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface VoteOpportunityRepository extends JpaRepository<VoteOpportunity, Long> {
    java.util.List<gov.election.counter.entity.VoteOpportunity> findByBallotImage_Id(Long ballotImageId);
    java.util.List<gov.election.counter.entity.VoteOpportunity> findByContest_Id(Long contestId);
}
