#!/bin/bash
# package_all_desktop.sh — jlink + jpackage every native desktop app in this
# repo. The four recommended ones (builder, counter, scanner, viewer) are
# listed first; blBuilder/blCounter/blScanner are also available (see the
# top-level README). See README.md's "Build a standalone desktop program"
# section for the manual per-app version of these same steps.
#
# `--type app-image` does not cross-compile — this produces macOS/.app
# bundles on macOS, Linux app-images on Linux, etc., matching whatever OS
# you run it on. Building for all three platforms means running this
# script on all three.
#
# Usage:
#   ./package_all_desktop.sh                # all 7 apps
#   ./package_all_desktop.sh builder counter scanner viewer   # just the four
#   ./package_all_desktop.sh blCounter       # just this one

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_MODULES="java.base,java.desktop,java.sql,java.net.http,java.naming,java.security.jgss,jdk.crypto.ec,java.logging,java.management,jdk.unsupported,java.instrument"

# dir:jarName:mainClass:extraModules:coreModule
APPS=(
  "builder:builder-0.9.13.jar:com.mjtrac.builderui.Launcher:java.xml:builder-core"
  "counter:counter-0.9.13.jar:com.mjtrac.counterui.Launcher:java.xml:counter-core"
  "scanner:scanner-0.9.13.jar:com.mjtrac.scannerui.Launcher::scanner-core"
  "viewer:viewer-0.9.13.jar:com.mjtrac.viewerui.Launcher::counter-core"
  "blBuilder:bBuilder-0.9.13.jar:com.mjtrac.ballot.fx.Launcher:java.scripting:builder-core"
  "blCounter:bCounter-0.9.13.jar:com.mjtrac.counter.fx.Launcher:java.scripting:counter-core"
  "blScanner:bScanner-0.9.13.jar:com.mjtrac.scanner.fx.Launcher:java.scripting:scanner-core"
)

WANTED=("$@")

wanted() {
  [[ ${#WANTED[@]} -eq 0 ]] && return 0
  for w in "${WANTED[@]}"; do [[ "$w" == "$1" ]] && return 0; done
  return 1
}

echo "== Installing shared -core modules =="
for core in counter-core scanner-core builder-core; do
  echo "-- $core --"
  ( cd "$SCRIPT_DIR/$core" && mvn -q install -DskipTests )
done

for entry in "${APPS[@]}"; do
  IFS=: read -r dir jar mainclass extra core <<< "$entry"
  wanted "$dir" || continue

  echo ""
  echo "== $dir =="
  cd "$SCRIPT_DIR/$dir"

  ./mvnw -q clean package -DskipTests
  mkdir -p target/lib
  cp "target/$jar" target/lib/

  modules="$BASE_MODULES"
  [[ -n "$extra" ]] && modules="$modules,$extra"

  rm -rf target/app-jre target/dist
  jlink \
    --module-path "$JAVA_HOME/jmods" \
    --add-modules "$modules" \
    --output target/app-jre \
    --strip-debug --no-header-files --no-man-pages --compress=2

  jpackage \
    --input target/lib \
    --name "$dir" \
    --main-jar "$jar" \
    --main-class "$mainclass" \
    --runtime-image target/app-jre \
    --type app-image \
    --app-version 1.0.0 \
    --dest target/dist

  echo "-- $dir packaged: $dir/target/dist/$dir* --"
done

echo ""
echo "== Done =="
