/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.service;

import com.mjtrac.counter.model.BboxReport.ContestBox;
import com.mjtrac.counter.model.BboxReport.IndicatorBox;
import com.mjtrac.counter.model.BboxReport.PageLayout;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for MarkerAnalysisService.analyse().
 *
 * Uses simple synthesised images (white background, black fills/lines)
 * with null homography (Hinv=null, detectedTL=null) so the service uses
 * direct coordinate lookup without perspective correction.
 */
@SpringBootTest
@DisplayName("MarkerAnalysisService — indicator detection")
class MarkerAnalysisServiceTest {

    @Autowired MarkerAnalysisService markerService;

    private static final int    DPI          = 300;
    private static final int    THRESHOLD    = 128;
    private static final double DARK_PCT_MIN = 8.0;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static BufferedImage whiteImage(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, w, h); g.dispose();
        return img;
    }

    private static PageLayout page(int imgW, int imgH) {
        PageLayout p = new PageLayout();
        p.contentAreaOffsetLeft = 0.0;
        p.contentAreaOffsetTop  = 0.0;
        p.contentAreaWidth      = imgW / (double) DPI;
        p.contentAreaHeight     = imgH / (double) DPI;
        return p;
    }

    private static ContestBox contest(double leftIn, double topIn,
                                       double wIn, double hIn) {
        ContestBox c = new ContestBox();
        c.title      = "Test Contest";
        c.contestType = "PLURALITY";
        c.maxVotes   = 1;
        c.offsetLeft = leftIn;
        c.offsetTop  = topIn;
        c.width      = wIn;
        c.height     = hIn;
        return c;
    }

    private static IndicatorBox indicator(double leftIn, double topIn,
                                           double wIn, double hIn,
                                           String style) {
        IndicatorBox b = new IndicatorBox();
        b.offsetLeft      = leftIn;
        b.offsetTop       = topIn;
        b.width           = wIn;
        b.height          = hIn;
        b.indicatorStyle  = style;
        b.candidateName   = "Test Candidate";
        return b;
    }

    private com.mjtrac.counter.model.BboxReport.MarkingResult
    analyse(BufferedImage img, PageLayout p, ContestBox c, IndicatorBox ind) {
        return markerService.analyse(img, p, c, ind,
            DPI, DPI, THRESHOLD, DARK_PCT_MIN, null, null);
    }

    // ── OVAL: unmarked ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unmarked oval: white image yields darkPct < 5 and marked = false")
    void testOvalUnmarked() {
        int w = 300, h = 300;
        var result = analyse(whiteImage(w, h), page(w, h),
            contest(0.0, 0.0, 1.0, 1.0),
            indicator(0.35, 0.42, 0.3, 0.12, "OVAL"));
        assertThat(result.darkPct).isLessThan(5.0);
        assertThat(result.marked).isFalse();
    }

    // ── OVAL: marked ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Marked oval: filled black oval yields darkPct > 60 and marked = true")
    void testOvalMarked() {
        int w = 300, h = 300;
        double lIn = 0.35, tIn = 0.42, wIn = 0.3, hIn = 0.12;
        BufferedImage img = whiteImage(w, h);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillOval((int)(lIn*DPI), (int)(tIn*DPI),
                   (int)(wIn*DPI), (int)(hIn*DPI));
        g.dispose();
        var result = analyse(img, page(w, h),
            contest(0.0, 0.0, 1.0, 1.0),
            indicator(lIn, tIn, wIn, hIn, "OVAL"));
        assertThat(result.darkPct).isGreaterThan(60.0);
        assertThat(result.marked).isTrue();
    }

    // ── CONNECT_DOTS: unmarked ────────────────────────────────────────────────

    @Test
    @DisplayName("Connect-dots: white image yields marked = false")
    void testConnectDotsUnmarked() {
        int w = 600, h = 300;
        var result = analyse(whiteImage(w, h), page(w, h),
            contest(0.0, 0.0, 2.0, 1.0),
            indicator(0.9, 0.42, 0.1, 0.1, "CONNECT_DOTS"));
        assertThat(result.marked).isFalse();
    }

    // ── CONNECT_DOTS: marked ──────────────────────────────────────────────────

    @Test
    @DisplayName("Connect-dots: horizontal line through centre yields marked = true")
    void testConnectDotsMarked() {
        int w = 600, h = 300;
        double lIn = 0.9, tIn = 0.42, wIn = 0.1, hIn = 0.1;
        BufferedImage img = whiteImage(w, h);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        int lineY = (int)((tIn + hIn / 2.0) * DPI);
        g.drawLine((int)(lIn*DPI), lineY, (int)((lIn+wIn)*DPI), lineY);
        g.dispose();
        var result = analyse(img, page(w, h),
            contest(0.0, 0.0, 2.0, 1.0),
            indicator(lIn, tIn, wIn, hIn, "CONNECT_DOTS"));
        assertThat(result.marked).isTrue();
    }

    // ── CONNECT_DOTS: false positive guard ────────────────────────────────────

    @Test
    @DisplayName("Connect-dots: line well above sampling zone yields marked = false")
    void testConnectDotsFalsePositive() {
        int w = 600, h = 300;
        double lIn = 0.9, tIn = 0.42, wIn = 0.1, hIn = 0.1;
        BufferedImage img = whiteImage(w, h);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.drawLine(0, (int)((tIn - 0.2) * DPI), w, (int)((tIn - 0.2) * DPI));
        g.dispose();
        var result = analyse(img, page(w, h),
            contest(0.0, 0.0, 2.0, 1.0),
            indicator(lIn, tIn, wIn, hIn, "CONNECT_DOTS"));
        assertThat(result.marked).isFalse();
    }

    // ── Two marked indicators (overvote precondition) ─────────────────────────

    @Test
    @DisplayName("Two filled ovals both register as marked")
    void testTwoMarkedIndicators() {
        int w = 300, h = 600;
        double wIn = 0.3, hIn = 0.12;
        BufferedImage img = whiteImage(w, h);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillOval((int)(0.1*DPI), (int)(0.1*DPI), (int)(wIn*DPI), (int)(hIn*DPI));
        g.fillOval((int)(0.1*DPI), (int)(0.4*DPI), (int)(wIn*DPI), (int)(hIn*DPI));
        g.dispose();
        PageLayout  p  = page(w, h);
        ContestBox  c  = contest(0.0, 0.0, 1.0, 2.0);
        var r1 = analyse(img, p, c, indicator(0.1, 0.1, wIn, hIn, "OVAL"));
        var r2 = analyse(img, p, c, indicator(0.1, 0.4, wIn, hIn, "OVAL"));
        assertThat(r1.marked).isTrue();
        assertThat(r2.marked).isTrue();
    }
}
