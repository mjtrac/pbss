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
package com.mjtrac.ballot.service;

import com.mjtrac.ballot.model.Candidate;
import com.mjtrac.ballot.model.Contest;
import com.mjtrac.ballot.repository.ContestRepository;
import com.mjtrac.ballot.util.BallotDimensions;
import com.mjtrac.ballot.util.MeasurementUtil;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

/**
 * Produces bounding-box reports (XML or YAML) and OCR name lists.
 *
 * Report structure (per side/page):
 *   - ballotContentArea: the heavy outer border enclosing all contest columns,
 *     expressed as width × height.  Offsets are always (0, 0) since all other
 *     coordinates are relative to this box's upper-left corner.
 *   - contests: list of contest boxes, each with its own bounding box and
 *     candidate indicator bounding boxes.
 *
 * For multi-page ballots, all data is nested under side_number elements.
 */
@Service
public class ExportService {

    public enum MeasurementUnit { INCHES, MM }

    private final BallotLayoutService layoutService;
    private final ContestRepository   contestRepository;

    public ExportService(BallotLayoutService layoutService,
                         ContestRepository contestRepository) {
        this.layoutService     = layoutService;
        this.contestRepository = contestRepository;
    }

    // ── XML bounding-box report ────────────────────────────────────────────

    /** Export XML for all pages. */
    public String exportOffsetReportXml(Long combinationId, MeasurementUnit unit) {
        return exportOffsetReportXml(combinationId, unit, 0);
    }

    /** Export XML for a specific page only (pageFilter=0 means all pages). */
    public String exportOffsetReportXml(Long combinationId, MeasurementUnit unit, int pageFilter) {
        List<BallotDimensions.PageLayout> pages = layoutService.getLayout(combinationId);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<ballotBoundingBoxReport combinationId=\"")
          .append(combinationId).append("\" unit=\"")
          .append(unit.name().toLowerCase()).append("\">\n");

        for (BallotDimensions.PageLayout page : pages) {
            if (pageFilter > 0 && page.getPageNumber() != pageFilter) continue;
            sb.append("  <side number=\"").append(page.getPageNumber()).append("\">\n");

            // ── Content area bounding box (first entry, offset from page upper-left) ──
            sb.append("    <ballotContentArea>\n");
            sb.append("      <offsetFromLeft>").append(fmt(page.getContentAreaOffsetLeftInches(), unit)).append("</offsetFromLeft>\n");
            sb.append("      <offsetFromTop>") .append(fmt(page.getContentAreaOffsetTopInches(),  unit)).append("</offsetFromTop>\n");
            sb.append("      <width>")         .append(fmt(page.getContentAreaWidthInches(),      unit)).append("</width>\n");
            sb.append("      <height>")        .append(fmt(page.getContentAreaHeightInches(),     unit)).append("</height>\n");
            sb.append("    </ballotContentArea>\n");

            // Corner registration marks (TL, TR, BR, BL — page-absolute from image top-left)
            String[] markNames = {"TL", "TR", "BR", "BL"};
            double[][] marks = page.getCornerMarksInches();
            sb.append("    <cornerMarks>\n");
            for (int mi = 0; mi < 4 && marks != null; mi++) {
                sb.append("      <mark corner=\"").append(markNames[mi]).append("\">\n");
                sb.append("        <x>").append(fmt(marks[mi][0], unit)).append("</x>\n");
                sb.append("        <y>").append(fmt(marks[mi][1], unit)).append("</y>\n");
                sb.append("      </mark>\n");
            }
            sb.append("    </cornerMarks>\n");

            // ── Contests ──────────────────────────────────────────────────────
            sb.append("    <contests>\n");
            for (BallotDimensions.ContestPosition cp : page.getContests()) {
                sb.append("      <contest id=\"").append(cp.getContestId())
                  .append("\" title=\"").append(escapeXml(cp.getContestTitle()))
                  .append("\" contestType=\"").append(cp.getContestType())
                  .append("\" maxVotes=\"").append(cp.getMaxVotes()).append("\">\n");

                sb.append("        <boundingBox>\n");
                sb.append("          <offsetFromLeft>").append(fmt(cp.getOffsetFromLeftInches(), unit)).append("</offsetFromLeft>\n");
                sb.append("          <offsetFromTop>") .append(fmt(cp.getOffsetFromTopInches(),  unit)).append("</offsetFromTop>\n");
                sb.append("          <width>")         .append(fmt(cp.getWidthInches(),           unit)).append("</width>\n");
                sb.append("          <height>")        .append(fmt(cp.getHeightInches(),          unit)).append("</height>\n");
                sb.append("        </boundingBox>\n");

                sb.append("        <candidates>\n");
                for (BallotDimensions.CandidatePosition cand : cp.getCandidates()) {
                    sb.append("          <candidate id=\"").append(cand.getCandidateId())
                      .append("\" name=\"").append(escapeXml(cand.getCandidateName()))
                      .append("\" writeIn=\"").append(cand.isWriteIn()).append("\">\n");
                    sb.append("            <indicator>\n");
                    sb.append("              <offsetFromLeft>").append(fmt(cand.getIndicatorOffsetFromLeftInches(), unit)).append("</offsetFromLeft>\n");
                    sb.append("              <offsetFromTop>") .append(fmt(cand.getIndicatorOffsetFromTopInches(),  unit)).append("</offsetFromTop>\n");
                    sb.append("              <width>")         .append(fmt(cand.getIndicatorWidthInches(),          unit)).append("</width>\n");
                    sb.append("              <height>")        .append(fmt(cand.getIndicatorHeightInches(),         unit)).append("</height>\n");
                    sb.append("            </indicator>\n");
                    sb.append("          </candidate>\n");
                }
                sb.append("        </candidates>\n");
                sb.append("      </contest>\n");
            }
            sb.append("    </contests>\n");
            sb.append("  </side>\n");
        }

        sb.append("</ballotBoundingBoxReport>\n");
        return sb.toString();
    }

    // ── YAML bounding-box report ───────────────────────────────────────────

    /** Export YAML for all pages. */
    public String exportOffsetReportYaml(Long combinationId, MeasurementUnit unit) {
        return exportOffsetReportYaml(combinationId, unit, 0);
    }

    /** Export YAML for a specific page only (pageFilter=0 means all pages). */
    public String exportOffsetReportYaml(Long combinationId, MeasurementUnit unit, int pageFilter) {
        List<BallotDimensions.PageLayout> pages = layoutService.getLayout(combinationId);

        List<Map<String, Object>> sideList = new ArrayList<>();
        for (BallotDimensions.PageLayout page : pages) {
            if (pageFilter > 0 && page.getPageNumber() != pageFilter) continue;
            Map<String, Object> sideMap = new LinkedHashMap<>();
            sideMap.put("side_number", page.getPageNumber());

            // Content area bounding box (first entry, offset from page upper-left)
            Map<String, Object> contentArea = new LinkedHashMap<>();
            contentArea.put("offsetFromLeft", fmt(page.getContentAreaOffsetLeftInches(), unit));
            contentArea.put("offsetFromTop",  fmt(page.getContentAreaOffsetTopInches(),  unit));
            contentArea.put("width",  fmt(page.getContentAreaWidthInches(),  unit));
            contentArea.put("height", fmt(page.getContentAreaHeightInches(), unit));
            sideMap.put("ballotContentArea", contentArea);

            // Corner registration marks
            String[] markNames = {"TL", "TR", "BR", "BL"};
            double[][] marks = page.getCornerMarksInches();
            List<Map<String, Object>> cornerMarkList = new ArrayList<>();
            for (int mi = 0; mi < 4 && marks != null; mi++) {
                Map<String, Object> mk = new LinkedHashMap<>();
                mk.put("corner", markNames[mi]);
                mk.put("x", fmt(marks[mi][0], unit));
                mk.put("y", fmt(marks[mi][1], unit));
                cornerMarkList.add(mk);
            }
            sideMap.put("cornerMarks", cornerMarkList);

            // Page-level marks (PTL, PTR at top of page)
            double[][] pm = page.getPageMarksInches();
            if (pm != null && pm.length >= 2) {
                String[] pmNames = {"PTL", "PTR"};
                List<Map<String, Object>> pmList = new ArrayList<>();
                for (int mi = 0; mi < 2; mi++) {
                    Map<String, Object> mk = new LinkedHashMap<>();
                    mk.put("corner", pmNames[mi]);
                    mk.put("x", fmt(pm[mi][0], unit));
                    mk.put("y", fmt(pm[mi][1], unit));
                    pmList.add(mk);
                }
                sideMap.put("pageMarks", pmList);
            }

            // Barcode centre (QR code position for scanner-alignment compensation)
            double[] bc = page.getBarcodeCentreInches();
            if (bc != null && bc.length >= 2) {
                Map<String, Object> barcodePos = new LinkedHashMap<>();
                barcodePos.put("x", fmt(bc[0], unit));
                barcodePos.put("y", fmt(bc[1], unit));
                sideMap.put("barcodeCentre", barcodePos);
            }

            // Contests
            List<Map<String, Object>> contestList = new ArrayList<>();
            for (BallotDimensions.ContestPosition cp : page.getContests()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("id",          cp.getContestId());
                cm.put("title",       cp.getContestTitle());
                cm.put("contestType", cp.getContestType());
                cm.put("maxVotes",    cp.getMaxVotes());

                Map<String, Object> bbox = new LinkedHashMap<>();
                bbox.put("offsetFromLeft", fmt(cp.getOffsetFromLeftInches(), unit));
                bbox.put("offsetFromTop",  fmt(cp.getOffsetFromTopInches(),  unit));
                bbox.put("width",          fmt(cp.getWidthInches(),          unit));
                bbox.put("height",         fmt(cp.getHeightInches(),         unit));
                cm.put("boundingBox", bbox);

                List<Map<String, Object>> candList = new ArrayList<>();
                for (BallotDimensions.CandidatePosition cand : cp.getCandidates()) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id",      cand.getCandidateId());
                    c.put("name",    cand.getCandidateName());
                    c.put("writeIn", cand.isWriteIn());
                    Map<String, Object> ind = new LinkedHashMap<>();
                    ind.put("offsetFromLeft",  fmt(cand.getIndicatorOffsetFromLeftInches(), unit));
                    ind.put("offsetFromTop",   fmt(cand.getIndicatorOffsetFromTopInches(),  unit));
                    ind.put("width",           fmt(cand.getIndicatorWidthInches(),          unit));
                    ind.put("height",          fmt(cand.getIndicatorHeightInches(),         unit));
                    if (cand.getIndicatorStyle() != null)
                        ind.put("indicatorStyle", cand.getIndicatorStyle());
                    c.put("indicator", ind);
                    candList.add(c);
                }
                cm.put("candidates", candList);
                contestList.add(cm);
            }
            sideMap.put("contests", contestList);
            sideList.add(sideMap);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("combinationId", combinationId);
        root.put("unit",          unit.name().toLowerCase());
        root.put("sides",         sideList);

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        return new Yaml(opts).dump(root);
    }

    // ── OCR name list ──────────────────────────────────────────────────────

    public String buildOcrNameList(Long electionId, Long regionId) {
        List<Contest> contests = contestRepository.findByElectionId(electionId);
        StringBuilder sb = new StringBuilder();
        sb.append("=== CONTEST AND CANDIDATE NAME LIST ===\n")
          .append("Election ID: ").append(electionId).append("\n");
        if (regionId != null) sb.append("Region ID: ").append(regionId).append("\n");
        sb.append("\n");
        for (Contest contest : contests) {
            sb.append("CONTEST: ").append(contest.getTitle()).append("\n");
            for (Candidate c : contest.getCandidates())
                sb.append("  CANDIDATE: ").append(c.getName()).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String fmt(double inches, MeasurementUnit unit) {
        double val = unit == MeasurementUnit.MM
                     ? MeasurementUtil.inchesToMm(inches) : inches;
        return String.format("%.4f", val);
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
