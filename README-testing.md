<!--
  Copyright (C) 2026 Mitch Trachtenberg
  Election Ballot System — licensed under the GNU General Public License v3.
  See <https://www.gnu.org/licenses/> for the full license text.
-->

# Testing the desktop programs

This is a from-clean-slate test sequence for the seven native desktop
programs (**builder**, **counter**, **scanner**, **viewer**, **blBuilder**,
**blCounter**, **blScanner**) and the three shared libraries they depend
on (**builder-core**, **counter-core**, **scanner-core**). Follow it in
order; each step assumes the previous ones succeeded.

This document was written after actually running every step below, on a
clean checkout, on 2026-07-21 — see [Confirmed results](#confirmed-results)
for what that run produced. If a later run of this sequence produces
different results, something changed; don't assume this document is still
accurate without re-running it.

## Prerequisites

- JDK 21+ (jlink/jpackage are part of the JDK — no separate install).
- Python 3.9+ with `pyyaml`, `numpy`, `Pillow` for `test-harness`'s
  scripts (`pip install -r test-harness/requirements.txt`).
- `poppler` (`brew install poppler` / `apt install poppler-utils`) for PDF
  rasterization.
- A real bBuilder-generated ballot PDF+YAML pair at `~/bBuilder_ballots/`
  (see step 4 below — the full sequence produces one as a byproduct of
  testing `builder`/`blBuilder`, so this isn't a separate prerequisite in
  practice).

## Step 1 — Install the shared `-core` libraries

`builder`, `counter`, `scanner` (Swing) and `blBuilder`, `blCounter`,
`blScanner` (JavaFX) all depend on one of three shared Maven artifacts —
none of the six apps duplicates this logic:

```bash
cd builder-core  && mvn -q install -DskipTests && cd ..
cd counter-core  && mvn -q install -DskipTests && cd ..
cd scanner-core  && mvn -q install -DskipTests && cd ..
```

(`package_all_desktop.sh`, in step 3, does this for you automatically —
run it standalone here only if you want to run `mvn test` per-app before
packaging anything.)

## Step 2 — `mvn test` per module

Run in each of these nine directories (the three `-core` libraries, then
the six apps — order doesn't matter here since step 1 already installed
the libraries they depend on):

```bash
for m in builder-core counter-core scanner-core \
         builder blBuilder counter blCounter scanner blScanner; do
  echo "== $m =="
  cd "$m"
  if [ -f mvnw ]; then ./mvnw -q clean test; else mvn -q clean test; fi
  cd ..
done
```

See [What `mvn test` covers](#what-mvn-test-covers-per-module) below for
what's actually being verified in each module, and
[Known environment-dependent behavior](#known-environment-dependent-behavior)
for the handful of tests that don't reliably pass/fail the same way on
every machine — that's expected, not a regression.

## Step 3 — Package every app

`package_all_desktop.sh` (repo root) installs the three `-core` libraries,
then jlinks + jpackages each app into a real, standalone `.app` (macOS) /
app-image (Linux) / folder-with-`.exe` (Windows) — this is the actual
distributable artifact end users run, so building it is part of testing,
not just `mvn test` passing:

```bash
./package_all_desktop.sh                                   # all 7
./package_all_desktop.sh builder counter scanner viewer     # just the four recommended ones
```

Confirm each app actually exists afterward:

```bash
for m in builder counter scanner viewer blBuilder blCounter blScanner; do
  echo -n "$m: "; ls "$m/target/dist/"
done
```

**Note:** this script resolves each built jar by glob (`target/<artifactId>-*.jar`),
not a hardcoded version — the packaging step used to hardcode a jar
filename with the project's version number baked in, which broke the
first time the version changed after the script was written. If a future
version bump ever reintroduces a hardcoded filename (`<name>-<version>.jar`),
that's a regression of this same bug.

## Step 4 — Scripted end-to-end pipelines

`mvn test` proves the engine and (where GUI infrastructure exists) the
screens work in isolation. These `test-harness/` scripts additionally
prove the *whole pipeline* — a real ballot design, marked with realistic
voter behavior, geometrically distorted the way a real scan would be, then
actually counted — end to end, against ground truth.

### builder / blBuilder — via `run_all.sh`

`test-harness/run_all.sh` builds a full 8-contest, 6-combination election
through a live `bBuilder` (start it first: `./mvnw spring-boot:run` in
`bBuilder/`), which is what actually produces the `~/bBuilder_ballots/`
fixture the rest of this sequence (and `builder`/`blBuilder`'s own `mvn
test` suites) depend on. This is the real clean-slate test of the ballot
generation path, even though it drives it through bBuilder's REST API
rather than `builder`'s own Swing UI (`builder`/`blBuilder` don't currently
have a GUI-click-through pipeline script the way `counter`/`blCounter` do
— see the coverage gaps noted below):

```bash
cd test-harness
./run_all.sh --reset --copies 1   # --reset wipes bCounter's DB first — required for a valid accuracy number
```

Watch for the final `Accuracy: NN.N% (M/N)` line and `Missing in DB` /
`Missing in GT` counts — see
[Confirmed results](#confirmed-results) for what a clean run should show.

### counter / blCounter — via `run_desktop_gui_pipeline.sh`

This is the one part of the sequence that drives the actual Swing/JavaFX
Start Count button via robot clicks, not just the counting engine:

```bash
cd test-harness
./run_desktop_gui_pipeline.sh   # regenerates the corpus fresh, then runs both GUI tests
```

**This corpus must be freshly regenerated before each run.** Successfully
scanning it renames every image to `.counted` (so a real restart resumes
instead of re-scanning everything — see `CountingService`/`VoteRecordService`);
re-running a GUI test against an already-fully-scanned corpus will fail
fast with "All images in this folder have already been counted" instead of
counting anything. That's the counting engine working correctly, not a
bug — just re-run `./run_desktop_gui_pipeline.sh` (or its `--skip-corpus`
flag if you specifically want to test the "restart on nothing left"
error path).

### scanner / blScanner

There is no equivalent scripted GUI pipeline for `scanner`/`blScanner` —
see the coverage gap noted below. `scanner-core`'s own `mvn test`
(step 2) is currently the closest thing to an end-to-end test: it drives
`ScanService` through a mock `command` backend (a shell script that writes
fake image files instead of talking to real scanner hardware) exercising
the same start/monitor/count/batch-log path a real `naps2`/`scanimage`
backend would.

## What `mvn test` covers, per module

| Module | What's tested |
|---|---|
| `builder-core` | No tests of its own — its model/service/repository classes (package `com.mjtrac.ballot`) are exercised through `blBuilder`'s and `builder`'s test suites below, not directly. |
| `counter-core` | `BallotViewServiceResolveImagePathTest` (image-path resolution for the viewer). The bulk of the counting engine (corner detection, barcode reading, marker analysis, RCV tabulation) is tested through `bCounter`/`blCounter`'s suites, not here. |
| `scanner-core` | `ScanServiceNotesTest` (start/end note logging) + `ScanServiceMockScanTest` (real scan-and-count path via a mock `command` backend — added 2026-07-21, previously untested). |
| `builder` | `BuilderEndToEndTest` (full CRUD → real PDF, headless, through `builder-core`'s own repositories), `RegionPartyShortcutTest` (the "use single party/region" reassign-then-delete shortcuts), `TestElectionBuilderTest` (the headless bBuilder-REST fallback used by `run_all.sh`). No Swing GUI-click-through test exists (no `assertj-swing` dependency in this module). |
| `blBuilder` | `BallotDesignTemplateTest`, `BallotGenerationServiceTest`, `BallotGenerationTest`, `ContestAssignmentServiceTest`, `ExportServiceTest`, `MeasurementUtilTest`, `TemplateReflectedInBallotTest` (all headless service-layer), plus real TestFX/Monocle screen tests: `AdminScreenTest`, `BallotCombinationScreenTest`, `BallotDesignTemplateScreenTest`, `ContestScreenTest`, `LoginScreenTest`, `PartyScreenTest`, `PrintScreenTest`, `RegionScreenTest`. |
| `counter` | `CountingServiceIntegrationTest` (full scan → results report, real fixture images), `CountingResumeIntegrationTest` (the stop/restart-resumes-correctly regression test — added 2026-07-20), `CountingPipelineGuiTest` (AssertJ-Swing, real Start Counting button clicks against the `desktop_pipeline` corpus — see coverage gap below). |
| `blCounter` | `BarcodeReaderServiceTest`, `CornerDetectionTest` (300 DPI precision fixtures), `MarkerAnalysisServiceTest`, `RcvTabulationServiceTest` (all headless), plus TestFX/Monocle screen tests: `AccountScreenTest`, `AdminScreenTest`, `CountingScreenTest`, `LoginScreenTest`, `ResultsScreenTest`, `ViewerScreenTest`, and `CountingPipelineGuiTest` (TestFX, real Start Count clicks against the `desktop_pipeline` corpus). |
| `scanner` | Only `ScreenshotGenerator` (a documentation-screenshot tool, not a test). No functional or GUI test exists in this module — no `assertj-swing` dependency. |
| `blScanner` | **No test directory exists at all.** No TestFX/Monocle dependency has been added to this module's `pom.xml`. |
| `viewer` | `BallotViewPanelLayoutTest`, `ContestCandidateWindowTest`, `HomographyTest` (all headless), plus `ScreenshotGenerator`. |

## Known environment-dependent behavior

**macOS Accessibility permission gates real Swing/AWT robot input.**
`counter`'s `CountingPipelineGuiTest` uses AssertJ-Swing, which drives a
*real* window via `java.awt.Robot` — this requires whatever process
launched the JVM (Terminal, iTerm, a CI runner) to hold Accessibility
permission (macOS: System Settings → Privacy & Security → Accessibility).
Without it, this test **skips itself** with a clear message rather than
hanging or failing — but the exact behavior is genuinely non-deterministic
across runs on a machine where that permission is inconsistently granted
to background/spawned processes: the same test, run back to back with no
code changes, has been observed to (a) complete a full real scan
successfully, (b) throw `ActionFailedException` and skip cleanly, and (c)
silently fail to deliver the click/keystroke events at all (caught by this
test's fail-fast check, which reports the actual folder field values and
message-label text rather than hanging for the full 20-minute timeout).
**None of these is a bug** — grant the permission and re-run if you need a
guaranteed pass, or run under Linux CI with Xvfb, which isn't subject to
this restriction.

**`blBuilder`/`blCounter`'s TestFX tests do not have this problem** — they
render off-screen via Monocle, so they're not gated by OS input focus at
all, and were 100% reliable across every run in this session.

**Coverage gaps** (not fixed as part of this document — noted so they're
not mistaken for accidental omissions):
- `builder` and `scanner` have no `assertj-swing` dependency and no
  GUI-click-through test at all.
- `blScanner` has zero automated tests of any kind.
- There is no scripted end-to-end pipeline for `scanner`/`blScanner`
  analogous to `run_desktop_gui_pipeline.sh` — closing that gap would mean
  adding TestFX+Monocle to `blScanner` and `assertj-swing` to `scanner`,
  which is meaningfully more work than extending the existing mock-backend
  approach and wasn't done here.

## Confirmed results

Run on 2026-07-21, clean checkout, macOS, JDK 25 (Homebrew), against the
current `main` branch:

| Module | Result |
|---|---|
| `builder-core`, `counter-core`, `scanner-core` | Installed cleanly. |
| `builder` | 5/5 tests pass (`BuilderEndToEndTest` 2, `RegionPartyShortcutTest` 2, `TestElectionBuilderTest` 1). |
| `blBuilder` | 79/79 tests pass across all 15 test classes. |
| `counter` | 3/3 tests pass, 1 skip (`CountingPipelineGuiTest` — Accessibility permission not granted to this shell on this run). |
| `blCounter` | Full suite passes; `CountingPipelineGuiTest` passes cleanly (23.03s) against a freshly regenerated corpus. |
| `scanner-core` (mock scan) | `ScanServiceMockScanTest` 2/2, `ScanServiceNotesTest` 4/4. |
| `scanner`, `blScanner` | No automated tests to run (see coverage gaps above). |
| `package_all_desktop.sh` (all 7 apps) | All 7 `.app` bundles produced successfully after fixing the hardcoded-version bug (see step 3). |
| `run_all.sh --reset --copies 1` (full 3,060-image election, 300 DPI) | **100.0% accuracy (88/88), 0 mismatches, 0 missing in DB, 0 missing in GT.** |
| `run_desktop_gui_pipeline.sh` equivalent (isolated 255-image corpus, 150 DPI, freshly regenerated ballot) | 255/255 counted, 0 flagged for review. |
| Ballot rotation tolerance (dedicated probe, current ballot design) | Rotated ballots counted correctly up to at least 6.5° in both directions. **Rotation up to 5° is confirmed handled correctly** — well within any realistic scanning condition; there is no need to support rotation beyond 5°. |

No test-harness material, run from a genuinely clean setup against the
ballot designs pbss currently generates, fails to count votes correctly.

When a ballot's corner detection does fail (well outside normal scanning
conditions), the failure mode is fail-safe, not fail-silent:
`voteRecord.persist()` — the only code path that writes votes to the
database — is only reached when a ballot's corner detection succeeds. A
ballot that fails is excluded from the tally entirely, logged by name to
`review_required.txt`, and (past `scanner.max-review-before-stop`) halts
the whole scan rather than continuing silently past it — never a wrong
vote attributed to the wrong candidate.
