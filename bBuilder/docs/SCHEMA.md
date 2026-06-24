<!--
  Copyright (C) 2026 Mitch Trachtenberg
  Election Ballot System — licensed under the GNU General Public License v3.
  See <https://www.gnu.org/licenses/> for the full license text.
-->

# bSuite — Database Schema

Generated from the JPA entity definitions.

## Applications and Databases

| App | Database File | Default Location |
|---|---|---|
| bBuilder | `election_ballot.db` | Same directory as the JAR / `user.dir` |
| bCounter | `counter_results.db` | Same directory as the JAR / `user.dir` |
| bViewer | Read-only view of `counter_results.db` | Configured via `spring.datasource.url` |

All three use SQLite by default. bBuilder also supports PostgreSQL via
`application-postgres.properties`.

---

# bBuilder Database (`election_ballot.db`)

## Entity Relationship Overview

```
Jurisdiction
  ├── Election              (many per jurisdiction)
  ├── Region                (many; SINGLE_PRECINCT or PRECINCT_GROUP)
  ├── Party                 (many per jurisdiction)
  ├── BallotType            (many per jurisdiction)
  └── User                  (many per jurisdiction)

Election
  ├── Contest               (many per election)
  ├── BallotDesignTemplate  (one per election)
  └── BallotCombination     (many per election)

Contest
  └── Candidate             (many per contest)

BallotCombination
  ├── Election
  ├── Region  (must be SINGLE_PRECINCT)
  ├── Party   (nullable — null means nonpartisan / general)
  └── BallotType

Region (PRECINCT_GROUP)
  └── members → [Region (SINGLE_PRECINCT), ...]   via region_members

Contest
  └── assignedRegions → [Region, ...]              via contest_regions
```

---

## Tables

### jurisdictions

Top-level jurisdiction (county, city, or governing entity).
All elections, regions, parties, ballot types, and users belong to one jurisdiction.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| name | VARCHAR NOT NULL UNIQUE | e.g. "Sample County" |
| address | VARCHAR | Mailing address |
| contact_email | VARCHAR | Elections office email |
| general_voting_instructions | TEXT | Printed at top of every ballot |

---

### elections

A specific election event tied to a jurisdiction.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| jurisdiction_id | BIGINT FK jurisdictions | Required |
| name | VARCHAR NOT NULL | e.g. "June 2026 Primary" |
| election_date | DATE | Optional |
| election_type | VARCHAR | PRIMARY, GENERAL, SPECIAL, RUNOFF |
| uniform_ballot | BOOLEAN | True = all voters get same ballot regardless of region or party |

---

### regions

A geographic or administrative unit for ballot assignment.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| jurisdiction_id | BIGINT FK jurisdictions | Required |
| name | VARCHAR NOT NULL | e.g. "Precinct 4", "Sample Water District" |
| region_type | VARCHAR NOT NULL | SINGLE_PRECINCT or PRECINCT_GROUP |
| group_type | VARCHAR | PrecinctGroups only: CITY, TOWN, WATER_DISTRICT, SCHOOL_DISTRICT, FIRE_DISTRICT, WARD, TOWNSHIP, OTHER |
| description | VARCHAR | Optional |

SINGLE_PRECINCT: an individual voting precinct. Voters belong to exactly one.
Ballot combinations are keyed to SinglePrecincts.

PRECINCT_GROUP: a named grouping spanning multiple SinglePrecincts.
Contests assigned to a PrecinctGroup automatically appear on every member
SinglePrecinct's ballot at print time.

---

### region_members (join table)

Links a PrecinctGroup to its constituent SinglePrecincts.

| Column | Type | Notes |
|---|---|---|
| group_id | BIGINT FK regions | The PrecinctGroup |
| member_id | BIGINT FK regions | A SinglePrecinct member |

---

### parties

Political parties, scoped to a jurisdiction. Used in primaries.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| jurisdiction_id | BIGINT FK jurisdictions | Required |
| name | VARCHAR NOT NULL | e.g. "Democratic", "Republican" |
| abbreviation | VARCHAR | e.g. "DEM", "REP" |

---

### ballot_types

Physical or procedural ballot types. Pre-seeded with common types; fully user-editable.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| jurisdiction_id | BIGINT FK jurisdictions | Required |
| name | VARCHAR NOT NULL | e.g. "Precinct", "Mail-In", "Absentee", "Provisional" |
| description | VARCHAR | Optional |

---

### contests

A race or ballot measure within an election.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| election_id | BIGINT FK elections | Required |
| title | VARCHAR NOT NULL | e.g. "U.S. Representative, District 2" |
| voting_method | VARCHAR | PLURALITY, RANKED_CHOICE, APPROVAL, MEASURE |
| max_choices | INT | Max candidates voter may select (plurality/approval) |
| max_rank_choices | INT | Max rank in ranked-choice (0 = unlimited) |
| display_order | INT | Ballot order (lower = earlier) |
| instructions | VARCHAR | Overrides default instruction text |
| explanatory_text | TEXT | Additional information |
| print_explanatory_text | BOOLEAN | Whether to print the explanatory text |
| explanatory_text_location | VARCHAR | Layout hint: BELOW_TITLE, RIGHT_COLUMN, etc. |

---

### contest_regions (join table)

Which regions a contest is assigned to.

| Column | Type | Notes |
|---|---|---|
| contest_id | BIGINT FK contests | |
| region_id | BIGINT FK regions | SinglePrecinct or PrecinctGroup |

---

### candidates

A candidate or option within a contest.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| contest_id | BIGINT FK contests | Required |
| name | VARCHAR NOT NULL | Candidate name, or "YES", "NO", "Write-In", etc. |
| write_in | BOOLEAN | True = write-in slot (prints blank fill line) |
| party_affiliation | VARCHAR | Party label printed next to name |
| display_order | INT | Order within the contest |
| explanatory_text | TEXT | Optional candidate note |
| print_explanatory_text | BOOLEAN | Whether to print the note |
| explanatory_text_location | VARCHAR | Layout hint: BELOW_NAME, RIGHT_OF_INDICATOR |

---

### ballot_combinations

Defines exactly what ballot a specific voter type receives.
Unique on (election_id, region_id, party_id, ballot_type_id).
region_id must refer to a SINGLE_PRECINCT region.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| election_id | BIGINT FK elections | Required |
| region_id | BIGINT FK regions | Must be SINGLE_PRECINCT |
| party_id | BIGINT FK parties | Required. Use the pre-seeded "Everyone" party for general/nonpartisan elections. |
| ballot_type_id | BIGINT FK ballot_types | Required |

Contest resolution at print time (ContestAssignmentService):
1. Contests directly assigned to region_id (the SinglePrecinct)
2. PrecinctGroups that include region_id as a member
3. Contests assigned to each of those PrecinctGroups
4. Deduplicated, sorted by display_order

---

### ballot_design_templates

Physical layout parameters for printed ballots. One per election, required before printing.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| election_id | BIGINT FK elections | One-to-one |
| paper_size | VARCHAR | LETTER_8_5x11, LEGAL_8_5x14, HALF_LETTER_8_5x5_5, HALF_LEGAL_8_5x7, A4, A3, A5 |
| vote_indicator_style | VARCHAR | OVAL (recommended for scanning), CHECKBOX. ARROW and NUMBER_FIELD are reserved for future use and not available in the UI. |
| columns | INT | Contest columns per page |
| margin_top_pt | FLOAT | Top margin in PDF points (72 pt = 1 inch) |
| margin_bottom_pt | FLOAT | |
| margin_left_pt | FLOAT | |
| margin_right_pt | FLOAT | |
| contest_title_font_size | FLOAT | Points |
| candidate_name_font_size | FLOAT | Points |
| header_font_size | FLOAT | Points (ballot metadata line) |
| instruction_font_size | FLOAT | Points |
| barcode_position | VARCHAR | TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT |
| multi_sheet | BOOLEAN | Print "Sheet N of M" indicators for multi-sheet ballots |

---

### print_logs

Audit trail: every ballot PDF generation event.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| printed_by_id | BIGINT FK users | Who triggered the print |
| ballot_combination_id | BIGINT FK ballot_combinations | Which combination |
| printed_at | DATETIME | Timestamp |
| copies | INT | Copies requested |
| paper_size | VARCHAR | Paper size snapshot at time of print |

---

### users

Application users with role-based access.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| username | VARCHAR NOT NULL UNIQUE | Login name |
| password_hash | VARCHAR NOT NULL | BCrypt. Never stored in plaintext. To reset the admin password: restart bBuilder with `-Dreset.admin.password=true` — a random password is generated and printed to stdout, then the server exits. |
| enabled | BOOLEAN | False = account disabled. Admins cannot disable their own account. |
| jurisdiction_id | BIGINT FK jurisdictions | User's jurisdiction |

---

### user_roles (element collection)

| Column | Type | Notes |
|---|---|---|
| user_id | BIGINT FK users | |
| role | VARCHAR | ADMIN, DATA_ENTRY, PRINTER |

ADMIN: full access — users, data entry, printing. Can add/edit/disable other users. Cannot disable their own account.
DATA_ENTRY: enter and edit election data; no printing.
PRINTER: generate ballot PDFs only; read-only data access.

All authenticated users can change their own password via `/account/password`.
Only ADMIN can manage other users.

---

## Workflow: Creating a Printable Ballot

Step 1. Admin > Jurisdictions — create your jurisdiction.
Step 2. Setup > Elections — create an election tied to that jurisdiction.
Step 3. Setup > Regions — create SinglePrecincts (one per voting precinct),
        then PrecinctGroups (districts, cities, etc.) and assign members.
Step 4. Setup > Parties — add parties if this is a primary election.
Step 5. Setup > Ballot Types — verify pre-seeded types or add your own.
Step 6. Setup > Contests — create contests, assign to regions, add candidates.
Step 7. Ballots > Combinations — create one BallotCombination per unique
        (Election + SinglePrecinct + Party + BallotType) variant.
Step 8. Ballots > Design Templates — create one template per election
        (paper size, vote indicator style, column count, fonts).
Step 9. Print Ballot — select a combination and generate the PDF.

Output files (PDF + YAML + XML) are written to `~/bBuilder_ballots/` by default.
Override via the `ballot.export.dir` property in `application.properties`.

---

## Machine Scanning Support

Each printed ballot includes:

**QR code + Code128 barcode:**
Encodes: `JurisdictionId|RegionId|PartyId|BallotTypeId|ElectionId|Page`
Having both formats supports 1D laser scanners (Code128) and 2D camera scanners (QR).

**Orientation marks:**
Solid black filled squares at top-left and top-right corners.
Scanning software uses these to detect upside-down or rotated images,
and to compute the homography for warping the image to canonical coordinates.

**Offset report (YAML or XML export):**
After generating a ballot, bBuilder exports the exact position of every vote
indicator relative to the content area upper-left corner, in inches.
Used by bCounter to locate and sample indicator regions.

**Ballot header:**
Small print at top: Jurisdiction | Region | Party | BallotType | Election [| Page N]
Both human-readable and encoded in the barcodes.

---

# bCounter Database (`counter_results.db`)

## Entity Relationship Overview

```
BarcodeRecord
  └── BallotImage (many per barcode — same ballot scanned multiple times)

BallotImage
  └── VoteOpportunity (many — one per candidate per page)

ContestRecord
  └── CandidateRecord (many per contest)

VoteOpportunity
  ├── BallotImage
  ├── ContestRecord
  └── CandidateRecord
```

---

## Tables

### barcode

A decoded barcode value. Parsed into constituent fields for querying.
One row per unique barcode string (i.e. per ballot combination + page).

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| raw_data | VARCHAR NOT NULL UNIQUE | Full barcode string e.g. "1\|1\|1\|1\|1\|1" |
| jurisdiction_id | VARCHAR | Parsed field 0 |
| region_id | VARCHAR | Parsed field 1 |
| party_id | VARCHAR | Parsed field 2 |
| ballot_type_id | VARCHAR | Parsed field 3 |
| election_id | VARCHAR | Parsed field 4 |
| page_number | VARCHAR | Parsed field 5 |

---

### ballot_image

One row per scanned image file. Unique on `image_path`.
If a duplicate path is attempted (parallel race condition), the constraint fires
and bCounter logs: `"Duplicate ballot skipped (parallel race) — already in database"`.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| image_path | VARCHAR NOT NULL UNIQUE | Absolute path to image file |
| image_name | VARCHAR NOT NULL | Filename only (for display) |
| counted_at | DATETIME | When this image was processed |
| dpi | INT | DPI detected or assumed from image metadata / width heuristic |
| page_number | INT | Page number decoded from barcode |
| was_rotated | BOOLEAN | True if image was auto-rotated 180° (upside-down detection) |
| corners_found | BOOLEAN | True if all four orientation marks were detected |
| warp_dpi | INT | DPI used for the canonical warped image |
| canonical_width | INT | Width of canonical content area in pixels |
| canonical_height | INT | Height of canonical content area in pixels |
| corner_marks | VARCHAR(200) | TL_x,TL_y,TR_x,TR_y,BR_x,BR_y,BL_x,BL_y in original image pixels |
| barcode_id | BIGINT FK barcode | Decoded barcode record |

---

### contest

One row per unique contest title+type combination seen across all scanned ballots.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| contest_title | VARCHAR NOT NULL | e.g. "City Council Member" |
| contest_type | VARCHAR NOT NULL | e.g. "PLURALITY" |
| max_votes | INT | Maximum votes allowed in this contest |

---

### candidate

One row per unique candidate within a contest.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| contest_id | BIGINT FK contest | Required |
| candidate_name | VARCHAR NOT NULL | e.g. "Anna Park" |
| ballot_candidate_id | BIGINT | Original candidate ID from the bBuilder YAML |
| write_in | BOOLEAN | True if this is a write-in slot |

---

### vote_opportunity

One row per indicator box per scanned image — the core scan result table.
Records both the pixel-level measurement and the final vote determination.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | Auto-generated |
| ballot_image_id | BIGINT FK ballot_image | Required |
| contest_id | BIGINT FK contest | Required |
| candidate_id_fk | BIGINT FK candidate | Required |
| abs_left | INT | Left edge of indicator in canonical image pixels |
| abs_top | INT | Top edge of indicator in canonical image pixels |
| indicator_width | INT | Indicator box width in canonical pixels |
| indicator_height | INT | Indicator box height in canonical pixels |
| warp_dpi | INT | DPI of the canonical image used for sampling |
| image_x | INT | Mapped x position in original (pre-warp) image pixels |
| image_y | INT | Mapped y position in original (pre-warp) image pixels |
| threshold | INT | Brightness threshold used (0–255); pixels below this are "dark" |
| dark_pct | DOUBLE | Percentage of indicator pixels below brightness threshold |
| dark_pixels | INT | Count of dark pixels in indicator region |
| total_pixels | INT | Total pixels sampled in indicator region |
| mean_intensity | DOUBLE | Mean luminance of all sampled pixels |
| vote_status | VARCHAR | UNMARKED, VOTED, OVERVOTED |

**vote_status rules:**
- VOTED: `dark_pct >= coverageThresholdPct` (default 7%) and contest not overvoted
- OVERVOTED: candidate was physically marked but contest has too many marks
- UNMARKED: `dark_pct < coverageThresholdPct` or candidate not marked in an overvoted contest

---

## Scan Configuration Parameters

These are set per scan session in the bCounter UI or via `run_counter.py`:

| Parameter | Default | Description |
|---|---|---|
| Brightness Threshold | 128 | Luminance value (0–255) below which a pixel counts as dark ink |
| Coverage Threshold (%) | 7.0 | Minimum % of indicator box pixels that must be dark to call it VOTED |
| Fallback DPI | 300 | Used when no DPI metadata and width heuristic fails |
| Assumed Paper Width | 8.5 in | Used to infer DPI from image pixel width |

---

# bViewer

bViewer opens the bCounter database read-only and uses a subset of the same
tables. It does not create or modify any data.

| bViewer Entity | Maps to bCounter table |
|---|---|
| BallotImageView | ballot_image |
| VoteOpportunityView | vote_opportunity |
| CandidateView | candidate |
| ContestView | contest |

