/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.service;

import com.mjtrac.counter.model.ScanSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real, end-to-end verification that the counting engine works correctly
 * through this module's own wiring (a copy of blCounter's CountingService
 * and its dependencies) — not just that it compiles/starts. Runs a full
 * scan against real ballot images and a real layout YAML (copied from an
 * earlier test-harness run), against an isolated temp SQLite DB and
 * reports directory so it never touches ~/pbss_data.
 *
 * Uses a test-only @SpringBootApplication scanning only com.mjtrac.counter
 * (not com.mjtrac.counterui) — reusing CounterApp itself would also
 * component-scan MainFrame, constructing a real JFrame as a side effect of
 * building the test context.
 */
@SpringBootTest(classes = CountingServiceIntegrationTest.TestConfig.class,
                 webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CountingServiceIntegrationTest {

    @SpringBootApplication(scanBasePackages = "com.mjtrac.counter")
    @EntityScan("com.mjtrac.counter.entity")
    @EnableJpaRepositories("com.mjtrac.counter.repository")
    static class TestConfig {
    }

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideDataDirs(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
        registry.add("data.dir", () -> tempDir.resolve("pbss_data").toString());
        registry.add("scanner.scribble-outline-dir", () -> tempDir.resolve("pbss_data/scribbles").toString());
    }

    @Autowired
    private CountingService countingService;

    @Test
    void fullScanProducesResultsReport() throws Exception {
        URL resource = getClass().getClassLoader().getResource("test-images/ballot_1_1_1_1_1_1.yaml");
        assertThat(resource).as("test-images resource on classpath").isNotNull();
        File testImagesDir = new File(resource.getFile()).getParentFile();

        countingService.startNewSession(
            testImagesDir.getAbsolutePath(), testImagesDir.getAbsolutePath(),
            128, 8.0, 200, false, "", 8.5);

        countingService.startScan("test");

        long deadline = System.currentTimeMillis() + 60_000;
        while (countingService.getSession().scanning && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertThat(countingService.getSession().scanning).as("scan finished within 60s").isFalse();

        ScanSession session = countingService.getSession();
        assertThat(session.scanError).isNull();
        assertThat(session.processed()).isEqualTo(4);

        countingService.finish("test");

        File resultsReport = new File(countingService.getReportOutputDir(), "results_report.html");
        assertThat(resultsReport).exists();
    }
}
