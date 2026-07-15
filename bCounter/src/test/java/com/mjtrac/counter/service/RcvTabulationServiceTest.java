/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.service;

import com.mjtrac.counter.service.RcvTabulationService.RcvResult;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for RcvTabulationService.
 *
 * RcvTabulationService.tabulateAndWrite() reads ranked votes from the
 * counter_results.db and writes an HTML report. Its results depend on
 * what's in the database at test time.
 *
 * These tests verify:
 *   1. tabulateAndWrite() completes without throwing even with an empty DB.
 *   2. Any RcvResult objects returned have valid outcome values.
 *   3. RcvResult structure has non-null required fields.
 *   4. The IRV algorithm is separately covered by rcv_tabulate.py in the
 *      test harness, which exercises the Python implementation against a
 *      known election fixture.
 *
 * Note: full IRV round-by-round correctness is best tested via the Python
 * test harness (rcv_tabulate.py) which runs against a real scan session.
 * The Java service's IRV logic is private; these tests exercise the public
 * API surface only.
 */
@SpringBootTest
@ActiveProfiles("sqlite")
@DisplayName("RcvTabulationService — public API")
class RcvTabulationServiceTest {

    @Autowired RcvTabulationService rcvService;

    @Test
    @DisplayName("tabulateAndWrite completes without throwing on empty database")
    void testTabulateEmptyDb() {
        // With no RANKED_CHOICE contests in DB, should return empty list silently
        assertThatCode(() -> {
            var results = rcvService.tabulateAndWrite(
                System.getProperty("java.io.tmpdir"));
            assertThat(results).isNotNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Any returned RcvResult has a valid outcome value")
    void testRcvResultOutcomeValues() {
        var results = rcvService.tabulateAndWrite(
            System.getProperty("java.io.tmpdir"));
        for (RcvResult r : results) {
            assertThat(r.outcome)
                .as("outcome for contest: " + r.contest)
                .isIn("winner", "tie", "no_majority");
        }
    }

    @Test
    @DisplayName("Any returned RcvResult has non-null rounds list")
    void testRcvResultHasRounds() {
        var results = rcvService.tabulateAndWrite(
            System.getProperty("java.io.tmpdir"));
        for (RcvResult r : results) {
            assertThat(r.rounds)
                .as("rounds for: " + r.contest)
                .isNotNull();
        }
    }

    @Test
    @DisplayName("Winner-outcome result has non-null winner field")
    void testWinnerResultHasWinner() {
        var results = rcvService.tabulateAndWrite(
            System.getProperty("java.io.tmpdir"));
        for (RcvResult r : results) {
            if ("winner".equals(r.outcome)) {
                assertThat(r.winner)
                    .as("winner field for: " + r.contest)
                    .isNotNull()
                    .isNotBlank();
            }
        }
    }
}
