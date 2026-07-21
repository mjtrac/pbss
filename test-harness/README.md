# pbss Test Harness

Automated test harness for the full pbss pipeline:
bBuilder → PDF → PNG → marked → distorted → bCounter → verified.

For the **native desktop apps** (blCounter, counter, and friends) instead
of the web apps, see [README-desktop.md](README-desktop.md) — same
create/mark/distort/count/verify shape, but counting is driven through each
app's real GUI (TestFX/AssertJ-Swing) rather than a REST endpoint.

---

## Prerequisites

**Running apps:**
- bBuilder at `http://localhost:8080` (start via pbss.app or `./mvnw spring-boot:run` in bBuilder/)
  - Ballot PDFs and YAML layout files are written to `~/bBuilder_ballots/` by default
- bCounter at `http://localhost:8081` (same)
- bViewer at `http://localhost:8082` (runs automatically inside bCounter — no separate start needed)

**Python 3.9+:**
```bash
pip install -r requirements.txt
```

**System tool (for PDF rasterization):**
```bash
brew install poppler      # macOS
apt install poppler-utils # Linux
```

**Verify everything is in place:**
```bash
python3 check_setup.py
```

---

## Quick Start

With bBuilder and bCounter already running:

```bash
cd test-harness
./run_all.sh
```

Options:
```bash
./run_all.sh --seed 42 --copies 3
./run_all.sh --builder http://localhost:8080 --counter http://localhost:8081
./run_all.sh --builder-dir /custom/yaml/path   # override bBuilder output dir
./run_all.sh --copies 1                        # single copy, fastest run
./run_all.sh --reset --dpi 150                 # wipe bCounter's DB + writeins/scribbles/reports
                                                # first (prompts for confirmation)
./run_all.sh --use-counter-app                 # skip bCounter entirely — only needs
                                                # bBuilder running. Launches the `counter`
                                                # Swing app (foreground, folders pre-filled)
                                                # so you can watch it count interactively,
                                                # then launches `viewer` on the same database
                                                # once you close counter. See
                                                # "Want to just watch counter count?" below.
```

### Want to just watch `counter` count?

If bCounter keeps timing out (or you'd simply rather watch the Swing app
count in real time than drive the web version), use `--use-counter-app`.
It runs the exact same Steps 0–4 (build election, mark ballots, apply
distortions) — the only thing that changes is Step 6: instead of waiting
for and driving bCounter over HTTP, it launches `counter` in the
foreground with the ballot-images and ballot-templates folders already
pre-filled. Sign in (`admin` / `ChangeMe123!` — seeded automatically on a
fresh `counter_results.db` by `CounterDataInitializer`), confirm the
folders, click **Start Counting**, and watch it go. Close the window when
it's done and the script continues: `verify_results.py` runs exactly as
usual, then `viewer` launches on the same database so you can review the
ballots it just counted.

Only bBuilder needs to be running for this mode — no bCounter, no
`localhost:8081` at all. Even bBuilder is optional: Step 1 now only waits
10 attempts (~30s, not the old 3-minute wait) before falling back —
regardless of `--use-counter-app` — to creating the test election
directly via `builder`'s own repositories (`TestElectionBuilder`, no
bBuilder/HTTP involved at all). It's a smaller test election than
`build_election.py`'s full multi-precinct/multi-party one (see "What It
Does" below), but real: a genuine PDF+YAML pair, written into the same
`~/pbss_data/db/election_ballot.db` bBuilder itself would use, idempotent
across repeated runs.

---

## What It Does

### Step 1 — `build_election.py`
Calls the bBuilder REST API (`/api/test/*`) to create:
- 1 jurisdiction, 1 election
- 2 ballot types (Precinct, Mail-In)
- 2 parties (Nonpartisan, Progressive)
- 3 precincts + 2 precinct groups
- 8 contests covering all types:
  - Ranked Choice (President, 4 ranks, 6 candidates)
  - Plurality/1 (Congress, 6 candidates)
  - Plurality/1 with preamble (State Senate, P1+P2 only)
  - Plurality/3 multi-winner (City Council, 7 candidates)
  - Approval (Mayor, 4 candidates)
  - Plurality/2 (School Board, P3 only)
  - Measure Yes/No with full preamble+postamble (Measure A)
  - Advisory measure Yes/No (Measure B, P3 only)
- 6 ballot combinations (3 precincts × 2 parties) on 8.5×14 legal paper
- Generates PDF+YAML+XML for each combination (2 pages each = 12 layout files)

Generates PDF+YAML+XML for each combination (2 pages each = 12 layout files) and
writes them to `~/bBuilder_ballots/` (created automatically). Override via the
`ballot.export.dir` property in `bBuilder/src/main/resources/application.properties`.

Writes: `election_data.json`

### Step 2 — `mark_ballots.py`
Rasterizes each ballot PDF page to a 300 DPI PNG, then applies 8 voting
scenarios:

| Scenario | Description |
|---|---|
| `valid_all_filled` | Correct filled ovals |
| `valid_all_x` | X marks (valid variant) |
| `valid_all_check` | Checkmarks (valid variant) |
| `overvote_plurality` | Too many marks in plurality contests |
| `write_ins` | Write-in candidate names drawn |
| `messy_marks` | Marks overflowing indicator boundaries |
| `outside_marks` | Marks just outside the box (should not count) |
| `mostly_blank` | Mostly unmarked ballot |
| `scribble_arrow_no` | Arrow + "NO!" margin note pointing at an already-marked vote |
| `scribble_vote_for_this` | Hasty circle + "VOTE FOR THIS" note beside an unmarked candidate |
| `write_in_marked` | Write-in name written and the indicator oval filled in (valid vote) |
| `write_in_unmarked` | Write-in name written but the indicator oval left blank (not a valid vote) |

Writes: `marked_ballots/<precinct>/<ballot>__<scenario>.png`
Writes: `marked_ballots/ground_truth.json`

### Step 3 — `distort_ballots.py`
Applies 15 geometric distortions to every marked PNG:

| Distortion | Description |
|---|---|
| `clean` | No distortion (baseline) |
| `rot_cw_1` | Rotated 1° clockwise |
| `rot_cw_1_5` | Rotated 1.5° clockwise |
| `rot_ccw_1` | Rotated 1° counter-clockwise |
| `rot_ccw_1_5` | Rotated 1.5° counter-clockwise |
| `trans_up/down/left/right` | Translated ¼" in each direction |
| `rot_cw1_trans` | 1° CW + ¼" translation |
| `rot_ccw1_trans` | 1° CCW + ¼" translation |
| `skew_right` / `skew_left` | Affine shear |
| `perspective` | Mild perspective warp |
| `upside_down` | 180° rotation |

Each variant is duplicated `--copies` times (default 3).

**Expected image count:**
6 standard combos × 2 pages × 8 scenarios × 15 distortions × 3 copies = **4,320 images**

2 large-header combos (Precinct 1 only) × 2 pages × 8 scenarios × 15 distortions × 3 copies = **1,440 images**

Total with `--copies 3`: **5,760 images**

*(The `--copies` flag controls duplication. Use `--copies 1` for a quick run.)*

Writes: `images/<precinct>/<distortion_type>/<filename>.png`
Writes: `images/ground_truth_all.json` (per-image + aggregate counts)

### Step 4 — `run_counter.py`
Logs in to bCounter and starts a scan of the `images/` folder tree.
Polls `/progress` every 2 seconds, printing the current image path and counts.
Automatically resumes at 1,000-image milestones.

The YAML layout directory is resolved in this order:
1. `--yaml-dir` argument (if provided)
2. Paths from `election_data.json` (set by `build_election.py`)
3. `~/bBuilder_ballots/` (default bBuilder output)
4. `../bBuilder/` (fallback for dev layout)

### Step 5 — `verify_results.py`
Queries `counter_results.db` and compares vote counts to ground truth.
Prints a match/mismatch summary and writes `verify_report.json`.

---

## Output Files

| File | Location | Contents |
|---|---|---|
| `election_data.json` | `test-harness/` | IDs + generated file paths |
| `marked_ballots/ground_truth.json` | `test-harness/` | Per-image expected marks |
| `images/ground_truth_all.json` | `test-harness/` | Per-image + aggregate expected counts |
| `verify_report.json` | `test-harness/` | Match/mismatch diff after counting |
| `images/` | `test-harness/` | Full test image tree |
| `results_report.html` | `reports.output.dir` (default: bCounter working dir) | Printable vote totals; updated every 500 images |
| `overvote_report.txt` | `reports.output.dir` | List of overvoted ballot/contest pairs |
| `review_required.txt` | `reports.output.dir` | Images flagged for manual review |
| `vote_summary.yaml` | image folder | Structured vote totals by precinct/party |

---

## Resetting Between Runs

The `build_election.py` script calls `DELETE /api/test/reset` which wipes all
ballot data from bBuilder's database before recreating it. To also reset the
counter database:

```bash
rm ~/pbss/bCounter/counter_results.db
```

Or use the provided reset script:
```bash
./reset_scan.sh
```

Then run `./run_all.sh` again.

---

## Running Individual Steps

```bash
# Just build the election (bBuilder must be running)
python3 build_election.py

# Just mark ballots (requires election_data.json)
python3 mark_ballots.py --seed 42

# Just apply distortions (requires marked_ballots/)
python3 distort_ballots.py --copies 1

# Just run the counter (bCounter must be running)
python3 run_counter.py

# Just verify (requires counter_results.db and ground_truth_all.json)
python3 verify_results.py --db ~/pbss/bCounter/counter_results.db

# Tabulate ranked-choice contests (IRV)
python3 rcv_tabulate.py --db ../bCounter/counter_results.db
python3 rcv_tabulate.py --contest "President" --json

# Merge and compare results from multiple scan stations (GUI)
python3 db_merge.py
```
