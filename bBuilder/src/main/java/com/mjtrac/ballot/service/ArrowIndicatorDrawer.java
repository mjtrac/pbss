/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Ballot System — licensed under the GNU General Public License v3.
 */
package com.mjtrac.ballot.service;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;

/**
 * Draws the arrow-style vote indicator: two small filled isosceles triangles,
 * one at the left edge and one at the right edge of the oval bounding box,
 * both pointing inward (tips toward the centre).
 *
 * Layout (using the same bounding box as an oval indicator):
 *
 *   Box width  = W,  box height = H
 *   Triangle width  = W/4  (at most one quarter of box width)
 *   Triangle base   = H/4  (one quarter of box height), centred vertically
 *
 *   Left triangle:  base at x=0, tip at x=W/4,  midY ± H/8
 *   Right triangle: base at x=W, tip at x=3W/4, midY ± H/8
 *
 * The centre half of the box (x = W/4 .. 3W/4) is left empty.
 * Both triangles are filled solid black.
 */
public class ArrowIndicatorDrawer {

    private ArrowIndicatorDrawer() {}

    /**
     * Draw the two inward-pointing triangles.
     *
     * @param canvas  iText PdfCanvas to draw on
     * @param x       left edge of the oval bounding box in PDF points
     * @param y       bottom edge of the oval bounding box in PDF points (PDF coords = bottom-up)
     * @param w       width of the oval bounding box in PDF points
     * @param h       height of the oval bounding box in PDF points
     */
    public static void draw(PdfCanvas canvas, float x, float y, float w, float h) {
        float midY    = y + h / 2f;
        float triW    = w / 4f;   // triangle depth (horizontal extent)
        float halfBase = h / 8f;  // half the base height = H/4 base, so H/8 each side of midY

        canvas.saveState();
        canvas.setFillColor(ColorConstants.BLACK);

        // Left triangle: base on left edge, tip pointing right
        canvas.moveTo(x,          midY - halfBase)  // base top-left
              .lineTo(x,          midY + halfBase)  // base bottom-left
              .lineTo(x + triW,   midY)             // tip
              .closePathFillStroke();

        // Right triangle: base on right edge, tip pointing left
        canvas.moveTo(x + w,        midY - halfBase)  // base top-right
              .lineTo(x + w,        midY + halfBase)  // base bottom-right
              .lineTo(x + w - triW, midY)             // tip
              .closePathFillStroke();

        canvas.restoreState();
    }
}
