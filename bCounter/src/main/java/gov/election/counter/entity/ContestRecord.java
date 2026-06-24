/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.counter.entity;

import jakarta.persistence.*;

/**
 * One row per unique contest title seen across all scanned images.
 * Contests are identified by title + contestType since the same contest
 * name could theoretically appear in different elections.
 */
@Entity
@Table(name = "contest",
       uniqueConstraints = @UniqueConstraint(columnNames = {"contest_title", "contest_type"}))
public class ContestRecord {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contest_title", nullable = false, length = 500)
    private String contestTitle;

    /** PLURALITY, RANKED_CHOICE, APPROVAL, MEASURE */
    @Column(name = "contest_type", nullable = false, length = 50)
    private String contestType;

    /** Maximum votes allowed in first-past-the-post contests. */
    @Column(name = "max_votes")
    private int maxVotes;

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public Long   getId()           { return id; }
    public String getContestTitle() { return contestTitle; }
    public void   setContestTitle(String v) { this.contestTitle = v; }
    public String getContestType()  { return contestType; }
    public void   setContestType(String v)  { this.contestType = v; }
    public int    getMaxVotes()     { return maxVotes; }
    public void   setMaxVotes(int v)        { this.maxVotes = v; }
}
