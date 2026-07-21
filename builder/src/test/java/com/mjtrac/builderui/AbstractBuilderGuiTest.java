/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.model.Party;
import com.mjtrac.ballot.model.Region;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.PartyRepository;
import com.mjtrac.ballot.repository.RegionRepository;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.exception.ActionFailedException;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shared bootstrap for builder's AssertJ-Swing GUI tests: a real MainFrame,
 * driven by a real java.awt.Robot, against an isolated temp SQLite DB and
 * export dir — never ~/pbss_data/election_ballot.db. Mirrors counter's
 * CountingPipelineGuiTest bootstrap (see counter/src/test/java/com/mjtrac/
 * counterui/CountingPipelineGuiTest.java), factored out here since every
 * screen's GUI test needs the identical context/robot/teardown dance.
 *
 * Fresh Spring context + fresh temp DB per @Test method (JUnit 5's default
 * per-method instance lifecycle) — slower than sharing one context across a
 * class, but avoids any cross-test data leakage between screens.
 */
abstract class AbstractBuilderGuiTest {

    @TempDir Path tempDir;

    protected ConfigurableApplicationContext springContext;
    protected FrameFixture window;
    protected MainFrame mainFrame;

    @SpringBootApplication(scanBasePackages = "com.mjtrac.ballot")
    @EntityScan("com.mjtrac.ballot.model")
    static class SeedConfig {
    }

    @BeforeEach
    void bootBuilder() {
        String dbUrl = "jdbc:sqlite:" + tempDir.resolve("test.db");
        String exportDir = tempDir.resolve("export").toString();

        // MainFrame's constructor eagerly calls navigate(HOME), which
        // refreshes every screen (MainFrame.refreshCurrent() loops over all
        // of them, not just the one being navigated to) — including
        // PartyPanel/RegionPanel, whose onFirstOpenEmpty() pops a real,
        // blocking JOptionPane the instant either table is still empty at
        // that point. Against a brand-new temp DB, that fires immediately,
        // synchronously, as part of building the MainFrame bean below —
        // before this method has had a chance to create a robot to dismiss
        // it. Observed for real: a live, unaddressed "Quick Setup" dialog
        // hanging the whole boot indefinitely (an earlier version of this
        // class tried racing a background dismiss-robot against this, which
        // was itself an intermittent hang — same dialog, unlucky timing).
        //
        // Fix: seed one throwaway Party and Region first, through a
        // minimal UI-free context (scanning only com.mjtrac.ballot, the
        // same TestConfig trick BuilderEndToEndTest uses), so neither table
        // is ever empty when MainFrame's constructor runs — the dialog
        // simply never fires. Removed again immediately after, so each
        // test's own repository assertions see only what it created itself.
        // .headless(false) matters here even though this context never
        // touches Swing: SpringApplicationBuilder defaults to setting the
        // JVM-wide java.awt.headless=true, which — once the AWT toolkit
        // reads it, the first time anything touches java.awt — sticks for
        // the rest of the process. Without this, the real BuilderApp
        // context booted right after this one fails MainFrame's
        // construction with a HeadlessException, even though *that*
        // builder call also says .headless(false): by then it's too late,
        // the flag was already latched by this earlier context.
        try (ConfigurableApplicationContext seedContext = new SpringApplicationBuilder(SeedConfig.class)
                .web(WebApplicationType.NONE)
                .headless(false)
                .run("--spring.datasource.url=" + dbUrl, "--ballot.export.dir=" + exportDir)) {
            JurisdictionRepository jurisdictionRepo = seedContext.getBean(JurisdictionRepository.class);
            Jurisdiction placeholder = new Jurisdiction();
            placeholder.setName("__gui_boot_placeholder__");
            placeholder = jurisdictionRepo.save(placeholder);

            Party party = new Party();
            party.setJurisdiction(placeholder);
            party.setName("__placeholder__");
            seedContext.getBean(PartyRepository.class).save(party);

            Region region = new Region();
            region.setJurisdiction(placeholder);
            region.setName("__placeholder__");
            region.setRegionType(Region.RegionType.SINGLE_PRECINCT);
            seedContext.getBean(RegionRepository.class).save(region);
        }

        springContext = new SpringApplicationBuilder(BuilderApp.class)
            .web(WebApplicationType.NONE)
            .headless(false)
            .run("--spring.datasource.url=" + dbUrl, "--ballot.export.dir=" + exportDir);

        // Remove the placeholders now that MainFrame has already
        // constructed past the empty-table check. Each screen's own table
        // picks up the deletion the next time it refresh()es, which every
        // test triggers itself by navigating there via the menu first.
        bean(PartyRepository.class).deleteAll();
        bean(RegionRepository.class).deleteAll();
        bean(JurisdictionRepository.class).deleteAll();

        // The context above eagerly constructs MainFrame (and every child
        // screen) before this robot exists — a robot with a *new* AWT
        // hierarchy only indexes components added after it starts
        // listening, so it would find the frame but none of its pre-built
        // children. The *current* hierarchy walks the live AWT window list
        // instead, correctly picking up the already-built frame.
        mainFrame = GuiActionRunner.execute(() -> springContext.getBean(MainFrame.class));
        window = new FrameFixture(BasicRobot.robotWithCurrentAwtHierarchy(), mainFrame);
        window.show();
        window.focus();
    }

    @AfterEach
    void tearDownBuilder() {
        if (window != null) window.cleanUp();
        if (springContext != null) springContext.close();
    }

    /** Bean accessor for seeding prerequisite entities directly through repositories, bypassing the GUI. */
    protected <T> T bean(Class<T> type) {
        return springContext.getBean(type);
    }

    /**
     * Wraps a robot-driven action, skipping the test cleanly (not failing
     * it) if this JVM's launching process lacks macOS Accessibility
     * permission for synthetic input — same rationale/wording as counter's
     * CountingPipelineGuiTest, since java.awt.Robot has the identical
     * requirement regardless of which Swing app it's driving.
     */
    protected void robotAction(Runnable action) {
        try {
            action.run();
        } catch (ActionFailedException ex) {
            assumeTrue(false,
                "AssertJ-Swing could not gain real OS input focus in this environment. "
                    + "Grant Accessibility permission to whatever launched this JVM "
                    + "(macOS: System Settings -> Privacy & Security -> Accessibility "
                    + "-> add Terminal/iTerm, then restart it) and re-run, or run this "
                    + "test under Linux CI with Xvfb (not subject to this restriction). "
                    + "Original error: " + ex.getMessage());
        }
    }
}
