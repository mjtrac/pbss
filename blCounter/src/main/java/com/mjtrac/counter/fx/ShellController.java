/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package com.mjtrac.counter.fx;

import com.mjtrac.counter.service.AuditLogService;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Persistent shell: nav sidebar + swappable center content — same pattern as blBuilder's ShellController. */
@Component
public class ShellController {

    private static final Logger log = LoggerFactory.getLogger(ShellController.class);

    private final AuthContext authContext;
    private final Navigator navigator;
    private final AuditLogService auditLog;

    @FXML private Label titleLabel;
    @FXML private Label breadcrumbLabel;
    @FXML private Label signedInAsLabel;
    @FXML private VBox countingSection;
    @FXML private VBox viewerSection;
    @FXML private VBox adminSection;
    @FXML private StackPane contentArea;

    public ShellController(AuthContext authContext, Navigator navigator, AuditLogService auditLog) {
        this.authContext = authContext;
        this.navigator = navigator;
        this.auditLog = auditLog;
    }

    @FXML
    private void initialize() {
        boolean counterOp = authContext.isCounterOperator();
        countingSection.setVisible(counterOp);
        countingSection.setManaged(counterOp);

        boolean viewer = authContext.isViewer();
        viewerSection.setVisible(viewer);
        viewerSection.setManaged(viewer);

        boolean admin = authContext.isAdmin();
        adminSection.setVisible(admin);
        adminSection.setManaged(admin);

        if (authContext.getCurrentUser() != null) {
            signedInAsLabel.setText("Signed in as " + authContext.getCurrentUser().getUsername());
        }
    }

    public void setContent(Node node) {
        contentArea.getChildren().setAll(node);
    }

    public void setBreadcrumb(String text) {
        breadcrumbLabel.setText(text);
    }

    @FXML private void showDashboard() { go("/fxml/dashboard.fxml", "Dashboard"); }
    @FXML private void showResults()   { go("/fxml/results.fxml", "Results"); }
    @FXML private void showViewer()    { go("/fxml/viewer.fxml", "Ballot Viewer"); }
    @FXML private void showAdmin()     { go("/fxml/admin.fxml", "Users"); }
    @FXML private void showAccount()   { go("/fxml/account.fxml", "Change Password"); }

    @FXML
    private void handleSignOut() {
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

    private void go(String fxmlPath, String breadcrumb) {
        try {
            navigator.showInContent(fxmlPath);
            setBreadcrumb(breadcrumb);
        } catch (IOException e) {
            log.error("Could not open {} ({})", breadcrumb, fxmlPath, e);
            new Alert(Alert.AlertType.ERROR, "Could not open " + breadcrumb + ": " + rootCause(e)).showAndWait();
        }
    }

    /** Walks to the deepest cause — FXMLLoader wraps the real failure in a LoadException whose own message is just the FXML URL. */
    private static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
