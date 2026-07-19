<!--
  Copyright (C) 2026 Mitch Trachtenberg
  Election Ballot System — licensed under the GNU General Public License v3.
  See <https://www.gnu.org/licenses/> for the full license text.
-->

# pbss — Paper Ballot Software System

pbss is an open-source suite for anyone in or out of government to design,
print, scan, count, and audit paper election ballots.

**No internet connection is required to run any of these programs.**
Every app in this repo serves itself on `localhost` (or, for the
recommended desktop apps below, doesn't open a network port at all), reads
and writes only local files and a local SQLite database, and makes no
calls to any external service at runtime. **For any real election, run
these programs on a machine that is not connected to the internet at
all** — connected only to a scanner and a printer (by USB, or a local
network reserved just for those devices), with data moved between
machines by USB drive rather than over any network. See
[Running Offline / Air-Gapped](#running-offline--air-gapped) below.

## Recommended: Desktop Programs (builder, counter, scanner, viewer)

The recommended way to run pbss is as four native, standalone desktop
programs — **builder**, **counter**, **scanner**, and **viewer** — built
with Swing. Each bundles its own private Java runtime, so end users don't
need Java, Maven, or a browser installed at all: on macOS it's a
double-clickable `.app`, on Linux an app-image (or `.deb`/`.rpm`), on
Windows a folder with a `.exe` (or an `.msi`). They're smaller, simpler,
and faster to start than their web (`b`) and JavaFX (`bl`) counterparts,
which remain available and are described further down.

| App | Role | Login |
|---|---|---|
| **builder** | Design elections, enter candidates/contests/regions, generate ballot PDFs and YAML layout files. Full CRUD across everything bBuilder manages. | None |
| **counter** | Load ballot images, count votes, report results. A small control panel for just the counting engine: folders in, start/stop, results out. | `CounterUser` — `COUNTER_OPERATOR` or `ADMIN` |
| **scanner** | Drive a physical document scanner (NAPS2, `scanimage`, or a custom command) to produce ballot images, plus operator start/end notes. | `ScannerUser` — `ADMINISTRATOR` or `OPERATOR` |
| **viewer** | Review scanned ballot images with vote-indicator overlays, read-only. | `CounterUser` — `VIEWER` or `ADMIN` |

All four read/write the same `~/pbss_data/` directory and database files as
their `b`/`bl` counterparts (see [Data Directories](#data-directories)
below) — fully interchangeable, including switching apps mid-election.
None of the four opens a network port; they talk to their local SQLite
database and the local filesystem directly.

### Building the recommended apps

`package_all_desktop.sh`, at the repo root, jlinks + jpackages every
native desktop app in one step:

```bash
./package_all_desktop.sh                              # all 7 desktop apps
./package_all_desktop.sh builder counter scanner viewer  # just the four
./package_all_desktop.sh counter                       # just one
```

**What it does:** installs the three shared `-core` libraries
(`counter-core`, `scanner-core`, `builder-core`) with `mvn install`, then
for each requested app: `./mvnw clean package`, flattens dependencies into
`target/lib/`, runs `jlink` to build a minimal private Java runtime
containing only the modules that app needs, and runs `jpackage --type
app-image` to bundle that runtime with the app into a native package.

**What to expect as output:** for each app, a self-contained package at
`<app>/target/dist/<app>.app` (macOS — Linux and Windows produce the
platform equivalent, an app-image folder or installer). Verify a build
without launching its UI:

```bash
viewer/target/dist/viewer.app/Contents/MacOS/viewer --version
# → viewer 0.9.14
```

**Cross-platform building:** yes, these can be built for macOS, Linux, and
Windows — but `--type app-image` does **not** cross-compile. It produces
whatever platform's native package matches the OS `jpackage` actually runs
on. Building all three means running `package_all_desktop.sh` (or the
manual jlink/jpackage steps in each app's own README) once on each target
OS. There's no way to produce a Windows package from a macOS machine, or
vice versa.

## Using builder / counter / scanner / viewer with test-harness

`test-harness/` is a Python automation suite (see `test-harness/README.md`
and `test-harness/README-desktop.md`) that builds a test election, marks
and distorts ballot images, runs them through a counting app, and verifies
the results against known ground truth. Its data-generation and
verification scripts are counting-app-agnostic — they work identically
whichever of bCounter/blCounter/counter produced the results, since all
three share the same `counter-core` database schema:

1. **Create test ballots** — `build_election.py` drives **bBuilder** (the
   web app) via its `/api/test/*` REST endpoints to create a full test
   election and generate ballot PDFs/YAMLs. `builder` (Swing) has no REST
   API to script against, so automated test-data creation goes through
   bBuilder; you can still design ballots by hand in `builder`'s own
   screens and feed the resulting PDFs/YAMLs into the same pipeline.
2. **Mark and distort** — `mark_ballots.py` rasterizes ballot PDFs and
   applies voting scenarios (valid marks, X's, checkmarks, overvotes,
   write-ins, ranked-choice, etc.), producing marked PNGs plus a
   ground-truth JSON. `distort_ballots.py` then applies 15 geometric
   distortions × N copies. Both operate purely on files, independent of
   which app will eventually count them.
3. **Scan/count** — point `counter`'s two folder fields (or `scanner`'s
   output folder feeding `counter`'s image folder) at the marked/distorted
   images and the YAML layout folder, then Start Counting. For **counter**
   specifically, this step is already automated: `run_desktop_gui_pipeline.sh`
   (see `test-harness/README-desktop.md`) drives it end-to-end with
   AssertJ-Swing (`CountingPipelineGuiTest`), the same way it drives
   blCounter with TestFX. Automated GUI-driven pipelines for `builder`,
   `scanner`, and `viewer` are not built yet — `test-harness/README-desktop.md`
   documents the pattern to extend the same AssertJ-Swing approach to them.
4. **Verify** — `verify_results.py` queries `counter_results.db` and diffs
   it against the ground-truth JSON regardless of which app wrote it,
   printing a pass/fail summary and writing `verify_report.json`.
5. **Review** — `viewer` isn't part of the automated pipeline (it's a
   read-only inspection tool, not something `verify_results.py` drives);
   use it afterward to visually spot-check individual ballots the
   verification step flagged as mismatched.

**What to expect:** a clean run prints a summary like
`✓ 500/500 ballots matched ground truth` and exits 0; any mismatch is
listed by ballot/contest/candidate in both the console output and
`verify_report.json`. `reset_scan.sh` resets `counter_results.db` and
restores image filenames (undoing the `.counted` rename) between runs.

## Configuring These Apps (Property Overrides)

All eight apps in this repo are Spring Boot applications underneath (four
Swing, three web, three JavaFX — some names shared), so they all support
the same standard Spring Boot ways of overriding a setting, checked in
this order of precedence (highest wins):

1. **Command-line arguments** — `--property.name=value`, appended after
   the jar or packaged binary:
   ```bash
   # From source
   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--scanner.max-review-before-stop=50"

   # A packaged app — arguments after the binary are forwarded straight
   # through to main(), so this works directly from a terminal:
   viewer/target/dist/viewer.app/Contents/MacOS/viewer --spring.datasource.url=jdbc:sqlite:/other/path.db
   ```
2. **JVM system properties** — `-Dproperty.name=value`:
   ```bash
   java -Dscanner.max-review-before-stop=50 -jar bCounter-0.9.14.jar
   ```
3. **An external `application.properties` (or `.yml`)** — drop one in the
   current working directory, or a `config/` subdirectory next to it, when
   launching from source or from a plain jar. It's read *before* the copy
   bundled inside the jar, so any property it sets wins.
4. **Environment variables** — Spring's relaxed binding maps
   `SCANNER_MAX_REVIEW_BEFORE_STOP=50` to `scanner.max-review-before-stop`.
5. **The bundled `application.properties`** inside each app's jar — the
   defaults documented in each app's own README.

One precedence detail worth knowing if you're reading the source: several
of these apps set a default image/report folder via
`SpringApplicationBuilder.properties(...)` in their `*App.java` — that
call sets Spring's **lowest**-precedence "default properties" layer,
*below* even the bundled `application.properties`, specifically so any of
the mechanisms above (and the plain properties file) can always override
it.

### For packaged (jlink/jpackage) apps specifically

A double-clicked `.app`/installer has no terminal to pass `--property=value`
to. Two options:

- **Launch it from a terminal** instead of double-clicking — args are
  forwarded to `main()` exactly as shown above (this is how `--version`
  works on every app in this repo).
- **Edit the generated `.cfg` file** so the override applies even when
  double-clicked. On macOS it's at
  `<App>.app/Contents/app/<App>.cfg`; add a line under `[JavaOptions]`:
  ```ini
  [JavaOptions]
  java-options=-Dspring.datasource.url=jdbc:sqlite:/other/path/counter_results.db
  ```
  (Program arguments instead of JVM properties go under a separate
  `[ArgOptions]` section as `arguments=--property=value` lines.) This is
  also how you'd point a packaged app's database at a non-default location
  — e.g. a merged database on a viewer-only station, see below.

## Running Offline / Air-Gapped

None of these apps require internet access to run, at any point:

- Each Spring Boot app serves itself on `localhost` only (the four
  recommended Swing apps don't open a network port at all — they talk to
  SQLite and the filesystem directly).
- No app calls out to any external API, license server, or update check.
- Scanner integration is entirely local: NAPS2/`scanimage`/a custom shell
  command talk to a locally attached device over USB.
- All data lives in local files (`~/pbss_data/`) and a local SQLite
  database file.

**For any real election, run the counting machine(s) on a network that is
not connected to the internet** — connected only to a scanner and a
printer, ideally over USB directly rather than any shared network at all.
Disable Wi-Fi and Bluetooth on the machine for the duration of the
election, and move data between machines (see the multi-station workflow
below) with a USB drive, never a network transfer. This isn't a special
mode you have to configure — it's the normal way these apps already run;
disconnecting the network changes nothing about how they function.

## Multi-Station Elections

For high ballot volumes, run multiple counting stations in parallel —
each a separate machine with its own attached scanner (and, if you're
using the notes/flag-page feature, its own printer) — then combine their
results.

### Setting up multiple stations

1. Install `counter` (or `bCounter`/`blCounter`) and, if driving a
   physical scanner, `scanner` (or `bScanner`/`blScanner`) on each
   station machine. Each station is a normal, independent, offline
   install — see [Running Offline / Air-Gapped](#running-offline--air-gapped)
   above.
2. Give each station a **disjoint** batch of ballots to scan (e.g. by
   precinct, or by physically dividing the ballot stack) — nothing in
   pbss deduplicates ballots that were scanned identically twice, so
   avoid feeding the same physical ballot to two stations.
3. Use the **same OS username and the same default `~/pbss_data` layout**
   on every station if at all possible. `counter_results.db` stores each
   scanned image's *full absolute path* — keeping paths identical across
   machines is what makes the merge step and any later ballot-image
   review portable, without needing to rewrite paths by hand.
4. Scan each station's batch independently to completion (each produces
   its own `counter_results.db` and its own `cast_ballot_scans/` folder of
   images).
5. Copy each station's `~/pbss_data/db/counter_results.db` and
   `~/pbss_data/cast_ballot_scans/` to a USB drive, and from there to a
   central machine for merging — never over a network.

### Merging databases

`db_merge.py` (repo root) is a Tkinter GUI for comparing and merging
multiple `counter_results.db` files:

```bash
python3 db_merge.py
# Requires Python 3.9+ and tkinter (included with Python on macOS)
```

- **Add Database** for each station's `counter_results.db`. The tool
  immediately shows per-station vote totals side by side, plus a live
  merged total, per contest and candidate.
- **Merge All → Save** writes a single combined database. Contests and
  candidates are deduplicated by name so per-station copies of the same
  contest collapse into one; ballot images are deduplicated by their
  stored path, so accidentally adding the same station's database twice
  (or overlapping batches) doesn't double-count — any such dedupe is
  reported in the **Merge Log** tab.
- **Export CSV** / **Export Excel** write a per-station-plus-summed
  breakdown of every contest/candidate as an additional canvassing/audit
  artifact, independent of the merge itself.

Point `counter`/`viewer` (or bCounter/blCounter) at the merged
`counter_results.db` for a combined results view — either replace
`~/pbss_data/db/counter_results.db` with it, or override the datasource
path per [Configuring These Apps](#configuring-these-apps-property-overrides)
above (`spring.datasource.url=jdbc:sqlite:/path/to/counter_results_merged.db`).

### Setting up a viewer-only station

`viewer` never writes to `counter_results.db`
(`spring.jpa.hibernate.ddl-auto=none`), so it's always safe to point one
or more read-only viewer stations at a shared or merged database:

1. Copy the merged `counter_results.db` (or a single station's, if you're
   not merging) to the viewer machine, and override
   `spring.datasource.url` to point at it (see above).
2. Copy each station's `cast_ballot_scans/` images to the viewer machine
   too, **preserving the same absolute paths** the scanning stations used
   — this is why step 3 above (same username, same `~/pbss_data` layout)
   matters: if the paths recorded in the database don't exist on the
   viewer machine, vote tallies still display correctly (they come from
   the database), but individual ballot images won't load.
3. Run `viewer`, sign in with a `VIEWER`- or `ADMIN`-role `CounterUser`.
   No scanning or counting happens here — it's read-only by design, safe
   to run on as many machines as needed without any coordination.

## Other Versions Available

Alongside the four recommended Swing apps, the original web apps and their
JavaFX counterparts remain fully available and fully maintained — same
data model, same `~/pbss_data/` files, interchangeable mid-election with
the Swing apps above:

| App | UI | Notes |
|---|---|---|
| **bBuilder** | Web (port 8080) | Original ballot-design app |
| **bCounter** | Web (port 8081), embeds **bViewer** (port 8082) | Original counting app |
| **bScanner** | Web (port 8083) | Original scanner-driver app |
| **blBuilder** | JavaFX desktop | Native rewrite of bBuilder |
| **blCounter** | JavaFX desktop | Native rewrite of bCounter; its Ballot Viewer screen embeds the original web Viewer in a `WebView` |
| **blScanner** | JavaFX desktop | Native rewrite of bScanner |

See [Native Desktop Versions](#native-desktop-versions) below for
`blBuilder`/`blCounter`/`blScanner` build details, and
[Download & Run](#download--run-pre-built-web-jars) /
[bBuilder](#bbuilder) / [bCounter](#bcounter) / [bScanner](#bscanner)
below for the web apps. For running the web apps (bBuilder/bCounter/bScanner)
in Docker, see **[docs/DOCKER.md](docs/DOCKER.md)** — a separate guide, since
containerizing a browser-UI app is a different concern from packaging a
desktop one.

---

## Data Directories

All pbss data is consolidated under a single root directory: `~/pbss_data/`.
This makes backup, migration, and management simple — one folder contains everything.
All subdirectories are created automatically on first run, by whichever app runs first
(web, JavaFX, or Swing — they all point at the same layout).

```
~/pbss_data/
  db/
    election_ballot.db      ← bBuilder/blBuilder/builder (elections, templates, combinations)
    counter_results.db      ← bCounter/blCounter/counter/viewer (scan results, votes)
    scanner.db              ← bScanner/blScanner/scanner (users, configuration)
  ballot_templates/         ← builder writes ballot PDFs and YAMLs here;
                               counter reads YAMLs from the same location
  cast_ballot_scans/        ← scanner deposits images here;
                               counter reads images from the same location
  reports/                  ← counter results (HTML, CSV, overvote report)
  writeins/                 ← counter write-in image crops
  scribbles/                ← counter scribble-detection outline images
  logs/
    bBuilder.log / blBuilder.log / builder.log
    bCounter.log / blCounter.log / counter.log
    bScanner.log / blScanner.log / scanner.log
```

All paths are configurable in each app's `application.properties` (or via
any of the [override mechanisms](#configuring-these-apps-property-overrides)
above). The table below shows the key configurable properties and their
defaults:

| Property | Default | App |
|---|---|---|
| `ballot.export.dir` | `~/pbss_data/ballot_templates` | builder / bBuilder |
| `scanner.default.image.dir` | `~/pbss_data/cast_ballot_scans` | counter / bCounter |
| `scanner.default.report.dir` | `~/pbss_data/ballot_templates` | counter / bCounter |
| `reports.output.dir` | `~/pbss_data/reports` | counter / bCounter |
| `data.writeins.dir` | `~/pbss_data/writeins` | counter / bCounter |
| `data.scribbles.dir` / `scanner.scribble-outline-dir` | `~/pbss_data/scribbles` | counter / bCounter |
| `scanner.output.dir` | `~/pbss_data/cast_ballot_scans` | scanner / bScanner |

---

## Repository Layout

```
pbss/
  builder/            Recommended: standalone Swing ballot-design tool (full CRUD + PDF generation)
  counter/            Recommended: standalone Swing counting control panel (folders, start/stop, results)
  scanner/             Recommended: standalone Swing scanner control panel (folder, start/stop, start/end notes)
  viewer/             Recommended: standalone Swing ballot image viewer, read-only
  builder-core/       Shared library: the ballot-design engine bBuilder/blBuilder/builder all depend on
  counter-core/       Shared library: the counting engine bCounter/blCounter/viewer/counter all depend on
  scanner-core/       Shared library: the scanner-driving engine bScanner/blScanner/scanner all depend on
  bBuilder/           Web version of builder                                (port 8080)
  bCounter/           Web version of counter                                (port 8081)
                      └── bViewer embedded                                  (port 8082)
  bScanner/           Web version of scanner                                (port 8083)
  blBuilder/          JavaFX desktop version of bBuilder
  blCounter/          JavaFX desktop version of bCounter
  blScanner/          JavaFX desktop version of bScanner
  db_merge.py         Multi-station counter_results.db viewer/merger (see Multi-Station Elections)
  test-harness/       Python automation scripts
  docs/               Documentation (SCHEMA.md, DOCKER.md, RACE_CONDITION.md)
```

---

## bBuilder

> Its model, repository, util, and generation services live in the shared
> [`builder-core`](builder-core/README.md) module — **run `mvn install` in
> `builder-core/` before building bBuilder for the first time.** Only the
> web controllers, Thymeleaf templates, and the
> `UserDetailsService`-implementing `UserService` stay local to bBuilder
> itself.

Provides a web UI for election administrators to:

- Define jurisdictions, elections, regions (precincts and precinct groups),
  parties, ballot types, and contests
- Assign contests to regions and candidates to contests
- Create ballot design templates (paper size, column count, font sizes, margins,
  vote indicator style)
- Generate ballot PDFs with machine-readable QR codes, orientation marks, and
  indicator offset YAML reports
- Manage users (ADMIN, DATA_ENTRY, PRINTER roles)

**Output files** (written to `~/pbss_data/ballot_templates/`):
- `ballot_*.pdf` — printable ballot
- `ballot_*.yaml` — indicator positions for bCounter

**Ballot features:**
- Vote indicator styles:
  - **Oval** — filled ellipse (recommended for optical scanning)
  - **Rectangle** — filled rectangle
  - **Connect dots** — voter draws a horizontal line between two pointed markers
- Ranked-choice voting: one filled box per rank per candidate; rank-1 box is
  widest and closest to the candidate name; rank numbers printed above boxes
- Write-in slots with centered fill lines
- Multi-page ballots with per-page QR codes
- QR code (1" square, top-right of each page) encodes:
  `JurisdictionId|RegionId|PartyId|BallotTypeId|ElectionId|Page`
- Six registration marks per page: two 18×9 pt page-level marks (PTL/PTR)
  inside the top margin, plus four content-box corner marks (TL rectangle,
  TR/BL/BR squares) just outside the content border
- Flexible HTML header zone: full HTML+CSS with inline images via data URIs,
  rendered by iText html2pdf; supports logos, seals, and custom voter instructions

**Admin password reset:**
```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dreset.admin.password=true"
```

---

## bCounter

Loads ballot image files (PNG, JPG, TIFF) from `~/pbss_data/cast_ballot_scans/`
by default, detects corners, warps to canonical coordinates, reads QR codes, and
samples each vote indicator for dark-pixel coverage.

> Its entities, repositories, model, and the counting/tallying services
> live in the shared [`counter-core`](counter-core/README.md) module —
> **run `mvn install` in `counter-core/` before building bCounter for the
> first time.** Only the web controllers, Thymeleaf templates, and the
> `UserDetailsService`-implementing `CounterUserService` stay local to
> bCounter itself.

**Key features:**
- Parallel scanning (configurable thread count)
- Upside-down ballot detection and auto-correction: corner detection always runs
  first; if the ballot is upside-down, the image is flipped before QR decoding
- QR-only identification — no linear barcode drawn or read
- Strategy interfaces for ballot identification (`BallotIdentifierService`) and
  corner detection (`BallotCornerDetectorService`) allow alternative implementations
  to be injected for non-pbss ballot formats
- Six-mark page geometry with geometric corner prediction
- Ranked-choice vote counting with IRV tabulation
- Connect-dots indicator detection (centered vertical stripe)
- Overvote detection per contest
- Write-in image extraction (column-accurate crop)
- Scribble detection via inter-ballot comparison (send one clean ballot first
  to seed the normative baseline)
- Periodic `results_report.html` written to `~/pbss_data/reports/`

**Scan start form defaults** (pre-populated, operator can override):
- Image folder: `~/pbss_data/cast_ballot_scans/`
- YAML layout folder: `~/pbss_data/ballot_templates/`
- Darkness threshold: 8% (dark pixel percentage to declare a mark)

If results already exist from a prior run, the "Start Counting" page shows a
**📋 Results** link and the date/time they were last generated.

**Roles:** bCounter and the embedded bViewer share a single `CounterUser`
account store (unified — there's no separate viewer credential). Default
seeded account: `admin` / `ChangeMe123!` (change via `/account/password` once
logged in). Roles:
- `ADMIN` — manage users and roles at `/admin`, plus full counter and viewer access
- `COUNTER_OPERATOR` — configure, start, and stop scanning/counting (port 8081)
- `VIEWER` — review scanned ballot images (port 8082)

**Configuration (`bCounter/src/main/resources/application.properties`):**
```properties
server.port=8081
viewer.server.port=8082
scanner.parallel-threads=0        # 0 = auto (half CPU cores)
scanner.max-review-before-stop=20 # stop after N corner-detection failures; 0 = no limit
scanner.scribble-detection=true
scanner.scribble-indicator-pad-in=0.1  # tolerance zone around each indicator
                                        # (inches) so a slight overflow when
                                        # filling it isn't flagged as a scribble
```

**Report files:**
| File | Location | When written |
|---|---|---|
| `results_report.html` | `~/pbss_data/reports/` | Every N images + scan end |
| `rcv_report.html` | `~/pbss_data/reports/` | Scan end (RCV contests) |
| `overvote_report.txt` | `~/pbss_data/reports/` | Scan end |
| `review_required.txt` | `~/pbss_data/reports/` | Scan end (if failures) |
| `ballot_manifest.csv` | `~/pbss_data/reports/` | Scan end (Arlo RLA) |
| `cvr_export.csv` | `~/pbss_data/reports/` | Scan end (Arlo RLA) |
| `vote_summary.yaml` | image folder | Scan end |
| `*.png.review` | image folder | Per-image (corner detection failure) |
| `writein_report.html` | `~/pbss_data/reports/` | Scan end (marked write-ins only) |
| `scribble_report.html` | `~/pbss_data/reports/` | Scan end (if any ballots flagged) |
| write-in crops | `~/pbss_data/writeins/` | Scan end |
| scribble outlines | `~/pbss_data/scribbles/` | Scan end |

### bViewer (embedded in bCounter)

Opens ballot images with colour-coded vote indicator overlays:
- 🟢 Green — VOTED / MARKED
- 🟡 Amber — OVERVOTED
- 🔵 Blue — UNMARKED

Accessible at `http://localhost:8082/viewer` while bCounter is running.
Ballot list and image views support name/glob and SQL filters, and toggles to
show/hide box outlines and candidate names.

`http://localhost:8082/results` shows the same results page as
`http://localhost:8081/results` (no separate viewer-side report), with a
**← Ballot Viewer** link back to the ballot list.

---

## bScanner

Any scanning software can be used with pbss — simply configure it to deposit
images into `~/pbss_data/cast_ballot_scans/` and bCounter will find them
automatically. bScanner is included as a convenience: a lightweight web UI that
drives a physical document scanner (NAPS2, scanimage, or a custom command)
without requiring any additional software configuration.

> Its entity, repository, model, `ScanService`, and `ScannerConfig` live in
> the shared [`scanner-core`](scanner-core/README.md) module — **run
> `mvn install` in `scanner-core/` before building bScanner for the first
> time.** Only the web controllers, Thymeleaf templates, and the
> `UserDetailsService`-implementing `ScannerUserDetailsService` stay local
> to bScanner itself.

**Supported backends:**
- **NAPS2** (macOS/Windows) — recommended; supports profiles, device selection, duplex
- **scanimage** (Linux) — SANE-based; ADF and duplex supported
- **Custom command** — any shell command; use `{output}` as the output path placeholder

**Roles:** ADMINISTRATOR (full config access) and OPERATOR (scan only).

**Configuration** (`bScanner/src/main/resources/application.properties`):
```properties
server.port=8083
scanner.backend=naps2
scanner.output.dir=${user.home}/pbss_data/cast_ballot_scans
scanner.dpi=300
scanner.duplex=true
scanner.source=duplex
```

---

## Vote Indicator Styles

| Style | Description | bCounter detection |
|---|---|---|
| **Oval** | Filled ellipse | Dark pixel % in inset sampling region |
| **Rectangle** | Filled rectangle | Dark pixel % in inset sampling region |
| **Connect dots** | Draw line between markers | Any dark pixel in centered 10%-wide vertical stripe |

Ranked-choice contests always use rank boxes regardless of the template's
indicator style. Connect-dots is not available for ranked-choice contests.

---

## Databases

All apps use SQLite by default. bBuilder also supports PostgreSQL.

| App | File | Default location |
|---|---|---|
| bBuilder | `election_ballot.db` | `~/pbss_data/db/` |
| bCounter | `counter_results.db` | `~/pbss_data/db/` |
| bScanner | `scanner.db` | `~/pbss_data/db/` |

For full schema documentation see `docs/SCHEMA.md`.

---

## Test Harness (Web Apps)

The `test-harness/` directory contains a Python automation suite that exercises
the full pipeline end-to-end against the web apps over HTTP. See
`test-harness/README.md` for full details, or
[Using builder / counter / scanner / viewer with test-harness](#using-builder--counter--scanner--viewer-with-test-harness)
above for the equivalent workflow against the recommended desktop apps, and
`test-harness/README-desktop.md` for the desktop GUI-automation pipeline
specifically.

### Quick run
```bash
# With bBuilder (8080) and bCounter (8081) already running:
cd test-harness
./run_all.sh
```

### Scripts

| Script | Purpose |
|---|---|
| `build_election.py` | Creates a complete test election in bBuilder via `/api/test/*` REST API; generates ballot PDFs and YAMLs to `~/pbss_data/ballot_templates/` |
| `mark_ballots.py` | Rasterizes ballot PDFs and applies voting scenarios. Supports `--ballot-pdf`/`--ballot-yaml` to use an existing bBuilder ballot directly |
| `distort_ballots.py` | Applies 15 geometric distortions (rotations, translations, skew, perspective, upside-down) × N copies per marked ballot |
| `run_counter.py` | Drives bCounter via HTTP: logs in, sets image and YAML folders, starts scanning, polls progress, waits for completion |
| `verify_results.py` | **Essential** — queries `counter_results.db` and diffs against `ground_truth_all.json`; prints summary report and writes `verify_report.json` |
| `rcv_tabulate.py` | Standalone IRV tabulator — reads `counter_results.db` and runs ranked-choice elimination rounds independently of bCounter's built-in tabulation |
| `test_template_fields.py` | Unit-tests bBuilder template fields: creates elections, prints ballots, asserts PDF output matches expectations |
| `test_rcv_two_contests.py` | Integration test for two-contest RCV: verifies IRV requires exactly two elimination rounds to produce a winner |
| `check_setup.py` | Verifies all Python dependencies are installed before running the harness |
| `run_all.sh` | Full pipeline: build → mark → distort → scan → verify |
| `reset_scan.sh` | Resets bCounter database and restores image filenames (`.counted` → `.png`) |

### What `run_all.sh` does
1. Runs `build_election.py` to create election in bBuilder
2. Runs `mark_ballots.py` to produce marked ballot PNGs with ground truth
3. Runs `distort_ballots.py` to produce distorted variants
4. Runs `run_counter.py` to drive bCounter through the full image set
5. Runs `verify_results.py` to diff DB results against ground truth

**Voting scenarios tested:** valid filled ovals, X marks, checkmarks, partial
marks, marks outside the box, messy marks, write-ins, ranked-choice, overvotes.

---

## User Roles

### bBuilder
| Role | Permissions |
|---|---|
| `ADMIN` | All: users, data entry, print ballots, audit log |
| `DATA_ENTRY` | Enter/edit election data; no printing |
| `PRINTER` | Generate ballot PDFs; read-only data access |

### bCounter / bViewer
bCounter and bViewer share a single user database (separate from bBuilder) —
one account can hold any combination of the roles below. Manage users and
roles at `/admin` (ADMIN only); change your own password at `/account/password`.

| Role | Permissions |
|---|---|
| `ADMIN` | Manage users and roles; full access to both bCounter and bViewer |
| `COUNTER_OPERATOR` | Configure, start, and stop scanning/counting (port 8081) |
| `VIEWER` | Review scanned ballot images (port 8082) |

### bScanner
| Role | Permissions |
|---|---|
| `ADMINISTRATOR` | Full access: config, users, scanning |
| `OPERATOR` | Scan only |

---

## Download & Run (Pre-Built Web JARs)

Pre-built JAR files are available on the [Releases page](https://github.com/mjtrac/pbss/releases).
You need only **Java 21 or later** — no Maven, no git, no IDE.

### 1. Install Java 21+

- **macOS:** `brew install openjdk@21` or download from [adoptium.net](https://adoptium.net)
- **Windows:** Download from [adoptium.net](https://adoptium.net) and run the installer
- **Linux:** `sudo apt install openjdk-21-jre` or equivalent

Verify: `java -version` should show 21 or higher.

### 2. Download the JARs

From the [latest release](https://github.com/mjtrac/pbss/releases/latest), download:
- `bBuilder-0.9.14.jar`
- `bCounter-0.9.14.jar`
- `bScanner-0.9.14.jar` (optional — only needed if driving a physical scanner)

### 3. Run

Open two terminal windows:

```bash
# Terminal 1 — Ballot Designer
java -jar bBuilder-0.9.14.jar
# Opens at http://localhost:8080

# Terminal 2 — Scanner, Counter & Viewer
java -jar bCounter-0.9.14.jar
# Opens at http://localhost:8081
# Ballot image viewer at http://localhost:8082/viewer/

# Terminal 3 (optional) — Physical scanner driver
java -jar bScanner-0.9.14.jar
# Opens at http://localhost:8083
```

All three apps create their data directories automatically on first run — no
manual setup required.

### 4. Configure (optional)

See [Configuring These Apps](#configuring-these-apps-property-overrides)
above for the full set of override mechanisms. Quick example:

```bash
java -jar bCounter-0.9.14.jar \
  --scanner.default.image.dir=/path/to/scans \
  --viewer.server.port=8082
  # Change the seeded admin/ChangeMe123! password at /account/password after first login.

java -jar bBuilder-0.9.14.jar \
  --ballot.export.dir=/path/to/ballot/output \
  --app.login-title="My County Election System"
```

### macOS launcher (optional)

Download `pbss_app.zip` from the release, unzip it, place `pbss.app` in the
same folder as the JARs, then:

```bash
# One-time: remove macOS quarantine
xattr -cr /path/to/pbss.app

# Launch
open /path/to/pbss.app
```

The launcher starts bBuilder and bCounter with a double-click and provides
Start/Stop controls.

---

## Quick Start (Development / Source Build)

> **Just want to run pbss?** See [Building the recommended apps](#building-the-recommended-apps)
> above, or [Download & Run](#download--run-pre-built-web-jars) for pre-built web JARs.

### Requirements
- Java 21+
- Maven 3.9+ (or use the included `mvnw` wrapper)
- Python 3.9+ (for the test harness only)

### Run bBuilder
```bash
cd bBuilder
./mvnw spring-boot:run
# Open browser: http://localhost:8080
# Default login: admin / ChangeMe123!
```

### Run bCounter (includes bViewer on port 8082)
```bash
cd bCounter
./mvnw spring-boot:run
# bCounter: http://localhost:8081
# bViewer:  http://localhost:8082  (login: admin / ChangeMe123!)
```

### Run bScanner (optional)
```bash
cd bScanner
./mvnw spring-boot:run
# bScanner: http://localhost:8083
# Default login: admin / ChangeMe123!
```

---

## macOS Launcher (pbss.app)

`pbss.app` is a double-click launcher that starts bBuilder and bCounter
from a simple menu — no Terminal required for normal use.

### Installation

Place `pbss.app` inside the `pbss/` folder, next to `bBuilder/` and `bCounter/`:

```
pbss/
  bBuilder/
  bCounter/
  bScanner/
  test-harness/
  pbss.app       ← put it here
```

### First-run: remove macOS quarantine

```bash
xattr -cr /Users/yourname/pbss/pbss.app
```

### Menu options

| Option | What it does |
|---|---|
| **Start All** | Starts bBuilder and bCounter; opens both in browser when ready |
| Start bBuilder only | Starts bBuilder alone |
| Start bCounter only | Starts bCounter + bViewer |
| Open bBuilder/bCounter/bViewer in Browser | Opens the URL without starting |
| **Run Test Harness…** | Prompts for Full run or Rescan only |
| Stop All | Stops both services |
| Quit | Optionally stops services before exiting |

### Logs

All logs go to `~/pbss_data/logs/`:
- `bBuilder.log`
- `bCounter.log`
- `bScanner.log`

---

## Building Distributable Web JARs

```bash
# bBuilder
cd bBuilder
./mvnw clean package -DskipTests
# → target/bBuilder-0.9.14.jar

# bCounter (includes bViewer on port 8082)
cd bCounter
./mvnw clean package -DskipTests
# → target/bCounter-0.9.14.jar

# bScanner
cd bScanner
./mvnw clean package -DskipTests
# → target/bScanner-0.9.14.jar
```

Run without Maven:
```bash
java -jar bBuilder/target/bBuilder-0.9.14.jar
java -jar bCounter/target/bCounter-0.9.14.jar
java -jar bScanner/target/bScanner-0.9.14.jar
```

### Publishing a GitHub Release

```bash
git tag -a v0.9.14 -m "pbss v0.9.14"
git push origin v0.9.14
# Releases → Draft a new release → attach JARs and pbss_app.zip
```

### Running the web apps in Docker

See **[docs/DOCKER.md](docs/DOCKER.md)** for containerizing
bBuilder/bCounter/bScanner.

---

## Native Desktop Versions

`blBuilder/`, `blCounter/`, and `blScanner/` are JavaFX desktop rewrites of
`bBuilder/`, `bCounter/`, and `bScanner/` — same data model, same
`~/pbss_data/` directories and database files (fully interchangeable with
the web versions, including mid-election), but a native desktop UI instead
of a browser. Spring Boot runs headless (`WebApplicationType.NONE`) purely
for dependency injection, JPA, and password hashing; JavaFX drives the UI
directly. The one exception is blCounter's Ballot Viewer screen, which
embeds the original web Viewer (unmodified) in a JavaFX `WebView` against a
small embedded server bound to `127.0.0.1` only — see
[`blCounter/README.md`](blCounter/README.md) for that screen's known
rendering limitation.

`viewer/`, `counter/`, `scanner/`, and `builder/` — the four apps
recommended at the top of this README — are the Swing alternative: see
[Recommended: Desktop Programs](#recommended-desktop-programs-builder-counter-scanner-viewer)
above for what each does, and each app's own README
([`viewer/README.md`](viewer/README.md), [`counter/README.md`](counter/README.md),
[`scanner/README.md`](scanner/README.md), [`builder/README.md`](builder/README.md))
for implementation detail and feature-scope tables against their `b`/`bl`
counterparts.

`bCounter/`, `bScanner/`, and `bBuilder/` are the three exceptions to
"originals stay untouched": each had accumulated genuinely identical
copies of the same entity/repository/model/service code across its web,
JavaFX, and Swing versions, so that code was extracted into shared
libraries — [`counter-core/`](counter-core/README.md) (bCounter,
blCounter, viewer, counter), [`scanner-core/`](scanner-core/README.md)
(bScanner, blScanner, scanner), and
[`builder-core/`](builder-core/README.md) (bBuilder, blBuilder, builder)
— which their consumers now depend on rather than each carrying their own
copy. This is the first time original web apps in this project have been
modified rather than left alone as references — see each `-core` module's
README for what moved, what deliberately stayed local to each app (mainly
login-related code, since the web apps'
Spring-Security-based auth needs diverge from their JavaFX/Swing
counterparts), and the before/after verification that confirmed zero
behavior change. **You must `mvn install` from `counter-core/`,
`scanner-core/`, and `builder-core/` before building any of their
consumers** — `package_all_desktop.sh` does this automatically; the
manual steps are below.

### Run from source (development)

```bash
# One-time (and after any counter-core/scanner-core/builder-core change): install the shared engines
cd counter-core && mvn install -DskipTests && cd ..
cd scanner-core && mvn install -DskipTests && cd ..
cd builder-core && mvn install -DskipTests && cd ..

cd blBuilder   # or blCounter, blScanner, viewer, counter, scanner, builder
./mvnw javafx:run     # viewer/counter/scanner/builder have no JavaFX UI — use ./mvnw spring-boot:run instead
```

### Build a standalone desktop program (manual steps)

`package_all_desktop.sh` (see
[Building the recommended apps](#building-the-recommended-apps) above) does
all of this automatically for any app in this repo, recommended or not.
The manual version, for reference:

```bash
cd blCounter   # or blBuilder, blScanner, viewer, counter, scanner, builder
./mvnw clean package -DskipTests
cp target/<app>-<version>.jar target/lib/

jlink \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.base,java.desktop,java.sql,java.net.http,java.naming,java.security.jgss,jdk.crypto.ec,java.logging,java.management,jdk.unsupported,java.instrument,java.scripting \
  --output target/app-jre \
  --strip-debug --no-header-files --no-man-pages --compress=2

jpackage \
  --input target/lib \
  --name blCounter \
  --main-jar <app>-<version>.jar \
  --main-class <fully.qualified.Launcher> \
  --runtime-image target/app-jre \
  --type app-image \
  --app-version 1.0.0 \
  --dest target/dist
```

viewer's, counter's, scanner's, and builder's module lists drop
`java.scripting` (only needed elsewhere for JavaFX's `FXMLLoader`, which
none of the four — being Swing, not JavaFX — use). counter's and
builder's module lists add `java.xml` (for `BboxReportLoader`'s
XML-format layout support and builder's PDF/export libraries,
respectively); viewer's and scanner's do not need it.

`--type app-image` produces the platform's native equivalent of a
standalone program on whichever OS `jpackage` is run on — it does not
cross-compile, so building a Windows package requires running this on
Windows, a Linux package on Linux, and so on:

| Platform | `--type app-image` result | Installer alternative |
|---|---|---|
| macOS | `<Name>.app` bundle | `--type dmg` / `pkg` |
| Linux | a folder with a launcher binary + bundled runtime | `--type deb` / `rpm` |
| Windows | a folder with a `.exe` launcher + bundled runtime | `--type exe` / `msi` |

Each app's `pom.xml` already disables `spring-boot-maven-plugin`'s
repackage step and uses `maven-dependency-plugin` to flatten dependencies
into `target/lib/` — `jpackage`'s `app-image` mode needs a plain classpath,
not Spring Boot's nested fat-jar layout. Pass `--version` to any built app
(e.g. `blCounter.app/Contents/MacOS/blCounter --version` on macOS) to print
its version without launching the UI — note this is the app's own version
(`0.9.14`), distinct from `--app-version 1.0.0` above, which is `jpackage`'s
version number for the native installer/bundle itself.

---

## Security Notes

| Library | License | Notes |
|---|---|---|
| Spring Boot 3.x | Apache 2.0 | Monitor https://spring.io/security |
| iText 8 + html2pdf | **AGPL 3.0** | AGPL requires open source or commercial license. Government internal use generally satisfies AGPL — confirm with legal. |
| ZXing 3.5.x | Apache 2.0 | QR code decoding |
| SnakeYAML 2.x | Apache 2.0 | **Use 2.x only** — 1.x had critical RCE (CVE-2022-1471) |
| SQLite JDBC 3.45.x | Apache 2.0 | Restrict OS permissions on `.db` files to app user |
| PostgreSQL JDBC 42.x | BSD 2-Clause | Keep updated |
| BCrypt (Spring Security) | Apache 2.0 | Cost factor 12 |

**Application-level protections:** CSRF tokens, Content-Security-Policy,
X-Frame-Options: DENY, session fixation protection, BCrypt password hashing,
parameterised JPA queries, Thymeleaf HTML auto-escaping.

---

## License

GNU General Public License v3 or later.
See `LICENSE` or https://www.gnu.org/licenses/ for the full text.

Copyright (C) 2026 Mitch Trachtenberg
