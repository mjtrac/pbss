/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.service;

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

    // ── Scribble detection summary ────────────────────────────────────────────

    /** DTO for a single scribble-flagged ballot. */
    public static class ScribbleRow {
        public final long   imageId;
        public final String imageName;
        public final String imagePath;
        public final int    scribblePixels;
        public final String barcodeData;
        public final String outlineImagePath;   // null if not generated

        public ScribbleRow(long imageId, String imageName, String imagePath,
                           int scribblePixels, String barcodeData,
                           String outlineImagePath) {
            this.imageId          = imageId;
            this.imageName        = imageName;
            this.imagePath        = imagePath;
            this.scribblePixels   = scribblePixels;
            this.barcodeData      = barcodeData;
            this.outlineImagePath = outlineImagePath;
        }
        public long   getImageId()           { return imageId; }
        public String getImageName()         { return imageName; }
        public String getImagePath()         { return imagePath; }
        public int    getScribblePixels()    { return scribblePixels; }
        public String getBarcodeData()       { return barcodeData; }
        public String getOutlineImagePath()  { return outlineImagePath; }
        public boolean isHasOutline()        { return outlineImagePath != null
                                                    && !outlineImagePath.isBlank(); }
    }

    /**
     * Returns all ballot images flagged by scribble detection,
     * ordered by suspicious pixel count descending (worst first).
     * Requires scribbleFlagged/scribblePixels/scribbleOutlinePath fields
     * on BallotImage.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<ScribbleRow> scribbledBallots() {
        String jpql = """
            SELECT i.id, i.imageName, i.imagePath, i.scribblePixels,
                   COALESCE(b.rawData, ''), i.scribbleOutlinePath
            FROM BallotImage i
            LEFT JOIN i.barcode b
            WHERE i.scribbleFlagged = true
            ORDER BY i.scribblePixels DESC
            """;
        List<Object[]> raw = em.createQuery(jpql).getResultList();
        List<ScribbleRow> rows = new ArrayList<>();
        for (Object[] r : raw)
            rows.add(new ScribbleRow(
                ((Number) r[0]).longValue(),
                str(r[1]), str(r[2]),
                r[3] != null ? ((Number) r[3]).intValue() : 0,
                str(r[4]),
                r[5] != null ? r[5].toString() : null));
        return rows;
    }

    @Transactional(readOnly = true)
    public long totalScribbled() {
        try {
            return (long) em.createQuery(
                "SELECT COUNT(i) FROM BallotImage i WHERE i.scribbleFlagged = true")
                .getSingleResult();
        } catch (Exception e) {
            return 0L;   // field not yet in schema — degrade gracefully
        }
    }

    // ── Write-in summary ──────────────────────────────────────────────────────

    /** DTO for a single marked write-in vote. */
    public static class WriteInRow {
        public final long   imageId;
        public final String imageName;
        public final String contest;
        public final String candidate;
        public final String barcodeData;

        public WriteInRow(long imageId, String imageName, String contest,
                          String candidate, String barcodeData) {
            this.imageId     = imageId;
            this.imageName   = imageName;
            this.contest     = contest;
            this.candidate   = candidate;
            this.barcodeData = barcodeData;
        }
        public long   getImageId()     { return imageId; }
        public String getImageName()   { return imageName; }
        public String getContest()     { return contest; }
        public String getCandidate()   { return candidate; }
        public String getBarcodeData() { return barcodeData; }
    }

    /**
     * Returns every write-in slot whose indicator was actually marked
     * (VOTED or OVERVOTED) — write-in text with an unmarked indicator is
     * excluded, matching how an unmarked write-in isn't a counted vote.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<WriteInRow> markedWriteIns() {
        String jpql = """
            SELECT bi.id, bi.imageName, c.contestTitle, cd.candidateName, COALESCE(b.rawData, '')
            FROM VoteOpportunity vo
            JOIN vo.ballotImage bi
            JOIN vo.contest c
            JOIN vo.candidate cd
            LEFT JOIN bi.barcode b
            WHERE cd.writeIn = true
              AND vo.voteStatus != com.mjtrac.counter.entity.VoteOpportunity$VoteStatus.UNMARKED
            ORDER BY bi.imageName, c.contestTitle
            """;
        List<Object[]> raw = em.createQuery(jpql).getResultList();
        List<WriteInRow> rows = new ArrayList<>();
        for (Object[] r : raw)
            rows.add(new WriteInRow(
                ((Number) r[0]).longValue(), str(r[1]), str(r[2]), str(r[3]), str(r[4])));
        return rows;
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
            "SELECT DISTINCT bi.imageName, cr.contestTitle, cr.id, cr.contestType, cr.maxVotes " +
            "FROM VoteOpportunity vo " +
            "JOIN vo.ballotImage bi " +
            "JOIN vo.contest cr " +
            "WHERE vo.voteStatus = com.mjtrac.counter.entity.VoteOpportunity$VoteStatus.OVERVOTED " +
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
