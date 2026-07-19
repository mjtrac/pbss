/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 */
package com.mjtrac.counter.service;

import java.awt.image.BufferedImage;

/**
 * Analyses the arrow-style vote indicator.
 *
 * Instead of measuring dark pixel percentage over the full indicator bounding
 * box, this analyser samples a small central zone:
 *
 *   Central zone = centre of the original oval bounding box
 *                  ± 1/8 of the box dimensions in each direction
 *               → a region 1/4 of the original width × 1/4 of the original height
 *
 * A voter fills in (or marks) the space between the two triangles, which
 * are at the left and right edges.  Any dark pixels in the central zone
 * indicate a marked ballot; the zone is entirely empty (white) when
 * no mark has been made.
 *
 * Dark pixel percentage is not used: the decision is binary — any dark
 * pixel in the central zone = VOTED.
 */
public class ArrowIndicatorAnalyzer {

    private ArrowIndicatorAnalyzer() {}

    /**
     * Determine whether the arrow indicator has been marked.
     *
     * @param warped     the homography-corrected canonical ballot image
     * @param px         left edge of the indicator bounding box in canonical pixels
     * @param py         top edge of the indicator bounding box in canonical pixels
     * @param pw         width of the indicator bounding box in canonical pixels
     * @param ph         height of the indicator bounding box in canonical pixels
     * @param threshold  luminance below which a pixel is considered dark (typically 128)
     * @return true if any dark pixel is found in the central zone
     */
    public static boolean isMarked(BufferedImage warped,
                                   int px, int py, int pw, int ph,
                                   int threshold) {
        int imgW = warped.getWidth();
        int imgH = warped.getHeight();

        // Central zone: centre ± pw/8 in x, centre ± ph/8 in y
        // → width = pw/4, height = ph/4
        int cx = px + pw / 2;
        int cy = py + ph / 2;
        int halfW = Math.max(1, pw / 8);
        int halfH = Math.max(1, ph / 8);

        int x0 = Math.max(0,    cx - halfW);
        int y0 = Math.max(0,    cy - halfH);
        int x1 = Math.min(imgW, cx + halfW);
        int y1 = Math.min(imgH, cy + halfH);

        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = warped.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >>  8) & 0xFF;
                int b =  rgb        & 0xFF;
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                if (lum < threshold) return true;
            }
        }
        return false;
    }

    /**
     * Returns a descriptive string of the sampled zone for debug logging.
     */
    public static String zoneDescription(int px, int py, int pw, int ph) {
        int cx = px + pw / 2, cy = py + ph / 2;
        int halfW = Math.max(1, pw / 8), halfH = Math.max(1, ph / 8);
        return String.format("centre=(%d,%d) zone=(%d,%d)-(%d,%d) size=%dx%d",
            cx, cy,
            cx - halfW, cy - halfH,
            cx + halfW, cy + halfH,
            halfW * 2, halfH * 2);
    }
}
