/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Landing screen: numbered step cards mirroring bBuilder's dashboard.html
 * workflow guide (1. Elections through 7. Ballot Combinations, then Print).
 * Clicking a card navigates to that screen — this is the "hint" the user
 * asked for, showing the intended order without forcing it.
 *
 * Deliberately NOT a @Component: it needs a navigation callback that only
 * exists inside MainFrame (not a Spring-injectable dependency), so
 * MainFrame constructs it directly with `new HomePanel(this::navigate)`.
 * Marking this @Component as well as manually constructing it made Spring
 * ALSO try to build one via component scanning, failing at startup since
 * no Consumer<String> bean exists to satisfy the constructor — caught by
 * an actual run of the packaged app, not by the test suite (BuilderApp's
 * own Spring context, MainFrame included, is never built in tests — see
 * BuilderEndToEndTest's comment on why).
 */
class HomePanel extends JPanel {

    record Step(String number, String title, String description, String screen) {}

    private static final Step[] STEPS = {
        new Step("1", "Elections", "Create an election and set its type and date.", "Elections"),
        new Step("2", "Regions", "Create single precincts, then precinct groups (districts, cities, etc.) and assign precincts to them.", "Regions"),
        new Step("3", "Parties", "Add party entries for primaries, or use a nonpartisan default for general elections.", "Parties"),
        new Step("4", "Ballot Types", "Precinct, Mail-In, Absentee, Provisional, etc.", "Ballot Types"),
        new Step("5", "Contests & Candidates", "Add races and measures, assign each to regions, then add candidates.", "Contests"),
        new Step("6", "Design Templates", "Set paper size, vote indicator style, columns, fonts, and barcode position.", "Ballot Design Templates"),
        new Step("7", "Ballot Combinations", "Define each unique ballot variant: Election + Precinct + Party + Ballot Type.", "Ballot Combinations"),
    };

    HomePanel(Consumer<String> onNavigate) {
        super(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(16, 16, 16, 16));

        JLabel title = new JLabel("pbss Ballot Builder");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        add(title, BorderLayout.NORTH);

        JPanel steps = new JPanel();
        steps.setLayout(new BoxLayout(steps, BoxLayout.Y_AXIS));

        JLabel hint = new JLabel("Suggested order — complete steps 1–7, then Print. Screens are also reachable any time from the menus above.");
        hint.setForeground(Color.GRAY);
        hint.setBorder(new EmptyBorder(4, 0, 12, 0));
        steps.add(hint);

        for (Step s : STEPS) {
            steps.add(stepCard(s, onNavigate));
            steps.add(Box.createVerticalStrut(6));
        }

        Step print = new Step("→", "Print Ballot", "Select a ballot combination and generate a PDF. Requires steps 1–7 above to be complete.", "Print");
        steps.add(stepCard(print, onNavigate));

        JScrollPane scroll = new JScrollPane(steps);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    private static JComponent stepCard(Step s, Consumer<String> onNavigate) {
        JPanel card = new JPanel(new BorderLayout(12, 0));
        card.setBorder(new CompoundBorder(new LineBorder(new Color(0xdd, 0xdd, 0xdd)), new EmptyBorder(10, 12, 10, 12)));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel number = new JLabel(s.number());
        number.setFont(number.getFont().deriveFont(Font.BOLD, 18f));
        number.setForeground(new Color(0x1a, 0x4a, 0x7a));
        number.setPreferredSize(new Dimension(28, 0));
        card.add(number, BorderLayout.WEST);

        JPanel text = new JPanel(new GridLayout(2, 1));
        JLabel titleLabel = new JLabel(s.title());
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        JLabel descLabel = new JLabel("<html>" + s.description() + "</html>");
        descLabel.setForeground(Color.DARK_GRAY);
        text.add(titleLabel);
        text.add(descLabel);
        card.add(text, BorderLayout.CENTER);

        card.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                onNavigate.accept(s.screen());
            }
        });
        return card;
    }
}
