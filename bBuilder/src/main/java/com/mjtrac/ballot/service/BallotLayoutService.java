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
package com.mjtrac.ballot.service;

import com.mjtrac.ballot.util.BallotDimensions;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores ballot layout data produced during PDF generation so it can later
 * be exported as XML/YAML bounding-box reports.
 *
 * Data is stored as a list of PageLayout objects (one per printed side),
 * each of which contains the content-area bounding box and all contests
 * on that page.
 */
@Service
public class BallotLayoutService {

    private final Map<Long, List<BallotDimensions.PageLayout>> layoutCache =
        new ConcurrentHashMap<>();

    public void storeLayout(Long combinationId,
                            List<BallotDimensions.PageLayout> pages) {
        layoutCache.put(combinationId, Collections.unmodifiableList(pages));
    }

    public List<BallotDimensions.PageLayout> getLayout(Long combinationId) {
        List<BallotDimensions.PageLayout> result = layoutCache.get(combinationId);
        if (result == null) {
            throw new IllegalStateException(
                "No layout data for combination " + combinationId +
                ". Generate the ballot PDF first, then request the export.");
        }
        return result;
    }

    /** Convenience: flat list of all ContestPositions across all pages. */
    public List<BallotDimensions.ContestPosition> getPositions(Long combinationId) {
        return getLayout(combinationId).stream()
            .flatMap(p -> p.getContests().stream())
            .toList();
    }

    public boolean hasLayout(Long combinationId) {
        return layoutCache.containsKey(combinationId);
    }
}
