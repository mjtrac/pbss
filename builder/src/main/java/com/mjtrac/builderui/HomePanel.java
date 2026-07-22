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
 * asked for, showing the intended order without forcing it. Each card also
 * has a collapsed-by-default "?" toggle revealing a longer explanation, for
 * a new user who needs more than the one-line description to get oriented.
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

    record Step(String number, String title, String description, String details, String screen) {}

    private static final Step[] STEPS = {
        new Step("1", "Elections",
            "Create an election and set its type and date.",
            "An election bundles everything below it — contests, ballot combinations, and "
            + "design templates all point at one election. Most jurisdictions have exactly one "
            + "election in progress at a time, but you can maintain several side by side (e.g. a "
            + "primary and the general that follows it).",
            "Elections"),
        new Step("2", "Regions",
            "Create single precincts, then precinct groups (districts, cities, etc.) and assign precincts to them.",
            "Precincts are the smallest geographic unit; precinct groups (districts, cities, wards) "
            + "bundle precincts together for contests that only apply to part of the jurisdiction. If "
            + "every voter gets an identical ballot, you only need one region — open this screen and "
            + "use \"Use Single Region\" to set that up in one step.",
            "Regions"),
        new Step("3", "Parties",
            "Add party entries for primaries, or use a nonpartisan default for general elections.",
            "Only needed for primary elections where each party has its own candidate list. For a "
            + "general or nonpartisan election, one party covers everyone — open this screen and use "
            + "\"Use Single Party\" to set that up in one step.",
            "Parties"),
        new Step("4", "Ballot Types",
            "Precinct, Mail-In, Absentee, Provisional, etc.",
            "Distinguishes how a ballot was cast. Most jurisdictions need at least Precinct and "
            + "Mail-In; each Ballot Combination in step 7 pairs one Ballot Type with one Region and Party.",
            "Ballot Types"),
        new Step("5", "Contests & Candidates",
            "Add races and measures, assign each to regions, then add candidates.",
            "Each race or ballot measure, assigned to the regions where it applies, with its "
            + "candidates in print order. Ranked-choice contests list candidates the same way — rank "
            + "boxes are added automatically at print time.",
            "Contests"),
        new Step("6", "Design Templates",
            "Set paper size, vote indicator style, columns, fonts, and barcode position.",
            "Paper size, column count, font sizes, and vote-indicator style (oval, rectangle, or "
            + "connect-the-dots) — this is the visual layout counter/bCounter expect when reading marks "
            + "back, so changing it after ballots are printed means re-printing them.",
            "Ballot Design Templates"),
        new Step("7", "Ballot Combinations",
            "Define each unique ballot variant: Election + Precinct + Party + Ballot Type.",
            "Every unique ballot variant your voters could receive — one row per Election + Precinct "
            + "+ Party + Ballot Type. A simple nonpartisan single-precinct election has exactly one "
            + "combination.",
            "Ballot Combinations"),
    };

    HomePanel(Consumer<String> onNavigate, Color[] cardColors) {
        super(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(16, 16, 16, 16));

        add(PbssTheme.titleBlock("pbss Ballot Builder"), BorderLayout.NORTH);

        JPanel steps = new JPanel();
        steps.setLayout(new BoxLayout(steps, BoxLayout.Y_AXIS));

        JLabel hint = new JLabel("Suggested order — complete steps 1–7, then Print. Screens are also reachable any time from the menus above.");
        hint.setForeground(Color.GRAY);
        hint.setBorder(new EmptyBorder(4, 0, 12, 0));
        steps.add(hint);

        int cardIndex = 0;
        for (Step s : STEPS) {
            steps.add(stepCard(s, onNavigate, steps, cardColors[cardIndex++ % cardColors.length]));
            steps.add(Box.createVerticalStrut(6));
        }

        Step print = new Step("→", "Print Ballot",
            "Select a ballot combination and generate a PDF. Requires steps 1–7 above to be complete.",
            "Generates the actual ballot PDF, plus the YAML layout file counter/bCounter/viewer read "
            + "back to know where each vote indicator is on the printed page.",
            "Print");
        steps.add(stepCard(print, onNavigate, steps, cardColors[cardIndex % cardColors.length]));

        JScrollPane scroll = new JScrollPane(steps);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    private static JComponent stepCard(Step s, Consumer<String> onNavigate, JPanel stepsContainer, Color background) {
        JPanel card = new JPanel(new BorderLayout(12, 0));
        card.setBorder(new CompoundBorder(new LineBorder(PbssTheme.RULE), new EmptyBorder(10, 12, 10, 12)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.setBackground(background);
        card.setOpaque(true);

        JLabel number = new JLabel(s.number());
        number.setFont(number.getFont().deriveFont(Font.BOLD, 18f));
        number.setForeground(PbssTheme.TEAL_DARK);
        number.setPreferredSize(new Dimension(28, 0));
        card.add(number, BorderLayout.WEST);

        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        JLabel titleLabel = new JLabel(s.title());
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        JLabel descLabel = new JLabel("<html>" + s.description() + "</html>");
        descLabel.setForeground(Color.DARK_GRAY);
        text.add(titleLabel);
        text.add(descLabel);
        card.add(text, BorderLayout.CENTER);

        JButton toggle = new JButton("?");
        toggle.setToolTipText("Show/hide a longer explanation of this step");
        toggle.setMargin(new Insets(2, 8, 2, 8));
        toggle.setFocusPainted(false);
        card.add(toggle, BorderLayout.EAST);

        JLabel detailsLabel = new JLabel("<html><div style='width:520px'>" + s.details() + "</div></html>");
        detailsLabel.setForeground(new Color(0x44, 0x44, 0x44));
        detailsLabel.setBorder(new EmptyBorder(8, 0, 0, 0));
        detailsLabel.setVisible(false);

        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.setBorder(card.getBorder());
        card.setBorder(null);
        wrapper.add(card);
        wrapper.add(detailsLabel);
        // Cap max height to the current preferred height — otherwise
        // BoxLayout(Y_AXIS) stretches every card to fill leftover vertical
        // space in the scroll pane. Recomputed on toggle since preferred
        // height changes once the details label becomes visible.
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, wrapper.getPreferredSize().height));

        toggle.addActionListener(e -> {
            boolean expanding = !detailsLabel.isVisible();
            detailsLabel.setVisible(expanding);
            toggle.setText(expanding ? "▾" : "?");
            wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, wrapper.getPreferredSize().height));
            // Deferred, not synchronous: validating immediately, while still
            // inside the button's own click-event dispatch, made the
            // scrollpane jump to a wrong position (a real reentrancy issue,
            // not just a screenshot-tool quirk) — this is the standard,
            // safe way to ask for a re-layout after changing a component's
            // visibility/size.
            SwingUtilities.invokeLater(() -> {
                stepsContainer.revalidate();
                stepsContainer.repaint();
            });
        });

        card.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                onNavigate.accept(s.screen());
            }
        });
        return wrapper;
    }
}
