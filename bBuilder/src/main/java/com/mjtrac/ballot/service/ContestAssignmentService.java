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
package com.mjtrac.ballot.service;

import com.mjtrac.ballot.model.Contest;
import com.mjtrac.ballot.model.Region;
import com.mjtrac.ballot.repository.ContestRepository;
import com.mjtrac.ballot.repository.RegionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves the ordered list of contests for a SinglePrecinct in a given election.
 *
 * Algorithm:
 *   Given SinglePrecinct P in election E:
 *   1. Contests directly assigned to P
 *   2. Find all PrecinctGroups that have P as a member
 *   3. Contests assigned to each of those PrecinctGroups
 *   4. Deduplicate by contest ID, sort by displayOrder
 *
 * This means a Water District contest only needs to be entered once and
 * automatically appears on ballots for every member SinglePrecinct.
 */
@Service
public class ContestAssignmentService {

    private final ContestRepository  contestRepo;
    private final RegionRepository   regionRepo;

    public ContestAssignmentService(ContestRepository contestRepo,
                                    RegionRepository regionRepo) {
        this.contestRepo = contestRepo;
        this.regionRepo  = regionRepo;
    }

    /**
     * Resolve the full ordered contest list for a SinglePrecinct in an election.
     *
     * @param singlePrecinctId  ID of a SINGLE_PRECINCT region
     * @param electionId        ID of the election
     * @return                  deduplicated, displayOrder-sorted contest list
     */
    @Transactional(readOnly = true)
    public List<Contest> resolveContestsForPrecinct(Long singlePrecinctId, Long electionId) {
        Region precinct = regionRepo.findById(singlePrecinctId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Region not found: " + singlePrecinctId));

        if (!precinct.isSinglePrecinct()) {
            throw new IllegalArgumentException(
                "Region \"" + precinct.getName() + "\" is a PrecinctGroup, not a " +
                "SinglePrecinct. Ballot generation requires a SinglePrecinct.");
        }

        Map<Long, Contest> collected = new LinkedHashMap<>();

        // Step 1: contests directly assigned to this SinglePrecinct
        for (Contest c : contestRepo.findByElectionIdAndAssignedRegion(electionId, singlePrecinctId)) {
            collected.put(c.getId(), c);
        }

        // Steps 2 & 3: find parent PrecinctGroups, collect their contests
        for (Region group : regionRepo.findGroupsContainingMember(singlePrecinctId)) {
            for (Contest c : contestRepo.findByElectionIdAndAssignedRegion(electionId, group.getId())) {
                collected.put(c.getId(), c);
            }
        }

        return collected.values().stream()
            .sorted(Comparator.comparingInt(Contest::getDisplayOrder))
            .collect(Collectors.toList());
    }

    /**
     * Explain which regions contribute contests to a SinglePrecinct.
     * Used for the admin audit view (who contributes what to this ballot).
     */
    @Transactional(readOnly = true)
    public Map<Region, List<Contest>> explainContestsForPrecinct(
            Long singlePrecinctId, Long electionId) {

        Map<Region, List<Contest>> explanation = new LinkedHashMap<>();
        Region precinct = regionRepo.findById(singlePrecinctId)
            .orElseThrow(() -> new IllegalArgumentException("Region not found: " + singlePrecinctId));

        List<Contest> direct = contestRepo.findByElectionIdAndAssignedRegion(electionId, singlePrecinctId);
        if (!direct.isEmpty()) explanation.put(precinct, direct);

        for (Region group : regionRepo.findGroupsContainingMember(singlePrecinctId)) {
            List<Contest> fromGroup = contestRepo.findByElectionIdAndAssignedRegion(electionId, group.getId());
            if (!fromGroup.isEmpty()) explanation.put(group, fromGroup);
        }
        return explanation;
    }
}
