/*
 * TestApiController — JSON REST API for test harness automation.
 * Provides endpoints to create/reset all ballot data and generate ballots
 * without going through the HTML UI.
 *
 * All endpoints require ADMIN role and are under /api/test/.
 * CSRF is disabled for these endpoints via TestApiSecurityConfig.
 */
package gov.election.ballot.controller;

import gov.election.ballot.model.*;
import gov.election.ballot.model.BallotDesignTemplate.PaperSize;
import gov.election.ballot.model.Region.RegionType;
import gov.election.ballot.repository.*;
import gov.election.ballot.service.BallotGenerationService;
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
    private final UserRepository            userRepo;

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
            UserRepository userRepo) {
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
        this.userRepo         = userRepo;
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
        if (body.containsKey("headerHeadline"))
            t.setHeaderHeadline(body.get("headerHeadline").toString());
        if (body.containsKey("headerBodyText"))
            t.setHeaderBodyText(body.get("headerBodyText").toString());

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