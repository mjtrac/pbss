/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.BallotCombination;
import com.mjtrac.ballot.model.BallotDesignTemplate;
import com.mjtrac.ballot.model.BallotType;
import com.mjtrac.ballot.model.Election;
import com.mjtrac.ballot.model.Jurisdiction;
import com.mjtrac.ballot.model.Party;
import com.mjtrac.ballot.model.Region;
import com.mjtrac.ballot.model.User;
import com.mjtrac.ballot.repository.BallotCombinationRepository;
import com.mjtrac.ballot.repository.BallotDesignTemplateRepository;
import com.mjtrac.ballot.repository.ElectionRepository;
import com.mjtrac.ballot.repository.JurisdictionRepository;
import com.mjtrac.ballot.repository.PartyRepository;
import com.mjtrac.ballot.repository.RegionRepository;
import com.mjtrac.ballot.repository.UserRepository;
import com.mjtrac.ballot.service.BallotGenerationService;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.data.TableCell;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Desktop/robot-driven counterpart to {@link TestElectionBuilder} for
 * test-harness/run_all.sh's {@code --desktop} flag: builds the same small
 * election (1 jurisdiction/region/ballot type, 2 Plurality contests with 2
 * candidates each, 1 combination, 1 design template) — but by actually
 * clicking through builder's real Swing screens via AssertJ-Swing, not by
 * calling repositories directly. This is a third alternative to the
 * bBuilder-REST path (build_election.py, the full 15-contest election) and
 * the headless repository-only fallback (TestElectionBuilder) — this one
 * genuinely exercises builder's UI as part of the pipeline, at the cost of
 * being much slower and more environment-sensitive (real java.awt.Robot
 * input — see README-testing.md's "Known environment-dependent behavior").
 *
 * Runs against the REAL {@code ~/pbss_data} database (same one bBuilder/
 * blBuilder/TestElectionBuilder use) — this needs to be the database
 * counter's own scan expects data in, so no isolated temp-DB override like
 * AbstractBuilderGuiTest's other screen tests use.
 *
 * Idempotent like TestElectionBuilder: if this exact election was already
 * built by a previous run (same jurisdiction/election name), this skips
 * re-driving several minutes of robot clicks and just regenerates +
 * reports the existing files headlessly instead.
 *
 * Usage: cd builder &amp;&amp; mvn -q test -Dtest=DesktopElectionBuilder \
 *          -Dtest-election.out=/abs/path/election_data.json
 */
class DesktopElectionBuilder {

    private static final String JURISDICTION_NAME = "Desktop Test County";
    private static final String ELECTION_NAME = "Desktop Test Election";
    private static final String REGION_NAME = "Precinct 1";
    private static final String BALLOT_TYPE_NAME = "Precinct";
    private static final String PRINT_USER = "desktop-test-harness";

    @SpringBootApplication(scanBasePackages = "com.mjtrac.ballot")
    @EntityScan("com.mjtrac.ballot.model")
    static class HeadlessConfig {
    }

    private ConfigurableApplicationContext springContext;
    private FrameFixture window;

    @AfterEach
    void tearDown() {
        if (window != null) window.cleanUp();
        if (springContext != null) springContext.close();
    }

    @Test
    void buildElectionThroughRealUi() throws Exception {
        String outPath = System.getProperty("test-election.out", "election_data.json");

        if (regenerateIfAlreadyBuilt(outPath)) {
            return;
        }

        ensurePartyAndRegionTablesAreNonEmpty();

        springContext = new SpringApplicationBuilder(BuilderApp.class)
            .web(WebApplicationType.NONE)
            .headless(false)
            .run();

        // Same reasoning as AbstractBuilderGuiTest: the context above
        // eagerly constructs MainFrame (and every child screen) before this
        // robot exists — a robot with a *new* AWT hierarchy would only
        // index components added after it starts listening. The *current*
        // hierarchy walks the live AWT window list instead.
        MainFrame mainFrame = GuiActionRunner.execute(() -> springContext.getBean(MainFrame.class));
        window = new FrameFixture(BasicRobot.robotWithCurrentAwtHierarchy(), mainFrame);
        window.show();
        window.focus();

        createJurisdiction();
        createRegion();
        createBallotType();
        createElection();
        createContest("Mayor", 1, "Alice Johnson", "Bob Williams");
        createContest("City Council Member", 2, "Carmen Lopez", "Dave Kim");
        createCombination();
        createTemplate();
        createUser();
        generatePdfAndWriteOutput(outPath);
    }

    /**
     * Mirrors TestElectionBuilder's own idempotency rationale ("so
     * re-running this doesn't pile up duplicate jurisdictions/elections").
     * Since driving the full UI sequence takes real wall-clock time and
     * pops real windows, re-running the whole thing on every test-harness
     * invocation would be wasteful — this reuses the prior run's entities
     * and just re-generates the PDF/YAML pair headlessly, exactly like
     * TestElectionBuilder does for its own single generateBallot() call.
     */
    private boolean regenerateIfAlreadyBuilt(String outPath) throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HeadlessConfig.class)
            .web(WebApplicationType.NONE)
            .headless(false)
            .run();
        try {
            JurisdictionRepository jurisdictionRepo = ctx.getBean(JurisdictionRepository.class);
            Optional<Jurisdiction> jurisdictionOpt = jurisdictionRepo.findByName(JURISDICTION_NAME);
            if (jurisdictionOpt.isEmpty()) return false;
            Jurisdiction jurisdiction = jurisdictionOpt.get();

            ElectionRepository electionRepo = ctx.getBean(ElectionRepository.class);
            Optional<Election> electionOpt = electionRepo.findByJurisdictionIdOrderByElectionDateDesc(jurisdiction.getId())
                .stream().filter(e -> ELECTION_NAME.equals(e.getName())).findFirst();
            if (electionOpt.isEmpty()) return false;
            Election election = electionOpt.get();

            BallotCombinationRepository combinationRepo = ctx.getBean(BallotCombinationRepository.class);
            Optional<BallotCombination> comboOpt = combinationRepo.findByElectionId(election.getId()).stream().findFirst();
            if (comboOpt.isEmpty()) return false;

            BallotDesignTemplateRepository templateRepo = ctx.getBean(BallotDesignTemplateRepository.class);
            Optional<BallotDesignTemplate> templateOpt = templateRepo.findAll().stream()
                .filter(t -> t.getElection() != null && election.getId().equals(t.getElection().getId()))
                .findFirst();
            if (templateOpt.isEmpty()) return false;

            UserRepository userRepo = ctx.getBean(UserRepository.class);
            Optional<User> userOpt = userRepo.findByUsername(PRINT_USER);
            if (userOpt.isEmpty()) return false;

            BallotCombinationRepository comboRepo = ctx.getBean(BallotCombinationRepository.class);
            BallotCombination combination = comboRepo.findById(comboOpt.get().getId()).orElseThrow();
            BallotDesignTemplate template = templateRepo.findById(templateOpt.get().getId()).orElseThrow();
            User user = userRepo.findById(userOpt.get().getId()).orElseThrow();

            BallotGenerationService ballotService = ctx.getBean(BallotGenerationService.class);
            ballotService.generateBallot(combination, template, user, 1, "en");
            List<String> written = ballotService.getLastWrittenFiles();
            List<String> yamlFiles = written.stream().filter(f -> f.endsWith(".yaml")).toList();
            List<String> pdfFiles = written.stream().filter(f -> f.endsWith(".pdf")).toList();

            TestElectionBuilder.writeElectionDataJson(outPath, combination.getId(), REGION_NAME, yamlFiles, pdfFiles);
            System.out.println("DesktopElectionBuilder: reused existing election (combination "
                + combination.getId() + "), regenerated " + yamlFiles.size() + " yaml, " + pdfFiles.size() + " pdf");
            return true;
        } finally {
            ctx.close();
        }
    }

    /**
     * MainFrame's constructor refreshes every screen (including Party and
     * Region), and PartyPanel/RegionPanel's onFirstOpenEmpty() pops a real,
     * blocking JOptionPane the instant either table is genuinely empty at
     * that point — see AbstractBuilderGuiTest's identical concern. That
     * class avoids it with a seed-then-delete temp DB; against the real,
     * persistent ~/pbss_data database, deleting real data back out doesn't
     * make sense, so this leaves a minimal, permanent, clearly-named
     * placeholder in place only if the table was truly empty — the same
     * "leave real but harmless dummy data behind" philosophy
     * TestElectionBuilder itself already uses.
     */
    private void ensurePartyAndRegionTablesAreNonEmpty() {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HeadlessConfig.class)
            .web(WebApplicationType.NONE)
            .headless(false)
            .run();
        try {
            PartyRepository partyRepo = ctx.getBean(PartyRepository.class);
            RegionRepository regionRepo = ctx.getBean(RegionRepository.class);
            if (partyRepo.findAll().isEmpty() || regionRepo.findAll().isEmpty()) {
                JurisdictionRepository jurisdictionRepo = ctx.getBean(JurisdictionRepository.class);
                Jurisdiction anyJurisdiction = jurisdictionRepo.findAll().stream().findFirst()
                    .orElseGet(() -> {
                        Jurisdiction j = new Jurisdiction();
                        j.setName("__gui_boot_placeholder__");
                        return jurisdictionRepo.save(j);
                    });
                if (partyRepo.findAll().isEmpty()) {
                    Party p = new Party();
                    p.setJurisdiction(anyJurisdiction);
                    p.setName("Nonpartisan");
                    partyRepo.save(p);
                }
                if (regionRepo.findAll().isEmpty()) {
                    Region r = new Region();
                    r.setJurisdiction(anyJurisdiction);
                    r.setName("__placeholder__");
                    r.setRegionType(Region.RegionType.SINGLE_PRECINCT);
                    regionRepo.save(r);
                }
            }
        } finally {
            ctx.close();
        }
    }

    private void createJurisdiction() {
        window.menuItem("JurisdictionsMenuItem").click();
        window.button("JurisdictionsNewButton").click();
        window.textBox("nameField").setText(JURISDICTION_NAME);
        window.button("saveButton").click();
    }

    private void createRegion() {
        window.menuItem("RegionsMenuItem").click();
        window.button("RegionsNewButton").click();
        selectComboItem("jurisdictionCombo", item ->
            item instanceof Jurisdiction j && JURISDICTION_NAME.equals(j.getName()));
        window.textBox("nameField").setText(REGION_NAME);
        window.comboBox("typeCombo").selectItem("SINGLE_PRECINCT");
        window.button("saveButton").click();
    }

    private void createBallotType() {
        window.menuItem("BallotTypesMenuItem").click();
        window.button("BallotTypesNewButton").click();
        selectComboItem("jurisdictionCombo", item ->
            item instanceof Jurisdiction j && JURISDICTION_NAME.equals(j.getName()));
        window.textBox("nameField").setText(BALLOT_TYPE_NAME);
        window.button("saveButton").click();
    }

    private void createElection() {
        window.menuItem("ElectionsMenuItem").click();
        window.button("ElectionsNewButton").click();
        selectComboItem("jurisdictionCombo", item ->
            item instanceof Jurisdiction j && JURISDICTION_NAME.equals(j.getName()));
        window.textBox("nameField").setText(ELECTION_NAME);
        window.comboBox("typeCombo").selectItem("GENERAL");
        window.button("saveButton").click();
    }

    private void createContest(String title, int maxChoices, String candidate1, String candidate2) {
        window.menuItem("ContestsMenuItem").click();
        window.button("ContestsNewButton").click();
        selectComboItem("electionCombo", item ->
            item instanceof Election e && ELECTION_NAME.equals(e.getName()));
        window.textBox("titleField").setText(title);
        window.spinner("maxChoicesSpinner").enterText(String.valueOf(maxChoices));
        window.button("saveButton").click();

        // Save cascades straight into the Candidates dialog (see
        // ContestCascadeGuiTest for the regression this depends on).
        window.dialog("candidatesDialog").requireVisible();
        window.button("addCandidateButton").click();
        window.dialog("candidatesDialog").table("candidatesTable")
            .cell(TableCell.row(0).column(0)).enterValue(candidate1);
        window.button("addCandidateButton").click();
        window.dialog("candidatesDialog").table("candidatesTable")
            .cell(TableCell.row(1).column(0)).enterValue(candidate2);
        window.dialog("candidatesDialog").button("saveContinueButton").click();

        // Candidates' save cascades into the Regions dialog. regionList
        // lists every Region in the whole database (not jurisdiction-
        // scoped), so — same reasoning as the combos below — find our
        // "Precinct 1" by identity (its actual name+jurisdiction), not by
        // list position.
        window.dialog("regionsDialog").requireVisible();
        var regionList = window.dialog("regionsDialog").list("regionList");
        int regionIndex = GuiActionRunner.execute(() -> {
            var model = ((javax.swing.JList<?>) regionList.target()).getModel();
            for (int i = 0; i < model.getSize(); i++) {
                Object item = model.getElementAt(i);
                if (item instanceof Region r && REGION_NAME.equals(r.getName())
                        && r.getJurisdiction() != null && JURISDICTION_NAME.equals(r.getJurisdiction().getName())) {
                    return i;
                }
            }
            return -1;
        });
        if (regionIndex < 0) {
            throw new IllegalStateException("Region \"" + REGION_NAME + "\" under \"" + JURISDICTION_NAME + "\" not found in regionList");
        }
        regionList.selectItem(regionIndex);
        window.dialog("regionsDialog").button("saveButton").click();
    }

    private void createCombination() {
        window.menuItem("BallotCombinationsMenuItem").click();
        window.button("BallotCombinationsNewButton").click();
        // These combos list every Region/BallotType/Election in the whole
        // database (not jurisdiction-scoped) with a custom ListCellRenderer
        // rather than an Object.toString() override, so AssertJ-Swing's
        // default text-based selectItem() can't be used and index 0 isn't
        // safe to assume — same reasoning as regionList above.
        selectComboItem("regionCombo", item ->
            item instanceof Region r && REGION_NAME.equals(r.getName())
                && r.getJurisdiction() != null && JURISDICTION_NAME.equals(r.getJurisdiction().getName()));
        selectComboItem("typeCombo", item ->
            item instanceof BallotType bt && BALLOT_TYPE_NAME.equals(bt.getName())
                && bt.getJurisdiction() != null && JURISDICTION_NAME.equals(bt.getJurisdiction().getName()));
        selectComboItem("electionCombo", item ->
            item instanceof Election e && ELECTION_NAME.equals(e.getName()));
        window.button("saveButton").click();
    }

    private void createTemplate() {
        window.menuItem("BallotDesignTemplatesMenuItem").click();
        window.button("BallotDesignTemplatesNewButton").click();
        selectComboItem("electionCombo", item ->
            item instanceof Election e && ELECTION_NAME.equals(e.getName()));
        window.comboBox("paperCombo").selectItem("LETTER_8_5x11");
        window.comboBox("indicatorCombo").selectItem("OVAL");
        window.spinner("columnsSpinner").enterText("1");
        window.button("saveButton").click();
    }

    private void createUser() {
        window.menuItem("AdminUsersMenuItem").click();
        window.button("UsersNewButton").click();
        window.textBox("usernameField").setText(PRINT_USER);
        window.textBox("passwordField").setText("unused-desktop-harness-pw");
        window.checkBox("adminCheck").check();
        window.button("saveButton").click();
    }

    private void generatePdfAndWriteOutput(String outPath) throws Exception {
        window.menuItem("PrintMenuItem").click();
        // combinationCombo and userCombo both list every row in the whole
        // database, unscoped — same reasoning as createCombination() above.
        selectComboItem("combinationCombo", item ->
            item instanceof BallotCombination c && c.getElection() != null
                && ELECTION_NAME.equals(c.getElection().getName()));
        // templateCombo IS already scoped to the selected combination's
        // election (loadTemplatesAndLanguages()), and we only ever create
        // one template for it, so index 0 is safe here.
        window.comboBox("templateCombo").selectItem(0);
        selectComboItem("userCombo", item ->
            item instanceof User u && PRINT_USER.equals(u.getUsername()));
        window.button("generateButton").click();

        String message = window.label("messageLabel").text();
        if (message == null || !message.startsWith("Generated")) {
            throw new IllegalStateException("Generate PDF did not succeed — messageLabel: " + message);
        }

        BallotGenerationService ballotService = springContext.getBean(BallotGenerationService.class);
        List<String> written = ballotService.getLastWrittenFiles();
        List<String> yamlFiles = written.stream().filter(f -> f.endsWith(".yaml")).toList();
        List<String> pdfFiles = written.stream().filter(f -> f.endsWith(".pdf")).toList();
        if (yamlFiles.isEmpty() || pdfFiles.isEmpty()) {
            throw new IllegalStateException("Generate PDF did not auto-export the expected YAML/PDF pair — got: " + written);
        }

        BallotCombinationRepository combinationRepo = springContext.getBean(BallotCombinationRepository.class);
        ElectionRepository electionRepo = springContext.getBean(ElectionRepository.class);
        Election election = electionRepo.findAll().stream()
            .filter(e -> ELECTION_NAME.equals(e.getName())).findFirst().orElseThrow();
        BallotCombination combination = combinationRepo.findByElectionId(election.getId()).stream().findFirst().orElseThrow();

        TestElectionBuilder.writeElectionDataJson(outPath, combination.getId(), REGION_NAME, yamlFiles, pdfFiles);
        System.out.println("DesktopElectionBuilder: wrote " + outPath + " (combination "
            + combination.getId() + ", " + yamlFiles.size() + " yaml, " + pdfFiles.size() + " pdf)");
    }

    /**
     * These combos hold real entities (with a custom ListCellRenderer, not
     * an Object.toString() override, so AssertJ-Swing's text-based
     * selectItem() can't match them) and most are NOT scoped to just our
     * jurisdiction/election — they list every row of that type in the
     * whole real database. Index 0 is only safe when a screen's combo is
     * genuinely scoped down to a single possibility already (noted inline
     * at each such call site); everywhere else, find the actual row we
     * created by identity instead of guessing a position.
     */
    private void selectComboItem(String comboName, java.util.function.Predicate<Object> matcher) {
        var combo = window.comboBox(comboName);
        int matchIndex = GuiActionRunner.execute(() -> {
            var model = combo.target().getModel();
            for (int i = 0; i < model.getSize(); i++) {
                if (matcher.test(model.getElementAt(i))) return i;
            }
            return -1;
        });
        if (matchIndex < 0) {
            throw new IllegalStateException("No matching item found in combo " + comboName);
        }
        combo.selectItem(matchIndex);
    }
}
