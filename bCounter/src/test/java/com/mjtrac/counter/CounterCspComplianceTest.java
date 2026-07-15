/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 *
 * CounterCspComplianceTest — static analysis of all bCounter Thymeleaf templates.
 * Same rules as bBuilder: no inline JS, no hardcoded cross-port URLs.
 */
package com.mjtrac.counter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CSP compliance — no inline JS in bCounter templates")
class CounterCspComplianceTest {

    private static final Path TEMPLATES =
        Paths.get("src/main/resources/templates");

    static Stream<Path> allTemplates() throws IOException {
        return Files.walk(TEMPLATES)
            .filter(p -> p.toString().endsWith(".html"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    @DisplayName("Template has no inline onclick= handler")
    void noInlineOnclick(Path template) throws IOException {
        assertThat(Files.readString(template))
            .as("Template %s must not contain inline onclick=", template)
            .doesNotContain("onclick=");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    @DisplayName("Template has no inline onchange= handler")
    void noInlineOnchange(Path template) throws IOException {
        // Match onchange= as a standalone attribute, not as part of data-onchange=
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
    @DisplayName("Template has no inline <script> block")
    void noInlineScript(Path template) throws IOException {
        String content = Files.readString(template);
        String[] lines = content.split("\n");
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("<script") && !line.contains("src=") && !line.contains("src =")) {
                if (!lines[i].contains("th:src") && !lines[i].contains(" src")) {
                    violations.add("Line " + (i+1) + ": " + line);
                }
            }
        }
        assertThat(violations)
            .as("Template %s must not contain inline <script> blocks", template)
            .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    @DisplayName("Template has no hardcoded localhost:8080 URL (bBuilder port)")
    void noHardcodedBuilderPort(Path template) throws IOException {
        assertThat(Files.readString(template))
            .as("Template %s must not hardcode localhost:8080", template)
            .doesNotContain("localhost:8080");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTemplates")
    @DisplayName("Viewer template has no hardcoded localhost:8081 URL")
    void noHardcodedCounterPort(Path template) throws IOException {
        // Only applies to viewer templates — bCounter's own templates
        // may legitimately reference port 8081 for progress polling
        if (!template.toString().contains("viewer")) return;
        assertThat(Files.readString(template))
            .as("Viewer template %s must not hardcode localhost:8081 — "
                + "use /viewer/** endpoints instead", template)
            .doesNotContain("localhost:8081");
    }
}
