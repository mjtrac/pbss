/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewerui;

import com.mjtrac.viewer.service.BallotViewService;
import com.mjtrac.viewer.service.BallotViewService.BallotView;
import com.mjtrac.viewer.service.BallotViewService.IndicatorBox;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Single-ballot view: image + overlay boxes, Next/Prev, zoom, show/hide
 * toggles, and auto-advance — a timer-driven "play through the ballots"
 * mode ported from viewer-view.js's identically-named feature (checkbox +
 * seconds + progress bar + Hold button + n/p/h keyboard shortcuts), the
 * same JS bCounter's and blCounter's web viewer both use (blCounter embeds
 * that exact page in a WebView rather than having its own JavaFX port — see
 * ViewerScreenController). Re-implemented here with javax.swing.Timer since
 * Swing has no direct equivalent to reuse, but the state machine (100ms
 * tick, hold resets the countdown without disabling auto-advance, stops at
 * the end of the list) matches the JS version's behavior exactly.
 */
@Component
class BallotViewPanel extends JPanel {

    private final BallotViewService viewService;
    private final ContestCandidateWindow contestCandidateWindow;
    private final OverlayImagePanel canvas = new OverlayImagePanel();
    private final JScrollPane scroll = new JScrollPane(canvas);

    // JTextField (not JLabel) so the displayed name/path can be
    // drag-selected and Ctrl+C'd — same convention as
    // BallotListPanel's selectedPathField/SQL help dialog.
    private final JTextField titleField = new JTextField();
    private boolean showFullPath = false;
    private final JLabel positionLabel = new JLabel();
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton backBtn = new JButton("← Back to list");
    private final JButton prevBtn = new JButton("← Prev");
    private final JButton nextBtn = new JButton("Next →");
    private final JButton fitBtn = new JButton("Fit");
    private final JButton zoomInBtn = new JButton("+");
    private final JButton zoomOutBtn = new JButton("−");
    private final JLabel zoomLabel = new JLabel("100%");
    private final JCheckBox showBoxesBox = new JCheckBox("Boxes", true);
    private final JCheckBox showNamesBox = new JCheckBox("Names", true);

    private final JCheckBox autoAdvanceBox = new JCheckBox("Auto-advance");
    private final JSpinner autoSecondsSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 300, 1));
    private final JProgressBar autoProgressBar = new JProgressBar(0, 100);
    private final JButton holdBtn = new JButton("⏸ Hold");
    private javax.swing.Timer autoTimer;
    private int autoElapsedMs = 0;
    private boolean held = false;

    private List<Long> ids = List.of();
    private int index = -1;
    private Runnable onBack = () -> {};

    BallotViewPanel(BallotViewService viewService, ContestCandidateWindow contestCandidateWindow) {
        super(new BorderLayout());
        this.viewService = viewService;
        this.contestCandidateWindow = contestCandidateWindow;

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        top.add(backBtn);
        top.add(new JSeparator(SwingConstants.VERTICAL));
        top.add(prevBtn);
        top.add(positionLabel);
        top.add(nextBtn);
        top.add(new JSeparator(SwingConstants.VERTICAL));
        top.add(zoomOutBtn);
        top.add(zoomLabel);
        top.add(zoomInBtn);
        top.add(fitBtn);
        top.add(showBoxesBox);
        top.add(showNamesBox);

        titleField.setEditable(false);
        titleField.setDragEnabled(true);
        titleField.setBorder(null);
        titleField.setOpaque(false);

        JPanel autoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        autoRow.add(autoAdvanceBox);
        autoRow.add(new JLabel("every"));
        // Capped to a 3-digit-ish width instead of the default full-stretch
        // a spinner gets when placed in some layouts — same reasoning as
        // builder's SimpleCrudPanel.addField()/PrintPanel.addRow() this
        // session: most numeric spinners only need a handful of digits.
        FontMetrics secondsFm = autoSecondsSpinner.getFontMetrics(autoSecondsSpinner.getFont());
        int secondsWidth = secondsFm.stringWidth("999") + 36;
        Dimension secondsSize = new Dimension(secondsWidth, autoSecondsSpinner.getPreferredSize().height);
        autoSecondsSpinner.setPreferredSize(secondsSize);
        autoSecondsSpinner.setMinimumSize(secondsSize);
        autoRow.add(autoSecondsSpinner);
        autoRow.add(new JLabel("sec"));
        autoProgressBar.setPreferredSize(new Dimension(140, autoProgressBar.getPreferredSize().height));
        autoProgressBar.setStringPainted(false);
        autoRow.add(autoProgressBar);
        holdBtn.setVisible(false);
        autoRow.add(holdBtn);

        // top and autoRow must share ONE BorderLayout region, not two —
        // mixing the absolute (NORTH) and relative (PAGE_START) constant
        // families in the same container is explicitly undefined per
        // BorderLayout's javadoc ("the relative constants will take
        // precedence... the [absolute] constant will be ignored"), which
        // silently dropped the whole toolbar (Back/Prev/Next/zoom/
        // checkboxes) in practice.
        JPanel northStack = new JPanel();
        northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
        northStack.add(top);
        northStack.add(autoRow);
        add(northStack, BorderLayout.NORTH);

        scroll.getViewport().setBackground(Color.DARK_GRAY);
        add(scroll, BorderLayout.CENTER);

        // Filename/path on the left (flexible width, for the long ones) and
        // hover status on the right — one status line instead of a separate
        // title bar up top, per the user's request to move it down here.
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        titleField.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.add(titleField, BorderLayout.CENTER);
        bottom.add(statusLabel, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        backBtn.addActionListener(e -> { stopAutoTimer(); onBack.run(); });
        prevBtn.addActionListener(e -> step(-1));
        nextBtn.addActionListener(e -> step(1));
        fitBtn.addActionListener(e -> fitToViewport());
        zoomInBtn.addActionListener(e -> setScale(canvas.getScaleValue() + 0.1));
        zoomOutBtn.addActionListener(e -> setScale(canvas.getScaleValue() - 0.1));
        showBoxesBox.addActionListener(e -> canvas.setShowBoxes(showBoxesBox.isSelected()));
        showNamesBox.addActionListener(e -> canvas.setShowNames(showNamesBox.isSelected()));
        canvas.setOnHover(this::showHoverInfo);
        canvas.setOnActivate(contestCandidateWindow::selectBox);
        contestCandidateWindow.setOnCandidateSelected(box -> canvas.setActiveBoxId(box.id));

        autoAdvanceBox.addActionListener(e -> onAutoAdvanceToggled());
        autoSecondsSpinner.addChangeListener(e -> { if (autoTimer != null) startAutoTimer(); });
        holdBtn.addActionListener(e -> toggleHold());
        bindKeyboardShortcuts();
    }

    /** Same n/p/h shortcuts as viewer-view.js, bound window-wide so focus on any child component doesn't block them. */
    private void bindKeyboardShortcuts() {
        InputMap im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getActionMap();
        im.put(KeyStroke.getKeyStroke('n'), "nextBallot");
        im.put(KeyStroke.getKeyStroke('N'), "nextBallot");
        am.put("nextBallot", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { step(1); }
        });
        im.put(KeyStroke.getKeyStroke('p'), "prevBallot");
        im.put(KeyStroke.getKeyStroke('P'), "prevBallot");
        am.put("prevBallot", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { step(-1); }
        });
        im.put(KeyStroke.getKeyStroke('h'), "holdAuto");
        im.put(KeyStroke.getKeyStroke('H'), "holdAuto");
        am.put("holdAuto", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (autoAdvanceBox.isSelected()) toggleHold();
            }
        });
    }

    void setOnBack(Runnable onBack) { this.onBack = onBack; }

    /** Carries BallotListPanel's "Show full path" toggle through to this screen's title. */
    void setShowFullPath(boolean showFullPath) { this.showFullPath = showFullPath; }

    void load(Long id, List<Long> ids) {
        this.ids = ids;
        this.index = ids.indexOf(id);
        if (this.index < 0) this.index = 0;
        showCurrent(true);
    }

    private void step(int delta) {
        int next = index + delta;
        if (next < 0 || next >= ids.size()) return;
        index = next;
        showCurrent(false);
    }

    // ── Auto-advance ─────────────────────────────────────────────────────
    // Ported from viewer-view.js's auto-advance feature (same one bCounter's
    // and blCounter's web viewer share — blCounter just embeds that page in
    // a WebView). 100ms tick matches the JS version; "held" resets the
    // countdown but keeps auto-advance enabled, matching its Hold button.

    private void onAutoAdvanceToggled() {
        held = false;
        holdBtn.setVisible(autoAdvanceBox.isSelected());
        holdBtn.setText("⏸ Hold");
        if (autoAdvanceBox.isSelected()) startAutoTimer(); else stopAutoTimer();
    }

    private void toggleHold() {
        held = !held;
        if (held) {
            holdBtn.setText("▶ Resume");
            if (autoTimer != null) autoTimer.stop();
        } else {
            holdBtn.setText("⏸ Hold");
            if (autoAdvanceBox.isSelected()) startAutoTimer();
        }
    }

    private void startAutoTimer() {
        stopAutoTimer();
        if (index >= ids.size() - 1) return; // nothing to advance to, same as JS's NEXT_ID === null check
        autoElapsedMs = 0;
        int seconds = (Integer) autoSecondsSpinner.getValue();
        autoProgressBar.setVisible(true);
        autoTimer = new javax.swing.Timer(100, e -> {
            autoElapsedMs += 100;
            autoProgressBar.setValue(Math.min(100, (int) (100L * autoElapsedMs / (seconds * 1000))));
            if (autoElapsedMs >= seconds * 1000) {
                if (!held) {
                    // Stop first, same order as the JS version (stopAutoTimer()
                    // then goNext()) — showCurrent() restarts a fresh timer for
                    // the new ballot if it actually advances; if this was the
                    // last ballot, step() no-ops and auto-advance just stays
                    // stopped, matching goNext() no-op-ing when NEXT_ID is null.
                    stopAutoTimer();
                    step(1);
                } else {
                    autoElapsedMs = 0;
                    autoProgressBar.setValue(0);
                }
            }
        });
        autoTimer.start();
    }

    private void stopAutoTimer() {
        if (autoTimer != null) { autoTimer.stop(); autoTimer = null; }
        autoProgressBar.setVisible(false);
        autoProgressBar.setValue(0);
    }

    private void showCurrent(boolean resetZoom) {
        prevBtn.setEnabled(index > 0);
        nextBtn.setEnabled(index < ids.size() - 1);
        positionLabel.setText((index + 1) + " of " + ids.size());
        statusLabel.setText(" ");

        // Same as viewer-view.js's restoreAutoTimer(), called unconditionally
        // after every navigation (auto or manual) when auto-advance is
        // checked — "held" only matters once a countdown actually completes
        // (see startAutoTimer()'s tick callback), not when starting one.
        if (autoAdvanceBox.isSelected()) startAutoTimer();

        var opt = viewService.findById(ids.get(index));
        if (opt.isEmpty()) {
            titleField.setText("(ballot not found)");
            return;
        }
        BallotView view = opt.get();
        titleField.setText(showFullPath ? view.imagePath : view.imageName);
        titleField.setCaretPosition(0);
        contestCandidateWindow.update(view.boxes);

        try {
            BufferedImage img = ImageIO.read(new File(view.resolvedPath));
            if (img == null) throw new IOException("Unsupported or unreadable image format");
            canvas.load(view, img);
            if (resetZoom) {
                SwingUtilities.invokeLater(this::fitToViewport);
            } else {
                setScale(canvas.getScaleValue());
            }
        } catch (IOException e) {
            statusLabel.setText("Could not load image: " + view.resolvedPath + " (" + e.getMessage() + ")");
        }
    }

    private void fitToViewport() {
        Dimension avail = scroll.getViewport().getExtentSize();
        double fit = canvas.fitScale(avail.width - 4, avail.height - 4);
        setScale(fit > 0 ? fit : 1.0);
    }

    private void setScale(double scale) {
        canvas.setScale(scale);
        zoomLabel.setText(Math.round(canvas.getScaleValue() * 100) + "%");
        canvas.revalidate();
    }

    private void showHoverInfo(IndicatorBox box) {
        statusLabel.setText(box == null ? " "
            : box.contest + " — " + box.label + " — " + box.statusLabel);
    }
}
