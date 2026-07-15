/*
 * Copyright (C) 2026 Mitch Trachtenberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.mjtrac.ballot.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.mjtrac.ballot.model.BallotDesignTemplate;
import org.springframework.stereotype.Service;

/**
 * Renders a QR code + Code128 linear barcode onto the PDF canvas using ZXing
 * for matrix generation and iText for rendering.
 *
 * Both codes encode the same pipe-delimited ballot metadata string:
 *   JurisdictionId|RegionId|PartyId|BallotTypeId|ElectionId|Sheet/TotalSheets
 *
 * Having both formats maximises scanner compatibility:
 *   - Code128 for 1D laser scanners
 *   - QR for 2D camera-based scanners
 */
@Service
public class BarcodeService {

    public void drawBarcode(PdfCanvas canvas,
                             String data,
                             BallotDesignTemplate template,
                             float pageWidth,
                             float pageHeight) throws Exception {

        int qrSize = (int) template.getBarcodeHeightPt();

        float qrX = switch (template.getBarcodePosition()) {
            case "TOP_LEFT", "BOTTOM_LEFT" -> template.getMarginLeftPt() + 15f;
            default -> pageWidth - template.getMarginRightPt() - qrSize - 15f;
        };

        float qrY = template.getBarcodePosition().startsWith("TOP")
                    ? pageHeight - template.getMarginTopPt() - qrSize - 5f
                    : template.getMarginBottomPt() + 5f;

        // Draw QR code
        BitMatrix qrMatrix = new MultiFormatWriter()
            .encode(data, BarcodeFormat.QR_CODE, qrSize, qrSize);
        drawBitMatrix(canvas, qrMatrix, qrX, qrY, qrSize, qrSize);

        // Draw Code128 linear barcode beneath the QR code
        int bcWidth  = (int) template.getBarcodeWidthPt();
        int bcHeight = 18;
        BitMatrix bcMatrix = new MultiFormatWriter()
            .encode(data, BarcodeFormat.CODE_128, bcWidth, bcHeight);
        float bcX = qrX - ((bcWidth - qrSize) / 2f);
        drawBitMatrix(canvas, bcMatrix, bcX, qrY - bcHeight - 3f, bcWidth, bcHeight);
    }

    private void drawBitMatrix(PdfCanvas canvas, BitMatrix matrix,
                                float x, float y, int w, int h) {
        float moduleW = (float) w / matrix.getWidth();
        float moduleH = (float) h / matrix.getHeight();

        canvas.setFillColor(ColorConstants.BLACK);
        for (int row = 0; row < matrix.getHeight(); row++) {
            for (int col = 0; col < matrix.getWidth(); col++) {
                if (matrix.get(col, row)) {
                    canvas.rectangle(
                        x + col * moduleW,
                        y + (matrix.getHeight() - row - 1) * moduleH,
                        moduleW, moduleH
                    ).fill();
                }
            }
        }
    }
}
