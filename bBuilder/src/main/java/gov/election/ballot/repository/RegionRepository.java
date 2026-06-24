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
package gov.election.ballot.repository;

import gov.election.ballot.model.Region;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface RegionRepository extends JpaRepository<Region, Long> {

    List<Region> findByJurisdictionIdOrderByName(Long jurisdictionId);

    /** Only SinglePrecinct regions for a jurisdiction. */
    List<Region> findByJurisdictionIdAndRegionTypeOrderByName(
        Long jurisdictionId, Region.RegionType regionType);

    /**
     * Find all PrecinctGroup regions that list the given SinglePrecinct as a member.
     * Used by ContestAssignmentService to resolve inherited contests.
     */
    @Query("SELECT r FROM Region r JOIN r.members m WHERE m.id = :memberId")
    List<Region> findGroupsContainingMember(@Param("memberId") Long memberId);
}
