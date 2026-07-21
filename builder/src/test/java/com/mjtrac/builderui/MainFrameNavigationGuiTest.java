/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every JMenuBar item switches the CardLayout to the right screen. Doesn't
 * click Home > Exit — that calls dispatchEvent(WINDOW_CLOSING), which under
 * EXIT_ON_CLOSE kills the JVM mid-test-run; its presence/enabled state is
 * asserted instead of actually triggering it.
 */
class MainFrameNavigationGuiTest extends AbstractBuilderGuiTest {

    @Test
    void everyMenuItemNavigatesToItsScreen() {
        org.assertj.swing.fixture.JTableFixture[] usersTableHolder = new org.assertj.swing.fixture.JTableFixture[1];
        robotAction(() -> {
            window.menuItem("dashboardMenuItem").requireVisible();

            window.menuItem("ElectionsMenuItem").click();
            assertThat(window.table("ElectionsTable").target().isShowing()).isTrue();

            window.menuItem("RegionsMenuItem").click();
            assertThat(window.table("RegionsTable").target().isShowing()).isTrue();

            window.menuItem("PartiesMenuItem").click();
            assertThat(window.table("PartiesTable").target().isShowing()).isTrue();

            window.menuItem("BallotTypesMenuItem").click();
            assertThat(window.table("BallotTypesTable").target().isShowing()).isTrue();

            window.menuItem("ContestsMenuItem").click();
            assertThat(window.table("ContestsTable").target().isShowing()).isTrue();

            window.menuItem("LanguagesMenuItem").click();
            assertThat(window.table("LanguagesTable").target().isShowing()).isTrue();

            window.menuItem("BallotCombinationsMenuItem").click();
            assertThat(window.table("BallotCombinationsTable").target().isShowing()).isTrue();

            window.menuItem("BallotDesignTemplatesMenuItem").click();
            assertThat(window.table("BallotDesignTemplatesTable").target().isShowing()).isTrue();

            window.menuItem("PrintMenuItem").click();
            assertThat(window.button("generateButton").target().isShowing()).isTrue();

            window.menuItem("JurisdictionsMenuItem").click();
            assertThat(window.table("JurisdictionsTable").target().isShowing()).isTrue();

            window.menuItem("AdminUsersMenuItem").click();
            usersTableHolder[0] = window.table("UsersTable");
            assertThat(usersTableHolder[0].target().isShowing()).isTrue();

            window.menuItem("dashboardMenuItem").click();
        });
        // window.table(name) requires the target to be showing by default,
        // so leaving Users and confirming it's gone means checking the
        // already-found fixture's live component reference rather than
        // re-finding it (which would itself throw once it's hidden).
        assertThat(usersTableHolder[0].target().isShowing()).isFalse();

        // Deliberately not clicked: Home > Exit would tear down the JVM
        // mid-suite (EXIT_ON_CLOSE). Presence/enabled state only.
        window.menuItem("exitMenuItem").requireVisible().requireEnabled();
    }
}
