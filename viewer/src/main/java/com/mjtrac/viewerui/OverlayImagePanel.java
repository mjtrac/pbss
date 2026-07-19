/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewerui;

import com.mjtrac.viewer.service.BallotViewService.BallotView;
import com.mjtrac.viewer.service.BallotViewService.IndicatorBox;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;

/**
 * Draws the ballot image plus color-coded, homography-transformed indicator
 * box overlays — the Swing equivalent of viewer-view.js's canvas rendering,
 * without any WebView/HTML layer involved.
 */
class OverlayImagePanel extends JPanel {

    private static final Color VOTED     = new Color(0x22, 0xc5, 0x5e);
    private static final Color OVERVOTED = new Color(0xea, 0xb3, 0x08);
    private static final Color UNMARKED  = new Color(0x3b, 0x82, 0xf6);

    private BufferedImage image;
    private List<IndicatorBox> boxes = List.of();
    private Homography homography;
    private double scale = 1.0;
    private boolean showBoxes = true;
    private boolean showNames = true;
    private Long activeId = null;
    private Long hoverId = null;

    private Consumer<IndicatorBox> onHover = b -> {};

    OverlayImagePanel() {
        setBackground(Color.DARK_GRAY);
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                IndicatorBox b = boxAt(e.getPoint());
                activeId = (b != null && b.id == (activeId == null ? -1 : activeId)) ? null
                         : (b != null ? b.id : null);
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                IndicatorBox b = boxAt(e.getPoint());
                Long id = b == null ? null : b.id;
                if (!java.util.Objects.equals(id, hoverId)) {
                    hoverId = id;
                    onHover.accept(b);
                    repaint();
                }
            }
        });
    }

    void setOnHover(Consumer<IndicatorBox> onHover) {
        this.onHover = onHover;
    }

    void load(BallotView view, BufferedImage img) {
        this.image  = img;
        this.boxes  = view.boxes;
        this.activeId = null;
        this.hoverId  = null;
        this.homography = Homography.build(
            view.cornerMarks, view.canonicalWidth, view.canonicalHeight,
            view.wasRotated, img.getWidth(), img.getHeight(), view.dpi, view.warpDpi);
        revalidate();
        repaint();
    }

    void setScale(double scale) {
        this.scale = Math.max(0.05, Math.min(8.0, scale));
        revalidate();
        repaint();
    }

    double getScaleValue() { return scale; }

    void setShowBoxes(boolean v) { this.showBoxes = v; repaint(); }
    void setShowNames(boolean v) { this.showNames = v; repaint(); }

    /** Computes a scale that fits the image within the given viewport size. */
    double fitScale(int viewportW, int viewportH) {
        if (image == null || viewportW <= 0 || viewportH <= 0) return 1.0;
        return Math.min((double) viewportW / image.getWidth(), (double) viewportH / image.getHeight());
    }

    private IndicatorBox boxAt(Point p) {
        if (!showBoxes || image == null) return null;
        for (IndicatorBox box : boxes) {
            Rectangle2D.Double r = homography.imageRect(box.x, box.y, box.w, box.h);
            Rectangle2D.Double scaled = new Rectangle2D.Double(
                r.x * scale, r.y * scale, r.width * scale, r.height * scale);
            if (scaled.contains(p)) return box;
        }
        return null;
    }

    @Override public Dimension getPreferredSize() {
        if (image == null) return new Dimension(400, 300);
        return new Dimension((int) Math.round(image.getWidth() * scale),
                              (int) Math.round(image.getHeight() * scale));
    }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        if (image == null) return;
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int w = (int) Math.round(image.getWidth() * scale);
        int h = (int) Math.round(image.getHeight() * scale);
        g.drawImage(image, 0, 0, w, h, null);

        if (showBoxes) {
            for (IndicatorBox box : boxes) drawBox(g, box);
        }
        if (showNames) {
            for (IndicatorBox box : boxes) drawLabel(g, box);
        }
    }

    private void drawBox(Graphics2D g, IndicatorBox box) {
        Rectangle2D.Double r = scaledRect(box);
        Color color = colorFor(box.status);
        boolean active = box.id == (activeId == null ? -1 : activeId);
        boolean hover  = box.id == (hoverId == null ? -1 : hoverId);

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, active ? 0.35f : 0.18f));
        g.setColor(color);
        g.fill(r);

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, active ? 1.0f : (hover ? 0.9f : 0.75f)));
        g.setStroke(new BasicStroke(active ? 3f : 2f));
        g.draw(r);
        g.setComposite(AlphaComposite.SrcOver);
    }

    private void drawLabel(Graphics2D g, IndicatorBox box) {
        Rectangle2D.Double r = scaledRect(box);
        Color color = colorFor(box.status);
        int fontSize = (int) Math.max(8, Math.min(13, r.height * 0.7));
        g.setFont(g.getFont().deriveFont(Font.BOLD, fontSize));
        FontMetrics fm = g.getFontMetrics();
        int textW = fm.stringWidth(box.label);
        int padX = 3, padY = 2;
        int lx = (int) r.x, ly = (int) (r.y - fontSize - padY * 2);

        g.setColor(color);
        g.fillRect(lx, ly, textW + padX * 2, fontSize + padY * 2);
        g.setColor(Color.BLACK);
        g.drawString(box.label, lx + padX, ly + fontSize + padY - fm.getDescent());
    }

    private Rectangle2D.Double scaledRect(IndicatorBox box) {
        Rectangle2D.Double r = homography.imageRect(box.x, box.y, box.w, box.h);
        return new Rectangle2D.Double(r.x * scale, r.y * scale, r.width * scale, r.height * scale);
    }

    private static Color colorFor(String status) {
        return switch (status) {
            case "VOTED"     -> VOTED;
            case "OVERVOTED" -> OVERVOTED;
            default           -> UNMARKED;
        };
    }
}
