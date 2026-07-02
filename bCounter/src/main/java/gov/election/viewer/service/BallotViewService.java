/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.viewer.service;

import gov.election.counter.entity.*;
import gov.election.counter.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Read-only service for the bViewer UI.
 * Uses bCounter's existing JPA entities and repositories — no duplicate mappings.
 *
 * FILTER METHODS:
 *   listByGlob(pattern) — server-side equivalent of the JS glob filter in index.html.
 *                          Matches against imageName and imagePath.
 *   listBySql(clause)   — executes a caller-supplied SQL WHERE clause against the
 *                          ballot_images / vote_opportunities / contest_records tables.
 *                          A strict token allowlist guards against injection.
 */
@Service
public class BallotViewService {

    private static final Logger log = LoggerFactory.getLogger(BallotViewService.class);

    private final BallotImageRepository     imageRepo;
    private final VoteOpportunityRepository voRepo;
    private final CandidateRecordRepository candRepo;
    private final ContestRecordRepository   contestRepo;
    private final JdbcTemplate              jdbc;

    public BallotViewService(BallotImageRepository imageRepo,
                              VoteOpportunityRepository voRepo,
                              CandidateRecordRepository candRepo,
                              ContestRecordRepository   contestRepo,
                              JdbcTemplate              jdbc) {
        this.imageRepo   = imageRepo;
        this.voRepo      = voRepo;
        this.candRepo    = candRepo;
        this.contestRepo = contestRepo;
        this.jdbc        = jdbc;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public static class BallotImageSummary {
        public final Long   id;
        public final String imageName;
        public final String imagePath;
        public final int    dpi;
        public final boolean wasRotated;
        public BallotImageSummary(BallotImage b) {
            this.id         = b.getId();
            this.imageName  = b.getImageName();
            this.imagePath  = b.getImagePath();
            this.dpi        = b.getDpi();
            this.wasRotated = b.isWasRotated();
        }
        public Long   getId()         { return id; }
        public String getImageName()  { return imageName; }
        public String getImagePath()  { return imagePath; }
        public int    getDpi()        { return dpi; }
        public boolean isWasRotated() { return wasRotated; }
    }

    public static class BallotView {
        public final long         imageId;
        public final String       imageName;
        public final String       imagePath;
        public final String       resolvedPath;
        public final int          canonicalWidth;
        public final int          canonicalHeight;
        public final int          warpDpi;
        public final int          dpi;
        public final boolean      wasRotated;
        public final String       cornerMarks;
        public final List<IndicatorBox> boxes;

        public BallotView(BallotImage img, List<IndicatorBox> boxes) {
            this.imageId         = img.getId();
            this.imageName       = img.getImageName();
            this.imagePath       = img.getImagePath();
            this.resolvedPath    = resolveImagePath(img.getImagePath());
            this.canonicalWidth  = img.getCanonicalWidth();
            this.canonicalHeight = img.getCanonicalHeight();
            this.warpDpi         = img.getWarpDpi();
            this.dpi             = img.getDpi();
            this.wasRotated      = img.isWasRotated();
            this.cornerMarks     = img.getCornerMarks();
            this.boxes           = boxes;
        }

        public long    getImageId()         { return imageId; }
        public String  getImageName()       { return imageName; }
        public String  getImagePath()       { return imagePath; }
        public String  getResolvedPath()    { return resolvedPath; }
        public int     getCanonicalWidth()  { return canonicalWidth; }
        public int     getCanonicalHeight() { return canonicalHeight; }
        public int     getWarpDpi()         { return warpDpi; }
        public int     getDpi()             { return dpi; }
        public boolean isWasRotated()       { return wasRotated; }
        public String  getCornerMarks()     { return cornerMarks; }
        public List<IndicatorBox> getBoxes(){ return boxes; }

        private static String resolveImagePath(String path) {
            if (path == null) return null;
            if (Files.exists(Paths.get(path))) return path;
            String counted = path.replaceAll(
                "\\.(png|jpg|jpeg|tif|tiff|bmp)$", ".counted");
            if (Files.exists(Paths.get(counted))) return counted;
            return path;
        }
    }

    public static class IndicatorBox {
        public final long    id;
        public final int     x, y, w, h;
        public final String  contest;
        public final String  contestType;
        public final String  candidate;
        public final String  status;
        public final String  color;
        public final String  label;
        public final boolean rankedChoice;
        public final String  statusLabel;

        public IndicatorBox(VoteOpportunity vo,
                            ContestRecord   contest,
                            CandidateRecord candidate) {
            this.id          = vo.getId();
            this.x           = vo.getAbsLeft();
            this.y           = vo.getAbsTop();
            this.w           = vo.getIndicatorWidth();
            this.h           = vo.getIndicatorHeight();
            this.contest     = contest.getContestTitle();
            this.contestType = contest.getContestType();
            this.candidate   = candidate.getCandidateName();
            this.status      = vo.getVoteStatus() != null
                               ? vo.getVoteStatus().name() : "UNMARKED";
            this.color       = switch (this.status) {
                case "VOTED"     -> "#22c55e";
                case "OVERVOTED" -> "#eab308";
                default          -> "#3b82f6";
            };
            Matcher rankM = Pattern.compile("\\(Rank (\\d+)\\)$")
                                   .matcher(this.candidate);
            this.rankedChoice = rankM.find();
            if (this.rankedChoice) {
                String rank  = rankM.group(1);
                this.label       = this.candidate
                    .replaceAll("\\s*\\(Rank \\d+\\)$", "") + " [R" + rank + "]";
                this.statusLabel = "VOTED".equals(this.status)
                    ? "MARKED at Rank " + rank : this.status;
            } else {
                this.label       = this.candidate;
                this.statusLabel = this.status;
            }
        }

        public long    getId()          { return id; }
        public int     getX()           { return x; }
        public int     getY()           { return y; }
        public int     getW()           { return w; }
        public int     getH()           { return h; }
        public String  getContest()     { return contest; }
        public String  getContestType() { return contestType; }
        public String  getCandidate()   { return candidate; }
        public String  getStatus()      { return status; }
        public String  getColor()       { return color; }
        public String  getLabel()       { return label; }
        public boolean isRankedChoice() { return rankedChoice; }
        public String  getStatusLabel() { return statusLabel; }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BallotImageSummary> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        Map<Long, BallotImage> byId = imageRepo.findAllById(ids).stream()
            .collect(Collectors.toMap(BallotImage::getId, b -> b));
        // Preserve the order from the filter (already sorted by image_name from query)
        return ids.stream()
            .map(byId::get)
            .filter(Objects::nonNull)
            .map(BallotImageSummary::new)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<BallotImageSummary> listAll() {
        return imageRepo.findAll().stream()
            .sorted(Comparator.comparing(BallotImage::getImageName))
            .map(BallotImageSummary::new)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Optional<BallotView> findById(Long id) {
        return imageRepo.findById(id).map(this::toBallotView);
    }

    @Transactional(readOnly = true)
    public Optional<BallotView> findByPath(String path) {
        return imageRepo.findByImagePath(path).map(this::toBallotView);
    }

    // ── Glob filter ───────────────────────────────────────────────────────────

    /**
     * Server-side equivalent of the JS globToRegex() in index.html.
     * Matches against imageName and imagePath (case-insensitive).
     * '*' matches anything including '/'; '?' matches any single character.
     * If the pattern contains '/', it is end-anchored (path-style match).
     *
     * @param pattern  glob pattern as typed by the user
     * @return ordered list of matching summaries (alphabetical by imageName)
     */
    @Transactional(readOnly = true)
    public List<BallotImageSummary> listByGlob(String pattern) {
        if (pattern == null || pattern.isBlank()) return listAll();

        String lower  = pattern.toLowerCase();
        Pattern regex = globToRegex(lower);
        boolean pathStyle = lower.contains("/");

        return imageRepo.findAll().stream()
            .sorted(Comparator.comparing(BallotImage::getImageName))
            .filter(b -> {
                String name = b.getImageName() != null
                    ? b.getImageName().toLowerCase() : "";
                String path = b.getImagePath() != null
                    ? b.getImagePath().toLowerCase() : name;
                if (pathStyle) {
                    return regex.matcher(path).find()
                        || regex.matcher(name).find();
                } else {
                    return regex.matcher(name).find()
                        || regex.matcher(path).find();
                }
            })
            .map(BallotImageSummary::new)
            .collect(Collectors.toList());
    }

    private static Pattern globToRegex(String glob) {
        // Escape dots, convert ? → single char, * → anything
        String re = glob
            .replace(".", "\\.")
            .replace("?", ".")
            .replace("*", ".*");
        // Path-style patterns are end-anchored; plain patterns are unanchored
        String suffix = (glob.contains("/") && !glob.endsWith("*")) ? ".*" : "";
        return glob.contains("/")
            ? Pattern.compile(re + suffix + "$",  Pattern.CASE_INSENSITIVE)
            : Pattern.compile(re,                 Pattern.CASE_INSENSITIVE);
    }

    // ── SQL filter ────────────────────────────────────────────────────────────

    /**
     * Execute a user-supplied SQL WHERE clause and return matching ballot IDs
     * in alphabetical order.
     *
     * The clause is appended to a fixed SELECT against the allowed tables:
     *
     *   SELECT DISTINCT bi.id, bi.image_name
     *   FROM ballot_image bi
     *   LEFT JOIN vote_opportunity vo ON vo.ballot_image_id = bi.id
     *   LEFT JOIN contest cr          ON cr.id = vo.contest_id
     *   LEFT JOIN candidate cdr       ON cdr.id = vo.candidate_id_fk
     *   LEFT JOIN barcode b           ON b.id = bi.barcode_id
     *   WHERE <clause>
     *   ORDER BY bi.image_name
     *
     * Available columns (use these names in the WHERE clause):
     *   bi.image_name        bi.image_path        bi.page_number
     *   bi.corners_found     bi.was_rotated
     *   b.raw_data           b.region_id          b.party_id
     *   b.election_id        b.jurisdiction_id
     *   cr.contest_title     cr.contest_type       cr.max_votes
     *   cdr.candidate_name
     *   vo.vote_status       vo.dark_pct
     *
     * Example clauses:
     *   cr.contest_title = 'Mayor'
     *   vo.vote_status = 'VOTED' AND cr.contest_title = 'Mayor'
     *   bi.barcode_data LIKE '1|2|%'
     *
     * SECURITY: the clause is validated by a strict token allowlist before
     * execution.  Any token not in the allowlist causes rejection.
     *
     * @param whereClause  SQL WHERE clause (without the WHERE keyword)
     * @return ordered list of matching summaries
     * @throws IllegalArgumentException if the clause fails allowlist validation
     */
    @Transactional(readOnly = true)
    public List<BallotImageSummary> listBySql(String whereClause) {
        if (whereClause == null || whereClause.isBlank()) return listAll();

        validateSqlClause(whereClause);   // throws on invalid input

        String sql = """
            SELECT DISTINCT bi.id, bi.image_name
            FROM ballot_image bi
            LEFT JOIN vote_opportunity vo ON vo.ballot_image_id = bi.id
            LEFT JOIN contest cr          ON cr.id = vo.contest_id
            LEFT JOIN candidate cdr       ON cdr.id = vo.candidate_id_fk
            LEFT JOIN barcode b           ON b.id = bi.barcode_id
            WHERE %s
            ORDER BY bi.image_name
            """.formatted(whereClause);

        log.info("BallotViewService SQL filter: {}", sql);

        List<Long> ids = jdbc.query(sql,
            (rs, rowNum) -> rs.getLong("id"));

        if (ids.isEmpty()) return Collections.emptyList();

        // Load full summaries for the matched IDs, preserving order
        Map<Long, BallotImage> byId = imageRepo.findAllById(ids).stream()
            .collect(Collectors.toMap(BallotImage::getId, b -> b));

        return ids.stream()
            .map(byId::get)
            .filter(Objects::nonNull)
            .map(BallotImageSummary::new)
            .collect(Collectors.toList());
    }

    /**
     * Validates a SQL WHERE clause against a strict token allowlist.
     *
     * Allowed tokens:
     *   - Table.column references from the known schema
     *   - SQL operators and keywords: =, !=, <>, <, >, <=, >=, LIKE, NOT, AND,
     *     OR, IN, IS, NULL, TRUE, FALSE, BETWEEN, LOWER, UPPER, TRIM, CAST, AS
     *   - String literals (single-quoted)
     *   - Numeric literals
     *   - Parentheses, comma, percent (wildcard), underscore (LIKE single-char),
     *     pipe (barcode separator)
     *
     * Anything else — semicolons, double-dashes, block comments, subselects,
     * DDL keywords, etc. — causes immediate rejection.
     *
     * @throws IllegalArgumentException describing the invalid token
     */
    static void validateSqlClause(String clause) {
        // Hard rejects — these patterns are never legitimate in a WHERE clause
        String upper = clause.toUpperCase();
        for (String forbidden : FORBIDDEN_PATTERNS) {
            if (upper.contains(forbidden)) {
                throw new IllegalArgumentException(
                    "SQL clause contains forbidden pattern: " + forbidden);
            }
        }

        // Tokenise and check each token against the allowlist
        // Tokeniser: split on whitespace and operators but keep quoted strings intact
        List<String> tokens = tokenise(clause);
        for (String token : tokens) {
            if (!isAllowedToken(token)) {
                throw new IllegalArgumentException(
                    "SQL clause contains disallowed token: \"" + token + "\". "
                    + "Only references to known columns and SQL comparison operators "
                    + "are permitted.");
            }
        }
    }

    private static final Set<String> FORBIDDEN_PATTERNS = Set.of(
        ";", "--", "/*", "*/", "DROP", "DELETE", "INSERT", "UPDATE",
        "CREATE", "ALTER", "TRUNCATE", "EXEC", "EXECUTE", "UNION",
        "SELECT", "FROM", "JOIN", "HAVING", "GROUP BY", "ORDER BY",
        "LIMIT", "OFFSET", "INTO", "VALUES", "PRAGMA", "ATTACH"
    );

    private static final Set<String> ALLOWED_COLUMNS = Set.of(
        // ballot_image
        "bi.id", "bi.image_name", "bi.image_path",
        "bi.page_number", "bi.corners_found", "bi.was_rotated",
        "bi.dpi", "bi.warp_dpi",
        // barcode (joined as b)
        "b.raw_data", "b.region_id", "b.party_id",
        "b.election_id", "b.jurisdiction_id", "b.ballot_type_id",
        // contest (joined as cr)
        "cr.contest_title", "cr.contest_type", "cr.max_votes",
        // candidate (joined as cdr)
        "cdr.candidate_name",
        // vote_opportunity (joined as vo)
        "vo.vote_status", "vo.dark_pct",
        "vo.abs_left", "vo.abs_top"
    );

    private static final Set<String> ALLOWED_KEYWORDS = Set.of(
        "AND", "OR", "NOT", "IN", "IS", "NULL", "TRUE", "FALSE",
        "LIKE", "BETWEEN", "LOWER", "UPPER", "TRIM", "CAST", "AS",
        "=", "!=", "<>", "<", ">", "<=", ">=",
        "(", ")", ",", "%", "_", "|"
    );

    private static List<String> tokenise(String clause) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < clause.length()) {
            char c = clause.charAt(i);

            // Skip whitespace
            if (Character.isWhitespace(c)) { i++; continue; }

            // Single-quoted string literal — consume whole thing as one token
            if (c == '\'') {
                int j = i + 1;
                while (j < clause.length()) {
                    if (clause.charAt(j) == '\'') {
                        // doubled quote = escaped quote inside literal
                        if (j + 1 < clause.length() && clause.charAt(j + 1) == '\'') {
                            j += 2;
                        } else {
                            j++; break;
                        }
                    } else {
                        j++;
                    }
                }
                tokens.add(clause.substring(i, j));
                i = j;
                continue;
            }

            // Numeric literal
            if (Character.isDigit(c) || (c == '-' && i + 1 < clause.length()
                    && Character.isDigit(clause.charAt(i + 1)))) {
                int j = i + 1;
                while (j < clause.length()
                    && (Character.isDigit(clause.charAt(j)) || clause.charAt(j) == '.'))
                    j++;
                tokens.add(clause.substring(i, j));
                i = j;
                continue;
            }

            // Two-character operators
            if (i + 1 < clause.length()) {
                String two = clause.substring(i, i + 2);
                if (two.equals("!=") || two.equals("<>")
                        || two.equals("<=") || two.equals(">=")) {
                    tokens.add(two); i += 2; continue;
                }
            }

            // Single-character operators and punctuation
            if ("=<>(),|%_".indexOf(c) >= 0) {
                tokens.add(String.valueOf(c)); i++; continue;
            }

            // Identifier or keyword (letters, digits, dot, underscore)
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < clause.length()
                    && (Character.isLetterOrDigit(clause.charAt(j))
                        || clause.charAt(j) == '_'
                        || clause.charAt(j) == '.'))
                    j++;
                tokens.add(clause.substring(i, j));
                i = j;
                continue;
            }

            // Anything else is suspicious — add as-is, will fail allowlist check
            tokens.add(String.valueOf(c));
            i++;
        }
        return tokens;
    }

    private static boolean isAllowedToken(String token) {
        if (token == null || token.isBlank()) return true;
        String upper = token.toUpperCase();
        // String literal
        if (token.startsWith("'") && token.endsWith("'")) return true;
        // Numeric literal
        if (token.matches("-?\\d+(\\.\\d+)?")) return true;
        // Known column reference
        if (ALLOWED_COLUMNS.contains(token.toLowerCase())) return true;
        // Known keyword or operator
        if (ALLOWED_KEYWORDS.contains(upper)) return true;
        return false;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private BallotView toBallotView(BallotImage img) {
        List<VoteOpportunity> vos = voRepo.findByBallotImage_Id(img.getId());

        List<IndicatorBox> boxes = vos.stream()
            .map(vo -> new IndicatorBox(vo, vo.getContest(), vo.getCandidate()))
            .collect(Collectors.toList());

        return new BallotView(img, boxes);
    }
}
