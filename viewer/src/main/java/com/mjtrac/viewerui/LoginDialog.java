/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewerui;

import com.mjtrac.counter.entity.CounterUser;
import com.mjtrac.counter.repository.CounterUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.util.Optional;

/** Modal login screen gating access with the same CounterUser/VIEWER-role accounts blCounter uses. */
@Component
class LoginDialog {

    private final CounterUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthContext authContext;

    @Value("${app.login-title:pbss Ballot Viewer}")
    private String loginTitle;

    LoginDialog(CounterUserRepository userRepository, PasswordEncoder passwordEncoder, AuthContext authContext) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authContext = authContext;
    }

    /** Blocks (modal) until sign-in succeeds or the dialog is closed. Returns true iff signed in. */
    boolean showAndAuthenticate(Frame owner) {
        JDialog dialog = new JDialog(owner, loginTitle, true);
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
        form.add(new JLabel(loginTitle), c);
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
                .filter(u -> u.getRoles().contains(CounterUser.Role.VIEWER)
                          || u.getRoles().contains(CounterUser.Role.ADMIN))
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()));

            if (match.isEmpty()) {
                errorLabel.setText("Invalid username/password, or account lacks Viewer access.");
                passwordField.setText("");
                return;
            }
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
