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

# ── Read data.database.dir from bCounter's application.properties ─────────────
BCOUNTER_PROPS="$BCOUNTER_DIR/src/main/resources/application.properties"

# Read and resolve a single property from a Spring properties file.
# Handles ${user.home}, ${user.dir}, and chained ${other.key} references.
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
  while [[ $pass -lt 5 ]] && echo "$raw" | grep -qF '${'  ; do
    ref=$(echo "$raw" | grep -oE '\$\{[^}]+\}' | head -1)
    ref_key="${ref:2:${#ref}-3}"
    ref_val=$(read_prop "$file" "$ref_key")
    [[ -n "$ref_val" ]] && raw="${raw//$ref/$ref_val}" || break
    pass=$((pass + 1))
  done
  echo "$raw"
}

DB_DIR="$(read_prop "$BCOUNTER_PROPS" "data.database.dir")"

if [[ -z "$DB_DIR" ]]; then
  echo "  ⚠  Could not read data.database.dir from $BCOUNTER_PROPS"
  echo "     Falling back to \$HOME/bSuite_data/db"
  DB_DIR="${HOME}/bSuite_data/db"
fi

echo "── Reset bCounter scan state ─────────────────────────────────"
echo "   bSuite:  $BSUITE_DIR"
echo "   DB dir:  $DB_DIR"
echo "   Images:  $IMAGES_DIR"
echo ""

# 1. Remove SQLite database files
echo "Step 1 — Removing counter database..."
removed=0
for f in "$DB_DIR/counter_results.db" \
          "$DB_DIR/counter_results.db-shm" \
          "$DB_DIR/counter_results.db-wal"; do
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
# 4. Restore .review files to .png
review_count=$(find "$IMAGES_DIR" -name "*.review" 2>/dev/null | wc -l | tr -d ' ')
if [ "$review_count" -gt 0 ]; then
    echo ""
    echo "Step 4 — Restoring $review_count .review file(s) to .png..."
    find "$IMAGES_DIR" -name "*.review" -exec sh -c 'mv "$1" "${1%.review}"' _ {} \;
    echo "  Restored $review_count file(s)"
fi

# 5. Stop bCounter
echo ""
echo "Step 5 — Stopping bCounter..."
pid=$(lsof -ti tcp:8081 2>/dev/null || true)
if [ -n "$pid" ]; then
    kill "$pid" 2>/dev/null || true
    echo "  Stopped bCounter (pid $pid)"
else
    echo "  (bCounter not running)"
fi

echo ""
echo "✓ Reset complete."
echo ""
echo "  Restart bCounter, then run: ./run_all.sh"
