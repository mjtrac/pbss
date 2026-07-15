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
package com.mjtrac.ballot.controller;

import com.mjtrac.ballot.model.BallotDesignTemplate;
import com.mjtrac.ballot.model.Election;
import com.mjtrac.ballot.repository.BallotDesignTemplateRepository;
import com.mjtrac.ballot.repository.ElectionRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/data/ballot-templates")
@PreAuthorize("hasAnyRole('ADMIN','DATA_ENTRY')")
public class BallotDesignTemplateController {
    private static final java.util.List<com.mjtrac.ballot.model.BallotDesignTemplate.VoteIndicatorStyle>
        SUPPORTED_INDICATOR_STYLES = java.util.Arrays.stream(
            com.mjtrac.ballot.model.BallotDesignTemplate.VoteIndicatorStyle.values())
        .filter(s -> s != com.mjtrac.ballot.model.BallotDesignTemplate.VoteIndicatorStyle.ARROW
                  && s != com.mjtrac.ballot.model.BallotDesignTemplate.VoteIndicatorStyle.NUMBER_FIELD)
        .toList();


    private final BallotDesignTemplateRepository templateRepo;
    private final ElectionRepository             electionRepo;

    public BallotDesignTemplateController(BallotDesignTemplateRepository t,
                                          ElectionRepository e) {
        this.templateRepo = t;
        this.electionRepo = e;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("templates", templateRepo.findAll());
        return "data/ballot-templates/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("template",        new BallotDesignTemplate());
        model.addAttribute("elections",       electionRepo.findAll());
        model.addAttribute("paperSizes",      BallotDesignTemplate.PaperSize.values());
        model.addAttribute("indicatorStyles", SUPPORTED_INDICATOR_STYLES);
        model.addAttribute("fontFamilies",      BallotDesignTemplate.FontFamily.values());
        model.addAttribute("formTitle",       "New Ballot Design Template");
        return "data/ballot-templates/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        BallotDesignTemplate t = templateRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Template not found: " + id));
        model.addAttribute("template",        t);
        model.addAttribute("elections",       electionRepo.findAll());
        model.addAttribute("paperSizes",      BallotDesignTemplate.PaperSize.values());
        model.addAttribute("indicatorStyles", SUPPORTED_INDICATOR_STYLES);
        model.addAttribute("fontFamilies",      BallotDesignTemplate.FontFamily.values());
        model.addAttribute("formTitle",       "Edit Template: " + t.getElection().getName());
        return "data/ballot-templates/form";
    }

    @PostMapping("/save")
    public String save(
            @RequestParam(required = false) Long   id,
            @RequestParam(required = false) Long   electionId,
            @RequestParam(required = false) String paperSize,
            @RequestParam(required = false) String voteIndicatorStyle,
            @RequestParam(defaultValue = "3")    int    columns,
            @RequestParam(defaultValue = "36")   float  marginTopPt,
            @RequestParam(defaultValue = "36")   float  marginBottomPt,
            @RequestParam(defaultValue = "36")   float  marginLeftPt,
            @RequestParam(defaultValue = "36")   float  marginRightPt,
            // font sizes
            @RequestParam(defaultValue = "11")   float  groupingLabelFontSize,
            @RequestParam(defaultValue = "11")   float  contestTitleFontSize,
            @RequestParam(defaultValue = "9")    float  instructionFontSize,
            @RequestParam(defaultValue = "9")    float  preambleFontSize,
            @RequestParam(defaultValue = "10")   float  candidateNameFontSize,
            @RequestParam(defaultValue = "9")    float  prefixSuffixFontSize,
            @RequestParam(defaultValue = "8")    float  candidateNoteFontSize,
            @RequestParam(defaultValue = "9")    float  postambleFontSize,
            @RequestParam(defaultValue = "9")    float  headerFontSize,
            // bold flags
            @RequestParam(defaultValue = "false") boolean groupingLabelBold,
            @RequestParam(defaultValue = "false") boolean contestTitleBold,
            @RequestParam(defaultValue = "false") boolean instructionBold,
            @RequestParam(defaultValue = "false") boolean preambleBold,
            @RequestParam(defaultValue = "false") boolean candidateNameBold,
            @RequestParam(defaultValue = "false") boolean prefixSuffixBold,
            @RequestParam(defaultValue = "false") boolean candidateNoteBold,
            @RequestParam(defaultValue = "false") boolean postambleBold,
            // italic flags
            @RequestParam(defaultValue = "false") boolean groupingLabelItalic,
            @RequestParam(defaultValue = "false") boolean contestTitleItalic,
            @RequestParam(defaultValue = "false") boolean instructionItalic,
            @RequestParam(defaultValue = "false") boolean preambleItalic,
            @RequestParam(defaultValue = "false") boolean candidateNameItalic,
            @RequestParam(defaultValue = "false") boolean prefixSuffixItalic,
            @RequestParam(defaultValue = "false") boolean candidateNoteItalic,
            @RequestParam(defaultValue = "false") boolean postambleItalic,
            // header zone text
            @RequestParam(required = false)           String  headerHtml,
            // misc
            @RequestParam(defaultValue = "TOP_RIGHT") String  barcodePosition,
            @RequestParam(defaultValue = "0")         float   barcodeWidthPt,
            @RequestParam(defaultValue = "72")        float   barcodeHeightPt,
            @RequestParam(defaultValue = "false")     boolean multiSheet,
            @RequestParam(defaultValue = "HELVETICA") String  fontFamilyPrimary,
            @RequestParam(defaultValue = "TIMES")     String  fontFamilyAlternate,
            @RequestParam(defaultValue = "false")     boolean groupingLabelAltFont,
            @RequestParam(defaultValue = "false")     boolean contestTitleAltFont,
            @RequestParam(defaultValue = "false")     boolean instructionAltFont,
            @RequestParam(defaultValue = "false")     boolean preambleAltFont,
            @RequestParam(defaultValue = "false")     boolean candidateNameAltFont,
            @RequestParam(defaultValue = "false")     boolean prefixSuffixAltFont,
            @RequestParam(defaultValue = "false")     boolean candidateNoteAltFont,
            @RequestParam(defaultValue = "false")     boolean postambleAltFont,
            @RequestParam(defaultValue = "false")     boolean headerAltFont,
            @RequestParam(defaultValue = "false")     boolean rcvIndicatorsRight,
            @RequestParam(defaultValue = "false")     boolean rcvShowRankNumbers,
            @RequestParam(defaultValue = "7")         float   rcvRankNumberFontPt,
            @RequestParam(defaultValue = "0.5")       float   rcvBoxLineWidthPt,
            @RequestParam(defaultValue = "0.5")       float   indicatorLineWidthPt,
            @RequestParam(defaultValue = "false")     boolean indicatorDashed,
            Model model,
            RedirectAttributes ra) {

        if (electionId == null) {
            return returnToForm(id, "Please select an election.", model);
        }
        if (paperSize == null || paperSize.isBlank()) {
            return returnToForm(id, "Please select a paper size.", model);
        }
        if (voteIndicatorStyle == null || voteIndicatorStyle.isBlank()) {
            return returnToForm(id, "Please select a vote indicator style.", model);
        }

        Election election = electionRepo.findById(electionId).orElse(null);
        if (election == null) {
            return returnToForm(id, "Election not found — please choose again.", model);
        }

        BallotDesignTemplate t = (id != null)
            ? templateRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + id))
            : new BallotDesignTemplate();

        t.setElection(election);
        t.setPaperSize(BallotDesignTemplate.PaperSize.valueOf(paperSize));
        BallotDesignTemplate.VoteIndicatorStyle style =
            BallotDesignTemplate.VoteIndicatorStyle.valueOf(voteIndicatorStyle);
        if (style == BallotDesignTemplate.VoteIndicatorStyle.ARROW
                || style == BallotDesignTemplate.VoteIndicatorStyle.NUMBER_FIELD) {
            ra.addFlashAttribute("error", style.name() + " indicator style is not yet available.");
            return "redirect:/data/ballot-templates";
        }
        t.setVoteIndicatorStyle(style);
        t.setColumns(columns);
        t.setMarginTopPt(marginTopPt);
        t.setMarginBottomPt(marginBottomPt);
        t.setMarginLeftPt(marginLeftPt);
        t.setMarginRightPt(marginRightPt);
        // font sizes
        t.setGroupingLabelFontSize(groupingLabelFontSize);
        t.setContestTitleFontSize(contestTitleFontSize);
        t.setInstructionFontSize(instructionFontSize);
        t.setPreambleFontSize(preambleFontSize);
        t.setCandidateNameFontSize(candidateNameFontSize);
        t.setPrefixSuffixFontSize(prefixSuffixFontSize);
        t.setCandidateNoteFontSize(candidateNoteFontSize);
        t.setPostambleFontSize(postambleFontSize);
        t.setHeaderFontSize(headerFontSize);
        // bold
        t.setGroupingLabelBold(groupingLabelBold);
        t.setContestTitleBold(contestTitleBold);
        t.setInstructionBold(instructionBold);
        t.setPreambleBold(preambleBold);
        t.setCandidateNameBold(candidateNameBold);
        t.setPrefixSuffixBold(prefixSuffixBold);
        t.setCandidateNoteBold(candidateNoteBold);
        t.setPostambleBold(postambleBold);
        // italic
        t.setGroupingLabelItalic(groupingLabelItalic);
        t.setContestTitleItalic(contestTitleItalic);
        t.setInstructionItalic(instructionItalic);
        t.setPreambleItalic(preambleItalic);
        t.setCandidateNameItalic(candidateNameItalic);
        t.setPrefixSuffixItalic(prefixSuffixItalic);
        t.setCandidateNoteItalic(candidateNoteItalic);
        t.setPostambleItalic(postambleItalic);
        // misc
        // header zone text (null/blank = use built-in default)
        t.setHeaderHtml(headerHtml != null && !headerHtml.isBlank()
            ? headerHtml
            : BallotDesignTemplate.DEFAULT_HEADER_HTML);
        t.setBarcodePosition(barcodePosition);
        t.setBarcodeWidthPt(barcodeWidthPt >= 0 ? barcodeWidthPt : 0f);
        t.setBarcodeHeightPt(barcodeHeightPt > 0 ? barcodeHeightPt : 72f);
        t.setMultiSheet(multiSheet);
        try { t.setFontFamilyPrimary(BallotDesignTemplate.FontFamily.valueOf(fontFamilyPrimary)); }
        catch (IllegalArgumentException ex) { t.setFontFamilyPrimary(BallotDesignTemplate.FontFamily.HELVETICA); }
        try { t.setFontFamilyAlternate(BallotDesignTemplate.FontFamily.valueOf(fontFamilyAlternate)); }
        catch (IllegalArgumentException ex) { t.setFontFamilyAlternate(BallotDesignTemplate.FontFamily.TIMES); }
        t.setGroupingLabelAltFont(groupingLabelAltFont);
        t.setContestTitleAltFont(contestTitleAltFont);
        t.setInstructionAltFont(instructionAltFont);
        t.setPreambleAltFont(preambleAltFont);
        t.setCandidateNameAltFont(candidateNameAltFont);
        t.setPrefixSuffixAltFont(prefixSuffixAltFont);
        t.setCandidateNoteAltFont(candidateNoteAltFont);
        t.setPostambleAltFont(postambleAltFont);
        t.setHeaderAltFont(headerAltFont);
        t.setRcvIndicatorsRight(rcvIndicatorsRight);
        t.setRcvShowRankNumbers(rcvShowRankNumbers);
        t.setRcvRankNumberFontPt(rcvRankNumberFontPt > 0 ? rcvRankNumberFontPt : 7f);
        t.setRcvBoxLineWidthPt(rcvBoxLineWidthPt > 0 ? rcvBoxLineWidthPt : 0.5f);
        t.setIndicatorLineWidthPt(indicatorLineWidthPt > 0 ? indicatorLineWidthPt : 0.5f);
        t.setIndicatorDashed(indicatorDashed);

        BallotDesignTemplate saved = templateRepo.save(t);
        ra.addFlashAttribute("success",
            (id != null ? "Updated" : "Created") +
            " ballot design template for \"" + saved.getElection().getName() + "\".");
        return "redirect:/data/ballot-templates/" + saved.getId() + "/edit";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        BallotDesignTemplate t = templateRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Template not found: " + id));
        templateRepo.delete(t);
        ra.addFlashAttribute("success", "Deleted ballot design template.");
        return "redirect:/data/ballot-templates";
    }

    private String returnToForm(Long id, String error, Model model) {
        BallotDesignTemplate t = (id != null)
            ? templateRepo.findById(id).orElse(new BallotDesignTemplate())
            : new BallotDesignTemplate();
        model.addAttribute("template",        t);
        model.addAttribute("elections",       electionRepo.findAll());
        model.addAttribute("paperSizes",      BallotDesignTemplate.PaperSize.values());
        model.addAttribute("indicatorStyles", SUPPORTED_INDICATOR_STYLES);
        model.addAttribute("fontFamilies",      BallotDesignTemplate.FontFamily.values());
        model.addAttribute("formTitle",       id != null ? "Edit Template" : "New Ballot Design Template");
        model.addAttribute("error",           error);
        return "data/ballot-templates/form";
    }
}
