#!/bin/bash
# run_desktop_gui_pipeline.sh — GUI-automation counterpart to run_all.sh, for
# the native desktop apps (blCounter, counter) instead of the web apps
# (bBuilder/bCounter). See README-desktop.md for the full explanation.
#
# Unlike run_all.sh, this never talks to a live bBuilder/bCounter over HTTP —
# ballot generation reuses an already-exported real ballot PDF+YAML
# (~/bBuilder_ballots by default) entirely offline, and counting is driven
# through each app's actual GUI (TestFX for blCounter, AssertJ-Swing for
# counter) rather than a REST endpoint. Nothing here ever touches
# ~/pbss_data — every isolated result lands under desktop_pipeline/.
#
# Usage:
#   ./run_desktop_gui_pipeline.sh                # full pipeline, both apps
#   ./run_desktop_gui_pipeline.sh --skip-corpus   # reuse an existing corpus
#   ./run_desktop_gui_pipeline.sh --dpi 150 --ballot ballot_1_1_1_1_1_1

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PBSS_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PIPELINE_DIR="$SCRIPT_DIR/desktop_pipeline"
BALLOT_SOURCE_DIR="${HOME}/bBuilder_ballots"
BALLOT="ballot_1_1_1_1_1_1"
DPI=150
SKIP_CORPUS=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-corpus) SKIP_CORPUS=true; shift ;;
    --dpi) DPI="$2"; shift 2 ;;
    --ballot) BALLOT="$2"; shift 2 ;;
    --ballot-source-dir) BALLOT_SOURCE_DIR="$2"; shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

# Same resolution as run_all.sh: non-interactive scripts don't get shell
# aliases, so a bare `python3` may resolve to a stub lacking pyyaml/etc.
PYTHON3=""
for candidate in \
    /Library/Frameworks/Python.framework/Versions/3.12/bin/python3 \
    /Library/Frameworks/Python.framework/Versions/3.11/bin/python3 \
    /opt/homebrew/bin/python3 \
    /usr/local/bin/python3 \
    /usr/bin/python3 \
    python3 \
    python; do
  if [[ "$candidate" == /* ]]; then
    [[ -x "$candidate" ]] || continue
    RESOLVED="$candidate"
  else
    RESOLVED=$(command -v "$candidate" 2>/dev/null) || continue
  fi
  if "$RESOLVED" -c "import yaml, numpy, PIL" 2>/dev/null; then
    PYTHON3="$RESOLVED"
    break
  fi
done
if [[ -z "$PYTHON3" ]]; then
  echo "No python3 with pyyaml/numpy/Pillow found — pip install -r requirements.txt"
  exit 1
fi
echo "Using Python: $PYTHON3"

if [[ "$SKIP_CORPUS" == false ]]; then
  if [[ ! -f "$BALLOT_SOURCE_DIR/$BALLOT.pdf" || ! -f "$BALLOT_SOURCE_DIR/$BALLOT.yaml" ]]; then
    echo "Ballot PDF/YAML not found: $BALLOT_SOURCE_DIR/$BALLOT.{pdf,yaml}"
    echo "Generate one with bBuilder first (or pass --ballot-source-dir), then re-run."
    exit 1
  fi

  echo "== Step 1: mark ballots (offline, real ballot: $BALLOT @ ${DPI} DPI) =="
  rm -rf "$PIPELINE_DIR/marked_ballots" "$PIPELINE_DIR/images"
  "$PYTHON3" "$SCRIPT_DIR/mark_ballots.py" \
    --ballot-pdf "$BALLOT_SOURCE_DIR/$BALLOT.pdf" \
    --ballot-yaml "$BALLOT_SOURCE_DIR/$BALLOT.yaml" \
    --out-dir "$PIPELINE_DIR/marked_ballots" \
    --dpi "$DPI" --seed 42

  echo "== Step 2: distort ballots =="
  "$PYTHON3" "$SCRIPT_DIR/distort_ballots.py" \
    --in-dir "$PIPELINE_DIR/marked_ballots" \
    --out-dir "$PIPELINE_DIR/images" \
    --gt-in "$PIPELINE_DIR/marked_ballots/ground_truth.json" \
    --gt-out "$PIPELINE_DIR/images/ground_truth_all.json" \
    --dpi "$DPI" --copies 1
else
  echo "== Skipping corpus generation (--skip-corpus) =="
fi

echo "== Step 3: drive blCounter's real Counting screen via TestFX =="
rm -rf "$PIPELINE_DIR/blcounter_results"
( cd "$PBSS_ROOT/blCounter" && ./mvnw -q test -Dtest=CountingPipelineGuiTest )

echo "== Step 4: verify blCounter's results against ground truth =="
"$PYTHON3" "$SCRIPT_DIR/verify_results.py" \
  --db "$PIPELINE_DIR/blcounter_results/counter_results.db" \
  --gt "$PIPELINE_DIR/images/ground_truth_all.json" \
  --out "$PIPELINE_DIR/blcounter_results/verify_report.json" || true

echo "== Step 5: drive counter's real MainFrame via AssertJ-Swing =="
rm -rf "$PIPELINE_DIR/counter_results"
( cd "$PBSS_ROOT/counter" && ./mvnw -q test -Dtest=CountingPipelineGuiTest )

# A schema-only DB file gets created by Spring/Hibernate as soon as the
# context starts, even if the test then skips itself before scanning
# anything (e.g. missing Accessibility permission — see README-desktop.md)
# — so check for actual rows, not just file existence.
COUNTER_DB="$PIPELINE_DIR/counter_results/counter_results.db"
if [[ -f "$COUNTER_DB" ]] && [[ "$("$PYTHON3" -c "
import sqlite3
try:
    print(sqlite3.connect('$COUNTER_DB').execute('select count(*) from ballot_image').fetchone()[0])
except Exception:
    print(0)
")" != "0" ]]; then
  echo "== Step 6: verify counter's results against ground truth =="
  "$PYTHON3" "$SCRIPT_DIR/verify_results.py" \
    --db "$COUNTER_DB" \
    --gt "$PIPELINE_DIR/images/ground_truth_all.json" \
    --out "$PIPELINE_DIR/counter_results/verify_report.json" || true
else
  echo "== Step 6: skipped — counter's GUI test didn't scan anything"
  echo "   (likely skipped itself; see its own JUnit output above, and"
  echo "   README-desktop.md's 'macOS Accessibility permission' section)"
fi

echo "== Done. See $PIPELINE_DIR/*/verify_report.json for full comparisons. =="
