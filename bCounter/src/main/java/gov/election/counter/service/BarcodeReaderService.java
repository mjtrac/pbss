/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package gov.election.counter.service;

import com.google.zxing.*;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes QR codes and Code128 barcodes from a BufferedImage using ZXing.
 *
 * Strategy (tried in order until a result is found):
 *   1. Direct ZXing decode of the original image.
 *   2. Contrast-stretched version (CLAHE-equivalent via histogram stretch).
 *   3. Adaptive threshold version.
 *   4. Rotated 180° (handles upside-down scans).
 *
 * The barcode data format is:
 *   JurisdictionId|RegionId|PartyId|BallotTypeId|ElectionId|PageNum
 */
@Service
public class BarcodeReaderService {

    private static final Logger log =
        LoggerFactory.getLogger(BarcodeReaderService.class);

    private static final Map<DecodeHintType, Object> HINTS = Map.of(
        DecodeHintType.TRY_HARDER, Boolean.TRUE,
        DecodeHintType.POSSIBLE_FORMATS, java.util.List.of(
            BarcodeFormat.QR_CODE,
            BarcodeFormat.CODE_128,
            BarcodeFormat.CODE_39)
    );

    /**
     * Attempts to decode the QR or barcode in the image.
     *
     * @param image grayscale or colour BufferedImage
     * @return decoded string, or null if no code found
     */
    /**
     * Decode the barcode and return {data, centreX, centreY} in image pixels.
     * centreX/Y are the detected QR centre; both -1 if not found or no position.
     */
    public double[] decodeWithPosition(BufferedImage image) {
        try {
            LuminanceSource source = new BufferedImageLuminanceSource(toGrayscale(image));
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = new MultiFormatReader().decode(bitmap, HINTS);
            if (result != null) {
                ResultPoint[] pts = result.getResultPoints();
                double cx = -1, cy = -1;
                if (pts != null && pts.length >= 3) {
                    // QR code: average the three finder pattern centres
                    double sx = 0, sy = 0;
                    for (ResultPoint p : pts) { sx += p.getX(); sy += p.getY(); }
                    cx = sx / pts.length;
                    cy = sy / pts.length;
                } else if (pts != null && pts.length == 2) {
                    // Linear barcode: midpoint of the two end points
                    cx = (pts[0].getX() + pts[1].getX()) / 2.0;
                    cy = (pts[0].getY() + pts[1].getY()) / 2.0;
                }
                // Store text in a 1-element array for the caller
                decodedText = result.getText();
                return new double[]{ cx, cy };
            }
        } catch (Exception e) { /* fall through */ }
        return new double[]{ -1, -1 };
    }

    /** Set by decodeWithPosition(); read by ScannerService after calling decode(). */
    private volatile String decodedText = null;

    public String decode(BufferedImage image) {
        // Try original
        String result = tryDecode(image);
        if (result != null) return result;

        // Try contrast-stretched
        result = tryDecode(contrastStretch(image));
        if (result != null) return result;

        // Try adaptive threshold
        result = tryDecode(adaptiveThreshold(image));
        if (result != null) return result;

        // Try 180° rotation (upside-down ballots)
        result = tryDecode(rotate180(image));
        if (result != null) return result;

        log.info("No barcode found in image");
        return null;
    }

    /**
     * Parse the ballot barcode into its component fields.
     * Returns an empty map if data is null or malformed.
     */
    public Map<String, String> parseBarcodeData(String data) {
        Map<String, String> result = new LinkedHashMap<>();
        if (data == null || data.isBlank()) return result;
        String[] parts = data.split("\\|");
        String[] labels = {"jurisdictionId", "regionId", "partyId",
                           "ballotTypeId", "electionId", "page"};
        for (int i = 0; i < labels.length; i++) {
            result.put(labels[i], i < parts.length ? parts[i] : "");
        }
        return result;
    }

    public int parsePageNumber(String data) {
        if (data == null) return 1;
        Map<String, String> fields = parseBarcodeData(data);
        try { return Integer.parseInt(fields.getOrDefault("page", "1")); }
        catch (NumberFormatException e) { return 1; }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private String tryDecode(BufferedImage img) {
        try {
            LuminanceSource source = new BufferedImageLuminanceSource(toGrayscale(img));
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = new MultiFormatReader().decode(bitmap, HINTS);
            return result != null ? result.getText() : null;
        } catch (NotFoundException e) {
            return null;
        } catch (Exception e) {
            log.debug("Decode attempt failed: " + e.getMessage());
            return null;
        }
    }

    private BufferedImage toGrayscale(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_BYTE_GRAY) return img;
        BufferedImage gray = new BufferedImage(
            img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return gray;
    }

    /** Simple linear histogram stretch to improve contrast in faded scans. */
    private BufferedImage contrastStretch(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] pixels = new int[w * h];
        img = toGrayscale(img);
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

    /** Simple global threshold to handle overexposed scans. */
    private BufferedImage adaptiveThreshold(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        img = toGrayscale(img);
        int[] pixels = new int[w * h];
        img.getRaster().getSamples(0, 0, w, h, 0, pixels);

        // Compute mean
        long sum = 0;
        for (int p : pixels) sum += p;
        int thresh = (int)(sum / pixels.length);

        for (int i = 0; i < pixels.length; i++)
            pixels[i] = pixels[i] < thresh ? 0 : 255;

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        out.getRaster().setSamples(0, 0, w, h, 0, pixels);
        return out;
    }

    private BufferedImage rotate180(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, img.getType());
        Graphics2D g = out.createGraphics();
        g.rotate(Math.PI, w / 2.0, h / 2.0);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }
}
