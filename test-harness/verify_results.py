#!/usr/bin/env python3
"""
verify_results.py — Compare counter database results to the ground truth JSON.

Queries counter_results.db and diffs against ground_truth_all.json.
Prints a summary report and writes verify_report.json.

Usage: python verify_results.py
         [--db   path/to/counter_results.db]
         [--gt   images/ground_truth_all.json]
         [--out  verify_report.json]
"""
import argparse, json, sqlite3
from pathlib import Path

def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--db",  default="${HOME}/pbss_data/db/counter_results.db")
    p.add_argument("--gt",  default="images/ground_truth_all.json")
    p.add_argument("--out", default="verify_report.json")
    return p.parse_args()

def query_db(db_path: str) -> dict:
    """
    Query the counter DB and return:
      actual[contest][candidate] = {"voted": N, "overvoted": N, "unmarked": N}
    """
    con = sqlite3.connect(db_path)
    cur = con.cursor()
    cur.execute("""
        SELECT
            k.contest_title,
            c.candidate_name,
            SUM(CASE WHEN v.vote_status = 'VOTED'     THEN 1 ELSE 0 END),
            SUM(CASE WHEN v.vote_status = 'OVERVOTED' THEN 1 ELSE 0 END),
            SUM(CASE WHEN v.vote_status = 'UNMARKED'  THEN 1 ELSE 0 END)
        FROM vote_opportunity  v
        JOIN candidate         c ON v.candidate_id_fk = c.id
        JOIN contest           k ON v.contest_id      = k.id
        GROUP BY k.contest_title, c.candidate_name
        ORDER BY k.contest_title, c.candidate_name
    """)
    rows = cur.fetchall()
    con.close()

    actual = {}
    for contest, cand, voted, overvoted, unmarked in rows:
        actual.setdefault(contest, {})[cand] = {
            "voted":     voted or 0,
            "overvoted": overvoted or 0,
            "unmarked":  unmarked or 0,
        }
    return actual

def summarize_gt(gt: dict) -> dict:
    """Aggregate per-image ground truth to contest/candidate vote counts."""
    expected: dict = {}
    per_image = gt.get("per_image", gt)
    for img_path, entry in per_image.items():
        for ind in entry.get("indicators", []):
            contest = ind["contest"]
            cand    = ind["candidate"]
            status  = ind.get("counted_as", "UNMARKED")
            expected.setdefault(contest, {}).setdefault(cand, {
                "voted": 0, "overvoted": 0, "unmarked": 0
            })
            if status == "VOTED":
                expected[contest][cand]["voted"] += 1
            elif status == "OVERVOTED":
                expected[contest][cand]["overvoted"] += 1
            else:
                expected[contest][cand]["unmarked"] += 1
    return expected

def main():
    args = parse_args()

    print(f"Database: {args.db}")
    print(f"Ground truth: {args.gt}")

    if not Path(args.db).exists():
        print(f"✗ Database not found: {args.db}")
        return
    if not Path(args.gt).exists():
        print(f"✗ Ground truth not found: {args.gt}")
        return

    actual = query_db(args.db)
    with open(args.gt) as f:
        gt = json.load(f)
    expected = summarize_gt(gt)

    report = {"matches": [], "mismatches": [], "missing_in_db": [],
              "missing_in_gt": []}

    all_contests = set(actual) | set(expected)
    for contest in sorted(all_contests):
        act_c = actual.get(contest, {})
        exp_c = expected.get(contest, {})
        all_cands = set(act_c) | set(exp_c)
        for cand in sorted(all_cands):
            act = act_c.get(cand, {"voted":0,"overvoted":0,"unmarked":0})
            exp = exp_c.get(cand, {"voted":0,"overvoted":0,"unmarked":0})
            row = {"contest": contest, "candidate": cand,
                   "expected": exp, "actual": act}
            if contest not in actual or cand not in act_c:
                report["missing_in_db"].append(row)
            elif contest not in expected or cand not in exp_c:
                report["missing_in_gt"].append(row)
            else:
                # Compare total physical marks (voted + overvoted) since an
                # overvote is still a real mark — the contest is overvoted but
                # each indicator was genuinely filled in by the voter.
                act_marks = act["voted"] + act["overvoted"]
                exp_marks = exp["voted"] + exp["overvoted"]
                if act_marks == exp_marks:
                    report["matches"].append(row)
                else:
                    row["delta_voted"] = act_marks - exp_marks
                    report["mismatches"].append(row)

    # Print summary
    print(f"\n{'─'*60}")
    print(f"  Matching:        {len(report['matches'])}  (voted+overvoted combined)")
    print(f"  Mismatches:      {len(report['mismatches'])}")
    print(f"  Missing in DB:   {len(report['missing_in_db'])}")
    print(f"  Missing in GT:   {len(report['missing_in_gt'])}")
    print(f"{'─'*60}")

    if report["mismatches"]:
        print("\nMISMATCHES:")
        for r in report["mismatches"]:
            print(f"  {r['contest']} / {r['candidate']}")
            exp_marks = r['expected']['voted'] + r['expected']['overvoted']
            act_marks  = r['actual']['voted']   + r['actual']['overvoted']
            print(f"    expected marks={exp_marks}  "
                  f"actual marks={act_marks}  "
                  f"delta={r['delta_voted']:+d}")

    if report["missing_in_db"]:
        print("\nMISSING IN DATABASE (expected but not counted):")
        for r in report["missing_in_db"][:10]:
            print(f"  {r['contest']} / {r['candidate']}")

    with open(args.out, "w") as f:
        json.dump(report, f, indent=2)
    print(f"\nFull report written to {args.out}")

    total = len(report["matches"]) + len(report["mismatches"])
    if total > 0:
        pct = 100 * len(report["matches"]) / total
        print(f"Accuracy: {pct:.1f}%  ({len(report['matches'])}/{total})")

if __name__ == "__main__":
    main()
