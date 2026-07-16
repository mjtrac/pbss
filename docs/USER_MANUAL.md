<!--
  Copyright (C) 2026 Mitch Trachtenberg
  Election Ballot System — licensed under the GNU General Public License v3.
  See <https://www.gnu.org/licenses/> for the full license text.
-->

# pbss User Manual

pbss (Paper Ballot Software System) is a four-application suite for designing,
printing, scanning, counting, and auditing paper election ballots:

| App | Port | Purpose |
|---|---|---|
| **bBuilder** | 8080 | Design elections, enter candidates, generate ballot PDFs |
| **bCounter** | 8081 | Load scanned ballot images, count votes, report results |
| **bViewer** | 8082 | Review scanned ballots with vote-indicator overlays (runs inside bCounter) |
| **bScanner** | 8083 | Drive a physical document scanner |

This manual has two parts:

- **[Part 1 — IT Staff](#part-1--it-staff)**: installing, configuring, securing,
  and maintaining pbss.
- **[Part 2 — Election Administrators](#part-2--election-administrators)**:
  running an election day to day — designing ballots, scanning, counting, and
  reporting results.

If you are doing both jobs yourself, read Part 1 first to get everything
running, then use Part 2 as your day-to-day reference.

---

# Part 1 — IT Staff

## 1.1 What you're installing

pbss consists of independent Spring Boot applications, each with its own
embedded web server, SQLite database, and login. There is no central server —
each app runs on the machine you start it on and is reached by URL
(`http://localhost:<port>` or `http://<machine-ip>:<port>` for other machines
on the same network).

You do not need Maven, git, or an IDE to run pbss — only a Java runtime.

## 1.2 Installing Java

pbss requires **Java 21 or later**.

- **macOS:** `brew install openjdk@21`, or download from
  [adoptium.net](https://adoptium.net)
- **Windows:** Download the installer from
  [adoptium.net](https://adoptium.net)
- **Linux:** `sudo apt install openjdk-21-jre` (or your distro's equivalent)

Verify with `java -version` — it should report 21 or higher.

## 1.3 Getting the software

Download the pre-built JARs from the project's Releases page:

- `bBuilder-1.0.0.jar`
- `bCounter-1.0.0.jar`
- `bScanner-1.0.0.jar` — only needed if this machine drives a physical scanner

Place them in a working directory of your choice, e.g. `C:\pbss\` or
`~/pbss\`.

## 1.4 Running the applications

Each app runs in its own terminal/console window (or as a background service —
see §1.9):

```bash
java -jar bBuilder-1.0.0.jar     # http://localhost:8080
java -jar bCounter-1.0.0.jar     # http://localhost:8081 (bViewer at :8082)
java -jar bScanner-1.0.0.jar     # http://localhost:8083  (optional)
```

All data directories are created automatically on first run — no manual setup
is required.

### macOS launcher

If you were given `pbss_app.zip`, unzip it and place `pbss.app` in the same
folder as the JARs, then remove the macOS quarantine flag (required once per
download, since the app isn't notarized):

```bash
xattr -cr /path/to/pbss.app
open /path/to/pbss.app
```

The launcher starts bBuilder and bCounter with Start/Stop controls — no
Terminal needed for day-to-day use. See §2.1 for the menu options an election
administrator will use.

## 1.5 Data layout

All pbss data lives under one root directory: `~/pbss_data/`. Back this
directory up as a unit — it's everything.

```
~/pbss_data/
  db/
    election_ballot.db      bBuilder: elections, templates, ballot combinations
    counter_results.db      bCounter: scan results, votes
    scanner.db              bScanner: users, scanner configuration
  ballot_templates/         bBuilder writes ballot PDFs + YAML layouts here;
                             bCounter reads the YAMLs from the same location
  cast_ballot_scans/        bScanner deposits scanned images here;
                             bCounter reads images from the same location
  reports/                  bCounter result reports (HTML, CSV, overvote report)
  writeins/                 bCounter write-in image crops
  scribbles/                bCounter scribble-detection outline images
  logs/
    bBuilder.log
    bCounter.log
    bScanner.log
```

Every path is overridable in `application.properties` — see §1.7.

## 1.6 Databases

All three apps use SQLite by default (one file each, listed above). bBuilder
additionally supports PostgreSQL if you need a shared/networked database —
set `spring.datasource.url` accordingly.

Full schema reference: `docs/database-schemas.md`.

**Restrict OS file permissions on the `.db` files to the account running the
app.** SQLite has no network listener to secure — the file itself is the
attack surface.

## 1.7 Configuration

Each app reads `application.properties` from its working directory, or you
can override any property at the command line without editing files:

```bash
java -jar bCounter-1.0.0.jar \
  --scanner.default.image.dir=/path/to/scans \
  --viewer.server.port=8082

java -jar bBuilder-1.0.0.jar \
  --ballot.export.dir=/path/to/ballot/output \
  --app.login-title="My County Election System"
```

Commonly changed properties:

| Property | Default | App |
|---|---|---|
| `server.port` | 8080 / 8081 / 8083 | all |
| `viewer.server.port` | 8082 | bCounter (bViewer) |
| `ballot.export.dir` | `~/pbss_data/ballot_templates` | bBuilder |
| `scanner.default.image.dir` | `~/pbss_data/cast_ballot_scans` | bCounter |
| `reports.output.dir` | `~/pbss_data/reports` | bCounter |
| `scanner.output.dir` | `~/pbss_data/cast_ballot_scans` | bScanner |
| `scanner.parallel-threads` | `0` (auto = half CPU cores) | bCounter |
| `scanner.max-review-before-stop` | `20` | bCounter |
| `server.servlet.session.timeout` | framework default | all |
| `app.background-color` | none | all — useful to visually distinguish test vs. production instances |

Full property reference: `docs/configuration.md`.

## 1.8 User accounts and roles

Every app seeds a default account on first run: **`admin` / `ChangeMe123!`**.
**Change this password immediately** at `/account/password` after first
login, on every app.

| App | Roles | Notes |
|---|---|---|
| bBuilder | `ADMIN`, `DATA_ENTRY`, `PRINTER` | Own user store |
| bCounter / bViewer | `ADMIN`, `COUNTER_OPERATOR`, `VIEWER` | Shared user store between the two — one account, any combination of roles |
| bScanner | `ADMINISTRATOR`, `OPERATOR` | Own user store |

Manage users at `/admin` (ADMIN/ADMINISTRATOR role required) in each app.

**bBuilder admin password reset** (if locked out):
```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dreset.admin.password=true"
```

## 1.9 Running as a service (production deployment)

For an election-day deployment you generally want the apps to survive
terminal closure and restart on reboot. pbss ships no service unit files —
wrap the `java -jar` commands in your platform's standard mechanism:

- **Linux:** a `systemd` unit per app (`ExecStart=java -jar bCounter-1.0.0.jar`)
- **Windows:** `nssm` or Task Scheduler with "run whether user is logged on or not"
- **macOS:** a `launchd` plist, or use `pbss.app` interactively

Point `logging.file.name` at a persistent log path in each case (defaults to
`~/pbss_data/logs/*.log` already).

## 1.10 Security notes

| Library | License | Note |
|---|---|---|
| Spring Boot 3.x | Apache 2.0 | Monitor spring.io/security for advisories |
| iText 8 + html2pdf | **AGPL 3.0** | Government internal use generally satisfies AGPL; confirm with legal counsel before external distribution |
| ZXing 3.5.x | Apache 2.0 | QR code decoding |
| SnakeYAML 2.x | Apache 2.0 | **Must stay on 2.x** — 1.x has a critical RCE (CVE-2022-1471) |
| SQLite JDBC 3.45.x | Apache 2.0 | Restrict OS permissions on `.db` files |
| PostgreSQL JDBC 42.x | BSD 2-Clause | Keep updated |
| BCrypt (Spring Security) | Apache 2.0 | Cost factor 12 |

Built-in application protections: CSRF tokens, Content-Security-Policy,
`X-Frame-Options: DENY`, session-fixation protection, BCrypt password hashing,
parameterized JPA queries, Thymeleaf auto-escaping.

**Network exposure:** by default each app binds to all interfaces on its
port. If running on a shared network, put the machine behind a firewall that
only allows the ports needed (8080–8083) from trusted hosts, and set
`server.servlet.session.cookie.secure=true` if you terminate HTTPS in front
of pbss (e.g. via a reverse proxy).

## 1.11 Backup and migration

Because everything lives under `~/pbss_data/`, backup is: **stop the apps,
copy the directory**. To migrate to a new machine, install Java, copy the
JARs and `~/pbss_data/` over, and start the apps — no database export/import
step is required since it's already SQLite files.

Take a backup **before** each scanning session and **after** results are
finalized, at minimum.

## 1.12 Troubleshooting

| Symptom | Where to look |
|---|---|
| App won't start / port in use | `~/pbss_data/logs/<app>.log`; check nothing else is bound to 8080–8083 |
| "review required" ballots piling up | `~/pbss_data/reports/review_required.txt`; the corner-detection failure threshold is `scanner.max-review-before-stop` |
| Locked out of bBuilder admin | See password reset in §1.8 |
| Need to point bViewer at a different results DB | `COUNTER_DB` environment variable, or `-Dspring.datasource.url=jdbc:sqlite:/path/to/counter_results.db` |
| Scanner backend not producing images | Check bScanner's configured backend (NAPS2/scanimage/custom command) and `scanner.output.dir` in `bScanner/application.properties` |

---

# Part 2 — Election Administrators

This part assumes IT has already installed pbss and given you login
credentials for bBuilder and bCounter. You will use a normal web browser —
no software installation on your part.

## 2.1 Starting the applications

If your machine has the macOS launcher (`pbss.app`), double-click it and use
the menu:

| Option | What it does |
|---|---|
| **Start All** | Starts bBuilder and bCounter, opens both in your browser |
| Start bBuilder only | For ballot design work only |
| Start bCounter only | For scanning/counting/reviewing only (includes bViewer) |
| Open bBuilder/bCounter/bViewer in Browser | Reopens the browser tab without restarting anything |
| **Run Test Harness…** | For test/practice elections only — see IT staff before using on production data |
| Stop All | Shuts everything down cleanly |

Otherwise, ask IT for the URLs — normally:
- bBuilder: `http://localhost:8080`
- bCounter: `http://localhost:8081`
- bViewer: `http://localhost:8082/viewer`

Log in with the account IT gave you. If you're prompted to change your
password on first login, do so immediately.

## 2.2 Designing an election (bBuilder)

Work through these in order:

1. **Jurisdiction / Election** — create the top-level election record.
2. **Regions** — define precincts and precinct groups.
3. **Parties** and **Ballot types** — as needed for your election.
4. **Contests** — define each race or measure, and assign it to the regions
   that vote on it.
5. **Candidates** — assign candidates (or write-in slots) to each contest.
6. **Ballot template** — choose paper size, column count, font sizes,
   margins, and vote indicator style:
   - **Oval** — filled ellipse (recommended for optical scanning accuracy)
   - **Rectangle** — filled rectangle
   - **Connect dots** — voter draws a line between two markers
   - Ranked-choice contests always use rank boxes regardless of this
     setting, and cannot use Connect-dots
7. Optionally customize the ballot header with your jurisdiction's logo/seal
   and voter instructions (HTML/CSS header zone).
8. **Generate ballots.** This produces, per ballot style, a PDF for printing
   and a YAML file bCounter uses to know where every vote indicator sits on
   the page. Both are written to the shared `ballot_templates` folder — you
   don't need to move anything for bCounter to find them.

Every ballot page carries a QR code (identifying jurisdiction, region, party,
ballot type, and page) and registration marks pbss uses to detect the
ballot's corners and orientation during scanning — don't crop, resize, or
otherwise alter the printed layout.

**User roles in bBuilder**, if you're managing other staff accounts:

| Role | Can do |
|---|---|
| `ADMIN` | Everything, including user management and the audit log |
| `DATA_ENTRY` | Enter/edit election data; cannot print |
| `PRINTER` | Generate ballot PDFs; read-only on election data |

## 2.3 Printing

Print the generated PDFs on your usual production printer. Nothing
pbss-specific is required at print time — standard paper stock and a printer
capable of the chosen paper size are sufficient. Keep the registration marks
and QR code intact and unobstructed.

## 2.4 Scanning ballots

**If you have a physical scanner attached to this machine (bScanner):**

1. Open bScanner (`http://localhost:8083`).
2. Confirm the backend is configured (NAPS2 on macOS/Windows, `scanimage` on
   Linux, or a custom command) — this is normally a one-time IT setup.
3. Feed ballots and scan. Images are deposited automatically into
   `cast_ballot_scans/`, where bCounter will find them.

**If scanning is done with separate scanning software:** point that software
at the same `cast_ballot_scans` folder bCounter reads from — any scanner
software works as long as images land there.

**Tip on scribble detection:** if you enable scribble detection, scan one
known-clean ballot first — pbss uses it to establish the "normal" baseline it
compares every other ballot against.

## 2.5 Counting (bCounter)

1. Open bCounter (`http://localhost:8081`) and log in.
2. On the "Start Counting" page, the image folder and YAML layout folder are
   pre-filled with the standard locations — override only if IT set up
   something non-standard.
3. Darkness threshold (default 8%) controls how dark a vote indicator must be
   to count as marked — leave at the default unless IT/vendor guidance says
   otherwise.
4. Click **Start Counting**. Progress is shown live; ballots that fail
   automatic corner detection are set aside for manual review rather than
   guessed at.
5. If you're re-running a scan (e.g., after fixing a batch of misfeeds), the
   page will show a **📋 Results** link and a timestamp if a prior run's
   results already exist.

During counting, pbss automatically:
- Detects and corrects upside-down ballots
- Reads the QR code to identify ballot style, region, and page
- Samples each vote indicator and flags overvotes per contest
- Runs ranked-choice tabulation (if any contest uses RCV)
- Extracts write-in image crops for adjudication
- Flags ballots that don't resemble the clean baseline (scribbles)

## 2.6 Reviewing results

Reports are written to `~/pbss_data/reports/` and are also viewable in the
browser:

| Report | What it's for |
|---|---|
| `results_report.html` | Running/final vote totals, updated periodically during the scan and at the end |
| `rcv_report.html` | Ranked-choice elimination rounds and winner, for RCV contests |
| `overvote_report.txt` | Ballots where a voter marked more choices than allowed in a contest |
| `review_required.txt` | Ballots that failed automatic processing and need eyes-on review |
| `writein_report.html` | Ballots with a marked write-in slot, with links to the cropped image |
| `scribble_report.html` | Ballots flagged as visually inconsistent with the clean baseline |
| `ballot_manifest.csv`, `cvr_export.csv` | Cast-vote records formatted for risk-limiting audit tools (e.g. Arlo) |

**Reviewing individual ballot images** — use bViewer
(`http://localhost:8082/viewer`):
- 🟢 Green = counted as voted/marked
- 🟡 Amber = overvoted
- 🔵 Blue = left unmarked
- Filter the ballot list by name/glob or SQL query; toggle box outlines and
  candidate names on/off for a cleaner view when spot-checking.

## 2.7 Handling review-required ballots

Ballots pbss couldn't process automatically (folded corners, heavy skew,
torn edges, etc.) are listed in `review_required.txt` and the affected image
files are renamed with a `.review` suffix in the scan folder. Resolve these
by hand — rescan the physical ballot if it's a scan-quality issue, or perform
manual adjudication per your jurisdiction's procedures.

## 2.8 End-of-count checklist

1. Confirm the scan count matches your expected ballot count for the batch.
2. Resolve everything in `review_required.txt`.
3. Resolve/adjudicate everything in `writein_report.html` and
   `overvote_report.txt` per your jurisdiction's rules.
4. Check `scribble_report.html` for anything flagged that needs a second
   look.
5. Confirm final totals in `results_report.html` (and `rcv_report.html` for
   ranked-choice contests).
6. Ask IT to back up `~/pbss_data/` for the record.

## 2.9 Getting help

For anything not covered here — configuration changes, account lockouts,
scanner setup, backups — contact your IT staff and point them to Part 1 of
this manual.
