/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.repository;

import com.mjtrac.counter.entity.ContestRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ContestRecordRepository extends JpaRepository<ContestRecord, Long> {
    Optional<ContestRecord> findByContestTitleAndContestType(String title, String type);
}
