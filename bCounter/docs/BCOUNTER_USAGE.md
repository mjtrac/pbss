<!--
  Copyright (C) 2026 Mitch Trachtenberg
  Election Ballot System — licensed under the GNU General Public License v3.
  See <https://www.gnu.org/licenses/> for the full license text.
-->

# bCounter — Usage Guide

bCounter is the ballot scanning and vote-counting application in the bSuite
election management system. It runs as a single Java process that serves two
web interfaces on separate ports.

---

## Starting bCounter

### From the source tree (development)

```bash
cd /path/to/bSuite/bCounter
./mvnw spring-boot:run
```

Use `clean` when you have changed source files and want to ensure a full
recompile:

```bash
./mvnw clean spring-boot:run
```

### From a packaged JAR (deployment)

```bash
java -jar bCounter-1.0.0.jar
```

### Configuration overrides at startup

Any `application.properties` setting can be overridden on the command line:

```bash
# Change the reports output directory
./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dreports.output.dir=/data/election/reports"

# Change the viewer password
./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dviewer.password=MySecurePassword"
```

---

## Ports and Web Interfaces

bCounter starts **two HTTP servers in the same process**:

| Port | Interface | Purpose |
|------|-----------|---------|
| **8081** | bCounter | Scan configuration, progress, results |
| **8082** | bViewer | Browse scanned ballot images with vote overlays |

Both ports start together and stop together — there is no way to run one
without the other.

### Verifying startup

Look for this line in the console output:

```
Tomcat started on ports 8081 (http), 8082 (http) with context path '/'
```

If you see only one port, or a `Address already in use` error, another
process is occupying that port.

---

## Accessing bCounter in a Browser

### bCounter (scanning and results) — port 8081

| URL | What you see |
|-----|-------------|
| `http://localhost:8081/` | Scan configuration page |
| `http://localhost:8081/login` | Login page |
| `http://localhost:8081/scan` | Live scanning progress |
| `http://localhost:8081/results` | Vote totals by contest |

**Default credentials:** `admin` / (set by `DataInitializer` on first run —
printed to the console). The admin password can be reset by restarting with
`-Dreset.admin.password=true`.

After a successful scan, the configuration page sidebar shows:
- **📋 Results** — opens the vote totals page
- **🔍 View Scanned Ballots** — opens bViewer in a new tab

### bViewer (ballot image review) — port 8082

| URL | What you see |
|-----|-------------|
| `http://localhost:8082/viewer/login` | Viewer login page |
| `http://localhost:8082/login` | Redirects to `/viewer/login` automatically |
| `http://localhost:8082/viewer/` | List of all scanned ballot images |
| `http://localhost:8082/viewer/view?id=N` | Single ballot with vote overlays |

**Default credentials:** `admin` / `ChangeMe123!`

Change these in `bCounter/src/main/resources/application.properties`:

```properties
viewer.username=admin
viewer.password=YourSecurePassword
```

**Overlay colours:**
- 🟢 Green — VOTED or MARKED (ranked choice)
- 🟡 Yellow — OVERVOTED
- 🔵 Blue — UNMARKED

**Note:** bViewer reads `counter_results.db` in real time. Images scanned
during an active count appear in the list as soon as they are written to the
database — refresh the list to see new arrivals. Images are served directly
from the filesystem path stored in the database, so bCounter must be running
on the same machine as the image files.

---

## Output Files

All report files are written to `reports.output.dir` (default: the directory
where bCounter was launched from, i.e. `bSuite/bCounter/`).

| File | Written when | Contents |
|------|-------------|---------|
| `results_report.html` | Every 500 images + scan end | Printable vote totals |
| `rcv_report.html` | Scan end (if ranked-choice contests exist) | IRV round-by-round tabulation |
| `overvote_report.txt` | Scan end | List of overvoted ballot/contest pairs |
| `review_required.txt` | Scan end (if any flagged) | Images needing manual review |
| `vote_summary.yaml` | Scan end | Structured totals (machine-readable) |

Override the output directory:

```properties
# in application.properties
reports.output.dir=/path/to/your/reports
```

---

## Running a Scan

1. Start bCounter (`./mvnw spring-boot:run`)
2. Log in at `http://localhost:8081/`
3. Enter the **Ballot Image Folder** — the directory tree containing scanned
   ballot PNG/TIFF/JPG files
4. Enter the **Bounding-Box Report Folder** — the directory containing the
   YAML layout files exported by bBuilder (default: `~/bBuilder_ballots/`)
5. Adjust threshold settings if needed (defaults work for most scans)
6. Click **▶ Start Scanning**

During the scan:
- The progress page shows images processed, current file, and a progress bar
- **🔍 View Scanned Ballots** appears once 100 images or 10% of the total
  have been processed — click to open bViewer in a new tab
- `results_report.html` is updated every 500 images

After the scan completes, all report files are written and a **📊 View
Report** link appears on the progress page.

---

## Conflicts with the Test Harness

The test harness (`test-harness/run_all.sh` and `run_counter.py`) drives
bCounter programmatically via its HTTP API. There are a few important
constraints:

### Do not run bCounter and the test harness simultaneously on different
machines pointing at the same database

`counter_results.db` is a SQLite file. SQLite supports only one writer at a
time. Running two bCounter instances against the same database file will
cause `SQLITE_BUSY` or `SQLITE_LOCKED` errors.

### Delete the database before restarting a scan

If you delete `counter_results.db` while bCounter is running (e.g. by
running `reset_scan.sh`), bCounter will lose its database connection and
subsequent writes will fail with `SQLITE_READONLY_DBMOVED`.

**Always restart bCounter after running `reset_scan.sh`.**

The correct sequence for a fresh test run is:

```bash
# 1. Stop bCounter (Ctrl+C)
cd /path/to/bSuite/test-harness
./reset_scan.sh

# 2. Restart bCounter
cd /path/to/bSuite/bCounter
./mvnw spring-boot:run

# 3. Run the scan
cd /path/to/bSuite/test-harness
python3 run_counter.py --images images --yaml-dir ~/bBuilder_ballots
```

Or use `run_all.sh` with `--counter-dir` to handle this automatically:

```bash
./run_all.sh --counter-dir /path/to/bSuite/bCounter
```

### The test harness holds its own HTTP session

`run_counter.py` logs in to bCounter and maintains its own authenticated
HTTP session to drive the scan. If you also open a browser and log in at
`localhost:8081` during a scan, you will have a separate session. The
browser session will **not** show scan progress (the progress page is
session-bound), but you can browse `/results` to see partial vote totals
as they accumulate in the database.

### Image files are renamed during scanning

bCounter renames each image after processing by appending `.counted` to the
full filename:

```
ballot_1_1_1_1_1_1__valid_all_filled__clean__c01.png
→ ballot_1_1_1_1_1_1__valid_all_filled__clean__c01.png.counted
```

bViewer handles both `.png.counted` and `.counted` filenames transparently.

To restore images for a rescan, run:

```bash
./reset_scan.sh
# or manually:
find images -name "*.png.counted" | while read f; do
  mv "$f" "${f%.counted}"
done
```

---

## Key Configuration Properties

All settings are in `bCounter/src/main/resources/application.properties`.

```properties
# Server ports
server.port=8081
viewer.server.port=8082

# Viewer credentials
viewer.username=admin
viewer.password=ChangeMe123!

# Database location (default: bCounter working directory)
spring.datasource.url=jdbc:sqlite:${user.dir}/counter_results.db

# Report output directory (default: bCounter working directory)
reports.output.dir=${user.dir}

# How often to write results_report.html during a scan
reports.interval=500

# Log file
logging.file.name=${user.home}/bSuite/logs/bCounter.log

# Parallel scanning threads (0 = auto: half CPU cores)
scanner.parallel-threads=0
```

---

## Troubleshooting

**White label error on `/results`**
Log in at `http://localhost:8081/login` first — the results page requires
an authenticated session.

**Images not displaying in bViewer**
The image path stored in the database must be accessible from the machine
running bCounter. If the images were scanned on a different machine, the
paths won't resolve. bViewer tries both `.png.counted` and `.counted`
extensions automatically.

**`SQLITE_READONLY_DBMOVED` error during scan**
The database file was deleted while bCounter was running. Stop bCounter,
delete the database files manually if needed, then restart bCounter before
scanning.

**Port already in use**
Another process (possibly a previous bCounter instance) is using port 8081
or 8082. Find and stop it:

```bash
lsof -ti tcp:8081 | xargs kill
lsof -ti tcp:8082 | xargs kill
```
