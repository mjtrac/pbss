/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.entity;

import jakarta.persistence.*;

/**
 * One row per unique barcode seen across all scanned images.
 *
 * Barcode format: jurisdictionId|regionId|partyId|ballotTypeId|electionId|page
 *
 * Shared by many BallotImage rows (one ballot may have multiple pages,
 * each with the same base barcode differing only in page number).
 */
@Entity
@Table(name = "barcode",
       uniqueConstraints = @UniqueConstraint(columnNames = "raw_data"))
public class BarcodeRecord {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Full raw barcode string as decoded from the image. */
    @Column(name = "raw_data", nullable = false, length = 500)
    private String rawData;

    /** Barcode field 0 — jurisdiction ID. */
    @Column(name = "jurisdiction_id")
    private String jurisdictionId;

    /** Barcode field 1 — region / precinct ID. */
    @Column(name = "region_id")
    private String regionId;

    /** Barcode field 2 — party ID (0 = nonpartisan). */
    @Column(name = "party_id")
    private String partyId;

    /** Barcode field 3 — ballot type ID. */
    @Column(name = "ballot_type_id")
    private String ballotTypeId;

    /** Barcode field 4 — election ID. */
    @Column(name = "election_id")
    private String electionId;

    /** Barcode field 5 — page number within the ballot. */
    @Column(name = "page_number")
    private String pageNumber;

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public Long   getId()             { return id; }
    public String getRawData()        { return rawData; }
    public void   setRawData(String v){ this.rawData = v; }
    public String getJurisdictionId() { return jurisdictionId; }
    public void   setJurisdictionId(String v) { this.jurisdictionId = v; }
    public String getRegionId()       { return regionId; }
    public void   setRegionId(String v)       { this.regionId = v; }
    public String getPartyId()        { return partyId; }
    public void   setPartyId(String v)        { this.partyId = v; }
    public String getBallotTypeId()   { return ballotTypeId; }
    public void   setBallotTypeId(String v)   { this.ballotTypeId = v; }
    public String getElectionId()     { return electionId; }
    public void   setElectionId(String v)     { this.electionId = v; }
    public String getPageNumber()     { return pageNumber; }
    public void   setPageNumber(String v)     { this.pageNumber = v; }

    /** Parse and populate fields from a raw barcode string. */
    public void parseFromRaw(String raw) {
        this.rawData = raw != null ? raw : "";
        String[] parts = this.rawData.split("\\|", -1);
        jurisdictionId = parts.length > 0 ? parts[0] : null;
        regionId       = parts.length > 1 ? parts[1] : null;
        partyId        = parts.length > 2 ? parts[2] : null;
        ballotTypeId   = parts.length > 3 ? parts[3] : null;
        electionId     = parts.length > 4 ? parts[4] : null;
        pageNumber     = parts.length > 5 ? parts[5] : null;
    }
}
