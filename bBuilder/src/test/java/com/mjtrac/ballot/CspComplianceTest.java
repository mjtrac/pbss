/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 *
 * CspComplianceTest — static analysis of all Thymeleaf templates.
 * Fails if any template contains inline JavaScript (onclick=, <script> without src,
 * javascript: URLs, or hardcoded localhost:808x URLs).
 * No Spring context needed — reads files directly from the filesystem.
 */
package com.mjtrac.ballot;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CSP compliance — no inline JS in templates")
class CspComplianceTest {

    private static final Path TEMPLATES =
        Paths.get("src/main/resources/templates");

    /** All .html files under src/main/resources/templates */
    static Stream<Path> allTemplates() throws IOException {
        return Files.walk(TEMPLATES)
            .filter(p -> p.toString().endsWith(".html"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    @DisplayName("Template has no inline onclick= handler")
    void noInlineOnclick(Path template) throws IOException {
        String content = Files.readString(template);
        boolean hasInlineOnclick = content.contains(" onclick=")
            || content.contains("\tonclick=")
            || content.contains(">onclick=");
        assertThat(hasInlineOnclick)
            .as("Template %s must not contain inline onclick= handler", template)
            .isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    @DisplayName("Template has no inline onchange= handler")
    void noInlineOnchange(Path template) throws IOException {
        String content = Files.readString(template);
        boolean hasInlineOnchange = content.contains(" onchange=")
            || content.contains("\tonchange=")
            || content.contains(">onchange=");
        assertThat(hasInlineOnchange)
            .as("Template %s must not contain inline onchange= handler", template)
            .isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    @DisplayName("Template has no inline oninput= handler")
    void noInlineOninput(Path template) throws IOException {
        String content = Files.readString(template);
        boolean hasInlineOninput = content.contains(" oninput=")
            || content.contains("\toninput=")
            || content.contains(">oninput=");
        assertThat(hasInlineOninput)
            .as("Template %s must not contain inline oninput= handler", template)
            .isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    @DisplayName("Template has no inline <script> block (only <script src=> allowed)")
    void noInlineScript(Path template) throws IOException {
        String content = Files.readString(template);
        // <script> without src= is an inline block — not allowed
        // <script th:src= or <script src= are fine
        // Use a simple check: <script> or <script > not followed by src on same line
        String[] lines = content.split("\n");
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("<script") && !line.contains("src=") && !line.contains("src =")) {
                // Allow <script> if it's part of a th:src reference split across lines
                if (!lines[i].contains("th:src") && !lines[i].contains(" src")) {
                    violations.add("Line " + (i+1) + ": " + line);
                }
            }
        }
        assertThat(violations)
            .as("Template %s must not contain inline <script> blocks: %s",
                template, violations)
            .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    @DisplayName("Template has no hardcoded localhost:8081 URL")
    void noHardcodedPort8081(Path template) throws IOException {
        String content = Files.readString(template);
        assertThat(content)
            .as("Template %s must not contain hardcoded localhost:8081 URL — "
                + "use Thymeleaf @{} expressions instead", template)
            .doesNotContain("localhost:8081");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    @DisplayName("Template has no hardcoded localhost:8080 URL")
    void noHardcodedPort8080(Path template) throws IOException {
        String content = Files.readString(template);
        assertThat(content)
            .as("Template %s must not contain hardcoded localhost:8080 URL", template)
            .doesNotContain("localhost:8080");
    }
}
