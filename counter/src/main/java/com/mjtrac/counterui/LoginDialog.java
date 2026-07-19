/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counterui;

import com.mjtrac.counter.entity.CounterUser;
import com.mjtrac.counter.repository.CounterUserRepository;
import com.mjtrac.counter.service.AuditLogService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.util.Optional;

/**
 * Modal login screen — same CounterUser accounts blCounter/viewer use,
 * requiring COUNTER_OPERATOR or ADMIN access (not a VIEWER-only account).
 * Every attempt, successful or not, is written to AuditLogService — the
 * whole point of requiring login here in the first place.
 */
@Component
class LoginDialog {

    private final CounterUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthContext authContext;
    private final AuditLogService auditLog;

    LoginDialog(CounterUserRepository userRepository, PasswordEncoder passwordEncoder,
                AuthContext authContext, AuditLogService auditLog) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authContext = authContext;
        this.auditLog = auditLog;
    }

    /** Blocks (modal) until sign-in succeeds or the dialog is closed. Returns true iff signed in. */
    boolean showAndAuthenticate(Frame owner) {
        JDialog dialog = new JDialog(owner, "pbss Ballot Counter — Sign In", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JTextField usernameField = new JTextField(18);
        JPasswordField passwordField = new JPasswordField(18);
        JLabel errorLabel = new JLabel(" ");
        errorLabel.setForeground(new Color(0xdc, 0x26, 0x26));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST;
        form.add(new JLabel("pbss Ballot Counter"), c);
        c.gridy++; c.gridwidth = 2;
        form.add(errorLabel, c);
        c.gridwidth = 1;

        c.gridy++; c.gridx = 0;
        form.add(new JLabel("Username:"), c);
        c.gridx = 1;
        form.add(usernameField, c);

        c.gridy++; c.gridx = 0;
        form.add(new JLabel("Password:"), c);
        c.gridx = 1;
        form.add(passwordField, c);

        JButton signIn = new JButton("Sign In");
        c.gridy++; c.gridx = 1; c.anchor = GridBagConstraints.EAST;
        form.add(signIn, c);

        Runnable attempt = () -> {
            String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
            char[] pw = passwordField.getPassword();
            String password = new String(pw);
            java.util.Arrays.fill(pw, ' ');

            Optional<CounterUser> match = userRepository.findByUsername(username)
                .filter(CounterUser::isEnabled)
                .filter(u -> u.getRoles().contains(CounterUser.Role.COUNTER_OPERATOR)
                          || u.getRoles().contains(CounterUser.Role.ADMIN))
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()));

            if (match.isEmpty()) {
                auditLog.log("LOGIN_FAILED", username, null);
                errorLabel.setText("Invalid username/password, or account lacks Counter Operator access.");
                passwordField.setText("");
                return;
            }
            auditLog.log("LOGIN", username, null);
            authContext.setCurrentUser(match.get());
            dialog.dispose();
        };

        signIn.addActionListener(e -> attempt.run());
        passwordField.addActionListener(e -> attempt.run());

        dialog.getContentPane().add(form);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true); // blocks until dispose()

        return authContext.isSignedIn();
    }
}
