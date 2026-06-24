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
package gov.election.ballot.model;

import jakarta.persistence.*;
import java.time.LocalDate;

/** A specific election event (e.g., "June 2026 Primary"). */
@Entity
@Table(name = "elections")
public class Election {

    public enum ElectionType { PRIMARY, GENERAL, SPECIAL, RUNOFF }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Jurisdiction jurisdiction;

    @Column(nullable = false)
    private String name;

    private LocalDate electionDate;

    @Enumerated(EnumType.STRING)
    private ElectionType electionType;

    /**
     * When true, every voter gets the same ballot regardless of region
     * or party — the "simple uniform ballot" case.
     */
    private boolean uniformBallot = false;

    // ── Getters & Setters ─────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Jurisdiction getJurisdiction() { return jurisdiction; }
    public void setJurisdiction(Jurisdiction j) { this.jurisdiction = j; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDate getElectionDate() { return electionDate; }
    public void setElectionDate(LocalDate electionDate) { this.electionDate = electionDate; }

    public ElectionType getElectionType() { return electionType; }
    public void setElectionType(ElectionType electionType) { this.electionType = electionType; }

    public boolean isUniformBallot() { return uniformBallot; }
    public void setUniformBallot(boolean uniformBallot) { this.uniformBallot = uniformBallot; }
}
