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
import java.util.ArrayList;
import java.util.List;

/**
 * A geographic or administrative unit used for ballot assignment.
 *
 * Two kinds, distinguished by regionType:
 *
 *  SINGLE_PRECINCT — an individual voting precinct.  Voters are registered
 *    to exactly one SinglePrecinct.  Ballot combinations are keyed to these.
 *    A SinglePrecinct has no member list of its own.
 *    Example: "Precinct 4", "Polling Place 12"
 *
 *  PRECINCT_GROUP — a named grouping that spans multiple SinglePrecincts
 *    (a Water District, City, School District, Township, etc.).
 *    Members are listed in the `members` collection.
 *    Assigning a contest to a PrecinctGroup automatically places it on the
 *    ballot for every member SinglePrecinct — no per-precinct entry needed.
 *    Example: "Sample Water District", "City of Sampleville"
 *
 * Contest resolution for a SinglePrecinct P in election E:
 *   1. Contests directly assigned to P
 *   2. PrecinctGroups that list P as a member
 *   3. Contests assigned to each of those PrecinctGroups
 *   4. Deduplicated, sorted by displayOrder
 */
@Entity
@Table(name = "regions")
public class Region {

    /** Distinguishes individual precincts from multi-precinct groupings. */
    public enum RegionType {
        SINGLE_PRECINCT,  // an individual voting precinct
        PRECINCT_GROUP    // a named grouping of multiple precincts
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Jurisdiction jurisdiction;

    @Column(nullable = false)
    private String name;

    /**
     * The kind of region: SINGLE_PRECINCT or PRECINCT_GROUP.
     * Replaces the former boolean `precinct` flag.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RegionType regionType = RegionType.SINGLE_PRECINCT;

    /**
     * An optional sub-type label for PrecinctGroups.
     * Examples: CITY, TOWN, WATER_DISTRICT, SCHOOL_DISTRICT, FIRE_DISTRICT, WARD, OTHER
     * Not applicable to SinglePrecincts.
     */
    private String groupType;

    private String description;

    /**
     * For PRECINCT_GROUP regions: the SinglePrecinct members of this group.
     * Empty for SINGLE_PRECINCT regions.
     *
     * JOIN TABLE: region_members
     *   group_id  -> the PrecinctGroup region
     *   member_id -> a SinglePrecinct member
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "region_members",
        joinColumns        = @JoinColumn(name = "group_id"),
        inverseJoinColumns = @JoinColumn(name = "member_id")
    )
    private List<Region> members = new ArrayList<>();

    // ── Convenience helpers ───────────────────────────────────

    public boolean isSinglePrecinct() {
        return regionType == RegionType.SINGLE_PRECINCT;
    }

    public boolean isPrecinctGroup() {
        return regionType == RegionType.PRECINCT_GROUP;
    }

    /** Display name with type for dropdowns. */
    public String getDisplayName() {
        if (regionType == RegionType.PRECINCT_GROUP && groupType != null && !groupType.isBlank()) {
            return name + " [" + groupType + "]";
        }
        return name;
    }

    // ── Getters & Setters ─────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Jurisdiction getJurisdiction() { return jurisdiction; }
    public void setJurisdiction(Jurisdiction jurisdiction) { this.jurisdiction = jurisdiction; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public RegionType getRegionType() { return regionType; }
    public void setRegionType(RegionType regionType) { this.regionType = regionType; }

    public String getGroupType() { return groupType; }
    public void setGroupType(String groupType) { this.groupType = groupType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<Region> getMembers() { return members; }
    public void setMembers(List<Region> members) { this.members = members; }
    // equals and hashCode based on id so that contains() works correctly
    // in Thymeleaf th:checked expressions and Java collections.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Region)) return false;
        Region other = (Region) o;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }

}
