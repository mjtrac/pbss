/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Scanner Driver — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package com.mjtrac.scanner.fx;

import com.mjtrac.scanner.config.ScannerConfig;
import com.mjtrac.scanner.service.AuditLogService;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Mirrors config.html / ConfigController. Admin-only. Saving writes straight
 * onto the shared ScannerConfig bean — same in-memory-only semantics as the
 * web version (nothing is persisted back to application.properties).
 */
@Component
public class ConfigViewController {

    private final ScannerConfig config;
    private final Navigator navigator;
    private final AuthContext authContext;
    private final AuditLogService auditLog;

    @FXML private Label navTitleLabel;
    @FXML private Label messageLabel;
    @FXML private ComboBox<String> backendCombo;
    @FXML private TextField naps2ProfileField;
    @FXML private TextField naps2DeviceField;
    @FXML private TextField naps2PathField;
    @FXML private TextField scanimagePathField;
    @FXML private TextField customCommandField;
    @FXML private TextField outputDirField;
    @FXML private ComboBox<String> sourceCombo;
    @FXML private Spinner<Integer> dpiSpinner;
    @FXML private CheckBox duplexCheck;
    @FXML private TextField filenamePrefixField;

    public ConfigViewController(ScannerConfig config, Navigator navigator, AuthContext authContext,
                                 AuditLogService auditLog) {
        this.config = config;
        this.navigator = navigator;
        this.authContext = authContext;
        this.auditLog = auditLog;
    }

    @FXML
    private void initialize() {
        if (!authContext.isAdministrator()) {
            navigateOrAlert(navigator::showMain);
            return;
        }
        navTitleLabel.setText(config.loginTitle);
        backendCombo.setItems(javafx.collections.FXCollections.observableArrayList(
            List.of("naps2", "scanimage", "command")));
        sourceCombo.setItems(javafx.collections.FXCollections.observableArrayList(
            List.of("feeder", "duplex", "glass")));
        dpiSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(150, 600, 300, 50));

        hideMessage();
        loadFromConfig();
    }

    private void loadFromConfig() {
        backendCombo.setValue(config.backend);
        naps2ProfileField.setText(nullToEmpty(config.naps2Profile));
        naps2DeviceField.setText(nullToEmpty(config.naps2Device));
        naps2PathField.setText(nullToEmpty(config.naps2Path));
        scanimagePathField.setText(nullToEmpty(config.scanimagePath));
        customCommandField.setText(nullToEmpty(config.customCommand));
        outputDirField.setText(nullToEmpty(config.outputDir));
        sourceCombo.setValue(config.source);
        dpiSpinner.getValueFactory().setValue(config.dpi);
        duplexCheck.setSelected(config.duplex);
        filenamePrefixField.setText(nullToEmpty(config.filenamePrefix));
    }

    @FXML
    private void handleSave() {
        config.backend = backendCombo.getValue();
        config.naps2Profile = naps2ProfileField.getText();
        config.naps2Device = naps2DeviceField.getText();
        config.naps2Path = naps2PathField.getText();
        config.scanimagePath = scanimagePathField.getText();
        config.customCommand = customCommandField.getText();
        config.outputDir = outputDirField.getText();
        config.source = sourceCombo.getValue();
        dpiSpinner.commitValue();
        config.dpi = dpiSpinner.getValue();
        config.duplex = duplexCheck.isSelected();
        config.filenamePrefix = filenamePrefixField.getText();

        showMessage("Configuration saved.");
    }

    @FXML
    private void handleOpenMain() {
        navigateOrAlert(navigator::showMain);
    }

    @FXML
    private void handleOpenUsers() {
        navigateOrAlert(navigator::showUsers);
    }

    @FXML
    private void handleSignOut() {
        if (authContext.getCurrentUser() != null) {
            auditLog.log("LOGOUT", authContext.getCurrentUser().getUsername(), null);
        }
        authContext.clear();
        navigateOrAlert(navigator::showLogin);
    }

    private interface NavAction {
        void run() throws IOException;
    }

    private void navigateOrAlert(NavAction action) {
        try {
            action.run();
        } catch (IOException e) {
            new Alert(Alert.AlertType.ERROR, "Navigation failed: " + e.getMessage()).showAndWait();
        }
    }

    private void showMessage(String text) {
        messageLabel.setText(text);
        messageLabel.setVisible(true);
        messageLabel.setManaged(true);
    }

    private void hideMessage() {
        messageLabel.setVisible(false);
        messageLabel.setManaged(false);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
