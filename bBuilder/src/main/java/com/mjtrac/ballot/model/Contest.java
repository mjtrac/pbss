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
package com.mjtrac.ballot.model;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A race or measure on the ballot, scoped to one or more regions.
 *
 * TEXTUAL ELEMENTS printed on the ballot (in order):
 *
 *   [groupingLabel]    — optional section header ABOVE the contest box,
 *                        e.g. "FEDERAL OFFICES", "STATE OFFICES".
 *                        Not inside the contest box; it precedes the box.
 *
 *   [contestTitle]     — the contest name, always printed.
 *
 *   [instructions]     — per-contest voting instruction, e.g. "Vote for one".
 *
 *   [preamble]         — optional block of text between the instruction and
 *                        the first candidate; e.g. a statutory description.
 *
 *   [candidates]       — each candidate, with optional prefix/suffix on the
 *                        same line, and optional note below the name.
 *
 *   [postamble]        — optional block of text after the last candidate;
 *                        e.g. a footnote, write-in reminder, disclaimer.
 *
 * ASSIGNMENT MODEL:
 *   A contest is assigned to regions via assignedRegions.
 *   See ContestAssignmentService for fan-out logic.
 */
@Entity
@Table(name = "contests")
public class Contest {

    public enum VotingMethod {
        PLURALITY, RANKED_CHOICE, APPROVAL, MEASURE
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Election election;

    /** Title as printed on the ballot. */
    @Column(nullable = false)
    private String title;

    /** Title used in database records and reports. Defaults to title if blank. */
    @Column
    private String recordTitle;

    /** Alias for title — used on ballot printout. */
    @Column
    private String printableTitle;

    private int maxChoices    = 1;
    private int maxRankChoices = 0;
    private int displayOrder  = 0;

    @Enumerated(EnumType.STRING)
    private VotingMethod votingMethod = VotingMethod.PLURALITY;

    // ── Grouping label ─────────────────────────────────────────────────────
    /**
     * Optional section header printed ABOVE the contest box on the ballot,
     * e.g. "FEDERAL OFFICES", "STATE OFFICES", "MEASURES".
     * When non-blank, this text appears above every contest that shares the
     * same label value and is adjacent on the ballot.  It is not deduplicated
     * automatically — enter it only on the first contest in a section, or on
     * every contest if you want it repeated.
     */
    @Column(columnDefinition = "TEXT")
    private String groupingLabel;

    private boolean printGroupingLabel = false;

    // ── Per-contest instruction ────────────────────────────────────────────
    @Column(columnDefinition = "TEXT")
    private String instructions;

    // ── Preamble (before candidates) ──────────────────────────────────────
    /**
     * Optional block of text printed between the contest instruction and the
     * first candidate/option.  Use for statutory text, bond summaries, etc.
     */
    @Column(columnDefinition = "TEXT")
    private String preamble;

    private boolean printPreamble = false;

    // ── Postamble (after candidates) ──────────────────────────────────────
    /**
     * Optional block of text printed after the last candidate/option.
     * Use for write-in reminders, disclaimers, or continuation notes.
     */
    @Column(columnDefinition = "TEXT")
    private String postamble;

    private boolean printPostamble = false;

    // ── Legacy explanatory text (kept for compatibility) ──────────────────
    @Column(columnDefinition = "TEXT")
    private String explanatoryText;

    private boolean printExplanatoryText = false;
    private String  explanatoryTextLocation;

    // ── Region assignment ──────────────────────────────────────────────────
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "contest_regions",
        joinColumns        = @JoinColumn(name = "contest_id"),
        inverseJoinColumns = @JoinColumn(name = "region_id"))
    private List<Region> assignedRegions = new ArrayList<>();

    @OneToMany(mappedBy = "contest", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("displayOrder ASC")
    private List<Candidate> candidates = new ArrayList<>();

    // ── Getters & Setters ─────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Election getElection() { return election; }
    public void setElection(Election e) { this.election = e; }

    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; this.printableTitle = t; }

    public String getPrintableTitle() {
        return (printableTitle != null && !printableTitle.isBlank()) ? printableTitle : title;
    }
    public void setPrintableTitle(String v) {
        this.printableTitle = v;
        if (this.title == null || this.title.isBlank()) this.title = v;
    }

    public String getRecordTitle() {
        return (recordTitle != null && !recordTitle.isBlank()) ? recordTitle : getTitle();
    }
    public void setRecordTitle(String v) { this.recordTitle = v; }

    public int getMaxChoices() { return maxChoices; }
    public void setMaxChoices(int v) { this.maxChoices = v; }

    public int getMaxRankChoices() { return maxRankChoices; }
    public void setMaxRankChoices(int v) { this.maxRankChoices = v; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int v) { this.displayOrder = v; }

    public VotingMethod getVotingMethod() { return votingMethod; }
    public void setVotingMethod(VotingMethod v) { this.votingMethod = v; }

    public String  getGroupingLabel()       { return groupingLabel; }
    public void    setGroupingLabel(String v)      { this.groupingLabel = v; }
    public boolean isPrintGroupingLabel()   { return printGroupingLabel; }
    public void    setPrintGroupingLabel(boolean v){ this.printGroupingLabel = v; }

    public String  getInstructions()        { return instructions; }
    public void    setInstructions(String v)       { this.instructions = v; }

    public String  getPreamble()            { return preamble; }
    public void    setPreamble(String v)           { this.preamble = v; }
    public boolean isPrintPreamble()        { return printPreamble; }
    public void    setPrintPreamble(boolean v)     { this.printPreamble = v; }

    public String  getPostamble()           { return postamble; }
    public void    setPostamble(String v)          { this.postamble = v; }
    public boolean isPrintPostamble()       { return printPostamble; }
    public void    setPrintPostamble(boolean v)    { this.printPostamble = v; }

    public String  getExplanatoryText()          { return explanatoryText; }
    public void    setExplanatoryText(String v)       { this.explanatoryText = v; }
    public boolean isPrintExplanatoryText()      { return printExplanatoryText; }
    public void    setPrintExplanatoryText(boolean v) { this.printExplanatoryText = v; }
    public String  getExplanatoryTextLocation()  { return explanatoryTextLocation; }
    public void    setExplanatoryTextLocation(String v){ this.explanatoryTextLocation = v; }

    public List<Region> getAssignedRegions() { return assignedRegions; }
    public void setAssignedRegions(List<Region> v) { this.assignedRegions = v; }

    public List<Candidate> getCandidates() { return candidates; }
    public void setCandidates(List<Candidate> v) { this.candidates = v; }
}
