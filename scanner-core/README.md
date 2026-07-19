# scanner-core

Shared library (not a runnable app) holding the scanner-driving engine that
`bScanner`, `blScanner`, and `scanner` all need.

## What's in it

- `com.mjtrac.scanner.entity.ScannerUser` / `repository.ScannerUserRepository`
- `com.mjtrac.scanner.model.ScanSession`
- `com.mjtrac.scanner.config.ScannerConfig` — all scan settings
  (`backend`, paths, output dir, dpi, duplex, source, filename prefix,
  batch-log dir), plus `supportsDpi()`/`supportsDuplex()` (true only for
  backends that actually forward those settings — see `ScanService`'s
  command builders) and the flag-page-printing toggle
  (`printFlagPages`/`printerName`).
- `com.mjtrac.scanner.service.ScanService` — drives the configured backend
  (NAPS2 console, `scanimage`, or a custom shell command) as a background
  process, tracks progress via `ScanSession`, and writes the human-readable
  `batch_log.txt`. Also owns start/end notes:
  - `startScan(comment)` logs the comment immediately as a "Start note"
    (its own timestamp, separate from the batch-completion summary that
    already existed).
  - `saveEndNote(note)` logs a note against the most recently completed
    batch (`ScanSession.lastBatchId`) — for anything discovered after the
    fact, e.g. a misfeed found while reviewing the physical stack.
- `com.mjtrac.scanner.service.FlagPagePrinter` (package-private) —
  optionally prints a note as a physical page via `java.awt.print`. Off by
  default; every failure is caught and logged internally, never propagated
  — a missing/broken printer must never stop a scan.

## What's deliberately *not* in it

`ScannerUserDetailsService` (implements Spring Security's
`UserDetailsService` for bScanner's web login) and `SecurityConfig` stay
local to bScanner — blScanner's FX login and `scanner`'s Swing login
(`LoginDialog`) both authenticate directly against
`ScannerUserRepository` + `PasswordEncoder` instead, so neither needs a
copy of `ScannerUserDetailsService`. `DataInitializer` (seeds the default
admin user) also stays local to each app that has a login, even though
it's currently byte-identical across bScanner/blScanner/scanner — same
reasoning as counter-core's `CounterDataInitializer`: app bootstrap
policy, not shared engine logic.

## Using it

```xml
<dependency>
  <groupId>com.mjtrac</groupId>
  <artifactId>scanner-core</artifactId>
  <version>0.9.13</version>
</dependency>
```

**Run `mvn install` in this directory before building any consumer for the
first time**, and again after any change here:

```bash
cd scanner-core
mvn install -DskipTests
```

## If you change something here

```bash
cd scanner-core && mvn test          # ScanServiceNotesTest (4 tests)
cd bScanner      && ./mvnw compile   # no automated test suite (yet)
cd blScanner     && ./mvnw compile   # no automated test suite (yet)
cd scanner       && ./mvnw compile
```

bScanner/blScanner have no automated tests (a pre-existing gap, not
introduced by this extraction) — verify them with a real boot smoke test
(`./mvnw spring-boot:run` / `./mvnw javafx:run`) after any scanner-core
change that touches `ScanService` or `ScannerConfig`.

## Version

Kept at `3.2.5` Spring Boot / in lockstep with the pbss release version
(`0.9.13`), matching bScanner/blScanner's own parent version — this is a
separate lineage from `counter-core` (which tracks bCounter/blCounter's
newer `3.3.0`), not something that needs to match it.
