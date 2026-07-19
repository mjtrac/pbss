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

import com.mjtrac.ballot.model.Contest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ContestRepository extends JpaRepository<Contest, Long> {

    List<Contest> findByElectionIdOrderByDisplayOrder(Long electionId);
    List<Contest> findByElectionId(Long electionId);

    /**
     * Find contests for an election that are directly assigned to a specific region.
     * Used as step 1 of ContestAssignmentService.resolveContestsForPrecinct().
     */
    @Query("""
        SELECT c FROM Contest c
        JOIN c.assignedRegions s
        WHERE c.election.id = :electionId
          AND s.id = :regionId
        ORDER BY c.displayOrder
        """)
    List<Contest> findByElectionIdAndAssignedRegion(
        @Param("electionId") Long electionId,
        @Param("regionId") Long regionId);
}
