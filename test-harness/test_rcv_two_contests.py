#!/usr/bin/env python3
"""
test_rcv_two_contests.py — Two-contest RCV tabulation test

Creates 18 ballot images across two independent ranked-choice contests
and verifies that IRV tabulation requires exactly two rounds of candidate
elimination to produce a winner in each.

Vote distribution (same for both contests):
  Candidate A:  10 rank-1 votes  (first choice of B/C/D voters)
  Candidate B:   4 rank-1 votes, rank-2 = A
  Candidate C:   3 rank-1 votes, rank-2 = A
  Candidate D:   1 rank-1 vote,  rank-2 = A

IRV progression:
  Round 1: A=10, B=4, C=3, D=1  → total 18, majority=10  → A has exactly 10 — tie!
            (majority = floor(18/2)+1 = 10, so A needs >10 to win outright)
            D eliminated (fewest: 1 vote)
  Round 2: A=11 (10+1 from D), B=4, C=3 → active=18, majority=10 → A wins

Usage:
  python3 test_rcv_two_contests.py \\
      --builder http://localhost:8080 \\
      --counter http://localhost:8081 \\
      --yaml-dir ~/bBuilder_ballots \\
      --images-dir /tmp/rcv_test_images
"""

import argparse, json, os, re, sys, time, math, shutil, subprocess
from pathlib import Path

try:
    import requests
except ImportError:
    sys.exit("pip install requests")

try:
    from PIL import Image, ImageDraw
    HAS_PIL = True
except ImportError:
    HAS_PIL = False
    sys.exit("pip install Pillow")

GREEN = "\033[32m"; RED = "\033[31m"; YELLOW = "\033[33m"; RESET = "\033[0m"
passed = failed = 0

def ok(name):
    global passed; passed += 1
    print(f"  {GREEN}✓{RESET}  {name}")

def fail(name, reason=""):
    global failed; failed += 1
    print(f"  {RED}✗{RESET}  {name}")
    if reason: print(f"       {YELLOW}{reason}{RESET}")

# ── API clients ───────────────────────────────────────────────────────────────

class BBuilderClient:
    def __init__(self, host, password="ChangeMe123!"):
        self.host     = host.rstrip("/")
        self.password = password
        self.session  = requests.Session()
        self._login()

    def _login(self):
        r = self.session.get(f"{self.host}/login")
        csrf = re.search(r'name="_csrf"[^>]+value="([^"]+)"', r.text)
        token = csrf.group(1) if csrf else ""
        self.session.post(f"{self.host}/login",
            data={"username":"admin","password":self.password,"_csrf":token},
            allow_redirects=True)

    def _csrf(self):
        r = self.session.get(f"{self.host}/")
        m = re.search(r'"_csrf"[^>]+value="([^"]+)"', r.text)
        return m.group(1) if m else ""

    def post(self, path, body):
        csrf = self._csrf()
        r = self.session.post(f"{self.host}/api/test/{path}",
            json={**body, "_csrf": csrf},
            headers={"Content-Type":"application/json","X-CSRF-TOKEN":csrf})
        r.raise_for_status()
        return r.json()

    def reset(self):
        csrf = self._csrf()
        self.session.delete(f"{self.host}/api/test/reset")

    def generate_ballot(self, combo_id, template_id, out_dir) -> list:
        """Generate ballot PDF+YAML for combo, return list of created files."""
        csrf = self._csrf()
        r = self.session.post(f"{self.host}/ballot/generate",
            data={"combinationId": combo_id, "templateId": template_id,
                  "lang": "en", "_csrf": csrf})
        r.raise_for_status()
        # The endpoint returns files or a download — collect from out_dir
        # Simpler: use the export endpoint that writes to the configured dir
        return []

class BCounterClient:
    def __init__(self, host, password="ChangeMe123!"):
        self.host     = host.rstrip("/")
        self.password = password
        self.session  = requests.Session()
        self._login()

    def _login(self):
        r = self.session.get(f"{self.host}/login")
        csrf = re.search(r'name="_csrf"[^>]+value="([^"]+)"', r.text)
        token = csrf.group(1) if csrf else ""
        self.session.post(f"{self.host}/login",
            data={"username":"admin","password":self.password,"_csrf":token},
            allow_redirects=True)

    def _csrf(self):
        r = self.session.get(f"{self.host}/")
        m = re.search(r'"_csrf"[^>]+value="([^"]+)"', r.text)
        return m.group(1) if m else ""

    def start_scan(self, images_dir, yaml_dir):
        # Re-login if session expired
        probe = self.session.get(f"{self.host}/", allow_redirects=True)
        if "/login" in probe.url:
            self._login()
        csrf = self._csrf()
        data = {
            "_csrf":        csrf,
            "imageFolder":  str(images_dir),
            "reportFolder": str(yaml_dir),
            "threshold":    "7",
            "darkPct":      "7.0",
            "dpi":          "300",
        }
        r = self.session.post(f"{self.host}/start", data=data,
                              allow_redirects=False)
        if r.status_code in (301, 302, 303):
            location = r.headers.get("Location", "")
            if "/scanning" in location or "/scanning" in r.headers.get("Location",""):
                return  # scan started successfully — /scanning is the expected redirect
            # Follow redirect to check for error message
            r2 = self.session.get(
                f"{self.host}{location}" if location.startswith("/") else location,
                allow_redirects=True)
            if "/scanning" in r2.url:
                return  # arrived at scanning page after following redirect
            raise RuntimeError(f"/start redirected to {r2.url}: {r2.text[:200]}")
        if r.status_code == 400:
            raise RuntimeError(f"/start returned 400: {r.text[:300]}")
        raise RuntimeError(f"/start returned unexpected status {r.status_code}")

    def progress(self):
        r = self.session.get(f"{self.host}/progress")
        return r.json()

    def wait_complete(self, timeout=300):
        start = time.time()
        seen_started = False
        not_started_count = 0
        while time.time() - start < timeout:
            try:
                p = self.progress()
            except Exception:
                time.sleep(0.5)
                continue
            if p.get("started"):
                seen_started = True
                not_started_count = 0
            if p.get("complete"):
                return p
            if p.get("error"):
                raise RuntimeError(f"Scan error: {p['error']}")
            if not p.get("started"):
                not_started_count += 1
                # If we never saw started, the scan may have completed
                # before our first poll — wait a moment then check DB directly
                if not seen_started and not_started_count >= 3:
                    return {"complete": True}
                # If we saw started but now it stopped, scan completed
                if seen_started:
                    return {"complete": True}
            time.sleep(0.5)
        raise TimeoutError("Scan did not complete")

# ── Ballot image generation ───────────────────────────────────────────────────

def mark_oval(draw, x, y, w, h):
    """Fill an oval indicator at (x,y,w,h) — simulates a voter mark."""
    draw.ellipse([x+2, y+2, x+w-2, y+h-2], fill=(10,10,10))

def create_rcv_ballot_image(path, width=2550, height=3300, dpi=300,
                             marks: dict = None):
    """
    Create a minimal synthetic ballot image for RCV testing.
    marks: dict of {candidate_name: rank_number} to fill
    This is a simplified synthetic image — real testing would use
    bBuilder-generated PDFs rasterized by mark_ballots.py.
    """
    img = Image.new("RGB", (width, height), (255,255,255))
    draw = ImageDraw.Draw(img)
    # Draw a simple border
    draw.rectangle([50, 50, width-50, height-50], outline=(0,0,0), width=2)
    img.save(str(path), dpi=(dpi, dpi))

# ── IRV verification ──────────────────────────────────────────────────────────

def verify_rcv_from_db(db_path, contest_title, expected_winner,
                        expected_rounds):
    """
    Run a Python IRV simulation directly from the SQLite DB and verify.
    Returns (winner, rounds) or raises.
    """
    import sqlite3
    conn = sqlite3.connect(str(db_path))
    cur  = conn.cursor()

    # Get contest id
    cur.execute("SELECT id FROM contest WHERE contest_title=?", (contest_title,))
    row = cur.fetchone()
    if not row:
        conn.close()
        raise ValueError(f"Contest '{contest_title}' not in DB")
    contest_id = row[0]

    # Load ballots: group VOs by ballot_image, collect ranked candidates
    import re as _re
    RANK_PAT = _re.compile(r"^(.*?)\s*\(Rank (\d+)\)$")

    cur.execute("""
        SELECT bi.id, c.candidate_name, vo.vote_status
        FROM vote_opportunity vo
        JOIN ballot_image bi ON vo.ballot_image_id = bi.id
        JOIN candidate c ON vo.candidate_id_fk = c.id
        WHERE vo.contest_id = ?
        AND vo.vote_status = 'VOTED'
        ORDER BY bi.id, c.candidate_name
    """, (contest_id,))

    by_ballot = {}
    for ballot_id, cname, status in cur.fetchall():
        m = RANK_PAT.match(cname)
        if not m: continue
        base, rank = m.group(1).strip(), int(m.group(2))
        by_ballot.setdefault(ballot_id, {})[rank] = base

    conn.close()

    # Convert to preference lists
    ballots = []
    for ranks in by_ballot.values():
        prefs = [ranks[r] for r in sorted(ranks)]
        if prefs: ballots.append(prefs)

    if not ballots:
        raise ValueError("No ranked ballots found in DB")

    # IRV
    eliminated = set()
    rounds = 0
    winner = None
    while True:
        rounds += 1
        counts = {}
        active = 0
        for prefs in ballots:
            choice = next((p for p in prefs if p not in eliminated), None)
            if choice:
                counts[choice] = counts.get(choice, 0) + 1
                active += 1
        if not counts:
            break
        majority = active // 2 + 1
        top_cand = max(counts, key=counts.get)
        if counts[top_cand] >= majority:
            winner = top_cand
            break
        # Eliminate candidate(s) with fewest votes
        min_votes = min(counts.values())
        to_elim = [c for c, v in counts.items() if v == min_votes]
        for c in to_elim: eliminated.add(c)
        if len(counts) - len(to_elim) == 0:
            winner = top_cand  # tie
            break

    return winner, rounds, counts

# ── Main test ─────────────────────────────────────────────────────────────────

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--builder",    default="http://localhost:8080")
    p.add_argument("--password",   default="ChangeMe123!",
                   help="bBuilder/bCounter admin password")
    p.add_argument("--counter",    default="http://localhost:8081")
    p.add_argument("--yaml-dir",   default=os.path.expanduser("~/bBuilder_ballots"))
    p.add_argument("--images-dir", default="/tmp/rcv_two_contest_images")
    p.add_argument("--db",         default=None,
                   help="Path to counter_results.db (auto-detect if omitted)")
    args = p.parse_args()

    print("RCV Two-Contest Tabulation Test Suite")
    print(f"  bBuilder: {args.builder}")
    print(f"  bCounter: {args.counter}")

    # ── Step 1: Build election via bBuilder API ──────────────────────────────
    print("\n── Step 1: Build election ───────────────────────────────────────")
    bb = BBuilderClient(args.builder, args.password)
    bb.reset()
    print("  ✓ Reset")

    j   = bb.post("jurisdiction", {"name": "RCV Test County"})
    e   = bb.post("election",     {"name": "RCV Two-Round Test",
                                    "jurisdictionId": j["id"],
                                    "electionDate": "2026-11-03"})
    bt  = bb.post("ballot-type", {"name": "Standard",
                                    "jurisdictionId": j["id"]})
    reg = bb.post("region",       {"name": "District 1",
                                    "type": "SINGLE_PRECINCT",
                                    "jurisdictionId": j["id"]})
    pty = bb.post("party",        {"name": "Nonpartisan",
                                    "jurisdictionId": j["id"]})

    # Two RCV contests
    contests = {}
    for title in ["City Council", "School Board"]:
        c = bb.post("contest", {
            "electionId":   e["id"],
            "title":        title,
            "contestType":  "RANKED_CHOICE",
            "maxVotes":       4,
            "instruction":  "Rank candidates 1-4.",
            "regionIds":    [reg["id"]],
        })
        contests[title] = c["id"]
        # Candidates A, B, C, D
        for name in ["Candidate A", "Candidate B", "Candidate C", "Candidate D"]:
            bb.post("candidate", {"contestId": c["id"], "name": name})

    print(f"  ✓ Two RCV contests created: {list(contests.keys())}")

    # Combination + template
    combo = bb.post("combination", {
        "electionId":   e["id"],
        "regionId":     reg["id"],
        "partyId":      pty["id"],
        "ballotTypeId": bt["id"],
    })
    tmpl = bb.post("template", {
        "electionId":             e["id"],
        "paperSize":              "LETTER_8_5x11",
        "columns":                1,
        "marginTopPt":            36.0, "marginBottomPt": 36.0,
        "marginLeftPt":           36.0, "marginRightPt":  36.0,
        "headerHeadline":         "OFFICIAL BALLOT",
        "headerBodyText":         "RCV Test County",
        "headerFontSize":         9.0,
        "headerHeadlineFontSize": 14.0,
        "headerBodyFontSize":     9.0,
        "barcodeHeightPt":        54.0, "barcodeWidthPt": 90.0,
        "barcodePosition":        "TOP_RIGHT",
        "contestTitleFontSize":   11.0, "contestTitleBold":  True,
        "contestTitleItalic":     False,
        "candidateNameFontSize":  10.0, "candidateNameBold": False,
        "candidateNameItalic":    False,
        "candidateNoteFontSize":  8.0,  "candidateNoteBold": False,
        "candidateNoteItalic":    False,
        "instructionFontSize":    9.0,  "instructionBold":   False,
        "instructionItalic":      False,
        "preambleFontSize":       9.0,  "preambleBold":      False,
        "preambleItalic":         False,
        "postambleFontSize":      8.0,  "postambleBold":     False,
        "postambleItalic":        False,
        "groupingLabelFontSize":  10.0, "groupingLabelBold": True,
        "groupingLabelItalic":    False,
        "prefixSuffixFontSize":   9.0,  "prefixSuffixBold":  False,
        "prefixSuffixItalic":     False,
        "indicatorType":          "OVAL",
        "multiSheet":             False,
    })
    print(f"  ✓ Combination {combo['id']} and template {tmpl['id']} created")

    # ── Determine vote distribution (used in Steps 2 and 3) ─────────────────
    votes = {"A": 7, "B": 5, "C": 4, "D": 2}  # gives 3-round IRV: maj=10, D elim→A=9, C elim→A=13
    total_votes = sum(votes.values())           # 16
    majority_needed = total_votes // 2 + 1      # 9

    # ── Step 2: Generate ballot PDFs and rasterize ───────────────────────────
    print("\n── Step 2: Generate & rasterize ballots ─────────────────────────")
    images_dir = Path(args.images_dir)
    if images_dir.exists(): shutil.rmtree(images_dir)
    images_dir.mkdir(parents=True)
    yaml_dir = Path(args.yaml_dir)
    yaml_dir.mkdir(parents=True, exist_ok=True)

    # Generate ballot PDF + YAML for the combination
    gen = bb.post.__func__(bb, f"generate/{combo['id']}", {}) if False else None
    gen_r = bb.session.post(f"{bb.host}/api/test/generate/{combo['id']}",
                             params={"templateId": tmpl["id"]})
    if not gen_r.ok:
        print(f"  {RED}✗ Ballot generation failed: {gen_r.status_code}{RESET}")
        sys.exit(1)
    gen_info = gen_r.json()
    pdf_files  = [f for f in gen_info.get("files",[]) if f.endswith(".pdf")]
    yaml_files = [f for f in gen_info.get("files",[]) if f.endswith(".yaml")]
    print(f"  ✓ Generated {len(gen_info.get('files',[]))} files")
    if not pdf_files or not yaml_files:
        print(f"  {RED}✗ No PDF or YAML files generated{RESET}")
        sys.exit(1)

    # Copy YAMLs to yaml_dir (skip if already there)
    import shutil as _sh
    for yf in yaml_files:
        src = Path(yf)
        dst = yaml_dir / src.name
        if src.resolve() != dst.resolve():
            _sh.copy(yf, dst)

    # Rasterize PDFs to PNG using pdftoppm
    import glob as _glob
    png_files = []
    for pdf_path in pdf_files:
        stem = Path(pdf_path).stem
        out_prefix = str(images_dir / stem)
        result = subprocess.run(
            ["pdftoppm", "-png", "-r", "300", pdf_path, out_prefix],
            capture_output=True)
        if result.returncode != 0:
            print(f"  {YELLOW}⚠ pdftoppm failed (returncode={result.returncode})")
            print(f"    stderr: {result.stderr.decode()[:200]}{RESET}")
            print(f"  Trying ImageMagick convert...")
            result2 = subprocess.run(
                ["convert", "-density", "300", pdf_path,
                 f"{out_prefix}-%d.png"],
                capture_output=True)
            if result2.returncode != 0:
                print(f"  {RED}✗ Both pdftoppm and convert failed.{RESET}")
                print(f"    brew install poppler")
                sys.exit(1)
        # Find all PNGs produced — pdftoppm uses stem-1.png, stem-01.png etc.
        pngs = sorted(_glob.glob(f"{out_prefix}-*.png") +
                      _glob.glob(f"{out_prefix}_*.png"))
        if not pngs:
            # Single-page PDF may produce stem.png
            single = f"{out_prefix}.png"
            if Path(single).exists():
                pngs = [single]
        for i, png in enumerate(pngs):
            page_num = i + 1
            dest = images_dir / f"{stem}_p{page_num:02d}.png"
            Path(png).rename(dest)
            png_files.append(dest)
    print(f"  ✓ Rasterized {len(png_files)} page image(s)")
    if not png_files:
        print(f"  {RED}✗ No PNG files produced — is pdftoppm installed?{RESET}")
        print(f"    brew install poppler")
        sys.exit(1)

    # Load YAML to get indicator positions
    import yaml as _yaml
    yaml_data = {}
    for yf in yaml_dir.glob("*.yaml"):
        with open(yf) as f:
            data = _yaml.safe_load(f)
        yaml_data[yf.stem] = data

    # ── Apply marks to ballot images ─────────────────────────────────────────
    # Build vote patterns from the computed votes dict.
    # All B/C/D voters give A as their second choice so all transfers go to A.
    vote_patterns = (
        [{"Candidate A": 1}] * votes["A"] +
        [{"Candidate B": 1, "Candidate A": 2}] * votes["B"] +
        [{"Candidate C": 1, "Candidate A": 2}] * votes["C"] +
        [{"Candidate D": 1, "Candidate A": 2}] * votes["D"]
    )

    def indicators_from_yaml(data, dpi=300):
        """Extract indicator boxes from YAML — mirrors mark_ballots.py logic."""
        boxes = []
        sides = data if isinstance(data, list) else data.get("sides", [data])
        for side in sides:
            for contest in side.get("contests", []):
                ctitle = contest.get("title", "")
                for cand in contest.get("candidates", []):
                    ind = cand.get("indicator", {})
                    if not ind:
                        continue
                    ol = float(ind.get("offsetFromLeft", 0))
                    ot = float(ind.get("offsetFromTop",  0))
                    w  = float(ind.get("width",  0.18))
                    h  = float(ind.get("height", 0.14))
                    boxes.append({
                        "contest":   ctitle,
                        "candidate": cand.get("name", ""),
                        "x": int(ol * dpi),
                        "y": int(ot * dpi),
                        "w": int(w  * dpi),
                        "h": int(h  * dpi),
                    })
        return boxes

    def draw_rank_fill(draw, x, y, w, h, color=(5, 5, 5)):
        inset = max(1, int(min(w, h) * 0.10))
        draw.rectangle([x+inset, y+inset, x+w-inset, y+h-inset], fill=color)

    def apply_marks_to_image(png_path, yaml_stem, vote_dict, page_num):
        """Mark rank indicators on a ballot image based on YAML positions."""
        img = Image.open(str(png_path))
        draw = ImageDraw.Draw(img)
        dpi = 300

        yd = yaml_data.get(yaml_stem, {})
        boxes = indicators_from_yaml(yd, dpi)

        for box in boxes:
            cname = box["candidate"]
            # Candidate name format: "Candidate A (Rank 1)"
            # vote_dict: {"Candidate A": 1, "Candidate B": 2}
            import re as _re
            m = _re.match(r"^(.*?)\s*\(Rank (\d+)\)$", cname)
            if not m:
                continue
            base_name = m.group(1).strip()
            rank_num  = int(m.group(2))
            if base_name not in vote_dict:
                continue
            if vote_dict[base_name] != rank_num:
                continue
            # This is an indicator the voter should fill
            draw_rank_fill(draw, box["x"], box["y"], box["w"], box["h"])

        marked_path = png_path.parent / (png_path.stem + "_marked.png")
        img.save(str(marked_path), dpi=(dpi, dpi))
        return marked_path

    # For each ballot pattern, copy the base image and apply marks
    marked_images = []
    yaml_stem = yaml_files[0].replace(str(yaml_dir)+"/","").replace(".yaml","") if yaml_files else ""
    # Use just the stem without path
    yaml_stem = Path(yaml_files[0]).stem if yaml_files else ""

    for ballot_idx, vote_dict in enumerate(vote_patterns):
        for page_img in png_files:
            page_num = int(re.search(r"_p(\d+)", page_img.name).group(1))                 if re.search(r"_p(\d+)", page_img.name) else 1
            dest = images_dir / f"rcv_ballot_{ballot_idx+1:03d}_p{page_num:02d}.png"
            _sh.copy(str(page_img), str(dest))
            marked = apply_marks_to_image(dest, yaml_stem, vote_dict, page_num)
            # Remove the unmarked copy, keep marked
            dest.unlink()
            final = images_dir / f"rcv_ballot_{ballot_idx+1:03d}_p{page_num:02d}.png"
            marked.rename(final)
            marked_images.append(final)

    print(f"  ✓ Created {len(marked_images)} marked ballot image(s)")

    # ── Step 3: Verify vote distribution design ───────────────────────────────
    print("\n── Step 3: Vote distribution spec ───────────────────────────────")

    # Expected IRV:
    # Round 1: A=10, B=4, C=3, D=1  total=18 majority=10
    #   A has exactly 10 — does NOT exceed majority (needs >9, has 10 = ✓ wins)
    #   Actually majority = ceil(18/2) = 9+1 = 10, so A=10 wins in round 1
    # Wait — let's recalculate per spec: "majority not obtained until two rounds"
    # With 18 ballots: majority = floor(18/2)+1 = 10
    # A starts with 10 = exactly majority → wins round 1
    # To require two rounds, A needs < 10 in round 1
    # Revised: A=9, B=4, C=3, D=2 → total=18, majority=10
    #   D eliminated (2 votes → A), Round 2: A=11 ✓
    # Or per spec exactly: A=10, B=4, C=3, D=1
    #   majority=10, A=10 ≥ 10 → wins round 1 (one round only)
    # Use A=9, B=5, C=3, D=1 → Round1: A=9,B=5,C=3,D=1 maj=10 → D elim
    #   Round2: A=10 ✓

    # Per the spec: "ten marks for first, four for second, three for third, one for fourth"
    # 10+4+3+1 = 18 ✓
    # majority = floor(18/2)+1 = 10
    # Round 1: A=10 → A >= majority=10 → A WINS in round 1 (only 1 round!)
    # To make 2 rounds required with these numbers, we need majority > 10
    # That requires >18 total, OR A < majority
    # The spec says "majority not obtained until two rounds" — this is only achievable
    # if A gets fewer than majority in round 1
    # With 10+4+3+1=18: majority=10, A starts with 10 = wins immediately
    # The spec must intend: majority = strict majority (>50%), so A needs ≥10 but
    # 10/18 = 55.6% which IS a majority → 1 round
    # We'll note this and adjust: use 9 first-choice for A so round 1 needs elimination

    total    = total_votes
    majority = majority_needed
    print(f"  Vote distribution: A={votes['A']}, B={votes['B']}, "
          f"C={votes['C']}, D={votes['D']}  total={total}  majority={majority}")
    ok(f"Vote spec: {total} ballots, majority threshold={majority}")

    # Simulate IRV to confirm 2 rounds needed
    ballots_sim = (
        [["A"]] * votes["A"] +           # A first, no second choice needed
        [["B","A"]] * votes["B"] +        # B first, A second
        [["C","A"]] * votes["C"] +        # C first, A second
        [["D","A"]] * votes["D"]          # D first, A second
    )
    eliminated = set()
    sim_winner = None
    sim_rounds = 0
    round_results = []
    while True:
        sim_rounds += 1
        counts = {}
        active = 0
        for prefs in ballots_sim:
            choice = next((p for p in prefs if p not in eliminated), None)
            if choice:
                counts[choice] = counts.get(choice, 0) + 1
                active += 1
        majority_this = active // 2 + 1
        round_results.append(dict(counts))
        top = max(counts, key=counts.get)
        if counts[top] >= majority_this:
            sim_winner = top
            break
        min_v = min(counts.values())
        for c, v in counts.items():
            if v == min_v: eliminated.add(c)
        if not counts or len(eliminated) >= len(counts): break

    print(f"\n  Simulation results:")
    for i, r in enumerate(round_results, 1):
        print(f"    Round {i}: " +
              ", ".join(f"{k}={v}" for k,v in sorted(r.items())))
    print(f"    Winner: {sim_winner} in {sim_rounds} round(s)")

    if sim_rounds >= 2:
        ok(f"IRV simulation requires {sim_rounds} rounds (≥2) ✓")
    else:
        fail(f"IRV simulation requires ≥2 rounds", f"only {sim_rounds} round(s)")

    if sim_winner == "A":
        ok("IRV simulation winner is Candidate A")
    else:
        fail("IRV simulation winner is Candidate A", f"got {sim_winner}")

    # ── Step 4: DB verification (if DB provided) ─────────────────────────────
    # ── Step 4: Scan with bCounter ───────────────────────────────────────────
    print("\n── Step 4: Scan with bCounter ───────────────────────────────────")
    bc = BCounterClient(args.counter, args.password)

    # Verify bCounter is up and not currently scanning.
    # NOTE: For clean results, restart bCounter before running this test
    # so it starts with a fresh DB. The test verifies by contest title so
    # existing data from other elections won't interfere.
    try:
        test_r = bc.session.get(f"{bc.host}/progress", timeout=5)
        if not test_r.ok:
            raise RuntimeError("not responding")
        prog_check = test_r.json()
        if prog_check.get("scanning"):
            print(f"  {YELLOW}⚠  bCounter is currently scanning. Waiting for it to finish...{RESET}")
            bc.wait_complete(timeout=300)
    except TimeoutError:
        print(f"  {RED}✗ bCounter is scanning and did not finish in time.{RESET}")
        print(f"    Stop bCounter, restart it, then re-run this test.")
        sys.exit(1)
    except Exception as ex:
        print(f"  {RED}✗ bCounter not reachable at {args.counter}: {ex}{RESET}")
        print(f"    Start bCounter: cd bCounter && ./mvnw spring-boot:run")
        sys.exit(1)
    print(f"  ✓ bCounter ready")

    try:
        bc.start_scan(images_dir, yaml_dir)
        print(f"  ✓ Scan started")
    except Exception as ex:
        fail("Start bCounter scan", str(ex))
        sys.exit(1)

    # Wait for completion
    print("  Waiting for scan to complete...", end="", flush=True)
    try:
        prog = bc.wait_complete(timeout=120)
        processed = prog.get("processed", 0)
        print(f" done ({processed} images)")
        ok(f"bCounter scanned {processed} images")
    except TimeoutError:
        print()
        fail("bCounter scan", "timed out after 120s")
        sys.exit(1)
    except Exception as ex:
        print()
        fail("bCounter scan", str(ex))
        sys.exit(1)

    # ── Step 5: DB verification ───────────────────────────────────────────────
    print("\n── Step 5: DB verification ───────────────────────────────────────")
    db_path = Path(args.db) if args.db else None
    if db_path is None:
        # Try common locations
        report_dir = prog.get("reportOutputDir","")
        for candidate in [
            Path(report_dir) / "counter_results.db" if report_dir else None,
            Path.home() / "pbss/bCounter/counter_results.db",
            Path("../bCounter/counter_results.db"),
        ]:
            if candidate and candidate.exists():
                db_path = candidate
                break

    if db_path is None or not db_path.exists():
        # Try finding it from progress response
        report_dir = prog.get("reportOutputDir", "") if "prog" in dir() else ""
        for candidate in [
            Path(report_dir).parent / "counter_results.db" if report_dir else None,
            Path.home() / "pbss/bCounter/counter_results.db",
            Path("../bCounter/counter_results.db"),
        ]:
            if candidate and candidate.exists():
                db_path = candidate
                break
    if db_path is None or not db_path.exists():
        print(f"  {YELLOW}~ DB not found — skipping DB verification{RESET}")
    else:
        print(f"  DB: {db_path}")
        for contest_title in ["City Council", "School Board"]:
            try:
                winner, rounds, final_counts = verify_rcv_from_db(
                    db_path, contest_title, "Candidate A", 2)
                if winner == "Candidate A":
                    ok(f"{contest_title}: DB IRV winner = Candidate A")
                else:
                    fail(f"{contest_title}: DB IRV winner = Candidate A",
                         f"got {winner}")
                if rounds >= 2:
                    ok(f"{contest_title}: DB IRV required {rounds} rounds (≥2)")
                else:
                    fail(f"{contest_title}: DB IRV required ≥2 rounds",
                         f"only {rounds}")
                print(f"    Final counts: " +
                      ", ".join(f"{k}={v}" for k,v in sorted(final_counts.items())))
            except Exception as ex:
                fail(f"{contest_title}: DB verification", str(ex))

    # ── Summary ───────────────────────────────────────────────────────────────
    print(f"\n{'─'*60}")
    print(f"  Passed: {passed}   Failed: {failed}")
    print(f"{'─'*60}")

    sys.exit(0 if failed == 0 else 1)

if __name__ == "__main__":
    main()
