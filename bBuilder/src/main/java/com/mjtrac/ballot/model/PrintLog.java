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
import java.time.LocalDateTime;

/**
 * Audit trail entry: who printed which ballot combination, when, and how many copies.
 * Required for election security compliance.
 */
@Entity
@Table(name = "print_logs")
public class PrintLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private User printedBy;

    @ManyToOne(optional = false)
    private BallotCombination ballotCombination;

    private LocalDateTime printedAt = LocalDateTime.now();

    private int copies = 1;

    /** Snapshot of the paper size used at time of printing. */
    private String paperSize;

    // ── Getters & Setters ─────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getPrintedBy() { return printedBy; }
    public void setPrintedBy(User printedBy) { this.printedBy = printedBy; }

    public BallotCombination getBallotCombination() { return ballotCombination; }
    public void setBallotCombination(BallotCombination ballotCombination) {
        this.ballotCombination = ballotCombination;
    }

    public LocalDateTime getPrintedAt() { return printedAt; }
    public void setPrintedAt(LocalDateTime printedAt) { this.printedAt = printedAt; }

    public int getCopies() { return copies; }
    public void setCopies(int copies) { this.copies = copies; }

    public String getPaperSize() { return paperSize; }
    public void setPaperSize(String paperSize) { this.paperSize = paperSize; }
}
