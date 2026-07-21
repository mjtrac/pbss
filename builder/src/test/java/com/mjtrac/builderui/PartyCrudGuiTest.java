/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.PartyRepository;
import org.assertj.swing.data.TableCell;
import org.assertj.swing.finder.JOptionPaneFinder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * New -> Edit -> Delete -> Refresh round trip for the Parties screen, plus
 * the "Use Single Party" toolbar button. The equivalent onFirstOpenEmpty()
 * quick-setup dialog fires automatically the instant this screen's table is
 * first refreshed while empty (including as a side effect of MainFrame's
 * own startup navigate(HOME) call, before any test-specific robot exists to
 * dismiss it — see AbstractBuilderGuiTest's boot dialog-dismiss loop) —
 * exercising the same replacePartiesWithSingle() logic via the explicit
 * button instead is deterministic rather than racing that implicit trigger.
 */
class PartyCrudGuiTest extends AbstractBuilderGuiTest {

    @Test
    void newEditDeleteRefreshCycle() {
        JurisdictionRepository jurisdictionRepo = bean(JurisdictionRepository.class);
        PartyRepository repo = bean(PartyRepository.class);

        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Test County");
        jurisdictionRepo.save(jurisdiction);

        robotAction(() -> {
            window.menuItem("PartiesMenuItem").click();

            window.button("PartiesNewButton").click();
            window.comboBox("jurisdictionCombo").selectItem(0);
            window.textBox("nameField").setText("Independence");
            window.textBox("abbrevField").setText("IND");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).extracting("name").containsExactly("Independence");

        robotAction(() -> {
            window.table("PartiesTable").cell(TableCell.row(0).column(2)).doubleClick();
            window.textBox("nameField").setText("Independent");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).extracting("name").containsExactly("Independent");

        robotAction(() -> {
            window.table("PartiesTable").selectRows(0);
            window.button("PartiesDeleteButton").click();
        });
        robotAction(() -> JOptionPaneFinder.findOptionPane().using(window.robot()).yesButton().click());
        assertThat(repo.findAll()).isEmpty();

        robotAction(() -> window.button("PartiesRefreshButton").click());
        assertThat(window.table("PartiesTable").target().getRowCount()).isEqualTo(0);
    }

    @Test
    void useSinglePartyReplacesExistingParties() {
        JurisdictionRepository jurisdictionRepo = bean(JurisdictionRepository.class);
        PartyRepository repo = bean(PartyRepository.class);

        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Test County");
        jurisdictionRepo.save(jurisdiction);

        robotAction(() -> {
            window.menuItem("PartiesMenuItem").click();
            window.button("PartiesNewButton").click();
            window.comboBox("jurisdictionCombo").selectItem(0);
            window.textBox("nameField").setText("Republican");
            window.button("saveButton").click();

            window.button("PartiesNewButton").click();
            window.comboBox("jurisdictionCombo").selectItem(0);
            window.textBox("nameField").setText("Democratic");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).hasSize(2);

        robotAction(() -> window.button("useSinglePartyButton").click());
        robotAction(() -> JOptionPaneFinder.findOptionPane().using(window.robot()).yesButton().click());

        assertThat(repo.findAll()).extracting("name").containsExactly("Nonpartisan");
    }
}
