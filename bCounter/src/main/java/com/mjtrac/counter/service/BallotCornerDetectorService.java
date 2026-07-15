/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.service;

import com.mjtrac.counter.service.Point2D;

import com.mjtrac.counter.model.BboxReport.PageLayout;
import java.awt.image.BufferedImage;

/**
 * Strategy interface for locating the four corners of the ballot content
 * bounding box in a scanned ballot image.
 *
 * <p>pbss's default implementation ({@link CornerDetectionService}) finds
 * the corners by locating the registration marks printed at the corners of
 * the content box.  Alternative implementations may use:
 * <ul>
 *   <li>OpenCV template matching against learned mark templates (as generated
 *       by {@code ballot_mapper.py}'s LEARN mode)</li>
 *   <li>Edge detection / Hough line intersection</li>
 *   <li>Deep learning corner detection</li>
 *   <li>Fixed-geometry calculation from a known scanner registration point</li>
 *   <li>Manual operator click (for one-off corrections)</li>
 * </ul>
 *
 * <p>Implementations must be Spring beans annotated with {@code @Service} or
 * {@code @Component}.  Use {@code @Primary} or {@code @Qualifier} to select
 * the active implementation when multiple are present.
 *
 * <h3>Coordinate system</h3>
 * All returned pixel coordinates are in the <em>original image</em> coordinate
 * system (origin = top-left, Y increases downward), matching standard Java
 * image conventions.  The {@code imageHolder} array allows implementations to
 * replace the image in-place (e.g. after 180° rotation to correct upside-down
 * ballots) — callers use {@code imageHolder[0]} after the call returns.
 */
public interface BallotCornerDetectorService {

    /**
     * Locate the four corners of the ballot content bounding box.
     *
     * <p>The content bounding box is the heavy rectangular border that surrounds
     * all contest columns on the ballot.  The four corners are returned in the
     * order [TL, TR, BR, BL] (top-left, top-right, bottom-right, bottom-left).
     *
     * <p>Individual corners may be null if they could not be detected.
     * {@link ScannerService} synthesizes missing corners from the found ones
     * when at least 3 are present.
     *
     * <p>Implementations must never throw — return an array of nulls if
     * detection fails entirely.
     *
     * @param imageHolder    single-element array containing the ballot image.
     *                       Implementations may replace {@code imageHolder[0]}
     *                       with a rotated/corrected copy; callers will use the
     *                       updated image for subsequent processing.
     * @param dpi            scan resolution in dots per inch
     * @param expectedWidthIn  expected content box width in inches (from YAML),
     *                       or 0 if unknown (e.g. for foreign ballots)
     * @param expectedHeightIn expected content box height in inches (from YAML),
     *                       or 0 if unknown
     * @param layout         the parsed YAML layout for this ballot combination,
     *                       providing mark geometry hints; may be null for
     *                       foreign ballots without a pbss layout file
     * @param bcOffsetX      X pixel offset of the barcode/identifier from the
     *                       image centre (used to bias corner search toward the
     *                       opposite side); 0 if unknown
     * @param bcOffsetY      Y pixel offset of the barcode/identifier; 0 if unknown
     * @return array of exactly 4 {@link Point2D} values [TL, TR, BR, BL],
     *         any of which may be null if that corner was not found
     */
    Point2D[] findContentBoxCorners(BufferedImage[] imageHolder,
                                     int dpi,
                                     double expectedWidthIn,
                                     double expectedHeightIn,
                                     PageLayout layout,
                                     double bcOffsetX,
                                     double bcOffsetY);

    /**
     * Convenience overload without barcode offset.
     * Default implementation passes (0, 0) for the offsets.
     */
    default Point2D[] findContentBoxCorners(BufferedImage[] imageHolder,
                                             int dpi,
                                             double expectedWidthIn,
                                             double expectedHeightIn,
                                             PageLayout layout) {
        return findContentBoxCorners(imageHolder, dpi,
            expectedWidthIn, expectedHeightIn, layout, 0, 0);
    }


    /**
     * 2D point type used by this interface.
     *
     * <p>{@link CornerDetectionService#Point2D} is the canonical definition,
     * kept there for backward compatibility with all existing callers.
     * Use {@code CornerDetectionService.Point2D} or import it directly.
     */
    // Point2D is defined in CornerDetectionService for backward compat.
    // Implementations and callers: import com.mjtrac.counter.service.CornerDetectionService.Point2D
}
