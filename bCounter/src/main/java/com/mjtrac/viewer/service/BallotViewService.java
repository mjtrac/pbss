/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewer.service;

import com.mjtrac.counter.entity.*;
import com.mjtrac.counter.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Read-only service for the bViewer UI.
 * Uses bCounter's existing JPA entities and repositories — no duplicate mappings.
 */
@Service
public class BallotViewService {

    @PersistenceContext
    private EntityManager em;

    private final BallotImageRepository  imageRepo;
    private final VoteOpportunityRepository voRepo;
    private final CandidateRecordRepository candRepo;
    private final ContestRecordRepository   contestRepo;

    public BallotViewService(BallotImageRepository imageRepo,
                              VoteOpportunityRepository voRepo,
                              CandidateRecordRepository candRepo,
                              ContestRecordRepository   contestRepo) {
        this.imageRepo   = imageRepo;
        this.voRepo      = voRepo;
        this.candRepo    = candRepo;
        this.contestRepo = contestRepo;
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
        public Long   getId()        { return id; }
        public String getImageName() { return imageName; }
        public String getImagePath() { return imagePath; }
        public int    getDpi()       { return dpi; }
        public boolean isWasRotated(){ return wasRotated; }
    }

    public static class BallotView {
        public final long         imageId;
        public final String       imageName;
        public final String       imagePath;
        public final String       resolvedPath;  // .counted fallback
        public final int          canonicalWidth;
        public final int          canonicalHeight;
        public final int          warpDpi;
        public final int          dpi;
        public final boolean      wasRotated;
        public final String       cornerMarks;
        public final List<IndicatorBox> boxes;

        public BallotView(BallotImage img, List<IndicatorBox> boxes) {
            this.imageId        = img.getId();
            this.imageName      = img.getImageName();
            this.imagePath      = img.getImagePath();
            this.resolvedPath   = resolveImagePath(img.getImagePath());
            this.canonicalWidth = img.getCanonicalWidth();
            this.canonicalHeight= img.getCanonicalHeight();
            this.warpDpi        = img.getWarpDpi();
            this.dpi            = img.getDpi();
            this.wasRotated     = img.isWasRotated();
            this.cornerMarks    = img.getCornerMarks();
            this.boxes          = boxes;
        }

        public long    getImageId()        { return imageId; }
        public String  getImageName()      { return imageName; }
        public String  getImagePath()      { return imagePath; }
        public String  getResolvedPath()   { return resolvedPath; }
        public int     getCanonicalWidth() { return canonicalWidth; }
        public int     getCanonicalHeight(){ return canonicalHeight; }
        public int     getWarpDpi()        { return warpDpi; }
        public int     getDpi()            { return dpi; }
        public boolean isWasRotated()      { return wasRotated; }
        public String  getCornerMarks()    { return cornerMarks; }
        public List<IndicatorBox> getBoxes() { return boxes; }
        public String getBoxesJson() {
            try {
                return new ObjectMapper().writeValueAsString(boxes);
            } catch (Exception e) { return "[]"; }
        }

        private static String resolveImagePath(String path) {
            if (path == null) return null;
            if (Files.exists(Paths.get(path))) return path;
            // Try .counted extension
            String counted = path.replaceAll("\\.(png|jpg|jpeg|tif|tiff|bmp)$", ".counted");
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
                String rank = rankM.group(1);
                this.label       = this.candidate.replaceAll("\\s*\\(Rank \\d+\\)$", "")
                                   + " [R" + rank + "]";
                this.statusLabel = "VOTED".equals(this.status)
                                   ? "MARKED at Rank " + rank : this.status;
            } else {
                this.label       = this.candidate;
                this.statusLabel = this.status;
            }
        }

        // Getters for Thymeleaf / Jackson
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


    @Transactional(readOnly = true)
    public List<BallotImageSummary> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        // Preserve the order of ids as returned by the filter
        Map<Long, BallotImage> byId = new HashMap<>();
        imageRepo.findAllById(ids).forEach(img -> byId.put(img.getId(), img));
        return ids.stream()
            .filter(byId::containsKey)
            .map(id -> new BallotImageSummary(byId.get(id)))
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<BallotImageSummary> listByGlob(String pattern) {
        String lower = pattern.toLowerCase();
        // Convert glob to regex: * -> .*, ? -> .
        String regex = pattern.toLowerCase()
            .replace(".", "LITERAL_DOT")
            .replace("*", ".*")
            .replace("?", ".")
            .replace("LITERAL_DOT", "[.]");
        Pattern re = Pattern.compile(regex);
        return imageRepo.findAll().stream()
            .filter(img -> {
                String path = img.getImagePath() != null
                    ? img.getImagePath().toLowerCase() : "";
                String name = img.getImageName() != null
                    ? img.getImageName().toLowerCase() : "";
                return re.matcher(path).find() || re.matcher(name).find();
            })
            .sorted(Comparator.comparing(BallotImage::getImageName))
            .map(BallotImageSummary::new)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<BallotImageSummary> listBySql(String whereClause) {
        // The WHERE clause references these aliases:
        //   bi  = ballot_image  (id, image_name, image_path, page_number, was_rotated, barcode_id)
        //   b   = barcode       (id, raw_data, region_id, election_id, party_id, ballot_type_id)
        //   vo  = vote_opportunity (vote_status, dark_pct, ballot_image_id, contest_id, candidate_id_fk)
        //   c   = contest       (id, contest_title, contest_type, max_votes)
        //   cdr = candidate     (id, candidate_name, write_in, contest_id)
        // We validate the clause (no DML) then run it via a native query.
        String upper = whereClause.trim().toUpperCase();
        for (String kw : new String[]{"INSERT","UPDATE","DELETE","DROP","ALTER","CREATE","TRUNCATE"}) {
            if (upper.contains(kw))
                throw new IllegalArgumentException("SQL filter may not contain: " + kw);
        }
        String sql =
            "SELECT DISTINCT bi.id FROM ballot_image bi " +
            "LEFT JOIN barcode b         ON b.id = bi.barcode_id " +
            "LEFT JOIN vote_opportunity vo ON vo.ballot_image_id = bi.id " +
            "LEFT JOIN contest c          ON c.id = vo.contest_id " +
            "LEFT JOIN candidate cdr      ON cdr.id = vo.candidate_id_fk " +
            "WHERE " + whereClause +
            " ORDER BY bi.id";
        try {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery(sql).getResultList();
            List<Long> longIds = ids.stream()
                .map(Number::longValue)
                .collect(Collectors.toList());
            return listByIds(longIds);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Invalid SQL filter: " + e.getMessage(), e);
        }
    }

    private BallotView toBallotView(BallotImage img) {
        // Load all VoteOpportunity rows for this image
        List<VoteOpportunity> vos = voRepo.findByBallotImage_Id(img.getId());

        // Build index maps to avoid N+1 queries
        Map<Long, ContestRecord>   contests   = new HashMap<>();
        Map<Long, CandidateRecord> candidates = new HashMap<>();
        for (VoteOpportunity vo : vos) {
            contests.computeIfAbsent(vo.getContest().getId(),
                id -> vo.getContest());
            candidates.computeIfAbsent(vo.getCandidate().getId(),
                id -> vo.getCandidate());
        }

        List<IndicatorBox> boxes = vos.stream()
            .map(vo -> new IndicatorBox(vo,
                vo.getContest(),
                vo.getCandidate()))
            .collect(Collectors.toList());

        return new BallotView(img, boxes);
    }
}
