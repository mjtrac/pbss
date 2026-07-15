/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.service;

import java.awt.image.BufferedImage;

/**
 * Strategy interface for identifying a ballot image's type, combination,
 * and page number from the scanned image itself.
 *
 * <p>pbss's default implementation ({@link BarcodeReaderService}) reads
 * a QR code or Code-128 barcode printed on the ballot.  Alternative
 * implementations may use OCR, template matching, OMR timing marks,
 * filename parsing, or manual operator selection.
 *
 * <p>The {@code barcodeData} field in the returned {@link BallotIdentification}
 * must match the key used to locate the ballot's YAML layout file via
 * {@link BboxReportLoader#loadForBarcode}.  For pbss ballots this is the
 * pipe-delimited string {@code JurisdictionId|RegionId|PartyId|BallotTypeId|ElectionId|PageNum}.
 * For foreign ballots it may be any string that maps to an existing YAML file
 * — the only requirement is that {@code BboxReportLoader} can find the file.
 *
 * <p>Implementations must be Spring beans (annotated with {@code @Service} or
 * {@code @Component}) so they can be injected into {@link ScannerService}.
 * When multiple implementations are present, qualify with {@code @Primary} or
 * {@code @Qualifier} to select the active one.
 */
public interface BallotIdentifierService {

    /**
     * Attempt to identify the ballot type from the scanned image.
     *
     * <p>Implementations should try multiple strategies internally (e.g.
     * contrast stretch, rotation) before returning a failure result.
     * They must never throw — return a {@link BallotIdentification} with
     * {@code decoded = false} if identification fails.
     *
     * @param image the full scanned ballot image (colour or grayscale)
     * @return identification result; never null
     */
    BallotIdentification identify(BufferedImage image);

    /**
     * Attempt to identify the ballot after a 180° rotation.
     * Called by {@link ScannerService} when the first attempt fails and
     * upside-down detection is active.
     *
     * <p>The default implementation simply calls {@link #identify(BufferedImage)}
     * with a pre-rotated image.  Override if your detection strategy can
     * determine orientation without a full rotation pass.
     *
     * @param rotatedImage image already rotated 180°
     * @return identification result; never null
     */
    default BallotIdentification identifyRotated(BufferedImage rotatedImage) {
        return identify(rotatedImage);
    }

    /**
     * Result returned by {@link #identify}.
     *
     * @param barcodeData  The ballot combination key used to locate the YAML
     *                     layout file.  For pbss ballots:
     *                     {@code JurId|RegId|PartyId|TypeId|ElecId|Page}.
     *                     Never null; "(not decoded)" if identification failed.
     * @param pageNumber   1-based page number within this ballot combination.
     *                     Defaults to 1 if not determinable.
     * @param positionX    X pixel coordinate of the identifier in the original
     *                     image (e.g. barcode centre, OCR text position).
     *                     -1 if unknown or not applicable.
     * @param positionY    Y pixel coordinate of the identifier. -1 if unknown.
     * @param decoded      true if identification succeeded and {@code barcodeData}
     *                     is a valid combination key.
     * @param method       Human-readable name of the method used, e.g.
     *                     "BARCODE_QR", "BARCODE_128", "OCR", "TEMPLATE_MATCH",
     *                     "FILENAME", "MANUAL", "COMPOSITE".
     */
    record BallotIdentification(
        String  barcodeData,
        int     pageNumber,
        double  positionX,
        double  positionY,
        boolean decoded,
        String  method
    ) {
        /** Convenience: returns true if the identifier position is known. */
        public boolean hasPosition() {
            return positionX >= 0 && positionY >= 0;
        }

        /** Factory: a failed identification result. */
        public static BallotIdentification notDecoded() {
            return new BallotIdentification("(not decoded)", 1, -1, -1, false, "NONE");
        }

        /** Factory: a successful result with a known position. */
        public static BallotIdentification of(String data, int page,
                                               double x, double y,
                                               String method) {
            return new BallotIdentification(data, page, x, y, true, method);
        }

        /** Factory: a successful result without a position. */
        public static BallotIdentification of(String data, int page, String method) {
            return new BallotIdentification(data, page, -1, -1, true, method);
        }
    }
}
