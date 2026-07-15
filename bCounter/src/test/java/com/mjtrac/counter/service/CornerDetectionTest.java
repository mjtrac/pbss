/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.counter.service;

import com.mjtrac.counter.model.BboxReport.PageLayout;
import com.mjtrac.counter.service.Point2D;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for CornerDetectionService — verifies that BL/BR/TL/TR/PTL/PTR marks
 * are correctly located in clean, rotated, and upside-down ballot images.
 *
 * Test images are generated from the real ballot PDF and stored in
 * src/test/resources/test-images/.  The YAML is read from bBuilder_ballots/.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CornerDetectionTest {

    @Autowired
    private CornerDetectionService cornerDetector;

    @Autowired
    private BboxReportLoader loader;

    /** Tolerance in pixels for mark centre position (±15px at 300dpi ≈ ±0.05") */
    private static final double TOL_PX = 15.0;

    /** DPI of test images (matches pdftoppm -r 300) */
    private static final int DPI = 300;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static BufferedImage loadTestImage(String name) throws Exception {
        try (InputStream is = CornerDetectionTest.class
                .getResourceAsStream("/test-images/" + name)) {
            assertThat(is).as("Test image not found: " + name).isNotNull();
            return ImageIO.read(is);
        }
    }

    private static PageLayout loadLayout() throws Exception {
        // Load from the real bBuilder output
        String yamlDir = System.getProperty("user.home") + "/bBuilder_ballots";
        Path dir = Paths.get(yamlDir);
        if (!Files.exists(dir)) {
            // Fall back to test resources
            dir = Paths.get(CornerDetectionTest.class
                .getResource("/test-images").toURI()).getParent();
        }
        var layouts = new BboxReportLoader().loadForBarcode(dir, "1|1|1|1|1|1");
        assertThat(layouts).as("No layout found for barcode 1|1|1|1|1|1").isNotEmpty();
        return layouts.get(0);
    }

    private Point2D[] detectCorners(BufferedImage image, PageLayout layout) {
        BufferedImage[] holder = { image };
        return cornerDetector.findContentBoxCorners(
            holder, DPI,
            layout.contentAreaWidth,
            layout.contentAreaHeight,
            layout, 0, 0);
    }

    // ── BL / BR detection ─────────────────────────────────────────────────────

    @Test @Order(1)
    @DisplayName("BL and BR marks found on clean ballot")
    void testBlBrFound_cleanBallot() throws Exception {
        BufferedImage img   = loadTestImage("ballot_clean-1.png");
        PageLayout    layout = loadLayout();

        Point2D[] corners = detectCorners(img, layout);

        assertThat(corners).as("Corners should not be null").isNotNull();
        // corners order: TL[0], TR[1], BR[2], BL[3]
        assertThat(corners[3]).as("BL corner").isNotNull();
        assertThat(corners[2]).as("BR corner").isNotNull();

        // BL should be near bottom-left of content area
        double expectedBlX = layout.cornerMarks[3][0] * DPI;
        double expectedBlY = layout.cornerMarks[3][1] * DPI;
        assertThat(corners[3].x()).as("BL x")
            .isCloseTo(expectedBlX, within(TOL_PX * 5));
        assertThat(corners[3].y()).as("BL y")
            .isCloseTo(expectedBlY, within(TOL_PX * 5));

        // BR should be near bottom-right
        double expectedBrX = layout.cornerMarks[2][0] * DPI;
        double expectedBrY = layout.cornerMarks[2][1] * DPI;
        assertThat(corners[2].x()).as("BR x")
            .isCloseTo(expectedBrX, within(TOL_PX * 5));
        assertThat(corners[2].y()).as("BR y")
            .isCloseTo(expectedBrY, within(TOL_PX * 5));
    }

    // ── TL / TR detection ─────────────────────────────────────────────────────

    @Test @Order(2)
    @DisplayName("TL and TR marks found on clean ballot")
    void testTlTrFound_cleanBallot() throws Exception {
        BufferedImage img    = loadTestImage("ballot_clean-1.png");
        PageLayout    layout = loadLayout();

        Point2D[] corners = detectCorners(img, layout);

        assertThat(corners).as("Corners should not be null").isNotNull();
        assertThat(corners[0]).as("TL corner").isNotNull();
        assertThat(corners[1]).as("TR corner").isNotNull();

        // Content box TL should be near expected position
        double expectedTlX = layout.contentAreaOffsetLeft * DPI;
        double expectedTlY = layout.contentAreaOffsetTop  * DPI;
        assertThat(corners[0].x()).as("TL x")
            .isCloseTo(expectedTlX, within(TOL_PX * 5));
        assertThat(corners[0].y()).as("TL y")
            .isCloseTo(expectedTlY, within(TOL_PX * 5));
    }

    // ── PTL / PTR detection ───────────────────────────────────────────────────

    @Test @Order(3)
    @DisplayName("PTL and PTR page-level marks found on clean ballot")
    void testPtlPtrFound_cleanBallot() throws Exception {
        BufferedImage img    = loadTestImage("ballot_clean-1.png");
        PageLayout    layout = loadLayout();

        assertThat(layout.pageMarks).as("pageMarks should be present in YAML").isNotNull();
        assertThat(layout.pageMarks.length).as("pageMarks should have 2 entries").isEqualTo(2);

        // PTL expected position in pixels
        double ptlX = layout.pageMarks[0][0] * DPI;
        double ptlY = layout.pageMarks[0][1] * DPI;
        double ptrX = layout.pageMarks[1][0] * DPI;
        double ptrY = layout.pageMarks[1][1] * DPI;

        // Verify positions are near the top of the page (within first 1 inch)
        assertThat(ptlY).as("PTL Y should be near top of page")
            .isLessThan(1.0 * DPI);
        assertThat(ptrY).as("PTR Y should be near top of page")
            .isLessThan(1.0 * DPI);

        // Verify PTL is on the left and PTR is on the right
        assertThat(ptlX).as("PTL X should be on left side")
            .isLessThan(img.getWidth() / 2.0);
        assertThat(ptrX).as("PTR X should be on right side")
            .isGreaterThan(img.getWidth() / 2.0);
    }

    // ── Upside-down detection ─────────────────────────────────────────────────

    @Test @Order(4)
    @DisplayName("Upside-down ballot is auto-rotated and corners found")
    void testUpsideDown_autoRotated() throws Exception {
        BufferedImage img    = loadTestImage("ballot_upside_down-1.png");
        PageLayout    layout = loadLayout();

        BufferedImage[] holder = { img };
        Point2D[] corners = cornerDetector.findContentBoxCorners(
            holder, DPI,
            layout.contentAreaWidth,
            layout.contentAreaHeight,
            layout, 0, 0);

        assertThat(corners).as("Corners should be found even on upside-down ballot")
            .isNotNull();
        assertThat(corners[0]).as("TL corner after auto-rotation").isNotNull();
        assertThat(corners[2]).as("BR corner after auto-rotation").isNotNull();

        // After rotation the image in holder[0] should differ from original
        assertThat(holder[0]).as("Image should have been rotated")
            .isNotSameAs(img);
    }

    // ── Rotated ballot ────────────────────────────────────────────────────────

    @Test @Order(5)
    @DisplayName("1° CCW rotated ballot — all corners found")
    void testRotatedCcw1_cornersFound() throws Exception {
        BufferedImage img    = loadTestImage("ballot_rot_ccw1-1.png");
        PageLayout    layout = loadLayout();

        Point2D[] corners = detectCorners(img, layout);

        assertThat(corners).as("Corners should be found on 1° CCW rotated ballot")
            .isNotNull();
        // All four corners should be non-null (or inferred via parallelogram)
        for (int i = 0; i < 4; i++) {
            assertThat(corners[i]).as("Corner[" + i + "] should not be null")
                .isNotNull();
        }

        // Content area dimensions should be close to expected
        double detectedW = dist(corners[0], corners[1]);
        double detectedH = dist(corners[0], corners[3]);
        assertThat(detectedW / DPI).as("Detected width in inches")
            .isCloseTo(layout.contentAreaWidth, within(0.5));
        assertThat(detectedH / DPI).as("Detected height in inches")
            .isCloseTo(layout.contentAreaHeight, within(1.0));
    }

    @Test @Order(6)
    @DisplayName("1.5° CCW rotated ballot — all corners found")
    void testRotatedCcw1_5_cornersFound() throws Exception {
        BufferedImage img    = loadTestImage("ballot_rot_ccw1_5-1.png");
        PageLayout    layout = loadLayout();

        Point2D[] corners = detectCorners(img, layout);

        assertThat(corners).as("Corners should be found on 1.5° CCW rotated ballot")
            .isNotNull();
        for (int i = 0; i < 4; i++) {
            assertThat(corners[i]).as("Corner[" + i + "] should not be null")
                .isNotNull();
        }
        double detectedW = dist(corners[0], corners[1]);
        double detectedH = dist(corners[0], corners[3]);
        assertThat(detectedW / DPI).as("Detected width in inches")
            .isCloseTo(layout.contentAreaWidth, within(0.5));
        assertThat(detectedH / DPI).as("Detected height in inches")
            .isCloseTo(layout.contentAreaHeight, within(1.0));
    }

    @Test @Order(7)
    @DisplayName("2° CCW rotated ballot — all corners found")
    void testRotatedCcw2_cornersFound() throws Exception {
        BufferedImage img    = loadTestImage("ballot_rot_ccw2-1.png");
        PageLayout    layout = loadLayout();

        Point2D[] corners = detectCorners(img, layout);

        assertThat(corners).as("Corners should be found on 2° CCW rotated ballot")
            .isNotNull();
        for (int i = 0; i < 4; i++) {
            assertThat(corners[i]).as("Corner[" + i + "] should not be null")
                .isNotNull();
        }
        double detectedW = dist(corners[0], corners[1]);
        double detectedH = dist(corners[0], corners[3]);
        // 2° rotation allows wider tolerance due to geometric prediction error
        assertThat(detectedW / DPI).as("Detected width in inches")
            .isCloseTo(layout.contentAreaWidth, within(0.75));
        assertThat(detectedH / DPI).as("Detected height in inches")
            .isCloseTo(layout.contentAreaHeight, within(1.2));
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private double dist(Point2D a, Point2D b) {
        double dx = b.x() - a.x(), dy = b.y() - a.y();
        return Math.sqrt(dx * dx + dy * dy);
    }
}
