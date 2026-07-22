/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 *
 * Manual dev utility, not a JUnit test (no @Test, name doesn't match
 * Surefire's include patterns) — generates real screenshots of this app's
 * screens for the user's guide by painting its actual Swing components
 * offscreen into PNGs. Run with:
 *   ./mvnw -q test-compile exec:java -Dexec.mainClass=com.mjtrac.counterui.ScreenshotGenerator -Dexec.classpathScope=test
 */
package com.mjtrac.counterui;

import com.mjtrac.counter.entity.CounterUser;
import com.mjtrac.counter.repository.CounterUserRepository;
import com.mjtrac.counter.service.CountingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

public class ScreenshotGenerator {

    static final Path OUT_DIR = Paths.get(System.getProperty("shots.dir",
        "/private/tmp/claude-501/-Users-mjtrac-pbss/2c1ac0f4-5791-487a-b6fa-f42d90ccdd41/scratchpad/shots"));

    public static void main(String[] args) throws Exception {
        OUT_DIR.toFile().mkdirs();

        Path dataDir = OUT_DIR.resolve("seed/counter_pbss_data");
        dataDir.resolve("db").toFile().mkdirs();
        dataDir.resolve("writeins").toFile().mkdirs();
        dataDir.resolve("scribbles").toFile().mkdirs();
        dataDir.resolve("reports").toFile().mkdirs();
        URL resource = ScreenshotGenerator.class.getClassLoader().getResource("test-images/ballot_1_1_1_1_1_1.yaml");
        File testImagesDir = new File(resource.getFile()).getParentFile();

        // NOTE: SpringApplicationBuilder.properties(...) sets the LOWEST-
        // precedence "default properties" layer — BELOW the app's own
        // bundled application.properties, which already defines all of
        // these keys with real ~/pbss_data defaults. Using .properties()
        // here would silently connect to (and write into) the real
        // production database instead of this scratch one. Command-line
        // args are the highest-precedence override, so pass everything
        // through .run(args) instead.
        String[] overrides = {
            "--spring.datasource.url=jdbc:sqlite:" + dataDir.resolve("db/counter_results.db"),
            "--data.writeins.dir=" + dataDir.resolve("writeins"),
            "--data.scribbles.dir=" + dataDir.resolve("scribbles"),
            "--scanner.scribble-outline-dir=" + dataDir.resolve("scribbles"),
            "--reports.output.dir=" + dataDir.resolve("reports"),
            "--scanner.default.image.dir=" + testImagesDir.getAbsolutePath(),
            "--scanner.default.report.dir=" + testImagesDir.getAbsolutePath(),
        };
        // CounterApp.main() isn't used here (this bypasses it to build the
        // Spring context with test-only datasource overrides), so the
        // look-and-feel install() call it would normally do has to happen
        // here instead — same ordering requirement: before MainFrame gets
        // eagerly constructed as a Spring bean.
        PbssTheme.install();

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(CounterApp.class)
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

            seedUser(ctx);

            MainFrame frame = ctx.getBean(MainFrame.class);
            AuthContext authContext = ctx.getBean(AuthContext.class);
            authContext.setCurrentUser(ctx.getBean(CounterUserRepository.class).findByUsername("operator").orElseThrow());

            frame.setContentPane(frame.getContentPane()); // no-op, keeps intent obvious
            frame.pack();
            frame.setSize(Math.max(frame.getWidth(), 640), Math.max(frame.getHeight(), 420));
            frame.addNotify();
            frame.validate();

            shoot(frame, "counter_1_ready.png");

            JButton startButton = findButton(frame, "startButton");
            startButton.doClick();

            CountingService countingService = ctx.getBean(CountingService.class);
            long deadline = System.currentTimeMillis() + 30_000;
            while (countingService.getSession().scanning && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            // Let the async finish() SwingWorker's done() callback run and
            // update the labels/buttons — it's scheduled on the EDT.
            Thread.sleep(1500);
            SwingUtilities.invokeAndWait(() -> {});

            shoot(frame, "counter_2_complete.png");

            System.out.println("Done: " + OUT_DIR);
        } finally {
            System.exit(0);
        }
    }

    private static void seedUser(ConfigurableApplicationContext ctx) {
        CounterUserRepository repo = ctx.getBean(CounterUserRepository.class);
        if (repo.findByUsername("operator").isPresent()) return;
        CounterUser u = new CounterUser();
        u.setUsername("operator");
        u.setPasswordHash(new BCryptPasswordEncoder().encode("unused"));
        u.setEnabled(true);
        u.setRoles(Set.of(CounterUser.Role.COUNTER_OPERATOR));
        repo.save(u);
    }

    static JButton findButton(JFrame frame, String fieldName) throws Exception {
        Field f = MainFrame.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (JButton) f.get(frame);
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
