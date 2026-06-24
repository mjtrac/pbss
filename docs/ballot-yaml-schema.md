# bSuite Ballot YAML Schema

Each ballot combination generates one YAML file per page, written to `ballot.export.dir`
(default: the bBuilder working directory). The filename pattern is:
`ballot_<election>_<jurisdiction>_<party>_<ballotType>_<combination>_<page>.yaml`

---

## Top-level fields

| Field | Type | Description |
|---|---|---|
| `election` | int | Election ID |
| `jurisdiction` | int | Jurisdiction ID |
| `party` | int | Party ID (0 = nonpartisan) |
| `ballotType` | int | Ballot type ID |
| `combination` | int | Ballot combination ID |
| `pageNumber` | int | Page number (1-based) |
| `pageWidth` | float | Page width in inches |
| `pageHeight` | float | Page height in inches |
| `sides` | list | One entry per ballot side (always 1 for single-sided) |

---

## `sides[]` entry

| Field | Type | Description |
|---|---|---|
| `sideNumber` | int | Side number (1-based) |
| `ballotContentArea` | object | Bounding box of the printable content area |
| `cornerMarks` | list | Four registration marks at corners of the content area |
| `barcodeCentre` | object | Centre of the QR barcode in page-absolute inches |
| `barcodeCentreInches` | object | Same as barcodeCentre (legacy alias) |
| `contests` | list | All contests on this side |

### `ballotContentArea`

| Field | Type | Description |
|---|---|---|
| `offsetFromLeft` | float | Distance from page left edge to content area left edge (inches) |
| `offsetFromTop` | float | Distance from page top edge to content area top edge (inches, image coords) |
| `width` | float | Content area width (inches) |
| `height` | float | Content area height (inches) |

### `cornerMarks[]` entry

| Field | Type | Description |
|---|---|---|
| `corner` | string | One of: `TL`, `TR`, `BR`, `BL` |
| `x` | float | Mark centre x in page-absolute inches from page left |
| `y` | float | Mark centre y in page-absolute inches from page top (image coords) |

The TL mark is a rectangle (~0.25" × 0.125"); the other three are squares (~0.125" × 0.125").

### `barcodeCentre`

| Field | Type | Description |
|---|---|---|
| `x` | float | Barcode centre x in page-absolute inches |
| `y` | float | Barcode centre y in page-absolute inches |

---

## `contests[]` entry

| Field | Type | Description |
|---|---|---|
| `id` | int | Contest ID |
| `title` | string | Contest title as printed on ballot |
| `contestType` | string | `PLURALITY`, `RANKED_CHOICE`, `YES_NO`, `APPROVAL` |
| `maxVotes` | int | Maximum votes allowed (1 for plurality) |
| `candidates` | list | Candidates/options in ballot order |

### `candidates[]` entry

| Field | Type | Description |
|---|---|---|
| `id` | int | Candidate ID |
| `name` | string | Candidate name as printed |
| `indicator` | object | The fill-in oval/checkbox for this candidate |

### `indicator`

| Field | Type | Description |
|---|---|---|
| `offsetFromLeft` | float | Indicator centre-left in page-absolute inches |
| `offsetFromTop` | float | Indicator centre-top in page-absolute inches (image coords) |
| `width` | float | Indicator width in inches |
| `height` | float | Indicator height in inches |

All coordinates are **page-absolute** — measured from the page top-left corner, not from the content area.
