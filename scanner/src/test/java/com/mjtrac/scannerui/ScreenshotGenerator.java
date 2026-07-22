/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 *
 * Manual dev utility, not a JUnit test — generates real screenshots of this
 * app's screens for the user's guide by painting its actual Swing
 * components offscreen into PNGs.
 */
package com.mjtrac.scannerui;

import com.mjtrac.scanner.entity.ScannerUser;
import com.mjtrac.scanner.repository.ScannerUserRepository;
import com.mjtrac.scanner.service.ScanService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ScreenshotGenerator {

    static final Path OUT_DIR = Paths.get(System.getProperty("shots.dir",
        "/private/tmp/claude-501/-Users-mjtrac-pbss/2c1ac0f4-5791-487a-b6fa-f42d90ccdd41/scratchpad/shots"));

    public static void main(String[] args) throws Exception {
        OUT_DIR.toFile().mkdirs();

        Path dataDir = OUT_DIR.resolve("seed/scanner_pbss_data");
        dataDir.resolve("db").toFile().mkdirs();
        dataDir.resolve("scans").toFile().mkdirs();

        // Reuse one of counter's committed test fixture PNGs as a stand-in
        // "scanned" image so the custom command backend has something real
        // to copy — no physical scanner needed for a realistic screenshot.
        String sourcePng = Paths.get(System.getProperty("user.home"),
            "pbss2/counter/src/test/resources/test-images/ballot_1_1_1_1_1_1__mostly_blank__clean__c01.png").toString();

        String[] overrides = {
            "--spring.datasource.url=jdbc:sqlite:" + dataDir.resolve("db/scanner.db"),
            "--scanner.output.dir=" + dataDir.resolve("scans"),
            "--scanner.backend=command",
            "--scanner.custom.command=cp " + sourcePng + " {output}scanned_ballot_0001.png",
        };
        // ScannerApp.main() isn't used here (this bypasses it to build the
        // Spring context with test-only datasource overrides), so the
        // look-and-feel install() call it would normally do has to happen
        // here instead — same ordering requirement: before MainFrame gets
        // eagerly constructed as a Spring bean.
        PbssTheme.install();

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ScannerApp.class)
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
            authContext.setCurrentUser(ctx.getBean(ScannerUserRepository.class).findByUsername("scanop").orElseThrow());

            frame.pack();
            frame.setSize(Math.max(frame.getWidth(), 620), Math.max(frame.getHeight(), 380));
            frame.addNotify();
            frame.validate();
            shoot(frame, "scanner_1_ready.png");

            findField(frame, "startNotesArea", JTextArea.class)
                .setText("Beginning Precinct 7 batch — box 1 of 3.");

            JButton startButton = findField(frame, "startButton", JButton.class);
            startButton.doClick();

            ScanService scanService = ctx.getBean(ScanService.class);
            long deadline = System.currentTimeMillis() + 20_000;
            while (scanService.getSession().scanning && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            Thread.sleep(1000);
            SwingUtilities.invokeAndWait(() -> {});
            frame.pack();
            frame.setSize(Math.max(frame.getWidth(), 620), Math.max(frame.getHeight(), 460));
            frame.validate();

            shoot(frame, "scanner_2_complete.png");

            System.out.println("Done: " + OUT_DIR);
        } finally {
            System.exit(0);
        }
    }

    private static void seedUser(ConfigurableApplicationContext ctx) {
        ScannerUserRepository repo = ctx.getBean(ScannerUserRepository.class);
        if (repo.findByUsername("scanop").isPresent()) return;
        PasswordEncoder encoder = ctx.getBean(PasswordEncoder.class);
        repo.save(new ScannerUser("scanop", encoder.encode("unused"), "OPERATOR"));
    }

    @SuppressWarnings("unchecked")
    static <T> T findField(JFrame frame, String fieldName, Class<T> type) throws Exception {
        Field f = MainFrame.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (T) f.get(frame);
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
