/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package com.mjtrac.counter.service;

import com.mjtrac.counter.service.Point2D;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes a perspective homography from detected ballot corners to the
 * theoretical content-area rectangle, then warps the image accordingly.
 *
 * WHY THIS MATTERS:
 * A scanned ballot may be skewed, rotated, or scaled differently in X vs Y.
 * By finding where the heavy outer border actually appears and mapping it to
 * the known theoretical rectangle, all subsequent box lookups become reliable.
 *
 * After warping, a pixel at theoretical coordinates (x_in, y_in) can be
 * sampled directly: pixel_x = x_in * dpi, pixel_y = y_in * dpi.
 *
 * PURE JAVA IMPLEMENTATION:
 * Computes the 3×3 homography H using the Direct Linear Transform (DLT)
 * algorithm.  H maps source (scanned) corners to destination (theoretical)
 * corners.  The warp is applied by mapping each destination pixel back to
 * the source image (inverse warp with bilinear interpolation).
 */
@Service
public class HomographyService {

    private static final Logger log =
        LoggerFactory.getLogger(HomographyService.class);

    /**
     * Warp the source image so the detected corners map to a rectangle
     * matching the expected content-area dimensions at the given DPI.
     *
     * @param source       original scanned image
     * @param detectedCorners [TL, TR, BR, BL] in source pixel coords
     * @param widthIn      expected content area width in inches
     * @param heightIn     expected content area height in inches
     * @param dpi          output resolution (pixels per inch)
     * @return perspective-corrected image, or scaled fallback on failure
     */
    public BufferedImage warpToContentArea(BufferedImage source,
                                            Point2D[] detectedCorners,
                                            double widthIn,
                                            double heightIn,
                                            int dpi) {
        int dstW = (int) Math.round(widthIn  * dpi);
        int dstH = (int) Math.round(heightIn * dpi);

        // Destination corners (perfect rectangle in output image)
        Point2D[] dst = {
            new Point2D(0,    0   ),  // TL
            new Point2D(dstW, 0   ),  // TR
            new Point2D(dstW, dstH),  // BR
            new Point2D(0,    dstH)   // BL
        };

        double[] H;
        try {
            H = computeHomography(detectedCorners, dst);
        } catch (Exception e) {
            log.warn("Homography failed (" + e.getMessage() +
                        "); falling back to simple scale");
            return scaleFallback(source, dstW, dstH);
        }

        // Inverse warp: for each destination pixel, find source pixel
        double[] Hinv = invertH(H);
        if (Hinv == null) {
            log.warn("Homography matrix not invertible; using scale fallback");
            return scaleFallback(source, dstW, dstH);
        }

        int srcW = source.getWidth(), srcH = source.getHeight();
        int[] srcPixels = getRgbPixels(source);
        int[] dstPixels = new int[dstW * dstH];

        for (int y = 0; y < dstH; y++) {
            for (int x = 0; x < dstW; x++) {
                // Map (x,y) in destination back to source using inverse H
                double[] src = applyHomography(Hinv, x, y);
                double sx = src[0], sy = src[1];

                // Bilinear interpolation
                int px = (int) sx, py = (int) sy;
                double fx = sx - px, fy = sy - py;

                if (px < 0 || px >= srcW - 1 || py < 0 || py >= srcH - 1) {
                    dstPixels[y * dstW + x] = 0xFFFFFFFF; // white for out-of-bounds
                    continue;
                }

                int c00 = srcPixels[py       * srcW + px    ];
                int c10 = srcPixels[py       * srcW + px + 1];
                int c01 = srcPixels[(py + 1) * srcW + px    ];
                int c11 = srcPixels[(py + 1) * srcW + px + 1];

                dstPixels[y * dstW + x] = bilinearInterp(c00, c10, c01, c11, fx, fy);
            }
        }

        BufferedImage dst_img = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB);
        dst_img.setRGB(0, 0, dstW, dstH, dstPixels, 0, dstW);
        return dst_img;
    }

    /**
     * Compute the 3×3 homography that maps canonical content-area coordinates
     * (in PIXELS at the given DPI) back to image pixel coordinates.
     *
     * This is the FORWARD transform: theoretical position → where it actually
     * appears on the scanned image.  Use this to verify that the coordinate
     * transform is placing boxes at the right locations on the real image.
     *
     * Specifically:
     *   - Destination (dst) = the perfect canonical rect at DPI resolution
     *   - Source (src)      = the detected corners in image pixels
     *   - The homography H maps src → dst  (used for the image warp)
     *   - The INVERSE H^-1 maps dst → src  (i.e., canonical → image pixels)
     *
     * Returns the 9-element row-major H^-1 matrix, or null if computation fails.
     *
     * @param detectedCorners [TL, TR, BR, BL] corners found in the image (pixels)
     * @param widthIn         theoretical content area width in inches
     * @param heightIn        theoretical content area height in inches
     * @param dpi             resolution at which to express canonical coordinates
     */
    public double[] computeCanonicalToImageTransform(Point2D[] detectedCorners,
                                                      double widthIn,
                                                      double heightIn,
                                                      int dpi) {
        int dstW = (int) Math.round(widthIn  * dpi);
        int dstH = (int) Math.round(heightIn * dpi);
        Point2D[] dst = {
            new Point2D(0,    0   ),
            new Point2D(dstW, 0   ),
            new Point2D(dstW, dstH),
            new Point2D(0,    dstH)
        };
        try {
            double[] H = computeHomography(detectedCorners, dst);
            // H maps image-pixels → canonical.  We want canonical → image-pixels = H^-1.
            return invertH(H);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Apply a 3×3 homography to a single point.  Public so callers can
     * transform individual coordinates (e.g. for debug YAML generation).
     *
     * @param H   9-element row-major homography matrix
     * @param x   source x coordinate
     * @param y   source y coordinate
     * @return    {destX, destY}
     */
    public double[] transformPoint(double[] H, double x, double y) {
        return applyHomography(H, x, y);
    }

    /**
     * Compute scale factors (pixels per inch in X and Y) from the detected
     * corners compared to the theoretical dimensions.
     * Used to record in the ScanResult for diagnostic purposes.
     */
    public double[] computeScaleFactors(Point2D[] detectedCorners,
                                         double widthIn, double heightIn) {
        // Top edge width and left edge height in pixels
        double topW  = dist(detectedCorners[0], detectedCorners[1]);
        double botW  = dist(detectedCorners[3], detectedCorners[2]);
        double leftH = dist(detectedCorners[0], detectedCorners[3]);
        double rightH= dist(detectedCorners[1], detectedCorners[2]);

        double avgW = (topW + botW) / 2.0;
        double avgH = (leftH + rightH) / 2.0;

        return new double[]{ avgW / widthIn, avgH / heightIn };
    }


    /**
     * Warp only the pixels corresponding to one indicator box from the source image.
     *
     * Instead of warping the entire content area, this method:
     *   1. Maps the indicator's canonical destination corners back to source pixels (via Hinv)
     *   2. Finds the bounding box of those source pixels
     *   3. Warps only that small patch to the canonical indicator size
     *
     * This is much faster than full-image warping when there are many indicators.
     *
     * @param source    original scanned image
     * @param Hinv      canonical→image homography (9-element row-major)
     * @param dstX      indicator left edge in canonical pixels
     * @param dstY      indicator top edge in canonical pixels
     * @param dstW      indicator width in canonical pixels
     * @param dstH      indicator height in canonical pixels
     * @return          warped patch image of size dstW × dstH
     */
    public BufferedImage warpIndicatorPatch(BufferedImage source, double[] Hinv,
                                             int dstX, int dstY, int dstW, int dstH) {
        int srcW = source.getWidth(), srcH = source.getHeight();
        int[] srcPixels = getRgbPixels(source);
        int[] dstPixels = new int[dstW * dstH];

        for (int y = 0; y < dstH; y++) {
            for (int x = 0; x < dstW; x++) {
                // Map canonical pixel (dstX+x, dstY+y) back to source
                double[] src = applyHomography(Hinv, dstX + x, dstY + y);
                double sx = src[0], sy = src[1];

                int px = (int) sx, py = (int) sy;
                double fx = sx - px, fy = sy - py;

                if (px < 0 || px >= srcW - 1 || py < 0 || py >= srcH - 1) {
                    dstPixels[y * dstW + x] = 0xFFFFFFFF;
                    continue;
                }

                int c00 = srcPixels[py       * srcW + px    ];
                int c10 = srcPixels[py       * srcW + px + 1];
                int c01 = srcPixels[(py + 1) * srcW + px    ];
                int c11 = srcPixels[(py + 1) * srcW + px + 1];

                dstPixels[y * dstW + x] = bilinearInterp(c00, c10, c01, c11, fx, fy);
            }
        }

        BufferedImage patch = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB);
        patch.setRGB(0, 0, dstW, dstH, dstPixels, 0, dstW);
        return patch;
    }

    // ── DLT Homography ─────────────────────────────────────────────────────────

    /**
     * Compute a 3×3 homography using the Direct Linear Transform.
     * src[i] maps to dst[i] for i in 0..3 (need exactly 4 point pairs).
     * Returns the 9-element row-major H matrix.
     */
    private double[] computeHomography(Point2D[] src, Point2D[] dst) {
        // Build the 8×9 matrix A where each point pair gives 2 rows
        double[][] A = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double sx = src[i].x(), sy = src[i].y();
            double dx = dst[i].x(), dy = dst[i].y();
            // Row 2i
            A[2*i][0] = sx; A[2*i][1] = sy; A[2*i][2] = 1;
            A[2*i][3] = 0;  A[2*i][4] = 0;  A[2*i][5] = 0;
            A[2*i][6] = -dx * sx; A[2*i][7] = -dx * sy; A[2*i][8] = -dx;
            // Row 2i+1
            A[2*i+1][0] = 0; A[2*i+1][1] = 0; A[2*i+1][2] = 0;
            A[2*i+1][3] = sx; A[2*i+1][4] = sy; A[2*i+1][5] = 1;
            A[2*i+1][6] = -dy * sx; A[2*i+1][7] = -dy * sy; A[2*i+1][8] = -dy;
        }
        // Solve via SVD (simplified: use Gaussian elimination on 8×8 → h9=1)
        // Scale so last element = 1
        double[] h = gaussianElimination(A);
        // Append h9 = 1
        double[] H = new double[9];
        System.arraycopy(h, 0, H, 0, 8);
        H[8] = 1.0;
        return H;
    }

    private double[] gaussianElimination(double[][] A) {
        int n = 8;
        // Augment with RHS from the 9th column negated (h9 = 1 substituted)
        double[][] M = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) M[i][j] = A[i][j];
            M[i][n] = -A[i][8]; // RHS = -h9 * col8 where h9=1
        }
        // Forward elimination with partial pivoting
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++)
                if (Math.abs(M[row][col]) > Math.abs(M[pivot][col])) pivot = row;
            double[] tmp = M[col]; M[col] = M[pivot]; M[pivot] = tmp;

            double d = M[col][col];
            if (Math.abs(d) < 1e-12) throw new ArithmeticException("Singular matrix");
            for (int j = col; j <= n; j++) M[col][j] /= d;

            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                double f = M[row][col];
                for (int j = col; j <= n; j++) M[row][j] -= f * M[col][j];
            }
        }
        double[] h = new double[n];
        for (int i = 0; i < n; i++) h[i] = M[i][n];
        return h;
    }

    private double[] invertH(double[] H) {
        // 3×3 matrix inversion via cofactors
        double a = H[0], b = H[1], c = H[2];
        double d = H[3], e = H[4], f = H[5];
        double g = H[6], h = H[7], k = H[8];
        double det = a*(e*k - f*h) - b*(d*k - f*g) + c*(d*h - e*g);
        if (Math.abs(det) < 1e-12) return null;
        double inv = 1.0 / det;
        return new double[]{
            (e*k - f*h)*inv,  (c*h - b*k)*inv,  (b*f - c*e)*inv,
            (f*g - d*k)*inv,  (a*k - c*g)*inv,  (c*d - a*f)*inv,
            (d*h - e*g)*inv,  (b*g - a*h)*inv,  (a*e - b*d)*inv
        };
    }

    private double[] applyHomography(double[] H, double x, double y) {
        double w_ = H[6]*x + H[7]*y + H[8];
        return new double[]{ (H[0]*x + H[1]*y + H[2])/w_,
                             (H[3]*x + H[4]*y + H[5])/w_ };
    }

    // ── Pixel helpers ──────────────────────────────────────────────────────────

    private int bilinearInterp(int c00, int c10, int c01, int c11, double fx, double fy) {
        int r = clamp((int)((r(c00)*(1-fx)*(1-fy) + r(c10)*fx*(1-fy)
                           + r(c01)*(1-fx)*fy      + r(c11)*fx*fy)));
        int g = clamp((int)((g(c00)*(1-fx)*(1-fy) + g(c10)*fx*(1-fy)
                           + g(c01)*(1-fx)*fy      + g(c11)*fx*fy)));
        int bv= clamp((int)((b(c00)*(1-fx)*(1-fy) + b(c10)*fx*(1-fy)
                           + b(c01)*(1-fx)*fy      + b(c11)*fx*fy)));
        return (0xFF << 24) | (r << 16) | (g << 8) | bv;
    }

    private int r(int rgb) { return (rgb >> 16) & 0xFF; }
    private int g(int rgb) { return (rgb >> 8)  & 0xFF; }
    private int b(int rgb) { return  rgb         & 0xFF; }
    private int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private int[] getRgbPixels(BufferedImage img) {
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    private BufferedImage scaleFallback(BufferedImage src, int dstW, int dstH) {
        BufferedImage out = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                           RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, dstW, dstH, null);
        g.dispose();
        return out;
    }

    private double dist(Point2D a, Point2D b) {
        double dx = b.x() - a.x(), dy = b.y() - a.y();
        return Math.sqrt(dx*dx + dy*dy);
    }
}
