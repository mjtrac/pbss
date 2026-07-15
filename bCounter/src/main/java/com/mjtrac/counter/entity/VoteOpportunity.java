/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.entity;

import jakarta.persistence.*;
import com.mjtrac.counter.entity.CandidateRecord;

/**
 * One row per indicator box analysed in a ballot image.
 *
 * This is the core fact table for the vote database.  It can be queried
 * to sum votes by precinct (via barcode.regionId), party (barcode.partyId),
 * contest, candidate, or any combination.
 *
 * Example query — votes for Alice by precinct:
 *   SELECT b.region_id, COUNT(*)
 *   FROM vote_opportunity v
 *   JOIN ballot_image i ON v.ballot_image_id = i.id
 *   JOIN barcode b      ON i.barcode_id = b.id
 *   WHERE v.candidate_name = 'Alice'
 *     AND v.vote_status = 'VOTED'
 *   GROUP BY b.region_id;
 */
@Entity
@Table(name = "vote_opportunity",
       indexes = {
           @Index(name = "idx_vo_image",     columnList = "ballot_image_id"),
           @Index(name = "idx_vo_contest",   columnList = "contest_id"),
           @Index(name = "idx_vo_candidate", columnList = "candidate_id_fk"),
           @Index(name = "idx_vo_status",    columnList = "vote_status"),
       })
public class VoteOpportunity {

    /** Vote status values. */
    public enum VoteStatus { UNMARKED, VOTED, OVERVOTED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ballot_image_id", nullable = false)
    private BallotImage ballotImage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contest_id", nullable = false)
    private ContestRecord contest;

    /** Normalised candidate record (join for human-readable name). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id_fk", nullable = false)
    private CandidateRecord candidate;

    // ── Indicator bounding box ───────────────────────────────────────────────

    /** Left edge in the CANONICAL (perspective-corrected, upright) image, pixels. */
    @Column(name = "abs_left")
    private int absLeft;

    /** Top edge in the CANONICAL image, pixels. */
    @Column(name = "abs_top")
    private int absTop;

    /** Width of indicator in canonical pixels. */
    @Column(name = "indicator_width")
    private int indicatorWidth;

    /** Height of indicator in canonical pixels. */
    @Column(name = "indicator_height")
    private int indicatorHeight;

    /** DPI of the canonical warped image used for absLeft/absTop. */
    @Column(name = "warp_dpi")
    private int warpDpi;

    /** Left edge in the ORIGINAL (possibly rotated/distorted) image, pixels.
     *  Kept for reference; use absLeft/absTop for display. */
    @Column(name = "image_x")
    private int imageX;

    /** Top edge in the original image, pixels. */
    @Column(name = "image_y")
    private int imageY;

    // ── Sampling results ──────────────────────────────────────────────────────

    /** Darkness threshold used (pixels below this are "dark"). */
    @Column
    private int threshold;

    /** Percentage of pixels in the indicator box that are dark. */
    @Column(name = "dark_pct")
    private double darkPct;

    /** Number of dark pixels in the indicator box. */
    @Column(name = "dark_pixels")
    private int darkPixels;

    /** Total pixels sampled in the indicator box. */
    @Column(name = "total_pixels")
    private int totalPixels;

    /** Mean pixel intensity (0=black, 255=white). */
    @Column(name = "mean_intensity")
    private double meanIntensity;

    /** UNMARKED, VOTED, or OVERVOTED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "vote_status", nullable = false, length = 20)
    private VoteStatus voteStatus = VoteStatus.UNMARKED;

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public Long         getId()             { return id; }
    public BallotImage  getBallotImage()    { return ballotImage; }
    public void         setBallotImage(BallotImage v)  { this.ballotImage = v; }
    public ContestRecord getContest()       { return contest; }
    public void         setContest(ContestRecord v)    { this.contest = v; }
    public CandidateRecord getCandidate()    { return candidate; }
    public void         setCandidate(CandidateRecord v){ this.candidate = v; }
    public int          getAbsLeft()        { return absLeft; }
    public void         setAbsLeft(int v)   { this.absLeft = v; }
    public int          getAbsTop()         { return absTop; }
    public void         setAbsTop(int v)    { this.absTop = v; }
    public int          getIndicatorWidth() { return indicatorWidth; }
    public void         setIndicatorWidth(int v)  { this.indicatorWidth = v; }
    public int          getIndicatorHeight(){ return indicatorHeight; }
    public void         setIndicatorHeight(int v) { this.indicatorHeight = v; }
    public int          getWarpDpi()        { return warpDpi; }
    public void         setWarpDpi(int v)   { this.warpDpi = v; }
    public int          getImageX()         { return imageX; }
    public void         setImageX(int v)    { this.imageX = v; }
    public int          getImageY()         { return imageY; }
    public void         setImageY(int v)    { this.imageY = v; }
    public int          getThreshold()      { return threshold; }
    public void         setThreshold(int v) { this.threshold = v; }
    public double       getDarkPct()        { return darkPct; }
    public void         setDarkPct(double v){ this.darkPct = v; }
    public int          getDarkPixels()     { return darkPixels; }
    public void         setDarkPixels(int v){ this.darkPixels = v; }
    public int          getTotalPixels()    { return totalPixels; }
    public void         setTotalPixels(int v){ this.totalPixels = v; }
    public double       getMeanIntensity()  { return meanIntensity; }
    public void         setMeanIntensity(double v) { this.meanIntensity = v; }
    public VoteStatus   getVoteStatus()     { return voteStatus; }
    public void         setVoteStatus(VoteStatus v) { this.voteStatus = v; }
}
