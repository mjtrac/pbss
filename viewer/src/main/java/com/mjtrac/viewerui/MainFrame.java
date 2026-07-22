/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewerui;

import com.mjtrac.viewer.service.BallotViewService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Top-level window: CardLayout swapping between the ballot list and a single ballot's view. */
@Component
class MainFrame extends JFrame {

    private static final String CARD_LIST = "list";
    private static final String CARD_VIEW = "view";

    private final AuthContext authContext;
    private final LoginDialog loginDialog;
    private final BallotListPanel listPanel;
    private final BallotViewPanel viewPanel;
    private final ContestCandidateWindow contestCandidateWindow;
    private final BallotViewService viewService;
    private final String datasourceUrl;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private JCheckBoxMenuItem contestsToggle;

    MainFrame(AuthContext authContext, LoginDialog loginDialog,
              BallotListPanel listPanel, BallotViewPanel viewPanel,
              ContestCandidateWindow contestCandidateWindow,
              BallotViewService viewService,
              @Value("${app.login-title:pbss Ballot Viewer}") String title,
              @Value("${spring.datasource.url}") String datasourceUrl) {
        super(title);
        this.authContext = authContext;
        this.loginDialog = loginDialog;
        this.listPanel = listPanel;
        this.viewPanel = viewPanel;
        this.contestCandidateWindow = contestCandidateWindow;
        this.viewService = viewService;
        this.datasourceUrl = datasourceUrl;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 750);
        setLocationRelativeTo(null);
        contestCandidateWindow.setLocation(getX() + getWidth() + 10, getY());

        content.add(listPanel, CARD_LIST);
        content.add(viewPanel, CARD_VIEW);
        setContentPane(content);

        setJMenuBar(buildMenuBar());

        // Keep the View menu's checkbox in sync if the second window is
        // closed via its own close button rather than the menu/shortcut.
        contestCandidateWindow.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentShown(java.awt.event.ComponentEvent e) { contestsToggle.setSelected(true); }
            @Override public void componentHidden(java.awt.event.ComponentEvent e) { contestsToggle.setSelected(false); }
        });

        listPanel.setOnView((id, ids) -> {
            viewPanel.setShowFullPath(listPanel.isShowFullPath());
            viewPanel.load(id, ids);
            cards.show(content, CARD_VIEW);
        });
        viewPanel.setOnBack(() -> cards.show(content, CARD_LIST));
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem dataSourceInfo = new JMenuItem("Data Source Info…");
        JMenuItem signOut = new JMenuItem("Sign Out");
        JMenuItem exit = new JMenuItem("Exit");
        dataSourceInfo.addActionListener(e -> showDataSourceInfo());
        signOut.addActionListener(e -> handleSignOut());
        exit.addActionListener(e -> System.exit(0));
        file.add(dataSourceInfo);
        file.addSeparator();
        file.add(signOut);
        file.addSeparator();
        file.add(exit);
        bar.add(file);

        JMenu view = new JMenu("View");
        contestsToggle = new JCheckBoxMenuItem("Contests & Candidates");
        contestsToggle.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L,
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        contestsToggle.addActionListener(e -> contestCandidateWindow.setVisible(contestsToggle.isSelected()));
        view.add(contestsToggle);
        bar.add(view);

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

    /**
     * Menu-only (not always-on-screen) info: which database this session is
     * reading and the common folder root of the currently loaded ballot
     * images — there's no single configured "images root" property (each
     * ballot_image.image_path is just whatever absolute path bCounter/
     * blCounter stored at scan time), so the root shown here is computed
     * from the actual loaded paths rather than assumed from config.
     */
    private void showDataSourceInfo() {
        List<BallotViewService.BallotImageSummary> all = viewService.listAll();
        String root = commonPathRoot(all.stream().map(s -> s.imagePath).toList());
        String html = "<html><body style='font-family:sans-serif;font-size:11px;width:440px'>"
            + "<p><b>Database</b></p>"
            + "<p style='font-family:monospace'>" + escapeHtml(datasourceUrl) + "</p>"
            + "<p style='margin-top:10px'><b>Ballot image folder root</b> <i>("
            + all.size() + " image" + (all.size() == 1 ? "" : "s") + " loaded)</i></p>"
            + "<p style='font-family:monospace'>" + escapeHtml(root) + "</p>"
            + "</body></html>";
        // JEditorPane (not JLabel) so both paths can be drag-selected and
        // Ctrl+C'd, same convention as BallotListPanel's SQL help dialog.
        JEditorPane pane = new JEditorPane("text/html", html);
        pane.setEditable(false);
        pane.setDragEnabled(true);
        pane.setBackground(UIManager.getColor("Panel.background"));
        JScrollPane scroll = new JScrollPane(pane);
        scroll.setPreferredSize(new Dimension(460, 180));
        JOptionPane.showMessageDialog(this, scroll, "Data Source Info", JOptionPane.PLAIN_MESSAGE);
    }

    /** Longest common ancestor directory of every loaded image's parent folder, or an explanatory placeholder. */
    private static String commonPathRoot(List<String> paths) {
        Path common = null;
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            Path parent = Paths.get(p).getParent();
            if (parent == null) continue;
            common = (common == null) ? parent : commonAncestor(common, parent);
            if (common == null) return "(no common root — images span multiple locations)";
        }
        return common != null ? common.toString() : "(no ballot images loaded)";
    }

    private static Path commonAncestor(Path a, Path b) {
        if (!java.util.Objects.equals(a.getRoot(), b.getRoot())) return null;
        int max = Math.min(a.getNameCount(), b.getNameCount());
        int i = 0;
        while (i < max && a.getName(i).equals(b.getName(i))) i++;
        if (i == 0) return a.getRoot();
        return a.getRoot().resolve(a.subpath(0, i));
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void handleSignOut() {
        authContext.clear();
        contestCandidateWindow.setVisible(false);
        setVisible(false);
        if (!loginDialog.showAndAuthenticate(this)) {
            System.exit(0);
        }
        listPanel.refresh();
        cards.show(content, CARD_LIST);
        setVisible(true);
    }
}
