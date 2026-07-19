/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Scanner Driver — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package com.mjtrac.scanner.fx;

import com.mjtrac.scanner.config.ScannerConfig;
import com.mjtrac.scanner.model.ScanSession;
import com.mjtrac.scanner.service.AuditLogService;
import com.mjtrac.scanner.service.ScanService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.util.Duration;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Mirrors index.html + MainController's /scan/start, /scan/stop, /status endpoints.
 * The web version polled /status over HTTP every second; here the Timeline reads
 * ScanService's in-memory session directly since everything runs in one process.
 */
@Component
public class ScanViewController {

    private final ScanService scanService;
    private final ScannerConfig config;
    private final AuthContext authContext;
    private final Navigator navigator;
    private final AuditLogService auditLog;

    @FXML private Label navTitleLabel;
    @FXML private HBox adminLinksBox;
    @FXML private Label statusBox;
    @FXML private Label countLabel;
    @FXML private Label lastFileLabel;
    @FXML private TextArea commentArea;
    @FXML private TextArea endNotesArea;
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private Button saveEndNoteButton;
    @FXML private Button printBatchSheetButton;
    @FXML private Label endNoteMessageLabel;
    @FXML private Label configSummaryLabel;
    @FXML private Hyperlink editConfigLink;

    private Timeline pollTimeline;

    public ScanViewController(ScanService scanService, ScannerConfig config,
                               AuthContext authContext, Navigator navigator, AuditLogService auditLog) {
        this.scanService = scanService;
        this.config = config;
        this.authContext = authContext;
        this.navigator = navigator;
        this.auditLog = auditLog;
    }

    @FXML
    private void initialize() {
        navTitleLabel.setText(config.loginTitle);
        boolean admin = authContext.isAdministrator();
        adminLinksBox.setVisible(admin);
        adminLinksBox.setManaged(admin);
        editConfigLink.setVisible(admin);
        editConfigLink.setManaged(admin);

        refreshConfigSummary();

        if (pollTimeline != null) {
            pollTimeline.stop();
            pollTimeline = null;
        }
        renderSession(scanService.getSession());
    }

    private void refreshConfigSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Backend: ").append(config.backend).append('\n');
        if (config.naps2Profile != null && !config.naps2Profile.isBlank()) {
            sb.append("NAPS2 Profile: ").append(config.naps2Profile).append('\n');
        } else if (config.naps2Device != null && !config.naps2Device.isBlank()) {
            sb.append("NAPS2 Device: ").append(config.naps2Device).append('\n');
        }
        sb.append("Source: ").append(config.source).append('\n');
        sb.append("DPI: ").append(config.dpi).append('\n');
        sb.append("Output folder: ").append(config.outputDir);
        configSummaryLabel.setText(sb.toString());
    }

    @FXML
    private void handleStart() {
        String comment = commentArea.getText() == null ? "" : commentArea.getText().trim();
        try {
            scanService.startScan(comment);
        } catch (IllegalStateException e) {
            new Alert(Alert.AlertType.ERROR, "Error: " + e.getMessage()).showAndWait();
            return;
        }
        commentArea.clear();
        setRunning(true);
        startPolling();
    }

    @FXML
    private void handleStop() {
        scanService.stopScan();
    }

    @FXML
    private void handleSaveEndNote() {
        String note = endNotesArea.getText() == null ? "" : endNotesArea.getText().trim();
        if (note.isEmpty()) return;
        scanService.saveEndNote(note);
        endNotesArea.clear();
        endNoteMessageLabel.setText("End note saved.");
    }

    /** Manual, user-clicked print — shows the system print dialog, unlike scanner-core's automatic flag pages. */
    @FXML
    private void handlePrintBatchSheet() {
        String error = BatchSheetPrinter.print(commentArea.getText(), endNotesArea.getText());
        if (error != null) {
            new Alert(Alert.AlertType.ERROR, error).showAndWait();
        }
    }

    @FXML
    private void handleOpenConfig() {
        try {
            navigator.showConfig();
        } catch (IOException e) {
            new Alert(Alert.AlertType.ERROR, "Could not open configuration: " + e.getMessage()).showAndWait();
        }
    }

    @FXML
    private void handleOpenUsers() {
        try {
            navigator.showUsers();
        } catch (IOException e) {
            new Alert(Alert.AlertType.ERROR, "Could not open users: " + e.getMessage()).showAndWait();
        }
    }

    @FXML
    private void handleSignOut() {
        stopPolling();
        if (authContext.getCurrentUser() != null) {
            auditLog.log("LOGOUT", authContext.getCurrentUser().getUsername(), null);
        }
        authContext.clear();
        try {
            navigator.showLogin();
        } catch (IOException e) {
            new Alert(Alert.AlertType.ERROR, "Could not sign out: " + e.getMessage()).showAndWait();
        }
    }

    private void startPolling() {
        stopPolling();
        pollTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> pollStatus()));
        pollTimeline.setCycleCount(Timeline.INDEFINITE);
        pollTimeline.play();
    }

    private void stopPolling() {
        if (pollTimeline != null) {
            pollTimeline.stop();
            pollTimeline = null;
        }
    }

    private void pollStatus() {
        ScanSession s = scanService.getSession();
        renderSession(s);
        if ((s.error != null && !s.error.isEmpty()) || (s.complete && !s.scanning)) {
            stopPolling();
        }
    }

    private void renderSession(ScanSession s) {
        countLabel.setText("Images scanned: " + s.imagesScanned);
        lastFileLabel.setText(s.lastFile != null ? s.lastFile : "");
        setRunning(s.scanning);

        long elapsedMs = s.startedAt > 0
            ? (s.completedAt > 0 ? s.completedAt - s.startedAt : System.currentTimeMillis() - s.startedAt)
            : 0;
        long elapsedSec = Math.round(elapsedMs / 1000.0);

        if (s.error != null && !s.error.isEmpty()) {
            setStatus("status-errored", "Error: " + s.error);
        } else if (s.complete && !s.scanning) {
            setStatus("status-done", "Complete — " + s.imagesScanned + " image(s) in " + elapsedSec + "s");
        } else if (s.scanning) {
            setStatus("status-running", "Scanning… " + s.imagesScanned + " image(s) — " + elapsedSec + "s");
            if (pollTimeline == null) {
                startPolling();
            }
        } else {
            setStatus("status-idle", "Ready to scan");
        }
    }

    private void setRunning(boolean running) {
        startButton.setDisable(running);
        stopButton.setDisable(!running);
    }

    private void setStatus(String styleClass, String text) {
        statusBox.getStyleClass().setAll("status-box", styleClass);
        statusBox.setText(text);
    }
}
