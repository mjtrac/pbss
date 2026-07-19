/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewerui;

import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the Java port of viewer-view.js's homography math against known
 * cases — this is the trickiest, most bug-prone piece of the Swing port,
 * so it gets a dedicated pin before relying on manual visual inspection.
 */
class HomographyTest {

    private static final double TOL = 1e-6;

    @Test
    void identityCornersMapBoxUnchanged() {
        // Corner marks exactly matching the canonical rectangle -> identity transform.
        Homography h = Homography.build("0,0,1000,0,1000,800,0,800",
            1000, 800, false, 1000, 800, 300, 300);

        Rectangle2D.Double r = h.imageRect(100, 100, 50, 30);

        assertThat(r.x).isCloseTo(100, org.assertj.core.data.Offset.offset(TOL));
        assertThat(r.y).isCloseTo(100, org.assertj.core.data.Offset.offset(TOL));
        assertThat(r.width).isCloseTo(50, org.assertj.core.data.Offset.offset(TOL));
        assertThat(r.height).isCloseTo(30, org.assertj.core.data.Offset.offset(TOL));
    }

    @Test
    void translatedCornersShiftBox() {
        // Whole detected quad shifted by (50,50) relative to the canonical rectangle.
        Homography h = Homography.build("50,50,1050,50,1050,850,50,850",
            1000, 800, false, 1100, 900, 300, 300);

        Rectangle2D.Double r = h.imageRect(100, 100, 50, 30);

        assertThat(r.x).isCloseTo(150, org.assertj.core.data.Offset.offset(TOL));
        assertThat(r.y).isCloseTo(150, org.assertj.core.data.Offset.offset(TOL));
    }

    @Test
    void missingCornersFallBackToDpiRatio() {
        Homography h = Homography.build(null, 1000, 800, false, 1000, 800, 300, 150);

        Rectangle2D.Double r = h.imageRect(10, 10, 5, 5);

        // imageDpi/warpDpi = 300/150 = 2.0
        assertThat(r.x).isCloseTo(20, org.assertj.core.data.Offset.offset(TOL));
        assertThat(r.width).isCloseTo(10, org.assertj.core.data.Offset.offset(TOL));
    }

    @Test
    void wasRotatedReflectsCornersThroughImageCenterBeforeSolving() {
        // These corners, reflected through a 1000x800 image center, become
        // exactly the identity rectangle from identityCornersMapBoxUnchanged().
        Homography h = Homography.build("1000,800,0,800,0,0,1000,0",
            1000, 800, true, 1000, 800, 300, 300);

        Rectangle2D.Double r = h.imageRect(100, 100, 50, 30);

        assertThat(r.x).isCloseTo(100, org.assertj.core.data.Offset.offset(TOL));
        assertThat(r.y).isCloseTo(100, org.assertj.core.data.Offset.offset(TOL));
    }

    @Test
    void malformedCornerMarksFallsBackGracefully() {
        Homography h = Homography.build("not,valid,data", 1000, 800, false, 1000, 800, 300, 300);
        Rectangle2D.Double r = h.imageRect(10, 10, 5, 5);
        // Falls back to ratio 300/300 = 1.0, not an exception.
        assertThat(r.x).isCloseTo(10, org.assertj.core.data.Offset.offset(TOL));
    }
}
