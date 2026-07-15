/*
 * Copyright (C) 2026 Mitch Trachtenberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.mjtrac.ballot.repository;

import com.mjtrac.ballot.model.PrintLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PrintLogRepository extends JpaRepository<PrintLog, Long> {

    /**
     * Returns only print logs whose BallotCombination still exists.
     * SQLite does not enforce FK constraints so orphaned logs can accumulate
     * when combinations or elections are deleted.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT l FROM PrintLog l "
        + "WHERE l.ballotCombination IS NOT NULL "
        + "AND EXISTS (SELECT 1 FROM BallotCombination c WHERE c = l.ballotCombination)")
    java.util.List<PrintLog> findAllWithValidCombination();

    /** Delete all print logs linked to combinations of the given election. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
        "DELETE FROM PrintLog l WHERE l.ballotCombination.election = :election")
    void deleteByElection(
        @org.springframework.data.repository.query.Param("election")
        com.mjtrac.ballot.model.Election election);
    List<PrintLog> findByPrintedByIdOrderByPrintedAtDesc(Long userId);
    List<PrintLog> findByBallotCombinationIdOrderByPrintedAtDesc(Long combinationId);
}
