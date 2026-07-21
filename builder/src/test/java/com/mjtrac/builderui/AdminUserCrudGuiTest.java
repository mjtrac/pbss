/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.UserRepository;
import org.assertj.swing.data.TableCell;
import org.assertj.swing.finder.JOptionPaneFinder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * New -> Edit -> Delete -> Refresh round trip for the Admin (Users) screen,
 * against the isolated test DB only — never the real shared `users` table
 * bBuilder/blBuilder also log into (see AbstractBuilderGuiTest). Also
 * covers the password-required-on-create validation dialog.
 */
class AdminUserCrudGuiTest extends AbstractBuilderGuiTest {

    @Test
    void newEditDeleteRefreshCycle() {
        JurisdictionRepository jurisdictionRepo = bean(JurisdictionRepository.class);
        UserRepository repo = bean(UserRepository.class);

        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Test County");
        jurisdictionRepo.save(jurisdiction);

        robotAction(() -> {
            window.menuItem("AdminUsersMenuItem").click();

            // Blank-password-on-create must be rejected with a blocking dialog.
            window.button("UsersNewButton").click();
            window.textBox("usernameField").setText("clerk1");
            window.button("saveButton").click();
        });
        robotAction(() -> JOptionPaneFinder.findOptionPane().using(window.robot()).okButton().click());
        assertThat(repo.findAll()).isEmpty();

        robotAction(() -> {
            window.textBox("passwordField").setText("s3cret-pw");
            window.checkBox("dataEntryCheck").check();
            window.comboBox("jurisdictionCombo").selectItem(0);
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).extracting("username").containsExactly("clerk1");
        assertThat(repo.findAll().get(0).getRoles()).containsExactly(com.mjtrac.ballot.model.User.Role.DATA_ENTRY);

        robotAction(() -> {
            window.table("UsersTable").cell(TableCell.row(0).column(1)).doubleClick();
            window.checkBox("adminCheck").check();
            window.button("saveButton").click();
        });
        assertThat(repo.findAll().get(0).getRoles())
            .containsExactlyInAnyOrder(com.mjtrac.ballot.model.User.Role.DATA_ENTRY, com.mjtrac.ballot.model.User.Role.ADMIN);

        robotAction(() -> {
            window.table("UsersTable").selectRows(0);
            window.button("UsersDeleteButton").click();
        });
        robotAction(() -> JOptionPaneFinder.findOptionPane().using(window.robot()).yesButton().click());
        assertThat(repo.findAll()).isEmpty();

        robotAction(() -> window.button("UsersRefreshButton").click());
        assertThat(window.table("UsersTable").target().getRowCount()).isEqualTo(0);
    }
}
