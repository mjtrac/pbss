/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.viewerui;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * pbss's signature element: a row of small dots evoking a paper ballot's
 * perforated tear-off stub — grounded in the actual subject rather than a
 * generic rule or gradient. Used once, consistently, as the divider under
 * this app's screen title (see PbssTheme.titleBlock()).
 */
final class PerforationDivider extends JComponent {

    private static final int DOT_DIAMETER = 4;
    private static final int GAP = 6;

    PerforationDivider() {
        setOpaque(false);
    }

    @Override public Dimension getPreferredSize() { return new Dimension(40, DOT_DIAMETER); }
    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, DOT_DIAMETER); }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(PbssTheme.RULE);
        int y = (getHeight() - DOT_DIAMETER) / 2;
        for (int x = 0; x + DOT_DIAMETER <= getWidth(); x += DOT_DIAMETER + GAP) {
            g2.fillOval(x, y, DOT_DIAMETER, DOT_DIAMETER);
        }
        g2.dispose();
    }
}
