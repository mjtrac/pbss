#!/bin/bash
# run_all.sh — Full bSuite test harness runner
#
# Prerequisites:
#   - bBuilder running at localhost:8080
#   - bCounter running at localhost:8081
#   - Python 3.9+ with requirements installed
#
# Usage:
#   ./run_all.sh [--seed N] [--copies N] [--builder http://...] [--counter http://...] [--builder-dir /path]
#
# Output:
#   election_data.json    — IDs of created entities + generated file paths
#   marked_ballots/       — PNGs with voter marks, ground_truth.json
#   images/               — Distorted copies in folder tree, ground_truth_all.json
#   verify_report.json    — Diff of DB results vs ground truth

set -euo pipefail

SEED=42
COPIES=1
QUICK=0
BUILDER_HOST="http://localhost:8080"
COUNTER_HOST="http://localhost:8081"
BUILDER_EXPORT_DIR=""   # derived from bBuilder application.properties below

# Parse args
while [[ $# -gt 0 ]]; do
  case $1 in
    --seed)         SEED="$2";               shift 2 ;;
    --copies)       COPIES="$2";             shift 2 ;;
    --builder)      BUILDER_HOST="$2";       shift 2 ;;
    --counter)      COUNTER_HOST="$2";       shift 2 ;;
    --builder-dir)  BUILDER_EXPORT_DIR="$2"; shift 2 ;;
    --quick)        QUICK=1;                 shift   ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

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
PROP_DB_DIR="$(read_prop     "$BCOUNTER_PROPS" "data.database.dir")"
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
  echo "  ⚠  Could not read data.database.dir from $BCOUNTER_PROPS"
  SYNC_OK=0
else
  echo "  bCounter data.database.dir : $PROP_DB_DIR"
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
echo "║           bSuite Test Harness                              ║"
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

# ── Step 2: Build election via API ────────────────────────────────────────────
echo ""
echo "Step 2 — Building test election in bBuilder"
"$PYTHON3" build_election.py --host "$BUILDER_HOST" --out election_data.json
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
  echo "  ⚡ Quick mode: single scenario (valid_all_filled), clean distortion, 1 copy"
fi
"$PYTHON3" mark_ballots.py \
  --election-data election_data.json \
  --out-dir marked_ballots \
  --seed "$SEED" \
  $MARK_EXTRA
echo ""

# ── Step 4: Apply distortions ─────────────────────────────────────────────────
echo "Step 4 — Applying geometric distortions"
rm -rf images
"$PYTHON3" distort_ballots.py \
  --in-dir  marked_ballots \
  --out-dir images \
  --copies  "$COPIES" \
  --gt-in   marked_ballots/ground_truth.json \
  --gt-out  images/ground_truth_all.json \
  $DISTORT_EXTRA
echo ""

# Count images
TOTAL=$(find images -name "*.png" | wc -l | tr -d ' ')
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
  # Legacy fallback: old ~/bBuilder_ballots location
  elif [ -d "${HOME}/bBuilder_ballots" ] && ls "${HOME}/bBuilder_ballots"/*.yaml >/dev/null 2>&1; then
    YAML_DIR="${HOME}/bBuilder_ballots"
    echo "  ⚠  YAML dir: $YAML_DIR (legacy location — update ballot.export.dir?)"
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

"$PYTHON3" run_counter.py \
  --host          "$COUNTER_HOST" \
  --images        "$(pwd)/images" \
  --yaml-dir      "$YAML_DIR" \
  --election-data election_data.json
echo ""

# ── Step 7: Verify results ────────────────────────────────────────────────────
echo "Step 7 — Verifying results against ground truth"

# Find counter_results.db — path derived from bCounter's data.database.dir property
DB_PATH=""
if [[ -n "$PROP_DB_PATH" && -f "$PROP_DB_PATH" ]]; then
  DB_PATH="$PROP_DB_PATH"
elif [[ -f "../bSuite/bCounter/counter_results.db" ]]; then
  DB_PATH="../bSuite/bCounter/counter_results.db"
  echo "  ⚠  Using legacy DB location: $DB_PATH"
elif [[ -f "../bCounter/counter_results.db" ]]; then
  DB_PATH="../bCounter/counter_results.db"
  echo "  ⚠  Using legacy DB location: $DB_PATH"
else
  echo "  ⚠  Could not find counter_results.db — will attempt: $PROP_DB_PATH"
  DB_PATH="$PROP_DB_PATH"
fi

"$PYTHON3" verify_results.py \
  --db  "$DB_PATH" \
  --gt  images/ground_truth_all.json \
  --out verify_report.json

echo ""
echo "╔════════════════════════════════════════════════════════════╗"
echo "║  Test harness complete                                     ║"
echo "║                                                            ║"
echo "║  Review results at: http://localhost:8081/results          ║"
echo "║  View ballots at:   http://localhost:8082                  ║"
echo "║  Full report:       verify_report.json                     ║"
echo "╚════════════════════════════════════════════════════════════╝"
