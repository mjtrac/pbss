/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.repository.*;
import com.mjtrac.ballot.service.BallotGenerationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Trigger for PDF (+ YAML/XML offset report, written as a side effect of
 * generateBallot() itself — see BallotGenerationServiceTest) generation.
 * Mirrors blBuilder's FX PrintViewController; the one difference is
 * "Printed by", a User picker, since this app has no login/AuthContext to
 * pull a signed-in user from — BallotGenerationService requires a non-null
 * User for its audit log (PrintLogService.record()).
 */
@Component
class PrintPanel extends JPanel {

    private final BallotCombinationRepository combinationRepo;
    private final BallotDesignTemplateRepository templateRepo;
    private final BallotLanguageRepository languageRepo;
    private final UserRepository userRepo;
    private final BallotGenerationService ballotService;

    @Value("${ballot.export.dir:${user.home}/pbss_data/ballot_templates}")
    private String exportDir;

    private final JComboBox<BallotCombination> combinationCombo = new JComboBox<>();
    private final JComboBox<BallotDesignTemplate> templateCombo = new JComboBox<>();
    private final JComboBox<String> languageCombo = new JComboBox<>();
    private final JComboBox<User> userCombo = new JComboBox<>();
    private final JSpinner copiesSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
    private final JLabel messageLabel = new JLabel(" ");
    private final JButton openFolderButton = new JButton("Open Output Folder");

    PrintPanel(BallotCombinationRepository combinationRepo, BallotDesignTemplateRepository templateRepo,
               BallotLanguageRepository languageRepo, UserRepository userRepo,
               BallotGenerationService ballotService) {
        super(new BorderLayout(8, 8));
        this.combinationRepo = combinationRepo;
        this.templateRepo = templateRepo;
        this.languageRepo = languageRepo;
        this.userRepo = userRepo;
        this.ballotService = ballotService;
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        combinationCombo.setName("combinationCombo");
        templateCombo.setName("templateCombo");
        languageCombo.setName("languageCombo");
        userCombo.setName("userCombo");
        copiesSpinner.setName("copiesSpinner");
        messageLabel.setName("messageLabel");
        openFolderButton.setName("openFolderButton");

        combinationCombo.setRenderer(renderer(o -> {
            BallotCombination c = (BallotCombination) o;
            String party = c.getParty() != null ? c.getParty().getName() : "Nonpartisan";
            return c.getRegion().getName() + " · " + party + " · "
                + c.getBallotType().getName() + " · " + c.getElection().getName();
        }));
        templateCombo.setRenderer(renderer(o -> {
            BallotDesignTemplate t = (BallotDesignTemplate) o;
            return t.getPaperSize().name() + " · " + t.getColumns() + " col · " + t.getVoteIndicatorStyle().name();
        }));
        userCombo.setRenderer(renderer(o -> ((User) o).getUsername()));

        combinationCombo.addActionListener(e -> loadTemplatesAndLanguages());

        JButton generateBtn = new JButton("Generate PDF");
        generateBtn.setName("generateButton");
        generateBtn.addActionListener(e -> handleGenerate());
        openFolderButton.addActionListener(e -> openOutputFolder());

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        addRow(grid, gc, 0, "Ballot Combination:", combinationCombo);
        addRow(grid, gc, 1, "Design Template:", templateCombo);
        addRow(grid, gc, 2, "Language:", languageCombo);
        addRow(grid, gc, 3, "Copies:", copiesSpinner);
        addRow(grid, gc, 4, "Printed by:", userCombo);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(generateBtn);
        buttons.add(openFolderButton);

        add(PbssTheme.titleBlock("Generate Ballot PDF"), BorderLayout.NORTH);
        add(grid, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(messageLabel, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);
    }

    private void addRow(JPanel grid, GridBagConstraints gc, int row, String label, JComponent field) {
        gc.gridx = 0; gc.gridy = row;
        grid.add(new JLabel(label), gc);
        gc.gridx = 1;
        if (field instanceof JSpinner spinner) {
            // Same reasoning as SimpleCrudPanel.addField(): numeric spinners
            // default to stretching across the whole row like a text field.
            FontMetrics fm = spinner.getFontMetrics(spinner.getFont());
            int width = fm.stringWidth("9999") + 36;
            Dimension d = new Dimension(width, spinner.getPreferredSize().height);
            spinner.setPreferredSize(d);
            spinner.setMinimumSize(d);
            gc.fill = GridBagConstraints.NONE;
            gc.weightx = 0;
        } else {
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1;
        }
        grid.add(field, gc);
        gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
    }

    private static <E> ListCellRenderer<E> renderer(java.util.function.Function<Object, String> nameOf) {
        return (list, value, index, isSelected, hasFocus) -> {
            JLabel l = new JLabel(value != null ? nameOf.apply(value) : "");
            l.setOpaque(true);
            l.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            return l;
        };
    }

    /** Called whenever this screen becomes visible — refreshes all combos with current data. */
    void refresh() {
        combinationCombo.removeAllItems();
        for (BallotCombination c : combinationRepo.findAll()) combinationCombo.addItem(c);
        userCombo.removeAllItems();
        for (User u : userRepo.findAll()) userCombo.addItem(u);
        loadTemplatesAndLanguages();
    }

    private void loadTemplatesAndLanguages() {
        templateCombo.removeAllItems();
        languageCombo.removeAllItems();
        BallotCombination combo = (BallotCombination) combinationCombo.getSelectedItem();
        if (combo == null) return;

        for (BallotDesignTemplate t : templateRepo.findByElectionId(combo.getElection().getId())) {
            templateCombo.addItem(t);
        }
        languageCombo.addItem("en");
        Jurisdiction jurisdiction = combo.getElection().getJurisdiction();
        if (jurisdiction != null) {
            for (BallotLanguage l : languageRepo.findByJurisdictionIdOrderByDisplayOrderAsc(jurisdiction.getId())) {
                languageCombo.addItem(l.getLanguageCode());
            }
        }
    }

    private void handleGenerate() {
        clearMessage();
        BallotCombination combo = (BallotCombination) combinationCombo.getSelectedItem();
        if (combo == null) {
            showError("Please select a ballot combination.");
            return;
        }
        BallotDesignTemplate template = (BallotDesignTemplate) templateCombo.getSelectedItem();
        if (template == null) {
            template = templateRepo.findFirstByElectionIdOrderByIdAsc(combo.getElection().getId()).orElse(null);
        }
        if (template == null) {
            showError("No design template found for election \"" + combo.getElection().getName()
                + "\". Create a ballot design template for this election first.");
            return;
        }
        User user = (User) userCombo.getSelectedItem();
        if (user == null) {
            showError("No user selected. Create a user on the Admin screen first — "
                + "ballot generation is attributed to a user for the print audit log.");
            return;
        }
        String lang = languageCombo.getSelectedItem() != null ? (String) languageCombo.getSelectedItem() : "en";
        int copies = (Integer) copiesSpinner.getValue();

        try {
            byte[] pdf = ballotService.generateBallot(combo, template, user, copies, lang);
            String filename = "ballot-" + combo.getId() + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".pdf";
            Path outPath = Path.of(exportDir, filename);
            Files.createDirectories(outPath.getParent());
            Files.write(outPath, pdf);

            // generateBallot() also auto-exports a canonical PDF + YAML
            // (+ XML, if configured) triple under its own barcode-derived
            // filename as a side effect (BallotGenerationService.
            // autoExport()) — getLastWrittenFiles() is the only way to
            // learn what it actually wrote, since the returned byte[] is
            // just the PDF. Previously unreported here (and in blBuilder's
            // identical PrintViewController — same gap, not fixed there).
            List<String> autoExported = ballotService.getLastWrittenFiles();
            StringBuilder msg = new StringBuilder("Generated " + pdf.length + " byte PDF (" + copies + " cop"
                + (copies == 1 ? "y" : "ies") + ") — saved to " + outPath);
            if (!autoExported.isEmpty()) {
                msg.append("; layout files: ");
                msg.append(String.join(", ", autoExported.stream()
                    .map(p -> Path.of(p).getFileName().toString()).toList()));
            }
            showOk(msg.toString());
        } catch (Exception e) {
            showError("Could not generate ballot: " + e.getMessage());
        }
    }

    private void openOutputFolder() {
        try {
            if (Desktop.isDesktopSupported()) {
                Files.createDirectories(Path.of(exportDir));
                Desktop.getDesktop().open(new File(exportDir));
            } else {
                showError("Output folder: " + exportDir);
            }
        } catch (IOException e) {
            showError("Could not open output folder: " + e.getMessage());
        }
    }

    private void showOk(String text) {
        messageLabel.setForeground(new Color(0x16, 0xa3, 0x4a));
        messageLabel.setText(text);
    }

    private void showError(String text) {
        messageLabel.setForeground(new Color(0xdc, 0x26, 0x26));
        messageLabel.setText(text);
    }

    private void clearMessage() {
        messageLabel.setText(" ");
    }
}
