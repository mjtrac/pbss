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

/** Single-ballot view: image + overlay boxes, Next/Prev, zoom, show/hide toggles. */
@Component
class BallotViewPanel extends JPanel {

    private final BallotViewService viewService;
    private final OverlayImagePanel canvas = new OverlayImagePanel();
    private final JScrollPane scroll = new JScrollPane(canvas);

    private final JLabel titleLabel = new JLabel();
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

    private List<Long> ids = List.of();
    private int index = -1;
    private Runnable onBack = () -> {};

    BallotViewPanel(BallotViewService viewService) {
        super(new BorderLayout());
        this.viewService = viewService;

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

        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        titleBar.add(titleLabel, BorderLayout.WEST);

        // titleBar and top must share ONE BorderLayout region, not two —
        // mixing the absolute (NORTH) and relative (PAGE_START) constant
        // families in the same container is explicitly undefined per
        // BorderLayout's javadoc ("the relative constants will take
        // precedence... the [absolute] constant will be ignored"), which
        // silently dropped the whole toolbar (Back/Prev/Next/zoom/
        // checkboxes) in practice.
        JPanel northStack = new JPanel();
        northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
        northStack.add(titleBar);
        northStack.add(top);
        add(northStack, BorderLayout.NORTH);

        scroll.getViewport().setBackground(Color.DARK_GRAY);
        add(scroll, BorderLayout.CENTER);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(statusLabel, BorderLayout.SOUTH);

        backBtn.addActionListener(e -> onBack.run());
        prevBtn.addActionListener(e -> step(-1));
        nextBtn.addActionListener(e -> step(1));
        fitBtn.addActionListener(e -> fitToViewport());
        zoomInBtn.addActionListener(e -> setScale(canvas.getScaleValue() + 0.1));
        zoomOutBtn.addActionListener(e -> setScale(canvas.getScaleValue() - 0.1));
        showBoxesBox.addActionListener(e -> canvas.setShowBoxes(showBoxesBox.isSelected()));
        showNamesBox.addActionListener(e -> canvas.setShowNames(showNamesBox.isSelected()));
        canvas.setOnHover(this::showHoverInfo);
    }

    void setOnBack(Runnable onBack) { this.onBack = onBack; }

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

    private void showCurrent(boolean resetZoom) {
        prevBtn.setEnabled(index > 0);
        nextBtn.setEnabled(index < ids.size() - 1);
        positionLabel.setText((index + 1) + " of " + ids.size());
        statusLabel.setText(" ");

        var opt = viewService.findById(ids.get(index));
        if (opt.isEmpty()) {
            titleLabel.setText("(ballot not found)");
            return;
        }
        BallotView view = opt.get();
        titleLabel.setText(view.imageName);

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
