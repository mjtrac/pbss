#!/usr/bin/env python3
"""
rcv_tabulate.py — Instant-Runoff Voting (IRV) tabulation for ranked-choice contests.

Reads bCounter's counter_results.db and runs IRV elimination rounds for every
RANKED_CHOICE contest.  Each ballot is reconstructed from the rank boxes that
were marked; the active highest rank is the ballot's current vote.

Algorithm (standard IRV):
  1. Count active rank-1 votes for each remaining candidate.
  2. If any candidate has > 50% of active ballots, they win.  Stop.
  3. Eliminate the candidate(s) with the fewest rank-1 votes.
  4. For each eliminated candidate's ballots, promote the next-ranked
     surviving candidate to become that ballot's active vote.
  5. Repeat from step 1 with the reduced candidate pool.

Ties for last place: all tied-last candidates are eliminated simultaneously
(parallel elimination).  This matches the most common IRV rule for ties.

Usage:
    python3 rcv_tabulate.py [--db path/to/counter_results.db]
                            [--contest "Contest Title"]
                            [--json]  # output JSON instead of text

Output: round-by-round vote totals, eliminated candidates, and winner.
"""

import sqlite3
import argparse
import json
import sys
from collections import defaultdict


# ── Database helpers ──────────────────────────────────────────────────────────

def load_ranked_contests(db_path: str) -> list[dict]:
    """Return all RANKED_CHOICE contest titles from the DB."""
    con = sqlite3.connect(db_path)
    rows = con.execute(
        "SELECT id, contest_title FROM contest WHERE contest_type = 'RANKED_CHOICE'"
    ).fetchall()
    con.close()
    return [{"id": r[0], "title": r[1]} for r in rows]


def load_ballots(db_path: str, contest_id: int) -> list[list[str]]:
    """
    For a given contest, reconstruct each ballot as an ordered list of
    candidate base-names, from rank 1 (most preferred) to rank N.

    A "ballot" here is one ballot_image's set of vote_opportunity rows for
    this contest.  We only include boxes that were marked VOTED.

    Returns a list of ballots; each ballot is a list of candidate base-names
    in rank order (index 0 = rank 1 = most preferred).
    """
    con = sqlite3.connect(db_path)

    # Fetch all VOTED rank-box rows for this contest
    rows = con.execute("""
        SELECT vo.ballot_image_id,
               ca.candidate_name,
               vo.vote_status
        FROM vote_opportunity vo
        JOIN candidate ca ON ca.id = vo.candidate_id_fk
        WHERE vo.contest_id = ?
          AND ca.candidate_name LIKE '%(Rank %)'
          AND vo.vote_status = 'VOTED'
        ORDER BY vo.ballot_image_id, ca.candidate_name
    """, (contest_id,)).fetchall()
    con.close()

    # Group by ballot_image_id; parse rank from "Name (Rank N)"
    import re
    rank_pat = re.compile(r'^(.*)\s+\(Rank\s+(\d+)\)$')

    by_ballot: dict[int, dict[int, str]] = defaultdict(dict)
    for ballot_id, cand_name, status in rows:
        m = rank_pat.match(cand_name)
        if not m:
            continue
        base_name = m.group(1).strip()
        rank      = int(m.group(2))
        by_ballot[ballot_id][rank] = base_name

    # Convert to ordered lists (rank 1 first)
    ballots = []
    for ballot_id, rank_map in by_ballot.items():
        if not rank_map:
            continue
        max_rank = max(rank_map.keys())
        ordered  = [rank_map[r] for r in range(1, max_rank + 1) if r in rank_map]
        if ordered:
            ballots.append(ordered)

    return ballots


# ── IRV engine ────────────────────────────────────────────────────────────────

class IrvBallot:
    """A single ranked-choice ballot with a mutable current-rank pointer."""

    def __init__(self, preferences: list[str]):
        self.preferences = preferences  # ordered list of candidate names
        self.current     = 0            # index of active choice

    def active_choice(self, eliminated: set[str]) -> str | None:
        """Advance past eliminated candidates; return active choice or None."""
        while self.current < len(self.preferences):
            if self.preferences[self.current] not in eliminated:
                return self.preferences[self.current]
            self.current += 1
        return None  # exhausted ballot


def run_irv(ballots_raw: list[list[str]], contest_title: str) -> dict:
    """
    Run IRV tabulation.

    Returns a dict:
      {
        "contest":  str,
        "total_ballots": int,
        "rounds":   [ { "round": N, "counts": {name: int}, "eliminated": [name], "majority": int } ],
        "winner":   str | None,
        "outcome":  "winner" | "tie" | "no_majority"
      }
    """
    ballots   = [IrvBallot(b) for b in ballots_raw]
    eliminated: set[str] = set()
    rounds: list[dict]   = []
    winner: str | None   = None
    outcome              = "no_majority"

    total_initial = len(ballots)

    round_num = 0
    while True:
        round_num += 1

        # Count active votes
        counts: dict[str, int] = defaultdict(int)
        active_total = 0
        for b in ballots:
            choice = b.active_choice(eliminated)
            if choice:
                counts[choice] += 1
                active_total   += 1

        if active_total == 0:
            outcome = "no_majority"
            break

        majority = active_total // 2 + 1
        sorted_cands = sorted(counts.items(), key=lambda x: -x[1])

        round_info = {
            "round":      round_num,
            "counts":     dict(sorted_cands),
            "majority":   majority,
            "active":     active_total,
            "eliminated": [],
        }

        # Check for winner
        leader_name, leader_votes = sorted_cands[0]
        if leader_votes >= majority:
            winner  = leader_name
            outcome = "winner"
            round_info["winner"] = winner
            rounds.append(round_info)
            break

        # Check for two-candidate tie (no elimination possible)
        remaining = [c for c in sorted_cands if c[0] not in eliminated]
        if len(remaining) <= 2:
            # It's a tie or one left
            if len(remaining) == 1:
                winner  = remaining[0][0]
                outcome = "winner"
                round_info["winner"] = winner
            else:
                outcome = "tie"
                round_info["tied"] = [remaining[0][0], remaining[1][0]]
            rounds.append(round_info)
            break

        # Eliminate lowest — all candidates tied for last place
        min_votes = sorted_cands[-1][1]
        to_eliminate = [name for name, v in sorted_cands if v == min_votes]

        # Safety: don't eliminate everyone
        if len(to_eliminate) >= len(remaining):
            outcome = "tie"
            round_info["tied"] = [c for c, _ in remaining]
            rounds.append(round_info)
            break

        for name in to_eliminate:
            eliminated.add(name)
        round_info["eliminated"] = to_eliminate

        rounds.append(round_info)

    return {
        "contest":       contest_title,
        "total_ballots": total_initial,
        "rounds":        rounds,
        "winner":        winner,
        "outcome":       outcome,
    }


# ── Formatting ────────────────────────────────────────────────────────────────

def format_text(result: dict) -> str:
    lines = []
    lines.append("=" * 60)
    lines.append(f"IRV TABULATION: {result['contest']}")
    lines.append("=" * 60)
    lines.append(f"Total ballots cast with rankings: {result['total_ballots']}")
    lines.append("")

    for rnd in result["rounds"]:
        lines.append(f"── Round {rnd['round']} "
                     f"(active ballots: {rnd['active']}, "
                     f"majority needed: {rnd['majority']}) ──")
        for cand, votes in rnd["counts"].items():
            pct  = 100 * votes / rnd["active"] if rnd["active"] else 0
            mark = " ← WINNER" if cand == rnd.get("winner") else ""
            lines.append(f"  {cand:<35} {votes:>5}  ({pct:5.1f}%){mark}")

        if rnd.get("winner"):
            lines.append(f"\n  ✓ {rnd['winner']} wins with a majority.")
        elif rnd.get("tied"):
            lines.append(f"\n  ⚠  Tie between: {', '.join(rnd['tied'])}")
        elif rnd.get("eliminated"):
            elim = ", ".join(rnd["eliminated"])
            lines.append(f"\n  ✗ Eliminated: {elim}")
        lines.append("")

    lines.append("-" * 60)
    if result["outcome"] == "winner":
        lines.append(f"WINNER: {result['winner']}")
    elif result["outcome"] == "tie":
        lines.append("OUTCOME: TIE — no candidate achieved a majority")
    else:
        lines.append("OUTCOME: No majority reached")
    lines.append("=" * 60)
    return "\n".join(lines)


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="IRV (instant-runoff) tabulation from bCounter database"
    )
    parser.add_argument(
        "--db",
        default="../bCounter/counter_results.db",
        help="Path to counter_results.db (default: ../bCounter/counter_results.db)"
    )
    parser.add_argument(
        "--contest",
        default=None,
        help="Contest title to tabulate (default: all RANKED_CHOICE contests)"
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Output results as JSON"
    )
    args = parser.parse_args()

    # Find contests
    try:
        contests = load_ranked_contests(args.db)
    except Exception as e:
        print(f"Error reading database: {e}", file=sys.stderr)
        sys.exit(1)

    if not contests:
        print("No RANKED_CHOICE contests found in the database.")
        sys.exit(0)

    if args.contest:
        contests = [c for c in contests
                    if args.contest.lower() in c["title"].lower()]
        if not contests:
            print(f"No contest matching '{args.contest}' found.", file=sys.stderr)
            sys.exit(1)

    all_results = []
    for contest in contests:
        ballots = load_ballots(args.db, contest["id"])
        if not ballots:
            print(f"No ranked ballots found for: {contest['title']}")
            continue
        result = run_irv(ballots, contest["title"])
        all_results.append(result)

    if args.json:
        print(json.dumps(all_results, indent=2))
    else:
        for result in all_results:
            print(format_text(result))
            print()


if __name__ == "__main__":
    main()
