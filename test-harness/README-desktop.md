# Desktop GUI Automation

The web-app test harness (`run_all.sh`, documented in [README.md](README.md))
drives bBuilder/bCounter over their REST/HTTP endpoints. The native desktop
apps (builder, counter, scanner, viewer, blBuilder, blCounter, blScanner)
have no such endpoints to script against — `counter`/`scanner`/`builder` are
headless Spring Boot with no web layer at all, and blBuilder/blCounter/
blScanner's own web endpoints are gone by design. Driving them requires
actual GUI automation: simulating the clicks and keystrokes a person would
make.

This directory (`run_desktop_gui_pipeline.sh` + the `CountingPipelineGuiTest`
classes it invokes) replicates `run_all.sh`'s create → mark → distort →
count → verify pipeline for two of those apps — **blCounter** and
**counter** — using two open-source GUI-automation libraries:

## Watching it run

Both tests normally run invisibly by design (see the library table below),
but each can be made to show a real on-screen window:

- **counter** (AssertJ-Swing) already drives a real `JFrame` — nothing to
  change. You just need macOS's Accessibility permission granted to
  whatever launches the JVM (see that app's section below); once granted,
  `./mvnw test -Dtest=CountingPipelineGuiTest` shows the real window while
  the robot drives it.
- **blCounter** (TestFX) renders off-screen via Monocle by default — that's
  *why* it needs no OS permission. To watch it instead:
  ```bash
  cd blCounter
  ./mvnw test -Dtest=CountingPipelineGuiTest \
    -Dtestfx.headless=false -Dglass.platform= -Dmonocle.platform=
  ```
  (empty `-D` values clear the Monocle-forcing properties so JavaFX falls
  back to the real platform toolkit). A real window will appear and the
  robot will visibly type/click/scroll through the ~255-image scan.

| App type | Library | Why |
|---|---|---|
| JavaFX (blBuilder, blCounter, blScanner) | [TestFX](https://github.com/TestFX/TestFX) + [Monocle](https://github.com/testfx/openjfx-monocle) | Already used for this project's existing FX screen tests. Monocle renders off-screen (headless) — no real window, no OS input permissions needed. |
| Swing (counter, scanner, builder, viewer) | [AssertJ-Swing](https://github.com/assertj/assertj-swing) | The standard open-source Swing GUI-automation library (successor to FEST-Swing). Drives real windows via `java.awt.Robot` — see the permission note below. |

## Why not just re-run `build_election.py` / `run_counter.py`?

Two reasons, one safety-related and one architectural:

1. **Safety.** `build_election.py` calls bBuilder's `DELETE /api/test/reset`,
   which wipes all ballot data — fine against a disposable dev instance, but
   dangerous if a real bBuilder/bCounter happens to be running against real
   `~/pbss_data` (which is exactly what this machine had running when this
   harness was built — see the note in the "Ground truth corpus" section
   below). This harness never makes an HTTP call to any bBuilder/bCounter
   instance at all, live or otherwise.
2. **The point of this exercise.** `run_counter.py` drives bCounter's
   `/start` REST endpoint — that proves the counting *engine* works, which
   `counter-core`'s existing test suite already covers exhaustively. What's
   new and worth proving here is that the *desktop GUI wiring* — the actual
   Start Count button, the actual folder fields, the actual Finish handler —
   correctly drives that same engine. Hence: real robot clicks, not a
   backdoor API call.

## Ground truth corpus (offline, real ballot data)

`mark_ballots.py --ballot-pdf/--ballot-yaml` (added for this harness) marks
up an **already-exported real ballot PDF+YAML pair** — by default
`~/bBuilder_ballots/ballot_1_1_1_1_1_1.{pdf,yaml}` — instead of requiring a
live bBuilder and `election_data.json`. `distort_ballots.py` then applies
its usual 17 geometric distortions, unchanged. Both scripts are pure
local file I/O — no server contact, so nothing at `~/pbss_data` is ever at
risk from this step either.

```bash
./run_desktop_gui_pipeline.sh                          # full pipeline
./run_desktop_gui_pipeline.sh --skip-corpus             # reuse existing images
./run_desktop_gui_pipeline.sh --dpi 150 --ballot ballot_1_1_1_1_1_1
```

Writes `desktop_pipeline/marked_ballots/`, `desktop_pipeline/images/`
(255 images at the default settings: 15 scenarios × 17 distortions), and
`desktop_pipeline/images/ground_truth_all.json`.

**Use 150 DPI, not 300.** These are pipeline/wiring tests, not
corner-detection-precision tests (`CornerDetectionTest` already covers that
at 300 DPI with dedicated fixtures) — 300 DPI real full-page ballots make a
255-image scan take several minutes for no added test value; 150 DPI runs
in well under a minute.

## blCounter — `CountingPipelineGuiTest` (TestFX)

`blCounter/src/test/java/com/mjtrac/counter/fx/CountingPipelineGuiTest.java`
loads the real `counting.fxml` + `CountingViewController` against a live
Spring context, then via `FxRobot`: types the images/layout folder paths,
sets the DPI spinner, clicks **Start Count**, polls the actual `#statusBox`
style class for completion, clicks **Finish & Save Results**, then asserts
the scan finished without an engine error and wrote a real results report.

`spring.datasource.url` and `reports.output.dir` are overridden to
`desktop_pipeline/blcounter_results/` via a dedicated `guipipeline` Spring
profile (`src/test/resources/application-guipipeline.properties`) —
**not** via `SpringApplicationBuilder.properties(...)`, which turned out to
set Spring Boot's *lowest*-precedence "default properties" and silently lost
to this module's own `src/test/resources/application.properties` (which
points at an in-memory DB and a shared temp dir — safe, but not what a
GUI-driven pipeline test needs, since results must persist as a real file
for `verify_results.py` to read afterward). If you write a similar test for
another JavaFX app, use the same profile-file trick, not `.properties(...)`,
for any key that already has a value in that module's own
`application*.properties`.

Run directly:
```bash
cd blCounter && ./mvnw test -Dtest=CountingPipelineGuiTest
```

**Re-running without regenerating the corpus fails.** `VoteRecordService`
renames each successfully-counted image to `<name>.png.counted` as an
anti-double-count safeguard — the same reason the web pipeline's
`reset_scan.sh` exists ("restore image filenames"). A second direct
`mvn test -Dtest=CountingPipelineGuiTest` against an already-scanned corpus
will fail fast with "No image files found in: ..." (the test detects this
immediately rather than hanging — see below). `run_desktop_gui_pipeline.sh`
regenerates the corpus by default on every run, so this only bites you when
invoking the test directly and skipping that step.

Then compare against ground truth:
```bash
python3 test-harness/verify_results.py \
  --db test-harness/desktop_pipeline/blcounter_results/counter_results.db \
  --gt test-harness/desktop_pipeline/images/ground_truth_all.json
```

### A real finding from this test, not a bug

At 150 DPI, 6 of the 17 distortion types (`perspective`, and rotations
≥1.5°: `rot_ccw_1_5`, `rot_cw_1_5`, `rot_ccw1_trans`, `rot_ccw_1`,
`rot_cw_2_0`) get correctly flagged for manual review rather than tallied —
corner-mark detection's rotation tolerance is lower at 150 DPI than at
300 DPI (where `CornerDetectionTest`'s dedicated fixtures confirm rotations
up to 2° are detected reliably). `verify_results.py`'s "accuracy" percentage
doesn't know to exclude review-required images from its ground-truth
expectations, so it reports this as a large mismatch — it isn't one. Check
`review_required.txt` in the results directory before reading too much into
a low accuracy number from this specific corpus.

## counter — `CountingPipelineGuiTest` (AssertJ-Swing)

`counter/src/test/java/com/mjtrac/counterui/CountingPipelineGuiTest.java`
is the Swing counterpart: launches the real `MainFrame`, types into the
image/report folder fields (named via `setName(...)` — the Swing equivalent
of FXML's `fx:id`, added to `MainFrame` for this), clicks **Start
Counting**, and waits for `MainFrame`'s own auto-`finish()` (no separate
Finish button here) to enable **Open Results Folder**.

Same `guipipeline`-profile trick as blCounter for `spring.datasource.url`/
`reports.output.dir`; `counter.dpi` also goes through that profile file
since this app has no DPI UI control (fixed via `application.properties` by
design — see `counter/README.md`).

### macOS Accessibility permission

`java.awt.Robot`'s synthetic keyboard/mouse events require the OS process
that launched the JVM to hold macOS's Accessibility permission (System
Settings → Privacy & Security → Accessibility). Confirmed directly in this
dev environment with a standalone `Robot` probe: `createScreenCapture`
worked, `mouseMove` silently no-opped. JavaFX/TestFX doesn't hit this at all
— Monocle renders off-screen, no real window or OS permission involved.
Swing/AWT has no equivalent headless backend, so AssertJ-Swing always needs
a real, focusable window.

The test detects this (an `ActionFailedException` from the very first
robot interaction) and skips cleanly via JUnit's `Assumptions.assumeTrue`,
with a message pointing at the fix, instead of hanging for the library's
full focus-wait timeout or reporting a false failure. To actually exercise
it end-to-end: grant Accessibility permission to whatever launches the JVM
(Terminal/iTerm — System Settings → Privacy & Security → Accessibility →
add it, then restart that application) and re-run, or run under Linux CI
with Xvfb (X11's XTest extension isn't gated the same way).

```bash
cd counter && ./mvnw test -Dtest=CountingPipelineGuiTest
```

## builder — screen tests + `DesktopElectionBuilder` (AssertJ-Swing)

`builder` (added 2026-07-21) has the same AssertJ-Swing setup as `counter`
— every menu item, every CRUD screen's New/Edit/Delete/Refresh cycle, and
the Contest→Candidates→Regions dialog cascade, in
`builder/src/test/java/com/mjtrac/builderui/*GuiTest.java`. Same macOS
Accessibility caveat as `counter` above, via the identical
`AbstractBuilderGuiTest.robotAction()` skip-cleanly wrapper. This pass also
caught and fixed two real bugs nothing had caught before: a
`ClassCastException` on "Manage Candidates"/"Assign Regions" for any
already-saved Contest reopened by double-click, and every candidate on a
newly created Contest being silently duplicated in the database on the
ordinary create→add-candidates→assign-regions workflow (`contestRepo.save()`
discarding its merge() return value across the cascade) — see
`builder/README-testing.md` and `ContestCascadeGuiTest`'s Javadoc for both.

`DesktopElectionBuilder` (same package) is `run_all.sh`'s third path for
building the test election — alongside `build_election.py` (bBuilder REST,
the full 15-contest election) and `TestElectionBuilder` (headless, direct
repository calls) — invoked by `run_all.sh --desktop`. It builds the same
small election `TestElectionBuilder` does (1 region, 2 Plurality contests,
4 candidates, 1 combination, 1 template), but by actually clicking through
`builder`'s real screens with a real `Robot`, cascading through the same
Contest→Candidates→Regions dialogs the screen tests cover. It runs against
the real `~/pbss_data` database (not an isolated temp DB — this needs to be
the database `counter` itself will scan against), and is idempotent: a
second run detects the already-built election by name and just re-generates
its PDF/YAML headlessly instead of re-driving several minutes of robot
clicks.

```bash
cd builder && mvn test -Dtest=DesktopElectionBuilder -Dtest-election.out=/abs/path/election_data.json
# or, as part of the full pipeline:
cd test-harness && ./run_all.sh --desktop
```

## Extending this to the other apps

Not yet built (out of scope for this pass, but the pattern above is
directly reusable):

- **scanner / viewer** (Swing) — same AssertJ-Swing pattern as `counter`
  and `builder`: name the relevant components via `setName(...)`, add the
  `assertj-swing` test dependency. `scanner`'s start/end-notes flow is the
  more involved candidate; `viewer` is read-only and simpler.
- **blBuilder / blScanner** (JavaFX) — blBuilder already has extensive
  TestFX coverage (79 tests, including `PrintScreenTest` driving real PDF
  generation) from its original build — that already satisfies "create
  ballots via the GUI" for the JavaFX side. blScanner currently has no
  tests at all (`src/test/` doesn't exist) — a pre-existing gap, not
  something this pass added or was scoped to fix.
- **scanner's actual scan-capture step** is out of scope everywhere in this
  project's test harness, web or desktop: it needs a physical or simulated
  scanner backend, which `test-harness/` has never driven — both the web
  and desktop pipelines start from pre-rendered PNGs instead.
