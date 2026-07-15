#!/bin/bash
# run_all.sh — Full pbss test harness runner
#
# Prerequisites:
#   - bBuilder running at localhost:8080
#   - bCounter running at localhost:8081
#   - Python 3.9+ with requirements installed
#
# Usage:
#   ./run_all.sh [--seed N] [--copies N] [--dpi 300] [--builder http://...] [--counter http://...] [--builder-dir /path]
#   ./run_all.sh --use-existing --ballot-pdf /path/ballot.pdf --ballot-yaml /path/ballot.yaml
#                [--quick] [--copies N] [--counter http://...]
#   ./run_all.sh --connect-dots  # use CONNECT_DOTS indicator style instead of OVAL
#   ./run_all.sh --reset --dpi 150  # also wipe bCounter's persisted DB + output
#                                   # dirs first (prompts for confirmation)
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
echo "║  bCounter: $COUNTER_HOST"
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
echo "Step 1 — Waiting for bBuilder to be ready"
for i in $(seq 1 60); do
  if curl -sf "$BUILDER_HOST/login" > /dev/null 2>&1; then
    echo "  ✓ bBuilder is ready"
    break
  fi
  echo "  ... waiting ($i/60)"
  sleep 3
done

# ── Step 2: Build election via API (skipped in --use-existing mode) ───────────
echo ""
if [[ "$USE_EXISTING" == "0" ]]; then
  echo "Step 2 — Building test election in bBuilder"
  IND_TYPE="OVAL"
  [[ "$CONNECT_DOTS" == "1" ]] && IND_TYPE="CONNECT_DOTS"
  "$PYTHON3" build_election.py --host "$BUILDER_HOST" --out election_data.json \
    --indicator-type "$IND_TYPE"
else
  echo "Step 2 — Skipped (--use-existing mode)"
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

# ── Step 5: Wait for bCounter ────────────────────────────────────────────────
echo "Step 5 — Waiting for bCounter to be ready"
for i in $(seq 1 60); do
  if curl -sf "$COUNTER_HOST/login" > /dev/null 2>&1; then
    echo "  ✓ bCounter is ready"
    break
  fi
  echo "  ... waiting ($i/60)"
  sleep 3
done

# ── Step 6: Run counter ───────────────────────────────────────────────────────
echo ""
echo "Step 6 — Running bCounter on test image tree"

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

echo ""
echo "╔════════════════════════════════════════════════════════════╗"
echo "║  Test harness complete                                     ║"
echo "║                                                            ║"
echo "║  Review results at: http://localhost:8081/results          ║"
echo "║  View ballots at:   http://localhost:8082                  ║"
echo "║  Full report:       verify_report.json                     ║"
echo "╚════════════════════════════════════════════════════════════╝"
