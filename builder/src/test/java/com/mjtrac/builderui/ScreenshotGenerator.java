/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 *
 * Manual dev utility, not a JUnit test — generates real screenshots of this
 * app's screens for the user's guide by painting its actual Swing
 * components offscreen into PNGs. Data-seeding mirrors
 * BuilderEndToEndTest's real save-then-reload pattern.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.repository.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ScreenshotGenerator {

    @SpringBootApplication(scanBasePackages = "com.mjtrac.ballot")
    @EntityScan("com.mjtrac.ballot.model")
    static class SeedConfig {
    }

    static final Path OUT_DIR = Paths.get(System.getProperty("shots.dir",
        "/private/tmp/claude-501/-Users-mjtrac-pbss/2c1ac0f4-5791-487a-b6fa-f42d90ccdd41/scratchpad/shots"));

    public static void main(String[] args) throws Exception {
        // BuilderApp.main() isn't used here (this bypasses it to build the
        // Spring context with test-only datasource overrides), so the
        // look-and-feel install() call it would normally do has to happen
        // here instead — same ordering requirement: before MainFrame gets
        // eagerly constructed as a Spring bean.
        PbssTheme.install();

        OUT_DIR.toFile().mkdirs();

        Path dataDir = OUT_DIR.resolve("seed/builder_pbss_data");
        dataDir.resolve("db").toFile().mkdirs();
        dataDir.resolve("ballot_templates").toFile().mkdirs();

        String[] overrides = {
            "--spring.datasource.url=jdbc:sqlite:" + dataDir.resolve("db/election_ballot.db"),
            "--ballot.export.dir=" + dataDir.resolve("ballot_templates"),
        };

        // Seed BEFORE the real (Swing-scanning) context boots below: MainFrame
        // is a Spring bean, eagerly constructed as part of that context's own
        // startup, and its constructor refreshes every screen including
        // Party/Region — whose onFirstOpenEmpty() pops a real, blocking
        // JOptionPane the instant either table is still empty at that point.
        // Seeding through this separate, UI-free context first (same trick
        // AbstractBuilderGuiTest/TestElectionBuilder use) means neither table
        // is ever empty when MainFrame's constructor actually runs — this
        // used to seed *after* .getBean(MainFrame.class), which never even
        // got that far: it hung inside .run(overrides) below, before this
        // method's own seedData() call was ever reached, the moment this ran
        // against a genuinely fresh scratch DB with nothing seeded yet.
        try (ConfigurableApplicationContext seedCtx = new SpringApplicationBuilder(SeedConfig.class)
                .web(WebApplicationType.NONE)
                .headless(false)
                .run(overrides)) {
            seedData(seedCtx);
        }

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(BuilderApp.class)
            .web(WebApplicationType.NONE)
            .headless(false)
            .run(overrides);

        try {
            String effectiveUrl = ctx.getEnvironment().getProperty("spring.datasource.url");
            System.out.println("Effective spring.datasource.url = " + effectiveUrl);
            if (effectiveUrl == null || !effectiveUrl.contains(dataDir.toString())) {
                throw new IllegalStateException(
                    "REFUSING TO CONTINUE: datasource did not resolve to the scratch dir. "
                    + "Effective URL was: " + effectiveUrl);
            }

            MainFrame frame = ctx.getBean(MainFrame.class);
            frame.setSize(1200, 780);
            frame.addNotify();
            frame.start(); // navigates to Home, refreshes every screen's table

            shoot(frame, "builder_1_home.png");

            JButton toggle = findButtonByText(frame, "?");
            if (toggle != null) {
                toggle.doClick();
                // The resize is deferred via invokeLater — flush it before validating/painting.
                SwingUtilities.invokeAndWait(() -> {});
                frame.validate();
                shoot(frame, "builder_1b_home_expanded.png");
            } else {
                System.out.println("WARNING: no step-annotation toggle button found");
            }

            navigate(frame, "Contests");
            shoot(frame, "builder_2_contests.png");

            JDialog contestForm = openDialogViaButton(frame, "ContestsNewButton");
            if (contestForm != null) {
                shoot(contestForm, "builder_2b_contest_form.png");
                SwingUtilities.invokeAndWait(contestForm::dispose);
            } else {
                System.out.println("WARNING: contest New dialog did not open");
            }

            // ContestCandidatesDialog isn't reachable via a button from here
            // without a full New/Edit round-trip — call it directly (same
            // package) against the seeded Mayor contest instead, polling
            // for the modal dialog the same way openDialogViaButton() does.
            Contest mayor = ctx.getBean(ContestRepository.class).findAll().stream()
                .filter(x -> "Mayor".equals(x.getTitle())).findFirst().orElseThrow();
            java.util.Set<Window> beforeCandidates = java.util.Set.of(Window.getWindows());
            SwingUtilities.invokeLater(() -> ContestCandidatesDialog.show(
                frame, mayor, ctx.getBean(ContestRepository.class),
                ctx.getBean(BallotLanguageRepository.class), ctx.getBean(CandidateTranslationRepository.class),
                () -> {}));
            JDialog candidatesDialog = null;
            for (int i = 0; i < 50 && candidatesDialog == null; i++) {
                for (Window w : Window.getWindows()) {
                    if (w instanceof JDialog d && d.isVisible() && !beforeCandidates.contains(w)) { candidatesDialog = d; break; }
                }
                if (candidatesDialog == null) Thread.sleep(100);
            }
            if (candidatesDialog != null) {
                shoot(candidatesDialog, "builder_2c_candidates_dialog.png");
                JDialog toDispose = candidatesDialog;
                SwingUtilities.invokeAndWait(toDispose::dispose);
            } else {
                System.out.println("WARNING: candidates dialog did not open");
            }

            navigate(frame, "Print");
            shoot(frame, "builder_3_print.png");

            System.out.println("Done: " + OUT_DIR);
        } finally {
            System.exit(0);
        }
    }

    private static void navigate(MainFrame frame, String screen) throws Exception {
        Method m = MainFrame.class.getDeclaredMethod("navigate", String.class);
        m.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                m.invoke(frame, screen);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Clicks a named button (asynchronously — the resulting dialog is
     * modal, so a synchronous click would block this thread until it's
     * dismissed) and polls for the new top-level window it opens.
     */
    private static JDialog openDialogViaButton(JFrame frame, String buttonName) throws Exception {
        JButton btn = findButtonByName(frame, buttonName);
        if (btn == null) return null;
        java.util.Set<Window> before = java.util.Set.of(Window.getWindows());
        SwingUtilities.invokeLater(btn::doClick);
        for (int i = 0; i < 50; i++) {
            for (Window w : Window.getWindows()) {
                if (w instanceof JDialog d && d.isVisible() && !before.contains(w)) return d;
            }
            Thread.sleep(100);
        }
        return null;
    }

    private static JButton findButtonByName(Container root, String name) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton b && name.equals(b.getName())) return b;
            if (c instanceof Container child) {
                JButton found = findButtonByName(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JButton findButtonByText(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton b && text.equals(b.getText())) return b;
            if (c instanceof Container child) {
                JButton found = findButtonByText(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void seedData(ConfigurableApplicationContext ctx) {
        JurisdictionRepository jurisdictionRepo = ctx.getBean(JurisdictionRepository.class);
        RegionRepository regionRepo = ctx.getBean(RegionRepository.class);
        ElectionRepository electionRepo = ctx.getBean(ElectionRepository.class);
        BallotTypeRepository ballotTypeRepo = ctx.getBean(BallotTypeRepository.class);
        ContestRepository contestRepo = ctx.getBean(ContestRepository.class);
        BallotCombinationRepository combinationRepo = ctx.getBean(BallotCombinationRepository.class);
        BallotDesignTemplateRepository templateRepo = ctx.getBean(BallotDesignTemplateRepository.class);

        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setName("Test County");
        jurisdiction = jurisdictionRepo.save(jurisdiction);

        Region precinct = new Region();
        precinct.setJurisdiction(jurisdiction);
        precinct.setName("Precinct 7");
        precinct.setRegionType(Region.RegionType.SINGLE_PRECINCT);
        precinct = regionRepo.save(precinct);

        // Without at least one Party, PartyPanel's table is empty when
        // MainFrame's constructor eagerly refreshes every screen —
        // triggering PartyPanel.onFirstOpenEmpty()'s real, blocking
        // JOptionPane with no robot/human to dismiss it, hanging this tool
        // indefinitely (same root cause as AbstractBuilderGuiTest's
        // seed-before-boot fix elsewhere in this module's test suite).
        Party party = new Party();
        party.setJurisdiction(jurisdiction);
        party.setName("Nonpartisan");
        ctx.getBean(PartyRepository.class).save(party);

        Election election = new Election();
        election.setJurisdiction(jurisdiction);
        election.setName("2026 General Election");
        election.setElectionType(Election.ElectionType.GENERAL);
        election = electionRepo.save(election);

        BallotType ballotType = new BallotType();
        ballotType.setJurisdiction(jurisdiction);
        ballotType.setName("Precinct");
        ballotType = ballotTypeRepo.save(ballotType);

        Contest mayor = new Contest();
        mayor.setElection(election);
        mayor.setTitle("Mayor");
        mayor.setMaxChoices(1);
        mayor.setVotingMethod(Contest.VotingMethod.PLURALITY);
        mayor.setAssignedRegions(List.of(precinct));
        Candidate alice = new Candidate();
        alice.setName("Alice Johnson");
        alice.setDisplayOrder(1);
        alice.setContest(mayor);
        Candidate bob = new Candidate();
        bob.setName("Bob Williams");
        bob.setDisplayOrder(2);
        bob.setContest(mayor);
        mayor.setCandidates(List.of(alice, bob));
        contestRepo.save(mayor);

        Contest council = new Contest();
        council.setElection(election);
        council.setTitle("City Council Member");
        council.setMaxChoices(2);
        council.setVotingMethod(Contest.VotingMethod.PLURALITY);
        council.setAssignedRegions(List.of(precinct));
        Candidate carmen = new Candidate();
        carmen.setName("Carmen Lopez");
        carmen.setDisplayOrder(1);
        carmen.setContest(council);
        Candidate dave = new Candidate();
        dave.setName("Dave Kim");
        dave.setDisplayOrder(2);
        dave.setContest(council);
        council.setCandidates(List.of(carmen, dave));
        contestRepo.save(council);

        BallotCombination combination = new BallotCombination();
        combination.setRegion(precinct);
        combination.setBallotType(ballotType);
        combination.setElection(election);
        combinationRepo.save(combination);

        BallotDesignTemplate template = new BallotDesignTemplate();
        template.setElection(election);
        template.setPaperSize(BallotDesignTemplate.PaperSize.LETTER_8_5x11);
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.OVAL);
        template.setColumns(1);
        templateRepo.save(template);
    }

    static void shoot(JFrame frame, String filename) throws Exception {
        shoot(frame.getContentPane(), frame.getWidth(), frame.getHeight(), filename);
    }

    static void shoot(JDialog dialog, String filename) throws Exception {
        shoot(dialog.getContentPane(), dialog.getWidth(), dialog.getHeight(), filename);
    }

    private static void shoot(Container content, int width, int height, String filename) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, img.getWidth(), img.getHeight());
        content.paint(g2);
        g2.dispose();
        ImageIO.write(img, "png", OUT_DIR.resolve(filename).toFile());
        System.out.println("Wrote " + filename + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }
}
