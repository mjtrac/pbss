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
COPIES=2
BUILDER_HOST="http://localhost:8080"
COUNTER_HOST="http://localhost:8081"
BUILDER_EXPORT_DIR="${HOME}/bBuilder_ballots"  # default bBuilder output dir (~/bBuilder_ballots)

# Parse args
while [[ $# -gt 0 ]]; do
  case $1 in
    --seed)    SEED="$2";         shift 2 ;;
    --copies)  COPIES="$2";       shift 2 ;;
    --builder)      BUILDER_HOST="$2";       shift 2 ;;
    --counter)      COUNTER_HOST="$2";       shift 2 ;;
    --builder-dir)  BUILDER_EXPORT_DIR="$2"; shift 2 ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

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
echo "║  seed=$SEED  copies=$COPIES                                ║"
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
"$PYTHON3" mark_ballots.py \
  --election-data election_data.json \
  --out-dir marked_ballots \
  --seed "$SEED"
echo ""

# ── Step 4: Apply distortions ─────────────────────────────────────────────────
echo "Step 4 — Applying geometric distortions"
rm -rf images
"$PYTHON3" distort_ballots.py \
  --in-dir  marked_ballots \
  --out-dir images \
  --copies  "$COPIES" \
  --gt-in   marked_ballots/ground_truth.json \
  --gt-out  images/ground_truth_all.json
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
  # Try the default bBuilder output directory first
  if [ -d "${HOME}/bBuilder_ballots" ] && ls "${HOME}/bBuilder_ballots"/*.yaml >/dev/null 2>&1; then
    YAML_DIR="${HOME}/bBuilder_ballots"
    echo "  YAML dir: $YAML_DIR (default bBuilder output)"
  # Then try the sibling bBuilder source directory (legacy/dev layout)
  elif [ -d "$(dirname "$0")/../bBuilder" ]; then
    YAML_DIR="$(cd "$(dirname "$0")/../bBuilder" && pwd)"
    echo "  ⚠  No YAMLs in ~/bBuilder_ballots — falling back to $YAML_DIR"
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

# Find counter_results.db
DB_PATH=""
if [[ -f "../bSuite/bCounter/counter_results.db" ]]; then
  DB_PATH="../bSuite/bCounter/counter_results.db"
elif [[ -f "../bCounter/counter_results.db" ]]; then
  DB_PATH="../bCounter/counter_results.db"
else
  echo "  ⚠  Could not find counter_results.db — specify with --db flag to verify_results.py"
  DB_PATH="counter_results.db"
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
