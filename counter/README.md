# counter

Standalone, minimal Swing control panel for just the ballot-counting engine
— a much smaller sibling of blCounter, not a replacement for it. Gated by a
CounterUser login (same accounts blCounter/viewer use — see
`LoginDialog`/`AuthContext`), with every sign-in attempt written to
`AuditLogService`. No ballot viewer, no results browser: it exists to
start/stop a scan and point you at the output files.

## What it does (and only this)

1. Two folder fields, pre-populated with the same defaults bCounter/blCounter
   use (`scanner.default.image.dir`, `scanner.default.report.dir`), each
   with a "Browse…" picker:
   - **Ballot images folder** — the scanned ballot PNG/JPG/TIFF files.
   - **Ballot templates folder** — the YAML/XML layout files bBuilder
     produces (indicator positions).
2. **Start Counting** / **Stop** buttons.
3. A live progress readout (pass number, images processed, duplicates,
   flagged-for-review count).
4. **Open Results Folder**, enabled once a scan finishes — opens
   `reports.output.dir` (default `~/pbss_data/reports`) in the OS file
   browser, where `results_report.html` and friends land.

Everything else — the darkness threshold, DPI, assumed paper width,
scribble detection, RCV tabulation, Arlo export, corner-detection
debugging — runs exactly as it does in bCounter, just configured via
`application.properties` rather than exposed as UI controls. See "Fixed
scan parameters" in that file if you need to tune them.

## The failure-count property

Same mechanism as bCounter/blCounter, unchanged:

```properties
# Stop scanning automatically after this many ballots are sent to manual
# review, so a systematic problem (wrong layout folder, bad images) doesn't
# silently skip large numbers of ballots undetected. 0 disables the limit.
scanner.max-review-before-stop=20
```

## Data

Reads and writes the same `~/pbss_data/db/counter_results.db` and
`~/pbss_data/reports/` (etc.) that bCounter/blCounter use —
`spring.jpa.hibernate.ddl-auto=update`, so it can also be the *first*
thing to ever run against a fresh `pbss_data` directory (it'll create the
schema itself, same as bCounter does). On an empty `counter_user` table,
`CounterDataInitializer` seeds a default `admin`/`ChangeMe123!` account
(mirroring bCounter's own `CounterDataInitializer`) — without it, a truly
fresh install with no other app ever run first would have no account to
sign in with at all.

## Run from source (development)

```bash
cd counter
./mvnw spring-boot:run
```

## Build a standalone desktop program

Same jlink + jpackage pattern as the other apps — see the top-level
[README's Native Desktop Versions section](../README.md#native-desktop-versions)
for the general steps. Module list (no JavaFX, no `java.scripting`; adds
`java.xml` for `BboxReportLoader`'s XML-format layout support):

```bash
jlink \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.base,java.desktop,java.sql,java.xml,java.net.http,java.naming,java.security.jgss,jdk.crypto.ec,java.logging,java.management,jdk.unsupported,java.instrument \
  --output target/counter-jre \
  --strip-debug --no-header-files --no-man-pages --compress=2

jpackage \
  --input target/lib \
  --name counter \
  --main-jar counter-0.9.13.jar \
  --main-class com.mjtrac.counterui.Launcher \
  --runtime-image target/counter-jre \
  --type app-image \
  --app-version 1.0.0 \
  --dest target/dist
```

## Implementation notes

- `com.mjtrac.counter.entity`/`repository`/`model`/`service` come from the
  shared [`counter-core`](../counter-core/README.md) module (also used by
  bCounter/blCounter/viewer) rather than a local copy. **Run
  `mvn install` in `~/pbss2/counter-core` before building this module for
  the first time.** Login authenticates directly against
  `CounterUserRepository` + `PasswordEncoder` in `LoginDialog` (no
  `UserDetailsService`), so it never needed a copy of `CounterUserService`.
- `CountingService.finish(username)` is called automatically once a scan's
  background loop exits (whether by completing naturally or by Stop),
  rather than requiring a separate manual step — blCounter's JavaFX shell
  has a distinct "Finish" button for this; this app collapses it into the
  Start/Stop pair the task description asked for. Note the actual
  `results_report.html`/etc. are written by the scan loop itself
  (`CountingService.runScanLoop`) regardless of `finish()` — `finish()`
  mainly adds the audit-log "FINISH" entry and a second
  `review_required.txt` write.
- Audit-log entries use the signed-in operator's username; `finish()`'s
  `"(unknown)"` fallback only fires if `AuthContext` somehow has no current
  user, which shouldn't happen once login has succeeded.
- `CountingServiceIntegrationTest` runs a real scan against real ballot
  images and a real layout YAML (copied from an earlier test-harness run,
  under `src/test/resources/test-images/`) against an isolated temp SQLite
  DB — verifying the whole pipeline works through this module's own wiring,
  not just that it compiles. It defines its own minimal
  `@SpringBootApplication` scanning only `com.mjtrac.counter` (not
  `com.mjtrac.counterui`) so building the test context doesn't also
  construct `MainFrame` (a real `JFrame`) as a side effect.
- Same two pitfalls as viewer, fixed the same way: `spring.main.headless`
  must be explicitly turned off (`SpringApplicationBuilder.headless(false)`
  in `CounterApp`), and `spring-boot-maven-plugin`'s `<skip>true</skip>`
  (needed for jpackage compatibility) must be scoped to the `repackage`
  execution only, not the plugin's top-level `<configuration>` — the
  latter also silently no-ops `mvn spring-boot:run`.
