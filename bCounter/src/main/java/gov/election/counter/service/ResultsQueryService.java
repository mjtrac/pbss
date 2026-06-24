/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.counter.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;

/**
 * Runs aggregation queries against the vote database and returns
 * structured result sets for display on the /results page.
 *
 * All queries join through:
 *   vote_opportunity → candidate → contest
 *   vote_opportunity → ballot_image → barcode  (for region/party)
 */
@Service
public class ResultsQueryService {

    @PersistenceContext
    private EntityManager em;

    // ── DTO returned to the template ──────────────────────────────────────────

    public static class VoteRow {
        public final String  contest;
        public final String  candidate;
        public final String  dimension;      // precinct/party/ALL
        public final long    voted;
        public final long    overvoted;
        public final long    unmarked;
        /** True when this row represents a ranked-choice rank box (e.g. "Name (Rank 1)"). */
        public final boolean rankedChoice;
        public final String  rankLabel;   // "Rank 1" etc, or null
        public final String  baseName;    // candidate name without rank suffix

        public String  getRankLabel()    { return rankLabel; }
        public String  getBaseName()     { return baseName; }
        public boolean isRankedChoice()  { return rankedChoice; }
        public String  getContest()      { return contest; }
        public String  getCandidate()    { return candidate; }
        public String  getDimension()    { return dimension; }
        public long    getVoted()        { return voted; }
        public long    getOvervoted()    { return overvoted; }
        public long    getUnmarked()     { return unmarked; }

        public VoteRow(String contest, String candidate,
                       String dimension, long voted, long overvoted, long unmarked) {
            this.contest      = contest;
            this.candidate    = candidate;
            this.dimension    = dimension;
            this.voted        = voted;
            this.overvoted    = overvoted;
            this.unmarked     = unmarked;
            this.rankedChoice = candidate != null && candidate.matches(".*\\(Rank \\d+\\)$");
            if (this.rankedChoice) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\(Rank (\\d+)\\)$").matcher(candidate);
                this.rankLabel = m.find() ? "Rank " + m.group(1) : null;
                this.baseName  = candidate.replaceAll("\\s*\\(Rank \\d+\\)$", "");
            } else {
                this.rankLabel = null;
                this.baseName  = candidate;
            }
        }
    }

    // ── Total votes by contest + candidate (all precincts) ────────────────────

    @Transactional(readOnly = true)
    public List<VoteRow> votesByContest() {
        String jpql = """
            SELECT k.contestTitle, c.candidateName,
                   SUM(CASE WHEN v.voteStatus = 'VOTED'     THEN 1 ELSE 0 END),
                   SUM(CASE WHEN v.voteStatus = 'OVERVOTED' THEN 1 ELSE 0 END),
                   SUM(CASE WHEN v.voteStatus = 'UNMARKED'  THEN 1 ELSE 0 END)
            FROM VoteOpportunity v
            JOIN v.contest   k
            JOIN v.candidate c
            GROUP BY k.contestTitle, c.candidateName
            ORDER BY k.contestTitle, SUM(CASE WHEN v.voteStatus = 'VOTED' THEN 1 ELSE 0 END) DESC
            """;
        return toRows(jpql, "ALL");
    }

    // ── Votes by contest + candidate + precinct (regionId) ───────────────────

    @Transactional(readOnly = true)
    public List<VoteRow> votesByContestAndPrecinct() {
        String jpql = """
            SELECT k.contestTitle, c.candidateName,
                   COALESCE(b.regionId, '(unknown)'),
                   SUM(CASE WHEN v.voteStatus = 'VOTED'     THEN 1 ELSE 0 END),
                   SUM(CASE WHEN v.voteStatus = 'OVERVOTED' THEN 1 ELSE 0 END),
                   SUM(CASE WHEN v.voteStatus = 'UNMARKED'  THEN 1 ELSE 0 END)
            FROM VoteOpportunity v
            JOIN v.contest        k
            JOIN v.candidate      c
            JOIN v.ballotImage    i
            JOIN i.barcode        b
            GROUP BY k.contestTitle, c.candidateName, b.regionId
            ORDER BY k.contestTitle, b.regionId, c.candidateName
            """;
        return toRows4(jpql);
    }

    // ── Votes by contest + candidate + party ──────────────────────────────────

    @Transactional(readOnly = true)
    public List<VoteRow> votesByContestAndParty() {
        String jpql = """
            SELECT k.contestTitle, c.candidateName,
                   COALESCE(b.partyId, '0'),
                   SUM(CASE WHEN v.voteStatus = 'VOTED'     THEN 1 ELSE 0 END),
                   SUM(CASE WHEN v.voteStatus = 'OVERVOTED' THEN 1 ELSE 0 END),
                   SUM(CASE WHEN v.voteStatus = 'UNMARKED'  THEN 1 ELSE 0 END)
            FROM VoteOpportunity v
            JOIN v.contest        k
            JOIN v.candidate      c
            JOIN v.ballotImage    i
            JOIN i.barcode        b
            GROUP BY k.contestTitle, c.candidateName, b.partyId
            ORDER BY k.contestTitle, b.partyId, c.candidateName
            """;
        return toRows4(jpql);
    }

    // ── Summary counts ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public long totalVotesCast() {
        return (long) em.createQuery(
            "SELECT COUNT(v) FROM VoteOpportunity v WHERE v.voteStatus = 'VOTED'")
            .getSingleResult();
    }

    @Transactional(readOnly = true)
    public long totalBallotImages() {
        return (long) em.createQuery("SELECT COUNT(i) FROM BallotImage i")
            .getSingleResult();
    }

    @Transactional(readOnly = true)
    public long totalOvervoted() {
        return (long) em.createQuery(
            "SELECT COUNT(DISTINCT v.ballotImage) FROM VoteOpportunity v WHERE v.voteStatus = 'OVERVOTED'")
            .getSingleResult();
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Object[]> overvotedPairs() {
        return em.createQuery(
            "SELECT DISTINCT bi.imageName, cr.contestTitle, cr.id, cr.contestType, cr.maxChoices " +
            "FROM VoteOpportunity vo " +
            "JOIN vo.ballotImage bi " +
            "JOIN vo.contest cr " +
            "WHERE vo.voteStatus = gov.election.counter.entity.VoteOpportunity$VoteStatus.OVERVOTED " +
            "ORDER BY bi.imageName, cr.contestTitle")
            .getResultList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<VoteRow> toRows(String jpql, String fixedDim) {
        List<Object[]> raw = em.createQuery(jpql).getResultList();
        List<VoteRow> rows = new ArrayList<>();
        for (Object[] r : raw)
            rows.add(new VoteRow(str(r[0]), str(r[1]), fixedDim,
                lng(r[2]), lng(r[3]), lng(r[4])));
        return rows;
    }

    @SuppressWarnings("unchecked")
    private List<VoteRow> toRows4(String jpql) {
        List<Object[]> raw = em.createQuery(jpql).getResultList();
        List<VoteRow> rows = new ArrayList<>();
        for (Object[] r : raw)
            rows.add(new VoteRow(str(r[0]), str(r[1]), str(r[2]),
                lng(r[3]), lng(r[4]), lng(r[5])));
        return rows;
    }

    private static String str(Object o) { return o != null ? o.toString() : ""; }
    private static long   lng(Object o) {
        if (o instanceof Number n) return n.longValue();
        return 0L;
    }
}
