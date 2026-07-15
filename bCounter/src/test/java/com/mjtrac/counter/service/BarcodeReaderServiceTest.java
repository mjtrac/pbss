/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@DisplayName("BarcodeReaderService — QR decoding")
class BarcodeReaderServiceTest {

    @Autowired BarcodeReaderService barcodeService;

    private static BufferedImage makeQrImage(String content, int sizePx) throws Exception {
        var writer = new QRCodeWriter();
        var matrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx);
        return MatrixToImageWriter.toBufferedImage(matrix);
    }

    private static BufferedImage ballotImageWithQr(String content,
            int ballotW, int ballotH, int qrSize) throws Exception {
        BufferedImage ballot = new BufferedImage(ballotW, ballotH,
            BufferedImage.TYPE_INT_RGB);
        Graphics2D g = ballot.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, ballotW, ballotH);
        BufferedImage qr = makeQrImage(content, qrSize);
        g.drawImage(qr, ballotW - qrSize - 10, 10, null);
        g.dispose();
        return ballot;
    }

    @Test
    @DisplayName("QR code in top-right quadrant is decoded by decode()")
    void testDecodeQrTopRight() throws Exception {
        String expected = "1|1|1|1|1|1";
        BufferedImage img = ballotImageWithQr(expected, 2550, 3300, 300);
        String decoded = barcodeService.decode(img);
        assertThat(decoded).isEqualTo(expected);
    }

    @Test
    @DisplayName("Blank white image returns null without throwing")
    void testBlankImageReturnsNull() {
        BufferedImage img = new BufferedImage(2550, 3300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 2550, 3300); g.dispose();
        assertThatCode(() -> {
            String result = barcodeService.decode(img);
            assertThat(result).isNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("decodeWithPosition returns non-null array with QR present")
    void testDecodeWithPosition() throws Exception {
        String expected = "2|1|0|1|1|2";
        BufferedImage img = ballotImageWithQr(expected, 2550, 3300, 300);
        double[] pos = barcodeService.decodeWithPosition(img);
        assertThat(pos).isNotNull();
        assertThat(pos.length).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("parseBarcodeData returns map with expected keys for pbss format")
    void testParseBarcodeData() {
        String data = "1|2|3|4|5|6";
        var map = barcodeService.parseBarcodeData(data);
        assertThat(map).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("parsePageNumber returns correct page from pbss barcode")
    void testParsePageNumber() {
        // pbss format: jurisdictionId|regionId|partyId|ballotTypeId|electionId|page
        String data = "1|1|1|1|1|2";
        int page = barcodeService.parsePageNumber(data);
        assertThat(page).isEqualTo(2);
    }

    @Test
    @DisplayName("decode() is stateless — 8 concurrent calls return correct independent results")
    void testDecodeIsStateless() throws Exception {
        int threads = 8;
        var errors  = new CopyOnWriteArrayList<Throwable>();
        var latch   = new CountDownLatch(threads);
        String[] contents = {"1|1|1|1|1|1","2|1|0|1|1|1","3|2|0|1|2|1",
                              "4|1|1|2|1|1","5|2|1|1|1|2","6|1|0|2|1|1",
                              "7|3|1|1|1|1","8|1|1|3|1|1"};
        List<BufferedImage> images = new ArrayList<>();
        for (String c : contents)
            images.add(ballotImageWithQr(c, 2550, 3300, 300));

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            Thread.ofVirtual().start(() -> {
                try {
                    String result = barcodeService.decode(images.get(idx));
                    assertThat(result).isEqualTo(contents[idx]);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        assertThat(errors).as("Concurrent decode() errors").isEmpty();
    }
}
