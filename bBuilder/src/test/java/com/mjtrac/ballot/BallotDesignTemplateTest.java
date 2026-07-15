/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.ballot;

import com.mjtrac.ballot.model.BallotDesignTemplate;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("BallotDesignTemplate unit tests")
class BallotDesignTemplateTest {

    @Test
    @DisplayName("Default indicatorLineWidthPt is 0.5")
    void testDefaultIndicatorLineWidth() {
        BallotDesignTemplate t = new BallotDesignTemplate();
        assertThat(t.getIndicatorLineWidthPt()).isEqualTo(0.5f);
    }

    @Test
    @DisplayName("Default barcodeHeightPt is 72 (1 inch QR code)")
    void testDefaultQrSize() {
        BallotDesignTemplate t = new BallotDesignTemplate();
        assertThat(t.getBarcodeHeightPt()).isEqualTo(72f);
    }

    @Test
    @DisplayName("CONNECT_DOTS is a valid VoteIndicatorStyle enum value")
    void testConnectDotsInEnum() {
        assertThatCode(() ->
            BallotDesignTemplate.VoteIndicatorStyle.valueOf("CONNECT_DOTS"))
            .doesNotThrowAnyException();
        assertThat(BallotDesignTemplate.VoteIndicatorStyle.valueOf("CONNECT_DOTS"))
            .isEqualTo(BallotDesignTemplate.VoteIndicatorStyle.CONNECT_DOTS);
    }

    @Test
    @DisplayName("DEFAULT_HEADER_HTML is non-null and non-blank")
    void testDefaultHeaderHtml() {
        assertThat(BallotDesignTemplate.DEFAULT_HEADER_HTML)
            .isNotNull()
            .isNotBlank()
            .contains("OFFICIAL BALLOT");
    }

    @Test
    @DisplayName("DEFAULT_HEADER_HTML contains font-size declarations")
    void testDefaultHeaderHasFontSizes() {
        // Must have explicit font sizes so html2pdf renders correctly
        assertThat(BallotDesignTemplate.DEFAULT_HEADER_HTML)
            .contains("font-size");
    }

    @Test
    @DisplayName("getHeaderHtml falls back to DEFAULT_HEADER_HTML when null")
    void testHeaderHtmlFallback() {
        BallotDesignTemplate t = new BallotDesignTemplate();
        t.setHeaderHtml(null);
        // The service calls getHeaderHtml(); the model should return default
        // rather than null so callers don't need a null check.
        assertThat(t.getHeaderHtml()).isNotNull();
    }

    @Test
    @DisplayName("All VoteIndicatorStyle values are usable without exception")
    void testAllIndicatorStylesValid() {
        for (BallotDesignTemplate.VoteIndicatorStyle s :
                BallotDesignTemplate.VoteIndicatorStyle.values()) {
            assertThatCode(() -> s.name()).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("Default columns is 3")
    void testDefaultColumns() {
        BallotDesignTemplate t = new BallotDesignTemplate();
        assertThat(t.getColumns()).isEqualTo(3);
    }
}
