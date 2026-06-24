/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.viewer.service;

import gov.election.viewer.entity.*;
import gov.election.viewer.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.*;
import java.util.*;

/**
 * Assembles all data needed to render one ballot image with overlaid
 * bounding boxes for each vote opportunity.
 */
@Service
public class BallotViewService {

    private final BallotImageViewRepository      imageRepo;
    private final VoteOpportunityViewRepository  voteRepo;
    private final ContestViewRepository          contestRepo;
    private final CandidateViewRepository        candidateRepo;

    public BallotViewService(BallotImageViewRepository imageRepo,
                              VoteOpportunityViewRepository voteRepo,
                              ContestViewRepository contestRepo,
                              CandidateViewRepository candidateRepo) {
        this.imageRepo     = imageRepo;
        this.voteRepo      = voteRepo;
        this.contestRepo   = contestRepo;
        this.candidateRepo = candidateRepo;
    }

    // ── DTO sent to the template / JS ─────────────────────────────────────────

    public static class IndicatorBox {
        public final long   id;
        public final int    x, y, w, h;
        public final String contest;
        public final String contestType;
        public final String candidate;
        public final String status;         // VOTED | OVERVOTED | UNMARKED
        public final String color;          // green | yellow | blue
        public final String label;          // display label
        public final boolean rankedChoice;  // true if candidate name ends with (Rank N)
        public final String  statusLabel;   // "VOTED", "MARKED at Rank N", "UNMARKED", etc.

        public IndicatorBox(long id, int x, int y, int w, int h,
                            String contest, String contestType,
                            String candidate, String status) {
            this.id = id;
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.contest      = contest;
            this.contestType  = contestType;
            this.candidate    = candidate;
            this.status       = status;
            this.color        = switch (status) {
                case "VOTED"      -> "#22c55e";   // green
                case "OVERVOTED"  -> "#eab308";   // yellow
                default           -> "#3b82f6";   // blue
            };
            // Detect ranked-choice boxes: candidate name ends with "(Rank N)"
            java.util.regex.Matcher rankMatcher = java.util.regex.Pattern
                .compile("\\(Rank (\\d+)\\)$").matcher(candidate != null ? candidate : "");
            this.rankedChoice = rankMatcher.find();
            if (this.rankedChoice) {
                String rank = rankMatcher.group(1);
                this.label       = candidate.replaceAll("\\s*\\(Rank \\d+\\)$", "")
                                   + " [R" + rank + "]";
                this.statusLabel = "VOTED".equals(status)
                    ? "MARKED at Rank " + rank
                    : status;
            } else {
                this.label       = candidate;
                this.statusLabel = status;
            }
        }
    }

    public static class BallotView {
        public final long          imageId;
        public final String        imagePath;
        public final String        imageName;
        public final int           dpi;
        public final boolean        wasRotated;
        public final int           warpDpi;
        public final int           canonicalWidth;
        public final int           canonicalHeight;
        /** Corner marks as "TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy" in image pixels. */
        public final String        cornerMarks;
        public final List<IndicatorBox> boxes;
        public final String        resolvedPath;

        public BallotView(long imageId, String imagePath, String imageName,
                          int dpi, boolean wasRotated, int warpDpi,
                          int canonicalWidth, int canonicalHeight,
                          String cornerMarks, List<IndicatorBox> boxes, String resolvedPath) {
            this.imageId        = imageId;
            this.imagePath      = imagePath;
            this.imageName      = imageName;
            this.dpi            = dpi;
            this.wasRotated     = wasRotated;
            this.warpDpi        = warpDpi > 0 ? warpDpi : dpi;
            this.canonicalWidth = canonicalWidth;
            this.canonicalHeight= canonicalHeight;
            this.cornerMarks    = cornerMarks;
            this.boxes          = boxes;
            this.resolvedPath   = resolvedPath;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Find a ballot image by its DB id. */
    @Transactional(readOnly = true)
    public Optional<BallotView> findById(long id) {
        return imageRepo.findById(id).map(this::buildView);
    }

    /** Find a ballot image by exact path or name (tries both). */
    @Transactional(readOnly = true)
    public Optional<BallotView> findByPath(String path) {
        Optional<BallotImageView> img = imageRepo.findByImagePath(path);
        if (img.isEmpty()) {
            // Try by name alone
            String name = Paths.get(path).getFileName().toString();
            // Strip .counted if present
            if (name.endsWith(".counted")) name = name.substring(0, name.length() - 8);
            img = imageRepo.findByImageName(name);
        }
        return img.map(this::buildView);
    }

    /** List all ballot images in the database. */
    @Transactional(readOnly = true)
    public List<BallotImageView> listAll() {
        return imageRepo.findAllByOrderByImageNameAsc();
    }

    // ── Build view ────────────────────────────────────────────────────────────

    private BallotView buildView(BallotImageView img) {
        // Build contest and candidate lookup maps
        Map<Long, ContestView>   contests   = new HashMap<>();
        Map<Long, CandidateView> candidates = new HashMap<>();
        contestRepo.findAll().forEach(c -> contests.put(c.getId(), c));
        candidateRepo.findAll().forEach(c -> candidates.put(c.getId(), c));

        List<VoteOpportunityView> votes = voteRepo.findByBallotImageId(img.getId());

        List<IndicatorBox> boxes = new ArrayList<>();
        for (VoteOpportunityView v : votes) {
            ContestView   ct  = contests.get(v.getContestId());
            CandidateView cnd = candidates.get(v.getCandidateIdFk());
            String contestTitle = ct  != null ? ct.getContestTitle() : "(unknown)";
            String contestType  = ct  != null ? ct.getContestType()  : "PLURALITY";
            String candidateName= cnd != null ? cnd.getCandidateName() : "(unknown)";
            // Use absLeft/absTop — canonical pixel coords the counter actually sampled.
            // The viewer JS will apply H⁻¹ to convert to original image pixel coords.
            boxes.add(new IndicatorBox(
                v.getId(),
                v.getAbsLeft(), v.getAbsTop(),
                v.getIndicatorWidth(), v.getIndicatorHeight(),
                contestTitle, contestType, candidateName,
                v.getVoteStatus() != null ? v.getVoteStatus() : "UNMARKED"));
        }

        // Resolve actual file path (account for .counted rename)
        String resolved = resolveImageFile(img.getImagePath());

        return new BallotView(img.getId(), img.getImagePath(),
            img.getImageName(), img.getDpi(), img.isWasRotated(),
            img.getWarpDpi(), img.getCanonicalWidth(), img.getCanonicalHeight(),
            img.getCornerMarks(), boxes, resolved);
    }

    /**
     * Find the image file on disk.  The counter renames files to name.ext.counted
     * after scanning.  Try the original path, then with .counted appended.
     */
    private String resolveImageFile(String originalPath) {
        if (originalPath == null) return null;
        Path p = Paths.get(originalPath);
        if (Files.exists(p)) return originalPath;
        Path counted = Paths.get(originalPath + ".counted");
        if (Files.exists(counted)) return counted.toString();
        return originalPath;  // return original even if not found — let caller handle
    }
}
