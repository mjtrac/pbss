# pbss Database Schemas

## bBuilder Database (`election_ballot.db`)

SQLite database storing the election design. Location: `ballot.datasource.url` (default: `${user.dir}/election_ballot.db`).

### Core tables

**`election`** — Top-level election record
| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PK | |
| `name` | TEXT | Election name |
| `election_date` | TEXT | ISO date |

**`jurisdiction`** — Geographic jurisdiction
| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PK | |
| `name` | TEXT | Jurisdiction name |
| `election_id` | INTEGER FK | → election.id |

**`party`** — Political party (or nonpartisan marker)
| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PK | |
| `name` | TEXT | Party name |
| `abbreviation` | TEXT | Short label |

**`ballot_type`** — A ballot type within a jurisdiction
| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PK | |
| `name` | TEXT | |
| `jurisdiction_id` | INTEGER FK | |

**`ballot_combination`** — A specific combination of election/jurisdiction/party/type
| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PK | |
| `election_id` | INTEGER FK | |
| `jurisdiction_id` | INTEGER FK | |
| `party_id` | INTEGER FK | |
| `ballot_type_id` | INTEGER FK | |

**`contest`** — A race or measure on a ballot
| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PK | |
| `title` | TEXT | |
| `contest_type` | TEXT | PLURALITY / RANKED_CHOICE / YES_NO / APPROVAL |
| `max_votes` | INTEGER | |
| `ballot_combination_id` | INTEGER FK | |
| `page_number` | INTEGER | Which page this contest appears on |
| `sort_order` | INTEGER | Display order |

**`candidate`** — A candidate or option within a contest
| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PK | |
| `name` | TEXT | |
| `contest_id` | INTEGER FK | |
| `sort_order` | INTEGER | |
| `is_write_in` | INTEGER | 0/1 |

---

## bCounter Database (`counter_results.db`)

SQLite database storing scanning results. Location: `spring.datasource.url` (default: `${user.dir}/counter_results.db`).

### Tables

**`barcode`** — One row per unique barcode value scanned
| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PK | |
| `barcode_data` | TEXT | Raw barcode string `election|jurisdiction|party|ballotType|combination|page` |
| `election_id` | INTEGER | |
| `jurisdiction_id` | INTEGER | |
| `party_id` | INTEGER | |
| `ballot_type_id` | INTEGER | |
| `combination_id` | INTEGER | |
| `page_number` | INTEGER | |

**`ballot_image`** — One row per scanned image file
| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PK | |
| `image_path` | TEXT | Absolute path to the image file |
| `barcode_id` | INTEGER FK | → barcode.id |
| `scan_time` | TEXT | ISO timestamp |
| `dpi` | INTEGER | Detected image DPI |
| `image_width` | INTEGER | Pixels |
| `image_height` | INTEGER | Pixels |
| `corners_found` | INTEGER | 0/1 |
| `was_rotated` | INTEGER | 0/1 — image was flipped 180° |
| `warp_dpi` | INTEGER | DPI used for homography warp |
| `canonical_width` | INTEGER | Warped image width in pixels |
| `canonical_height` | INTEGER | Warped image height in pixels |
| `corner_marks` | TEXT | JSON array of 4 corner mark centres [[x,y],...] |
| `bbox_tl_x`, `bbox_tl_y` | INTEGER | Top-left bbox corner in image pixels |
| `bbox_tr_x`, `bbox_tr_y` | INTEGER | Top-right bbox corner |
| `bbox_br_x`, `bbox_br_y` | INTEGER | Bottom-right bbox corner |
| `bbox_bl_x`, `bbox_bl_y` | INTEGER | Bottom-left bbox corner |
| `error_message` | TEXT | Non-null if ballot requires review |
| `review_required` | INTEGER | 0/1 |

**`contest`** — Contests found on scanned ballots
| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PK | |
| `barcode_id` | INTEGER FK | |
| `contest_id` | INTEGER | From design YAML |
| `title` | TEXT | |
| `contest_type` | TEXT | |
| `max_votes` | INTEGER | |
| `page_number` | INTEGER | |

**`candidate`** — Candidates within contests
| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PK | |
| `contest_id` | INTEGER FK | → contest.id |
| `candidate_id` | INTEGER | From design YAML |
| `name` | TEXT | |
| `is_write_in` | INTEGER | 0/1 |
| `sort_order` | INTEGER | |

**`vote_opportunity`** — One row per candidate per scanned ballot image
| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PK | |
| `ballot_image_id` | INTEGER FK | → ballot_image.id |
| `candidate_id` | INTEGER FK | → candidate.id |
| `contest_id` | INTEGER FK | → contest.id |
| `marked` | INTEGER | 0/1 — was the indicator filled in? |
| `dark_pct` | REAL | Fraction of dark pixels in the indicator region |
| `mean_intensity` | REAL | Mean pixel luminance (0=black, 255=white) |
| `indicator_left` | INTEGER | Indicator left edge in canonical pixels |
| `indicator_top` | INTEGER | Indicator top edge in canonical pixels |
| `indicator_width` | INTEGER | Width in canonical pixels |
| `indicator_height` | INTEGER | Height in canonical pixels |
