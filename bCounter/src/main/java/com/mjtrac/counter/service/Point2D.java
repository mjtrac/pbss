/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.service;

/**
 * 2D point in image pixel coordinates (origin = image top-left, Y increases downward).
 *
 * Used by {@link BallotCornerDetectorService} implementations and callers
 * throughout bCounter.  Defined as a top-level class so it can be imported
 * without requiring a canonical nested-type import path.
 */
public record Point2D(double x, double y) {

    /** Rounds to the nearest integer x coordinate. */
    public int xi() { return (int) Math.round(x); }

    /** Rounds to the nearest integer y coordinate. */
    public int yi() { return (int) Math.round(y); }

    /** Returns a new point offset by (dx, dy). */
    public Point2D translate(double dx, double dy) {
        return new Point2D(x + dx, y + dy);
    }

    /** Euclidean distance to another point. */
    public double distanceTo(Point2D other) {
        double dx = this.x - other.x, dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public String toString() {
        return String.format("(%.1f, %.1f)", x, y);
    }
}
