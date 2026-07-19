/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 *
 * CountingPipelineGuiTest — the real happy-path scan CountingScreenTest left
 * as a follow-up scope note. Drives the actual Counting screen via TestFX
 * robot clicks (not by calling CountingService directly) against a real
 * marked-and-distorted ballot corpus produced offline by
 * test-harness/mark_ballots.py + distort_ballots.py, using a real ballot PDF
 * layout already exported to ~/bBuilder_ballots.
 *
 * Everything reads/writes under test-harness/desktop_pipeline/ or a temp
 * dir — spring.datasource.url is overridden to a dedicated SQLite file
 * there, never ~/pbss_data/db/counter_results.db. See
 * test-harness/README-desktop.md for how to generate the image corpus this
 * test expects, and how to run verify_results.py against the results DB it
 * produces afterward.
 */
package com.mjtrac.counter.fx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mjtrac.counter.CounterFxApplication;
import com.mjtrac.counter.service.CountingService;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith(ApplicationExtension.class)
class CountingPipelineGuiTest {

    private static ConfigurableApplicationContext springContext;

    // blCounter/ is the Maven working dir during `mvn test`; the harness
    // lives alongside it as a sibling checkout dir.
    private static final Path PIPELINE_DIR =
        new File("../test-harness/desktop_pipeline").getAbsoluteFile().toPath().normalize();
    private static final Path IMAGES_DIR = PIPELINE_DIR.resolve("images/marked_ballots");
    private static final Path LAYOUT_DIR =
        Path.of(System.getProperty("user.home"), "bBuilder_ballots");
    private static final Path RESULTS_DIR = PIPELINE_DIR.resolve("blcounter_results");

    @Start
    void start(Stage stage) throws Exception {
        Files.createDirectories(RESULTS_DIR);

        springContext = new SpringApplicationBuilder(CounterFxApplication.class)
            .web(WebApplicationType.SERVLET)
            .properties(
                "server.port=0",
                "gui.pipeline.resultsDir=" + RESULTS_DIR)
            .profiles("guipipeline")
            .run();

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/counting.fxml"));
        loader.setControllerFactory(springContext::getBean);
        Parent root = loader.load();
        stage.setScene(new Scene(root, 900, 700));
        stage.show();
    }

    @AfterAll
    static void closeContext() {
        if (springContext != null) springContext.close();
    }

    @Test
    void fullScanCountsRealDistortedBallots(FxRobot robot) throws Exception {
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

        robot.clickOn("#imageFolderField").write(IMAGES_DIR.toString());
        robot.clickOn("#reportFolderField").write(LAYOUT_DIR.toString());
        robot.interact(() -> {
            @SuppressWarnings("unchecked")
            Spinner<Integer> dpiSpinner = (Spinner<Integer>) robot.lookup("#dpiSpinner").query();
            dpiSpinner.getValueFactory().setValue(150);
        });

        robot.clickOn("Start Count");
        WaitForAsyncUtils.waitForFxEvents();

        // Fail fast with the actual error text if handleStart() rejected the
        // folders (e.g. a robot.write() that didn't fully land before the
        // click, under system load) — the alternative is a silent hang until
        // the full timeout below, since on that path #statusBox never leaves
        // "status-idle" and CountingService.session.isStarted() stays false.
        CountingService countingServiceEarlyCheck = springContext.getBean(CountingService.class);
        if (!countingServiceEarlyCheck.getSession().isStarted()) {
            Label messageLabel = robot.lookup("#messageLabel").queryAs(Label.class);
            throw new AssertionError("Start Count did not start a scan — session never entered "
                + "the started state. #messageLabel text: \"" + messageLabel.getText() + "\" "
                + "(imageFolderField=\"" + robot.lookup("#imageFolderField").queryTextInputControl().getText()
                + "\", reportFolderField=\"" + robot.lookup("#reportFolderField").queryTextInputControl().getText() + "\")");
        }

        WaitForAsyncUtils.waitFor(1200, TimeUnit.SECONDS, () -> {
            Label statusBox = robot.lookup("#statusBox").queryAs(Label.class);
            return statusBox.getStyleClass().contains("status-done")
                || statusBox.getStyleClass().contains("status-errored");
        });

        CountingService countingService = springContext.getBean(CountingService.class);
        assertThat(countingService.getSession().scanError)
            .as("scan should complete without an engine error")
            .isNull();
        assertThat(countingService.getSession().totalImages())
            .as("all generated images should have been enumerated")
            .isEqualTo(expectedImages);

        robot.clickOn("Finish & Save Results");
        WaitForAsyncUtils.waitForFxEvents();

        File resultsReport = RESULTS_DIR.resolve("results_report.html").toFile();
        assertThat(resultsReport)
            .as("Finish & Save Results should write a results report — "
                + "run test-harness/verify_results.py --db "
                + RESULTS_DIR.resolve("counter_results.db")
                + " --gt " + groundTruthFile + " to compare tallies against ground truth")
            .exists();
    }
}
