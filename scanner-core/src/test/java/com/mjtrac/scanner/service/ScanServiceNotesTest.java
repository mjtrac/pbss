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
 * Verifies the start/end notes logging added on top of the original
 * writeBatchLog() mechanism — no Spring context needed, ScanService only
 * takes a ScannerConfig.
 */
class ScanServiceNotesTest {

    @TempDir
    Path tempDir;

    @Test
    void startNoteIsLoggedImmediatelyWithTimestamp() throws Exception {
        ScannerConfig config = new ScannerConfig();
        config.backend = "command";
        config.customCommand = "true"; // no-op shell command, exits 0 immediately
        config.outputDir = tempDir.toString();
        config.batchLogDir = tempDir.toString();
        config.filenamePrefix = "test_";
        config.printFlagPages = false; // off by default — must not throw/require a printer

        ScanService service = new ScanService(config);
        service.startScan("Precinct 4 morning batch");

        // startScan logs the start note synchronously (before the async scan
        // runs), so no polling/wait is needed to see it.
        Path logFile = tempDir.resolve("batch_log.txt");
        assertThat(logFile).exists();
        String content = Files.readString(logFile);
        assertThat(content).contains("Start note");
        assertThat(content).contains("Precinct 4 morning batch");
        // Human-readable timestamp format: yyyy-MM-dd HH:mm:ss
        assertThat(content).containsPattern("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void endNoteIsLoggedAgainstTheLastBatchId() throws Exception {
        ScannerConfig config = new ScannerConfig();
        config.backend = "command";
        config.customCommand = "true";
        config.outputDir = tempDir.toString();
        config.batchLogDir = tempDir.toString();
        config.filenamePrefix = "test_";
        config.printFlagPages = false;

        ScanService service = new ScanService(config);
        service.startScan("");

        long deadline = System.currentTimeMillis() + 10_000;
        while (service.getSession().scanning && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(service.getSession().complete).isTrue();
        assertThat(service.getSession().lastBatchId).isNotNull();

        service.saveEndNote("Misfeed on sheet 12 — recount by hand");

        String content = Files.readString(tempDir.resolve("batch_log.txt"));
        assertThat(content).contains("End note");
        assertThat(content).contains("batch " + service.getSession().lastBatchId);
        assertThat(content).contains("Misfeed on sheet 12");
    }

    @Test
    void blankNotesAreNotLogged() {
        ScannerConfig config = new ScannerConfig();
        config.backend = "command";
        config.customCommand = "true";
        config.outputDir = tempDir.toString();
        config.batchLogDir = tempDir.toString();
        config.filenamePrefix = "test_";

        ScanService service = new ScanService(config);
        service.saveEndNote("   ");
        assertThat(tempDir.resolve("batch_log.txt")).doesNotExist();
    }

    @Test
    void dpiDuplexCapabilityReflectsBackend() {
        ScannerConfig naps2 = new ScannerConfig();
        naps2.backend = "naps2";
        assertThat(naps2.supportsDpi()).isTrue();
        assertThat(naps2.supportsDuplex()).isTrue();

        ScannerConfig scanimage = new ScannerConfig();
        scanimage.backend = "scanimage";
        assertThat(scanimage.supportsDpi()).isTrue();
        assertThat(scanimage.supportsDuplex()).isFalse();

        ScannerConfig command = new ScannerConfig();
        command.backend = "command";
        assertThat(command.supportsDpi()).isFalse();
        assertThat(command.supportsDuplex()).isFalse();
    }
}
