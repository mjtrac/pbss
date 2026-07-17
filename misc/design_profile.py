"""
design_profile.py — Ballot design profile: learn, save, load, apply.

A DesignProfile captures the visual grammar of a ballot format:
  - Corner mark geometry and appearance (as image templates)
  - Column structure (count and divider positions as content-width fractions)
  - Contest separator characteristics (line thickness, gap height)
  - Candidate row geometry (height, indicator size and position)

Once learned from one or two annotated examples it can:
  - Auto-detect corners on new images via template matching
  - Auto-segment columns and contests without user clicks
  - Generate corner_detector.py (Python verification tool)
  - Generate CornerDetectionService_custom.java (bCounter integration)
"""

import base64
import json
import math
import re
from pathlib import Path
from dataclasses import dataclass, field, asdict
from typing import Optional

import cv2
import numpy as np

# ── Data structures ───────────────────────────────────────────────────────────

@dataclass
class MarkTemplate:
    """One corner/page mark: geometry + appearance template."""
    label:               str      = ""        # TL, TR, BL, BR, PTL, PTR
    offset_from_corner_x_in: float = 0.0     # signed offset from content corner
    offset_from_corner_y_in: float = 0.0
    width_in:            float   = 0.0
    height_in:           float   = 0.0
    match_threshold:     float   = 0.75      # template match score threshold
    template_b64:        str     = ""        # base64-encoded PNG of the mark

    def has_template(self):
        return bool(self.template_b64)

    def get_template_image(self):
        """Decode base64 template to OpenCV grayscale image."""
        data = base64.b64decode(self.template_b64)
        arr  = np.frombuffer(data, dtype=np.uint8)
        img  = cv2.imdecode(arr, cv2.IMREAD_GRAYSCALE)
        return img

    def set_template_from_cv(self, gray_crop):
        """Encode a grayscale crop as base64 PNG."""
        ok, buf = cv2.imencode(".png", gray_crop)
        if ok:
            self.template_b64 = base64.b64encode(buf.tobytes()).decode()


@dataclass
class ColumnProfile:
    count:              int         = 2
    divider_x_fracs:    list        = field(default_factory=list)
    # fractions of content width where column dividers fall


@dataclass
class ContestSeparatorProfile:
    type:               str         = "horizontal_line"  # or "whitespace"
    min_gap_px:         int         = 4
    darkness_threshold: float       = 0.35
    line_thickness_px:  int         = 2


@dataclass
class CandidateRowProfile:
    height_in:                  float = 0.28
    indicator_offset_left_in:   float = 0.08
    indicator_width_in:         float = 0.22
    indicator_height_in:        float = 0.11


@dataclass
class ContentAreaProfile:
    offset_left_in:  float = 0.0
    offset_top_in:   float = 0.0
    width_in:        float = 0.0
    height_in:       float = 0.0


@dataclass
class DesignProfile:
    format_name:         str                         = "Unknown Ballot Format"
    dpi_reference:       int                         = 300
    content_area:        ContentAreaProfile           = field(default_factory=ContentAreaProfile)
    corner_marks:        dict                        = field(default_factory=dict)  # label → MarkTemplate
    page_marks:          dict                        = field(default_factory=dict)  # PTL, PTR
    columns:             ColumnProfile               = field(default_factory=ColumnProfile)
    contest_separator:   ContestSeparatorProfile     = field(default_factory=ContestSeparatorProfile)
    candidate_row:       CandidateRowProfile          = field(default_factory=CandidateRowProfile)
    training_images:     list                        = field(default_factory=list)
    notes:               str                         = ""

    # ── Serialisation ─────────────────────────────────────────────────────────

    def save(self, path: str):
        def _serialise(obj):
            if hasattr(obj, '__dataclass_fields__'):
                return {k: _serialise(v) for k, v in asdict(obj).items()}
            if isinstance(obj, dict):
                return {k: _serialise(v) for k, v in obj.items()}
            if isinstance(obj, list):
                return [_serialise(i) for i in obj]
            return obj

        d = _serialise(self)
        # corner/page marks need special handling since they're dicts of MarkTemplate
        d['corner_marks'] = {k: asdict(v) for k, v in self.corner_marks.items()}
        d['page_marks']   = {k: asdict(v) for k, v in self.page_marks.items()}

        with open(path, 'w') as f:
            json.dump(d, f, indent=2)
        print(f"Design profile saved: {path}")

    @classmethod
    def load(cls, path: str) -> 'DesignProfile':
        with open(path) as f:
            d = json.load(f)

        p = cls()
        p.format_name     = d.get('format_name', '')
        p.dpi_reference   = d.get('dpi_reference', 300)
        p.notes           = d.get('notes', '')
        p.training_images = d.get('training_images', [])

        ca = d.get('content_area', {})
        p.content_area = ContentAreaProfile(**ca)

        p.corner_marks = {k: MarkTemplate(**v)
                          for k, v in d.get('corner_marks', {}).items()}
        p.page_marks   = {k: MarkTemplate(**v)
                          for k, v in d.get('page_marks', {}).items()}

        col = d.get('columns', {})
        p.columns = ColumnProfile(**col)

        sep = d.get('contest_separator', {})
        p.contest_separator = ContestSeparatorProfile(**sep)

        row = d.get('candidate_row', {})
        p.candidate_row = CandidateRowProfile(**row)

        print(f"Design profile loaded: {path}  ({p.format_name})")
        return p

    # ── Corner detection ──────────────────────────────────────────────────────

    def find_corners(self, cv_img, dpi=None) -> dict:
        """
        Locate corner marks on a new ballot image using template matching.
        Returns dict: label → (x, y) pixel coords of mark centre.
        Falls back to content-area geometry if template matching fails.

        Returns {} if profile has no templates yet (untrained).
        """
        if dpi is None:
            dpi = self.dpi_reference

        results = {}
        gray    = cv2.cvtColor(cv_img, cv2.COLOR_BGR2GRAY)
        h, w    = gray.shape

        all_marks = {**self.corner_marks, **self.page_marks}
        for label, mark in all_marks.items():
            if not mark.has_template():
                continue
            tmpl = mark.get_template_image()
            if tmpl is None:
                continue

            # Scale template to current DPI if needed
            if dpi != self.dpi_reference and self.dpi_reference > 0:
                scale = dpi / self.dpi_reference
                th    = max(1, int(tmpl.shape[0] * scale))
                tw    = max(1, int(tmpl.shape[1] * scale))
                tmpl  = cv2.resize(tmpl, (tw, th))

            tmpl_h, tmpl_w = tmpl.shape

            # Search in a quadrant near the expected corner
            search_x0, search_y0, search_x1, search_y1 = \
                self._search_region(label, w, h, dpi)

            roi = gray[search_y0:search_y1, search_x0:search_x1]
            if roi.shape[0] < tmpl_h or roi.shape[1] < tmpl_w:
                continue

            res   = cv2.matchTemplate(roi, tmpl, cv2.TM_CCOEFF_NORMED)
            _, max_val, _, max_loc = cv2.minMaxLoc(res)

            if max_val >= mark.match_threshold:
                mx = search_x0 + max_loc[0] + tmpl_w // 2
                my = search_y0 + max_loc[1] + tmpl_h // 2
                results[label] = (mx, my)
            else:
                # Fall back to geometry-based estimate
                est = self._geometry_estimate(label, w, h, dpi)
                if est:
                    results[label] = est
                print(f"  ⚠  Template match for {label} score={max_val:.2f} "
                      f"< threshold {mark.match_threshold:.2f} — using estimate")

        return results

    def _search_region(self, label, img_w, img_h, dpi):
        """Return (x0, y0, x1, y1) search region for a given mark label."""
        pad = int(dpi * 1.5)   # search within 1.5" of expected corner
        if label in ('TL', 'PTL'):
            return 0, 0, min(img_w, pad*2), min(img_h, pad*2)
        elif label in ('TR', 'PTR'):
            return max(0, img_w - pad*2), 0, img_w, min(img_h, pad*2)
        elif label == 'BL':
            return 0, max(0, img_h - pad*2), min(img_w, pad*2), img_h
        elif label == 'BR':
            return max(0, img_w - pad*2), max(0, img_h - pad*2), img_w, img_h
        else:
            return 0, 0, img_w, img_h

    def _geometry_estimate(self, label, img_w, img_h, dpi):
        """Estimate mark position from content area geometry."""
        ca  = self.content_area
        if ca.width_in == 0:
            return None
        left  = int(ca.offset_left_in * dpi)
        top   = int(ca.offset_top_in  * dpi)
        right = int((ca.offset_left_in + ca.width_in)  * dpi)
        bot   = int((ca.offset_top_in  + ca.height_in) * dpi)
        lut   = {'TL': (left, top), 'TR': (right, top),
                 'BR': (right, bot), 'BL': (left,  bot),
                 'PTL': (left,  top), 'PTR': (right, top)}
        return lut.get(label)

    # ── Column / contest segmentation ─────────────────────────────────────────

    def column_x_ranges(self, content_x0, content_x1):
        """
        Return list of (x0, x1) column ranges in pixels given content area bounds.
        """
        width = content_x1 - content_x0
        divs  = sorted(self.columns.divider_x_fracs)
        xs    = [content_x0] + [int(content_x0 + f * width) for f in divs] + [content_x1]
        return [(xs[i], xs[i+1]) for i in range(len(xs)-1)]

    def find_contest_boundaries(self, cv_img, col_x0, col_y0, col_x1, col_y1):
        """
        Find contest top-y positions within a column using the learned
        contest separator profile (horizontal line or whitespace gap).
        Returns list of y pixel values marking the TOP of each contest.
        """
        sep   = self.contest_separator
        h, w  = cv_img.shape[:2]
        rx0   = max(0, col_x0); rx1 = min(w, col_x1)
        ry0   = max(0, col_y0); ry1 = min(h, col_y1)

        roi   = cv_img[ry0:ry1, rx0:rx1]
        gray  = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)

        if sep.type == "horizontal_line":
            boundaries = self._find_separator_lines(
                gray, sep.darkness_threshold, sep.line_thickness_px)
        else:
            boundaries = self._find_whitespace_gaps(gray, sep.min_gap_px)

        return [ry0 + y for y in boundaries]

    def _find_separator_lines(self, gray, darkness_threshold, min_thickness):
        """Find dark horizontal lines (contest separators)."""
        _, thresh = cv2.threshold(gray, 0, 255,
                                  cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
        row_darkness = np.mean(thresh == 255, axis=1)
        h = len(row_darkness)
        boundaries = [0]    # first contest starts at top
        in_line = False
        line_start = 0
        for y in range(h):
            dark = row_darkness[y] > darkness_threshold
            if dark and not in_line:
                in_line = True; line_start = y
            elif not dark and in_line:
                in_line = False
                if y - line_start >= min_thickness:
                    boundaries.append((line_start + y) // 2)
        return boundaries

    def _find_whitespace_gaps(self, gray, min_gap_px):
        """Find white horizontal gaps (contest separators)."""
        _, thresh  = cv2.threshold(gray, 200, 255, cv2.THRESH_BINARY)
        row_white  = np.mean(thresh == 255, axis=1)
        h          = len(row_white)
        boundaries = [0]
        in_gap     = False; gap_start = 0
        for y in range(h):
            if row_white[y] > 0.90 and not in_gap:
                in_gap = True; gap_start = y
            elif row_white[y] <= 0.90 and in_gap:
                in_gap = False
                if y - gap_start >= min_gap_px:
                    boundaries.append((gap_start + y) // 2)
        return boundaries

    # ── Learn from annotation ─────────────────────────────────────────────────

    def learn_from_annotation(self, cv_img, dpi,
                              corner_clicks,   # dict label→(x,y) pixel
                              col_divider_xs,  # list of x pixels
                              contest_tops,    # list of y pixels (first contest top per col)
                              mark_crops):     # dict label→(x0,y0,x1,y1) crop region
        """
        Update this profile from a user-annotated example.
        Called after the user has clicked corners, dividers, and contest tops
        and drawn rectangles around each corner mark.
        """
        ca_tl = corner_clicks.get('TL'); ca_tr = corner_clicks.get('TR')
        ca_bl = corner_clicks.get('BL'); ca_br = corner_clicks.get('BR')

        if not all([ca_tl, ca_tr, ca_bl, ca_br]):
            print("⚠  Cannot learn: missing corner clicks")
            return

        # Content area geometry in inches
        ca_x0 = min(ca_tl[0], ca_bl[0])
        ca_x1 = max(ca_tr[0], ca_br[0])
        ca_y0 = min(ca_tl[1], ca_tr[1])
        ca_y1 = max(ca_bl[1], ca_br[1])
        self.content_area = ContentAreaProfile(
            offset_left_in = round(ca_x0 / dpi, 4),
            offset_top_in  = round(ca_y0 / dpi, 4),
            width_in       = round((ca_x1 - ca_x0) / dpi, 4),
            height_in      = round((ca_y1 - ca_y0) / dpi, 4),
        )
        ca_w  = ca_x1 - ca_x0

        # Column dividers as fractions of content width
        if col_divider_xs:
            self.columns.count           = len(col_divider_xs) + 1
            self.columns.divider_x_fracs = sorted([
                round((x - ca_x0) / max(1, ca_w), 4)
                for x in col_divider_xs
            ])
        else:
            self.columns.count           = 1
            self.columns.divider_x_fracs = []

        # Learn mark templates from crops
        gray = cv2.cvtColor(cv_img, cv2.COLOR_BGR2GRAY)
        h, w = gray.shape
        for label, (cx0, cy0, cx1, cy1) in mark_crops.items():
            crop_x0 = max(0, cx0); crop_y0 = max(0, cy0)
            crop_x1 = min(w, cx1); crop_y1 = min(h, cy1)
            crop     = gray[crop_y0:crop_y1, crop_x0:crop_x1]
            if crop.size == 0:
                continue

            # Compute mark geometry relative to nearest content corner
            mark_cx  = (cx0 + cx1) // 2
            mark_cy  = (cy0 + cy1) // 2
            mark_w   = cx1 - cx0
            mark_h   = cy1 - cy0

            if label in ('TL', 'PTL'):
                ref = (ca_x0, ca_y0)
            elif label in ('TR', 'PTR'):
                ref = (ca_x1, ca_y0)
            elif label == 'BL':
                ref = (ca_x0, ca_y1)
            elif label == 'BR':
                ref = (ca_x1, ca_y1)
            else:
                ref = (mark_cx, mark_cy)

            mt = MarkTemplate(
                label                    = label,
                offset_from_corner_x_in = round((mark_cx - ref[0]) / dpi, 4),
                offset_from_corner_y_in = round((mark_cy - ref[1]) / dpi, 4),
                width_in                 = round(mark_w / dpi, 4),
                height_in                = round(mark_h / dpi, 4),
                match_threshold          = 0.72,
            )
            mt.set_template_from_cv(crop)

            if label.startswith('P'):
                self.page_marks[label]   = mt
            else:
                self.corner_marks[label] = mt

            print(f"  Learned mark {label}: {mark_w}×{mark_h}px "
                  f"offset=({mt.offset_from_corner_x_in:.3f}\","
                  f"{mt.offset_from_corner_y_in:.3f})\"")

        self.dpi_reference = dpi

    def learn_candidate_geometry(self, indicator_bboxes, dpi):
        """
        Update candidate row profile from a list of detected indicator
        bounding boxes [(x0,y0,x1,y1), ...] collected across contests.
        """
        if not indicator_bboxes:
            return
        heights = [(b[3]-b[1]) for b in indicator_bboxes]
        widths  = [(b[2]-b[0]) for b in indicator_bboxes]
        # Use median to be robust to outliers (e.g. filled vs empty indicators)
        med_h = float(np.median(heights))
        med_w = float(np.median(widths))
        # Candidate row height: estimate from vertical spacing between indicators
        if len(indicator_bboxes) > 1:
            sorted_boxes = sorted(indicator_bboxes, key=lambda b: b[1])
            spacings = []
            for i in range(1, len(sorted_boxes)):
                spacing = sorted_boxes[i][1] - sorted_boxes[i-1][1]
                if 0 < spacing < dpi:   # sanity: less than 1" apart
                    spacings.append(spacing)
            row_h = float(np.median(spacings)) if spacings else med_h * 2.5
        else:
            row_h = med_h * 2.5

        ca_left = int(self.content_area.offset_left_in * dpi)
        offset_left = float(np.median([(b[0]-ca_left) for b in indicator_bboxes]))

        self.candidate_row = CandidateRowProfile(
            height_in               = round(row_h  / dpi, 4),
            indicator_offset_left_in= round(offset_left / dpi, 4),
            indicator_width_in      = round(med_w  / dpi, 4),
            indicator_height_in     = round(med_h  / dpi, 4),
        )
        print(f"  Learned candidate row: height={self.candidate_row.height_in:.3f}\" "
              f"indicator={self.candidate_row.indicator_width_in:.3f}×"
              f"{self.candidate_row.indicator_height_in:.3f}\"")


# ── Generator functions ───────────────────────────────────────────────────────

def generate_python_detector(profile: DesignProfile, out_path: str):
    """
    Generate corner_detector.py — a standalone Python corner detection module
    that uses the learned templates and geometry from the profile.
    """
    # Serialise templates for embedding
    mark_data = {}
    for label, mt in {**profile.corner_marks, **profile.page_marks}.items():
        mark_data[label] = {
            'template_b64':            mt.template_b64,
            'offset_from_corner_x_in': mt.offset_from_corner_x_in,
            'offset_from_corner_y_in': mt.offset_from_corner_y_in,
            'width_in':                mt.width_in,
            'height_in':               mt.height_in,
            'match_threshold':         mt.match_threshold,
        }

    marks_json = json.dumps(mark_data, indent=4)
    content_json = json.dumps({
        'offset_left_in': profile.content_area.offset_left_in,
        'offset_top_in':  profile.content_area.offset_top_in,
        'width_in':       profile.content_area.width_in,
        'height_in':      profile.content_area.height_in,
    }, indent=4)

    code = f'''#!/usr/bin/env python3
"""
corner_detector.py — Auto-generated ballot corner detector.
Format: {profile.format_name}
Generated by ballot_mapper.py from learned design profile.

Usage:
    python corner_detector.py ballot.png [--dpi 300]
    # Prints JSON: {{"TL": [x, y], "TR": [x, y], "BL": [x, y], "BR": [x, y], ...}}

    # Or import:
    from corner_detector import BallotCornerDetector
    detector = BallotCornerDetector()
    corners  = detector.find_corners_in_file("ballot.png", dpi=300)
"""

import argparse, base64, json, sys
import cv2
import numpy as np

# ── Embedded mark templates (learned from training images) ────────────────────

MARK_DATA = {marks_json}

CONTENT_AREA = {content_json}

DPI_REFERENCE = {profile.dpi_reference}


class BallotCornerDetector:
    """
    Detects corner and page marks on ballot images using learned templates.
    """

    def find_corners_in_file(self, image_path: str, dpi: int = None) -> dict:
        img = cv2.imread(image_path)
        if img is None:
            raise FileNotFoundError(f"Cannot load image: {{image_path}}")
        return self.find_corners(img, dpi or DPI_REFERENCE)

    def find_corners(self, cv_img, dpi: int = DPI_REFERENCE) -> dict:
        """
        Returns dict label→(x, y) pixel coords for found marks.
        Missing marks fall back to geometry-based estimates.
        """
        gray = cv2.cvtColor(cv_img, cv2.COLOR_BGR2GRAY)
        h, w = gray.shape
        results = {{}}

        for label, md in MARK_DATA.items():
            b64 = md.get('template_b64', '')
            if not b64:
                continue

            # Decode template
            data = base64.b64decode(b64)
            arr  = np.frombuffer(data, dtype=np.uint8)
            tmpl = cv2.imdecode(arr, cv2.IMREAD_GRAYSCALE)
            if tmpl is None:
                continue

            # Scale template if DPI differs from reference
            if dpi != DPI_REFERENCE and DPI_REFERENCE > 0:
                scale = dpi / DPI_REFERENCE
                tw = max(1, int(tmpl.shape[1] * scale))
                th = max(1, int(tmpl.shape[0] * scale))
                tmpl = cv2.resize(tmpl, (tw, th))

            th_px, tw_px = tmpl.shape

            # Restrict search to expected quadrant
            search_x0, search_y0, search_x1, search_y1 = \\
                self._search_region(label, w, h, dpi)
            roi = gray[search_y0:search_y1, search_x0:search_x1]
            if roi.shape[0] < th_px or roi.shape[1] < tw_px:
                results[label] = self._geometry_estimate(label, w, h, dpi)
                continue

            res = cv2.matchTemplate(roi, tmpl, cv2.TM_CCOEFF_NORMED)
            _, max_val, _, max_loc = cv2.minMaxLoc(res)
            threshold = md.get('match_threshold', 0.72)

            if max_val >= threshold:
                mx = search_x0 + max_loc[0] + tw_px // 2
                my = search_y0 + max_loc[1] + th_px // 2
                results[label] = (int(mx), int(my))
            else:
                est = self._geometry_estimate(label, w, h, dpi)
                results[label] = est
                print(f"  ⚠  {{label}} match={{max_val:.2f}} < {{threshold:.2f}}: "
                      f"using geometry estimate {{est}}", file=sys.stderr)

        return results

    def verify_image(self, image_path: str, dpi: int = None, out_path: str = None):
        """
        Draw detected corners onto a copy of the image for visual verification.
        Saves to out_path (default: image_path + '_corners.png').
        """
        img = cv2.imread(image_path)
        if img is None:
            raise FileNotFoundError(image_path)
        corners = self.find_corners(img, dpi or DPI_REFERENCE)
        vis     = img.copy()
        colors  = {{'TL': (0,0,255), 'TR': (0,128,255),
                    'BR': (0,200,0), 'BL': (255,128,0),
                    'PTL':(0,255,255),'PTR':(255,0,255)}}
        for label, (cx, cy) in corners.items():
            col = colors.get(label, (200,200,200))
            cv2.drawMarker(vis, (cx, cy), col,
                           cv2.MARKER_CROSS, 30, 3)
            cv2.putText(vis, label, (cx+12, cy-8),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, col, 2)
        out = out_path or (str(image_path).rsplit('.', 1)[0] + '_corners.png')
        cv2.imwrite(out, vis)
        print(f"Verification image written: {{out}}")
        return corners

    @staticmethod
    def _search_region(label, img_w, img_h, dpi):
        pad = int(dpi * 1.5)
        if label in ('TL', 'PTL'):
            return 0, 0, min(img_w, pad*2), min(img_h, pad*2)
        elif label in ('TR', 'PTR'):
            return max(0, img_w-pad*2), 0, img_w, min(img_h, pad*2)
        elif label == 'BL':
            return 0, max(0, img_h-pad*2), min(img_w, pad*2), img_h
        elif label == 'BR':
            return max(0, img_w-pad*2), max(0, img_h-pad*2), img_w, img_h
        return 0, 0, img_w, img_h

    @staticmethod
    def _geometry_estimate(label, img_w, img_h, dpi):
        ca = CONTENT_AREA
        if ca.get('width_in', 0) == 0:
            return None
        left  = int(ca['offset_left_in'] * dpi)
        top   = int(ca['offset_top_in']  * dpi)
        right = int((ca['offset_left_in'] + ca['width_in'])  * dpi)
        bot   = int((ca['offset_top_in']  + ca['height_in']) * dpi)
        lut   = {{'TL':(left,top), 'TR':(right,top),
                   'BR':(right,bot), 'BL':(left,bot),
                   'PTL':(left,top), 'PTR':(right,top)}}
        return lut.get(label)


if __name__ == '__main__':
    ap = argparse.ArgumentParser(
        description='Detect ballot corners using learned templates')
    ap.add_argument('image', help='Ballot image file')
    ap.add_argument('--dpi',    type=int, default=DPI_REFERENCE)
    ap.add_argument('--verify', action='store_true',
                    help='Save annotated verification image')
    ap.add_argument('--out',    default=None, help='Verification image path')
    args = ap.parse_args()

    d = BallotCornerDetector()
    if args.verify:
        corners = d.verify_image(args.image, args.dpi, args.out)
    else:
        corners = d.find_corners_in_file(args.image, args.dpi)
    print(json.dumps({{k: list(v) for k, v in corners.items()
                       if v is not None}}, indent=2))
'''
    with open(out_path, 'w') as f:
        f.write(code)
    print(f"Python corner detector written: {out_path}")


def generate_java_service(profile: DesignProfile, out_path: str,
                          package: str = "com.mjtrac.counter.service"):
    """
    Generate CornerDetectionService_custom.java — a real implementation of
    bCounter's BallotCornerDetectorService strategy interface, built from
    corner-mark templates and geometry learned from a handful of sample
    scans of one vendor's ballot design (see DesignProfile.learn_from_annotation).

    Unlike an earlier version of this generator, the output here:
      - actually `implements BallotCornerDetectorService` with the exact
        method signature and Point2D[4] / [TL,TR,BR,BL] return contract,
      - uses pure java.awt.image (no OpenCV/JavaCV) — bCounter has no
        native image dependency today and this doesn't add one,
      - does template matching itself (coarse-to-fine normalized cross-
        correlation) rather than relying on a runtime OpenCV binding,
      - works even when `layout` (the pbss YAML) is null, since all the
        geometry it needs comes from the learned profile, not the YAML.

    See docs/ballot-design-portability.md section 4 for the full rationale.
    """
    ca = profile.content_area
    same_pkg = (package == "com.mjtrac.counter.service")
    extra_imports = "" if same_pkg else (
        "import com.mjtrac.counter.service.BallotCornerDetectorService;\n"
        "import com.mjtrac.counter.service.Point2D;\n"
    )

    # Build one MARK_GEOM.put(...) / MARK_TEMPLATE_B64.put(...) pair per
    # corner mark (TL/TR/BR/BL only — page marks PTL/PTR aren't needed by
    # the mark-to-content-box-corner projection below).
    static_init_parts = []
    for label, mt in profile.corner_marks.items():
        b64    = mt.template_b64 or ""
        chunks = [b64[i:i+80] for i in range(0, len(b64), 80)]
        b64_java = (" +\n            ".join(f'"{c}"' for c in chunks)
                    if chunks else '""')
        static_init_parts.append(
            f'        MARK_GEOM.put("{label}", new double[]{{'
            f'{mt.offset_from_corner_x_in}, {mt.offset_from_corner_y_in}, '
            f'{mt.width_in}, {mt.height_in}, {mt.match_threshold}}});\n'
            f'        MARK_TEMPLATE_B64.put("{label}",\n'
            f'            {b64_java});'
        )
    static_init_lines = "\n".join(static_init_parts)

    n_training = len(profile.training_images)
    training_note = (f"{n_training} training image(s): "
                      f"{', '.join(profile.training_images)}"
                      if n_training else "no training images recorded")

    java = f'''/*
 * AUTO-GENERATED by design_profile.py generate_java_service()
 * Format: {profile.format_name}
 * Learned from {training_note}
 * Do not edit by hand — regenerate from the design profile instead.
 *
 * Implements BallotCornerDetectorService using corner-mark templates and
 * geometry learned from a handful of sample scans of one ballot design.
 * See docs/ballot-design-portability.md (section 4) for background.
 *
 * DEPLOYMENT:
 *   1. Copy this file to bCounter/src/main/java/{package.replace('.', '/')}/
 *   2. This class is annotated @Primary. Remove @Primary from (or delete)
 *      CornerDetectionService.java first — Spring refuses to start with
 *      two @Primary beans for the same interface.
 */
package {package};

import com.mjtrac.counter.model.BboxReport.PageLayout;
{extra_imports}import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom corner detection service for the "{profile.format_name}" ballot format.
 *
 * Content area (at {float(profile.dpi_reference)} DPI reference):
 *   left={ca.offset_left_in}", top={ca.offset_top_in}",
 *   width={ca.width_in}", height={ca.height_in}"
 */
@Service
@Primary
public class CornerDetectionService_custom implements BallotCornerDetectorService {{

    private static final Logger log =
        LoggerFactory.getLogger(CornerDetectionService_custom.class);

    private static final double DPI_REFERENCE = {float(profile.dpi_reference)};

    // Content area geometry learned from training images (inches, at DPI_REFERENCE)
    private static final double CA_LEFT_IN   = {ca.offset_left_in};
    private static final double CA_TOP_IN    = {ca.offset_top_in};
    private static final double CA_WIDTH_IN  = {ca.width_in};
    private static final double CA_HEIGHT_IN = {ca.height_in};

    // Per-mark geometry: label -> {{offsetFromCornerX_in, offsetFromCornerY_in,
    //                              width_in, height_in, matchThreshold}}
    private static final Map<String, double[]> MARK_GEOM = new LinkedHashMap<>();
    // Per-mark template, base64-encoded PNG
    private static final Map<String, String> MARK_TEMPLATE_B64 = new LinkedHashMap<>();
    static {{
{static_init_lines}
    }}

    private final Map<String, BufferedImage> templates = new LinkedHashMap<>();

    public CornerDetectionService_custom() {{
        for (Map.Entry<String, String> e : MARK_TEMPLATE_B64.entrySet()) {{
            BufferedImage img = decodeTemplate(e.getValue(), e.getKey());
            if (img != null) templates.put(e.getKey(), img);
        }}
    }}

    private BufferedImage decodeTemplate(String b64, String label) {{
        if (b64 == null || b64.isBlank()) return null;
        try {{
            byte[] bytes = Base64.getDecoder().decode(b64);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {{
                log.warn("Failed to decode template for {{}}", label);
                return null;
            }}
            return img;
        }} catch (IOException e) {{
            log.error("Template decode error for {{}}: {{}}", label, e.getMessage());
            return null;
        }}
    }}

    // ── BallotCornerDetectorService ───────────────────────────────────────────

    @Override
    public Point2D[] findContentBoxCorners(BufferedImage[] imageHolder,
                                            int dpi,
                                            double expectedWidthIn,
                                            double expectedHeightIn,
                                            PageLayout layout,
                                            double bcOffsetX,
                                            double bcOffsetY) {{
        return findContentBoxCorners(imageHolder, dpi, expectedWidthIn, expectedHeightIn, false);
    }}

    private Point2D[] findContentBoxCorners(BufferedImage[] imageHolder,
                                             int dpi,
                                             double expectedWidthIn,
                                             double expectedHeightIn,
                                             boolean alreadyFlipped) {{
        BufferedImage image = imageHolder[0];
        int w = image.getWidth(), h = image.getHeight();
        int[] gray = toGrayscalePixels(image, w, h);

        Map<String, double[]> foundPx = new LinkedHashMap<>(); // label -> {{x, y, score}}
        for (String label : new String[]{{"TL", "TR", "BR", "BL"}}) {{
            double[] geom = MARK_GEOM.get(label);
            BufferedImage tmpl = templates.get(label);
            if (geom == null || tmpl == null) continue;

            BufferedImage scaledTmpl = tmpl;
            if (Math.abs(dpi - DPI_REFERENCE) > 1.0 && DPI_REFERENCE > 0) {{
                scaledTmpl = scaleImage(tmpl, dpi / DPI_REFERENCE);
            }}

            int[] region = searchRegion(label, w, h, dpi);
            double[] match = matchTemplate(gray, w, h, scaledTmpl, region);
            double threshold = geom[4];
            if (match != null && match[2] >= threshold) {{
                foundPx.put(label, match);
                log.debug("Mark {{}} found at ({{}},{{}}) score={{}}",
                    label, (int) match[0], (int) match[1], match[2]);
            }} else {{
                double[] est = geometryEstimate(label, dpi);
                if (est != null) {{
                    foundPx.put(label, new double[]{{est[0], est[1], 0.0}});
                    log.debug("Mark {{}} not matched (best={{}}) -- using geometry estimate ({{}},{{}})",
                        label, match != null ? match[2] : -1.0, (int) est[0], (int) est[1]);
                }} else {{
                    log.warn("Mark {{}} not found: no template match and no geometry estimate", label);
                }}
            }}
        }}

        int hits = foundPx.size();
        if (hits < 2) {{
            if (!alreadyFlipped) {{
                log.debug("Fewer than 2 corner marks found -- retrying after 180deg rotation");
                imageHolder[0] = rotateImage(image, Math.PI);
                return findContentBoxCorners(imageHolder, dpi, expectedWidthIn, expectedHeightIn, true);
            }}
            log.error("Corner detection failed: only {{}}/4 marks found (need >= 2)", hits);
            return new Point2D[]{{null, null, null, null}};
        }}
        if (alreadyFlipped) log.debug("Ballot was flipped 180deg before processing");

        Point2D[] marks = new Point2D[4]; // [TL, TR, BR, BL]
        String[] order = {{"TL", "TR", "BR", "BL"}};
        for (int i = 0; i < 4; i++) {{
            double[] p = foundPx.get(order[i]);
            if (p != null) marks[i] = new Point2D(p[0], p[1]);
        }}

        // Infer any one missing corner via the parallelogram rule
        if (marks[0] == null && marks[1] != null && marks[2] != null && marks[3] != null)
            marks[0] = new Point2D(marks[3].x() + marks[1].x() - marks[2].x(),
                                    marks[3].y() + marks[1].y() - marks[2].y());
        if (marks[1] == null && marks[0] != null && marks[2] != null && marks[3] != null)
            marks[1] = new Point2D(marks[0].x() + marks[2].x() - marks[3].x(),
                                    marks[0].y() + marks[2].y() - marks[3].y());
        if (marks[2] == null && marks[0] != null && marks[1] != null && marks[3] != null)
            marks[2] = new Point2D(marks[1].x() + marks[3].x() - marks[0].x(),
                                    marks[1].y() + marks[3].y() - marks[0].y());
        if (marks[3] == null && marks[0] != null && marks[1] != null && marks[2] != null)
            marks[3] = new Point2D(marks[0].x() + marks[2].x() - marks[1].x(),
                                    marks[0].y() + marks[2].y() - marks[1].y());

        return marksToBboxCorners(marks, dpi, expectedWidthIn, expectedHeightIn);
    }}

    // ── Mark -> content-box corner conversion ─────────────────────────────────
    //
    // Each mark sits at a learned offset from its corresponding content-box
    // corner (MARK_GEOM[label][0..1], in design inches). We build local
    // right/down axes from the four DETECTED mark pixel positions (so
    // rotation and mild shear are handled, not just axis-aligned translation)
    // and project each mark's design-space offset along those axes. This
    // mirrors bBuilder's own CornerDetectionService.marksToBboxCorners.
    private Point2D[] marksToBboxCorners(Point2D[] marks, int dpi,
                                          double expectedWidthIn, double expectedHeightIn) {{
        double[] tlOff = MARK_GEOM.get("TL");
        double[] trOff = MARK_GEOM.get("TR");
        double[] brOff = MARK_GEOM.get("BR");
        double[] blOff = MARK_GEOM.get("BL");
        if (tlOff == null || trOff == null || brOff == null || blOff == null) {{
            log.warn("Incomplete mark geometry in learned profile -- returning raw mark positions");
            return marks;
        }}

        // Design-space (inches) position of each mark and its content-box corner
        double[] origTL = {{ CA_LEFT_IN + tlOff[0],               CA_TOP_IN + tlOff[1] }};
        double[] origTR = {{ CA_LEFT_IN + CA_WIDTH_IN + trOff[0], CA_TOP_IN + trOff[1] }};
        double[] origBR = {{ CA_LEFT_IN + CA_WIDTH_IN + brOff[0], CA_TOP_IN + CA_HEIGHT_IN + brOff[1] }};
        double[] origBL = {{ CA_LEFT_IN + blOff[0],               CA_TOP_IN + CA_HEIGHT_IN + blOff[1] }};
        double[][] origMarks   = {{ origTL, origTR, origBR, origBL }};
        double[][] bboxCorners = {{
            {{ CA_LEFT_IN,               CA_TOP_IN }},
            {{ CA_LEFT_IN + CA_WIDTH_IN, CA_TOP_IN }},
            {{ CA_LEFT_IN + CA_WIDTH_IN, CA_TOP_IN + CA_HEIGHT_IN }},
            {{ CA_LEFT_IN,               CA_TOP_IN + CA_HEIGHT_IN }}
        }};

        double designW = origMarks[1][0] - origMarks[0][0];
        double designH = origMarks[3][1] - origMarks[0][1];
        if (Math.abs(designW) < 0.01 || Math.abs(designH) < 0.01) {{
            log.warn("Design dimensions too small -- returning raw mark positions");
            return marks;
        }}

        double tlx = marks[0].x(), tly = marks[0].y();
        double trx = marks[1].x(), try_ = marks[1].y();
        double brx = marks[2].x(), bry = marks[2].y();
        double blx = marks[3].x(), bly = marks[3].y();

        double rxPerIn = ((trx - tlx) + (brx - blx)) / 2.0 / designW;
        double ryPerIn = ((try_ - tly) + (bry - bly)) / 2.0 / designW;
        double dxPerIn = ((blx - tlx) + (brx - trx)) / 2.0 / designH;
        double dyPerIn = ((bly - tly) + (bry - try_)) / 2.0 / designH;

        Point2D[] corners = new Point2D[4];
        for (int i = 0; i < 4; i++) {{
            double offRightIn = bboxCorners[i][0] - origMarks[i][0];
            double offDownIn  = bboxCorners[i][1] - origMarks[i][1];
            corners[i] = new Point2D(
                marks[i].x() + offRightIn * rxPerIn + offDownIn * dxPerIn,
                marks[i].y() + offRightIn * ryPerIn + offDownIn * dyPerIn);
        }}

        // Sanity check against expected (YAML) or learned content-box size
        double topLen = dist(corners[0], corners[1]);
        double rightLen = dist(corners[1], corners[2]);
        double expW = expectedWidthIn  > 0 ? expectedWidthIn  : CA_WIDTH_IN;
        double expH = expectedHeightIn > 0 ? expectedHeightIn : CA_HEIGHT_IN;
        double detW = topLen / dpi, detH = rightLen / dpi;
        if (expW > 0 && expH > 0
                && (Math.abs(detW - expW) > 1.0 || Math.abs(detH - expH) > 1.0)) {{
            log.warn("Detected content box {{}}x{{}}in does not match expected {{}}x{{}}in",
                String.format("%.2f", detW), String.format("%.2f", detH), expW, expH);
        }}

        return corners;
    }}

    private double dist(Point2D a, Point2D b) {{
        double dx = b.x() - a.x(), dy = b.y() - a.y();
        return Math.sqrt(dx * dx + dy * dy);
    }}

    // ── Template matching (pure java.awt, no native deps) ─────────────────────
    //
    // Coarse-to-fine normalized cross-correlation (NCC), equivalent to
    // OpenCV's TM_CCOEFF_NORMED. Searches a small window near each mark's
    // expected position rather than the whole image, and samples the
    // window coarsely before refining -- this is what keeps per-ballot
    // corner detection fast enough for scan-volume processing without an
    // FFT-based matcher.

    private int[] searchRegion(String label, int imgW, int imgH, int dpi) {{
        int pad = (int) (dpi * 0.5); // +/- 0.5in around the expected corner
        switch (label) {{
            case "TL": return new int[]{{0, 0, Math.min(imgW, pad * 2), Math.min(imgH, pad * 2)}};
            case "TR": return new int[]{{Math.max(0, imgW - pad * 2), 0, imgW, Math.min(imgH, pad * 2)}};
            case "BL": return new int[]{{0, Math.max(0, imgH - pad * 2), Math.min(imgW, pad * 2), imgH}};
            case "BR": return new int[]{{Math.max(0, imgW - pad * 2), Math.max(0, imgH - pad * 2), imgW, imgH}};
            default:   return new int[]{{0, 0, imgW, imgH}};
        }}
    }}

    private double[] geometryEstimate(String label, int dpi) {{
        double left  = CA_LEFT_IN * dpi;
        double top   = CA_TOP_IN  * dpi;
        double right = (CA_LEFT_IN + CA_WIDTH_IN)  * dpi;
        double bot   = (CA_TOP_IN  + CA_HEIGHT_IN) * dpi;
        switch (label) {{
            case "TL": return new double[]{{left,  top}};
            case "TR": return new double[]{{right, top}};
            case "BR": return new double[]{{right, bot}};
            case "BL": return new double[]{{left,  bot}};
            default:   return null;
        }}
    }}

    /** Returns {{templateCentreX, templateCentreY, bestScore}}, or null if the region is too small. */
    private double[] matchTemplate(int[] gray, int imgW, int imgH,
                                    BufferedImage tmplImg, int[] region) {{
        int tw = tmplImg.getWidth(), th = tmplImg.getHeight();
        int sx0 = region[0], sy0 = region[1], sx1 = region[2], sy1 = region[3];
        if (sx1 - sx0 < tw || sy1 - sy0 < th || tw < 1 || th < 1) return null;

        int[] tmpl = toGrayscalePixels(tmplImg, tw, th);
        double tMean = 0;
        for (int v : tmpl) tMean += v;
        tMean /= tmpl.length;
        double[] tZero = new double[tmpl.length];
        double tEnergy = 0;
        for (int i = 0; i < tmpl.length; i++) {{
            tZero[i] = tmpl[i] - tMean;
            tEnergy += tZero[i] * tZero[i];
        }}
        if (tEnergy < 1e-6) return null;

        // Coarse pass: sample every ~1/6th of the template's smaller dimension
        int step = Math.max(1, Math.min(tw, th) / 6);
        int bestX = -1, bestY = -1;
        double bestScore = -2.0;
        for (int y = sy0; y <= sy1 - th; y += step) {{
            for (int x = sx0; x <= sx1 - tw; x += step) {{
                double score = ncc(gray, imgW, x, y, tw, th, tZero, tEnergy);
                if (score > bestScore) {{ bestScore = score; bestX = x; bestY = y; }}
            }}
        }}
        if (bestX < 0) return null;

        // Fine pass: 1px steps in a window around the coarse best
        int fx0 = Math.max(sx0, bestX - step), fx1 = Math.min(sx1 - tw, bestX + step);
        int fy0 = Math.max(sy0, bestY - step), fy1 = Math.min(sy1 - th, bestY + step);
        for (int y = fy0; y <= fy1; y++) {{
            for (int x = fx0; x <= fx1; x++) {{
                double score = ncc(gray, imgW, x, y, tw, th, tZero, tEnergy);
                if (score > bestScore) {{ bestScore = score; bestX = x; bestY = y; }}
            }}
        }}

        return new double[]{{ bestX + tw / 2.0, bestY + th / 2.0, bestScore }};
    }}

    private double ncc(int[] gray, int imgW, int x0, int y0, int tw, int th,
                        double[] tZero, double tEnergy) {{
        double pMean = 0;
        for (int dy = 0; dy < th; dy++)
            for (int dx = 0; dx < tw; dx++)
                pMean += gray[(y0 + dy) * imgW + (x0 + dx)];
        pMean /= (tw * th);

        double num = 0, pEnergy = 0;
        for (int dy = 0; dy < th; dy++) {{
            for (int dx = 0; dx < tw; dx++) {{
                double pv = gray[(y0 + dy) * imgW + (x0 + dx)] - pMean;
                num += pv * tZero[dy * tw + dx];
                pEnergy += pv * pv;
            }}
        }}
        if (pEnergy < 1e-6) return -1.0;
        return num / Math.sqrt(pEnergy * tEnergy);
    }}

    // ── Image helpers ──────────────────────────────────────────────────────────

    private int[] toGrayscalePixels(BufferedImage img, int w, int h) {{
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int[] gray = new int[w * h];
        for (int i = 0; i < px.length; i++) {{
            int r = (px[i] >> 16) & 0xFF;
            int g = (px[i] >> 8)  & 0xFF;
            int b =  px[i]        & 0xFF;
            gray[i] = (r * 77 + g * 150 + b * 29) >> 8;
        }}
        return gray;
    }}

    private BufferedImage scaleImage(BufferedImage src, double scale) {{
        int nw = Math.max(1, (int) Math.round(src.getWidth()  * scale));
        int nh = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return dst;
    }}

    private BufferedImage rotateImage(BufferedImage src, double angleRad) {{
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.translate(w / 2.0, h / 2.0);
        g.rotate(angleRad);
        g.translate(-w / 2.0, -h / 2.0);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }}
}}
'''
    with open(out_path, 'w') as f:
        f.write(java)
    print(f"Java CornerDetectionService_custom written: {out_path}")
    print(f"  implements BallotCornerDetectorService -- see the file's own")
    print(f"  header comment for the @Primary deployment step.")
