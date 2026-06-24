/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.counter.service;

import gov.election.counter.entity.ContestRecord;
import gov.election.counter.entity.VoteOpportunity;
import gov.election.counter.repository.ContestRecordRepository;
import gov.election.counter.repository.VoteOpportunityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Instant-Runoff Voting (IRV) tabulation service.
 *
 * Reads the counter_results.db read-only (no writes), runs IRV elimination
 * rounds for every RANKED_CHOICE contest, and writes rcv_report.html to the
 * reports output directory.
 *
 * Algorithm:
 *   1. Count active rank-1 votes for each remaining candidate.
 *   2. If any candidate has > 50% of active ballots → winner.
 *   3. Eliminate candidate(s) with fewest rank-1 votes (parallel elimination
 *      for ties at the bottom).
 *   4. Promote next surviving ranked choice on each eliminated ballot.
 *   5. Repeat until winner, tie, or no active ballots remain.
 */
@Service
public class RcvTabulationService {

    private static final Logger log =
        LoggerFactory.getLogger(RcvTabulationService.class);

    private static final Pattern RANK_PAT =
        Pattern.compile("^(.+?)\\s+\\(Rank\\s+(\\d+)\\)$");

    private final ContestRecordRepository   contestRepo;
    private final VoteOpportunityRepository voRepo;

    public RcvTabulationService(ContestRecordRepository   contestRepo,
                                 VoteOpportunityRepository voRepo) {
        this.contestRepo = contestRepo;
        this.voRepo      = voRepo;
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Run IRV tabulation for all RANKED_CHOICE contests and write
     * rcv_report.html to the given output directory.
     *
     * @param reportOutputDir directory to write rcv_report.html
     * @return list of RCV results (one per contest); empty if none found
     */
    @Transactional(readOnly = true)
    public List<RcvResult> tabulateAndWrite(String reportOutputDir) {
        List<ContestRecord> ranked = contestRepo.findAll().stream()
            .filter(c -> "RANKED_CHOICE".equals(c.getContestType()))
            .collect(Collectors.toList());

        if (ranked.isEmpty()) {
            log.debug("No RANKED_CHOICE contests found — skipping RCV tabulation");
            return Collections.emptyList();
        }

        List<RcvResult> results = new ArrayList<>();
        for (ContestRecord contest : ranked) {
            List<List<String>> ballots = loadBallots(contest.getId());
            if (ballots.isEmpty()) {
                log.info("RCV: no ranked ballots for '{}' — skipping", contest.getContestTitle());
                continue;
            }
            RcvResult result = runIrv(contest.getContestTitle(), ballots);
            results.add(result);
            log.info("RCV: '{}' → outcome={} winner={}",
                contest.getContestTitle(), result.outcome, result.winner);
        }

        if (!results.isEmpty()) {
            writeHtmlReport(results, reportOutputDir);
        }
        return results;
    }

    // ── Ballot loading ─────────────────────────────────────────────────────────

    private List<List<String>> loadBallots(Long contestId) {
        List<VoteOpportunity> vos = voRepo.findByContest_Id(contestId);

        // Keep only VOTED rank-box rows.
        // Track overvoted ranks: if two candidates are marked at the same rank
        // on the same ballot, that rank position is invalid and the ballot is
        // exhausted at that point (cannot be counted or transferred past it).
        Map<Long, Map<Integer, String>> byBallot  = new LinkedHashMap<>();
        Map<Long, Set<Integer>>         overvoted = new LinkedHashMap<>();

        for (VoteOpportunity vo : vos) {
            String candName = vo.getCandidate().getCandidateName();
            if (!"VOTED".equals(vo.getVoteStatus() != null
                    ? vo.getVoteStatus().name() : "")) continue;
            Matcher m = RANK_PAT.matcher(candName);
            if (!m.matches()) continue;
            String baseName = m.group(1).strip();
            int    rank     = Integer.parseInt(m.group(2));
            Long   ballotId = vo.getBallotImage().getId();

            Map<Integer, String> rankMap =
                byBallot.computeIfAbsent(ballotId, k -> new TreeMap<>());
            if (rankMap.containsKey(rank)) {
                // Two candidates marked at same rank — overvote at this position
                overvoted.computeIfAbsent(ballotId, k -> new java.util.HashSet<>())
                         .add(rank);
                log.debug("RCV overvote: ballot {} has two marks at Rank {} — "
                    + "ballot exhausted at that rank", ballotId, rank);
            } else {
                rankMap.put(rank, baseName);
            }
        }

        // Convert to ordered preference lists (rank 1 first).
        // Truncate at any overvoted rank — the ballot is exhausted there.
        List<List<String>> ballots = new ArrayList<>();
        for (Map.Entry<Long, Map<Integer, String>> e : byBallot.entrySet()) {
            Map<Integer, String> rankMap = e.getValue();
            Set<Integer> badRanks = overvoted.getOrDefault(e.getKey(), Set.of());
            if (rankMap.isEmpty()) continue;
            int maxRank = Collections.max(rankMap.keySet());
            List<String> prefs = new ArrayList<>();
            for (int r = 1; r <= maxRank; r++) {
                if (badRanks.contains(r)) break; // exhausted at this rank
                if (rankMap.containsKey(r)) prefs.add(rankMap.get(r));
            }
            if (!prefs.isEmpty()) ballots.add(prefs);
        }
        return ballots;
    }

    // ── IRV engine ─────────────────────────────────────────────────────────────

    RcvResult runIrv(String contestTitle, List<List<String>> ballotsRaw) {
        // Mutable pointer per ballot: index of current active choice
        int[] ptr       = new int[ballotsRaw.size()];
        Set<String> eliminated = new LinkedHashSet<>();
        List<RcvRound> rounds  = new ArrayList<>();
        String winner          = null;
        String outcome         = "no_majority";
        int    totalBallots    = ballotsRaw.size();

        for (int roundNum = 1; roundNum <= ballotsRaw.size() + 1; roundNum++) {

            // Count active rank-1 votes
            Map<String, Integer> counts = new LinkedHashMap<>();
            int activeTotal = 0;
            for (int i = 0; i < ballotsRaw.size(); i++) {
                String choice = activeChoice(ballotsRaw.get(i), ptr, i, eliminated);
                if (choice != null) {
                    counts.merge(choice, 1, Integer::sum);
                    activeTotal++;
                }
            }

            if (activeTotal == 0) { outcome = "no_majority"; break; }

            int majority = activeTotal / 2 + 1;

            // Sort descending by votes
            List<Map.Entry<String, Integer>> sorted = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
                .collect(Collectors.toList());

            RcvRound round = new RcvRound(roundNum, majority, activeTotal,
                                          new LinkedHashMap<>(), null, null, null);
            sorted.forEach(e -> round.counts.put(e.getKey(), e.getValue()));

            // Check winner
            String leader  = sorted.get(0).getKey();
            int    leading = sorted.get(0).getValue();
            if (leading >= majority) {
                winner          = leader;
                outcome         = "winner";
                round.winner    = winner;
                rounds.add(round);
                break;
            }

            // Check if only 1-2 candidates left
            if (sorted.size() <= 2) {
                if (sorted.size() == 1) {
                    winner       = sorted.get(0).getKey();
                    outcome      = "winner";
                    round.winner = winner;
                } else {
                    outcome      = "tie";
                    round.tied   = List.of(sorted.get(0).getKey(),
                                           sorted.get(1).getKey());
                }
                rounds.add(round);
                break;
            }

            // Eliminate all tied-last candidates
            int minVotes = sorted.get(sorted.size() - 1).getValue();
            List<String> toElim = sorted.stream()
                .filter(e -> e.getValue() == minVotes)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

            if (toElim.size() >= sorted.size()) {
                outcome    = "tie";
                round.tied = sorted.stream().map(Map.Entry::getKey)
                                   .collect(Collectors.toList());
                rounds.add(round);
                break;
            }

            round.eliminated = toElim;
            eliminated.addAll(toElim);

            // Advance pointers past eliminated candidates
            for (int i = 0; i < ballotsRaw.size(); i++) {
                List<String> prefs = ballotsRaw.get(i);
                while (ptr[i] < prefs.size()
                       && eliminated.contains(prefs.get(ptr[i]))) {
                    ptr[i]++;
                }
            }

            rounds.add(round);
        }

        return new RcvResult(contestTitle, totalBallots, rounds, winner, outcome);
    }

    private String activeChoice(List<String> prefs, int[] ptr, int i,
                                 Set<String> eliminated) {
        while (ptr[i] < prefs.size()) {
            if (!eliminated.contains(prefs.get(ptr[i]))) return prefs.get(ptr[i]);
            ptr[i]++;
        }
        return null;
    }

    // ── HTML report ────────────────────────────────────────────────────────────

    private void writeHtmlReport(List<RcvResult> results, String reportOutputDir) {
        Path out = Paths.get(reportOutputDir, "rcv_report.html");
        String ts = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("MMMM d, yyyy  h:mm a"));

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {
            pw.println("<!DOCTYPE html>");
            pw.println("<html lang=\"en\"><head><meta charset=\"UTF-8\">");
            pw.println("<title>RCV Tabulation Report</title>");
            pw.println("<style>");
            pw.println("body{font-family:Arial,sans-serif;font-size:11pt;max-width:900px;margin:2rem auto;color:#111}");
            pw.println("h1{font-size:16pt;border-bottom:3px solid #1a2744;padding-bottom:.4rem;color:#1a2744}");
            pw.println("h2{font-size:14pt;margin:2.5rem 0 .3rem;color:#1a2744}");
            pw.println(".meta{color:#555;font-size:10pt;margin:.2rem 0 1rem}");
            pw.println(".rh{background:#1a2744;color:#fff;padding:.35rem .7rem;border-radius:4px 4px 0 0;font-weight:bold;font-size:10.5pt}");
            pw.println(".rn{font-size:9pt;color:#cce;font-weight:normal;margin-left:.5rem}");
            pw.println("table{border-collapse:collapse;width:100%;margin-bottom:1rem}");
            pw.println("th{text-align:left;background:#2d4070;color:#fff;padding:.3rem .6rem;font-size:10pt}");
            pw.println("th.r{text-align:right}");
            pw.println("td{padding:.28rem .6rem;border-bottom:1px solid #e5e7eb;font-size:10.5pt}");
            pw.println("td.r{text-align:right;font-variant-numeric:tabular-nums}");
            pw.println("td.p{text-align:right;color:#555;font-size:.9em}");
            pw.println("tr.winner td{background:#dcfce7;font-weight:bold}");
            pw.println("tr.elim td{background:#fef3c7;color:#92400e}");
            pw.println("tr.leader td{background:#eff6ff}");
            pw.println(".badge{display:inline-block;font-size:.75em;padding:1px 7px;border-radius:3px;margin-left:.6rem;vertical-align:middle}");
            pw.println(".bw{background:#16a34a;color:#fff}.be{background:#d97706;color:#fff}");
            pw.println(".bar-bg{background:#e5e7eb;border-radius:3px;height:10px;width:150px;display:inline-block;vertical-align:middle}");
            pw.println(".bar{border-radius:3px;height:10px}.bar.leader{background:#3b82f6}.bar.winner{background:#16a34a}.bar.elim{background:#d97706}.bar.normal{background:#1a2744}");
            pw.println(".tn{font-size:9.5pt;color:#555;font-style:italic;padding:.4rem .7rem;border-left:3px solid #d97706;background:#fffbeb;margin:.3rem 0 .6rem}");
            pw.println(".outcome{margin-top:1rem;padding:.55rem 1.1rem;border-radius:5px;font-weight:bold;font-size:12pt}");
            pw.println(".ow{background:#dcfce7;color:#166534}.ot{background:#fef3c7;color:#92400e}.on{background:#f1f5f9;color:#475569}");
            pw.println("@media print{body{margin:1cm}}");
            pw.println("</style></head><body>");
            pw.printf("<h1>Ranked-Choice Voting \u2014 Tabulation Report</h1>%n");
            pw.printf("<p class=\"meta\">Generated: %s</p>%n", h(ts));

            for (RcvResult result : results) {
                pw.printf("<h2>%s</h2>%n", h(result.contest));
                pw.printf("<p class=\"meta\">Total ballots with rankings: <strong>%,d</strong>"
                    + " &nbsp;&middot;&nbsp; Rounds required: <strong>%d</strong></p>%n",
                    result.totalBallots, result.rounds.size());

                Map<String, Integer> round1Counts = result.rounds.isEmpty()
                    ? Collections.emptyMap() : result.rounds.get(0).counts;

                for (int ri = 0; ri < result.rounds.size(); ri++) {
                    RcvRound round = result.rounds.get(ri);
                    boolean isFinal = ri == result.rounds.size() - 1;
                    String roundLabel = "Round " + round.round + (isFinal ? " \u2014 Final" : "");

                    pw.printf("<div class=\"rh\">%s"
                        + "<span class=\"rn\">active ballots: %,d"
                        + " &nbsp;|&nbsp; majority threshold: %,d</span></div>%n",
                        h(roundLabel), round.active, round.majority);

                    // Transfer note
                    if (ri > 0) {
                        RcvRound prev = result.rounds.get(ri - 1);
                        if (prev.eliminated != null && !prev.eliminated.isEmpty()) {
                            int transferred = prev.eliminated.stream()
                                .mapToInt(e -> prev.counts.getOrDefault(e, 0)).sum();
                            String elimNames = String.join(", ", prev.eliminated);
                            pw.printf("<div class=\"tn\">"
                                + "&#8599; <strong>%s</strong> eliminated in Round %d "
                                + "&mdash; <strong>%,d</strong> ballot(s) redistributed "
                                + "to next surviving ranked choice."
                                + "</div>%n", h(elimNames), prev.round, transferred);
                        }
                    }

                    pw.println("<table>");
                    pw.print("  <tr><th>Candidate</th>");
                    pw.print("<th class=\"r\">Rank-1 Votes</th>");
                    pw.print("<th class=\"r\">% Active</th>");
                    pw.print("<th>Vote Distribution</th>");
                    if (ri > 0) pw.print("<th class=\"r\">Change vs Round 1</th>");
                    pw.println("</tr>");

                    List<String> elimThisRound = round.eliminated != null
                        ? round.eliminated : Collections.emptyList();
                    int maxVotes = round.counts.values().stream()
                        .max(Integer::compare).orElse(0);

                    for (Map.Entry<String, Integer> e : round.counts.entrySet()) {
                        String cand  = e.getKey();
                        int    votes = e.getValue();
                        double pct   = round.active > 0 ? 100.0 * votes / round.active : 0;
                        int    barW  = Math.min((int) Math.round(pct * 1.5), 150);

                        boolean isWinner = cand.equals(round.winner);
                        boolean isElim   = elimThisRound.contains(cand);
                        boolean isLeader = !isWinner && !isElim && votes == maxVotes;
                        String rowClass  = isWinner ? "winner" : isElim ? "elim"
                                         : isLeader ? "leader" : "";
                        String barClass  = isWinner ? "winner" : isElim ? "elim"
                                         : isLeader ? "leader" : "normal";

                        pw.printf("  <tr class=\"%s\">%n", rowClass);
                        pw.printf("    <td>%s%s</td>%n", h(cand),
                            isWinner ? "<span class=\"badge bw\">\u2713 Winner</span>"
                            : isElim ? "<span class=\"badge be\">Eliminated</span>" : "");
                        pw.printf("    <td class=\"r\">%,d</td>%n", votes);
                        pw.printf("    <td class=\"p\">%.1f%%</td>%n", pct);
                        pw.printf("    <td><div class=\"bar-bg\">"
                            + "<div class=\"bar %s\" style=\"width:%dpx\"></div>"
                            + "</div></td>%n", barClass, barW);
                        if (ri > 0) {
                            int r1v = round1Counts.getOrDefault(cand, 0);
                            int diff = votes - r1v;
                            String delta = diff > 0
                                ? "<span style=\"color:#16a34a\">+" + String.format("%,d", diff) + "</span>"
                                : diff < 0
                                ? "<span style=\"color:#dc2626\">" + String.format("%,d", diff) + "</span>"
                                : "\u2014";
                            pw.printf("    <td class=\"r\">%s</td>%n", delta);
                        }
                        pw.println("  </tr>");
                    }
                    pw.println("</table>");
                }

                // Outcome
                String oc = result.outcome;
                String ocClass = "winner".equals(oc) ? "ow" : "tie".equals(oc) ? "ot" : "on";
                String ocText  = "winner".equals(oc) ? "\u2713 Winner: " + h(result.winner)
                               : "tie".equals(oc) ? "\u26a0 Tie \u2014 no majority reached"
                               : "No majority reached";
                pw.printf("<div class=\"outcome %s\">%s</div><br>%n", ocClass, ocText);
            }

            pw.println("</body></html>");
            log.info("RCV report written: {}", out.toAbsolutePath());

        } catch (Exception ex) {
            log.warn("Could not write rcv_report.html: {}", ex.getMessage());
        }
    }

    private static String h(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;");
    }

    // ── Result DTOs ────────────────────────────────────────────────────────────

    public static class RcvResult {
        public final String        contest;
        public final int           totalBallots;
        public final List<RcvRound> rounds;
        public final String        winner;
        public final String        outcome;   // "winner" | "tie" | "no_majority"

        RcvResult(String contest, int totalBallots, List<RcvRound> rounds,
                  String winner, String outcome) {
            this.contest      = contest;
            this.totalBallots = totalBallots;
            this.rounds       = rounds;
            this.winner       = winner;
            this.outcome      = outcome;
        }
    }

    public static class RcvRound {
        public final int                    round;
        public final int                    majority;
        public final int                    active;
        public final Map<String, Integer>   counts;      // candidate → votes, desc order
        public       String                 winner;      // set if this round has a winner
        public       List<String>           eliminated;  // set if candidates eliminated
        public       List<String>           tied;        // set if tie

        RcvRound(int round, int majority, int active,
                 Map<String, Integer> counts,
                 String winner, List<String> eliminated, List<String> tied) {
            this.round      = round;
            this.majority   = majority;
            this.active     = active;
            this.counts     = counts;
            this.winner     = winner;
            this.eliminated = eliminated;
            this.tied       = tied;
        }
    }
}
