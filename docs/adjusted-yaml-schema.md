# bSuite Adjusted YAML Schema

bCounter writes one adjusted YAML file per scanned image alongside the image file.
The filename is `<original_image_name>_adjusted.yaml`.

The adjusted YAML translates all ballot layout coordinates from the source YAML
into **image pixel coordinates** (origin = image top-left), accounting for the
homography (perspective correction) applied to align the scanned image.

---

## Top-level fields

| Field | Type | Description |
|---|---|---|
| `sourceYaml` | string | Absolute path to the ballot design YAML used |
| `sourceImage` | string | Absolute path to the scanned image file |
| `pageNumber` | int | Page number of this scan |
| `dpi` | int | Detected image DPI |
| `cornersFound` | string | `"true"` if corner detection succeeded |
| `transformType` | string | Always `perspective_homography` |
| `note` | string | Coordinate system description |
| `ballotContentArea` | object | Content area in image pixels |
| `contests` | list | Contests with all coordinates in image pixels |

---

## `ballotContentArea`

| Field | Type | Description |
|---|---|---|
| `theoreticalLeft_in` | float | Expected left offset in inches (from design YAML) |
| `theoreticalTop_in` | float | Expected top offset in inches |
| `theoreticalWidth_in` | float | Expected width in inches |
| `theoreticalHeight_in` | float | Expected height in inches |
| `topLeft` | object | Detected TL corner `{x, y}` in image pixels |
| `topRight` | object | Detected TR corner `{x, y}` in image pixels |
| `bottomRight` | object | Detected BR corner `{x, y}` in image pixels |
| `bottomLeft` | object | Detected BL corner `{x, y}` in image pixels |
| `boundingRect` | object | Axis-aligned bounding rect `{x, y, width, height}` in pixels |

---

## `contests[]` entry

| Field | Type | Description |
|---|---|---|
| `id` | int | Contest ID |
| `title` | string | Contest title |
| `boundingBox` | object | Contest bounding box in image pixels |
| `candidates` | list | Candidates with pixel coordinates |

### `candidates[]` → `indicator`

| Field | Type | Description |
|---|---|---|
| `theoreticalLeft_in` | float | Expected left in inches |
| `theoreticalTop_in` | float | Expected top in inches |
| `theoreticalWidth_in` | float | Expected width in inches |
| `theoreticalHeight_in` | float | Expected height in inches |
| `topLeft` | object | `{x, y}` in image pixels |
| `topRight` | object | `{x, y}` in image pixels |
| `bottomRight` | object | `{x, y}` in image pixels |
| `bottomLeft` | object | `{x, y}` in image pixels |
| `boundingRect` | object | `{x, y, width, height}` in pixels |

All pixel coordinates use **image pixel space**: origin at top-left of the image,
x increases rightward, y increases downward. These are the actual detected positions
after perspective correction, not theoretical positions from the design.
