/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package com.mjtrac.counter.service;

import com.google.zxing.*;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.common.GlobalHistogramBinarizer;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes QR codes from scanned ballot images using ZXing.
 *
 * <p>pbss prints only a QR code (no linear barcode). Only
 * {@code BarcodeFormat.QR_CODE} is attempted, which avoids false positives
 * from dense ballot text that can confuse linear-barcode detectors.
 *
 * <p><strong>Orientation contract:</strong> {@link ScannerService} always runs
 * corner detection and flips upside-down images <em>before</em> calling
 * {@link #identify}. The QR code is therefore always expected at the
 * top-right of the correctly-oriented image.
 *
 * <p><strong>Thread safety:</strong> This class is a Spring singleton.
 * No mutable instance fields exist — every method operates entirely on
 * local variables and parameters. Concurrent calls from multiple scan
 * worker threads are safe with no synchronisation needed.
 *
 * <p>QR data format: {@code JurId|RegionId|PartyId|TypeId|ElecId|Page}
 */
@Primary
@Service
public class BarcodeReaderService implements BallotIdentifierService {

    private static final Logger log =
        LoggerFactory.getLogger(BarcodeReaderService.class);

    /** Only QR_CODE is attempted — no linear barcode is drawn or expected. */
    private static final Map<DecodeHintType, Object> HINTS = Map.of(
        DecodeHintType.TRY_HARDER, Boolean.TRUE,
        DecodeHintType.POSSIBLE_FORMATS, java.util.List.of(
            BarcodeFormat.QR_CODE)
    );

    // ── BallotIdentifierService implementation ────────────────────────────────

    /**
     * Identifies the ballot QR code from an already-upright image.
     *
     * <p>Search order (all local, no shared state):
     * <ol>
     *   <li>Top-right crop with HybridBinarizer — fastest, covers normal scans
     *   <li>Top-right crop with GlobalHistogramBinarizer — better for even lighting
     *   <li>Full image with HybridBinarizer — handles misaligned scans
     *   <li>Full image with GlobalHistogramBinarizer
     *   <li>Contrast-stretched top-right crop, both binarizers
     *   <li>Adaptive-threshold top-right crop, both binarizers
     * </ol>
     *
     * <p>The top-right crop is right 40% × top 30% of the image — large enough
     * to contain a 1" QR code at any scanner DPI from 200 to 600, while
     * excluding the dense ballot text that can confuse binarization.
     */
    @Override
    public BallotIdentification identify(BufferedImage image) {
        // All variables local — no writes to any instance field.
        BufferedImage tr = cropTopRight(image);

        Result r = tryDecode(tr,    false);
        if (r == null) r = tryDecode(tr,    true);
        if (r == null) r = tryDecode(image, false);
        if (r == null) r = tryDecode(image, true);
        if (r == null) r = tryDecode(contrastStretch(tr),    false);
        if (r == null) r = tryDecode(contrastStretch(tr),    true);
        if (r == null) r = tryDecode(adaptiveThreshold(tr),  false);
        if (r == null) r = tryDecode(adaptiveThreshold(tr),  true);

        if (r != null) {
            String data = r.getText();
            ResultPoint[] pts = r.getResultPoints();
            double cx = -1, cy = -1;
            if (pts != null && pts.length >= 2) {
                double sx = 0, sy = 0;
                for (ResultPoint p : pts) { sx += p.getX(); sy += p.getY(); }
                cx = sx / pts.length;
                cy = sy / pts.length;
            }
            return BallotIdentification.of(data, parsePageNumber(data),
                                           cx, cy, "BARCODE_QR");
        }
        log.info("[BarcodeReaderService] No QR code found in image");
        return BallotIdentification.notDecoded();
    }

    /**
     * Image is already upright when this is called — delegates to identify().
     */
    @Override
    public BallotIdentification identifyRotated(BufferedImage rotatedImage) {
        return identify(rotatedImage);
    }

    // ── Legacy public methods — preserved for ScanController and tests ────────

    /** Returns the decoded QR data string, or null if not found. */
    public String decode(BufferedImage image) {
        BallotIdentification id = identify(image);
        return id.decoded() ? id.barcodeData() : null;
    }

    /** Returns {centreX, centreY} in image pixels, or {-1,-1} if not found. */
    public double[] decodeWithPosition(BufferedImage image) {
        BallotIdentification id = identify(image);
        return new double[]{ id.positionX(), id.positionY() };
    }

    /** Parses the pipe-delimited QR string into named fields. */
    public Map<String, String> parseBarcodeData(String data) {
        Map<String, String> result = new LinkedHashMap<>();
        if (data == null || data.isBlank()) return result;
        String[] parts = data.split("\\|");
        String[] labels = {"jurisdictionId", "regionId", "partyId",
                           "ballotTypeId", "electionId", "page"};
        for (int i = 0; i < labels.length; i++)
            result.put(labels[i], i < parts.length ? parts[i] : "");
        return result;
    }

    /** Extracts the page number from the QR string (last pipe-delimited field). */
    public int parsePageNumber(String data) {
        if (data == null) return 1;
        Map<String, String> fields = parseBarcodeData(data);
        try { return Integer.parseInt(fields.getOrDefault("page", "1")); }
        catch (NumberFormatException e) { return 1; }
    }

    // ── Private helpers — all stateless ──────────────────────────────────────

    /**
     * Crops the top-right search region: right 40% × top 30%.
     * Covers a 1" QR code at the right margin on letter/legal/A4
     * at 200–600 DPI, while excluding dense ballot text.
     * Stateless — returns a sub-image view, no pixel copying.
     */
    private BufferedImage cropTopRight(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int cropW = Math.max(1, w * 40 / 100);
        int cropH = Math.max(1, h * 30 / 100);
        return img.getSubimage(w - cropW, 0, cropW, cropH);
    }

    /**
     * Attempts ZXing QR decode using either HybridBinarizer (useGlobal=false)
     * or GlobalHistogramBinarizer (useGlobal=true).
     * Returns a ZXing Result (with position data), or null if not found.
     * Stateless — all variables local.
     *
     * @param useGlobal false = HybridBinarizer (adaptive, good for uneven lighting)
     *                  true  = GlobalHistogramBinarizer (better for even lighting)
     */
    private Result tryDecode(BufferedImage img, boolean useGlobal) {
        try {
            LuminanceSource src =
                new BufferedImageLuminanceSource(toGrayscale(img));
            BinaryBitmap bmp = new BinaryBitmap(
                useGlobal ? new GlobalHistogramBinarizer(src)
                          : new HybridBinarizer(src));
            return new MultiFormatReader().decode(bmp, HINTS);
        } catch (NotFoundException e) {
            return null;
        } catch (Exception e) {
            log.debug("QR decode attempt failed: {}", e.getMessage());
            return null;
        }
    }

    /** Converts to grayscale. Returns input unchanged if already TYPE_BYTE_GRAY. */
    private BufferedImage toGrayscale(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_BYTE_GRAY) return img;
        BufferedImage gray = new BufferedImage(
            img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return gray;
    }

    /** Linear histogram stretch — improves contrast on faded or light scans. */
    private BufferedImage contrastStretch(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        img = toGrayscale(img);
        int[] pixels = new int[w * h];
        img.getRaster().getSamples(0, 0, w, h, 0, pixels);
        int min = 255, max = 0;
        for (int p : pixels) { if (p < min) min = p; if (p > max) max = p; }
        if (max <= min) return img;
        float scale = 255f / (max - min);
        for (int i = 0; i < pixels.length; i++)
            pixels[i] = Math.round((pixels[i] - min) * scale);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        out.getRaster().setSamples(0, 0, w, h, 0, pixels);
        return out;
    }

    /** Global mean threshold binarisation — handles overexposed scans. */
    private BufferedImage adaptiveThreshold(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        img = toGrayscale(img);
        int[] pixels = new int[w * h];
        img.getRaster().getSamples(0, 0, w, h, 0, pixels);
        long sum = 0;
        for (int p : pixels) sum += p;
        int thresh = (int)(sum / pixels.length);
        for (int i = 0; i < pixels.length; i++)
            pixels[i] = pixels[i] < thresh ? 0 : 255;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        out.getRaster().setSamples(0, 0, w, h, 0, pixels);
        return out;
    }
}
