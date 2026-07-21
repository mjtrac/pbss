#!/bin/bash
# run_all.sh — Full pbss test harness runner
#
# Prerequisites:
#   - bBuilder running at localhost:8080
#   - bCounter running at localhost:8081 (unless --use-counter-app — see below)
#   - Python 3.9+ with requirements installed
#
# Usage:
#   ./run_all.sh [--seed N] [--copies N] [--dpi 300] [--builder http://...] [--counter http://...] [--builder-dir /path]
#   ./run_all.sh --use-existing --ballot-pdf /path/ballot.pdf --ballot-yaml /path/ballot.yaml
#                [--quick] [--copies N] [--counter http://...]
#   ./run_all.sh --connect-dots  # use CONNECT_DOTS indicator style instead of OVAL
#   ./run_all.sh --reset --dpi 150  # also wipe bCounter's persisted DB + output
#                                   # dirs first (prompts for confirmation)
#   ./run_all.sh --use-counter-app  # skip waiting for bCounter entirely — instead
#                                   # launch the `counter` Swing app (in the
#                                   # foreground, folders pre-filled) so you can
#                                   # watch it count, then launch `viewer` on the
#                                   # same database once it's done. Only needs
#                                   # bBuilder running, not bCounter.
#   ./run_all.sh --desktop         # skip bBuilder entirely — build the (small,
#                                   # TestElectionBuilder-sized) test election by
#                                   # driving builder's real Swing UI via a real
#                                   # java.awt.Robot (DesktopElectionBuilder),
#                                   # instead of build_election.py's REST calls
#                                   # or TestElectionBuilder's direct repository
#                                   # calls. Pops real windows; idempotent on
#                                   # re-run (regenerates the existing election's
#                                   # PDF/YAML instead of re-driving the UI).
#
# Output:
#   election_data.json       — IDs of created entities + generated file paths
#   marked_ballots/          — PNGs with voter marks, ground_truth.json
#   {dpi}_images/            — Distorted copies (e.g. 150_images/ or images/ for 300dpi)
#   verify_report.json       — Diff of DB results vs ground truth
#   ~/pbss_data/reports/   — bCounter results report
#   ~/pbss_data/writeins/  — Write-in image crops

set -euo pipefail

SEED=42
COPIES=1
SCAN_DPI=300
IMAGES_DIR="images"   # overridden below if DPI != 300
QUICK=0
BUILDER_HOST="http://localhost:8080"
COUNTER_HOST="http://localhost:8081"
BUILDER_EXPORT_DIR=""   # derived from bBuilder application.properties below
USE_EXISTING=0
BALLOT_PDF=""
BALLOT_YAML=""
CONNECT_DOTS=0
RESET=0
USE_COUNTER_APP=0
DESKTOP=0

# Parse args
while [[ $# -gt 0 ]]; do
  case $1 in
    --seed)         SEED="$2";               shift 2 ;;
    --copies)       COPIES="$2";             shift 2 ;;
    --dpi)          SCAN_DPI="$2";           shift 2 ;;
    --builder)      BUILDER_HOST="$2";       shift 2 ;;
    --counter)      COUNTER_HOST="$2";       shift 2 ;;
    --builder-dir)  BUILDER_EXPORT_DIR="$2"; shift 2 ;;
    --quick)        QUICK=1;                 shift   ;;
    --use-existing)   USE_EXISTING=1;        shift   ;;
    --ballot-pdf)     BALLOT_PDF="$2";       shift 2 ;;
    --ballot-yaml)    BALLOT_YAML="$2";      shift 2 ;;
    --connect-dots)   CONNECT_DOTS=1;        shift   ;;
    --reset)          RESET=1;               shift   ;;
    --use-counter-app) USE_COUNTER_APP=1;    shift   ;;
    --desktop)        DESKTOP=1;             shift   ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

if [[ "$RESET" == "1" ]]; then
  echo "⚠️  WARNING: --reset will PERMANENTLY clear all bCounter election data:"
  echo "     - every vote record and matched ballot image in the current database"
  echo "     - ~/pbss_data/writeins/, ~/pbss_data/scribbles/, ~/pbss_data/reports/"
  echo ""
  read -r -p "Type 'yes' to proceed: " RESET_CONFIRM
  if [[ "$RESET_CONFIRM" != "yes" ]]; then
    echo "Aborted — no data was cleared."
    exit 1
  fi
  echo ""
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
BSUITE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── Read and resolve application.properties ───────────────────────────────────
# Handles ${user.home}, ${user.dir}, and chained ${other.key} references.
# Works with bash 3 (macOS default) — no associative arrays required.
# Usage: read_prop <file> <key>
read_prop() {
  local file="$1" key="$2"
  [[ -f "$file" ]] || { echo ""; return; }
  local escaped_key raw ref ref_key ref_val pass
  escaped_key=$(echo "$key" | sed 's/\./\\./g')
  raw=$(grep -E "^[[:space:]]*${escaped_key}[[:space:]]*=" "$file" \
        | tail -1 \
        | sed "s|^[[:space:]]*${escaped_key}[[:space:]]*=||")
  [[ -z "$raw" ]] && { echo ""; return; }
  raw="${raw//\$\{user.home\}/$HOME}"
  raw="${raw//\$\{user.dir\}/$(pwd)}"
  pass=0
  while [[ $pass -lt 5 ]] && echo "$raw" | grep -qF '${' ; do
    ref=$(echo "$raw" | grep -oE '\$\{[^}]+\}' | head -1)
    ref_key="${ref:2:${#ref}-3}"
    ref_val=$(read_prop "$file" "$ref_key")
    [[ -n "$ref_val" ]] && raw="${raw//$ref/$ref_val}" || break
    pass=$((pass + 1))
  done
  echo "$raw"
}

BCOUNTER_PROPS="$BSUITE_DIR/bCounter/src/main/resources/application.properties"
BBUILDER_PROPS="$BSUITE_DIR/bBuilder/src/main/resources/application.properties"

# ── Derive key paths from properties ─────────────────────────────────────────
PROP_BALLOT_DIR="$(read_prop "$BBUILDER_PROPS" "ballot.export.dir")"
PROP_DB_DIR="$(read_prop     "$BCOUNTER_PROPS" "data.db.dir")"
PROP_DB_PATH="${PROP_DB_DIR}/counter_results.db"

# If --builder-dir was not passed on command line, use the property value
if [[ -z "$BUILDER_EXPORT_DIR" ]]; then
  BUILDER_EXPORT_DIR="$PROP_BALLOT_DIR"
fi

# ── Sync check ────────────────────────────────────────────────────────────────
echo "── Checking property sync ────────────────────────────────────"
SYNC_OK=1

if [[ -z "$PROP_BALLOT_DIR" ]]; then
  echo "  ⚠  Could not read ballot.export.dir from $BBUILDER_PROPS"
  SYNC_OK=0
else
  echo "  bBuilder ballot.export.dir : $PROP_BALLOT_DIR"
fi

if [[ -z "$PROP_DB_DIR" ]]; then
  echo "  ⚠  Could not read data.db.dir from $BCOUNTER_PROPS"
  SYNC_OK=0
else
  echo "  bCounter data.db.dir : $PROP_DB_DIR"
fi

if [[ -n "$BUILDER_EXPORT_DIR" && "$BUILDER_EXPORT_DIR" != "$PROP_BALLOT_DIR" ]]; then
  echo "  ⚠  --builder-dir overrides property: using $BUILDER_EXPORT_DIR"
fi

if [[ "$SYNC_OK" == "0" ]]; then
  echo "  ✗ Could not verify property sync — continuing with fallbacks"
else
  echo "  ✓ Properties readable"
fi
echo ""

# Find python3 that has the required packages.
# Works on macOS, Linux, and Windows (Git Bash).
# We search candidate paths in priority order; the first one that can
# import all required packages wins.  Shell aliases are not available in
# non-interactive scripts, so we do not rely on them.
PYTHON3=""
for candidate in \
    /Library/Frameworks/Python.framework/Versions/3.12/bin/python3 \
    /Library/Frameworks/Python.framework/Versions/3.11/bin/python3 \
    /opt/homebrew/bin/python3 \
    /usr/local/bin/python3 \
    /usr/bin/python3 \
    python3 \
    python; do
  # Skip non-existent paths; resolve aliases via PATH last
  if [[ "$candidate" == /* ]]; then
    [[ -x "$candidate" ]] || continue
    RESOLVED="$candidate"
  else
    RESOLVED=$(command -v "$candidate" 2>/dev/null) || continue
  fi
  if "$RESOLVED" -c "import requests, yaml, cv2, pdf2image" 2>/dev/null; then
    PYTHON3="$RESOLVED"
    echo "  Using Python: $PYTHON3"
    break
  fi
done

if [[ -z "$PYTHON3" ]]; then
  echo "✗ Could not find a python3 with all required packages."
  echo ""
  echo "  Install missing packages with the Python that has the others:"
  echo "    python3 -m pip install Pillow PyYAML opencv-python-headless requests pdf2image"
  echo ""
  echo "  On macOS, if pip gives 'externally managed environment':"
  echo "    pip install --break-system-packages Pillow PyYAML opencv-python-headless requests pdf2image"
  exit 1
fi

echo "╔════════════════════════════════════════════════════════════╗"
echo "║           pbss Test Harness                              ║"
echo "╠════════════════════════════════════════════════════════════╣"
echo "║  seed=$SEED  copies=$COPIES  quick=$QUICK                  ║"
echo "║  bBuilder: $BUILDER_HOST"
if [[ "$USE_COUNTER_APP" == "1" ]]; then
echo "║  counter:  Swing app (--use-counter-app), no bCounter needed"
else
echo "║  bCounter: $COUNTER_HOST"
fi
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# ── Step 0: Check dependencies ────────────────────────────────────────────────
echo "Step 0 — Checking dependencies"
"$PYTHON3" check_setup.py
echo ""

# Set image directory name based on DPI
if [ "$SCAN_DPI" != "300" ]; then
  IMAGES_DIR="${SCAN_DPI}_images"
fi

# ── Step 1: Wait for bBuilder ────────────────────────────────────────────────
# Only 10 attempts (~30s), not the old 3-minute wait — if bBuilder genuinely
# isn't running, Step 2 below falls back to creating the test election
# directly via `builder`'s own repositories instead of failing outright.
echo "Step 1 — Waiting for bBuilder to be ready"
BUILDER_READY=0
BUILDER_ATTEMPTS=10
if [[ "$USE_EXISTING" == "1" ]]; then
  echo "  Skipped (--use-existing mode doesn't need bBuilder)"
elif [[ "$DESKTOP" == "1" ]]; then
  echo "  Skipped (--desktop builds the election via builder's own UI, not bBuilder)"
else
  for i in $(seq 1 "$BUILDER_ATTEMPTS"); do
    if curl -sf "$BUILDER_HOST/login" > /dev/null 2>&1; then
      echo "  ✓ bBuilder is ready"
      BUILDER_READY=1
      break
    fi
    echo "  ... waiting ($i/$BUILDER_ATTEMPTS)"
    sleep 3
  done
  if [[ "$BUILDER_READY" == "0" ]]; then
    echo ""
    echo "  ⚠  bBuilder never became ready at $BUILDER_HOST after $BUILDER_ATTEMPTS attempts."
    echo "     Falling back to creating the test election directly via builder"
    echo "     (no bBuilder/HTTP needed) — see Step 2."
  fi
fi

# ── Step 2: Build the test election (skipped in --use-existing mode) ─────────
echo ""
if [[ "$USE_EXISTING" == "1" ]]; then
  echo "Step 2 — Skipped (--use-existing mode)"
elif [[ "$DESKTOP" == "1" ]]; then
  echo "Step 2 — Building test election via builder's real UI (--desktop, DesktopElectionBuilder)"
  BUILDER_DIR="$BSUITE_DIR/builder"
  if [ ! -d "$BUILDER_DIR" ]; then
    echo "✗ builder/ not found at $BUILDER_DIR — cannot use --desktop here."
    exit 1
  fi
  echo "  Installing builder-core (if changed) ..."
  ( cd "$BSUITE_DIR/builder-core" && mvn -q install -DskipTests ) || {
    echo "✗ builder-core failed to install — see the Maven output above."
    exit 1
  }
  echo "  This pops real windows on your screen and drives them via a real"
  echo "  java.awt.Robot — see builder/README-testing.md's \"Known"
  echo "  environment-dependent behavior\" if it fails or hangs (macOS"
  echo "  Accessibility permission)."
  ELECTION_DATA_ABS="$(pwd)/election_data.json"
  ( cd "$BUILDER_DIR" && mvn -q test -Dtest=DesktopElectionBuilder \
      "-Dtest-election.out=$ELECTION_DATA_ABS" ) || {
    echo "✗ DesktopElectionBuilder exited with an error — see the Maven output above."
    exit 1
  }
elif [[ "$BUILDER_READY" == "1" ]]; then
  echo "Step 2 — Building test election in bBuilder"
  IND_TYPE="OVAL"
  [[ "$CONNECT_DOTS" == "1" ]] && IND_TYPE="CONNECT_DOTS"
  "$PYTHON3" build_election.py --host "$BUILDER_HOST" --out election_data.json \
    --indicator-type "$IND_TYPE"
else
  echo "Step 2 — Building test election via builder (bBuilder unreachable)"
  BUILDER_DIR="$BSUITE_DIR/builder"
  if [ ! -d "$BUILDER_DIR" ]; then
    echo "✗ builder/ not found at $BUILDER_DIR — cannot fall back. Start bBuilder and re-run:"
    echo "    cd $BSUITE_DIR/bBuilder && ./mvnw spring-boot:run"
    exit 1
  fi
  echo "  Installing builder-core (if changed) ..."
  ( cd "$BSUITE_DIR/builder-core" && mvn -q install -DskipTests ) || {
    echo "✗ builder-core failed to install — see the Maven output above."
    exit 1
  }
  # Captured here, before the subshell's own `cd` — $(pwd) evaluated inside
  # that subshell would resolve to $BUILDER_DIR instead of test-harness/.
  ELECTION_DATA_ABS="$(pwd)/election_data.json"
  ( cd "$BUILDER_DIR" && ./mvnw -q spring-boot:run \
      -Dspring-boot.run.main-class=com.mjtrac.builderui.TestElectionBuilder \
      -Dspring-boot.run.arguments="--test-election.out=$ELECTION_DATA_ABS" ) || {
    echo "✗ TestElectionBuilder exited with an error — see the Maven output above."
    exit 1
  }
fi
echo ""

# ── Step 3: Mark ballots ──────────────────────────────────────────────────────
echo "Step 3 — Marking ballot PNGs with voter scenarios"
rm -rf marked_ballots
MARK_EXTRA=""
DISTORT_EXTRA=""
if [[ "$QUICK" == "1" ]]; then
  MARK_EXTRA="--scenarios valid_all_filled"
  DISTORT_EXTRA="--distortions clean"
  COPIES=1
  echo "  ⚡ Quick mode: single scenario, clean distortion, 1 copy"
fi
if [[ "$USE_EXISTING" == "1" ]]; then
  # Existing ballot mode: use provided PDF+YAML, auto-generate scenarios
  if [[ -z "$BALLOT_PDF" || -z "$BALLOT_YAML" ]]; then
    echo "  ✗ --use-existing requires --ballot-pdf and --ballot-yaml"
    exit 1
  fi
  echo "  Using existing ballot:"
  echo "    PDF:  $BALLOT_PDF"
  echo "    YAML: $BALLOT_YAML"
  "$PYTHON3" mark_ballots.py \
    --ballot-pdf  "$BALLOT_PDF" \
    --ballot-yaml "$BALLOT_YAML" \
    --auto-scenario \
    --out-dir marked_ballots \
    --seed "$SEED" \
    --dpi "$SCAN_DPI" \
    $MARK_EXTRA
else
  "$PYTHON3" mark_ballots.py \
    --election-data election_data.json \
    --out-dir marked_ballots \
    --seed "$SEED" \
    --dpi "$SCAN_DPI" \
    $MARK_EXTRA
fi
echo ""

# ── Step 4: Apply distortions ─────────────────────────────────────────────────
echo "Step 4 — Applying geometric distortions"
rm -rf "$IMAGES_DIR"
"$PYTHON3" distort_ballots.py \
  --in-dir  marked_ballots \
  --out-dir "$IMAGES_DIR" \
  --copies  "$COPIES" \
  --dpi     "$SCAN_DPI" \
  --gt-in   marked_ballots/ground_truth.json \
  --gt-out  "$IMAGES_DIR/ground_truth_all.json" \
  $DISTORT_EXTRA
echo ""

# Count images
TOTAL=$(find "$IMAGES_DIR" -name "*.png" | wc -l | tr -d ' ')
echo "  Total images in tree: $TOTAL"
echo ""

# ── Step 5: Wait for bCounter (skipped entirely with --use-counter-app) ──────
if [[ "$USE_COUNTER_APP" == "0" ]]; then
  echo "Step 5 — Waiting for bCounter to be ready"
  COUNTER_READY=0
  for i in $(seq 1 60); do
    if curl -sf "$COUNTER_HOST/login" > /dev/null 2>&1; then
      echo "  ✓ bCounter is ready"
      COUNTER_READY=1
      break
    fi
    echo "  ... waiting ($i/60)"
    sleep 3
  done
  if [[ "$COUNTER_READY" == "0" ]]; then
    echo ""
    echo "✗ bCounter never became ready at $COUNTER_HOST after 3 minutes."
    echo "  Start it first, in its own terminal:"
    echo "    cd $BSUITE_DIR/bCounter && ./mvnw spring-boot:run"
    echo "  Then re-run this script once you see \"Started ... Application\"."
    echo ""
    echo "  Or skip bCounter entirely and watch the counter Swing app instead:"
    echo "    ./run_all.sh --use-counter-app"
    exit 1
  fi
else
  echo "Step 5 — Skipping bCounter wait (--use-counter-app)"
fi

# ── Step 6: Run counter ───────────────────────────────────────────────────────
echo ""
if [[ "$USE_COUNTER_APP" == "1" ]]; then
  echo "Step 6 — Running the counter Swing app on test image tree"
else
  echo "Step 6 — Running bCounter on test image tree"
fi

if [[ "$RESET" == "1" ]]; then
  echo ""
  echo "── Clearing bCounter output directories (--reset) ──────────────"
  DATA_ROOT="$(dirname "${PROP_DB_DIR:-$HOME/pbss_data/db}")"
  for dir in "$DATA_ROOT/writeins" "$DATA_ROOT/scribbles" "$DATA_ROOT/reports"; do
    if [ -d "$dir" ]; then
      rm -f "$dir"/*.png "$dir"/*.html "$dir"/*.txt "$dir"/*.csv "$dir"/*.yaml 2>/dev/null || true
      echo "  Cleared: $dir"
    fi
  done
  echo ""
fi

# Derive bBuilder export dir from election_data.json, with explicit fallback
YAML_DIR=""
if python3 -c "
import json, sys
with open('election_data.json') as f:
    d = json.load(f)
yamls = [y for c in d.get('combinations',[]) for y in c.get('yamlFiles',[])]
if yamls:
    import os; print(os.path.dirname(yamls[0]))
" 2>/dev/null > /tmp/yaml_dir.txt; then
  YAML_DIR=$(cat /tmp/yaml_dir.txt)
fi
if [ -z "$YAML_DIR" ] || [ ! -d "$YAML_DIR" ]; then
  # Use the value from bBuilder's application.properties
  if [ -n "$PROP_BALLOT_DIR" ] && [ -d "$PROP_BALLOT_DIR" ] && \
     ls "$PROP_BALLOT_DIR"/*.yaml >/dev/null 2>&1; then
    YAML_DIR="$PROP_BALLOT_DIR"
    echo "  YAML dir: $YAML_DIR (from ballot.export.dir)"
  # Then try the sibling bBuilder source directory (legacy/dev layout)
  elif [ -d "$(dirname "$0")/../bBuilder" ]; then
    YAML_DIR="$(cd "$(dirname "$0")/../bBuilder" && pwd)"
    echo "  ⚠  No YAMLs found — falling back to $YAML_DIR"
  else
    echo "  ✗ Could not locate YAML files. Run bBuilder Print first."
    exit 1
  fi
else
  echo "  YAML dir: $YAML_DIR"
fi

if [[ "$USE_COUNTER_APP" == "1" ]]; then
  IMAGES_ABS="$(pwd)/$IMAGES_DIR"
  COUNTER_DIR="$BSUITE_DIR/counter"
  if [ ! -d "$COUNTER_DIR" ]; then
    echo "✗ counter/ not found at $COUNTER_DIR — cannot use --use-counter-app here."
    exit 1
  fi

  echo "  Installing counter-core (if changed) ..."
  ( cd "$BSUITE_DIR/counter-core" && mvn -q install -DskipTests ) || {
    echo "✗ counter-core failed to install — see the Maven output above."
    exit 1
  }

  echo ""
  echo "── Launching counter ────────────────────────────────────────"
  echo "  Ballot images folder    : $IMAGES_ABS"
  echo "  Ballot templates folder : $YAML_DIR"
  echo ""
  echo "  Both fields above should already be pre-filled when the window"
  echo "  opens. Sign in (default: admin / ChangeMe123!), confirm the"
  echo "  folders, click \"Start Counting\", and watch it go."
  echo "  Close the counter window when it's done to continue this script."
  echo ""
  ( cd "$COUNTER_DIR" && ./mvnw -q spring-boot:run \
      -Dspring-boot.run.arguments="--scanner.default.image.dir=$IMAGES_ABS --scanner.default.report.dir=$YAML_DIR" ) || {
    echo "✗ counter exited with an error — see the Maven output above."
    exit 1
  }
  echo ""
else
  NEW_ELECTION_FLAG=""
  [[ "$RESET" == "1" ]] && NEW_ELECTION_FLAG="--new-election"

  "$PYTHON3" run_counter.py \
    --dpi "$SCAN_DPI" \
    --host          "$COUNTER_HOST" \
    --images        "$(pwd)/$IMAGES_DIR" \
    --yaml-dir      "$YAML_DIR" \
    --election-data election_data.json \
    $NEW_ELECTION_FLAG
  echo ""
fi

# ── Step 7: Verify results ────────────────────────────────────────────────────
echo "Step 7 — Verifying results against ground truth"

# Find counter_results.db — path derived from bCounter's data.database.dir property
DB_PATH=""
if [[ -n "$PROP_DB_PATH" && -f "$PROP_DB_PATH" ]]; then
  DB_PATH="$PROP_DB_PATH"
else
  echo "  ⚠  Could not find counter_results.db — will attempt: $PROP_DB_PATH"
  DB_PATH="$PROP_DB_PATH"
fi

"$PYTHON3" verify_results.py \
  --db  "$DB_PATH" \
  --gt  "$IMAGES_DIR/ground_truth_all.json" \
  --out verify_report.json

# ── Step 8: Review in viewer (only with --use-counter-app) ───────────────────
if [[ "$USE_COUNTER_APP" == "1" ]]; then
  VIEWER_DIR="$BSUITE_DIR/viewer"
  echo ""
  if [ -d "$VIEWER_DIR" ]; then
    echo "Step 8 — Launching viewer to review the ballots just counted"
    echo "  Sign in (admin / ChangeMe123!, or any VIEWER-role account)."
    echo "  Close the viewer window when you're done to finish this script."
    echo ""
    ( cd "$VIEWER_DIR" && ./mvnw -q spring-boot:run ) || \
      echo "  ⚠  viewer exited with an error — see the Maven output above (verify_report.json was still written)."
  else
    echo "  ⚠  viewer/ not found at $VIEWER_DIR — skipping Step 8."
  fi
fi

echo ""
echo "╔════════════════════════════════════════════════════════════╗"
echo "║  Test harness complete                                     ║"
echo "║                                                            ║"
if [[ "$USE_COUNTER_APP" == "1" ]]; then
echo "║  Full report:       verify_report.json                     ║"
else
echo "║  Review results at: http://localhost:8081/results          ║"
echo "║  View ballots at:   http://localhost:8082                  ║"
echo "║  Full report:       verify_report.json                     ║"
fi
echo "╚════════════════════════════════════════════════════════════╝"
