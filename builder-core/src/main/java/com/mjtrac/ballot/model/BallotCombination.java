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
package com.mjtrac.ballot.model;

import jakarta.persistence.*;

/**
 * Represents a specific ballot variant: the combination of an atomic precinct,
 * an optional party (for primaries), a ballot type, and an election.
 *
 * KEY DESIGN POINT:
 *   region here MUST always be an atomic precinct (isPrecinct == true).
 *   Composite regions (water districts, cities, etc.) are used only
 *   for contest assignment, not for ballot identification.
 *
 * CONTEST RESOLUTION:
 *   The list of contests on this ballot is NOT stored here. It is computed
 *   at ballot generation time by ContestAssignmentService, which:
 *     1. Finds all contests directly assigned to this precinct
 *     2. Finds all composite regions that contain this precinct
 *     3. Adds contests assigned to any of those composites
 *     4. Deduplicates and sorts by displayOrder
 *
 *   This means contests automatically propagate from composites to member
 *   precincts without any per-precinct data entry.
 */
@Entity
@Table(name = "ballot_combinations",
       uniqueConstraints = @UniqueConstraint(
           columnNames = {"region_id", "party_id", "ballot_type_id", "election_id"}))
public class BallotCombination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Must be an atomic precinct (Region.isPrecinct == true).
     * This is the unit that identifies which ballot a voter receives.
     */
    @ManyToOne(optional = false)
    private Region region;

    /** Null for nonpartisan or general elections. */
    @ManyToOne
    private Party party;

    @ManyToOne(optional = false)
    private BallotType ballotType;

    @ManyToOne(optional = false)
    private Election election;

    // ── Getters & Setters ─────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Region getRegion() { return region; }
    public void setRegion(Region s) { this.region = s; }

    public Party getParty() { return party; }
    public void setParty(Party party) { this.party = party; }

    public BallotType getBallotType() { return ballotType; }
    public void setBallotType(BallotType ballotType) { this.ballotType = ballotType; }

    public Election getElection() { return election; }
    public void setElection(Election election) { this.election = election; }
}
