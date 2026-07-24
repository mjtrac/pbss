/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Ballot System — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package com.mjtrac.ballot.fx;

import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.model.PrintLog;
import com.mjtrac.ballot.model.User;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.PrintLogRepository;
import com.mjtrac.ballot.repository.UserRepository;
import com.mjtrac.ballot.service.UserService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.StringConverter;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Mirrors admin/dashboard.html + user-form.html + the removed AdminController. Admin-only. */
@Component
public class AdminViewController {

    private static final DateTimeFormatter PRINTED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final UserService userService;
    private final UserRepository userRepo;
    private final PrintLogRepository printLogRepo;
    private final JurisdictionRepository jurisdictionRepo;

    @FXML private Label messageLabel;
    @FXML private TableView<User> userTable;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private ComboBox<Jurisdiction> jurisdictionCombo;
    @FXML private CheckBox adminRoleCheck;
    @FXML private CheckBox dataEntryRoleCheck;
    @FXML private CheckBox printerRoleCheck;
    @FXML private TableView<PrintLog> printLogTable;

    public AdminViewController(UserService userService,
                                UserRepository userRepo,
                                PrintLogRepository printLogRepo,
                                JurisdictionRepository jurisdictionRepo) {
        this.userService = userService;
        this.userRepo = userRepo;
        this.printLogRepo = printLogRepo;
        this.jurisdictionRepo = jurisdictionRepo;
    }

    @FXML
    private void initialize() {
        jurisdictionCombo.setConverter(new StringConverter<>() {
            @Override public String toString(Jurisdiction j) { return j == null ? "" : j.getName(); }
            @Override public Jurisdiction fromString(String s) { return null; }
        });
        jurisdictionCombo.setItems(FXCollections.observableArrayList(jurisdictionRepo.findAll()));
        if (!jurisdictionCombo.getItems().isEmpty()) {
            jurisdictionCombo.getSelectionModel().selectFirst();
        }

        hideMessage();
        buildUserColumns();
        buildPrintLogColumns();
        refresh();
    }

    private void buildUserColumns() {
        TableColumn<User, String> usernameCol = new TableColumn<>("Username");
        usernameCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("username"));
        usernameCol.setPrefWidth(160);

        TableColumn<User, String> rolesCol = new TableColumn<>("Roles");
        rolesCol.setCellValueFactory(row -> new javafx.beans.property.SimpleStringProperty(
            row.getValue().getRoles().toString()));
        rolesCol.setPrefWidth(200);

        TableColumn<User, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(row -> new javafx.beans.property.SimpleStringProperty(
            row.getValue().isEnabled() ? "Enabled" : "Disabled"));
        statusCol.setPrefWidth(90);

        TableColumn<User, Void> toggleCol = new TableColumn<>("Enable/Disable");
        toggleCol.setPrefWidth(120);
        toggleCol.setCellFactory(col -> new TableCell<>() {
            private final Button toggleButton = new Button();
            { toggleButton.setOnAction(e -> handleToggle(getTableView().getItems().get(getIndex()))); }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    User u = getTableView().getItems().get(getIndex());
                    toggleButton.setText(u.isEnabled() ? "Disable" : "Enable");
                    setGraphic(toggleButton);
                }
            }
        });

        userTable.getColumns().setAll(List.of(usernameCol, rolesCol, statusCol, toggleCol));
    }

    private void buildPrintLogColumns() {
        TableColumn<PrintLog, String> whenCol = new TableColumn<>("Printed At");
        whenCol.setCellValueFactory(row -> new javafx.beans.property.SimpleStringProperty(
            row.getValue().getPrintedAt() == null ? "" : row.getValue().getPrintedAt().format(PRINTED_AT_FORMAT)));
        whenCol.setPrefWidth(150);

        TableColumn<PrintLog, String> byCol = new TableColumn<>("Printed By");
        byCol.setCellValueFactory(row -> new javafx.beans.property.SimpleStringProperty(
            row.getValue().getPrintedBy() != null ? row.getValue().getPrintedBy().getUsername() : ""));
        byCol.setPrefWidth(130);

        TableColumn<PrintLog, Number> copiesCol = new TableColumn<>("Copies");
        copiesCol.setCellValueFactory(row -> new javafx.beans.property.SimpleIntegerProperty(row.getValue().getCopies()));
        copiesCol.setPrefWidth(70);

        TableColumn<PrintLog, String> paperCol = new TableColumn<>("Paper Size");
        paperCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("paperSize"));
        paperCol.setPrefWidth(140);

        TableColumn<PrintLog, String> osUserCol = new TableColumn<>("OS User");
        osUserCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("osUsername"));
        osUserCol.setPrefWidth(110);

        TableColumn<PrintLog, String> hostnameCol = new TableColumn<>("Hostname");
        hostnameCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("hostname"));
        hostnameCol.setPrefWidth(140);

        TableColumn<PrintLog, String> serialCol = new TableColumn<>("Machine Serial");
        serialCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("machineSerial"));
        serialCol.setPrefWidth(140);

        printLogTable.getColumns().setAll(
            List.of(whenCol, byCol, osUserCol, hostnameCol, serialCol, copiesCol, paperCol));
    }

    private void refresh() {
        userTable.setItems(FXCollections.observableArrayList(userRepo.findAll()));
        printLogTable.setItems(FXCollections.observableArrayList(printLogRepo.findAllWithValidCombination()));
    }

    @FXML
    private void handleCreateUser() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        Jurisdiction jurisdiction = jurisdictionCombo.getValue();

        Set<User.Role> roles = EnumSet.noneOf(User.Role.class);
        if (adminRoleCheck.isSelected()) roles.add(User.Role.ADMIN);
        if (dataEntryRoleCheck.isSelected()) roles.add(User.Role.DATA_ENTRY);
        if (printerRoleCheck.isSelected()) roles.add(User.Role.PRINTER);

        if (username.isEmpty()) {
            showError("Username is required.");
            return;
        }
        if (password.length() < 12) {
            showError("Password is required and must be at least 12 characters.");
            return;
        }
        if (roles.isEmpty()) {
            showError("Please select at least one role.");
            return;
        }
        if (jurisdiction == null) {
            showError("Please select a jurisdiction.");
            return;
        }

        try {
            userService.createUser(username, password, roles, jurisdiction);
            usernameField.clear();
            passwordField.clear();
            adminRoleCheck.setSelected(false);
            dataEntryRoleCheck.setSelected(false);
            printerRoleCheck.setSelected(false);
            refresh();
            showOk("Created user \"" + username + "\".");
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
        }
    }

    private void handleToggle(User user) {
        userService.setEnabled(user.getId(), !user.isEnabled());
        String verb = user.isEnabled() ? "Disabled" : "Enabled";
        refresh();
        showOk(verb + " user \"" + user.getUsername() + "\".");
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
