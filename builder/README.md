# builder

Standalone Swing ballot-design tool — full CRUD across all the data
bBuilder manages, plus PDF generation. No login (a pragmatic choice, same
as counter/scanner) — the Admin screen still manages `User` records
directly, since they're shared with bBuilder/blBuilder's own login.

## Layout

A top menu bar (Setup / Ballots / Admin, plus a direct Home item) mirrors
bBuilder's own `layout.html` nav groups, replacing the earlier sidebar
list. The Home screen is a set of numbered step cards (1–7, then Print)
mirroring bBuilder's `dashboard.html` — a hint at the suggested order,
not an enforced one; every screen is also reachable directly from the
menus at any time.

Every list screen supports **double-click to edit** a row (in addition to
selecting it and clicking Edit) — including the newly-added sub-screens
below — matching how bBuilder's own web UI and viewer's ballot list both
behave.

## Screens

| Screen | Covers |
|---|---|
| Jurisdictions | name, address, contact email, voting instructions |
| Parties | jurisdiction, name, abbreviation |
| Ballot Types | jurisdiction, name, description |
| Languages | jurisdiction, IETF code, display name, order |
| Regions | jurisdiction, name, SINGLE_PRECINCT/PRECINCT_GROUP, member precincts (for groups) |
| Elections | jurisdiction, name, date, type, uniform-ballot flag |
| Ballot Combinations | precinct + party + ballot type + election |
| Ballot Design Templates | see below — full field set except WYSIWYG preview |
| Contests | election, title, voting method, max/rank choices, display order, instructions, grouping label, preamble, postamble, explanatory text (+ print-flag checkboxes for each) |
| Print | pick a combination + template + language + copies + printed-by user, generate a real PDF |
| Admin (Users) | username, password, roles (ADMIN/DATA_ENTRY/PRINTER), enabled, jurisdiction |

### Contest sub-screens: Candidates, Regions, Translations

Matching bBuilder's own web behavior (region assignment and candidate
management only appear once a contest has an id — `contest-form.html`'s
`th:if="${contest.id != null}"` sections), these are **separate screens**,
not fields inside the Contest form itself:

- **Candidates** — every candidate/option for the contest in one editable
  table (name, write-in, party, order, prefix/suffix text + print flags,
  explanatory text + print flag) — bulk entry, not a one-at-a-time form.
  This is bBuilder's dedicated `/contests/{id}/candidates` page, as a table
  instead of a page.
- **Regions** — precinct/precinct-group assignment, a multi-select list
  (bBuilder splits this into two checkbox groups on the same page; here
  it's one list, groups labeled `(group)`).
- **Translations** (on both Contest and, per-candidate, from the
  Candidates screen) — per-language text, one tab per active
  `BallotLanguage` for the contest's jurisdiction. This is a capability
  bBuilder has (`LanguageController`'s `/contests/{id}/translate` and
  `/candidates/{id}/translate` routes) that builder never had before this
  pass — `ContestTranslationDialog` / `CandidateTranslationDialog`.

**Every Contest save — new or edit — opens Candidates, then Regions,**
automatically once the dialog closes, per explicit request ("once saved,
should open new screens"). Both remain reachable afterward via buttons on
the Contest edit form (reached by double-clicking an existing row), so
editing candidates or regions doesn't require re-saving the contest.

### Ballot Design Templates: full field set, still no WYSIWYG

`BallotDesignTemplate`'s ~40 fields are now all exposed — paper size,
columns, margins, primary/alternate font family, a Font Sizes and Styles
table (size + bold + italic + alt-font checkboxes, one row per text role:
grouping label, contest title, instructions, preamble, candidate name,
prefix/suffix, candidate note, postamble, header), indicator line
width/dash style, barcode width/QR height, multi-sheet flag, RCV indicator
position/rank-number display, and the header HTML text. Two exclusions,
neither a UI omission:

- **No WYSIWYG preview/canvas** — settings are plain form controls (a
  checkbox is still not a rendered visual), same spirit as the header-HTML
  field being raw text, no live-rendered editor.
- **`headerHeadline`/`headerHeadlineFontSize`/`headerBodyText`/
  `headerBodyFontSize`** are excluded because the entity defines the
  fields but has no getters/setters for them at all (confirmed via grep —
  dead fields, nothing to bind) — and **`barcodePosition`** is excluded
  because bBuilder's own form hardcodes it to `TOP_RIGHT` "for scanning
  reliability" rather than exposing it either.

## Data

Reads/writes the same `~/pbss_data/db/election_ballot.db`
bBuilder/blBuilder use, and the same `ballot.export.dir`
(`~/pbss_data/ballot_templates`) for generated PDFs and offset reports.

## Run from source (development)

```bash
cd builder
./mvnw spring-boot:run
```

## Build a standalone desktop program

Same jlink + jpackage pattern as the other apps — see the top-level
README's Native Desktop Versions section.

```bash
jlink \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.base,java.desktop,java.sql,java.xml,java.net.http,java.naming,java.security.jgss,jdk.crypto.ec,java.logging,java.management,jdk.unsupported,java.instrument \
  --output target/builder-jre \
  --strip-debug --no-header-files --no-man-pages --compress=2

jpackage \
  --input target/lib \
  --name builder \
  --main-jar builder-0.9.13.jar \
  --main-class com.mjtrac.builderui.Launcher \
  --runtime-image target/builder-jre \
  --type app-image \
  --app-version 1.0.0 \
  --dest target/dist
```

## Implementation notes

- `com.mjtrac.ballot.model`/`repository`/`util`/`service` come from the
  shared [`builder-core`](../builder-core/README.md) module. **Run
  `mvn install` in `~/pbss2/builder-core` before building this module for
  the first time.**
- `SimpleCrudPanel<T>` is a small reusable base class (list JTable +
  New/Edit/Delete/Refresh buttons, driving a JDialog-based form) used by
  every screen except Print (which has no list — it's a form-only trigger
  screen) — avoids re-writing the same table+button wiring across nine
  entities. Watch the field-initializer order if you touch it: `table`
  must be constructed *after* `columnNames`/`rowValues` are assigned, not
  as a field initializer — `JTable`'s constructor synchronously calls back
  into the table model for `getColumnCount()`, which reads `columnNames`.
  A field initializer runs before the constructor body, so it would see
  `null` (this was a real bug caught during initial smoke testing).
- `MainFrame`'s `@Component` import collides with `java.awt.Component` by
  name — the `for (Component c : ...)` loop needs `java.awt.Component`
  fully qualified.
- No `@EnableJpaRepositories` on `BuilderApp` — `builder-core`'s own
  `DatabaseConfig` already declares it (component-scanned via
  `scanBasePackages`); duplicating it throws
  `BeanDefinitionOverrideException` (also caught during smoke testing).
  `@EntityScan` is still needed since entities live outside this app's
  own package.
- `BallotGenerationService.generateBallot()` requires a non-null `User`
  (used for `PrintLogService`'s audit log) — since this app has no login,
  the Print screen has a "Printed by" user picker instead of pulling from
  an `AuthContext`.
- Same headless/jpackage pitfalls as the other Swing tools, fixed the same
  way: `SpringApplicationBuilder.headless(false)`, and
  `spring-boot-maven-plugin`'s `<skip>true</skip>` scoped to the
  `repackage` execution only (not the plugin's top-level configuration,
  which would also silently no-op `mvn spring-boot:run`).
- `BuilderEndToEndTest` builds a full data graph (jurisdiction → region →
  election → ballot type → contest+candidates → ballot combination →
  design template → user) through the real repositories against an
  isolated temp SQLite DB, reloads everything by id, and generates a real
  PDF — verifying the CRUD/cascade save-and-reload path the screens rely
  on, not just that the code compiles. A second test method,
  `contestCandidatesAndTranslationsRoundTrip`, covers the newer
  Contest/Candidates/Regions/Translations flow the same way — real
  repository calls, real save-then-reload, no Swing involved (driving
  actual Swing screens needs a real display; see
  `test-harness/README-desktop.md`'s note on the macOS Accessibility
  permission this dev sandbox lacks, which blocks AssertJ-Swing
  specifically). Two `@Test` methods share one `@SpringBootTest` context/DB
  for the class — give any new test its own jurisdiction name,
  `jurisdictions.name` is unique.
- `ContestCandidatesDialog`/`ContestRegionsDialog` save by mutating
  `contest.getCandidates()`/`contest.setAssignedRegions(...)` and
  re-saving the *Contest* (not a candidate/region repository directly) —
  `Contest.candidates` is `@OneToMany(cascade=ALL, orphanRemoval=true)`,
  so this handles add/update/remove correctly, same pattern the old inline
  candidate sub-table used before this became a separate screen.
- The Contest save handler calls `SwingUtilities.getWindowAncestor(grid)`,
  not `this` (the `ContestPanel`, which lives in the main window, not the
  edit dialog) — an easy mistake since `this` compiles fine there, it's
  just the wrong window. `grid` (the field-holding panel actually inside
  the dialog) is the correct anchor for dialogs opened from within a save
  handler.
- `ContestTranslationDialog`/`CandidateTranslationDialog` resolve the
  jurisdiction via `contest.getElection().getJurisdiction()` and list
  languages with `BallotLanguageRepository
  .findByJurisdictionIdOrderByDisplayOrderAsc(...)` — one tab per
  language, matching bBuilder's `LanguageController`. A candidate must
  already have an id (saved at least once) before its translations screen
  will open — there's nothing to attach a translation row to otherwise.
- `SimpleCrudPanel`'s double-click handler calls the same `openForm(sel)`
  the Edit button does — one `MouseListener` added to the base class
  covers every subclass screen, including the newer sub-dialogs that
  aren't `SimpleCrudPanel`s themselves (`ContestCandidatesDialog`,
  `ContestRegionsDialog`) but sit behind a `SimpleCrudPanel` row
  (Contests) that now has it.
