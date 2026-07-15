/*
 * Copyright (C) 2026 Mitch Trachtenberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.mjtrac.ballot;

import com.mjtrac.ballot.service.BallotLayoutService;
import com.mjtrac.ballot.service.ExportService;
import com.mjtrac.ballot.util.BallotDimensions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    @Mock BallotLayoutService layoutService;
    @InjectMocks ExportService exportService;

    @BeforeEach
    void setUp() {
        List<BallotDimensions.CandidatePosition> candidates = List.of(
            new BallotDimensions.CandidatePosition(1L, "Alice", false, 0.5, 1.2, 0.306, 0.153, "OVAL"),
            new BallotDimensions.CandidatePosition(2L, "Bob",   false, 0.5, 1.8, 0.306, 0.153, "OVAL")
        );
        List<BallotDimensions.ContestPosition> contests = List.of(
            new BallotDimensions.ContestPosition(10L, "Mayor", "PLURALITY", 1, 1,
                0.1, 0.1, 2.0, 1.5, candidates)
        );
        List<BallotDimensions.PageLayout> pages = List.of(
            new BallotDimensions.PageLayout(1, 0.5, 0.5, 7.5, 10.0, contests,
                new double[][]{{0.4,0.4},{8.1,0.4},{8.1,10.6},{0.4,10.6}})
        );
        when(layoutService.getLayout(99L)).thenReturn(pages);
    }

    @Test @DisplayName("XML output contains side_number wrapper")
    void xmlContainsSide() {
        String xml = exportService.exportOffsetReportXml(99L, ExportService.MeasurementUnit.INCHES);
        assertThat(xml).contains("<side number=\"1\">");
        assertThat(xml).contains("</side>");
    }

    @Test @DisplayName("XML output contains ballotContentArea as first entry in each side")
    void xmlContainsContentArea() {
        String xml = exportService.exportOffsetReportXml(99L, ExportService.MeasurementUnit.INCHES);
        assertThat(xml).contains("<ballotContentArea>");
        int contentAreaIdx = xml.indexOf("<ballotContentArea>");
        int contestIdx     = xml.indexOf("<contest ");
        assertThat(contentAreaIdx).isLessThan(contestIdx);
    }

    @Test @DisplayName("XML contest entry has title and bounding box")
    void xmlContestInfo() {
        String xml = exportService.exportOffsetReportXml(99L, ExportService.MeasurementUnit.INCHES);
        assertThat(xml).contains("title=\"Mayor\"");
        assertThat(xml).contains("<boundingBox>");
        assertThat(xml).contains("<width>");
        assertThat(xml).contains("<height>");
    }

    @Test @DisplayName("XML candidate has indicator bounding box")
    void xmlCandidateIndicator() {
        String xml = exportService.exportOffsetReportXml(99L, ExportService.MeasurementUnit.INCHES);
        assertThat(xml).contains("Alice");
        assertThat(xml).contains("<indicator>");
    }

    @Test @DisplayName("YAML output has sides list")
    void yamlContainsSides() {
        String yaml = exportService.exportOffsetReportYaml(99L, ExportService.MeasurementUnit.INCHES);
        assertThat(yaml).contains("sides:");
        assertThat(yaml).contains("side_number: 1");
    }

    @Test @DisplayName("YAML output contains ballotContentArea before contests")
    void yamlContentAreaFirst() {
        String yaml = exportService.exportOffsetReportYaml(99L, ExportService.MeasurementUnit.INCHES);
        assertThat(yaml).contains("ballotContentArea:");
        int caIdx = yaml.indexOf("ballotContentArea:");
        int cIdx  = yaml.indexOf("contests:");
        assertThat(caIdx).isLessThan(cIdx);
    }

    @Test @DisplayName("MM conversion produces different values than inches")
    void mmConversion() {
        String in_xml = exportService.exportOffsetReportXml(99L, ExportService.MeasurementUnit.INCHES);
        String mm_xml = exportService.exportOffsetReportXml(99L, ExportService.MeasurementUnit.MM);
        assertThat(in_xml).isNotEqualTo(mm_xml);
        assertThat(mm_xml).contains("unit=\"mm\"");
    }

    @Test @DisplayName("Missing layout data throws IllegalStateException")
    @MockitoSettings(strictness = Strictness.LENIENT)
    void missingDataThrows() {
        when(layoutService.getLayout(0L))
            .thenThrow(new IllegalStateException("No layout data"));
        assertThatThrownBy(() ->
            exportService.exportOffsetReportXml(0L, ExportService.MeasurementUnit.INCHES))
            .isInstanceOf(IllegalStateException.class);
    }
}
