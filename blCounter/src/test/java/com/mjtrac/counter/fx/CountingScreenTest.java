/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 *
 * CountingScreenTest — validation-path coverage for the ported
 * CountingService (folder validation). See CountingPipelineGuiTest for the
 * real happy-path scan, driven against a real generated ballot corpus.
 */
package com.mjtrac.counter.fx;

import com.mjtrac.counter.CounterFxApplication;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
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

import static org.testfx.api.FxAssert.verifyThat;
import static org.testfx.matcher.control.LabeledMatchers.hasText;

@ExtendWith(ApplicationExtension.class)
class CountingScreenTest {

    private static ConfigurableApplicationContext springContext;

    @Start
    void start(Stage stage) throws Exception {
        springContext = new SpringApplicationBuilder(CounterFxApplication.class)
            .web(WebApplicationType.SERVLET)
            .properties("server.port=0")
            .profiles("sqlite")
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
    void missingImageFolderShowsError(FxRobot robot) {
        robot.clickOn("Start Count");
        verifyThat("#messageLabel", hasText("Image folder is required."));
    }

    @Test
    void nonexistentImageFolderShowsError(FxRobot robot) {
        robot.clickOn("#imageFolderField").write("/definitely/not/a/real/folder");
        robot.clickOn("Start Count");
        verifyThat("#messageLabel", hasText("Image folder not found: /definitely/not/a/real/folder"));
    }
}
