/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Election;
import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.repository.BallotDesignTemplateRepository;
import com.mjtrac.ballot.repository.ElectionRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import org.assertj.swing.finder.JOptionPaneFinder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * New -> Edit -> Delete -> Refresh round trip for the Ballot Design
 * Templates screen. This form has ~40 fields (see BallotDesignTemplatePanel's
 * class Javadoc) — full per-field coverage would be disproportionate to its
 * regression risk; this drives a representative subset spanning every
 * control type (combo, spinner, checkbox, the generated typography-table
 * row controls, and the header text area) rather than all of them.
 */
class BallotDesignTemplateCrudGuiTest extends AbstractBuilderGuiTest {

    @Test
    void newEditDeleteRefreshCycle() {
        JurisdictionRepository jurisdictionRepo = bean(JurisdictionRepository.class);
        ElectionRepository electionRepo = bean(ElectionRepository.class);
        BallotDesignTemplateRepository repo = bean(BallotDesignTemplateRepository.class);

        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Test County");
        jurisdiction = jurisdictionRepo.save(jurisdiction);

        Election election = new Election();
        election.setJurisdiction(jurisdiction);
        election.setName("Test Election");
        election.setElectionType(Election.ElectionType.GENERAL);
        electionRepo.save(election);

        robotAction(() -> {
            window.menuItem("BallotDesignTemplatesMenuItem").click();

            window.button("BallotDesignTemplatesNewButton").click();
            window.comboBox("electionCombo").selectItem(0);
            window.comboBox("paperCombo").selectItem("LETTER_8_5x11");
            window.comboBox("indicatorCombo").selectItem("OVAL");
            window.spinner("columnsSpinner").enterText("2");
            window.spinner("typeRowContestTitleSize").enterText("14");
            window.checkBox("typeRowContestTitleBold").check();
            window.checkBox("rcvShowRankNumbers").check();
            window.textBox("headerHtmlArea").setText("<h1>Test Ballot</h1>");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll()).hasSize(1);
        var saved = repo.findAll().get(0);
        assertThat(saved.getPaperSize().name()).isEqualTo("LETTER_8_5x11");
        assertThat(saved.getColumns()).isEqualTo(2);
        assertThat(saved.getContestTitleFontSize()).isEqualTo(14f);
        assertThat(saved.isContestTitleBold()).isTrue();
        assertThat(saved.isRcvShowRankNumbers()).isTrue();
        assertThat(saved.getHeaderHtml()).isEqualTo("<h1>Test Ballot</h1>");

        robotAction(() -> {
            window.table("BallotDesignTemplatesTable").selectRows(0);
            window.button("BallotDesignTemplatesEditButton").click();
            window.spinner("columnsSpinner").enterText("3");
            window.button("saveButton").click();
        });
        assertThat(repo.findAll().get(0).getColumns()).isEqualTo(3);

        robotAction(() -> {
            window.table("BallotDesignTemplatesTable").selectRows(0);
            window.button("BallotDesignTemplatesDeleteButton").click();
        });
        robotAction(() -> JOptionPaneFinder.findOptionPane().using(window.robot()).yesButton().click());
        assertThat(repo.findAll()).isEmpty();

        robotAction(() -> window.button("BallotDesignTemplatesRefreshButton").click());
        assertThat(window.table("BallotDesignTemplatesTable").target().getRowCount()).isEqualTo(0);
    }
}
