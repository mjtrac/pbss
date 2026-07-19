/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.ballot.model;

import jakarta.persistence.*;

/**
 * Translation of a Contest's text fields into one language.
 * If a translation exists for the requested language, it overrides
 * the base Contest fields when generating a ballot in that language.
 * Any field left null falls back to the base Contest value.
 */
@Entity
@Table(name = "contest_translations",
       uniqueConstraints = @UniqueConstraint(columnNames = {"contest_id","language_code"}))
public class ContestTranslation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "contest_id")
    private Contest contest;

    @Column(name = "language_code", nullable = false, length = 16)
    private String languageCode;

    /** Translated contest title. Null = use Contest.title. */
    @Column(columnDefinition = "TEXT")
    private String title;

    /** Translated custom instruction. Null = use Contest.instructions (or auto-generated). */
    @Column(columnDefinition = "TEXT")
    private String instructions;

    /** Translated preamble. Null = use Contest.preamble. */
    @Column(columnDefinition = "TEXT")
    private String preamble;

    /** Translated postamble. Null = use Contest.postamble. */
    @Column(columnDefinition = "TEXT")
    private String postamble;

    /** Translated grouping label. Null = use Contest.groupingLabel. */
    @Column(columnDefinition = "TEXT")
    private String groupingLabel;

    public Long   getId()            { return id; }
    public Contest getContest()      { return contest; }
    public void   setContest(Contest c) { this.contest = c; }
    public String getLanguageCode()  { return languageCode; }
    public void   setLanguageCode(String c) { this.languageCode = c; }
    public String getTitle()         { return title; }
    public void   setTitle(String t) { this.title = t; }
    public String getInstructions()  { return instructions; }
    public void   setInstructions(String s) { this.instructions = s; }
    public String getPreamble()      { return preamble; }
    public void   setPreamble(String s) { this.preamble = s; }
    public String getPostamble()     { return postamble; }
    public void   setPostamble(String s) { this.postamble = s; }
    public String getGroupingLabel() { return groupingLabel; }
    public void   setGroupingLabel(String s) { this.groupingLabel = s; }
}
