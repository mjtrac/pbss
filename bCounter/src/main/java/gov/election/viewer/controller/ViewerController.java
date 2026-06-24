/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.viewer.controller;

import gov.election.viewer.service.BallotViewService.BallotImageSummary;
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

    @GetMapping({"/viewer/", "/viewer"})
    public String index(Model model) {
        List<BallotImageSummary> images = viewService.listAll();
        model.addAttribute("images", images);
        model.addAttribute("count",  images.size());
        return "viewer/index";
    }

    // ── View a ballot with overlays ───────────────────────────────────────────

    @GetMapping("/viewer/view")
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
            return "viewer/view";
        }

        BallotView ballot = bv.get();
        model.addAttribute("ballot", ballot);

        // Group boxes by contest for the sidebar legend
        Map<String, List<IndicatorBox>> byContest = new LinkedHashMap<>();
        for (IndicatorBox box : ballot.boxes)
            byContest.computeIfAbsent(box.contest, k -> new ArrayList<>()).add(box);
        model.addAttribute("byContest", byContest);

        // Prev / next navigation — look up position in sorted image list
        List<BallotImageSummary> all = viewService.listAll();
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

        return "viewer/view";
    }

    // ── Serve the image file (handles .counted rename) ────────────────────────

    @GetMapping("/viewer/image")
    public ResponseEntity<byte[]> image(@RequestParam Long id) throws Exception {
        Optional<BallotView> bv = viewService.findById(id);
        if (bv.isEmpty()) return ResponseEntity.notFound().build();

        // Resolve at request time so we handle both .png and .counted regardless
        // of what the file was named when the BallotView was constructed.
        String storedPath = bv.get().imagePath;
        if (storedPath == null) return ResponseEntity.notFound().build();

        Path file = Paths.get(storedPath);
        if (!Files.exists(file)) {
            // bCounter renames files by appending .counted to the full filename
            // e.g. ballot.png → ballot.png.counted
            Path counted1 = Paths.get(storedPath + ".counted");
            // Also try stripping extension and replacing with .counted
            // e.g. ballot.png → ballot.counted
            String counted2str = storedPath.replaceAll(
                "\\.(png|jpg|jpeg|tif|tiff|bmp)$", ".counted");
            Path counted2 = Paths.get(counted2str);
            if (Files.exists(counted1))      file = counted1;
            else if (Files.exists(counted2)) file = counted2;
            else return ResponseEntity.notFound().build();
        }

        byte[] bytes = Files.readAllBytes(file);
        String name  = file.getFileName().toString().toLowerCase();
        // .counted files are renamed .png files — serve as PNG
        MediaType mt = name.contains(".png") || name.contains(".counted")
                                              ? MediaType.IMAGE_PNG
                     : name.contains(".jpg") || name.contains(".jpeg")
                                              ? MediaType.IMAGE_JPEG
                     : MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok()
            .contentType(mt)
            .cacheControl(CacheControl.noCache())
            .body(bytes);
    }

    // ── JSON overlay data ─────────────────────────────────────────────────────

    @GetMapping("/viewer/boxes")
    @ResponseBody
    public List<IndicatorBox> boxes(@RequestParam Long id) {
        return viewService.findById(id)
            .map(bv -> bv.boxes)
            .orElse(List.of());
    }
}
