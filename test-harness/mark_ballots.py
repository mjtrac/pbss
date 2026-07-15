#!/usr/bin/env python3
"""
mark_ballots.py — Convert ballot PDFs to PNGs and draw voter marks on them.

Reads election_data.json (from build_election.py) and for each combination:
  1. Rasterizes each page of the ballot PDF to a 300-DPI PNG
  2. Reads the corresponding YAML layout file
  3. For each scenario in the scenario table, draws marks on indicator boxes
  4. Writes marked PNGs to an output folder
  5. Records ground truth (what was marked and how) to ground_truth.json

Mark types:
  filled_oval  — filled ellipse (the "correct" way)
  x_mark       — two diagonal lines (X)
  check        — checkmark shape
  partial      — small mark not fully inside the box (tests edge detection)
  outside      — mark centered just outside the box boundary
  messy        — large irregular blob extending beyond the box
"""

import argparse, json, math, random, sys
from pathlib import Path

import numpy as np
import yaml
from PIL import Image, ImageDraw, ImageFont

# ── Args ──────────────────────────────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--election-data", default="election_data.json")
    p.add_argument("--out-dir",       default="marked_ballots")
    p.add_argument("--seed",          type=int, default=42)
    p.add_argument("--dpi",           type=int, default=300)
    p.add_argument("--scenarios",     default=None,
                   help="Comma-separated list of scenario names to run (default: all)")
    p.add_argument("--style-config",  default="indicator_style.yaml",
                   help="YAML file specifying indicator_style: oval|arrow")
    p.add_argument("--auto-scenario", action="store_true",
                   help="Generate voting scenarios automatically from the YAML "
                        "rather than using the hardcoded SCENARIOS table. "
                        "Works with any ballot, not just the test election.")
    p.add_argument("--ballot-pdf",    default=None,
                   help="Path to an existing bBuilder ballot PDF to use directly "
                        "(skips election_data.json; pair with --ballot-yaml)")
    p.add_argument("--ballot-yaml",   default=None,
                   help="Path to the YAML layout file matching --ballot-pdf")
    return p.parse_args()

def load_indicator_style(config_path: str) -> str:
    """Read indicator_style from the config file. Defaults to 'oval' if absent."""
    p = Path(config_path)
    if not p.exists():
        print(f"  ⚠  Style config not found: {config_path} -- defaulting to oval")
        return "oval"
    try:
        with open(p) as f:
            cfg = yaml.safe_load(f)
        style = str(cfg.get("indicator_style", "oval")).lower()
        if style not in ("oval", "arrow"):
            print(f"  ⚠  Unknown indicator_style '{style}' -- defaulting to oval")
            return "oval"
        return style
    except Exception as e:
        print(f"  ⚠  Could not read style config: {e} -- defaulting to oval")
        return "oval"

# ── PDF → PNG ─────────────────────────────────────────────────────────────────

def pdf_to_pngs(pdf_path: str, dpi: int) -> list[Image.Image]:
    """Rasterize every page of a PDF to PIL Images at the given DPI."""
    try:
        from pdf2image import convert_from_path
        pages = convert_from_path(pdf_path, dpi=dpi)
        return pages
    except Exception as e:
        raise RuntimeError(f"Could not rasterize {pdf_path}: {e}\n"
                           "Install pdf2image and poppler: pip install pdf2image && "
                           "brew install poppler")

# ── YAML loading ──────────────────────────────────────────────────────────────

def load_yaml(yaml_path: str) -> dict:
    with open(yaml_path) as f:
        return yaml.safe_load(f)

def indicators_from_yaml(data: dict, dpi: int) -> list[dict]:
    """
    Extract all indicator boxes from a single-page YAML (side_number list).
    Returns list of dicts with keys: contest, candidate, x, y, w, h (pixels),
    contestType, maxVotes, writeIn.
    """
    boxes = []
    sides = data if isinstance(data, list) else data.get("sides", [data])
    for side in sides:
        content_area = side.get("ballotContentArea", {})
        ca_left = float(content_area.get("offsetFromLeft", 0))
        ca_top  = float(content_area.get("offsetFromTop",  0))
        for contest in side.get("contests", []):
            ctype    = contest.get("contestType", "PLURALITY")
            maxvotes = int(contest.get("maxVotes", 1))
            ctitle   = contest.get("title", "")
            for cand in contest.get("candidates", []):
                # YAML key is "indicator" (singular), not "indicators"
                ind = cand.get("indicator", {})
                if not ind:
                    continue
                ol = float(ind.get("offsetFromLeft", 0))
                ot = float(ind.get("offsetFromTop",  0))
                w  = float(ind.get("width",  0.18))
                h  = float(ind.get("height", 0.14))
                # indicator offsets in YAML are page-absolute inches
                # indicatorStyle from YAML overrides the global style for ranked-choice
                ind_style = str(ind.get("indicatorStyle", "")).upper()
                boxes.append({
                    "contest":        ctitle,
                    "candidate":      cand.get("name", ""),
                    "contestType":    ctype,
                    "maxVotes":       maxvotes,
                    "writeIn":        cand.get("writeIn", False),
                    "indicatorStyle": ind_style,  # "OVAL","CHECKBOX","ARROW","" etc.
                    "x": int(ol * dpi),
                    "y": int(ot * dpi),
                    "w": int(w  * dpi),
                    "h": int(h  * dpi),
                })
    return boxes

# ── Drawing primitives ────────────────────────────────────────────────────────

def draw_filled_oval(draw: ImageDraw.ImageDraw, x, y, w, h, color=(5,5,5)):
    """Dark filled ellipse -- the correct voter mark.
    Uses near-black and minimal inset so dark pixel percentage
    survives bilinear interpolation blending in the counter pipeline."""
    inset = max(1, int(min(w, h) * 0.05))
    draw.ellipse([x+inset, y+inset, x+w-inset, y+h-inset], fill=color)

def draw_x_mark(draw: ImageDraw.ImageDraw, x, y, w, h, color=(20,20,20)):
    """Two crossing diagonal lines — use 30% of min dimension for line width
    to ensure >7% pixel coverage survives homography warp."""
    lw = max(3, int(min(w,h)*0.30))
    draw.line([x+2, y+2, x+w-2, y+h-2], fill=color, width=lw)
    draw.line([x+w-2, y+2, x+2, y+h-2], fill=color, width=lw)

def draw_check(draw: ImageDraw.ImageDraw, x, y, w, h, color=(20,20,20)):
    """Simple checkmark shape — use 30% of min dimension for line width
    to ensure >7% pixel coverage survives homography warp."""
    lw = max(3, int(min(w,h)*0.30))
    mid_x = x + int(w * 0.35)
    bot_y = y + h - 2
    draw.line([x+2, y + int(h*0.55), mid_x, bot_y], fill=color, width=lw)
    draw.line([mid_x, bot_y, x+w-2, y+2], fill=color, width=lw)

def draw_partial(draw: ImageDraw.ImageDraw, x, y, w, h, color=(30,30,30)):
    """Mark that partially fills the box — tests threshold sensitivity.
    Uses a 15% inset (reduced from 30%) to ensure enough pixels are drawn
    to survive the homography warp and still be detectable."""
    inset = int(min(w,h) * 0.15)
    draw.ellipse([x+inset, y+inset, x+w-inset, y+h-inset], fill=color)

def draw_rank_fill(draw: ImageDraw.ImageDraw, x, y, w, h, color=(5,5,5)):
    """Fill a rank box solidly — voter fills the box to indicate their rank choice.
    Uses the same dark fill as draw_filled_oval but rectangular to match rank box shape."""
    inset = max(1, int(min(w, h) * 0.10))
    draw.rectangle([x+inset, y+inset, x+w-inset, y+h-inset], fill=color)


def draw_outside(draw: ImageDraw.ImageDraw, x, y, w, h, rng, color=(30,30,30)):
    """Mark placed just outside the box — should NOT be detected as a vote."""
    offset_x = w + rng.randint(2, 8)
    draw.ellipse([x+offset_x, y+2, x+offset_x+w, y+h-2], fill=color)

def draw_messy(draw: ImageDraw.ImageDraw, x, y, w, h, color=(20,20,20)):
    """Large blob overflowing the indicator — still should count as voted."""
    overflow = int(min(w,h) * 0.4)
    draw.ellipse([x-overflow, y-overflow, x+w+overflow, y+h+overflow], fill=color)

def draw_arrow_vote(draw: ImageDraw.ImageDraw, x, y, w, h, color=(5,5,5)):
    """Fill the central zone of an arrow indicator (between the two triangles).
    The analyser detects any dark pixel in a zone 1/4 the box size centred on the box.
    We draw a filled rectangle in that exact zone to simulate a voter marking there."""
    cx, cy   = x + w // 2, y + h // 2
    half_w   = max(1, w // 8)   # 1/8 of box width each side of centre
    half_h   = max(1, h // 8)   # 1/8 of box height each side of centre
    draw.rectangle([cx - half_w, cy - half_h, cx + half_w, cy + half_h], fill=color)

def draw_write_in_text(draw: ImageDraw.ImageDraw, x, y, w, h, name: str,
                       color=(20,20,20), mark: bool = True):
    """Draw handwritten-style text in a write-in region.
    Writes the candidate name on the write-in line below the indicator row.
    bBuilder draws the write-in line centered in the column, roughly one full
    row-height below the indicator.

    mark=True (default) also fills the oval, matching a voter who wrote a name
    AND filled it in — a valid write-in vote. mark=False leaves the oval blank,
    matching a voter who wrote a name but forgot to mark it — not a valid vote,
    but real handwriting a scanner still has to cope with.
    """
    if mark:
        # Fill the oval so bCounter's darkPct threshold is met
        draw_x_mark(draw, x, y, w, h, color)
    # The write-in line is centered in the column, one row below the indicator.
    # Row height ≈ indicator height * 3 (candidateFontSize + OVAL_HEIGHT + gap).
    # We write the name centered below the indicator at that offset.
    try:
        font = ImageFont.load_default(size=max(16, h // 2))
    except Exception:
        try:
            font = ImageFont.load_default()
        except Exception:
            font = None
    row_gap = int(h * 3.0)     # one row-height below indicator
    text_y  = y + row_gap      # on the write-in line
    text_x  = x - w * 2       # start a bit left of the indicator to appear centered
    draw.text((text_x, text_y), name, fill=color, font=font)


# ── Scribbles / margin annotations ─────────────────────────────────────────────
# Extraneous ink a real voter sometimes adds — arrows, circled notes, exclamations
# — that isn't part of any indicator and shouldn't be counted as a vote, but is
# exactly what ScribbleDetectionService's inter-ballot comparison is meant to catch.

def draw_arrow(draw: ImageDraw.ImageDraw, from_xy, to_xy,
               color=(15,15,15), width=5, head_len=22):
    """Straight shaft with a simple V-shaped arrowhead at to_xy."""
    fx, fy = from_xy
    tx, ty = to_xy
    draw.line([fx, fy, tx, ty], fill=color, width=width)
    angle = math.atan2(ty - fy, tx - fx)
    for da in (0.45, -0.45):
        hx = tx - head_len * math.cos(angle - da)
        hy = ty - head_len * math.sin(angle - da)
        draw.line([tx, ty, hx, hy], fill=color, width=width)

def draw_annotation_text(draw: ImageDraw.ImageDraw, x, y, text,
                         color=(15,15,15), font_size=32):
    """Write a short margin note at an absolute page position, sized roughly
    like natural handwriting rather than a full-size ballot font."""
    try:
        font = ImageFont.load_default(size=font_size)
    except Exception:
        font = ImageFont.load_default()
    draw.text((x, y), text, fill=color, font=font)

def draw_scribble_circle(draw: ImageDraw.ImageDraw, x, y, w, h, color=(15,15,15)):
    """A hasty circled note beside an indicator — not the indicator itself,
    so it never registers as a vote for that candidate."""
    draw.ellipse([x, y, x + w, y + h], outline=color, width=4)

def apply_scribble(draw: ImageDraw.ImageDraw, boxes: list[dict], spec: dict):
    """Draw a margin annotation anchored to one indicator box, without
    affecting that box's own vote/ground-truth status — the ink lands
    outside the indicator bounds, same as a real voter's stray mark.
    """
    box = next((b for b in boxes
                if b["contest"] == spec["contest"] and b["candidate"] == spec["candidate"]),
               None)
    if box is None:
        return
    x, y, w, h = box["x"], box["y"], box["w"], box["h"]
    kind = spec["type"]
    if kind == "arrow_no":
        # Arrow starts up and to the left of the box and points at it;
        # "NO!" sits just past the arrow's tail.
        tail = (x - int(w * 2.2), y - int(h * 1.6))
        head = (x - int(w * 0.3), y + int(h * 0.3))
        draw_arrow(draw, tail, head, width=max(3, w // 10))
        draw_annotation_text(draw, tail[0] - int(w * 0.8), tail[1] - int(h * 1.4),
                             "NO!", font_size=max(24, int(h * 0.9)))
    elif kind == "vote_for_this":
        # A small hasty circle in the row gap directly below the box, plus
        # the note beside it. The candidate name sits immediately to the
        # right of the oval, so annotating there would overlap the print;
        # row spacing is roughly 4-5x box height, so keep this annotation's
        # footprint under ~2.5x h to clear the next candidate row.
        circle_d = max(int(h * 1.6), 12)
        mark_x   = x + int(w * 0.15)
        mark_y   = y + h + int(h * 0.6)
        draw_scribble_circle(draw, mark_x, mark_y, circle_d, circle_d)
        font_size = max(12, min(18, int(h * 1.6)))
        draw_annotation_text(draw, mark_x + circle_d + 8, mark_y - int(h * 0.1),
                             "VOTE FOR THIS", font_size=font_size)


# ── Auto-scenario generation ──────────────────────────────────────────────────

def auto_scenarios_from_boxes(boxes: list[dict]) -> dict[str, dict]:
    """
    Generate a set of voting scenarios automatically from indicator boxes
    read from a YAML file.  Works with any pbss ballot regardless of
    contest titles or candidate names.

    Scenarios generated:
      auto_valid       — one valid vote per contest (first eligible candidate)
      auto_overvote    — two votes in every plurality/approval contest
      auto_abstain     — no votes anywhere
      auto_write_in    — fill every write-in oval (if present)
      auto_rcv_top2    — vote rank-1 and rank-2 in RCV contests, first candidate elsewhere

    Returns a dict of {scenario_name: {contest_title: {candidate_name: action}}}
    suitable for passing to apply_scenario().
    """
    # Group boxes by contest
    from collections import defaultdict
    contests: dict[str, dict] = {}
    for box in boxes:
        ct = box["contest"]
        if ct not in contests:
            contests[ct] = {
                "type":      box["contestType"],
                "maxVotes":  box["maxVotes"],
                "candidates": [],
            }
        contests[ct]["candidates"].append(box)

    valid:    dict = {}
    overvote: dict = {}
    abstain:  dict = {}
    write_in: dict = {}
    rcv_top2: dict = {}

    for title, info in contests.items():
        cands    = info["candidates"]
        ctype    = info["type"].upper()
        maxvotes = info["maxVotes"]
        is_rcv   = (ctype == "RANKED_CHOICE")

        # Separate write-in and regular candidates
        regulars  = [c for c in cands if not c["writeIn"]
                     and "(Rank" not in c["candidate"]]
        write_ins = [c for c in cands if c["writeIn"]]
        # For RCV, get rank-1 candidates (closest to candidate name)
        rank1s    = [c for c in cands if "(Rank 1)" in c["candidate"]]
        rank2s    = [c for c in cands if "(Rank 2)" in c["candidate"]]

        # ── auto_valid ───────────────────────────────────────────────
        if is_rcv:
            # Vote rank-1 for the first candidate
            if rank1s:
                valid.setdefault(title, {})[rank1s[0]["candidate"]] = "rank:1"
        else:
            # Vote for first regular candidate (or "Yes" if present)
            yes_cands = [c for c in regulars if c["candidate"].lower() == "yes"]
            first = yes_cands[0] if yes_cands else (regulars[0] if regulars else None)
            if first:
                valid.setdefault(title, {})[first["candidate"]] = "vote"

        # ── auto_overvote ────────────────────────────────────────────
        if not is_rcv and len(regulars) >= 2:
            # Vote for first two candidates (overvote in vote-for-one contests)
            if maxvotes == 1:
                overvote.setdefault(title, {})[regulars[0]["candidate"]] = "vote"
                overvote.setdefault(title, {})[regulars[1]["candidate"]] = "vote"
            else:
                # Already allows multiple — just vote for first
                overvote.setdefault(title, {})[regulars[0]["candidate"]] = "vote"

        # ── auto_write_in ────────────────────────────────────────────
        if write_ins:
            write_in.setdefault(title, {})[write_ins[0]["candidate"]] =                 "write_in:Test Candidate"

        # ── auto_rcv_top2 ────────────────────────────────────────────
        if is_rcv:
            if rank1s:
                rcv_top2.setdefault(title, {})[rank1s[0]["candidate"]] = "rank:1"
            if rank2s:
                rcv_top2.setdefault(title, {})[rank2s[0]["candidate"]] = "rank:2"
        else:
            # Non-RCV: vote for first candidate
            if regulars:
                rcv_top2.setdefault(title, {})[regulars[0]["candidate"]] = "vote"

        # ── auto_abstain ─────────────────────────────────────────────
        # No actions — all boxes left unmarked (empty dict per contest)
        abstain.setdefault(title, {})

    return {
        "auto_valid":    valid,
        "auto_overvote": overvote,
        "auto_abstain":  abstain,
        "auto_write_in": write_in    if write_in else {},
        "auto_rcv_top2": rcv_top2,
    }


# ── Scenario engine ───────────────────────────────────────────────────────────

# For each contest type, define how votes are assigned in different scenarios.
# scenario_id → { contestTitle → action }
# action: "vote", "overvote", "abstain", "x_mark", "check", "partial",
#         "outside", "messy", "write_in:<name>"

SCENARIOS = {
    # Scenario 0: straightforward valid votes (all contests, filled ovals)
    "valid_all_filled": {
        "President of the United States": {"Alexandria Washington (Rank 1)": "rank:1",
                                            "Benjamin Adams (Rank 2)":       "rank:2",
                                            "Carolina Jefferson (Rank 3)":   "rank:3",
                                            "Douglas Madison (Rank 4)":      "rank:4",
                                            "Eleanor Monroe (Rank 5)":       "rank:5"},
        "Representative in Congress":     {"Alice Smith":           "vote"},
        "State Senator":                  {"Patricia Chen":         "vote"},
        "City Council Member":            {"Anna Park": "vote", "Brian Foster": "vote",
                                           "Carmen Lopez": "vote"},
        "Mayor":                          {"Bill de Blasio": "vote",
                                           "Zohran Mamdani": "vote"},
        "School Board Director":          {"Grace Lee": "vote", "Henry Brown": "vote"},
        "Measure A — Infrastructure Bond":{"Yes": "vote"},
        "Measure B — Advisory Vote on Library Hours": {"Yes": "vote"},
        "County Assessor":                {"Linda Park": "vote"},
        "County Treasurer":               {"Sarah Hoffman": "vote"},
        "District Attorney":              {"Rachel Kim": "vote"},
        "Superior Court Judge, Seat 3":   {"Yes": "vote"},
        "Water District Board Member":    {"Chen Wei": "vote", "Barbara Scott": "vote",
                                           "Ahmed Hassan": "vote"},
        "Measure C — Local Road Tax":     {"Yes": "vote"},
        "County Executive":               {"Victoria Chang": "vote", "Samuel Obi": "vote"},
    },
    # Scenario 1: all X marks (not filled ovals)
    "valid_all_x": {
        "President of the United States": {"Benjamin Adams (Rank 1)":        "rank:1",
                                            "Alexandria Washington (Rank 2)": "rank:2",
                                            "Carolina Jefferson (Rank 3)":   "rank:3",
                                            "Douglas Madison (Rank 4)":      "rank:4",
                                            "Eleanor Monroe (Rank 5)":       "rank:5"},
        "Representative in Congress":     {"Bill Jones":            "x_mark"},
        "State Senator":                  {"Robert Williams":       "x_mark"},
        "City Council Member":            {"David Kim": "x_mark", "Eva Patel": "x_mark",
                                           "Frank Nguyen": "x_mark"},
        "Mayor":                          {"Eric Adams": "x_mark"},
        "School Board Director":          {"Iris Martinez": "x_mark",
                                           "James Wilson": "x_mark"},
        "Measure A — Infrastructure Bond":{"No": "x_mark"},
        "Measure B — Advisory Vote on Library Hours": {"No": "x_mark"},
    },
    # Scenario 2: checkmarks
    "valid_all_check": {
        "President of the United States": {"Benjamin Adams (Rank 1)":        "rank:1",
                                            "Alexandria Washington (Rank 2)": "rank:2",
                                            "Carolina Jefferson (Rank 3)":   "rank:3",
                                            "Douglas Madison (Rank 4)":      "rank:4",
                                            "Eleanor Monroe (Rank 5)":       "rank:5"},
        "Representative in Congress":     {"Chuck Edwards":    "check"},
        "State Senator":                  {"Sandra Okafor":    "check"},
        "City Council Member":            {"Anna Park": "check", "Carmen Lopez": "check",
                                           "Eva Patel": "check"},
        "Mayor":                          {"Nina Turner": "check"},
        "School Board Director":          {"Grace Lee": "check"},
        "Measure A — Infrastructure Bond":{"Yes": "check"},
        "Measure B — Advisory Vote on Library Hours": {"Yes": "check"},
    },
    # Scenario 3: overvotes in plurality contests
    "overvote_plurality": {
        "President of the United States": {"Alexandria Washington (Rank 1)": "rank:1",
                                            "Benjamin Adams (Rank 1)": "rank:1"},  # rank 1 marked twice = overvote
        "Representative in Congress":     {"Alice Smith": "vote", "Bill Jones": "vote",
                                           "Chuck Edwards": "vote"},  # max=1, so overvote
        "State Senator":                  {"Patricia Chen": "vote",
                                           "Robert Williams": "vote"},  # max=1, overvote
        "City Council Member":            {"Anna Park": "vote", "Brian Foster": "vote",
                                           "Carmen Lopez": "vote", "David Kim": "vote"},
                                           # max=3, David Kim makes 4 = overvote
        "Mayor":                          {"Bill de Blasio": "vote",
                                           "Eric Adams": "vote"},  # approval, no overvote
        "Measure A — Infrastructure Bond":{"Yes": "vote", "No": "vote"},  # overvote
    },
    # Scenario 4: write-in votes
    "write_ins": {
        "President of the United States": {"Write-In (Rank 1)": "write_in:Mickey Mouse"},
        "Representative in Congress":     {"Write-In": "write_in:Donald Duck"},
        "State Senator":                  {"Patricia Chen": "vote"},
        "City Council Member":            {"Write-In": "write_in:Jane Doe",
                                           "Anna Park": "vote", "Brian Foster": "vote"},
        "Mayor":                          {"Eric Adams": "vote"},
        "School Board Director":          {"Write-In": "write_in:John Smith"},
        "Measure A — Infrastructure Bond":{"Yes": "vote"},
    },
    # Scenario 5: marks partially outside boxes
    "messy_marks": {
        "President of the United States": {"Alexandria Washington (Rank 1)": "rank:1",
                                            "Benjamin Adams (Rank 2)":       "rank:2",
                                            "Carolina Jefferson (Rank 3)":   "rank:3"},
        "Representative in Congress":     {"Dorothy Johnson":       "messy"},
        "State Senator":                  {"Robert Williams":       "partial"},
        "City Council Member":            {"Anna Park": "messy", "Brian Foster": "vote",
                                           "Carmen Lopez": "partial"},
        "Mayor":                          {"Bill de Blasio": "messy"},
        "School Board Director":          {"Grace Lee": "partial"},
        "Measure A — Infrastructure Bond":{"Yes": "messy"},
    },
    # Scenario 6: marks outside the bounding box (should not count)
    "outside_marks": {
        "President of the United States": {"Alexandria Washington (Rank 1)": "outside"},
        "Representative in Congress":     {"Alice Smith": "outside"},
        "State Senator":                  {"Patricia Chen": "outside"},
        "City Council Member":            {"Anna Park": "outside"},
        "Mayor":                          {"Bill de Blasio": "vote"},  # at least one valid
        "School Board Director":          {"Grace Lee": "outside"},
        "Measure A — Infrastructure Bond":{"Yes": "vote"},
    },
    # Scenario 7: mostly blank ballot
    "mostly_blank": {
        "Mayor": {"Bill de Blasio": "vote"},
        "Measure A — Infrastructure Bond": {"Yes": "vote"},
    },
    # Scenario 8: margin scribble — arrow pointing at an already-marked vote,
    # annotated "NO!". The vote itself still counts; the arrow+text is stray
    # ink outside the indicator that ScribbleDetectionService should flag.
    "scribble_arrow_no": {
        "President of the United States": {"Alexandria Washington (Rank 1)": "rank:1",
                                            "Benjamin Adams (Rank 2)":       "rank:2",
                                            "Carolina Jefferson (Rank 3)":   "rank:3"},
        "Mayor":                          {"Bill de Blasio": "vote"},
        "Measure A — Infrastructure Bond":{"Yes": "vote"},
        "__scribble__": {"contest": "Mayor", "candidate": "Bill de Blasio",
                         "type": "arrow_no"},
    },
    # Scenario 9: margin scribble — a hasty circled note beside an unmarked
    # candidate reading "VOTE FOR THIS". The circle sits next to, not on, the
    # indicator, so the candidate stays unvoted; only the stray ink should
    # be flagged.
    "scribble_vote_for_this": {
        "President of the United States": {"Alexandria Washington (Rank 1)": "rank:1",
                                            "Benjamin Adams (Rank 2)":       "rank:2",
                                            "Carolina Jefferson (Rank 3)":   "rank:3"},
        "Mayor":                          {"Bill de Blasio": "vote"},
        "Measure A — Infrastructure Bond":{"Yes": "vote"},
        "__scribble__": {"contest": "City Council Member", "candidate": "David Kim",
                         "type": "vote_for_this"},
    },
    # Scenario 10: write-in with the indicator oval marked — a valid write-in vote.
    "write_in_marked": {
        "President of the United States": {"Write-In (Rank 1)":               "write_in:Mickey Mouse",
                                            "Alexandria Washington (Rank 2)":  "rank:2",
                                            "Benjamin Adams (Rank 3)":         "rank:3"},
        "Mayor":                          {"Bill de Blasio": "vote"},
        "Measure A — Infrastructure Bond":{"Yes": "vote"},
    },
    # Scenario 11: write-in text with the indicator oval left blank — a voter
    # wrote a name but never filled in the oval. Not a valid vote, but real
    # handwriting a scanner still has to tolerate without misreading it as one.
    "write_in_unmarked": {
        "President of the United States": {"Write-In (Rank 1)":               "write_in_unmarked:Daffy Duck",
                                            "Alexandria Washington (Rank 2)":  "rank:2",
                                            "Benjamin Adams (Rank 3)":         "rank:3"},
        "Mayor":                          {"Bill de Blasio": "vote"},
        "Measure A — Infrastructure Bond":{"Yes": "vote"},
    },

    # ── RCV split scenarios ──────────────────────────────────────────────────
    # Three equal-copy scenarios giving different rank-1 preferences for the
    # President ranked-choice contest.  With equal copies:
    #   Round 1: Benjamin leads, Alexandria second, Carolina last (fewest rank-1)
    #   Carolina eliminated; her ballots have Alexandria at Rank 2 → transfer
    #   Round 2: Alexandria gains Carolina's votes → majority → WINNER
    "rcv_alex_first": {
        # Alexandria = Rank 1 (35% of copies)
        "President of the United States": {"Alexandria Washington (Rank 1)": "rank:1",
                                            "Benjamin Adams (Rank 2)":       "rank:2"},
        "Representative in Congress":     {"Chuck Edwards":  "vote"},
        "State Senator":                  {"Sandra Okafor":  "vote"},
        "City Council Member":            {"Anna Park":      "vote",
                                           "Brian Foster":   "vote",
                                           "Carmen Lopez":   "vote"},
        "School Board Member":            {"Patricia Chen":  "vote"},
        "Mayor":                          {"Bill de Blasio": "vote"},
        "Measure A — Infrastructure Bond":{"Yes":            "vote"},
    },
    "rcv_ben_first": {
        # Benjamin = Rank 1, Alexandria = Rank 2 (45% of copies)
        "President of the United States": {"Benjamin Adams (Rank 1)":        "rank:1",
                                            "Alexandria Washington (Rank 2)": "rank:2"},
        "Representative in Congress":     {"Chuck Edwards":  "vote"},
        "State Senator":                  {"Sandra Okafor":  "vote"},
        "City Council Member":            {"Anna Park":      "vote",
                                           "Brian Foster":   "vote",
                                           "Carmen Lopez":   "vote"},
        "School Board Member":            {"Patricia Chen":  "vote"},
        "Mayor":                          {"Bill de Blasio": "vote"},
        "Measure A — Infrastructure Bond":{"Yes":            "vote"},
    },
    "rcv_carol_first": {
        # Carolina = Rank 1, Alexandria = Rank 2 (20% of copies)
        # Carolina is eliminated in round 1; ballots transfer to Alexandria
        "President of the United States": {"Carolina Jefferson (Rank 1)":    "rank:1",
                                            "Alexandria Washington (Rank 2)": "rank:2"},
        "Representative in Congress":     {"Chuck Edwards":  "vote"},
        "State Senator":                  {"Sandra Okafor":  "vote"},
        "City Council Member":            {"Anna Park":      "vote",
                                           "Brian Foster":   "vote",
                                           "Carmen Lopez":   "vote"},
        "School Board Member":            {"Patricia Chen":  "vote"},
        "Mayor":                          {"Bill de Blasio": "vote"},
        "Measure A — Infrastructure Bond":{"Yes":            "vote"},
    },
}

def apply_scenario(draw: ImageDraw.ImageDraw, boxes: list[dict],
                   scenario: dict, rng: random.Random,
                   global_style: str = "oval") -> list[dict]:
    """
    Apply a scenario to indicator boxes. Returns ground truth list of
    {contest, candidate, action, x, y, w, h}.
    """
    truth = []
    for box in boxes:
        contest = box["contest"]
        cand    = box["candidate"]
        action  = scenario.get(contest, {}).get(cand)
        if not action:
            truth.append({**box, "action": "unmarked", "counted_as": "UNMARKED"})
            continue

        x, y, w, h = box["x"], box["y"], box["w"], box["h"]

        # Determine effective drawing style for this indicator:
        # - Ranked-choice always uses oval (box marks are drawn as ovals/fills)
        # - ARROW style uses the central-zone fill
        # - All others use the global style
        ind_style_raw = box.get("indicatorStyle", "").upper()
        use_arrow = (ind_style_raw == "ARROW") or                     (global_style == "arrow" and ind_style_raw not in ("OVAL","CHECKBOX","NUMBER_FIELD"))

        if action == "vote":
            if use_arrow:
                draw_arrow_vote(draw, x, y, w, h)
            else:
                draw_filled_oval(draw, x, y, w, h)
        elif action == "x_mark":
            draw_x_mark(draw, x, y, w, h)
        elif action == "check":
            draw_check(draw, x, y, w, h)
        elif action == "partial":
            draw_partial(draw, x, y, w, h)
        elif action == "outside":
            draw_outside(draw, x, y, w, h, rng)
        elif action == "messy":
            draw_messy(draw, x, y, w, h)
        elif action.startswith("write_in:"):
            name = action.split(":", 1)[1]
            draw_write_in_text(draw, x, y, w, h, name)
        elif action.startswith("write_in_unmarked:"):
            name = action.split(":", 1)[1]
            draw_write_in_text(draw, x, y, w, h, name, mark=False)
        elif action.startswith("rank:"):
            draw_rank_fill(draw, x, y, w, h)
        unmarked = action == "outside" or action.startswith("write_in_unmarked:")
        truth.append({**box, "action": action,
                      "counted_as": "UNMARKED" if unmarked else "VOTED"})

    scribble_spec = scenario.get("__scribble__")
    if scribble_spec:
        apply_scribble(draw, boxes, scribble_spec)

    return truth

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    args = parse_args()
    rng  = random.Random(args.seed)
    out  = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)

    global_style = load_indicator_style(args.style_config)
    print(f"Indicator style: {global_style}")

    all_ground_truth = {}

    # ── Existing ballot mode ───────────────────────────────────────────────────
    # If --ballot-pdf and --ballot-yaml are provided, use those directly instead
    # of reading election_data.json.  Useful for testing ballots printed by bBuilder
    # without running build_election.py first.
    if args.ballot_pdf or args.ballot_yaml:
        if not (args.ballot_pdf and args.ballot_yaml):
            print("ERROR: --ballot-pdf and --ballot-yaml must both be provided.")
            sys.exit(1)
        pdf_path  = args.ballot_pdf
        yaml_path = args.ballot_yaml
        if not Path(pdf_path).exists():
            print(f"ERROR: PDF not found: {pdf_path}"); sys.exit(1)
        if not Path(yaml_path).exists():
            print(f"ERROR: YAML not found: {yaml_path}"); sys.exit(1)

        print(f"\n── Existing ballot mode ─────────────────────────────────────")
        print(f"  PDF:  {pdf_path}")
        print(f"  YAML: {yaml_path}")

        pages = pdf_to_pngs(pdf_path, args.dpi)
        if not pages:
            print("ERROR: No pages rasterized from PDF"); sys.exit(1)
        src_image = pages[0]

        yaml_data = load_yaml(yaml_path)
        boxes     = indicators_from_yaml(yaml_data, args.dpi)
        print(f"  ✓ {len(boxes)} indicators found in YAML")

        stem    = Path(pdf_path).stem
        # Choose scenario table
        if args.auto_scenario:
            scenario_table = auto_scenarios_from_boxes(boxes)
            print(f"  ✓ Auto-generated {len(scenario_table)} scenarios from YAML")
        else:
            scenario_table = SCENARIOS

        allowed = set(args.scenarios.split(",")) if args.scenarios else None
        for scenario_name, scenario in scenario_table.items():
            if allowed and scenario_name not in allowed:
                continue
            img  = src_image.copy().convert("RGB")
            draw = ImageDraw.Draw(img)
            truth = apply_scenario(draw, boxes, scenario, rng, global_style)
            fname = f"{stem}__{scenario_name}.png"
            fpath = out / fname
            fpath.parent.mkdir(parents=True, exist_ok=True)
            img.save(str(fpath), dpi=(args.dpi, args.dpi))
            voted = sum(1 for t in truth if t["counted_as"] == "VOTED")
            print(f"    ✓ {fname}  ({voted}/{len(truth)} marked)")
            all_ground_truth[str(fpath)] = {
                "combo_id":    "existing",
                "precinct":    "existing",
                "party":       "existing",
                "scenario":    scenario_name,
                "yaml_source": yaml_path,
                "indicators":  truth,
            }
        gt_path = out / "ground_truth.json"
        with open(gt_path, "w") as f:
            json.dump(all_ground_truth, f, indent=2)
        print(f"\n── Ground truth written to {gt_path}")
        return

    # ── Standard election_data.json mode ──────────────────────────────────────
    with open(args.election_data) as f:
        election = json.load(f)

    for combo in election["combinations"]:
        combo_id = combo["combinationId"]
        precinct = combo["precinct"]
        party    = combo["party"]

        print(f"\n── Combo {combo_id}: {precinct} / {party} ─────────────")

        yaml_files = combo.get("yamlFiles", [])
        pdf_files  = combo.get("pdfFiles",  [])

        if not yaml_files or not pdf_files:
            print(f"  ⚠  No yaml or pdf files recorded — skipping")
            continue

        for yaml_path, pdf_path in zip(yaml_files, pdf_files):
            if not Path(yaml_path).exists():
                print(f"  ⚠  YAML not found: {yaml_path}")
                continue
            if not Path(pdf_path).exists():
                print(f"  ⚠  PDF not found: {pdf_path}")
                continue

            # Rasterize PDF page to PIL image
            pages = pdf_to_pngs(pdf_path, args.dpi)
            if not pages:
                print(f"  ⚠  No pages rasterized from {pdf_path}")
                continue
            src_image = pages[0]  # one page per PDF now

            # Load YAML indicator layout
            yaml_data = load_yaml(yaml_path)
            boxes = indicators_from_yaml(yaml_data, args.dpi)
            if not boxes:
                print(f"  ⚠  No indicator boxes found in {yaml_path}")
                continue
            print(f"  ✓ {Path(yaml_path).name}: {len(boxes)} indicators")

            # Choose scenario table: auto-generated or hardcoded
            if args.auto_scenario:
                scenario_table = auto_scenarios_from_boxes(boxes)
                print(f"  ✓ Auto-generated {len(scenario_table)} scenarios from YAML")
            else:
                scenario_table = SCENARIOS

            # Apply each scenario
            stem = Path(pdf_path).stem
            allowed = set(args.scenarios.split(",")) if args.scenarios else None
            for scenario_name, scenario in scenario_table.items():
                if allowed and scenario_name not in allowed:
                    continue
                img  = src_image.copy().convert("RGB")
                draw = ImageDraw.Draw(img)

                truth = apply_scenario(draw, boxes, scenario, rng, global_style)

                fname = f"{stem}__{scenario_name}.png"
                fpath = out / precinct.replace(" ", "_") / fname
                fpath.parent.mkdir(parents=True, exist_ok=True)
                img.save(str(fpath), dpi=(args.dpi, args.dpi))

                all_ground_truth[str(fpath)] = {
                    "combo_id":    combo_id,
                    "precinct":    precinct,
                    "party":       party,
                    "scenario":    scenario_name,
                    "yaml_source": yaml_path,
                    "indicators":  truth,
                }
                voted = sum(1 for t in truth if t["counted_as"] == "VOTED")
                print(f"    ✓ {fname}  ({voted}/{len(truth)} marked)")

    # Write ground truth
    gt_path = out / "ground_truth.json"
    with open(gt_path, "w") as f:
        json.dump(all_ground_truth, f, indent=2)
    print(f"\n── Ground truth written to {gt_path}")
    print(f"   Total images: {len(all_ground_truth)}")

if __name__ == "__main__":
    main()
