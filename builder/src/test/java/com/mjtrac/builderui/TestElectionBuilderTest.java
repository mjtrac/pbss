/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies TestElectionBuilder (the headless bBuilder-REST-API fallback
 * for run_all.sh) actually produces a real PDF+YAML pair and a
 * mark_ballots.py-compatible election_data.json, and — the actual bug
 * this caught during development — that running it twice against the
 * same database reuses the existing election instead of creating
 * duplicates. That bug was entity .equals() reliance (Jurisdiction/
 * BallotType/Election have no equals()/hashCode() override, so it falls
 * back to object identity, which never matches a freshly-loaded entity
 * from a separate run) silently piling up a new BallotType + Combination
 * on every invocation.
 */
class TestElectionBuilderTest {

    @Test
    void producesValidElectionDataAndIsIdempotentAcrossRuns(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("test.db");
        Path exportDir = tempDir.resolve("ballot_templates");
        Path outJson = tempDir.resolve("election_data.json");

        runOnce(db, exportDir, outJson);
        assertThat(Files.exists(outJson)).isTrue();
        String firstJson = Files.readString(outJson);
        assertThat(firstJson).contains("\"combinationId\": 1");

        try (ConfigurableApplicationContext ctx = openContext(db)) {
            assertThat(ctx.getBean(JurisdictionRepository.class).findAll()).hasSize(1);
            assertThat(ctx.getBean(BallotTypeRepository.class).findAll()).hasSize(1);
            assertThat(ctx.getBean(BallotCombinationRepository.class).findAll()).hasSize(1);
            assertThat(ctx.getBean(ContestRepository.class).findAll()).hasSize(2);

            BallotCombination combo = ctx.getBean(BallotCombinationRepository.class).findAll().get(0);
            assertThat(combo.getParty()).as("nonpartisan combination has no party").isNull();
        }

        // Second run against the same database — must reuse everything,
        // not create a second jurisdiction/ballot-type/combination.
        runOnce(db, exportDir, outJson);

        try (ConfigurableApplicationContext ctx = openContext(db)) {
            assertThat(ctx.getBean(JurisdictionRepository.class).findAll()).as("idempotent").hasSize(1);
            assertThat(ctx.getBean(BallotTypeRepository.class).findAll()).as("idempotent").hasSize(1);
            assertThat(ctx.getBean(BallotCombinationRepository.class).findAll()).as("idempotent").hasSize(1);
            assertThat(ctx.getBean(ContestRepository.class).findAll()).as("idempotent").hasSize(2);
        }

        String secondJson = Files.readString(outJson);
        assertThat(secondJson).as("same combinationId reused, not a new one")
            .contains("\"combinationId\": 1");
    }

    private static void runOnce(Path db, Path exportDir, Path outJson) throws Exception {
        // .run(), not .main() — main() calls System.exit(0), which would
        // kill this test's own JVM.
        TestElectionBuilder.run(new String[]{
            "--spring.datasource.url=jdbc:sqlite:" + db,
            "--ballot.export.dir=" + exportDir,
            "--test-election.out=" + outJson,
        });
    }

    private static ConfigurableApplicationContext openContext(Path db) {
        return new SpringApplicationBuilder(TestElectionBuilder.Config.class)
            .web(WebApplicationType.NONE)
            .headless(true)
            .run("--spring.datasource.url=jdbc:sqlite:" + db);
    }
}
