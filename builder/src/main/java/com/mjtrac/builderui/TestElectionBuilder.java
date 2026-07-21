/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.repository.*;
import com.mjtrac.ballot.service.BallotGenerationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Headless fallback for test-harness/run_all.sh's Step 2 (normally
 * build_election.py driving bBuilder's REST API) — creates a small but
 * real test election directly via builder-core's repositories and
 * BallotGenerationService, no bBuilder (or any web server) required. Only
 * meant to unblock the rest of the pipeline (mark_ballots.py onward) when
 * bBuilder genuinely isn't reachable; nowhere near build_election.py's
 * full multi-precinct/multi-party/RCV/write-in richness — see
 * test-harness/README.md's "Want to just watch counter count?" section
 * for when this is used instead of the real thing.
 *
 * Idempotent: finds-or-creates by name, so re-running this doesn't pile up
 * duplicate jurisdictions/elections in ~/pbss_data/db/election_ballot.db
 * (the same shared database bBuilder itself would use).
 *
 * Writes an election_data.json compatible with mark_ballots.py's expected
 * schema: {"combinations": [{"combinationId", "precinct", "party",
 * "yamlFiles", "pdfFiles"}]}.
 *
 * Usage: ./mvnw -q spring-boot:run -Dspring-boot.run.main-class=com.mjtrac.builderui.TestElectionBuilder \
 *          -Dspring-boot.run.arguments="--test-election.out=/abs/path/election_data.json"
 */
public class TestElectionBuilder {

    private static final String JURISDICTION_NAME = "Test Harness Fallback County";
    private static final String ELECTION_NAME = "Test Harness Fallback Election";
    private static final String REGION_NAME = "Precinct 1";
    private static final String BALLOT_TYPE_NAME = "Precinct";
    private static final String PRINT_USER = "test-harness-fallback";

    @SpringBootApplication(scanBasePackages = "com.mjtrac.ballot")
    @EntityScan("com.mjtrac.ballot.model")
    static class Config {
    }

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } finally {
            System.exit(0);
        }
    }

    /**
     * The actual work, with no System.exit() call — so a test can invoke
     * this directly in-process without killing its own JVM. main() is just
     * this plus the exit.
     */
    static void run(String[] args) throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Config.class)
            .web(WebApplicationType.NONE)
            .headless(true)
            .run(args);

        try {
            String outPath = ctx.getEnvironment().getProperty("test-election.out", "election_data.json");

            JurisdictionRepository jurisdictionRepo = ctx.getBean(JurisdictionRepository.class);
            RegionRepository regionRepo = ctx.getBean(RegionRepository.class);
            ElectionRepository electionRepo = ctx.getBean(ElectionRepository.class);
            BallotTypeRepository ballotTypeRepo = ctx.getBean(BallotTypeRepository.class);
            ContestRepository contestRepo = ctx.getBean(ContestRepository.class);
            BallotCombinationRepository combinationRepo = ctx.getBean(BallotCombinationRepository.class);
            BallotDesignTemplateRepository templateRepo = ctx.getBean(BallotDesignTemplateRepository.class);
            UserRepository userRepo = ctx.getBean(UserRepository.class);
            BallotGenerationService ballotService = ctx.getBean(BallotGenerationService.class);

            Jurisdiction jurisdiction = jurisdictionRepo.findByName(JURISDICTION_NAME).orElseGet(() -> {
                Jurisdiction j = new Jurisdiction();
                j.setName(JURISDICTION_NAME);
                return jurisdictionRepo.save(j);
            });

            Region region = regionRepo.findByJurisdictionIdAndRegionTypeOrderByName(
                    jurisdiction.getId(), Region.RegionType.SINGLE_PRECINCT)
                .stream().filter(r -> REGION_NAME.equals(r.getName())).findFirst()
                .orElseGet(() -> {
                    Region r = new Region();
                    r.setJurisdiction(jurisdiction);
                    r.setName(REGION_NAME);
                    r.setRegionType(Region.RegionType.SINGLE_PRECINCT);
                    return regionRepo.save(r);
                });

            BallotType ballotType = ballotTypeRepo.findAll().stream()
                // Compare by id, not .equals() — Jurisdiction has no
                // equals()/hashCode() override, so it falls back to object
                // identity, which never matches a freshly-loaded entity
                // from a different JVM run (silently created a duplicate
                // BallotType, then BallotCombination, on every re-run).
                .filter(bt -> jurisdiction.getId().equals(bt.getJurisdiction().getId()) && BALLOT_TYPE_NAME.equals(bt.getName()))
                .findFirst().orElseGet(() -> {
                    BallotType bt = new BallotType();
                    bt.setJurisdiction(jurisdiction);
                    bt.setName(BALLOT_TYPE_NAME);
                    return ballotTypeRepo.save(bt);
                });

            Election election = electionRepo.findByJurisdictionIdOrderByElectionDateDesc(jurisdiction.getId())
                .stream().filter(e -> ELECTION_NAME.equals(e.getName())).findFirst()
                .orElseGet(() -> {
                    Election e = new Election();
                    e.setJurisdiction(jurisdiction);
                    e.setName(ELECTION_NAME);
                    e.setElectionType(Election.ElectionType.GENERAL);
                    return electionRepo.save(e);
                });

            List<Contest> contests = contestRepo.findByElectionId(election.getId());
            if (contests.isEmpty()) {
                Contest mayor = new Contest();
                mayor.setElection(election);
                mayor.setTitle("Mayor");
                mayor.setMaxChoices(1);
                mayor.setVotingMethod(Contest.VotingMethod.PLURALITY);
                mayor.setAssignedRegions(List.of(region));
                Candidate alice = new Candidate();
                alice.setName("Alice Johnson");
                alice.setDisplayOrder(1);
                alice.setContest(mayor);
                Candidate bob = new Candidate();
                bob.setName("Bob Williams");
                bob.setDisplayOrder(2);
                bob.setContest(mayor);
                mayor.setCandidates(List.of(alice, bob));
                contestRepo.save(mayor);

                Contest council = new Contest();
                council.setElection(election);
                council.setTitle("City Council Member");
                council.setMaxChoices(2);
                council.setVotingMethod(Contest.VotingMethod.PLURALITY);
                council.setAssignedRegions(List.of(region));
                Candidate carmen = new Candidate();
                carmen.setName("Carmen Lopez");
                carmen.setDisplayOrder(1);
                carmen.setContest(council);
                Candidate dave = new Candidate();
                dave.setName("Dave Kim");
                dave.setDisplayOrder(2);
                dave.setContest(council);
                council.setCandidates(List.of(carmen, dave));
                contestRepo.save(council);
            }

            // Filtered in Java, not via the derived-query method with a
            // null party argument — Spring Data's null-parameter handling
            // for equality-derived queries turned out not to match an
            // existing null-party row here, silently creating a duplicate
            // combination on every re-run instead of reusing it.
            BallotCombination combination = combinationRepo.findByElectionId(election.getId()).stream()
                .filter(c -> c.getParty() == null
                    && region.getId().equals(c.getRegion().getId())
                    && ballotType.getId().equals(c.getBallotType().getId()))
                .findFirst()
                .orElseGet(() -> {
                    BallotCombination c = new BallotCombination();
                    c.setRegion(region);
                    c.setBallotType(ballotType);
                    c.setElection(election);
                    return combinationRepo.save(c);
                });

            BallotDesignTemplate template = templateRepo.findAll().stream()
                .filter(t -> t.getElection() != null && election.getId().equals(t.getElection().getId()))
                .findFirst().orElseGet(() -> {
                    BallotDesignTemplate t = new BallotDesignTemplate();
                    t.setElection(election);
                    t.setPaperSize(BallotDesignTemplate.PaperSize.LETTER_8_5x11);
                    t.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.OVAL);
                    t.setColumns(1);
                    return templateRepo.save(t);
                });

            User printedBy = userRepo.findByUsername(PRINT_USER).orElseGet(() -> {
                User u = new User();
                u.setUsername(PRINT_USER);
                u.setPasswordHash("unused");
                u.setRoles(Set.of(User.Role.ADMIN));
                return userRepo.save(u);
            });

            // Reload by id, mirroring how the real Print screen does it —
            // exercises the same save-then-load path, not just in-memory objects.
            BallotCombination reloadedCombo = combinationRepo.findById(combination.getId()).orElseThrow();
            BallotDesignTemplate reloadedTemplate = templateRepo.findById(template.getId()).orElseThrow();
            User reloadedUser = userRepo.findById(printedBy.getId()).orElseThrow();

            ballotService.generateBallot(reloadedCombo, reloadedTemplate, reloadedUser, 1, "en");
            List<String> written = ballotService.getLastWrittenFiles();
            List<String> yamlFiles = written.stream().filter(f -> f.endsWith(".yaml")).toList();
            List<String> pdfFiles = written.stream().filter(f -> f.endsWith(".pdf")).toList();

            if (yamlFiles.isEmpty() || pdfFiles.isEmpty()) {
                throw new IllegalStateException("generateBallot() did not auto-export the expected "
                    + "YAML/PDF pair — got: " + written);
            }

            writeElectionDataJson(outPath, combination.getId(), region.getName(), yamlFiles, pdfFiles);
            System.out.println("TestElectionBuilder: wrote " + outPath + " (combination "
                + combination.getId() + ", " + yamlFiles.size() + " yaml, " + pdfFiles.size() + " pdf)");
        } finally {
            ctx.close();
        }
    }

    /** Package-visible (not private) so DesktopElectionBuilder — same package, test source root — can reuse it. */
    static void writeElectionDataJson(String outPath, Long combinationId, String precinctName,
                                               List<String> yamlFiles, List<String> pdfFiles) throws Exception {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"combinations\": [\n    {\n");
        json.append("      \"combinationId\": ").append(combinationId).append(",\n");
        json.append("      \"precinct\": ").append(jsonString(precinctName)).append(",\n");
        json.append("      \"party\": \"Nonpartisan\",\n");
        json.append("      \"yamlFiles\": ").append(jsonArray(yamlFiles)).append(",\n");
        json.append("      \"pdfFiles\": ").append(jsonArray(pdfFiles)).append("\n");
        json.append("    }\n  ]\n}\n");

        Path path = Path.of(outPath);
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, json.toString());
    }

    static String jsonArray(List<String> values) {
        List<String> quoted = new ArrayList<>();
        for (String v : values) quoted.add(jsonString(v));
        return "[" + String.join(", ", quoted) + "]";
    }

    static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
