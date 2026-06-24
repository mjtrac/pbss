/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package gov.election.counter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import gov.election.counter.model.BboxReport;
import gov.election.counter.model.BboxReport.*;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;
import org.yaml.snakeyaml.Yaml;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Parses bounding-box report files produced by election-ballot-system.
 *
 * Supports both XML and YAML formats.  If both are present the XML is used
 * (it is produced first by the generation service).
 *
 * NESTING MODEL:
 * All offsets in the report are relative to the immediately enclosing element:
 *   - contest.offsetLeft/Top is relative to the content area upper-left
 *   - indicator.offsetLeft/Top is relative to the contest box upper-left
 *
 * Absolute page position = sum of all enclosing offsets (see IndicatorBox.absolutePosition).
 */
@Service
public class BboxReportLoader {

    @org.springframework.beans.factory.annotation.Value("${ballot.layout.format:yaml}")
    private String layoutFormat;


    private static final Logger log =
        LoggerFactory.getLogger(BboxReportLoader.class);

    private static final Set<String> IMAGE_EXTS =
        Set.of(".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp");

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Find the first XML file in the given folder.
     * Returns the path or null if none found.
     */
    public Path findXml(Path folder) throws IOException {
        try (var stream = Files.list(folder)) {
            return stream.filter(p -> p.toString().toLowerCase().endsWith(".xml"))
                         .sorted().findFirst().orElse(null);
        }
    }

    /**
     * Find the first YAML file in the given folder (kept for compatibility).
     * Use loadAllFromFolder() to get all pages across multiple YAML files.
     */
    public Path findYaml(Path folder) throws IOException {
        try (var stream = Files.list(folder)) {
            return stream.filter(p -> {
                String s = p.toString().toLowerCase();
                return s.endsWith(".yaml") || s.endsWith(".yml");
            }).sorted().findFirst().orElse(null);
        }
    }

    /**
     * Find the YAML file(s) in the given folder that correspond to a specific
     * ballot barcode (e.g. "1|3|1|1|1|1").
     *
     * The barcode encodes: election|jurisdiction|party|ballotType|combination|page
     * The YAML filename pattern is: ballot_E_J_P_T_C_P.yaml
     * where E=election, J=jurisdiction, P=party, T=ballotType, C=combination.
     * We match on the first 5 fields (ignoring the page number suffix).
     *
     * If no barcode-specific file is found, falls back to all YAML files in the folder.
     *
     * @param folder    directory containing YAML layout files
     * @param barcode   barcode string e.g. "1|3|1|1|1|1"
     * @return list of PageLayout for all pages of this ballot combination
     */
    public List<PageLayout> loadForBarcode(Path folder, String barcode) throws Exception {
        if (barcode == null || barcode.isBlank()) return loadAllFromFolder(folder);

        // Build filename prefix from barcode fields 1-5 (election|jurisdiction|party|type|combo)
        String[] parts = barcode.split("\\|");
        if (parts.length < 5) return loadAllFromFolder(folder);

        // Pattern: ballot_E_J_P_T_C_*.yaml  (page number varies)
        String prefix = "ballot_" + parts[0] + "_" + parts[1] + "_"
                      + parts[2] + "_" + parts[3] + "_" + parts[4] + "_";

        List<java.nio.file.Path> matches;
        try (var stream = java.nio.file.Files.list(folder)) {
            matches = stream.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.startsWith(prefix.toLowerCase())
                    && (name.endsWith(".yaml") || name.endsWith(".yml"));
            }).sorted().collect(java.util.stream.Collectors.toList());
        }

        if (matches.isEmpty()) {
            log.warn("No YAML found for barcode " + barcode
                + " (prefix=" + prefix + ") in " + folder + " -- loading all");
            return loadAllFromFolder(folder);
        }

        log.info("Loading " + matches.size() + " YAML page(s) for barcode "
            + barcode + ": " + matches.get(0).getFileName());

        List<PageLayout> pages = new java.util.ArrayList<>();
        for (java.nio.file.Path p : matches) {
            try {
                pages.addAll(loadYaml(p));
            } catch (Exception e) {
                log.warn("Could not load " + p + ": " + e.getMessage());
            }
        }
        return pages;
    }

    /**
     * Find all YAML/XML files in the folder and merge their page layouts.
     * This correctly handles multi-page ballots where each page has its own file.
     */
    public List<PageLayout> loadAllFromFolder(Path folder) throws IOException {
        List<PageLayout> allPages = new ArrayList<>();
        boolean preferXml = "xml".equalsIgnoreCase(layoutFormat);

        // Collect both types
        List<Path> xmlFiles, yamlFiles;
        try (var stream = Files.list(folder)) {
            xmlFiles = stream.filter(p -> p.toString().toLowerCase().endsWith(".xml"))
                             .sorted().collect(java.util.stream.Collectors.toList());
        }
        try (var stream = Files.list(folder)) {
            yamlFiles = stream.filter(p -> {
                String s = p.toString().toLowerCase();
                return s.endsWith(".yaml") || s.endsWith(".yml");
            }).sorted().collect(java.util.stream.Collectors.toList());
        }

        // Use preferred format; fall back to the other if preferred is absent
        List<Path> primary   = preferXml ? xmlFiles  : yamlFiles;
        List<Path> secondary = preferXml ? yamlFiles : xmlFiles;
        String primaryFmt    = preferXml ? "XML"     : "YAML";
        String secondaryFmt  = preferXml ? "YAML"    : "XML";

        List<Path> toLoad;
        if (!primary.isEmpty()) {
            log.info("Loading ballot layout from " + primaryFmt + " files in " + folder);
            toLoad = primary;
        } else if (!secondary.isEmpty()) {
            log.info("No " + primaryFmt + " files found; falling back to " + secondaryFmt + " in " + folder);
            toLoad = secondary;
            preferXml = !preferXml; // flip so we use the right loader below
        } else {
            log.warn("No YAML or XML layout files found in " + folder);
            return allPages;
        }

        for (Path p : toLoad) {
            try {
                List<PageLayout> pages = preferXml ? loadXml(p) : loadYaml(p);
                allPages.addAll(pages);
            } catch (Exception e) {
                log.warn("Could not load " + p + ": " + e.getMessage());
            }
        }
        return allPages;
    }

    /**
     * Find all image files in the given folder, sorted by name.
     */
    public List<Path> findImages(Path folder) throws IOException {
        List<Path> images = new ArrayList<>();
        try (var stream = Files.list(folder)) {
            stream.filter(p -> {
                String ext = p.toString().toLowerCase();
                int dot = ext.lastIndexOf('.');
                return dot >= 0 && IMAGE_EXTS.contains(ext.substring(dot));
            }).sorted().forEach(images::add);
        }
        return images;
    }

    /**
     * Load a bounding-box report from XML or YAML.
     * If xmlPath is non-null and readable, XML is used; otherwise YAML.
     */
    public List<PageLayout> load(Path xmlPath, Path yamlPath) throws Exception {
        if (xmlPath != null && Files.isReadable(xmlPath)) {
            return loadXml(xmlPath);
        } else if (yamlPath != null && Files.isReadable(yamlPath)) {
            return loadYaml(yamlPath);
        }
        throw new IllegalArgumentException("No readable XML or YAML report found.");
    }

    // ── XML parsing ────────────────────────────────────────────────────────────

    public List<PageLayout> loadXml(Path path) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        // Disable external entity processing for safety
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = dbf.newDocumentBuilder().parse(path.toFile());
        doc.getDocumentElement().normalize();

        List<PageLayout> pages = new ArrayList<>();
        NodeList sideNodes = doc.getElementsByTagName("side");

        for (int i = 0; i < sideNodes.getLength(); i++) {
            Element side = (Element) sideNodes.item(i);
            PageLayout page = new PageLayout();
            page.pageNumber = intAttr(side, "number", 1);

            Element ca = firstChild(side, "ballotContentArea");
            if (ca != null) {
                page.contentAreaOffsetLeft = xmlDouble(ca, "offsetFromLeft");
                page.contentAreaOffsetTop  = xmlDouble(ca, "offsetFromTop");
                page.contentAreaWidth      = xmlDouble(ca, "width");
                page.contentAreaHeight     = xmlDouble(ca, "height");
            }

            // Corner registration marks (TL, TR, BR, BL)
            Element cornerMarksEl = firstChild(side, "cornerMarks");
            if (cornerMarksEl != null) {
                NodeList markNodes = cornerMarksEl.getElementsByTagName("mark");
                page.cornerMarks = new double[4][2];
                String[] order = {"TL","TR","BR","BL"};
                for (int mi = 0; mi < markNodes.getLength(); mi++) {
                    Element me = (Element) markNodes.item(mi);
                    String corner = me.getAttribute("corner");
                    for (int oi = 0; oi < order.length; oi++) {
                        if (order[oi].equals(corner)) {
                            page.cornerMarks[oi][0] = xmlDouble(me, "x");
                            page.cornerMarks[oi][1] = xmlDouble(me, "y");
                        }
                    }
                }
            }

            Element contestsEl = firstChild(side, "contests");
            if (contestsEl != null) {
                NodeList contestNodes = contestsEl.getElementsByTagName("contest");
                for (int j = 0; j < contestNodes.getLength(); j++) {
                    Element ce = (Element) contestNodes.item(j);
                    ContestBox contest = new ContestBox();
                    contest.id          = longAttr(ce, "id");
                    contest.title       = ce.getAttribute("title");
                    contest.contestType = ce.hasAttribute("contestType") ? ce.getAttribute("contestType") : "PLURALITY";
                    contest.maxVotes    = ce.hasAttribute("maxVotes") ? Integer.parseInt(ce.getAttribute("maxVotes")) : 1;
                    contest.page  = page.pageNumber;

                    Element bb = firstChild(ce, "boundingBox");
                    if (bb != null) {
                        contest.offsetLeft = xmlDouble(bb, "offsetFromLeft");
                        contest.offsetTop  = xmlDouble(bb, "offsetFromTop");
                        contest.width      = xmlDouble(bb, "width");
                        contest.height     = xmlDouble(bb, "height");
                    }

                    Element cands = firstChild(ce, "candidates");
                    if (cands != null) {
                        NodeList candNodes = cands.getElementsByTagName("candidate");
                        for (int k = 0; k < candNodes.getLength(); k++) {
                            Element cand = (Element) candNodes.item(k);
                            IndicatorBox ind = new IndicatorBox();
                            ind.candidateId   = longAttr(cand, "id");
                            ind.candidateName = cand.getAttribute("name");
                            ind.writeIn       = "true".equalsIgnoreCase(cand.getAttribute("writeIn"));

                            Element indEl = firstChild(cand, "indicator");
                            if (indEl != null) {
                                ind.offsetLeft     = xmlDouble(indEl, "offsetFromLeft");
                                ind.offsetTop      = xmlDouble(indEl, "offsetFromTop");
                                ind.width          = xmlDouble(indEl, "width");
                                ind.height         = xmlDouble(indEl, "height");
                                String xmlStyle = indEl.getAttribute("indicatorStyle");
                                if (xmlStyle != null && !xmlStyle.isEmpty())
                                    ind.indicatorStyle = xmlStyle;
                            }
                            contest.indicators.add(ind);
                        }
                    }
                    page.contests.add(contest);
                }
            }
            pages.add(page);
        }
        return pages;
    }

    // ── YAML parsing ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<PageLayout> loadYaml(Path path) throws Exception {
        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(path)) {
            root = yaml.load(in);
        }

        List<PageLayout> pages = new ArrayList<>();
        List<Map<String, Object>> sides = castList(root.get("sides"));
        for (Map<String, Object> side : sides) {
            PageLayout page = new PageLayout();
            page.pageNumber = intVal(side.get("side_number"), 1);
            page.sourceFile = path.toAbsolutePath().toString();

            Map<String, Object> ca = castMap(side.get("ballotContentArea"));
            if (ca != null) {
                page.contentAreaOffsetLeft = doubleVal(ca.get("offsetFromLeft"));
                page.contentAreaOffsetTop  = doubleVal(ca.get("offsetFromTop"));
                page.contentAreaWidth      = doubleVal(ca.get("width"));
                page.contentAreaHeight     = doubleVal(ca.get("height"));
            }
            Map<String, Object> bcp = castMap(side.get("barcodeCentre"));
            if (bcp != null) {
                page.barcodeCentreX = doubleVal(bcp.get("x"));
                page.barcodeCentreY = doubleVal(bcp.get("y"));
            }

            // Corner registration marks
            List<Map<String, Object>> cmList = castList(side.get("cornerMarks"));
            if (!cmList.isEmpty()) {
                page.cornerMarks = new double[4][2];
                String[] order = {"TL","TR","BR","BL"};
                for (Map<String, Object> mk : cmList) {
                    String corner = String.valueOf(mk.getOrDefault("corner",""));
                    for (int oi = 0; oi < order.length; oi++) {
                        if (order[oi].equals(corner)) {
                            page.cornerMarks[oi][0] = doubleVal(mk.get("x"));
                            page.cornerMarks[oi][1] = doubleVal(mk.get("y"));
                        }
                    }
                }
            }

            List<Map<String, Object>> contests = castList(side.get("contests"));
            for (Map<String, Object> c : contests) {
                ContestBox contest = new ContestBox();
                contest.id          = longVal(c.get("id"));
                contest.title       = String.valueOf(c.getOrDefault("title", ""));
                contest.contestType = String.valueOf(c.getOrDefault("contestType", "PLURALITY"));
                Object _mv = c.get("maxVotes");
                contest.maxVotes    = _mv instanceof Number ? ((Number)_mv).intValue() : 1;
                contest.page  = page.pageNumber;

                Map<String, Object> bb = castMap(c.get("boundingBox"));
                if (bb != null) {
                    contest.offsetLeft = doubleVal(bb.get("offsetFromLeft"));
                    contest.offsetTop  = doubleVal(bb.get("offsetFromTop"));
                    contest.width      = doubleVal(bb.get("width"));
                    contest.height     = doubleVal(bb.get("height"));
                }

                List<Map<String, Object>> cands = castList(c.get("candidates"));
                for (Map<String, Object> cand : cands) {
                    IndicatorBox ind = new IndicatorBox();
                    ind.candidateId   = longVal(cand.get("id"));
                    ind.candidateName = String.valueOf(cand.getOrDefault("name", ""));
                    Object _wi = cand.get("writeIn");
                    ind.writeIn       = Boolean.TRUE.equals(_wi) || "true".equals(String.valueOf(_wi));

                    Map<String, Object> indMap = castMap(cand.get("indicator"));
                    if (indMap != null) {
                        ind.offsetLeft = doubleVal(indMap.get("offsetFromLeft"));
                        ind.offsetTop  = doubleVal(indMap.get("offsetFromTop"));
                        ind.width      = doubleVal(indMap.get("width"));
                        ind.height     = doubleVal(indMap.get("height"));
                        Object style   = indMap.get("indicatorStyle");
                        if (style != null) ind.indicatorStyle = style.toString();
                    }
                    contest.indicators.add(ind);
                }
                page.contests.add(contest);
            }
            pages.add(page);
        }
        return pages;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Element firstChild(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        return (Element) nl.item(0);
    }

    private double xmlDouble(Element el, String tag) {
        NodeList nl = el.getElementsByTagName(tag);
        if (nl.getLength() == 0) return 0.0;
        String text = nl.item(0).getTextContent().trim().split("\\s+")[0];
        try { return Double.parseDouble(text); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private int intAttr(Element el, String attr, int def) {
        String v = el.getAttribute(attr);
        try { return Integer.parseInt(v); } catch (Exception e) { return def; }
    }

    private Long longAttr(Element el, String attr) {
        String v = el.getAttribute(attr);
        try { return Long.parseLong(v); } catch (Exception e) { return null; }
    }

    private int intVal(Object o, int def) {
        if (o == null) return def;
        try { return Integer.parseInt(String.valueOf(o)); }
        catch (Exception e) { return def; }
    }

    private Long longVal(Object o) {
        if (o == null) return null;
        try { return Long.parseLong(String.valueOf(o)); }
        catch (Exception e) { return null; }
    }

    private double doubleVal(Object o) {
        if (o == null) return 0.0;
        String s = String.valueOf(o).trim().split("\\s+")[0];
        try { return Double.parseDouble(s); } catch (Exception e) { return 0.0; }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object o) {
        if (o instanceof List<?> l) return (List<Map<String, Object>>) l;
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return null;
    }
}
