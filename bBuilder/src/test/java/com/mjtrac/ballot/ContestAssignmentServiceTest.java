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
package com.mjtrac.ballot;

import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.repository.ContestRepository;
import com.mjtrac.ballot.repository.RegionRepository;
import com.mjtrac.ballot.service.ContestAssignmentService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ContestAssignmentService.
 *
 * Tests the core algorithm: given a SinglePrecinct, collect contests from
 * direct assignment AND from any PrecinctGroups that contain it,
 * then deduplicate and sort by displayOrder.
 *
 * All dependencies are mocked — no database required.
 */
@ExtendWith(MockitoExtension.class)
class ContestAssignmentServiceTest {

    @Mock ContestRepository contestRepo;
    @Mock RegionRepository  regionRepo;

    @InjectMocks
    ContestAssignmentService service;

    Jurisdiction county;
    Region       precinct1;
    Region       waterDistrict;
    Election     election;
    Contest      countyContest, districtContest, precinctContest;

    @BeforeEach
    void setUp() {
        county = new Jurisdiction();
        county.setId(10L);
        county.setName("Test County");

        election = new Election();
        election.setId(100L);
        election.setJurisdiction(county);
        election.setName("Test Election");

        // A SinglePrecinct region
        precinct1 = new Region();
        precinct1.setId(1L);
        precinct1.setName("Precinct 1");
        precinct1.setJurisdiction(county);
        precinct1.setRegionType(Region.RegionType.SINGLE_PRECINCT);

        // A PrecinctGroup containing precinct1
        waterDistrict = new Region();
        waterDistrict.setId(50L);
        waterDistrict.setName("Test Water District");
        waterDistrict.setJurisdiction(county);
        waterDistrict.setRegionType(Region.RegionType.PRECINCT_GROUP);
        waterDistrict.setMembers(List.of(precinct1));

        countyContest   = contest(200L, "County Sheriff",          10);
        districtContest = contest(201L, "Water Board Director",    20);
        precinctContest = contest(202L, "Precinct Committee Member", 30);
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when region is a PrecinctGroup, not SinglePrecinct")
    void throwsWhenNotSinglePrecinct() {
        when(regionRepo.findById(50L)).thenReturn(Optional.of(waterDistrict));

        assertThatThrownBy(() -> service.resolveContestsForPrecinct(50L, 100L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PrecinctGroup");
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when region not found")
    void throwsWhenRegionNotFound() {
        when(regionRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveContestsForPrecinct(99L, 100L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Returns only directly assigned contests when SinglePrecinct has no PrecinctGroup parents")
    void directAssignmentOnly() {
        when(regionRepo.findById(1L)).thenReturn(Optional.of(precinct1));
        when(contestRepo.findByElectionIdAndAssignedRegion(100L, 1L))
            .thenReturn(List.of(precinctContest));
        when(regionRepo.findGroupsContainingMember(1L))
            .thenReturn(List.of());

        List<Contest> result = service.resolveContestsForPrecinct(1L, 100L);

        assertThat(result).containsExactly(precinctContest);
    }

    @Test
    @DisplayName("Merges direct and PrecinctGroup contests, sorted by displayOrder")
    void mergesDirectAndGroup() {
        when(regionRepo.findById(1L)).thenReturn(Optional.of(precinct1));
        when(contestRepo.findByElectionIdAndAssignedRegion(100L, 1L))
            .thenReturn(List.of(precinctContest));    // displayOrder 30
        when(regionRepo.findGroupsContainingMember(1L))
            .thenReturn(List.of(waterDistrict));
        when(contestRepo.findByElectionIdAndAssignedRegion(100L, 50L))
            .thenReturn(List.of(districtContest));    // displayOrder 20

        List<Contest> result = service.resolveContestsForPrecinct(1L, 100L);

        // districtContest (20) before precinctContest (30)
        assertThat(result).containsExactly(districtContest, precinctContest);
    }

    @Test
    @DisplayName("Deduplicates a contest assigned both directly and via PrecinctGroup")
    void deduplicatesSameContest() {
        when(regionRepo.findById(1L)).thenReturn(Optional.of(precinct1));
        when(contestRepo.findByElectionIdAndAssignedRegion(100L, 1L))
            .thenReturn(List.of(districtContest));
        when(regionRepo.findGroupsContainingMember(1L))
            .thenReturn(List.of(waterDistrict));
        when(contestRepo.findByElectionIdAndAssignedRegion(100L, 50L))
            .thenReturn(List.of(districtContest));    // same contest again

        List<Contest> result = service.resolveContestsForPrecinct(1L, 100L);

        assertThat(result).hasSize(1);
        assertThat(result).containsExactly(districtContest);
    }

    @Test
    @DisplayName("Returns empty list when no contests are assigned")
    void returnsEmptyWhenNoContests() {
        when(regionRepo.findById(1L)).thenReturn(Optional.of(precinct1));
        when(contestRepo.findByElectionIdAndAssignedRegion(anyLong(), anyLong()))
            .thenReturn(List.of());
        when(regionRepo.findGroupsContainingMember(1L))
            .thenReturn(List.of());

        assertThat(service.resolveContestsForPrecinct(1L, 100L)).isEmpty();
    }

    @Test
    @DisplayName("Collects contests from multiple PrecinctGroup parents, sorted by displayOrder")
    void multiplePrecinctGroupParents() {
        Region city = new Region();
        city.setId(51L);
        city.setName("Test City");
        city.setJurisdiction(county);
        city.setRegionType(Region.RegionType.PRECINCT_GROUP);

        Contest cityContest = contest(203L, "City Council", 15);

        when(regionRepo.findById(1L)).thenReturn(Optional.of(precinct1));
        when(contestRepo.findByElectionIdAndAssignedRegion(100L, 1L))
            .thenReturn(List.of());
        when(regionRepo.findGroupsContainingMember(1L))
            .thenReturn(List.of(waterDistrict, city));
        when(contestRepo.findByElectionIdAndAssignedRegion(100L, 50L))
            .thenReturn(List.of(districtContest));    // displayOrder 20
        when(contestRepo.findByElectionIdAndAssignedRegion(100L, 51L))
            .thenReturn(List.of(cityContest));        // displayOrder 15

        List<Contest> result = service.resolveContestsForPrecinct(1L, 100L);

        // cityContest (15) before districtContest (20)
        assertThat(result).containsExactly(cityContest, districtContest);
    }

    private Contest contest(Long id, String title, int displayOrder) {
        Contest c = new Contest();
        c.setId(id);
        c.setTitle(title);
        c.setDisplayOrder(displayOrder);
        c.setVotingMethod(Contest.VotingMethod.PLURALITY);
        c.setMaxChoices(1);
        c.setElection(election);
        c.setCandidates(List.of());
        return c;
    }
}
