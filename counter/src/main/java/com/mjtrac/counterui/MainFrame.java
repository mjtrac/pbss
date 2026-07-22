/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counterui;

import com.mjtrac.counter.model.ScanSession;
import com.mjtrac.counter.service.CountingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;

/**
 * The entire UI: two folder fields (with defaults), Start/Resume + Pause, a
 * live progress readout, and a button pointing at the results folder once a
 * scan finishes. Pause leaves the session in place and relabels Start to
 * Resume (continuing via CountingService.resumeScan() from wherever it
 * stopped, not re-scanning from the beginning); genuine completion (the
 * image queue runs out on its own) is the only thing that auto-runs
 * finish() and disables both buttons, showing where the results landed.
 * Deliberately minimal — no results browser, no report viewer of its own;
 * scan parameters beyond the two folders (threshold, darkness, DPI, paper
 * width) are fixed via application.properties rather than exposed here. See
 * CountingViewController (blCounter's JavaFX equivalent, which this
 * mirrors) for the fuller-featured version.
 */
@Component
public class MainFrame extends JFrame {

    private final CountingService countingService;
    private final AuthContext authContext;
    private final LoginDialog loginDialog;

    private final int    fixedThreshold;
    private final double fixedDarkPctMin;
    private final int    fixedDpi;
    private final double fixedPaperWidthIn;

    private final JTextField imageFolderField;
    private final JTextField reportFolderField;
    private final JButton startButton = new JButton("Start Counting");
    private final JButton pauseButton = new JButton("Pause");
    private final JButton openResultsButton = new JButton("Open Results Folder");
    private final JButton printResultsButton = new JButton("Print Results");
    private final JLabel statusLabel = new JLabel("Ready");
    private final JLabel progressLabel = new JLabel(" ");
    private final JLabel messageLabel = new JLabel(" ");

    private Timer pollTimer;
    private boolean finishRequested = false;

    MainFrame(CountingService countingService, AuthContext authContext, LoginDialog loginDialog,
              @Value("${scanner.default.image.dir}") String defaultImageDir,
              @Value("${scanner.default.report.dir}") String defaultReportDir,
              @Value("${counter.threshold:128}") int fixedThreshold,
              @Value("${counter.dark-pct-min:8.0}") double fixedDarkPctMin,
              @Value("${counter.dpi:200}") int fixedDpi,
              @Value("${counter.assumed-paper-width-in:8.5}") double fixedPaperWidthIn) {
        super("pbss Ballot Counter v" + Launcher.readVersion());
        this.countingService = countingService;
        this.authContext = authContext;
        this.loginDialog = loginDialog;
        this.fixedThreshold = fixedThreshold;
        this.fixedDarkPctMin = fixedDarkPctMin;
        this.fixedDpi = fixedDpi;
        this.fixedPaperWidthIn = fixedPaperWidthIn;

        this.imageFolderField = new JTextField(defaultImageDir, 34);
        this.reportFolderField = new JTextField(defaultReportDir, 34);
        imageFolderField.setName("imageFolderField");
        reportFolderField.setName("reportFolderField");
        startButton.setName("startButton");
        pauseButton.setName("pauseButton");
        openResultsButton.setName("openResultsButton");
        printResultsButton.setName("printResultsButton");
        statusLabel.setName("statusLabel");
        progressLabel.setName("progressLabel");
        messageLabel.setName("messageLabel");

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setContentPane(buildContent());
        pack();
        // pack() sizes the window from each label's *initial* text —
        // progressLabel/statusLabel/messageLabel start blank or short and
        // grow substantially once a real scan is running (e.g. "Scan
        // halted: 20 ballots required manual review (limit 20). Fix the
        // issue and rescan uncounted images."). Non-resizable plus a pack()
        // snapshot from before that text exists meant real messages and
        // even the Browse buttons could end up clipped outside the window
        // — a real user-reported bug, not a placeholder concern.
        Dimension packed = getSize();
        setMinimumSize(new Dimension(Math.max(packed.width, 640), Math.max(packed.height, 420)));
        setLocationRelativeTo(null);

        startButton.addActionListener(this::handleStart);
        pauseButton.addActionListener(this::handlePause);
        openResultsButton.addActionListener(this::handleOpenResults);
        printResultsButton.addActionListener(this::handlePrintResults);
        pauseButton.setEnabled(false);
        openResultsButton.setEnabled(false);
        printResultsButton.setEnabled(false);
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new GridBagLayout());
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST;
        // Every row fills the available width and the container gets all
        // the horizontal weight — otherwise each row just takes its own
        // (possibly stale, pre-scan) preferred width and the window's
        // overall preferred size undershoots once real content arrives.
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        int row = 0;

        c.gridy = row++;
        root.add(PbssTheme.titleBlock("pbss Ballot Counter"), c);

        c.gridy = row++;
        root.add(new JLabel("Ballot images folder:"), c);
        c.gridy = row++;
        root.add(folderRow(imageFolderField), c);

        c.gridy = row++;
        root.add(new JLabel("Ballot templates folder (YAML/XML layouts):"), c);
        c.gridy = row++;
        root.add(folderRow(reportFolderField), c);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        buttons.add(startButton);
        buttons.add(pauseButton);
        c.gridy = row++;
        root.add(buttons, c);

        c.gridy = row++;
        root.add(statusLabel, c);
        c.gridy = row++;
        root.add(progressLabel, c);
        c.gridy = row++;
        messageLabel.setForeground(new Color(0xdc, 0x26, 0x26));
        root.add(messageLabel, c);

        JPanel resultButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        resultButtons.add(openResultsButton);
        resultButtons.add(printResultsButton);
        c.gridy = row++;
        root.add(resultButtons, c);

        return root;
    }

    private JPanel folderRow(JTextField field) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(field, BorderLayout.CENTER);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> browseInto(field));
        row.add(browse, BorderLayout.EAST);
        return row;
    }

    private void browseInto(JTextField field) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (!field.getText().isBlank()) {
            File current = new File(field.getText());
            if (current.isDirectory()) chooser.setCurrentDirectory(current);
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void handleStart(ActionEvent e) {
        clearMessage();
        // A session that's been started, isn't currently scanning, and
        // stopped via stopRequested (either the user's own Pause or the
        // max-review-before-stop halt) is resumable in place — continuing
        // from session.currentIndex, not re-enumerating the image folder
        // and re-scanning everything from the start. Anything else (never
        // started, or genuinely complete) is a fresh start.
        ScanSession existing = countingService.getSession();
        boolean resuming = existing.isStarted() && !existing.scanning && existing.stopRequested;
        if (resuming) {
            try {
                countingService.resumeScan(currentUsername());
            } catch (IllegalStateException ex) {
                showError(ex.getMessage());
                return;
            }
        } else {
            try {
                countingService.startNewSession(
                    stripTrailingSlash(imageFolderField.getText()), stripTrailingSlash(reportFolderField.getText()),
                    fixedThreshold, fixedDarkPctMin, fixedDpi, false, "", fixedPaperWidthIn);
            } catch (Exception ex) {
                showError(ex.getMessage());
                return;
            }
            try {
                countingService.startScan(currentUsername());
            } catch (IllegalStateException ex) {
                showError(ex.getMessage());
                return;
            }
        }
        finishRequested = false;
        openResultsButton.setEnabled(false);
        startButton.setEnabled(false);
        pauseButton.setEnabled(true);
        startPolling();
    }

    private void handlePause(ActionEvent e) {
        countingService.stopScan();
        // The scan loop notices stopRequested and exits asynchronously;
        // render() relabels Start -> Resume and re-enables it once that's
        // actually observed (session.scanning == false). Disable Pause
        // immediately since pausing an already-stopping scan is meaningless.
        pauseButton.setEnabled(false);
    }

    private void handleOpenResults(ActionEvent e) {
        String dir = countingService.getReportOutputDir();
        try {
            if (dir != null && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(new File(dir));
            } else {
                showError("Results folder: " + dir);
            }
        } catch (IOException ex) {
            showError("Could not open results folder: " + ex.getMessage());
        }
    }

    /**
     * Delegates to the OS's own PRINT verb on results_report.html — unlike
     * BatchSheetPrinter's hand-drawn Graphics2D page, a real vote-tally
     * report can have many tables, so letting the platform's own HTML
     * renderer (whatever it opens the file with) handle layout/pagination
     * is far more robust than reimplementing that in Swing.
     */
    private void handlePrintResults(ActionEvent e) {
        String dir = countingService.getReportOutputDir();
        if (dir == null) {
            showError("No results yet — finish a scan first.");
            return;
        }
        File report = new File(dir, "results_report.html");
        if (!report.exists()) {
            showError("Results report not found: " + report);
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.PRINT)) {
                Desktop.getDesktop().print(report);
            } else {
                showError("Printing isn't supported on this system — open " + report + " and print it manually.");
            }
        } catch (IOException ex) {
            showError("Could not print results: " + ex.getMessage());
        }
    }

    /**
     * A trailing slash shouldn't change behavior — a bare string comparison
     * or path-join done naively elsewhere could otherwise treat
     * "/foo/bar" and "/foo/bar/" as different folders.
     */
    private static String stripTrailingSlash(String path) {
        if (path == null) return null;
        String trimmed = path.strip();
        while (trimmed.length() > 1 && (trimmed.endsWith("/") || trimmed.endsWith(File.separator))) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private void startPolling() {
        stopPolling();
        pollTimer = new Timer(500, e -> pollStatus());
        pollTimer.start();
    }

    private void stopPolling() {
        if (pollTimer != null) {
            pollTimer.stop();
            pollTimer = null;
        }
    }

    private void pollStatus() {
        ScanSession session = countingService.getSession();
        // Same reasoning as blCounter's JavaFX progress screen: this panel is
        // always live, so the milestone pause a browser UI would need
        // POST /resume for is cleared immediately here instead.
        if (session.pauseForResults) {
            countingService.clearPause();
        }
        render(session);

        if (!session.scanning && session.isStarted()) {
            // Genuine completion (the scan loop ran out of images on its
            // own) is the only case that should auto-run finish() and write
            // final reports -- a user-requested Pause, or the
            // max-review-before-stop halt, both leave stopRequested true and
            // just need to stop polling until the user clicks Resume.
            boolean genuinelyComplete = session.scanError == null && !session.stopRequested;
            if (genuinelyComplete) {
                if (!finishRequested) {
                    finishRequested = true;
                    runFinish();
                }
            } else {
                stopPolling();
            }
        }
    }

    /** Runs finish() off the EDT — it does file I/O (report writing, audit log). */
    private void runFinish() {
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                countingService.finish(currentUsername());
                return null;
            }
            @Override protected void done() {
                stopPolling();
                try {
                    get();
                    openResultsButton.setEnabled(true);
                    printResultsButton.setEnabled(true);
                } catch (Exception ex) {
                    showError("Could not write final results: " + ex.getMessage());
                }
                // Nothing left to count -- keep both Start/Resume and Pause
                // disabled. render() below shows where the results landed.
                startButton.setEnabled(false);
                pauseButton.setEnabled(false);
                render(countingService.getSession());
            }
        }.execute();
    }

    private void render(ScanSession session) {
        progressLabel.setText(String.format("Pass %d — %d / %d images — %d duplicate(s), %d flagged for review",
            session.passNumber, session.processed(), session.totalImages(),
            session.duplicatePaths.size(), session.reviewRequired.size()));

        if (session.scanError != null && !session.scanError.isEmpty()) {
            // scanError can be a full sentence (e.g. the max-review-before-
            // stop explanation) — wrap it instead of letting a single long
            // line push the window wider than its content area.
            statusLabel.setText("<html><body style='width:520px'>Error: "
                + session.scanError.replace("&", "&amp;").replace("<", "&lt;") + "</body></html>");
            // Resumable in place, same as a plain Pause -- resumeScan()
            // clears scanError and continues from where it stopped.
            startButton.setText("Resume");
            startButton.setEnabled(true);
            pauseButton.setEnabled(false);
        } else if (finishRequested && !session.scanning) {
            String dir = countingService.getReportOutputDir();
            statusLabel.setText("<html><body style='width:520px'>Complete — " + session.processed()
                + " image(s) counted. Results written to: "
                + (dir != null ? dir.replace("&", "&amp;").replace("<", "&lt;") : "(unknown)")
                + "</body></html>");
            // Nothing left to count -- both buttons stay disabled (set by
            // runFinish()'s done() callback; re-asserted here too since
            // render() runs on every poll tick while a scan is active).
            startButton.setEnabled(false);
            pauseButton.setEnabled(false);
        } else if (session.scanning) {
            statusLabel.setText("Scanning… " + session.processed() + " / " + session.totalImages());
            startButton.setEnabled(false);
            pauseButton.setEnabled(true);
        } else if (session.stopRequested) {
            statusLabel.setText("Paused after " + session.processed() + " / " + session.totalImages() + " image(s)");
            startButton.setText("Resume");
            startButton.setEnabled(true);
            pauseButton.setEnabled(false);
        } else if (session.isStarted()) {
            // Scanning just finished naturally but finishRequested hasn't
            // been set yet -- pollStatus() is about to trigger runFinish()
            // in the background. Keep both buttons disabled through this
            // one transitional tick rather than briefly showing Start as
            // clickable, which would race a fresh startNewSession() against
            // finish() still reading/writing the same session.
            statusLabel.setText("Finishing…");
            startButton.setEnabled(false);
            pauseButton.setEnabled(false);
        } else {
            statusLabel.setText("Ready");
            startButton.setText("Start Counting");
            startButton.setEnabled(true);
            pauseButton.setEnabled(false);
        }
    }

    private void showError(String text) {
        messageLabel.setText(text);
    }

    private void clearMessage() {
        messageLabel.setText(" ");
    }

    private String currentUsername() {
        return authContext.getCurrentUser() != null ? authContext.getCurrentUser().getUsername() : "(unknown)";
    }

    /** Blocks (via the modal login dialog) until signed in, then shows the frame. Exits the JVM if login is abandoned. */
    void start() {
        if (!loginDialog.showAndAuthenticate(this)) {
            System.exit(0);
        }
        setVisible(true);
    }
}
