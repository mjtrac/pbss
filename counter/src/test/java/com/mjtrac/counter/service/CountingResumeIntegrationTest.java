/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.service;

import com.mjtrac.counter.repository.BallotImageRepository;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression test for the "restart re-counts already-counted ballots"
 * report: doPersist() only renamed a scanned image to ".counted" — the
 * marker findImagesRecursive() uses to skip it on a later restart — on the
 * path where markings were found. A fully blank ballot (like this fixture's
 * "mostly_blank" scenario) hit an early return before that rename, so a
 * stop/restart would re-queue and re-scan it forever: harmless (the
 * image_path unique constraint blocks a double count) but wasteful, and it
 * meant the restarted session's live progress count no longer reflected
 * genuine remaining work — the exact symptom reported (a much lower
 * mid-session count than the true cumulative total shown in results).
 *
 * Uses its own temp copy of the fixture images (not the shared
 * test-images/ classpath resource CountingServiceIntegrationTest scans) so
 * this test's renames-to-.counted don't interfere with that test's fixtures.
 */
@SpringBootTest(classes = CountingResumeIntegrationTest.TestConfig.class,
                 webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CountingResumeIntegrationTest {

    @SpringBootApplication(scanBasePackages = "com.mjtrac.counter")
    @EntityScan("com.mjtrac.counter.entity")
    @EnableJpaRepositories("com.mjtrac.counter.repository")
    static class TestConfig {
    }

    @TempDir
    static Path dbDir;

    @DynamicPropertySource
    static void overrideDataDirs(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("test.db"));
        registry.add("data.dir", () -> dbDir.resolve("pbss_data").toString());
        registry.add("scanner.scribble-outline-dir", () -> dbDir.resolve("pbss_data/scribbles").toString());
    }

    @Autowired
    private CountingService countingService;

    @Autowired
    private BallotImageRepository ballotImageRepository;

    @Test
    void restartAfterCompletionFindsNothingLeftToScan(@TempDir Path imagesDir) throws Exception {
        copyFixture("ballot_1_1_1_1_1_1.yaml", imagesDir);
        copyFixture("ballot_1_1_1_1_1_1__mostly_blank__clean__c01.png", imagesDir);

        countingService.startNewSession(
            imagesDir.toAbsolutePath().toString(), imagesDir.toAbsolutePath().toString(),
            128, 8.0, 150, false, "", 8.5);
        countingService.startScan("test");
        waitForScanToFinish();
        countingService.finish("test");

        assertThat(ballotImageRepository.findAll()).hasSize(1);

        // Every image in the folder must now be renamed to .counted — including
        // this blank-ballot one — or a restart will re-queue and re-scan it.
        try (Stream<Path> files = Files.list(imagesDir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                .as("the blank ballot must be renamed to .counted, same as a marked one")
                .noneMatch(name -> !name.endsWith(".counted") && !name.endsWith(".yaml"));
        }

        // Simulating "Stop" then "Start Counting" again on the same folder:
        // with nothing left un-.counted, this must fail fast with a clear
        // "already counted" message, not silently re-scan the blank ballot
        // (which used to happen) or claim the folder has no images at all.
        assertThatThrownBy(() -> countingService.startNewSession(
            imagesDir.toAbsolutePath().toString(), imagesDir.toAbsolutePath().toString(),
            128, 8.0, 150, false, "", 8.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already been counted");

        // And the original count is untouched — nothing was lost or duplicated.
        assertThat(ballotImageRepository.findAll()).hasSize(1);
    }

    private static void copyFixture(String name, Path destDir) throws Exception {
        URL resource = CountingResumeIntegrationTest.class.getClassLoader()
            .getResource("test-images/" + name);
        assertThat(resource).as("fixture on classpath: " + name).isNotNull();
        Files.copy(new File(resource.getFile()).toPath(), destDir.resolve(name),
            StandardCopyOption.REPLACE_EXISTING);
    }

    private void waitForScanToFinish() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (countingService.getSession().scanning && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertThat(countingService.getSession().scanning).as("scan finished within 60s").isFalse();
        assertThat(countingService.getSession().scanError).isNull();
    }
}
