/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewerui;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps an indicator box's CANONICAL (perspective-corrected, upright) pixel
 * coordinates back to the ORIGINAL scanned image's pixel coordinates, so the
 * overlay can be drawn on the unwarped image exactly as it was scanned.
 *
 * Direct Java port of the same math in blCounter's
 * static/js/viewer-view.js (parseCorners/computeHomography/
 * solveHomography4pt/gaussElim/applyH/canonicalBoxToImageRect) — kept
 * numerically identical so overlays line up the same way here as they do
 * in the web/WebView Viewer. See that file for the original comments.
 */
final class Homography {

    private record Point(double x, double y) {}

    /** 3x3 projective matrix mapping canonical coords -> image coords, or null if unavailable. */
    private final double[][] h;
    private final double fallbackRatio;

    private Homography(double[][] h, double fallbackRatio) {
        this.h = h;
        this.fallbackRatio = fallbackRatio;
    }

    /**
     * @param cornerMarks   "TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy" as stored on BallotImage, or null
     * @param canonicalW    canonical (warped) content width, pixels
     * @param canonicalH    canonical (warped) content height, pixels
     * @param wasRotated    if true, corner marks are in the rotated-upright image's coordinate
     *                      space and must be reflected back through the image center
     * @param natW          natural width of the loaded (original, unrotated) image, pixels
     * @param natH           natural height of the loaded image, pixels
     * @param imageDpi      DPI of the original image (for the no-corners fallback ratio)
     * @param warpDpi       DPI used to build the canonical image (for the fallback ratio)
     */
    static Homography build(String cornerMarks, int canonicalW, int canonicalH,
                             boolean wasRotated, int natW, int natH,
                             int imageDpi, int warpDpi) {
        double ratio = warpDpi > 0 ? (double) imageDpi / warpDpi : 1.0;
        List<Point> corners = parseCorners(cornerMarks);
        if (corners == null || canonicalW <= 0 || canonicalH <= 0) {
            return new Homography(null, ratio);
        }
        if (wasRotated) {
            List<Point> reflected = new ArrayList<>(4);
            for (Point c : corners) reflected.add(new Point(natW - c.x(), natH - c.y()));
            corners = reflected;
        }
        List<Point> src = List.of(
            new Point(0, 0),
            new Point(canonicalW, 0),
            new Point(canonicalW, canonicalH),
            new Point(0, canonicalH));
        double[][] matrix = solveHomography4pt(src, corners);
        return new Homography(matrix, ratio);
    }

    /** Axis-aligned bounding rect of the transformed box, in original-image pixel space. */
    Rectangle2D.Double imageRect(double absLeft, double absTop, double w, double hh) {
        if (h == null) {
            return new Rectangle2D.Double(absLeft * fallbackRatio, absTop * fallbackRatio,
                w * fallbackRatio, hh * fallbackRatio);
        }
        Point p1 = apply(absLeft,     absTop);
        Point p2 = apply(absLeft + w, absTop);
        Point p3 = apply(absLeft + w, absTop + hh);
        Point p4 = apply(absLeft,     absTop + hh);
        double minX = Math.min(Math.min(p1.x(), p2.x()), Math.min(p3.x(), p4.x()));
        double maxX = Math.max(Math.max(p1.x(), p2.x()), Math.max(p3.x(), p4.x()));
        double minY = Math.min(Math.min(p1.y(), p2.y()), Math.min(p3.y(), p4.y()));
        double maxY = Math.max(Math.max(p1.y(), p2.y()), Math.max(p3.y(), p4.y()));
        return new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY);
    }

    private Point apply(double cx, double cy) {
        double w = h[2][0] * cx + h[2][1] * cy + h[2][2];
        return new Point(
            (h[0][0] * cx + h[0][1] * cy + h[0][2]) / w,
            (h[1][0] * cx + h[1][1] * cy + h[1][2]) / w);
    }

    private static List<Point> parseCorners(String cornerMarks) {
        if (cornerMarks == null || cornerMarks.isBlank()) return null;
        String[] parts = cornerMarks.split(",");
        if (parts.length < 8) return null;
        double[] v = new double[8];
        try {
            for (int i = 0; i < 8; i++) v[i] = Double.parseDouble(parts[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return List.of(
            new Point(v[0], v[1]),   // TL
            new Point(v[2], v[3]),   // TR
            new Point(v[4], v[5]),   // BR
            new Point(v[6], v[7]));  // BL
    }

    /** Solves for the 8 free parameters of a 3x3 homography (h[2][2]=1) mapping src -> dst. */
    private static double[][] solveHomography4pt(List<Point> src, List<Point> dst) {
        double[][] A = new double[8][8];
        double[]   b = new double[8];
        for (int i = 0; i < 4; i++) {
            double sx = src.get(i).x(), sy = src.get(i).y();
            double dx = dst.get(i).x(), dy = dst.get(i).y();
            A[2 * i]     = new double[]{ sx, sy, 1,  0,  0, 0, -dx * sx, -dx * sy};
            b[2 * i]     = dx;
            A[2 * i + 1] = new double[]{  0,  0, 0, sx, sy, 1, -dy * sx, -dy * sy};
            b[2 * i + 1] = dy;
        }
        double[] hv = gaussElim(A, b);
        if (hv == null) return null;
        return new double[][]{
            {hv[0], hv[1], hv[2]},
            {hv[3], hv[4], hv[5]},
            {hv[6], hv[7], 1.0},
        };
    }

    private static double[] gaussElim(double[][] a, double[] b) {
        int n = a.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int maxRow = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(m[row][col]) > Math.abs(m[maxRow][col])) maxRow = row;
            }
            double[] tmp = m[col]; m[col] = m[maxRow]; m[maxRow] = tmp;
            if (Math.abs(m[col][col]) < 1e-12) return null;
            for (int row = col + 1; row < n; row++) {
                double f = m[row][col] / m[col][col];
                for (int k = col; k <= n; k++) m[row][k] -= f * m[col][k];
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            x[i] = m[i][n];
            for (int j = i + 1; j < n; j++) x[i] -= m[i][j] * x[j];
            x[i] /= m[i][i];
        }
        return x;
    }
}
