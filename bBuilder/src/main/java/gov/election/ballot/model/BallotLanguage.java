/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.ballot.model;

import jakarta.persistence.*;

/**
 * A language supported for ballot printing within a jurisdiction.
 * Ballots can be printed in any registered language; the indicator
 * positions and barcodes are identical across languages.
 */
@Entity
@Table(name = "ballot_languages",
       uniqueConstraints = @UniqueConstraint(columnNames = {"jurisdiction_id","language_code"}))
public class BallotLanguage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "jurisdiction_id")
    private Jurisdiction jurisdiction;

    /** IETF language tag, e.g. "en", "es", "zh-Hant", "tl". */
    @Column(name = "language_code", nullable = false, length = 16)
    private String languageCode;

    /** Human-readable name shown in the UI, e.g. "Spanish", "中文 (繁體)". */
    @Column(name = "language_name", nullable = false)
    private String languageName;

    /** Display order on the language selector. */
    private int displayOrder = 0;

    public Long   getId()           { return id; }
    public Jurisdiction getJurisdiction() { return jurisdiction; }
    public void   setJurisdiction(Jurisdiction j) { this.jurisdiction = j; }
    public String getLanguageCode() { return languageCode; }
    public void   setLanguageCode(String c) { this.languageCode = c; }
    public String getLanguageName() { return languageName; }
    public void   setLanguageName(String n) { this.languageName = n; }
    public int    getDisplayOrder() { return displayOrder; }
    public void   setDisplayOrder(int o) { this.displayOrder = o; }
}
