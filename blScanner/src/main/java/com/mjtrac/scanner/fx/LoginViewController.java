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
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/** Mirrors login.html / the removed Spring Security form-login flow. */
@Component
public class LoginViewController {

    private final ScannerUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthContext authContext;
    private final Navigator navigator;
    private final ScannerConfig config;
    private final AuditLogService auditLog;

    @FXML private Label titleLabel;
    @FXML private Label messageLabel;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button signInButton;

    public LoginViewController(ScannerUserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                AuthContext authContext,
                                Navigator navigator,
                                ScannerConfig config,
                                AuditLogService auditLog) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authContext = authContext;
        this.navigator = navigator;
        this.config = config;
        this.auditLog = auditLog;
    }

    @FXML
    private void initialize() {
        titleLabel.setText(config.loginTitle);
        hideMessage();
    }

    @FXML
    private void handleSignIn() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();

        Optional<ScannerUser> match = userRepository.findByUsername(username)
            .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()));

        if (match.isEmpty()) {
            auditLog.log("LOGIN_FAILED", username, null);
            showError("Invalid username or password.");
            passwordField.clear();
            return;
        }

        auditLog.log("LOGIN", username, null);
        authContext.setCurrentUser(match.get());
        try {
            navigator.showMain();
        } catch (IOException e) {
            showError("Could not open the scan screen: " + e.getMessage());
        }
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
