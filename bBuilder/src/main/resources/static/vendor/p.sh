#!/usr/bin/env bash
set -euo pipefail

SRC="/Users/mjtrac/Downloads/node_modules/codemirror"
DST="/Users/mjtrac/pbss/bBuilder/src/main/resources/static/vendor/codemirror"

mkdir -p \
  "$DST/lib" \
  "$DST/mode/xml" \
  "$DST/mode/javascript" \
  "$DST/mode/css" \
  "$DST/mode/htmlmixed"

cp "$SRC/lib/codemirror.js" "$DST/lib/"
cp "$SRC/lib/codemirror.css" "$DST/lib/"

cp "$SRC/mode/xml/xml.js" "$DST/mode/xml/"
cp "$SRC/mode/javascript/javascript.js" "$DST/mode/javascript/"
cp "$SRC/mode/css/css.js" "$DST/mode/css/"
cp "$SRC/mode/htmlmixed/htmlmixed.js" "$DST/mode/htmlmixed/"

echo "CodeMirror 5 files copied to $DST"
