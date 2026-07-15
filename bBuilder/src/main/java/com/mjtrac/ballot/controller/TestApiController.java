/*
 * TestApiController — JSON REST API for test harness automation.
 * Provides endpoints to create/reset all ballot data and generate ballots
 * without going through the HTML UI.
 *
 * All endpoints require ADMIN role and are under /api/test/.
 * CSRF is disabled for these endpoints via TestApiSecurityConfig.
 */
package com.mjtrac.ballot.controller;

import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.model.BallotDesignTemplate.PaperSize;
import com.mjtrac.ballot.model.Region.RegionType;
import com.mjtrac.ballot.repository.*;
import com.mjtrac.ballot.service.BallotGenerationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/test")
public class TestApiController {

    private final JurisdictionRepository    jurisdictionRepo;
    private final ElectionRepository        electionRepo;
    private final BallotTypeRepository      ballotTypeRepo;
    private final PartyRepository           partyRepo;
    private final RegionRepository          regionRepo;
    private final ContestRepository         contestRepo;
    private final CandidateRepository       candidateRepo;
    private final BallotCombinationRepository combRepo;
    private final BallotDesignTemplateRepository templateRepo;
    private final BallotGenerationService   ballotService;
    private final UserRepository             userRepo;
    private final BallotLanguageRepository   languageRepo;
    private final ContestTranslationRepository  contestTranslationRepo;
    private final CandidateTranslationRepository candidateTranslationRepo;
    private final PrintLogRepository         printLogRepo;

    public TestApiController(
            JurisdictionRepository jurisdictionRepo,
            ElectionRepository electionRepo,
            BallotTypeRepository ballotTypeRepo,
            PartyRepository partyRepo,
            RegionRepository regionRepo,
            ContestRepository contestRepo,
            CandidateRepository candidateRepo,
            BallotCombinationRepository combRepo,
            BallotDesignTemplateRepository templateRepo,
            BallotGenerationService ballotService,
            UserRepository userRepo,
            BallotLanguageRepository languageRepo,
            ContestTranslationRepository contestTranslationRepo,
            CandidateTranslationRepository candidateTranslationRepo,
            PrintLogRepository printLogRepo) {
        this.jurisdictionRepo = jurisdictionRepo;
        this.electionRepo     = electionRepo;
        this.ballotTypeRepo   = ballotTypeRepo;
        this.partyRepo        = partyRepo;
        this.regionRepo       = regionRepo;
        this.contestRepo      = contestRepo;
        this.candidateRepo    = candidateRepo;
        this.combRepo         = combRepo;
        this.templateRepo     = templateRepo;
        this.ballotService    = ballotService;
        this.userRepo                  = userRepo;
        this.languageRepo              = languageRepo;
        this.contestTranslationRepo    = contestTranslationRepo;
        this.candidateTranslationRepo  = candidateTranslationRepo;
        this.printLogRepo              = printLogRepo;
    }

    // ── Health check ──────────────────────────────────────────────────────────

    @GetMapping("/ping")
    public ResponseEntity<Map<String,String>> ping() {
        return ResponseEntity.ok(Map.of("status", "ok", "app", "bBuilder"));
    }

    // ── Reset — wipe all test data ────────────────────────────────────────────

    @DeleteMapping("/reset")
    @Transactional
    public ResponseEntity<Map<String,String>> reset() {
        combRepo.deleteAll();
        templateRepo.deleteAll();
        candidateRepo.deleteAll();
        contestRepo.deleteAll();
        regionRepo.deleteAll();
        ballotTypeRepo.deleteAll();
        partyRepo.deleteAll();
        electionRepo.deleteAll();
        jurisdictionRepo.deleteAll();
        return ResponseEntity.ok(Map.of("status", "reset complete"));
    }

    // ── Create jurisdiction ───────────────────────────────────────────────────

    @PostMapping("/jurisdiction")
    @Transactional
    public ResponseEntity<Map<String,Object>> createJurisdiction(
            @RequestBody Map<String,String> body) {
        Jurisdiction j = new Jurisdiction();
        j.setName(body.get("name"));
        j = jurisdictionRepo.save(j);
        return ok("id", j.getId(), "name", j.getName());
    }

    // ── Create election ───────────────────────────────────────────────────────

    @PostMapping("/election")
    @Transactional
    public ResponseEntity<Map<String,Object>> createElection(
            @RequestBody Map<String,Object> body) {
        Jurisdiction jur = jurisdictionRepo.findById(
                Long.valueOf(body.get("jurisdictionId").toString()))
            .orElseThrow(() -> new IllegalArgumentException("jurisdiction not found"));
        Election e = new Election();
        e.setName(body.get("name").toString());
        e.setJurisdiction(jur);
        String dateStr = body.getOrDefault("electionDate", "2026-11-03").toString();
        e.setElectionDate(LocalDate.parse(dateStr));
        e = electionRepo.save(e);
        return ok("id", e.getId(), "name", e.getName());
    }

    // ── Create ballot type ────────────────────────────────────────────────────

    @PostMapping("/ballot-type")
    @Transactional
    public ResponseEntity<Map<String,Object>> createBallotType(
            @RequestBody Map<String,Object> body) {
        // BallotType requires a jurisdiction
        Long jId = body.containsKey("jurisdictionId")
            ? Long.valueOf(body.get("jurisdictionId").toString())
            : jurisdictionRepo.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No jurisdiction exists yet"))
                .getId();
        Jurisdiction jur = jurisdictionRepo.findById(jId)
            .orElseThrow(() -> new IllegalArgumentException("jurisdiction not found"));
        BallotType bt = new BallotType();
        bt.setName(body.get("name").toString());
        bt.setJurisdiction(jur);
        bt = ballotTypeRepo.save(bt);
        return ok("id", bt.getId(), "name", bt.getName());
    }

    // ── Create party ──────────────────────────────────────────────────────────

    @PostMapping("/party")
    @Transactional
    public ResponseEntity<Map<String,Object>> createParty(
            @RequestBody Map<String,Object> body) {
        Party p = new Party();
        p.setName(body.get("name").toString());
        p.setJurisdiction(resolveJurisdiction(body.get("jurisdictionId")));
        if (body.containsKey("abbreviation")) p.setAbbreviation(body.get("abbreviation").toString());
        p = partyRepo.save(p);
        return ok("id", p.getId(), "name", p.getName());
    }

    // ── Create region (single precinct or group) ──────────────────────────────

    @PostMapping("/region")
    @Transactional
    public ResponseEntity<Map<String,Object>> createRegion(
            @RequestBody Map<String,Object> body) {
        Region r = new Region();
        r.setName(body.get("name").toString());
        r.setJurisdiction(resolveJurisdiction(body.get("jurisdictionId")));
        String type = body.getOrDefault("type", "SINGLE_PRECINCT").toString();
        r.setRegionType(RegionType.valueOf(type));
        if (body.containsKey("groupType"))
            r.setGroupType(body.get("groupType").toString());
        r = regionRepo.save(r);

        // Add member precincts if it's a group
        if (body.containsKey("memberIds")) {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) body.get("memberIds");
            Region group = r;
            for (Object mid : ids) {
                Region member = regionRepo.findById(Long.valueOf(mid.toString()))
                    .orElseThrow(() -> new IllegalArgumentException("member region not found: " + mid));
                List<Region> members = group.getMembers();
                if (members == null) { members = new java.util.ArrayList<>(); group.setMembers(members); }
                members.add(member);
                regionRepo.save(group);
            }
        }
        return ok("id", r.getId(), "name", r.getName(), "type", r.getRegionType().name());
    }

    // ── Create contest ────────────────────────────────────────────────────────

    @PostMapping("/contest")
    @Transactional
    public ResponseEntity<Map<String,Object>> createContest(
            @RequestBody Map<String,Object> body) {
        Election election = electionRepo.findById(
                Long.valueOf(body.get("electionId").toString()))
            .orElseThrow(() -> new IllegalArgumentException("election not found"));

        Contest c = new Contest();
        String titleStr = body.get("title").toString();
        c.setTitle(titleStr);        // sets both title and printableTitle
        c.setRecordTitle(titleStr);
        c.setElection(election);

        // Map contestType string to VotingMethod enum
        String ctype = body.getOrDefault("contestType", "PLURALITY").toString();
        // Accept both "RANKED_CHOICE" and "RANKED" for convenience
        if (ctype.equals("RANKED")) ctype = "RANKED_CHOICE";
        c.setVotingMethod(Contest.VotingMethod.valueOf(ctype));

        int maxVotes = Integer.parseInt(body.getOrDefault("maxVotes", "1").toString());
        c.setMaxChoices(maxVotes);
        // For ranked choice, also set maxRankChoices
        if (c.getVotingMethod() == Contest.VotingMethod.RANKED_CHOICE) {
            c.setMaxRankChoices(maxVotes);
        }

        if (body.containsKey("sectionHeader")) {
            c.setGroupingLabel(body.get("sectionHeader").toString());
            c.setPrintGroupingLabel(true);
        }
        if (body.containsKey("instruction")) {
            c.setInstructions(body.get("instruction").toString());
        }
        if (body.containsKey("instructions")) {
            c.setInstructions(body.get("instructions").toString());
        }
        if (body.containsKey("preamble")) {
            c.setPreamble(body.get("preamble").toString());
            c.setPrintPreamble(true);
        }
        if (body.containsKey("postamble")) {
            c.setPostamble(body.get("postamble").toString());
            c.setPrintPostamble(true);
        }
        if (body.containsKey("printedTitle"))
            c.setPrintableTitle(body.get("printedTitle").toString());

        // Save contest first, then assign regions (mirrors how UI controller works)
        c = contestRepo.save(c);

        if (body.containsKey("regionIds")) {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) body.get("regionIds");
            List<Region> regions = regionRepo.findAllById(
                ids.stream().map(id -> Long.valueOf(id.toString())).toList());
            c.setAssignedRegions(regions);
            c = contestRepo.save(c);
        }

        return ok("id", c.getId(), "title", c.getRecordTitle());
    }

    // ── Create candidate ──────────────────────────────────────────────────────

    @PostMapping("/candidate")
    @Transactional
    public ResponseEntity<Map<String,Object>> createCandidate(
            @RequestBody Map<String,Object> body) {
        Contest contest = contestRepo.findById(
                Long.valueOf(body.get("contestId").toString()))
            .orElseThrow(() -> new IllegalArgumentException("contest not found"));

        Candidate cand = new Candidate();
        cand.setName(body.get("name").toString());
        cand.setContest(contest);
        cand.setWriteIn(Boolean.parseBoolean(
            body.getOrDefault("writeIn", "false").toString()));
        if (body.containsKey("explanatoryText"))
            cand.setExplanatoryText(body.get("explanatoryText").toString());
        if (body.containsKey("prefix"))
            cand.setPrefixText(body.get("prefix").toString());
        if (body.containsKey("suffix"))
            cand.setSuffixText(body.get("suffix").toString());

        cand = candidateRepo.save(cand);
        return ok("id", cand.getId(), "name", cand.getName());
    }

    // ── Create ballot design template ─────────────────────────────────────────

    @PostMapping("/template")
    @Transactional
    public ResponseEntity<Map<String,Object>> createTemplate(
            @RequestBody Map<String,Object> body) {
        Election election = electionRepo.findById(
                Long.valueOf(body.get("electionId").toString()))
            .orElseThrow(() -> new IllegalArgumentException("election not found"));

        BallotDesignTemplate t = new BallotDesignTemplate();
        t.setElection(election);
        String ps = body.getOrDefault("paperSize", "LETTER_8_5x11").toString();
        t.setPaperSize(PaperSize.valueOf(ps));
        t.setColumns(Integer.parseInt(body.getOrDefault("columns", "3").toString()));

        String ind = body.getOrDefault("indicatorType", "OVAL").toString();
        t.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.valueOf(ind));
        // headerHtml is the single field for header content in this version
        if (body.containsKey("headerHtml"))
            t.setHeaderHtml(body.get("headerHtml").toString());
        else if (body.containsKey("headerHeadline") || body.containsKey("headerBodyText")) {
            // Legacy: build headerHtml from headline + body text
            String headline = body.getOrDefault("headerHeadline", "OFFICIAL BALLOT").toString();
            String body2    = body.getOrDefault("headerBodyText", "").toString();
            StringBuilder html = new StringBuilder();
            html.append("<div style=\"font-family:Helvetica,Arial,sans-serif;padding:4px 0\">");
            html.append("<p style=\"font-size:13pt;font-weight:bold;line-height:1.6\">")
               .append(headline).append("</p>");
            for (String line : body2.split("\\n")) {
                if (!line.isBlank())
                    html.append("<p style=\"font-size:9pt;line-height:1.4\">")
                        .append(line.trim()).append("</p>");
            }
            html.append("</div>");
            t.setHeaderHtml(html.toString());
        }
        if (body.containsKey("headerHtml"))
            t.setHeaderHtml(body.get("headerHtml").toString());

        // Optional layout overrides
        if (body.containsKey("marginTopPt"))
            t.setMarginTopPt(Float.parseFloat(body.get("marginTopPt").toString()));
        if (body.containsKey("marginBottomPt"))
            t.setMarginBottomPt(Float.parseFloat(body.get("marginBottomPt").toString()));
        if (body.containsKey("marginLeftPt"))
            t.setMarginLeftPt(Float.parseFloat(body.get("marginLeftPt").toString()));
        if (body.containsKey("marginRightPt"))
            t.setMarginRightPt(Float.parseFloat(body.get("marginRightPt").toString()));
        if (body.containsKey("contestTitleFontSize"))
            t.setContestTitleFontSize(Float.parseFloat(body.get("contestTitleFontSize").toString()));
        if (body.containsKey("candidateNameFontSize"))
            t.setCandidateNameFontSize(Float.parseFloat(body.get("candidateNameFontSize").toString()));
        if (body.containsKey("instructionFontSize"))
            t.setInstructionFontSize(Float.parseFloat(body.get("instructionFontSize").toString()));
        if (body.containsKey("headerFontSize"))
            t.setHeaderFontSize(Float.parseFloat(body.get("headerFontSize").toString()));
        if (body.containsKey("contestTitleBold"))
            t.setContestTitleBold(Boolean.parseBoolean(body.get("contestTitleBold").toString()));
        if (body.containsKey("candidateNameBold"))
            t.setCandidateNameBold(Boolean.parseBoolean(body.get("candidateNameBold").toString()));

        t = templateRepo.save(t);
        return ok("id", t.getId(), "paperSize", t.getPaperSize().name());
    }

    // ── Create ballot combination ─────────────────────────────────────────────

    @PostMapping("/combination")
    @Transactional
    public ResponseEntity<Map<String,Object>> createCombination(
            @RequestBody Map<String,Object> body) {
        Region region = regionRepo.findById(
                Long.valueOf(body.get("regionId").toString()))
            .orElseThrow(() -> new IllegalArgumentException("region not found"));
        Party party = partyRepo.findById(
                Long.valueOf(body.get("partyId").toString()))
            .orElseThrow(() -> new IllegalArgumentException("party not found"));
        BallotType bt = ballotTypeRepo.findById(
                Long.valueOf(body.get("ballotTypeId").toString()))
            .orElseThrow(() -> new IllegalArgumentException("ballot type not found"));
        Election el = electionRepo.findById(
                Long.valueOf(body.get("electionId").toString()))
            .orElseThrow(() -> new IllegalArgumentException("election not found"));

        BallotCombination bc = new BallotCombination();
        bc.setRegion(region);
        bc.setParty(party);
        bc.setBallotType(bt);
        bc.setElection(el);
        bc = combRepo.save(bc);
        return ok("id", bc.getId(), "region", region.getName(), "party", party.getName());
    }

    // ── Generate ballot PDF + YAML + XML ─────────────────────────────────────

    @PostMapping("/generate/{combinationId}")
    public ResponseEntity<Map<String,Object>> generate(
            @PathVariable Long combinationId,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                Long templateId,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                String lang) throws Exception {
        BallotCombination combo = combRepo.findById(combinationId)
            .orElseThrow(() -> new IllegalArgumentException("combination not found"));
        BallotDesignTemplate template = (templateId != null)
            ? templateRepo.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("template not found: " + templateId))
            : templateRepo.findFirstByElectionIdOrderByIdAsc(combo.getElection().getId())
                .orElseThrow(() -> new IllegalStateException("no template for election"));
        User user = userRepo.findByUsername("admin")
            .orElseThrow(() -> new IllegalStateException("admin user not found"));

        ballotService.generateBallot(combo, template, user, 1, lang != null ? lang : "en");
        List<String> files = ballotService.getLastWrittenFiles();
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("combinationId", combinationId);
        resp.put("templateId",    template.getId());
        resp.put("files", files);
        return ResponseEntity.ok(resp);
    }

    // ── List all combinations ─────────────────────────────────────────────────

    @GetMapping("/combinations")
    public ResponseEntity<List<Map<String,Object>>> listCombinations() {
        List<Map<String,Object>> result = new ArrayList<>();
        for (BallotCombination bc : combRepo.findAll()) {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id",         bc.getId());
            m.put("region",     bc.getRegion().getName());
            m.put("regionId",   bc.getRegion().getId());
            m.put("party",      bc.getParty().getName());
            m.put("partyId",    bc.getParty().getId());
            m.put("ballotType", bc.getBallotType().getName());
            m.put("election",   bc.getElection().getName());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    // ── List elections ────────────────────────────────────────────────────────

    @GetMapping("/elections")
    public ResponseEntity<List<Map<String,Object>>> listElections() {
        List<Map<String,Object>> result = new ArrayList<>();
        for (Election e : electionRepo.findAll()) {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id",   e.getId());
            m.put("name", e.getName());
            m.put("jurisdictionId", e.getJurisdiction().getId());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    // ── List contests ─────────────────────────────────────────────────────────

    @GetMapping("/contests")
    public ResponseEntity<List<Map<String,Object>>> listContests(
            @RequestParam(required = false) Long electionId) {
        List<Contest> contests = electionId != null
            ? contestRepo.findByElectionId(electionId)
            : contestRepo.findAll();
        List<Map<String,Object>> result = new ArrayList<>();
        for (Contest c : contests) {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id",    c.getId());
            m.put("title", c.getRecordTitle());
            m.put("votingMethod", c.getVotingMethod().name());
            m.put("maxChoices",   c.getMaxChoices());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    // ── List candidates ───────────────────────────────────────────────────────

    @GetMapping("/candidates")
    public ResponseEntity<List<Map<String,Object>>> listCandidates(
            @RequestParam(required = false) Long contestId) {
        List<Candidate> cands = contestId != null
            ? contestRepo.findById(contestId)
                .map(ct -> ct.getCandidates())
                .orElse(java.util.List.of())
            : candidateRepo.findAll();
        List<Map<String,Object>> result = new ArrayList<>();
        for (Candidate c : cands) {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id",      c.getId());
            m.put("name",    c.getName());
            m.put("writeIn", c.isWriteIn());
            m.put("contestId", c.getContest().getId());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    // ── List templates ────────────────────────────────────────────────────────

    @GetMapping("/templates")
    public ResponseEntity<List<Map<String,Object>>> listTemplates(
            @RequestParam(required = false) Long electionId) {
        List<BallotDesignTemplate> templates = electionId != null
            ? templateRepo.findByElectionId(electionId)
            : templateRepo.findAll();
        List<Map<String,Object>> result = new ArrayList<>();
        for (BallotDesignTemplate t : templates) {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id",        t.getId());
            m.put("paperSize", t.getPaperSize().name());
            m.put("columns",   t.getColumns());
            m.put("indicatorStyle", t.getVoteIndicatorStyle().name());
            m.put("electionId", t.getElection().getId());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    // ── List regions ──────────────────────────────────────────────────────────

    @GetMapping("/regions")
    public ResponseEntity<List<Map<String,Object>>> listRegions(
            @RequestParam(required = false) Long jurisdictionId) {
        List<Region> regions = jurisdictionId != null
            ? regionRepo.findByJurisdictionIdOrderByName(jurisdictionId)
            : regionRepo.findAll();
        List<Map<String,Object>> result = new ArrayList<>();
        for (Region r : regions) {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id",   r.getId());
            m.put("name", r.getName());
            m.put("type", r.getRegionType().name());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    // ── Create language ───────────────────────────────────────────────────────

    @PostMapping("/language")
    @Transactional
    public ResponseEntity<Map<String,Object>> createLanguage(
            @RequestBody Map<String,Object> body) {
        Jurisdiction jur = resolveJurisdiction(body.get("jurisdictionId"));
        BallotLanguage lang = new BallotLanguage();
        lang.setLanguageCode(body.get("languageCode").toString());
        lang.setLanguageName(body.get("languageName").toString());
        lang.setJurisdiction(jur);
        lang = languageRepo.save(lang);
        return ok("id", lang.getId(), "languageCode", lang.getLanguageCode());
    }

    // ── Create contest translation ────────────────────────────────────────────

    @PostMapping("/contest-translation")
    @Transactional
    public ResponseEntity<Map<String,Object>> createContestTranslation(
            @RequestBody Map<String,Object> body) {
        Contest contest = contestRepo.findById(
                Long.valueOf(body.get("contestId").toString()))
            .orElseThrow(() -> new IllegalArgumentException("contest not found"));
        String langCode = body.get("languageCode").toString();
        ContestTranslation ct = contestTranslationRepo
            .findByContestIdAndLanguageCode(contest.getId(), langCode)
            .orElse(new ContestTranslation());
        ct.setContest(contest);
        ct.setLanguageCode(langCode);
        if (body.containsKey("title"))        ct.setTitle(body.get("title").toString());
        if (body.containsKey("instructions")) ct.setInstructions(body.get("instructions").toString());
        if (body.containsKey("preamble"))     ct.setPreamble(body.get("preamble").toString());
        if (body.containsKey("postamble"))    ct.setPostamble(body.get("postamble").toString());
        ct = contestTranslationRepo.save(ct);
        return ok("id", ct.getId(), "languageCode", langCode);
    }

    // ── Create candidate translation ──────────────────────────────────────────

    @PostMapping("/candidate-translation")
    @Transactional
    public ResponseEntity<Map<String,Object>> createCandidateTranslation(
            @RequestBody Map<String,Object> body) {
        Candidate cand = candidateRepo.findById(
                Long.valueOf(body.get("candidateId").toString()))
            .orElseThrow(() -> new IllegalArgumentException("candidate not found"));
        String langCode = body.get("languageCode").toString();
        CandidateTranslation ct = candidateTranslationRepo
            .findByCandidateIdAndLanguageCode(cand.getId(), langCode)
            .orElse(new CandidateTranslation());
        ct.setCandidate(cand);
        ct.setLanguageCode(langCode);
        if (body.containsKey("name"))            ct.setName(body.get("name").toString());
        if (body.containsKey("explanatoryText")) ct.setExplanatoryText(body.get("explanatoryText").toString());
        ct = candidateTranslationRepo.save(ct);
        return ok("id", ct.getId(), "languageCode", langCode);
    }

    // ── Delete individual entities ────────────────────────────────────────────

    @DeleteMapping("/election/{id}")
    @Transactional
    public ResponseEntity<Map<String,Object>> deleteElection(@PathVariable Long id) {
        Election e = electionRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("election not found: " + id));
        printLogRepo.deleteByElection(e);
        combRepo.deleteAll(combRepo.findByElectionId(id));
        contestRepo.deleteAll(contestRepo.findByElectionId(id));
        templateRepo.deleteAll(templateRepo.findByElectionId(id));
        electionRepo.delete(e);
        return ok("deleted", "election", "id", id);
    }

    @DeleteMapping("/contest/{id}")
    @Transactional
    public ResponseEntity<Map<String,Object>> deleteContest(@PathVariable Long id) {
        Contest c = contestRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("contest not found: " + id));
        contestRepo.delete(c);
        return ok("deleted", "contest", "id", id);
    }

    @DeleteMapping("/candidate/{id}")
    @Transactional
    public ResponseEntity<Map<String,Object>> deleteCandidate(@PathVariable Long id) {
        Candidate c = candidateRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("candidate not found: " + id));
        candidateRepo.delete(c);
        return ok("deleted", "candidate", "id", id);
    }

    @DeleteMapping("/template/{id}")
    @Transactional
    public ResponseEntity<Map<String,Object>> deleteTemplate(@PathVariable Long id) {
        BallotDesignTemplate t = templateRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("template not found: " + id));
        templateRepo.delete(t);
        return ok("deleted", "template", "id", id);
    }

    @DeleteMapping("/combination/{id}")
    @Transactional
    public ResponseEntity<Map<String,Object>> deleteCombination(@PathVariable Long id) {
        BallotCombination bc = combRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("combination not found: " + id));
        combRepo.delete(bc);
        return ok("deleted", "combination", "id", id);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String,Object>> ok(Object... kvPairs) {
        Map<String,Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length - 1; i += 2)
            m.put(kvPairs[i].toString(), kvPairs[i+1]);
        return ResponseEntity.ok(m);
    }

    /** Get jurisdiction by id, or the first one if no id provided. */
    private Jurisdiction resolveJurisdiction(Object jIdObj) {
        if (jIdObj != null) {
            return jurisdictionRepo.findById(Long.valueOf(jIdObj.toString()))
                .orElseThrow(() -> new IllegalArgumentException("jurisdiction not found: " + jIdObj));
        }
        return jurisdictionRepo.findAll().stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("No jurisdiction exists — create one first"));
    }

}