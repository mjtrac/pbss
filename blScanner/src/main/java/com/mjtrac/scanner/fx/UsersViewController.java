/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Scanner Driver — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package com.mjtrac.scanner.fx;

import com.mjtrac.scanner.config.ScannerConfig;
import com.mjtrac.scanner.entity.ScannerUser;
import com.mjtrac.scanner.repository.ScannerUserRepository;
import com.mjtrac.scanner.service.AuditLogService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Mirrors users.html / UsersController. Admin-only. */
@Component
public class UsersViewController {

    private final ScannerUserRepository repo;
    private final PasswordEncoder encoder;
    private final ScannerConfig config;
    private final Navigator navigator;
    private final AuthContext authContext;
    private final AuditLogService auditLog;

    @FXML private Label navTitleLabel;
    @FXML private Label messageLabel;
    @FXML private TableView<ScannerUser> usersTable;
    @FXML private TextField newUsernameField;
    @FXML private PasswordField newPasswordField;
    @FXML private ComboBox<String> newRoleCombo;

    public UsersViewController(ScannerUserRepository repo, PasswordEncoder encoder,
                                ScannerConfig config, Navigator navigator, AuthContext authContext,
                                AuditLogService auditLog) {
        this.repo = repo;
        this.encoder = encoder;
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
        newRoleCombo.setItems(FXCollections.observableArrayList("OPERATOR", "ADMINISTRATOR"));
        newRoleCombo.setValue("OPERATOR");
        hideMessage();
        buildColumns();
        refreshUsers();
    }

    private void buildColumns() {
        TableColumn<ScannerUser, String> usernameCol = new TableColumn<>("Username");
        usernameCol.setCellValueFactory(new PropertyValueFactory<>("username"));
        usernameCol.setPrefWidth(160);

        TableColumn<ScannerUser, String> roleCol = new TableColumn<>("Role");
        roleCol.setCellValueFactory(new PropertyValueFactory<>("role"));
        roleCol.setPrefWidth(120);

        TableColumn<ScannerUser, Void> passwordCol = new TableColumn<>("Change Password");
        passwordCol.setPrefWidth(220);
        passwordCol.setCellFactory(col -> new TableCell<>() {
            private final PasswordField field = new PasswordField();
            private final Button setButton = new Button("Set");
            private final HBox box = new HBox(6, field, setButton);
            {
                field.setPromptText("New password");
                field.setPrefWidth(130);
                setButton.setOnAction(e -> {
                    ScannerUser user = getTableView().getItems().get(getIndex());
                    handleChangePassword(user, field.getText());
                    field.clear();
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : box);
            }
        });

        TableColumn<ScannerUser, Void> deleteCol = new TableColumn<>("Delete");
        deleteCol.setPrefWidth(90);
        deleteCol.setCellFactory(col -> new TableCell<>() {
            private final Button deleteButton = new Button("Delete");
            {
                deleteButton.setOnAction(e -> {
                    ScannerUser user = getTableView().getItems().get(getIndex());
                    handleDeleteUser(user);
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : deleteButton);
            }
        });

        usersTable.getColumns().setAll(List.of(usernameCol, roleCol, passwordCol, deleteCol));
    }

    private void refreshUsers() {
        usersTable.setItems(FXCollections.observableArrayList(repo.findAll()));
    }

    @FXML
    private void handleAddUser() {
        String username = newUsernameField.getText() == null ? "" : newUsernameField.getText().trim();
        String password = newPasswordField.getText() == null ? "" : newPasswordField.getText();
        String role = newRoleCombo.getValue();

        if (username.isEmpty() || password.isEmpty()) {
            showError("Username and password are required.");
            return;
        }
        if (repo.findByUsername(username).isPresent()) {
            showError("Username already exists: " + username);
            return;
        }
        if (!role.equals("ADMINISTRATOR") && !role.equals("OPERATOR")) {
            showError("Invalid role: " + role);
            return;
        }

        repo.save(new ScannerUser(username, encoder.encode(password), role));
        newUsernameField.clear();
        newPasswordField.clear();
        newRoleCombo.setValue("OPERATOR");
        refreshUsers();
        showOk("User " + username + " created.");
    }

    private void handleChangePassword(ScannerUser user, String newPassword) {
        if (newPassword == null || newPassword.isEmpty()) {
            showError("Enter a new password first.");
            return;
        }
        Optional<ScannerUser> found = repo.findById(user.getId());
        found.ifPresent(u -> {
            u.setPasswordHash(encoder.encode(newPassword));
            repo.save(u);
        });
        showOk("Password updated.");
    }

    private void handleDeleteUser(ScannerUser user) {
        if (repo.count() <= 1) {
            showError("Cannot delete the last user.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete user " + user.getUsername() + "?", ButtonType.YES, ButtonType.NO);
        confirm.showAndWait();
        if (confirm.getResult() != ButtonType.YES) {
            return;
        }
        repo.deleteById(user.getId());
        refreshUsers();
        showOk("User deleted.");
    }

    @FXML
    private void handleOpenMain() {
        navigateOrAlert(navigator::showMain);
    }

    @FXML
    private void handleOpenConfig() {
        navigateOrAlert(navigator::showConfig);
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

    private void showOk(String text) {
        messageLabel.getStyleClass().setAll("msg-ok");
        messageLabel.setText(text);
        messageLabel.setVisible(true);
        messageLabel.setManaged(true);
    }

    private void showError(String text) {
        messageLabel.getStyleClass().setAll("msg-error");
        messageLabel.setText(text);
        messageLabel.setVisible(true);
        messageLabel.setManaged(true);
    }

    private void hideMessage() {
        messageLabel.setVisible(false);
        messageLabel.setManaged(false);
    }
}
