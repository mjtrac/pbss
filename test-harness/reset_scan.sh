#!/bin/bash
# reset_scan.sh — Reset bCounter database and restore image filenames
# Run from any directory; paths are relative to bSuite root.
#
# Usage:
#   ./reset_scan.sh                          # use default images dir
#   ./reset_scan.sh --images /custom/path    # use custom images dir

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BSUITE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BCOUNTER_DIR="$BSUITE_DIR/bCounter"
IMAGES_DIR="$BSUITE_DIR/test-harness/images"  # default

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --images) IMAGES_DIR="$2"; shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

echo "── Reset bCounter scan state ─────────────────────────────────"
echo "   bSuite:  $BSUITE_DIR"
echo "   DB dir:  $BCOUNTER_DIR"
echo "   Images:  $IMAGES_DIR"
echo ""

# 1. Remove SQLite database files
echo "Step 1 — Removing counter database..."
removed=0
for f in "$BCOUNTER_DIR/counter_results.db" \
          "$BCOUNTER_DIR/counter_results.db-shm" \
          "$BCOUNTER_DIR/counter_results.db-wal"; do
    if [ -f "$f" ]; then
        rm -f "$f"
        echo "  Removed: $(basename $f)"
        removed=$((removed + 1))
    fi
done
[ $removed -eq 0 ] && echo "  (no database files found)"

# 2. Remove adjusted YAML files from image tree
echo ""
echo "Step 2 — Removing adjusted YAML files..."
if [ -d "$IMAGES_DIR" ]; then
    adj_count=$(find "$IMAGES_DIR" -name "*_adjusted.yaml" | wc -l | tr -d ' ')
    if [ "$adj_count" -gt 0 ]; then
        find "$IMAGES_DIR" -name "*_adjusted.yaml" -delete
        echo "  Removed $adj_count adjusted YAML file(s)"
    else
        echo "  (no adjusted YAML files found)"
    fi
else
    echo "  (images directory not found: $IMAGES_DIR)"
fi

# 3. Restore .counted filenames to .png
echo ""
echo "Step 3 — Restoring .counted files to .png..."
if [ -d "$IMAGES_DIR" ]; then
    count=$(find "$IMAGES_DIR" -name "*.counted" | wc -l | tr -d ' ')
    if [ "$count" -gt 0 ]; then
        find "$IMAGES_DIR" -name "*.counted" \
            -exec sh -c 'mv "$1" "${1%.counted}"' _ {} \;
        echo "  Restored $count file(s)"
    else
        echo "  (no .counted files found)"
    fi
else
    echo "  ⚠  Images directory not found: $IMAGES_DIR"
fi

echo ""
echo "✓ Reset complete."
echo ""
echo "⚠  Restart bCounter before scanning (the DB file was deleted)."
echo "   Then: python3 run_counter.py --images images --yaml-dir ~/bBuilder_ballots"
