/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;

/**
 * Persistent shell: top menu bar + swappable content area, mirroring
 * bBuilder's web layout (layout.html's Setup/Ballots/Admin nav groups) and
 * dashboard.html's numbered-step landing page — shown here as HomePanel.
 */
@Component
public class MainFrame extends JFrame {

    private static final String HOME = "Home";

    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);

    MainFrame(JurisdictionPanel jurisdictionPanel, PartyPanel partyPanel,
              BallotTypePanel ballotTypePanel, BallotLanguagePanel ballotLanguagePanel,
              RegionPanel regionPanel, AdminPanel adminPanel,
              ElectionPanel electionPanel, BallotCombinationPanel ballotCombinationPanel,
              BallotDesignTemplatePanel ballotDesignTemplatePanel, ContestPanel contestPanel,
              PrintPanel printPanel,
              @Value("${dashboard.card.colors:" + PbssTheme.DEFAULT_DASHBOARD_COLORS + "}") String dashboardCardColors) {
        super("pbss Ballot Builder v" + Launcher.readVersion());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 780);
        setLocationRelativeTo(null);

        addScreen(HOME, new HomePanel(this::navigate, PbssTheme.parsePalette(dashboardCardColors)));
        addScreen("Elections", electionPanel);
        addScreen("Regions", regionPanel);
        addScreen("Parties", partyPanel);
        addScreen("Ballot Types", ballotTypePanel);
        addScreen("Contests", contestPanel);
        addScreen("Languages", ballotLanguagePanel);
        addScreen("Ballot Combinations", ballotCombinationPanel);
        addScreen("Ballot Design Templates", ballotDesignTemplatePanel);
        addScreen("Print", printPanel);
        addScreen("Jurisdictions", jurisdictionPanel);
        addScreen("Admin (Users)", adminPanel);

        setJMenuBar(buildMenuBar());

        JPanel root = new JPanel(new BorderLayout());
        root.add(content, BorderLayout.CENTER);
        setContentPane(root);

        navigate(HOME);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu home = new JMenu(HOME);
        home.setName("homeMenu");
        JMenuItem dashboard = new JMenuItem("Dashboard");
        dashboard.setName("dashboardMenuItem");
        dashboard.addActionListener(e -> navigate(HOME));
        JMenuItem exit = new JMenuItem("Exit");
        exit.setName("exitMenuItem");
        exit.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));
        home.add(dashboard);
        home.addSeparator();
        home.add(exit);
        bar.add(home);

        JMenu setup = new JMenu("Setup");
        setup.setName("setupMenu");
        addItem(setup, "Elections");
        addItem(setup, "Regions");
        addItem(setup, "Parties");
        addItem(setup, "Ballot Types");
        addItem(setup, "Contests");
        addItem(setup, "Languages");
        bar.add(setup);

        JMenu ballots = new JMenu("Ballots");
        ballots.setName("ballotsMenu");
        addItem(ballots, "Ballot Combinations");
        addItem(ballots, "Ballot Design Templates");
        addItem(ballots, "Print");
        bar.add(ballots);

        JMenu admin = new JMenu("Admin");
        admin.setName("adminMenu");
        addItem(admin, "Jurisdictions");
        addItem(admin, "Admin (Users)");
        bar.add(admin);

        JMenu view = new JMenu("View");
        view.setName("viewMenu");
        JCheckBoxMenuItem showIdColumn = new JCheckBoxMenuItem("Show ID Column", PbssTheme.isShowIdColumn());
        showIdColumn.setName("showIdColumnMenuItem");
        showIdColumn.addActionListener(e -> PbssTheme.setShowIdColumn(showIdColumn.isSelected()));
        view.add(showIdColumn);
        bar.add(view);

        return bar;
    }

    private void addItem(JMenu menu, String screen) {
        JMenuItem item = new JMenuItem(screen.equals("Admin (Users)") ? "Users" : screen);
        item.setName(screen.replaceAll("[^A-Za-z0-9]", "") + "MenuItem");
        item.addActionListener(e -> navigate(screen));
        menu.add(item);
    }

    private void navigate(String screen) {
        cards.show(content, screen);
        refreshCurrent();
    }

    private void addScreen(String name, JComponent panel) {
        content.add(panel, name);
    }

    /** Refreshes every screen's table on each nav switch — wasteful at scale, harmless at this screen count. */
    private void refreshCurrent() {
        for (java.awt.Component c : content.getComponents()) {
            if (c instanceof SimpleCrudPanel<?> crud) {
                crud.refresh();
            } else if (c instanceof PrintPanel print) {
                print.refresh();
            }
        }
    }

    void start() {
        setVisible(true);
        refreshCurrent();
    }
}
