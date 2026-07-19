# builder-core

Shared library (not a runnable app) holding the ballot-design engine that
`bBuilder`, `blBuilder`, and `builder` all need.

## What's in it

- `com.mjtrac.ballot.model.*` — the 14 JPA entities backing
  `election_ballot.db` (`Jurisdiction`, `Election`, `Party`, `BallotType`,
  `Region`, `BallotLanguage`, `Contest`, `Candidate`,
  `ContestTranslation`, `CandidateTranslation`, `BallotCombination`,
  `BallotDesignTemplate`, `User`, `PrintLog`).
- `com.mjtrac.ballot.repository.*` — their Spring Data repositories.
- `com.mjtrac.ballot.util.*` — `BallotDimensions`, `MeasurementUtil`.
- `com.mjtrac.ballot.config.DatabaseConfig` — `@EnableJpaRepositories` +
  `@EnableTransactionManagement` for `com.mjtrac.ballot.repository`.
  Consumers don't need their own `@EnableJpaRepositories` — this provides
  it via component scan (see each consumer's main class comment; declaring
  it twice throws `BeanDefinitionOverrideException`). `@EntityScan` is
  still each consumer's own concern, since entities live outside any
  consumer's own package.
- `com.mjtrac.ballot.service.*` — the generation engine:
  `BallotGenerationService` (PDF via iText 8), `BallotLayoutService`,
  `BallotTranslationService`, `BarcodeService` (ZXing), `PrinterService`
  (PDFBox rasterization), `PrintLogService`, `ContestAssignmentService`,
  `ExportService` (YAML/XML offset reports via SnakeYAML), and
  `ArrowIndicatorDrawer`.

## What's deliberately *not* in it

`UserService` stays local to each of bBuilder and blBuilder, because they
genuinely diverge: bBuilder's implements Spring Security's
`UserDetailsService` for its web form-login; blBuilder's doesn't, since FX
login checks credentials directly. `builder` has no login and never had a
copy of this class — its Admin screen manages `User` records directly via
`UserRepository` instead. `DataInitializer` (seeds a default admin user)
also stays local, even though it's currently byte-identical between
bBuilder and blBuilder — same reasoning as counter-core's
`CounterDataInitializer`: app bootstrap policy, not shared engine logic.
Web-only pieces (`SecurityConfig`, `GlobalModelAdvice`, all Thymeleaf
controllers) stay in bBuilder; blBuilder's `fx/` package and builder's
`builderui/` package are each their own app-specific UI layer.

## Using it

```xml
<dependency>
  <groupId>com.mjtrac</groupId>
  <artifactId>builder-core</artifactId>
  <version>0.9.13</version>
</dependency>
```

**Run `mvn install` in this directory before building any consumer for the
first time**, and again after any change here:

```bash
cd builder-core
mvn install -DskipTests
```

## If you change something here

```bash
cd builder-core && mvn test           # no dedicated builder-core tests yet — see bBuilder's own suite
cd bBuilder      && ./mvnw test       # 277 tests
cd blBuilder     && ./mvnw test       # 79 tests (TestFX)
cd builder       && ./mvnw test       # BuilderEndToEndTest — real CRUD graph + real PDF generation
```

All three passed with zero regressions when this module was first
extracted (2026-07-19) — that's the bar to keep clearing.

## Version

Kept in lockstep with the pbss release version (currently `0.9.13`),
matching bBuilder/blBuilder's own parent version — same convention as
counter-core and scanner-core.
