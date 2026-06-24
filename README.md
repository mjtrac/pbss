<!--
  Copyright (C) 2026 Mitch Trachtenberg
  Election Ballot System — licensed under the GNU General Public License v3.
  See <https://www.gnu.org/licenses/> for the full license text.
-->

# bSuite — Election Ballot Management System

bSuite is an open-source suite for anyone in or out of government to design, print, scan, and audit paper election ballots.  It consists of two Spring Boot
applications that can be run independently or together:

| App | Port | Purpose |
|---|---|---|
| **bBuilder** | 8080 | Design elections, enter candidates, generate ballot PDFs and YAML layout files |
| **bCounter** | 8081 | Scan ballot images, count votes, report results |
| **bViewer** | 8082 | Review scanned ballot images with vote-indicator overlays (runs inside bCounter) |

---

## Download & Run (No Development Tools Required)

Pre-built JAR files are available on the [Releases page](https://github.com/mjtrac/bSuite/releases).
You need only **Java 21 or later** — no Maven, no git, no IDE.

### 1. Install Java 21+

- **macOS:** `brew install openjdk@21` or download from [adoptium.net](https://adoptium.net)
- **Windows:** Download from [adoptium.net](https://adoptium.net) and run the installer
- **Linux:** `sudo apt install openjdk-21-jre` or equivalent

Verify: `java -version` should show 21 or higher.

### 2. Download the JARs

From the [latest release](https://github.com/mjtrac/bSuite/releases/latest), download:
- `bBuilder-1.0.0.jar`
- `bCounter-1.0.0.jar`

### 3. Run

Open two terminal windows:

```bash
# Terminal 1 — Ballot Designer
java -jar bBuilder-1.0.0.jar
# Opens at http://localhost:8080

# Terminal 2 — Scanner, Counter & Viewer
java -jar bCounter-1.0.0.jar
# Opens at http://localhost:8081
# Ballot image viewer at http://localhost:8082/viewer/
```

### 4. Configure (optional)

Override any setting on the command line:

```bash
java -jar bCounter-1.0.0.jar \
  --reports.output.dir=/path/to/reports \
  --viewer.password=YourPassword \
  --viewer.server.port=8082

java -jar bBuilder-1.0.0.jar \
  --ballot.export.dir=/path/to/ballot/output \
  --app.login-title="My County Election System"
```

Or create an `application.properties` file in the same directory as the JAR:

```properties
# bCounter — place alongside bCounter-1.0.0.jar
reports.output.dir=/data/election/reports
viewer.password=ChangeMe123!
app.login-title=My County Election System
```

### macOS launcher (optional)

Download `bSuite_app.zip` from the release, unzip it, place `bSuite.app` in the
same folder as both JARs, then:

```bash
# One-time: remove macOS quarantine
xattr -cr /path/to/bSuite.app

# Launch
open /path/to/bSuite.app
```

The launcher starts both JARs with a double-click and provides Start/Stop controls.

---

## Quick Start (Development / Source Build)

> **Just want to run bSuite?** See [Download & Run](#download--run-no-development-tools-required) above.

### Requirements
- Java 21+
- Maven 3.9+ (or use the included `mvnw` wrapper)
- Python 3.9+ (for the test harness only)

### Run bBuilder
```bash
cd bBuilder
./mvnw spring-boot:run
# Open browser: http://localhost:8080
# Default login: admin / (generated on first run — check stdout)
```

### Run bCounter (includes bViewer on port 8082)
```bash
cd bCounter
./mvnw spring-boot:run
# bCounter: http://localhost:8081
# bViewer:  http://localhost:8082  (login: admin / ChangeMe123!)
```

### Configure output directories

bBuilder writes ballot PDFs and YAML layout files to `~/bBuilder_ballots/` by
default. (If you want people to be able to count votes on the ballots using
a different system, you should distribute bBuilder's PDFs and yaml files to simplify their lives.)
Override in `bBuilder/src/main/resources/application.properties`:
```properties
ballot.export.dir=/path/to/your/output/dir
```

bCounter writes its reports to the working directory by default. Override:
```properties
reports.output.dir=/path/to/your/reports/dir
reports.interval=500   # write results_report.html every N images
```

---

## Repository Layout

```
bSuite/
  bBuilder/           Ballot design + PDF generation          (port 8080)
  bCounter/           Ballot scanning + vote counting         (port 8081)
                      └── bViewer embedded                    (port 8082)
  test-harness/       Python automation scripts
  docs/               Documentation (SCHEMA.md, RACE_CONDITION.md)
```

---

## bBuilder

Provides a web UI for election administrators to:

- Define jurisdictions, elections, regions (precincts and precinct groups),
  parties, ballot types, and contests
- Assign contests to regions and candidates to contests
- Create ballot design templates (paper size, column count, font sizes, margins,
  vote indicator style)
- Generate ballot PDFs with machine-readable QR+Code128 barcodes, orientation
  marks, and indicator offset YAML/XML reports
- Manage users (ADMIN, DATA_ENTRY, PRINTER roles)

**Output files** (written to `ballot.export.dir`):
- `ballot_*.pdf` — printable ballot
- `ballot_*.yaml` — indicator positions for bCounter
- `ballot_*.xml` — same positions in XML

**Ballot features:**
- Vote indicator styles: OVAL (recommended for scanning), CHECKBOX
- Ranked-choice: one filled box per rank per candidate; rank 1 box is widest
- Write-in slots with fill lines
- Multi-page ballots with sheet numbering
- Barcodes encode: `JurisdictionId|RegionId|PartyId|BallotTypeId|ElectionId|Page`

### Admin password reset
```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dreset.admin.password=true"
# Prints a new random password to stdout and exits
```

---

## bCounter

Scans ballot image files (PNG, JPG, TIFF), detects corners, warps to
canonical coordinates, reads barcodes, and samples each vote indicator
for dark-pixel coverage.

**Key features:**
- Parallel scanning (configurable thread count)
- Upside-down ballot detection and auto-rotation
- Ranked-choice vote detection (filled rank boxes)
- Overvote detection per contest
- Write-in image extraction
- Race-condition retry queue (see `docs/RACE_CONDITION.md`)
- Periodic `results_report.html` written to `reports.output.dir`

**Configuration (`bCounter/src/main/resources/application.properties`):**
```properties
server.port=8081
viewer.server.port=8082
viewer.username=admin
viewer.password=ChangeMe123!
reports.output.dir=${user.dir}
reports.interval=500
scanner.parallel-threads=0   # 0 = auto (half CPU cores)
spring.datasource.hikari.maximum-pool-size=1
```

**Report files:**
| File | Written to | When |
|---|---|---|
| `results_report.html` | `reports.output.dir` | Every `reports.interval` images + scan end |
| `overvote_report.txt` | `reports.output.dir` | Scan end |
| `review_required.txt` | `reports.output.dir` | Scan end (if any) |
| `vote_summary.yaml` | image folder | Scan end |

### bViewer (embedded in bCounter)

Opens ballot images with colour-coded vote indicator overlays:
- 🟢 Green — VOTED / MARKED
- 🟡 Yellow — OVERVOTED
- 🔵 Blue — UNMARKED

Accessible at `http://localhost:8082` while bCounter is running. Uses bCounter's
database read-only — no separate process or database needed.

---

## Databases

Both apps use SQLite by default. bBuilder also supports PostgreSQL.

| App | File | Default location |
|---|---|---|
| bBuilder | `election_ballot.db` | `bBuilder/` working directory |
| bCounter | `counter_results.db` | `bCounter/` working directory |

For full schema documentation see `docs/SCHEMA.md`.

---

## Test Harness

The `test-harness/` directory contains a Python automation suite that exercises
the full pipeline end-to-end. See `test-harness/README.md` for full details.

### Quick run
```bash
# With bBuilder (port 8080) and bCounter (port 8081) already running:
cd test-harness
./run_all.sh
```

### What it does
1. **`build_election.py`** — creates a complete test election in bBuilder via the
   `/api/test/*` REST API; generates 6 ballot combinations (3 precincts × 2 parties)
   plus 2 large-header variants, writing PDFs and YAMLs to `~/bBuilder_ballots/`
2. **`mark_ballots.py`** — rasterizes ballot PDFs and applies 8 voting scenarios
   (valid fills, X marks, checkmarks, overvotes, write-ins, ranked-choice, etc.)
3. **`distort_ballots.py`** — applies 15 geometric distortions × N copies
4. **`run_counter.py`** — drives bCounter to scan all images
5. **`verify_results.py`** — compares scan results to ground truth

**Other tools:**
- `rcv_tabulate.py` — instant-runoff voting (IRV) tabulation from the database
- `db_merge.py` — GUI for comparing and merging results from multiple scan stations
- `reset_scan.sh` — clears the counter database and restores image filenames for rescan

---

## User Roles

### bBuilder
| Role | Permissions |
|---|---|
| `ADMIN` | All: users, data entry, print ballots, audit log |
| `DATA_ENTRY` | Enter/edit election data; no printing |
| `PRINTER` | Generate ballot PDFs; read-only data access |

### bCounter / bViewer
bCounter uses its own user database (separate from bBuilder).
bViewer uses a simple in-memory user configured via `viewer.username` /
`viewer.password` in `application.properties`.

---


---

## macOS Launcher (bSuite.app)

`bSuite.app` is a double-click launcher that starts bBuilder, bCounter, and
the test harness from a simple menu — no Terminal required for normal use.

### Installation

Place `bSuite.app` inside the `bSuite/` folder, next to `bBuilder/` and
`bCounter/`:

```
bSuite/
  bBuilder/
  bCounter/
  test-harness/
  bSuite.app       ← put it here
```

### First-run: remove macOS quarantine

macOS blocks apps downloaded from the internet with a quarantine flag.
Remove it before the first launch:

```bash
xattr -cr /Users/yourname/bSuite/bSuite.app
```

This is a one-time step. Without it, macOS will refuse to open the app with
a message like "bSuite cannot be opened because it is from an unidentified
developer."

### Starting the launcher

```bash
open /Users/yourname/bSuite/bSuite.app
```

Or double-click `bSuite.app` in Finder after running the `xattr` command above.

### Menu options

| Option | What it does |
|---|---|
| **Start All** | Starts bBuilder (8080) and bCounter (8081/8082) together; opens both in the browser when ready |
| Start bBuilder only | Starts bBuilder alone |
| Start bCounter only | Starts bCounter + bViewer |
| Open bBuilder/bCounter/bViewer in Browser | Opens the URL without starting |
| **Run Test Harness…** | Prompts for Full run or Rescan only, then opens a Terminal window with live output |
| Stop All | Stops both services |
| Quit | Optionally stops services before exiting |

### Logs

Each service logs to a file in the bSuite root:
- `bBuilder.log` — bBuilder output
- `bCounter.log` — bCounter/bViewer output

## Building Distributable JARs

Build self-contained "fat JARs" that include all dependencies:

```bash
# bBuilder
cd bBuilder
./mvnw clean package -DskipTests
# → target/bBuilder-1.0.0.jar  (~80 MB)

# bCounter (includes bViewer on port 8082)
cd bCounter
./mvnw clean package -DskipTests
# → target/bCounter-1.0.0.jar  (~80 MB)
```

Run without Maven:

```bash
java -jar bBuilder/target/bBuilder-1.0.0.jar
java -jar bCounter/target/bCounter-1.0.0.jar
```

### Publishing a GitHub Release

```bash
# Tag the release
git tag -a v1.0.0 -m "bSuite v1.0.0"
git push origin v1.0.0

# Then on GitHub:
# Releases → Draft a new release → select tag → attach:
#   bBuilder/target/bBuilder-1.0.0.jar
#   bCounter/target/bCounter-1.0.0.jar
#   bSuite_app.zip  (macOS launcher)
```

Users download the JARs from the Releases page and run them with `java -jar`.

### Packaging with bundled JRE (no JDK on target machine)

```bash
jlink \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.base,java.desktop,java.sql,java.net.http,java.naming,java.security.jgss,jdk.crypto.ec \
  --output ./bsuite-jre \
  --strip-debug --compress=2 --no-header-files --no-man-pages

jpackage \
  --input target \
  --name "bCounter" \
  --main-jar bCounter-1.0.0.jar \
  --runtime-image ./bsuite-jre \
  --type dmg          # macOS; use exe (Windows) or deb/rpm (Linux)
```

---

## Security Notes

| Library | License | Notes |
|---|---|---|
| Spring Boot 3.x | Apache 2.0 | Monitor https://spring.io/security |
| iText 8 | **AGPL 3.0** | AGPL requires open source or commercial license. Government internal use generally satisfies AGPL — confirm with legal. |
| ZXing 3.5.x | Apache 2.0 | Barcodes (QR + Code128) |
| SnakeYAML 2.x | Apache 2.0 | **Use 2.x only** — 1.x had critical RCE (CVE-2022-1471) |
| SQLite JDBC 3.45.x | Apache 2.0 | Restrict OS permissions on `.db` file to app user |
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
