/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.scanner.service;

import com.mjtrac.scanner.config.ScannerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the actual scan-and-count path (unlike ScanServiceNotesTest's
 * "true" no-op command, which only exercises note logging): a "command"
 * backend that really writes image files to {output} is the same shape a
 * real naps2/scanimage backend produces, letting this run as a genuine,
 * deterministic, hardware-free clean-slate test of ScanService's counting,
 * monitoring, and batch-log-writing logic — the piece scanner and blScanner
 * both depend on and neither previously had any test coverage for.
 */
class ScanServiceMockScanTest {

    @TempDir
    Path tempDir;

    /**
     * {output} expands to outDir.resolve(prefix) (see ScanService.buildCustomCommand) —
     * a shell command appending digits + ".png" to that produces filenames identical
     * in shape to what naps2/scanimage would write (prefix + sequence number).
     */
    private static ScannerConfig mockConfig(Path tempDir, int imageCount) {
        ScannerConfig config = new ScannerConfig();
        config.backend = "command";
        config.customCommand = "for i in $(seq 1 " + imageCount + "); do "
            + "printf 'fake-png-bytes' > \"{output}$(printf '%04d' $i).png\"; done";
        config.outputDir = tempDir.toString();
        config.batchLogDir = tempDir.toString();
        config.filenamePrefix = "test_";
        config.printFlagPages = false;
        return config;
    }

    private static void awaitCompletion(ScanService service) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (service.getSession().scanning && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(service.getSession().scanning).as("scan finished within 10s").isFalse();
    }

    @Test
    void mockScanWritesAndCountsRealImageFiles() throws Exception {
        ScannerConfig config = mockConfig(tempDir, 5);
        ScanService service = new ScanService(config);

        service.startScan("Precinct 7 test batch");
        awaitCompletion(service);

        assertThat(service.getSession().error).isNull();
        assertThat(service.getSession().complete).isTrue();
        assertThat(service.getSession().imagesScanned).isEqualTo(5);
        // lastFile is only set by the concurrent monitor thread's 500ms polling
        // loop (see ScanService.monitorOutput) — a mock scan this fast can finish
        // and get interrupted before that thread completes even one poll, so
        // unlike imagesScanned (computed independently, after the fact, once the
        // process exits) lastFile isn't a reliable signal here. Not asserted on.

        long pngCount;
        try (var files = Files.list(tempDir)) {
            pngCount = files.filter(p -> p.getFileName().toString().endsWith(".png")).count();
        }
        assertThat(pngCount).as("5 real PNG files actually written to disk").isEqualTo(5);

        // {output} expands to outDir/<filenamePrefix><timestamp>_ (see
        // ScanService.buildCustomCommand + runScan's own prefix construction),
        // so actual filenames are "test_<yyyyMMdd_HHmmss>_0001.png" etc. —
        // match the numbered suffix, not a literal "test_0001.png".
        String log = Files.readString(tempDir.resolve("batch_log.txt"));
        assertThat(log).contains("Images    : 5");
        assertThat(log).contains("Precinct 7 test batch");
        for (int i = 1; i <= 5; i++) {
            assertThat(log).containsPattern("test_\\d{8}_\\d{6}_" + String.format("%04d", i) + "\\.png");
        }
    }

    /**
     * stopScan() only sets session.scanning=false and session.error — it does
     * NOT kill the underlying OS process (runScan() has no code path that
     * checks session.scanning while blocked in proc.waitFor()/reader.readLine()).
     * So a scan already in flight runs to full completion regardless of
     * stopScan() being called — this test exists to pin down that real,
     * possibly-surprising behavior (matching how a real scanner's Stop button
     * can't always instantly abort physical paper already feeding), not to
     * claim the scan gets interrupted partway.
     */
    @Test
    void stopScanFlagsSessionButDoesNotKillTheInFlightProcess() throws Exception {
        ScannerConfig config = mockConfig(tempDir, 10);
        ScanService service = new ScanService(config);

        service.startScan();
        service.stopScan();
        assertThat(service.getSession().scanning).isFalse();
        assertThat(service.getSession().error).isEqualTo("Scan stopped by operator");

        long deadline = System.currentTimeMillis() + 10_000;
        while (!service.getSession().complete && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertThat(service.getSession().complete).isTrue();
        assertThat(service.getSession().imagesScanned)
            .as("the mock scan's own shell loop ran to completion, unaffected by stopScan()")
            .isEqualTo(10);
        // The operator-stop message is NOT overwritten by the (successful)
        // exit-code check in runScan() finally-block, since that branch only
        // fires "if (exitCode != 0 && session.error == null)" — error is
        // already non-null here, so the operator's stop reason is preserved
        // even though every image actually landed.
        assertThat(service.getSession().error).isEqualTo("Scan stopped by operator");
    }
}
