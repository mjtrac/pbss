/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.repository.BallotLanguageRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import org.assertj.swing.data.TableCell;
import org.assertj.swing.finder.JOptionPaneFinder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** New -> Edit -> Delete -> Refresh round trip for the Languages screen. */
class BallotLanguageCrudGuiTest extends AbstractBuilderGuiTest {

    @Test
    void newEditDeleteRefreshCycle() {
        JurisdictionRepository jurisdictionRepo = bean(JurisdictionRepository.class);
        BallotLanguageRepository repo = bean(BallotLanguageRepository.class);

        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Test County");
        jurisdictionRepo.save(jurisdiction);

        robotAction(() -> {
            window.menuItem("LanguagesMenuItem").click();

            window.button("LanguagesNewButton").click();
            window.comboBox("jurisdictionCombo").selectItem(0);
            window.textBox("codeField").setText("es");
            window.textBox("nameField").setText("Spanish");
            window.spinner("orderSpinner").enterText("1");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).extracting("languageName").containsExactly("Spanish");

        robotAction(() -> {
            window.table("LanguagesTable").cell(TableCell.row(0).column(3)).doubleClick();
            window.textBox("nameField").setText("Español");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).extracting("languageName").containsExactly("Español");

        robotAction(() -> {
            window.table("LanguagesTable").selectRows(0);
            window.button("LanguagesDeleteButton").click();
        });
        robotAction(() -> JOptionPaneFinder.findOptionPane().using(window.robot()).yesButton().click());
        assertThat(repo.findAll()).isEmpty();

        robotAction(() -> window.button("LanguagesRefreshButton").click());
        assertThat(window.table("LanguagesTable").target().getRowCount()).isEqualTo(0);
    }
}
