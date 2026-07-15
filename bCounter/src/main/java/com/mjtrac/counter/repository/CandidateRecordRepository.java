/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.repository;

import com.mjtrac.counter.entity.CandidateRecord;
import com.mjtrac.counter.entity.ContestRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface CandidateRecordRepository extends JpaRepository<CandidateRecord, Long> {
    Optional<CandidateRecord> findByContestAndCandidateName(
        ContestRecord contest, String candidateName);
}
