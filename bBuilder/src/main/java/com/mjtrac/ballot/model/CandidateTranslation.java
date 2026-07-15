/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.ballot.model;

import jakarta.persistence.*;

/**
 * Translation of a Candidate's text fields into one language.
 * Null fields fall back to the base Candidate value.
 */
@Entity
@Table(name = "candidate_translations",
       uniqueConstraints = @UniqueConstraint(columnNames = {"candidate_id","language_code"}))
public class CandidateTranslation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "candidate_id")
    private Candidate candidate;

    @Column(name = "language_code", nullable = false, length = 16)
    private String languageCode;

    /** Translated candidate name. Null = use Candidate.name. */
    @Column(columnDefinition = "TEXT")
    private String name;

    /** Translated explanatory text. Null = use Candidate.explanatoryText. */
    @Column(columnDefinition = "TEXT")
    private String explanatoryText;

    public Long   getId()            { return id; }
    public Candidate getCandidate()  { return candidate; }
    public void   setCandidate(Candidate c) { this.candidate = c; }
    public String getLanguageCode()  { return languageCode; }
    public void   setLanguageCode(String c) { this.languageCode = c; }
    public String getName()          { return name; }
    public void   setName(String n)  { this.name = n; }
    public String getExplanatoryText() { return explanatoryText; }
    public void   setExplanatoryText(String s) { this.explanatoryText = s; }
}
