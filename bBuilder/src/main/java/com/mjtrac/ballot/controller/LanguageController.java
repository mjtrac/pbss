/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.ballot.controller;

import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.repository.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Manages ballot languages and translations for contests and candidates.
 *
 * Routes:
 *   GET  /data/languages                       — list languages for active jurisdiction
 *   POST /data/languages                       — add a language
 *   POST /data/languages/{id}/delete           — remove a language
 *   GET  /data/contests/{id}/translate         — translation form for a contest
 *   POST /data/contests/{id}/translate         — save contest translations
 *   GET  /data/candidates/{id}/translate       — translation form for a candidate
 *   POST /data/candidates/{id}/translate       — save candidate translations
 */
@Controller
public class LanguageController {

    private final BallotLanguageRepository      langRepo;
    private final JurisdictionRepository        jurisRepo;
    private final ContestRepository             contestRepo;
    private final CandidateRepository           candRepo;
    private final ContestTranslationRepository  contestTrRepo;
    private final CandidateTranslationRepository candTrRepo;

    public LanguageController(BallotLanguageRepository langRepo,
                               JurisdictionRepository jurisRepo,
                               ContestRepository contestRepo,
                               CandidateRepository candRepo,
                               ContestTranslationRepository contestTrRepo,
                               CandidateTranslationRepository candTrRepo) {
        this.langRepo      = langRepo;
        this.jurisRepo     = jurisRepo;
        this.contestRepo   = contestRepo;
        this.candRepo      = candRepo;
        this.contestTrRepo = contestTrRepo;
        this.candTrRepo    = candTrRepo;
    }

    // ── Language list ─────────────────────────────────────────────────────────

    @GetMapping("/data/languages")
    public String list(Model model) {
        List<Jurisdiction> all = jurisRepo.findAll();
        model.addAttribute("jurisdictions", all);
        if (!all.isEmpty()) {
            Jurisdiction j = all.get(0);
            model.addAttribute("selectedJurisdiction", j);
            model.addAttribute("languages",
                langRepo.findByJurisdictionIdOrderByDisplayOrderAsc(j.getId()));
        }
        model.addAttribute("newLang", new BallotLanguage());
        return "data/languages";
    }

    @PostMapping("/data/languages")
    public String add(@RequestParam Long jurisdictionId,
                      @RequestParam String languageCode,
                      @RequestParam String languageName,
                      @RequestParam(defaultValue = "0") int displayOrder,
                      RedirectAttributes ra) {
        Jurisdiction j = jurisRepo.findById(jurisdictionId)
            .orElseThrow(() -> new IllegalArgumentException("Jurisdiction not found"));

        if (langRepo.findByJurisdictionIdAndLanguageCode(
                jurisdictionId, languageCode).isPresent()) {
            ra.addFlashAttribute("error",
                "Language code '" + languageCode + "' already exists for this jurisdiction.");
            return "redirect:/data/languages";
        }

        BallotLanguage lang = new BallotLanguage();
        lang.setJurisdiction(j);
        lang.setLanguageCode(languageCode.strip().toLowerCase());
        lang.setLanguageName(languageName.strip());
        lang.setDisplayOrder(displayOrder);
        langRepo.save(lang);
        ra.addFlashAttribute("success",
            "Language '" + languageName + "' added.");
        return "redirect:/data/languages";
    }

    @PostMapping("/data/languages/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        langRepo.deleteById(id);
        ra.addFlashAttribute("success", "Language removed.");
        return "redirect:/data/languages";
    }

    // ── Contest translations ──────────────────────────────────────────────────

    @GetMapping("/data/contests/{id}/translate")
    public String contestTranslateForm(@PathVariable Long id, Model model) {
        Contest c = contestRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Contest not found: " + id));
        List<BallotLanguage> langs = langRepo
            .findByJurisdictionIdOrderByDisplayOrderAsc(
                c.getElection().getJurisdiction() != null
                    ? c.getElection().getJurisdiction().getId() : -1L);
        List<ContestTranslation> existing = contestTrRepo.findByContestId(id);

        model.addAttribute("contest",      c);
        model.addAttribute("languages",    langs);
        model.addAttribute("translations", existing);
        return "data/contest-translate";
    }

    @PostMapping("/data/contests/{id}/translate")
    public String contestTranslateSave(@PathVariable Long id,
                                        @RequestParam java.util.Map<String,String> params,
                                        RedirectAttributes ra) {
        Contest c = contestRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Contest not found: " + id));

        // params come in as title_es, instructions_es, preamble_es, etc.
        List<BallotLanguage> langs = langRepo
            .findByJurisdictionIdOrderByDisplayOrderAsc(
                c.getElection().getJurisdiction() != null
                    ? c.getElection().getJurisdiction().getId() : -1L);

        for (BallotLanguage lang : langs) {
            String code = lang.getLanguageCode();
            ContestTranslation tr = contestTrRepo
                .findByContestIdAndLanguageCode(id, code)
                .orElseGet(() -> {
                    ContestTranslation t = new ContestTranslation();
                    t.setContest(c);
                    t.setLanguageCode(code);
                    return t;
                });
            tr.setTitle(params.get("title_" + code));
            tr.setInstructions(params.get("instructions_" + code));
            tr.setPreamble(params.get("preamble_" + code));
            tr.setPostamble(params.get("postamble_" + code));
            tr.setGroupingLabel(params.get("groupingLabel_" + code));
            contestTrRepo.save(tr);
        }
        ra.addFlashAttribute("success", "Translations saved.");
        return "redirect:/data/contests/" + id + "/translate";
    }

    // ── Candidate translations ────────────────────────────────────────────────

    @GetMapping("/data/candidates/{id}/translate")
    public String candidateTranslateForm(@PathVariable Long id, Model model) {
        Candidate cand = candRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + id));
        Contest c = cand.getContest();
        List<BallotLanguage> langs = langRepo
            .findByJurisdictionIdOrderByDisplayOrderAsc(
                c.getElection().getJurisdiction() != null
                    ? c.getElection().getJurisdiction().getId() : -1L);
        List<CandidateTranslation> existing = candTrRepo.findByCandidateId(id);

        model.addAttribute("candidate",    cand);
        model.addAttribute("contest",      c);
        model.addAttribute("languages",    langs);
        model.addAttribute("translations", existing);
        return "data/candidate-translate";
    }

    @PostMapping("/data/candidates/{id}/translate")
    public String candidateTranslateSave(@PathVariable Long id,
                                          @RequestParam java.util.Map<String,String> params,
                                          RedirectAttributes ra) {
        Candidate cand = candRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + id));
        Contest c = cand.getContest();
        List<BallotLanguage> langs = langRepo
            .findByJurisdictionIdOrderByDisplayOrderAsc(
                c.getElection().getJurisdiction() != null
                    ? c.getElection().getJurisdiction().getId() : -1L);

        for (BallotLanguage lang : langs) {
            String code = lang.getLanguageCode();
            CandidateTranslation tr = candTrRepo
                .findByCandidateIdAndLanguageCode(id, code)
                .orElseGet(() -> {
                    CandidateTranslation t = new CandidateTranslation();
                    t.setCandidate(cand);
                    t.setLanguageCode(code);
                    return t;
                });
            tr.setName(params.get("name_" + code));
            tr.setExplanatoryText(params.get("explanatoryText_" + code));
            candTrRepo.save(tr);
        }
        ra.addFlashAttribute("success", "Translations saved.");
        return "redirect:/data/candidates/" + id + "/translate";
    }
}
