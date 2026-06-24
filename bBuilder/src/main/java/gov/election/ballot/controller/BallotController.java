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
package gov.election.ballot.controller;

import gov.election.ballot.model.*;
import gov.election.ballot.repository.*;
import gov.election.ballot.service.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/print")
public class BallotController {

    private final BallotCombinationRepository    combinationRepo;
    private final BallotDesignTemplateRepository templateRepo;
    private final BallotLanguageRepository        langRepo;
    private final UserRepository                 userRepo;
    private final ElectionRepository             electionRepo;
    private final BallotGenerationService        ballotService;
    private final ExportService                  exportService;

    public BallotController(BallotCombinationRepository combinationRepo,
                             BallotDesignTemplateRepository templateRepo,
                             BallotLanguageRepository langRepo,
                             UserRepository userRepo,
                             ElectionRepository electionRepo,
                             BallotGenerationService ballotService,
                             ExportService exportService) {
        this.combinationRepo = combinationRepo;
        this.templateRepo    = templateRepo;
        this.langRepo        = langRepo;
        this.userRepo        = userRepo;
        this.electionRepo    = electionRepo;
        this.ballotService   = ballotService;
        this.exportService   = exportService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PRINTER')")
    public String printForm(Model model) {
        model.addAttribute("combinations", combinationRepo.findAll());
        // Load available languages for the language selector
        // Use the first jurisdiction's languages as a proxy
        var allJurisdictions = combinationRepo.findAll().stream()
            .map(c -> c.getElection().getJurisdiction())
            .filter(j -> j != null)
            .findFirst();
        allJurisdictions.ifPresent(j ->
            model.addAttribute("languages",
                langRepo.findByJurisdictionIdOrderByDisplayOrderAsc(j.getId())));
        return "print/form";
    }

    @PostMapping(value = "/generate", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','PRINTER')")
    public ResponseEntity<byte[]> generate(
            @RequestParam(required = false) Long combinationId,
            @RequestParam(required = false, defaultValue = "en") String lang,
            @RequestParam(defaultValue = "1") int copies,
            @AuthenticationPrincipal UserDetails userDetails) throws Exception {

        if (combinationId == null) {
            return ResponseEntity.badRequest().build();
        }

        BallotCombination combo = combinationRepo.findById(combinationId)
            .orElseThrow(() -> new IllegalArgumentException("Invalid combination ID: " + combinationId));

        BallotDesignTemplate template = templateRepo
            .findFirstByElectionIdOrderByIdAsc(combo.getElection().getId())
            .orElseThrow(() -> new IllegalStateException(
                "No design template found for election \"" + combo.getElection().getName() +
                "\". Create a ballot design template for this election first."));

        User user = userRepo.findByUsername(userDetails.getUsername())
            .orElseThrow(() -> new IllegalStateException("Authenticated user not found in database."));

        byte[] pdf = ballotService.generateBallot(combo, template, user, copies, lang);

        List<String> written = ballotService.getLastWrittenFiles();
        String filesHeader = String.join("|", written);

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "inline; filename=\"ballot-" + combinationId + ".pdf\"")
            .header("X-Ballot-Files", filesHeader)
            .header("Access-Control-Expose-Headers", "X-Ballot-Files")
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf);
    }

    @GetMapping("/export/xml/{combinationId}")
    @PreAuthorize("hasAnyRole('ADMIN','DATA_ENTRY')")
    public ResponseEntity<String> exportXml(
            @PathVariable Long combinationId,
            @RequestParam(defaultValue = "INCHES") ExportService.MeasurementUnit unit) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_XML)
            .body(exportService.exportOffsetReportXml(combinationId, unit));
    }

    @GetMapping("/export/yaml/{combinationId}")
    @PreAuthorize("hasAnyRole('ADMIN','DATA_ENTRY')")
    public ResponseEntity<String> exportYaml(
            @PathVariable Long combinationId,
            @RequestParam(defaultValue = "INCHES") ExportService.MeasurementUnit unit) {
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_PLAIN)
            .body(exportService.exportOffsetReportYaml(combinationId, unit));
    }

    @GetMapping("/ocr-names/{electionId}")
    @PreAuthorize("hasAnyRole('ADMIN','DATA_ENTRY')")
    public ResponseEntity<String> ocrNames(
            @PathVariable Long electionId,
            @RequestParam(required = false) Long regionId) {
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_PLAIN)
            .body(exportService.buildOcrNameList(electionId, regionId));
    }
}
