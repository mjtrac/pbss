# scanner

Standalone, minimal Swing control panel for just the scanner-driving engine
— a much smaller sibling of bScanner/blScanner, not a replacement for them.
Gated by a `ScannerUser` login (same accounts bScanner/blScanner use — see
`LoginDialog`/`AuthContext`; either `ADMINISTRATOR` or `OPERATOR` may sign
in), with every attempt written to `AuditLogService`. No user management
UI beyond that: it exists to drive a scan batch, capture operator notes,
and get out of the way.

## What it does

1. **Output folder** field, pre-populated from `scanner.output.dir`, with a
   "Browse…" picker.
2. **Start notes** — a text area that's "picked up" (logged immediately,
   with its own timestamp) whenever a scan starts or restarts. Reuses
   `ScanService`'s existing `comment` parameter, which used to only get
   folded into the end-of-batch summary log entry — now it's also logged
   the moment the scan starts.
3. **Start Scan** / **Stop** buttons, with a live progress readout (images
   scanned, last file written).
4. **End notes** — appears once a batch finishes. A separate text area +
   "Save End Note" button, for flagging something discovered *after* the
   fact (a misfeed/doublefeed found while reviewing the stack, say) — not
   tied to the batch's completion time, since an operator may write this
   well after the scan actually finished. Tagged in the log against the
   batch it refers to.
5. **DPI** and **Duplex** controls — shown *only* when the currently
   configured backend actually forwards them to the scan command:

   | Backend | DPI forwarded? | Duplex forwarded? |
   |---|---|---|
   | naps2 | ✅ (`--dpi`) | ✅ (`--source duplex`) |
   | scanimage | ✅ (`--resolution`) | ❌ (not implemented in the command builder) |
   | command (custom) | ❌ | ❌ |

   Showing a control that silently does nothing would be worse than not
   showing it at all — see `ScannerConfig.supportsDpi()`/`supportsDuplex()`.

Both note fields write to the same human-readable `batch_log.txt` that
`writeBatchLog()` already produced (in `scanner.batch-log.dir`, defaulting
to `scanner.output.dir`) — this app doesn't invent a new log file, it adds
two new *kinds* of entry to the existing one.

## Optional: flag pages on an attached printer

Off by default. When enabled, every saved start/end note is also printed
as a large, unmistakable page — meant to be pulled out and physically
inserted into the paper ballot stack at the point a misfeed/doublefeed was
flagged, not scanned by the ballot scanner itself.

```properties
scanner.notes.print-flag-pages=true
scanner.notes.printer-name=           # blank = system default printer
```

**A printing failure never stops scanning** — `FlagPagePrinter` catches
and logs every exception internally (no printer attached, driver error,
wrong printer name, etc.); the note is still written to `batch_log.txt`
regardless of whether printing succeeds.

## Data

Reads/writes the same `~/pbss_data/db/scanner.db` bScanner/blScanner use,
including `ScannerUser` for login —
`spring.jpa.hibernate.ddl-auto=update`, so it can also be the first thing
to ever run against a fresh `pbss_data` directory. On an empty
`scanner_users` table, `ScannerDataInitializer` seeds a default
`admin`/`ChangeMe123!` account (mirroring bScanner's own
`DataInitializer`) — without it, a truly fresh install with no other app
ever run first would have no account to sign in with at all.

## Run from source (development)

```bash
cd scanner
./mvnw spring-boot:run
```

## Build a standalone desktop program

Same jlink + jpackage pattern as the other apps — see the top-level
README's Native Desktop Versions section. Module list (no JavaFX, no
`java.scripting`, no `java.xml` — this app does no XML/YAML parsing):

```bash
jlink \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.base,java.desktop,java.sql,java.net.http,java.naming,java.security.jgss,jdk.crypto.ec,java.logging,java.management,jdk.unsupported,java.instrument \
  --output target/scanner-jre \
  --strip-debug --no-header-files --no-man-pages --compress=2

jpackage \
  --input target/lib \
  --name scanner \
  --main-jar scanner-0.9.13.jar \
  --main-class com.mjtrac.scannerui.Launcher \
  --runtime-image target/scanner-jre \
  --type app-image \
  --app-version 1.0.0 \
  --dest target/dist
```

## Implementation notes

- `com.mjtrac.scanner.entity`/`repository`/`model`/`ScanService`/
  `ScannerConfig` come from the shared
  [`scanner-core`](../scanner-core/README.md) module (also used by
  bScanner/blScanner). **Run `mvn install` in `~/pbss2/scanner-core`
  before building this module for the first time.**
- `ScannerConfig` is a live, mutable Spring singleton bean — this UI
  mutates its fields directly (output dir, dpi, duplex) exactly the way
  bScanner's web `ConfigController` already did; changes are in-memory
  only for the life of the process, not persisted back to
  `application.properties`.
- `ScanServiceNotesTest` (in scanner-core) verifies the notes logging
  end-to-end using a no-op `command` backend (`/bin/sh -c true`) — no
  physical scanner or printer needed to test the logic.
- Login authenticates directly against `ScannerUserRepository` +
  `PasswordEncoder` in `LoginDialog` (no `UserDetailsService`), the same
  pattern `counter`/`viewer` use — bScanner's own
  `UserDetailsService`-implementing `ScannerUserDetailsService` stays local
  to bScanner since only its web form-login needs it.
