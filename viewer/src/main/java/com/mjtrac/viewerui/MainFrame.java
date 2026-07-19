/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewerui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;

/** Top-level window: CardLayout swapping between the ballot list and a single ballot's view. */
@Component
class MainFrame extends JFrame {

    private static final String CARD_LIST = "list";
    private static final String CARD_VIEW = "view";

    private final AuthContext authContext;
    private final LoginDialog loginDialog;
    private final BallotListPanel listPanel;
    private final BallotViewPanel viewPanel;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);

    MainFrame(AuthContext authContext, LoginDialog loginDialog,
              BallotListPanel listPanel, BallotViewPanel viewPanel,
              @Value("${app.login-title:pbss Ballot Viewer}") String title) {
        super(title);
        this.authContext = authContext;
        this.loginDialog = loginDialog;
        this.listPanel = listPanel;
        this.viewPanel = viewPanel;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 750);
        setLocationRelativeTo(null);

        content.add(listPanel, CARD_LIST);
        content.add(viewPanel, CARD_VIEW);
        setContentPane(content);

        setJMenuBar(buildMenuBar());

        listPanel.setOnView((id, ids) -> {
            viewPanel.load(id, ids);
            cards.show(content, CARD_VIEW);
        });
        viewPanel.setOnBack(() -> cards.show(content, CARD_LIST));
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem signOut = new JMenuItem("Sign Out");
        JMenuItem exit = new JMenuItem("Exit");
        signOut.addActionListener(e -> handleSignOut());
        exit.addActionListener(e -> System.exit(0));
        file.add(signOut);
        file.addSeparator();
        file.add(exit);
        bar.add(file);
        return bar;
    }

    /** Blocks (via the modal login dialog) until signed in, then shows the frame. Exits the JVM if login is abandoned. */
    void start() {
        if (!loginDialog.showAndAuthenticate(this)) {
            System.exit(0);
        }
        listPanel.refresh();
        cards.show(content, CARD_LIST);
        setVisible(true);
    }

    private void handleSignOut() {
        authContext.clear();
        setVisible(false);
        if (!loginDialog.showAndAuthenticate(this)) {
            System.exit(0);
        }
        listPanel.refresh();
        cards.show(content, CARD_LIST);
        setVisible(true);
    }
}
