/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 *
 * CountingPipelineGuiTest — the AssertJ-Swing counterpart to blCounter's
 * TestFX test of the same name. Drives the real MainFrame (Start Counting
 * button, folder fields) via robot clicks against a real marked-and-
 * distorted ballot corpus produced offline by test-harness's
 * mark_ballots.py + distort_ballots.py, using a real ballot PDF layout
 * already exported to ~/bBuilder_ballots.
 *
 * Everything reads/writes under test-harness/desktop_pipeline/ — the
 * "guipipeline" Spring profile (src/test/resources/application-guipipeline
 * .properties) overrides spring.datasource.url to a dedicated SQLite file
 * there, never ~/pbss_data/db/counter_results.db. See
 * test-harness/README-desktop.md for how to generate the image corpus this
 * test expects, and how to run verify_results.py against the results DB it
 * produces afterward.
 */
package com.mjtrac.counterui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mjtrac.counter.service.CountingService;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.exception.ActionFailedException;
import org.assertj.swing.fixture.FrameFixture;
import org.assertj.swing.timing.Condition;
import org.assertj.swing.timing.Pause;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.swing.timing.Timeout.timeout;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CountingPipelineGuiTest {

    // counter/ is the Maven working dir during `mvn test`; the harness lives
    // alongside it as a sibling checkout dir.
    private static final Path PIPELINE_DIR =
        new File("../test-harness/desktop_pipeline").getAbsoluteFile().toPath().normalize();
    private static final Path IMAGES_DIR = PIPELINE_DIR.resolve("images/marked_ballots");
    private static final Path LAYOUT_DIR =
        Path.of(System.getProperty("user.home"), "bBuilder_ballots");
    private static final Path RESULTS_DIR = PIPELINE_DIR.resolve("counter_results");

    private ConfigurableApplicationContext springContext;
    private FrameFixture window;

    @AfterEach
    void tearDown() {
        if (window != null) window.cleanUp();
        if (springContext != null) springContext.close();
    }

    @Test
    void fullScanCountsRealDistortedBallots() throws Exception {
        assumeTrue(Files.isDirectory(IMAGES_DIR),
            "Image corpus not found at " + IMAGES_DIR + " — run test-harness's "
                + "mark_ballots.py + distort_ballots.py first (see README-desktop.md).");
        assumeTrue(Files.isDirectory(LAYOUT_DIR),
            "Ballot layout dir not found at " + LAYOUT_DIR + " — need a real "
                + "bBuilder-generated ballot_1_1_1_1_1_1.pdf/.yaml pair there.");

        Path groundTruthFile = PIPELINE_DIR.resolve("images/ground_truth_all.json");
        assumeTrue(Files.exists(groundTruthFile), "ground_truth_all.json not found — run distort_ballots.py first.");
        JsonNode groundTruth = new ObjectMapper().readTree(groundTruthFile.toFile());
        int expectedImages = groundTruth.get("total_images").asInt();

        Files.createDirectories(RESULTS_DIR);

        springContext = new SpringApplicationBuilder(CounterApp.class)
            .web(WebApplicationType.NONE)
            .headless(false)
            .properties("gui.pipeline.resultsDir=" + RESULTS_DIR)
            .profiles("guipipeline")
            .run();

        // The Spring context eagerly constructs MainFrame (and all its child
        // components) as part of .run(), before any robot exists — a robot
        // with a *new* AWT hierarchy only indexes components added after it
        // starts listening, so it would find the frame but none of its
        // pre-built children. The *current* hierarchy walks the live AWT
        // window list instead, correctly picking up an already-built frame.
        MainFrame mainFrame = GuiActionRunner.execute(() -> springContext.getBean(MainFrame.class));
        window = new FrameFixture(BasicRobot.robotWithCurrentAwtHierarchy(), mainFrame);
        window.show();
        window.focus();

        try {
            window.textBox("imageFolderField").deleteText().enterText(IMAGES_DIR.toString());
            window.textBox("reportFolderField").deleteText().enterText(LAYOUT_DIR.toString());
            window.button("startButton").click();
        } catch (ActionFailedException ex) {
            // java.awt.Robot's synthetic keyboard/mouse events need real OS
            // input focus, which on macOS requires the launching process
            // (Terminal/iTerm/whatever spawned this JVM) to hold the
            // Accessibility permission — a sandboxed/background process
            // commonly doesn't have it, and there's no code-level fix (this
            // is exactly why blCounter's JavaFX equivalent uses Monocle's
            // off-screen headless platform instead of real windows — Swing
            // has no such headless backend). Confirmed via a standalone
            // java.awt.Robot probe: screen capture worked, mouseMove()
            // silently no-opped. Skip cleanly rather than fail on an
            // environment gap, or hang for minutes retrying focus gain.
            assumeTrue(false,
                "AssertJ-Swing could not gain real OS input focus in this environment. "
                    + "Grant Accessibility permission to whatever launched this JVM "
                    + "(macOS: System Settings -> Privacy & Security -> Accessibility "
                    + "-> add Terminal/iTerm, then restart it) and re-run, or run this "
                    + "test under Linux CI with Xvfb (not subject to this restriction). "
                    + "Original error: " + ex.getMessage());
        }

        // Fail fast with the actual error text if handleStart() rejected the
        // folders — most commonly because the corpus is already fully
        // counted from a prior run (findImagesRecursive() skips already-
        // .counted files, so a stale corpus makes startNewSession() throw
        // "already been counted" immediately). The alternative is a silent
        // 20-minute hang below: on that path openResultsButton never gets
        // enabled and the session never starts, so Pause.pause() just times
        // out with a generic message that never surfaces the real cause.
        CountingService countingServiceEarlyCheck = springContext.getBean(CountingService.class);
        if (!countingServiceEarlyCheck.getSession().isStarted()) {
            throw new AssertionError("Start Counting did not start a scan — session never entered "
                + "the started state. messageLabel text: \"" + window.label("messageLabel").text() + "\" "
                + "(imageFolderField=\"" + window.textBox("imageFolderField").text()
                + "\", reportFolderField=\"" + window.textBox("reportFolderField").text() + "\") "
                + "— if the message mentions ballots already being counted, regenerate the corpus: "
                + "see test-harness/README-desktop.md.");
        }

        // MainFrame auto-runs finish() as soon as scanning completes (no
        // separate Finish button, unlike blCounter's fuller screen) and only
        // enables "Open Results Folder" once that finish() SwingWorker's
        // done() callback returns — the real end-to-end completion signal,
        // covering both the scan loop and the report/DB write.
        Pause.pause(new Condition("scan to finish and results to be written") {
            @Override public boolean test() {
                return window.button("openResultsButton").target().isEnabled();
            }
        }, timeout(1_200_000));

        CountingService countingService = springContext.getBean(CountingService.class);
        assertThat(countingService.getSession().scanError)
            .as("scan should complete without an engine error")
            .isNull();
        assertThat(countingService.getSession().totalImages())
            .as("all generated images should have been enumerated")
            .isEqualTo(expectedImages);

        File resultsReport = RESULTS_DIR.resolve("results_report.html").toFile();
        assertThat(resultsReport)
            .as("finish() should write a results report — run "
                + "test-harness/verify_results.py --db " + RESULTS_DIR.resolve("counter_results.db")
                + " --gt " + groundTruthFile + " to compare tallies against ground truth")
            .exists();
    }
}
