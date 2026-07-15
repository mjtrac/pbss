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

/**
 * A candidate or ballot option within a contest.
 *
 * TEXTUAL ELEMENTS printed for each candidate (in order on a single row):
 *
 *   [prefixText]   — optional short text BEFORE the candidate name on the same
 *                    line, e.g. "★", "Incumbent:", a term number.
 *                    Printed at prefixSuffix font size.
 *
 *   [name]         — the candidate name or option label (always printed).
 *
 *   [partyAffiliation] — party label printed after the name in parentheses,
 *                    e.g. "(Democratic)".  Ballot display only; not a FK.
 *
 *   [suffixText]   — optional short text AFTER the candidate name on the same
 *                    line, e.g. "Write-In", "Appointed", a year of office.
 *
 *   [explanatoryText] — optional note printed on a separate line below the name,
 *                    at candidateNote font size.
 */
@Entity
@Table(name = "candidates")
public class Candidate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "contest_id")
    private Contest contest;

    // ── Printable name (on ballot) and record name (in database/reports) ──
    /** The name as it appears printed on the ballot. */
    @Column(nullable = false)
    private String printableName;

    /** The name used in database records, reports, and YAML exports.
     *  Defaults to printableName if blank. */
    @Column
    private String recordName;

    /** Legacy 'name' column kept for DB compatibility; mapped to printableName. */
    @Column(nullable = false)
    private String name;

    @Column(name = "write_in")
    private boolean writeIn      = false;
    private String  partyAffiliation;
    private int     displayOrder = 0;

    // ── Prefix text (before name, same line) ──────────────────────────────
    /**
     * Short text printed immediately before the candidate name on the ballot,
     * e.g. "★", "Incumbent:", "Appointed -".
     * Leave blank for standard entries.
     */
    @Column(columnDefinition = "TEXT")
    private String prefixText;

    private boolean printPrefixText = false;

    // ── Suffix text (after name, same line) ───────────────────────────────
    /**
     * Short text printed immediately after the candidate name on the ballot,
     * e.g. "Write-In", "Endorsed by City Council".
     */
    @Column(columnDefinition = "TEXT")
    private String suffixText;

    private boolean printSuffixText = false;

    // ── Explanatory note (below name) ──────────────────────────────────────
    @Column(columnDefinition = "TEXT")
    private String explanatoryText;

    private boolean printExplanatoryText = false;

    /** Layout hint: "BELOW_NAME", "RIGHT_OF_INDICATOR" */
    private String explanatoryTextLocation;

    // ── Getters & Setters ─────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Contest getContest() { return contest; }
    public void setContest(Contest c) { this.contest = c; }

    public String getName() { return name; }
    public void setName(String n) { this.name = n; this.printableName = n; }

    public String getPrintableName() {
        return (printableName != null && !printableName.isBlank()) ? printableName : name;
    }
    public void setPrintableName(String v) {
        this.printableName = v;
        if (this.name == null || this.name.isBlank()) this.name = v;
    }

    public String getRecordName() {
        return (recordName != null && !recordName.isBlank()) ? recordName : getPrintableName();
    }
    public void setRecordName(String v) { this.recordName = v; }

    public boolean isWriteIn() { return writeIn; }
    public void setWriteIn(boolean v) { this.writeIn = v; }

    public String getPartyAffiliation() { return partyAffiliation; }
    public void setPartyAffiliation(String v) { this.partyAffiliation = v; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int v) { this.displayOrder = v; }

    public String  getPrefixText()       { return prefixText; }
    public void    setPrefixText(String v)      { this.prefixText = v; }
    public boolean isPrintPrefixText()   { return printPrefixText; }
    public void    setPrintPrefixText(boolean v){ this.printPrefixText = v; }

    public String  getSuffixText()       { return suffixText; }
    public void    setSuffixText(String v)      { this.suffixText = v; }
    public boolean isPrintSuffixText()   { return printSuffixText; }
    public void    setPrintSuffixText(boolean v){ this.printSuffixText = v; }

    public String  getExplanatoryText()         { return explanatoryText; }
    public void    setExplanatoryText(String v)      { this.explanatoryText = v; }
    public boolean isPrintExplanatoryText()     { return printExplanatoryText; }
    public void    setPrintExplanatoryText(boolean v){ this.printExplanatoryText = v; }
    public String  getExplanatoryTextLocation()      { return explanatoryTextLocation; }
    public void    setExplanatoryTextLocation(String v){ this.explanatoryTextLocation = v; }
}
