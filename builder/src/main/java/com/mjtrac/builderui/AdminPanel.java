/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.model.User;
import com.mjtrac.ballot.model.User.Role;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Manages the shared `users` table — accounts created/edited here work
 * for signing into bBuilder/blBuilder too (this app itself has no login).
 */
@Component
class AdminPanel extends SimpleCrudPanel<User> {

    private final UserRepository repo;
    private final JurisdictionRepository jurisdictionRepo;
    private final PasswordEncoder passwordEncoder;

    AdminPanel(UserRepository repo, JurisdictionRepository jurisdictionRepo, PasswordEncoder passwordEncoder) {
        super("Users", new String[]{"ID", "Username", "Roles", "Enabled", "Jurisdiction"},
            u -> new Object[]{u.getId(), u.getUsername(),
                u.getRoles().stream().map(Enum::name).sorted().collect(Collectors.joining(", ")),
                u.isEnabled(),
                u.getJurisdiction() != null ? u.getJurisdiction().getName() : ""});
        this.repo = repo;
        this.jurisdictionRepo = jurisdictionRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override List<User> loadAll() { return repo.findAll(); }

    @Override void save(User entity) { repo.save(entity); }

    @Override void delete(User entity) { repo.deleteById(entity.getId()); }

    @Override JComponent buildForm(User existing, Consumer<User> onSave) {
        User u = existing != null ? existing : new User();

        JTextField usernameField = new JTextField(u.getUsername(), 24);
        JPasswordField passwordField = new JPasswordField(24);
        JLabel passwordHint = new JLabel(existing == null
            ? "Required for a new user." : "Leave blank to keep the current password.");
        JCheckBox enabledCheck = new JCheckBox("Enabled", existing == null || u.isEnabled());
        JCheckBox adminCheck = new JCheckBox("ADMIN", u.getRoles().contains(Role.ADMIN));
        JCheckBox dataEntryCheck = new JCheckBox("DATA_ENTRY", u.getRoles().contains(Role.DATA_ENTRY));
        JCheckBox printerCheck = new JCheckBox("PRINTER", u.getRoles().contains(Role.PRINTER));
        JComboBox<Jurisdiction> jurisdictionCombo = JurisdictionCombo.build(jurisdictionRepo, u.getJurisdiction());
        usernameField.setName("usernameField");
        passwordField.setName("passwordField");
        enabledCheck.setName("enabledCheck");
        adminCheck.setName("adminCheck");
        dataEntryCheck.setName("dataEntryCheck");
        printerCheck.setName("printerCheck");

        JPanel roles = new JPanel();
        roles.setLayout(new BoxLayout(roles, BoxLayout.X_AXIS));
        roles.add(adminCheck);
        roles.add(dataEntryCheck);
        roles.add(printerCheck);

        JPanel grid = fieldGrid();
        addField(grid, 0, "Username:", usernameField);
        addField(grid, 1, "Password:", passwordField);
        addField(grid, 2, "", passwordHint);
        addField(grid, 3, "Roles:", roles);
        addField(grid, 4, "Jurisdiction:", jurisdictionCombo);
        addField(grid, 5, "", enabledCheck);

        return formShell(existing == null ? "New User" : "Edit User", grid,
            () -> {
                u.setUsername(usernameField.getText());
                char[] pw = passwordField.getPassword();
                if (pw.length > 0) {
                    u.setPasswordHash(passwordEncoder.encode(new String(pw)));
                } else if (existing == null) {
                    JOptionPane.showMessageDialog(grid, "Password is required for a new user.");
                    return;
                }
                java.util.Arrays.fill(pw, ' ');
                u.setEnabled(enabledCheck.isSelected());
                u.setJurisdiction((Jurisdiction) jurisdictionCombo.getSelectedItem());
                Set<Role> roleSet = new java.util.HashSet<>();
                if (adminCheck.isSelected()) roleSet.add(Role.ADMIN);
                if (dataEntryCheck.isSelected()) roleSet.add(Role.DATA_ENTRY);
                if (printerCheck.isSelected()) roleSet.add(Role.PRINTER);
                u.setRoles(roleSet);
                onSave.accept(u);
            },
            () -> SwingUtilities.getWindowAncestor(grid).dispose());
    }
}
