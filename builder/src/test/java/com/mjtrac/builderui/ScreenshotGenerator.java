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

    static final Path OUT_DIR = Paths.get(System.getProperty("shots.dir",
        "/private/tmp/claude-501/-Users-mjtrac-pbss/2c1ac0f4-5791-487a-b6fa-f42d90ccdd41/scratchpad/shots"));

    public static void main(String[] args) throws Exception {
        OUT_DIR.toFile().mkdirs();

        Path dataDir = OUT_DIR.resolve("seed/builder_pbss_data");
        dataDir.resolve("db").toFile().mkdirs();
        dataDir.resolve("ballot_templates").toFile().mkdirs();

        String[] overrides = {
            "--spring.datasource.url=jdbc:sqlite:" + dataDir.resolve("db/election_ballot.db"),
            "--ballot.export.dir=" + dataDir.resolve("ballot_templates"),
        };
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

            seedData(ctx);

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
        Container content = frame.getContentPane();
        BufferedImage img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, img.getWidth(), img.getHeight());
        content.paint(g2);
        g2.dispose();
        ImageIO.write(img, "png", OUT_DIR.resolve(filename).toFile());
        System.out.println("Wrote " + filename + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }
}
