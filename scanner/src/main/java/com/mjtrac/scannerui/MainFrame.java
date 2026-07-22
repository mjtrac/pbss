/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.scannerui;

import com.mjtrac.scanner.config.ScannerConfig;
import com.mjtrac.scanner.model.ScanSession;
import com.mjtrac.scanner.service.ScanService;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * The entire UI: output folder (with default), start/end notes, Start/Stop,
 * live progress, and DPI/duplex controls shown only when the active backend
 * actually forwards them (see ScannerConfig.supportsDpi()/supportsDuplex()).
 * Gated by a ScannerUser login (LoginDialog) with every attempt written to
 * AuditLogService — same as counter. Mirrors ConfigController's pattern of
 * mutating the shared ScannerConfig bean's fields directly (in-memory only,
 * not persisted back to application.properties).
 */
@Component
public class MainFrame extends JFrame {

    private final ScanService scanService;
    private final ScannerConfig config;
    private final AuthContext authContext;
    private final LoginDialog loginDialog;

    private final JTextField outputDirField;
    private final JTextArea startNotesArea = new JTextArea(3, 40);
    private final JTextArea endNotesArea = new JTextArea(3, 40);
    private final JButton startButton = new JButton("Start Scan");
    private final JButton stopButton = new JButton("Stop");
    private final JButton saveEndNoteButton = new JButton("Save End Note");
    private final JButton printBatchSheetButton = new JButton("Print Batch Sheet");
    private final JLabel statusLabel = new JLabel("Ready");
    private final JLabel progressLabel = new JLabel(" ");
    private final JLabel messageLabel = new JLabel(" ");
    private final JSpinner dpiSpinner = new JSpinner(new SpinnerNumberModel(300, 72, 1200, 25));
    private final JCheckBox duplexCheckbox = new JCheckBox("Duplex");
    private final JPanel endNotesPanel;

    private Timer pollTimer;

    MainFrame(ScanService scanService, ScannerConfig config, AuthContext authContext, LoginDialog loginDialog) {
        super("pbss Scanner v" + Launcher.readVersion());
        this.scanService = scanService;
        this.config = config;
        this.authContext = authContext;
        this.loginDialog = loginDialog;
        this.outputDirField = new JTextField(config.outputDir, 34);
        // Clamped, not the raw property value: dpiSpinner's model only
        // accepts [72, 1200] (see its field declaration above), and
        // SpinnerNumberModel.setValue() throws IllegalArgumentException for
        // anything outside that — a scanner.dpi override outside that range
        // in application.properties would otherwise crash this window at
        // construction instead of just clamping to something sane. Same
        // bug class confirmed for real in builder's Contest candidates
        // table (see ContestCandidatesDialog's Order spinner) and its
        // ballot design template screen, both driven by persisted data
        // rather than a properties override, but the underlying model-
        // bounds mismatch is identical.
        this.dpiSpinner.setValue(Math.max(72, Math.min(config.dpi, 1200)));
        this.duplexCheckbox.setSelected(config.duplex);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel titleBlock = PbssTheme.titleBlock("pbss Scanner");
        // Stretch to the full row width, same as every field below it —
        // capping only height, not width, to Integer.MAX_VALUE.
        titleBlock.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleBlock.getPreferredSize().height));
        root.add(titleBlock);

        root.add(labeled("Output folder:", folderRow(outputDirField)));
        root.add(Box.createVerticalStrut(8));

        JPanel scanSettings = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        if (config.supportsDpi()) {
            scanSettings.add(new JLabel("DPI:"));
            scanSettings.add(dpiSpinner);
        }
        if (config.supportsDuplex()) {
            scanSettings.add(duplexCheckbox);
        }
        if (scanSettings.getComponentCount() > 0) {
            root.add(scanSettings);
            root.add(Box.createVerticalStrut(8));
        }

        root.add(labeled("Start notes (picked up when the scan starts or restarts):",
            new JScrollPane(startNotesArea)));
        root.add(Box.createVerticalStrut(8));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(startButton);
        buttons.add(stopButton);
        buttons.add(printBatchSheetButton);
        root.add(buttons);
        root.add(Box.createVerticalStrut(8));

        root.add(statusLabel);
        root.add(progressLabel);
        messageLabel.setForeground(new Color(0xdc, 0x26, 0x26));
        root.add(messageLabel);
        root.add(Box.createVerticalStrut(8));

        endNotesPanel = new JPanel();
        endNotesPanel.setLayout(new BoxLayout(endNotesPanel, BoxLayout.Y_AXIS));
        endNotesPanel.add(labeled("End notes (e.g. flag a misfeed/doublefeed for manual correction):",
            new JScrollPane(endNotesArea)));
        JPanel saveRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        saveRow.add(saveEndNoteButton);
        endNotesPanel.add(saveRow);
        endNotesPanel.setVisible(false);
        root.add(endNotesPanel);

        setContentPane(root);
        pack();
        setLocationRelativeTo(null);

        stopButton.setEnabled(false);
        startButton.addActionListener(e -> handleStart());
        stopButton.addActionListener(e -> handleStop());
        saveEndNoteButton.addActionListener(e -> handleSaveEndNote());
        printBatchSheetButton.addActionListener(e -> handlePrintBatchSheet());
    }

    private JPanel labeled(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(new JLabel(label), BorderLayout.NORTH);
        p.add(field, BorderLayout.CENTER);
        return p;
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

    private void handleStart() {
        clearMessage();
        config.outputDir = outputDirField.getText();
        if (config.supportsDpi())    config.dpi    = (Integer) dpiSpinner.getValue();
        if (config.supportsDuplex()) config.duplex = duplexCheckbox.isSelected();

        try {
            scanService.startScan(startNotesArea.getText());
        } catch (IllegalStateException ex) {
            showError(ex.getMessage());
            return;
        }
        endNotesPanel.setVisible(false);
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        pack();
        startPolling();
    }

    private void handleStop() {
        scanService.stopScan();
        stopButton.setEnabled(false);
    }

    private void handleSaveEndNote() {
        String note = endNotesArea.getText();
        if (note.isBlank()) return;
        scanService.saveEndNote(note);
        endNotesArea.setText("");
        messageLabel.setForeground(new Color(0x16, 0xa3, 0x4a));
        messageLabel.setText("End note saved.");
    }

    /** Manual, user-clicked print — shows the system print dialog, unlike scanner-core's automatic flag pages. */
    private void handlePrintBatchSheet() {
        String error = BatchSheetPrinter.print(startNotesArea.getText(), endNotesArea.getText());
        if (error != null) {
            messageLabel.setForeground(new Color(0xdc, 0x26, 0x26));
            messageLabel.setText(error);
        }
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
        ScanSession session = scanService.getSession();
        render(session);
        if (!session.scanning && (session.complete || session.error != null)) {
            stopPolling();
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            if (session.complete) {
                endNotesPanel.setVisible(true);
                pack();
            }
        }
    }

    private void render(ScanSession session) {
        progressLabel.setText(session.imagesScanned + " image(s) scanned"
            + (session.lastFile != null ? " — last: " + session.lastFile : ""));

        if (session.error != null && !session.error.isEmpty()) {
            statusLabel.setText("Error: " + session.error);
        } else if (session.scanning) {
            statusLabel.setText("Scanning…");
        } else if (session.complete) {
            statusLabel.setText("Complete — " + session.imagesScanned + " image(s)");
        } else {
            statusLabel.setText("Ready");
        }
    }

    private void showError(String text) {
        messageLabel.setForeground(new Color(0xdc, 0x26, 0x26));
        messageLabel.setText(text);
    }

    private void clearMessage() {
        messageLabel.setText(" ");
    }

    /** Blocks (via the modal login dialog) until signed in, then shows the frame. Exits the JVM if login is abandoned. */
    void start() {
        if (!loginDialog.showAndAuthenticate(this)) {
            System.exit(0);
        }
        setVisible(true);
    }
}
