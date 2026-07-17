# Ballot Design Portability — What's Pluggable, What Isn't

bCounter's scanning pipeline (`ScannerService`) was built around one ballot
design: the layout `bBuilder` generates. This document catalogs every point
in that pipeline where a *different* ballot design's physical conventions
(mark shapes, layout codes, indicator styles, contest semantics) would need
new code versus just a new YAML file, so that supporting a foreign/vendor
ballot doesn't require reverse-engineering the pipeline from scratch.

See [`ballot-yaml-schema.md`](ballot-yaml-schema.md) for the YAML field
reference. This document is about the *code* that consumes that YAML.

---

## 1. Already pluggable (Spring strategy interfaces)

Two stages are genuine strategy interfaces — swap the implementation by
adding a new `@Service`/`@Component` bean and marking it `@Primary` (or use
`@Qualifier` to select at injection time). No existing code needs to change.

### `BallotCornerDetectorService` — corner/orientation detection
`bCounter/src/main/java/com/mjtrac/counter/service/BallotCornerDetectorService.java`

- Default impl: `CornerDetectionService`. Finds the content-box border line,
  then locates registration marks below/beside it.
- **Bakes in bBuilder's specific mark geometry**: marks are 9pt squares
  (TR/BR/BL) and one 18pt rectangle (TL), sitting a fixed 22px-at-300dpi gap
  below the border line (`MARK_H_IN`, `MARK_SQ_W_IN`, `MARK_RECT_W_IN`,
  `MARK_BELOW_GAP_IN` constants). It also *requires* `layout.cornerMarks`
  from the YAML — with `layout == null` it returns `null` outright, so it
  cannot cold-start on an image with no matching YAML hints.
- To support a design with different marks (ArUco markers, timing-mark
  strips, no printed marks at all — just page edges from a known scanner
  feed), implement a new class against this interface. The Javadoc on the
  interface already lists the expected alternatives (template matching,
  Hough-line edge detection, fixed-geometry calculation, manual click).

### `BallotIdentifierService` — layout/QR/barcode identification
`bCounter/src/main/java/com/mjtrac/counter/service/BallotIdentifierService.java`

- Default impl: `BarcodeReaderService` (QR / Code-128).
- The interface only requires that `identify()` return a
  `barcodeData` string that `BboxReportLoader.loadForBarcode` can resolve to
  a YAML file — it does not have to be a barcode at all. OCR, filename
  parsing, template matching, or manual selection are explicitly named as
  valid alternatives in the Javadoc.
- This is the lowest-friction extension point: a foreign ballot with no
  machine-readable code at all can still work if you supply an
  implementation that derives the key some other way (e.g. by prompting
  the operator, or by filename convention during a pilot run).

---

## 2. Not pluggable — concrete classes with hardcoded, if/else dispatch

These would need direct edits (or a refactor into a strategy interface) to
handle something outside their current cases.

### `MarkerAnalysisService` — is this indicator marked?
`bCounter/src/main/java/com/mjtrac/counter/service/MarkerAnalysisService.java`

Dispatches on the YAML `indicatorStyle` string via `if/else`, not DI:

| `indicatorStyle` | Logic |
|---|---|
| `ARROW` | Delegates to `ArrowIndicatorAnalyzer` — checks for any dark pixel in a centered zone 1/4 the box size (voter completes a broken arrow). |
| `CONNECT_DOTS` | Checks a centered 10%-width vertical stripe for any dark pixel (voter connects two arrow tips). |
| anything else (`OVAL`, `CHECKBOX`, `RECTANGLE`, default) | Dark-pixel-percentage-of-bounding-box ≥ `darkPctMin`, with a 1px edge-trim retry for borderline (5–10%) coverage. |

The default density-based path is shape-agnostic by construction — it
doesn't care if the box is an oval, a square checkbox, or a box the voter
fills with an X, only that a "voted" mark darkens a large fraction of the
box. **Most vendor indicator conventions will work under the default path
with no code change** — only correct bounding boxes in the YAML and
reasonable `threshold`/`darkPctMin` session settings are needed.

A new style only requires code here if it can't be reduced to "some
dark-pixel test inside/around the box" — e.g. a specific pen-ink color
check, or an OMR timing-strip code. Right now that means editing this file
directly (adding another `if ("YOURSTYLE".equalsIgnoreCase(...))` branch).
`ArrowIndicatorAnalyzer` shows the pattern to follow if you want to keep new
styles in their own file, but there is no interface for it to implement —
consider introducing one (mirroring `BallotCornerDetectorService`) if you
expect to add several exotic styles rather than just one or two.

### `ScribbleDetectionService` — stray-ink / write-in area flagging
`bCounter/src/main/java/com/mjtrac/counter/service/ScribbleDetectionService.java`

- Flags dark pixels found outside every declared indicator zone.
- `DARK_THRESHOLD = 128` is a compile-time constant, **not** a session
  setting (unlike `MarkerAnalysisService`'s `threshold`/`darkPctMin`, which
  are configurable per scan session). Different paper stock or scan
  contrast on a foreign ballot may need this threshold tuned — currently
  that means editing the constant, not a config change.

### `RcvTabulationService` — ranked-choice tabulation
`bCounter/src/main/java/com/mjtrac/counter/service/RcvTabulationService.java`

- The elimination algorithm (parallel elimination / IRV) is generic to the
  *voting method*, not the ballot's visual design — no change needed there.
- But it identifies which `IndicatorBox` rows belong to which rank via a
  **hardcoded regex on `candidateName`**: `^(.+?)\s+\(Rank\s+(\d+)\)$`.
  Any YAML feeding this service — including one produced by a generalized
  ballot-mapper for a foreign RCV design — **must** encode candidate names
  with a literal `" (Rank N)"` suffix, regardless of how the source ballot
  actually labels its rank columns (numerals, "1st/2nd/3rd", letters,
  etc.). This is a required naming convention to document for anyone
  building a new YAML generator, not a detection gap.

### Schema vs. implementation gap: `contestType`
`docs/ballot-yaml-schema.md` documents four valid `contestType` values:
`PLURALITY`, `RANKED_CHOICE`, `YES_NO`, `APPROVAL`. In code, only three
strings are ever tested:

- `RcvTabulationService` — matches ranked contests via the rank-suffix
  regex above (not `contestType` directly).
- `VoteTallyService.tallyOneImage()` — the overvote check only fires when
  `contestType` is exactly `"PLURALITY"` or `"MEASURE"` (`fptp` boolean,
  around line 278). **`YES_NO` and `APPROVAL` contests currently get no
  overvote enforcement at all** — every marked option is recorded
  regardless of `maxVotes`. `MEASURE` is recognized here but isn't in the
  schema doc's list. If you're generating YAML for a foreign ballot with
  yes/no or approval-voting contests, be aware the tally will silently
  accept overvotes on them today; either use `PLURALITY` with `maxVotes: 1`
  as a workaround, or extend the `fptp` condition in `VoteTallyService`.

---

## 3. Configuration knobs (no code change needed)

Already exposed per-scan-session, useful first-line tuning before touching
any of the above:

- `session.threshold` — luminance cutoff for "dark" pixel (`MarkerAnalysisService`).
- `session.darkPctMin` — % of box that must be dark to count as marked.
- `session.assumedPaperWidthIn` — fallback for the DPI heuristic when image
  metadata lacks DPI (`ScannerService`, defaults to 8.5"); paired with the
  `STANDARD_DPIS` snap list (`{72, 96, 150, 200, 300, 400, 600}`).

---

## 4. The realistic deployment model: one vendor per instance

In practice a given pbss deployment counts ballots from **one** vendor's
design, not an arbitrary mix. That changes the requirement from "detect any
ballot design at runtime" to "calibrate once from a handful of sample
images, then run fast and reliably against that one design from then on."
`misc/design_profile.py` already exists to do exactly this, and it is
further along than the rest of this document might suggest — but the bridge
from "learned profile" to "running inside bCounter" is currently broken.

### What already works (Python side)

`DesignProfile.learn_from_annotation(cv_img, dpi, ...)` — given one or two
annotated sample images, records:
- corner/page-mark templates as cropped image patches (`MarkTemplate`,
  stored base64) plus their offset/size geometry,
- column count and divider positions as content-width **fractions**
  (`ColumnProfile`) — resolution-independent,
- contest separator characteristics (rule-line thickness vs. whitespace-gap
  height — `ContestSeparatorProfile`),
- candidate row geometry (`CandidateRowProfile`) via
  `learn_candidate_geometry()`.

`DesignProfile.save()`/`.load()` persist this as one portable file per
vendor design. `find_corners()` and `find_contest_boundaries()` then apply
the learned profile via OpenCV template matching (`cv2.matchTemplate`) —
fast, since it searches a small window near the *expected* mark position
instead of the full blob/border-line search `CornerDetectionService` does
cold. This is the right shape for "calibrate once, run fast on that vendor
from then on."

### What's broken: the Python → Java bridge

`generate_java_service()` (design_profile.py:634) is meant to export a
learned profile as a `BallotCornerDetectorService` bean bCounter can load
directly — its own header comment says *"Drop-in replacement for bCounter's
CornerDetectionService... can be substituted without other changes."* That
claim doesn't hold today:

| | Real interface (`BallotCornerDetectorService`) | Generated class (`CornerDetectionService_custom`) |
|---|---|---|
| Method | `findContentBoxCorners(BufferedImage[], int, double, double, PageLayout, double, double)` | `findCornerMarks(Mat, double)` |
| Return | `Point2D[4]` (`[TL,TR,BR,BL]`, `com.mjtrac.counter.service.Point2D`) | `Map<String,Point2D>` (its own nested record) |
| Image type | `java.awt.image.BufferedImage` | `org.opencv.core.Mat` |
| Interface implemented | — | **none** — it doesn't `implements BallotCornerDetectorService` at all |

It also imports `org.opencv.core.*`/`org.opencv.imgproc.Imgproc`, and
**`bCounter/pom.xml` has no OpenCV/JavaCV dependency** — confirmed by
`grep -n "opencv" bCounter/pom.xml` returning nothing. The rest of
bCounter's image pipeline (`CornerDetectionService`, `MarkerAnalysisService`,
`ScribbleDetectionService`) is deliberately pure `java.awt.image`, so this
generated file can't be dropped in as-is; it would need a new native
dependency just for this one class, or the generator needs to stop
targeting OpenCV.

**Fixed.** `generate_java_service()` now emits a class that actually
`implements BallotCornerDetectorService` with the exact method signature
and `Point2D[4]`/`[TL,TR,BR,BL]` return contract, does template matching in
pure `java.awt` (coarse-to-fine normalized cross-correlation over a small
window near each mark's expected position, scaled for DPI), and converts
matched marks to content-box corners via the same local-axis projection
`CornerDetectionService.marksToBboxCorners` uses — no OpenCV dependency
added. It also works when `layout` is `null`, since all its geometry comes
from the learned profile rather than the YAML. Verified by generating a
real profile and compiling the output against bCounter's actual classpath
(`./mvnw compile`) — no errors.

The generated class is `@Primary`; its header comment now correctly warns
that `CornerDetectionService.java`'s own `@Primary` must be removed (or the
file deleted) before dropping the generated one in, since Spring refuses to
start with two `@Primary` beans for the same interface.

This closes the loop the user is describing: point the mapper/profile
learner at a few sample scans from the one vendor a given deployment
serves, generate a real `@Primary @Service` bean from that profile, and
bCounter runs fast, template-matched corner detection tuned to that vendor
from then on — no per-image blob search, no OpenCV dependency added.

---

## 5. Recommended path for onboarding a new (non-bBuilder) ballot design

1. **Generate a YAML layout** for the new design using the ballot-mapper
   tooling (`misc/ballot_mapper_v25.py` et al.) — its manual capture steps
   (click corners, drag columns/contests/indicators) are already
   shape-agnostic since a human drives them; only its *auto-assist* fitting
   (dashed-line closing kernel, fixed gray threshold 192) is tuned toward
   bBuilder's own rendering and may need re-tuning per source image.
2. **Corner marks**: if the new design's registration marks don't match
   bBuilder's 9pt/18pt convention (or it has none), implement
   `BallotCornerDetectorService`. If it uses conventional printed marks just
   at different sizes/positions, `CornerDetectionService`'s YAML-hint
   fallback path may already suffice — check `layout.cornerMarks`/
   `layout.pageMarks` are populated correctly by the mapper before writing
   new Java.
3. **Layout code**: verify `BarcodeReaderService` decodes the new design's
   symbology (QR / Code-128 both work already). If it uses a different code
   or none, implement `BallotIdentifierService`.
4. **Indicator style**: leave `indicatorStyle` as `OVAL`/`CHECKBOX`/omitted
   unless the mark truly isn't a "some region gets dark when voted" pattern
   — the default path in `MarkerAnalysisService` already covers most real
   ballot conventions.
5. **Ranked-choice**: encode `" (Rank N)"` in `candidateName` if using
   `RANKED_CHOICE`.
6. **Yes/No or approval contests**: be aware of the overvote-enforcement gap
   in §2 above until `VoteTallyService` is extended.
