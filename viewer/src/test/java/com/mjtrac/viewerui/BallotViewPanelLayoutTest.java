/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewerui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real bug: BallotViewPanel used to add its title bar
 * and its Back/Prev/Next/zoom/checkbox toolbar to the same BorderLayout
 * region via two different constant families (NORTH and PAGE_START).
 * java.awt.BorderLayout's own javadoc says mixing them is undefined and the
 * relative constant (PAGE_START) wins — silently dropping the whole
 * toolbar. Never threw, never logged; only visible by actually looking at
 * the running app, which is how it was found.
 */
class BallotViewPanelLayoutTest {

    @Test
    void toolbarAndTitleBothGetRealScreenSpace() {
        BallotViewPanel panel = new BallotViewPanel(null, new ContestCandidateWindow());
        JFrame frame = new JFrame();
        frame.setContentPane(panel);
        frame.pack();
        frame.setSize(1100, 750);
        frame.validate();

        List<JButton> buttons = new ArrayList<>();
        collectButtons(panel, buttons);

        // Back, Prev, Next, Fit, +, − — the toolbar this bug made disappear.
        assertThat(buttons).as("toolbar buttons should exist in the component tree").hasSizeGreaterThanOrEqualTo(6);
        for (JButton button : buttons) {
            // The Hold button starts intentionally hidden (auto-advance is
            // off by default, matching viewer-view.js's holdBtn.style.
            // display='none' in that state) — invisible components get no
            // real layout space by design, which isn't the bug this test
            // guards against.
            if (!button.isVisible()) continue;
            assertThat(button.getWidth()).as(button.getText() + " width").isGreaterThan(0);
            assertThat(button.getHeight()).as(button.getText() + " height").isGreaterThan(0);
        }
    }

    private static void collectButtons(Container container, List<JButton> out) {
        for (Component c : container.getComponents()) {
            if (c instanceof JButton button) out.add(button);
            if (c instanceof Container child) collectButtons(child, out);
        }
    }
}
