/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.viewer.controller;

import gov.election.viewer.entity.BallotImageView;
import gov.election.viewer.service.BallotViewService;
import gov.election.viewer.service.BallotViewService.BallotView;
import gov.election.viewer.service.BallotViewService.IndicatorBox;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.util.*;

/**
 * Serves the ballot viewer UI.
 *
 * GET /           → image list / search form
 * GET /view?id=N  → view ballot image N with overlays
 * GET /view?path= → view ballot image by file path
 * GET /image?id=N → serve the raw image bytes (handles .counted rename)
 * GET /boxes?id=N → JSON: list of IndicatorBox for JS overlay
 */
@Controller
public class ViewerController {

    private final BallotViewService viewService;

    public ViewerController(BallotViewService viewService) {
        this.viewService = viewService;
    }

    // ── Home — image list ─────────────────────────────────────────────────────

    @GetMapping("/")
    public String index(Model model) {
        List<BallotImageView> images = viewService.listAll();
        model.addAttribute("images", images);
        model.addAttribute("count",  images.size());
        return "index";
    }

    // ── View a ballot with overlays ───────────────────────────────────────────

    @GetMapping("/view")
    public String view(
            @RequestParam(required = false) Long   id,
            @RequestParam(required = false) String path,
            Model model) {

        Optional<BallotView> bv = Optional.empty();
        if (id   != null) bv = viewService.findById(id);
        else if (path != null) bv = viewService.findByPath(path);

        if (bv.isEmpty()) {
            model.addAttribute("error",
                "Ballot image not found in database."
                + (id   != null ? " (id="   + id   + ")" : "")
                + (path != null ? " (path=" + path + ")" : ""));
            return "view";
        }

        BallotView ballot = bv.get();
        model.addAttribute("ballot", ballot);

        // Group boxes by contest for the sidebar legend
        Map<String, List<IndicatorBox>> byContest = new LinkedHashMap<>();
        for (IndicatorBox box : ballot.boxes)
            byContest.computeIfAbsent(box.contest, k -> new ArrayList<>()).add(box);
        model.addAttribute("byContest", byContest);

        // Prev / next navigation — look up position in sorted image list
        List<BallotImageView> all = viewService.listAll();
        Long prevId = null, nextId = null;
        int pos = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(ballot.imageId)) { pos = i; break; }
        }
        if (pos > 0)               prevId = all.get(pos - 1).getId();
        if (pos < all.size() - 1)  nextId = all.get(pos + 1).getId();

        model.addAttribute("prevId",    prevId);
        model.addAttribute("nextId",    nextId);
        model.addAttribute("position",  pos + 1);
        model.addAttribute("total",     all.size());

        return "view";
    }

    // ── Serve the image file (handles .counted rename) ────────────────────────

    @GetMapping("/image")
    public ResponseEntity<byte[]> image(@RequestParam Long id) throws Exception {
        Optional<BallotView> bv = viewService.findById(id);
        if (bv.isEmpty()) return ResponseEntity.notFound().build();

        String resolvedPath = bv.get().resolvedPath;
        if (resolvedPath == null) return ResponseEntity.notFound().build();

        Path file = Paths.get(resolvedPath);
        if (!Files.exists(file)) return ResponseEntity.notFound().build();

        byte[] bytes = Files.readAllBytes(file);
        String name  = file.getFileName().toString().toLowerCase();
        MediaType mt = name.contains(".png")  ? MediaType.IMAGE_PNG
                     : name.contains(".jpg") || name.contains(".jpeg")
                                              ? MediaType.IMAGE_JPEG
                     : MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok()
            .contentType(mt)
            .cacheControl(CacheControl.noCache())
            .body(bytes);
    }

    // ── JSON overlay data ─────────────────────────────────────────────────────

    @GetMapping("/boxes")
    @ResponseBody
    public List<IndicatorBox> boxes(@RequestParam Long id) {
        return viewService.findById(id)
            .map(bv -> bv.boxes)
            .orElse(List.of());
    }
}
