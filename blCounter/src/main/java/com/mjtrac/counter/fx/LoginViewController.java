/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package com.mjtrac.counter.fx;

import com.mjtrac.counter.entity.CounterUser;
import com.mjtrac.counter.repository.CounterUserRepository;
import com.mjtrac.counter.service.AuditLogService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * Mirrors templates/login.html / the removed LoginController form-login
 * flow — including its audit logging (CounterSecurityConfig's
 * loginSuccessHandler/loginFailureHandler on the web side), which this
 * screen was missing entirely until a user-reported gap: every attempt
 * here now writes LOGIN/LOGIN_FAILED to AuditLogService too.
 */
@Component
public class LoginViewController {

    private final CounterUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthContext authContext;
    private final Navigator navigator;
    private final AuditLogService auditLog;

    @Value("${app.login-title:pbss Ballot Counter}")
    private String loginTitle;

    @FXML private Label titleLabel;
    @FXML private Label messageLabel;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button signInButton;

    public LoginViewController(CounterUserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                AuthContext authContext,
                                Navigator navigator,
                                AuditLogService auditLog) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authContext = authContext;
        this.navigator = navigator;
        this.auditLog = auditLog;
    }

    @FXML
    private void initialize() {
        titleLabel.setText(loginTitle);
        hideMessage();
    }

    @FXML
    private void handleSignIn() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();

        Optional<CounterUser> match = userRepository.findByUsername(username)
            .filter(CounterUser::isEnabled)
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
            navigator.showShell();
        } catch (IOException e) {
            showError("Could not open the dashboard: " + e.getMessage());
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
