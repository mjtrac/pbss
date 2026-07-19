# counter-core

Shared library (not a runnable app) holding the ballot-counting engine
that `bCounter`, `blCounter`, `viewer`, and `counter` all need — pulled
out after those four accumulated genuinely identical copies of the same
entity/repository/model/service code.

## What's in it

- `com.mjtrac.counter.entity.*` — the 6 JPA entities backing
  `counter_results.db` (`BallotImage`, `BarcodeRecord`, `CandidateRecord`,
  `ContestRecord`, `CounterUser`, `VoteOpportunity`).
- `com.mjtrac.counter.repository.*` — their Spring Data repositories.
- `com.mjtrac.counter.model.*` — `BboxReport`, `ScanSession`.
- `com.mjtrac.counter.service.*` — the counting engine itself: corner
  detection, homography, barcode/marker analysis, scribble detection, vote
  tallying, RCV tabulation, Arlo export, and `CountingService` (the
  extracted scan-loop orchestrator). 18 of the 19 service classes that used
  to live in each consumer are here now.
- `com.mjtrac.viewer.service.BallotViewService` — the read-only
  ballot-image-lookup service used by both blCounter's embedded Viewer and
  the standalone `viewer` app.
- `com.mjtrac.counter.config.PasswordEncoderConfig` — the shared
  `BCryptPasswordEncoder` bean.

## What's deliberately *not* in it

`CounterUserService` stays local to each of bCounter and blCounter,
because they genuinely diverge: bCounter's implements Spring Security's
`UserDetailsService` for its web form-login; blCounter's doesn't, since FX
login checks credentials directly. `viewer` and `counter` authenticate
directly against `CounterUserRepository` + `PasswordEncoder` in their own
`LoginDialog` (no `UserDetailsService` needed) — never had a copy of
`CounterUserService`. Forcing this one file into a shared abstraction
wasn't worth it for a ~25-line difference — see each app's own
`CounterUserService.java`/`LoginDialog.java` if you need to change
user/role logic.

Anything web/UI-specific also stays local: bCounter's controllers and
Thymeleaf templates, blCounter's `fx/` package and `viewer/controller`,
`viewer`'s `viewerui/` package, counter's `counterui/` package.

## Using it

Not part of a Maven multi-module reactor — each consumer just declares a
normal dependency:

```xml
<dependency>
  <groupId>com.mjtrac</groupId>
  <artifactId>counter-core</artifactId>
  <version>0.9.13</version>
</dependency>
```

Which means **you must `mvn install` this module before building any
consumer for the first time**, and again after any change here:

```bash
cd counter-core
mvn install -DskipTests
```

(No `mvnw` wrapper — this is a library, not something you run directly, so
whatever Maven you have installed is fine.)

## If you change something here

A change to any of these files affects bCounter, blCounter, viewer, and
counter simultaneously. After `mvn install`:

```bash
cd bCounter  && ./mvnw test   # 104 tests
cd blCounter && ./mvnw test   # 33 tests (TestFX)
cd viewer    && ./mvnw test   # 6 tests (Homography + BallotViewPanelLayout)
cd counter   && ./mvnw test   # 2 test classes (login/audit + end-to-end scan)
```

All four passed with zero regressions when this module was first extracted
(2026-07-19) — that's the bar to keep clearing.

## Version

Kept in lockstep with the pbss release version (currently `0.9.13`) for
simplicity — there's no reason yet for counter-core to version
independently of its consumers.
