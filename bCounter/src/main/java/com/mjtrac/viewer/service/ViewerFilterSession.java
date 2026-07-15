/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewer.service;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Holds the active filter state for one user's bViewer session.
 *
 * Stored as a plain session attribute (key: "viewerFilter") so it survives
 * page navigations without requerying.  The controller replaces it whenever
 * the user applies a new filter and clears it on explicit clear or new-election.
 *
 * THREAD SAFETY: HTTP sessions are per-user; Spring MVC processes one request
 * per session at a time by default, so no synchronisation is needed.
 */
public class ViewerFilterSession implements Serializable {

    public enum FilterType { NONE, GLOB, SQL }

    /** Sentinel representing "no filter active". */
    public static final ViewerFilterSession NONE =
        new ViewerFilterSession(FilterType.NONE, "", "", Collections.emptyList());

    // ── State ─────────────────────────────────────────────────────────────────

    /** Type of the active filter. */
    public final FilterType filterType;

    /** Raw filter value as entered by the user (glob pattern or SQL clause). */
    public final String filterValue;

    /** Human-readable description shown in the nav bar, e.g. "glob: Precinct_1/*" */
    public final String filterDesc;

    /**
     * Ordered list of BallotImage IDs matching the filter.
     * Empty when filterType == NONE (meaning all images are in scope,
     * but listAll() is used rather than this list).
     */
    public final List<Long> filteredIds;

    public ViewerFilterSession(FilterType filterType,
                                String filterValue,
                                String filterDesc,
                                List<Long> filteredIds) {
        this.filterType   = filterType;
        this.filterValue  = filterValue != null ? filterValue : "";
        this.filterDesc   = filterDesc  != null ? filterDesc  : "";
        this.filteredIds  = Collections.unmodifiableList(
            filteredIds != null ? filteredIds : Collections.emptyList());
    }

    public boolean isActive()  { return filterType != FilterType.NONE; }
    public int     size()      { return filteredIds.size(); }

    /** Returns the display label for the filter type, e.g. "Glob filter" */
    public String typeLabel() {
        return switch (filterType) {
            case GLOB -> "Name filter";
            case SQL  -> "SQL filter";
            default   -> "";
        };
    }

    @Override
    public String toString() {
        return "ViewerFilterSession[" + filterType + " \"" + filterValue
            + "\" → " + filteredIds.size() + " images]";
    }
}
