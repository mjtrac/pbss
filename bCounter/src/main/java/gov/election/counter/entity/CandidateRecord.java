/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.counter.entity;

import jakarta.persistence.*;

/**
 * One row per unique candidate/option name within a contest.
 * Normalises candidate names out of vote_opportunity so queries
 * can join on integer keys rather than string matching.
 *
 * Example query — total votes per candidate in a contest:
 *   SELECT c.candidate_name, COUNT(*)
 *   FROM vote_opportunity v
 *   JOIN candidate c ON v.candidate_id_fk = c.id
 *   JOIN contest   k ON v.contest_id = k.id
 *   WHERE k.contest_title = 'Mayor'
 *     AND v.vote_status = 'VOTED'
 *   GROUP BY c.candidate_name
 *   ORDER BY COUNT(*) DESC;
 */
@Entity
@Table(name = "candidate",
       uniqueConstraints = @UniqueConstraint(
           columnNames = {"contest_id", "candidate_name"}))
public class CandidateRecord {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The contest this candidate belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contest_id", nullable = false)
    private ContestRecord contest;

    /** Name as it appears in the ballot YAML. */
    @Column(name = "candidate_name", nullable = false, length = 500)
    private String candidateName;

    /** Candidate ID from the ballot system (may be null). */
    @Column(name = "ballot_candidate_id")
    private Long ballotCandidateId;

    /** True if this is a write-in slot. */
    @Column(name = "write_in")
    private boolean writeIn;

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public Long          getId()               { return id; }
    public ContestRecord getContest()          { return contest; }
    public void          setContest(ContestRecord v)       { this.contest = v; }
    public String        getCandidateName()    { return candidateName; }
    public void          setCandidateName(String v)        { this.candidateName = v; }
    public Long          getBallotCandidateId(){ return ballotCandidateId; }
    public void          setBallotCandidateId(Long v)      { this.ballotCandidateId = v; }
    public boolean       isWriteIn()           { return writeIn; }
    public void          setWriteIn(boolean v) { this.writeIn = v; }
}
