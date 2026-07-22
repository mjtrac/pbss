/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 *
 * Manual dev utility, not a JUnit test — generates real screenshots of this
 * app's screens for the user's guide by painting its actual Swing
 * components offscreen into PNGs. Reads (never writes — ddl-auto=none,
 * same as the real app) the rich ballot corpus left behind by an earlier
 * test-harness desktop GUI pipeline run, entirely under test-harness/
 * (gitignored, disposable test output — not real election data).
 */
package com.mjtrac.viewerui;

import com.mjtrac.viewer.service.BallotViewService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ScreenshotGenerator {

    static final Path OUT_DIR = Paths.get(System.getProperty("shots.dir",
        "/private/tmp/claude-501/-Users-mjtrac-pbss/2c1ac0f4-5791-487a-b6fa-f42d90ccdd41/scratchpad/shots"));

    // Read-only seed: an earlier blCounter test-harness GUI-pipeline run's
    // output. Never written to here (viewer's own ddl-auto=none matches).
    static final String SEED_DB = System.getProperty("user.home")
        + "/pbss2/test-harness/desktop_pipeline/blcounter_results/counter_results.db";

    public static void main(String[] args) throws Exception {
        OUT_DIR.toFile().mkdirs();

        if (!new File(SEED_DB).isFile()) {
            throw new IllegalStateException("Seed DB not found: " + SEED_DB
                + " — run test-harness/run_desktop_gui_pipeline.sh first.");
        }

        String[] overrides = {
            "--spring.datasource.url=jdbc:sqlite:" + SEED_DB,
        };
        // ViewerApp.main() isn't used here (this bypasses it to build the
        // Spring context with test-only datasource overrides), so the
        // look-and-feel install() call it would normally do has to happen
        // here instead — same ordering requirement: before MainFrame gets
        // eagerly constructed as a Spring bean.
        PbssTheme.install();

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ViewerApp.class)
            .web(WebApplicationType.NONE)
            .headless(false)
            .run(overrides);

        try {
            String effectiveUrl = ctx.getEnvironment().getProperty("spring.datasource.url");
            System.out.println("Effective spring.datasource.url = " + effectiveUrl);
            if (effectiveUrl == null || !effectiveUrl.contains(SEED_DB)) {
                throw new IllegalStateException(
                    "REFUSING TO CONTINUE: datasource did not resolve to the read-only seed db. "
                    + "Effective URL was: " + effectiveUrl);
            }

            BallotListPanel listPanel = ctx.getBean(BallotListPanel.class);
            BallotViewPanel viewPanel = ctx.getBean(BallotViewPanel.class);
            BallotViewService viewService = ctx.getBean(BallotViewService.class);

            listPanel.setSize(1100, 750);
            listPanel.doLayout();
            listPanel.addNotify();
            listPanel.refresh();
            listPanel.validate();
            shoot(listPanel, "viewer_1_list.png");

            List<Long> ids = viewService.listAll().stream().map(b -> b.id).toList();
            Long targetId = viewService.listAll().stream()
                .filter(b -> b.imageName.contains("valid_all_check") && b.imageName.contains("clean"))
                .map(b -> b.id).findFirst().orElse(ids.get(0));

            viewPanel.setSize(1100, 750);
            viewPanel.doLayout();
            viewPanel.addNotify();
            viewPanel.validate();

            SwingUtilities.invokeAndWait(() -> viewPanel.load(targetId, ids));
            // load() schedules fitToViewport() via invokeLater — flush the
            // EDT queue once more so it actually runs before we paint.
            SwingUtilities.invokeAndWait(() -> {});
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(() -> {});

            shoot(viewPanel, "viewer_2_view.png");

            ContestCandidateWindow contestWindow = ctx.getBean(ContestCandidateWindow.class);
            contestWindow.setSize(400, 620);
            contestWindow.addNotify();
            JTree tree = (JTree) getField(contestWindow, "tree");
            for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
            contestWindow.validate();
            shoot((JComponent) contestWindow.getContentPane(), "viewer_3_contests.png");

            System.out.println("Done: " + OUT_DIR);
        } finally {
            System.exit(0);
        }
    }

    static Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    static void shoot(JComponent panel, String filename) throws Exception {
        BufferedImage img = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, img.getWidth(), img.getHeight());
        panel.paint(g2);
        g2.dispose();
        ImageIO.write(img, "png", OUT_DIR.resolve(filename).toFile());
        System.out.println("Wrote " + filename + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }
}
