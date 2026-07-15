#!/usr/bin/env python3
"""
ballot_mapper.py — pbss Ballot Layout Inference Tool (bMapper)

Analyses a scanned ballot image from an unknown vendor, infers the layout
(columns, contests, candidates, vote indicators), overlays its guesses on
the image for user review, and writes a bCounter-compatible YAML layout file.

Usage:
    python ballot_mapper.py [--image path/to/scan.png]
                            [--out ~/pbss_data/ballot_templates/]
                            [--dpi 300]
                            [--mode learn|verify]
                            [--yaml existing_layout.yaml]  # verify mode only

Workflow (LEARN mode):
    1. Load ballot image
    2. Auto-detect or user-draw content bounding box
    3. User clicks to identify barcode/QR region → decoded as YAML key
    4. Detect columns via horizontal projection
    5. Detect contest boundaries via vertical projection per column
    6. Detect vote indicators via connected component analysis
    7. OCR contest titles and candidate names
    8. Display overlays — user can drag/resize/delete/add boxes
    9. Write YAML to output directory
   10. Prompt for next image (same ballot type or new one)

Dependencies:
    pip install opencv-python pillow pytesseract pyyaml numpy
    brew install tesseract   # macOS
    apt install tesseract-ocr  # Linux

Copyright (C) 2026 Mitch Trachtenberg — GPL v3
"""

import argparse
import json
import math
import os
import sys
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk
from pathlib import Path
from dataclasses import dataclass, field
from typing import Optional

import cv2
import numpy as np
import yaml
from PIL import Image, ImageTk

# ── Optional barcode decode ───────────────────────────────────────────────────────
try:
    from pyzbar import pyzbar as _pyzbar
    PYZBAR_AVAILABLE = True
except ImportError:
    PYZBAR_AVAILABLE = False
    print("⚠  pyzbar not installed — QR auto-decode disabled. "
          "Install with: pip install pyzbar")

# ── Optional Tesseract OCR ─────────────────────────────────────────────────────
try:
    import pytesseract
    TESSERACT_AVAILABLE = True
except ImportError:
    TESSERACT_AVAILABLE = False
    print("⚠  pytesseract not installed — OCR disabled. "
          "Install with: pip install pytesseract")

# ── Constants ──────────────────────────────────────────────────────────────────
INDICATOR_INSET_PT = 1.0          # 1pt inset from each edge of detected indicator
MIN_IND_WIDTH_IN   = 0.12         # minimum indicator width (inches)
MAX_IND_WIDTH_IN   = 0.55         # maximum indicator width (inches)
MIN_IND_HEIGHT_IN  = 0.06         # minimum indicator height (inches)
MAX_IND_HEIGHT_IN  = 0.25         # maximum indicator height (inches)
MIN_SOLIDITY       = 0.45         # connected component solidity (filled-ness)
MAX_ASPECT_RATIO   = 5.0          # max width/height for an indicator shape
HANDLE_R           = 6            # drag-handle radius in canvas pixels
ZOOM_STEP          = 1.15         # zoom in/out multiplier


# ══════════════════════════════════════════════════════════════════════════════
# Data model
# ══════════════════════════════════════════════════════════════════════════════

@dataclass
class Indicator:
    """One vote indicator box in image pixels (relative to full image TL)."""
    x: float          # left edge in image pixels
    y: float          # top edge in image pixels
    w: float          # width in image pixels
    h: float          # height in image pixels
    candidate: str = ""
    style: str = "OVAL"         # OVAL / CHECKBOX / CONNECT_DOTS / UNKNOWN
    appears_marked: bool = False
    dark_pct: float = 0.0
    confidence: float = 1.0

    @property
    def cx(self): return self.x + self.w / 2
    @property
    def cy(self): return self.y + self.h / 2


@dataclass
class Contest:
    """One contest region in image pixels."""
    x: float
    y: float
    w: float
    h: float
    title: str = ""
    instruction: str = ""
    column: int = 0
    indicators: list = field(default_factory=list)
    confidence: float = 1.0


@dataclass
class Layout:
    """Full inferred ballot layout."""
    barcode_key: str = ""
    dpi: int = 300
    content_x: float = 0
    content_y: float = 0
    content_w: float = 0
    content_h: float = 0
    page_w: float = 0
    page_h: float = 0
    columns: list = field(default_factory=list)   # list of x-boundaries
    contests: list = field(default_factory=list)


# ══════════════════════════════════════════════════════════════════════════════
# Detection pipeline
# ══════════════════════════════════════════════════════════════════════════════

class Detector:
    """Runs the CV pipeline to infer ballot layout from a grayscale image."""

    def __init__(self, gray: np.ndarray, dpi: int):
        self.gray = gray
        self.dpi  = dpi
        self.h, self.w = gray.shape
        self._binary = None

    def binary(self) -> np.ndarray:
        if self._binary is None:
            _, b = cv2.threshold(
                self.gray, 0, 255,
                cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
            self._binary = b
        return self._binary

    # ── Content bounding box ──────────────────────────────────────────────────

    def infer_content_box(self) -> tuple[float, float, float, float]:
        """
        Estimate the ballot content area by finding the largest
        dark-bordered rectangle.  Returns (x, y, w, h) in image pixels.
        Falls back to the full image with a 1% margin.
        """
        # Edge detect, dilate to close gaps, find contours
        edges = cv2.Canny(self.gray, 50, 150)
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3))
        dilated = cv2.dilate(edges, kernel, iterations=2)
        contours, _ = cv2.findContours(
            dilated, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

        best = None
        best_area = 0
        img_area = self.w * self.h
        for c in contours:
            rx, ry, rw, rh = cv2.boundingRect(c)
            area = rw * rh
            # Must cover at least 30% of image and be roughly rectangular
            if area < img_area * 0.30:
                continue
            # Aspect ratio sanity
            if rw < self.w * 0.4 or rh < self.h * 0.4:
                continue
            if area > best_area:
                best_area = area
                best = (float(rx), float(ry), float(rw), float(rh))

        if best:
            return best

        # Fallback: 2% margin on each side
        margin_x = self.w * 0.02
        margin_y = self.h * 0.02
        return (margin_x, margin_y,
                self.w - 2 * margin_x, self.h - 2 * margin_y)

    # ── Column detection ──────────────────────────────────────────────────────

    def detect_columns(self, cx: float, cy: float,
                        cw: float, ch: float) -> list[float]:
        """
        Find vertical column separators within the content box.
        Returns a list of x-coordinates (image pixels) that are column
        boundaries, including the left and right edges of the content box.
        """
        x0, x1 = int(cx), int(cx + cw)
        y0, y1 = int(cy), int(cy + ch)
        roi = self.binary()[y0:y1, x0:x1]

        # Horizontal projection: count dark pixels per column
        proj = roi.sum(axis=0).astype(float)
        proj /= (proj.max() + 1e-6)   # normalise 0–1

        # Smooth with a wide kernel to find whitespace valleys
        kernel_w = max(1, int(self.dpi * 0.08))   # ~24px at 300 DPI
        proj_s = np.convolve(proj, np.ones(kernel_w) / kernel_w, mode='same')

        # Find valleys (potential column separators)
        valleys = self._find_valleys(proj_s, min_width_px=int(self.dpi * 0.05))

        # Convert to image-absolute x coords
        boundaries = [float(cx)]
        for v in valleys:
            boundaries.append(float(cx + v))
        boundaries.append(float(cx + cw))

        # Sanity: at least 2 boundaries (1 column)
        if len(boundaries) < 2:
            boundaries = [float(cx), float(cx + cw)]

        return boundaries

    def _find_valleys(self, proj: np.ndarray,
                       min_width_px: int = 15) -> list[int]:
        """Find x-positions of valleys in a 1D projection."""
        threshold = proj.mean() * 0.4
        in_valley = False
        start = 0
        valleys = []
        for i, v in enumerate(proj):
            if not in_valley and v < threshold:
                in_valley = True
                start = i
            elif in_valley and v >= threshold:
                in_valley = False
                width = i - start
                if width >= min_width_px:
                    valleys.append((start + i) // 2)
        return valleys

    # ── Contest boundary detection ────────────────────────────────────────────

    def detect_contests_in_column(self, cx: float, cy: float,
                                   cw: float, ch: float,
                                   col_idx: int) -> list[Contest]:
        """
        Find contest boundaries within one column using vertical projection.
        Returns a list of Contest objects with x/y/w/h set.
        """
        x0, x1 = int(cx), int(cx + cw)
        y0, y1 = int(cy), int(cy + ch)
        roi = self.binary()[y0:y1, x0:x1]

        # Vertical projection: dark pixels per row
        proj = roi.sum(axis=1).astype(float)
        proj /= (proj.max() + 1e-6)

        # Smooth to find dense-text bands separated by border lines or whitespace
        kernel_h = max(1, int(self.dpi * 0.03))   # ~9px at 300 DPI
        proj_s = np.convolve(proj, np.ones(kernel_h) / kernel_h, mode='same')

        # Find peaks (dark horizontal lines = contest borders or text-dense areas)
        # Then find gaps between them as contest regions
        splits = self._find_horizontal_splits(proj_s)

        contests = []
        boundaries = [0] + splits + [int(ch)]
        min_h = int(self.dpi * 0.3)   # minimum contest height: 0.3"

        for i in range(len(boundaries) - 1):
            top    = boundaries[i]
            bottom = boundaries[i + 1]
            if bottom - top < min_h:
                continue
            c = Contest(
                x=float(cx),
                y=float(cy + top),
                w=float(cw),
                h=float(bottom - top),
                column=col_idx,
            )
            contests.append(c)

        return contests

    def _find_horizontal_splits(self, proj: np.ndarray) -> list[int]:
        """Find row indices that are likely contest separators."""
        # A separator is either:
        #   (a) a dark peak (border line)
        #   (b) a whitespace gap (no text)
        threshold_dark  = proj.mean() * 2.0
        threshold_light = proj.mean() * 0.15

        splits = []
        prev_was_split = False
        for i, v in enumerate(proj):
            is_split = (v > threshold_dark or v < threshold_light)
            if is_split and not prev_was_split:
                splits.append(i)
            prev_was_split = is_split

        # Merge splits that are very close together
        merged = []
        for s in splits:
            if merged and s - merged[-1] < int(self.dpi * 0.15):
                merged[-1] = (merged[-1] + s) // 2
            else:
                merged.append(s)

        return merged

    # ── Indicator detection ───────────────────────────────────────────────────

    def detect_indicators_in_contest(self, contest: Contest) -> list[Indicator]:
        """
        Find vote indicator shapes within a contest region using connected
        component analysis.  Returns Indicator objects in image pixels.
        """
        x0 = int(contest.x)
        y0 = int(contest.y)
        x1 = int(contest.x + contest.w)
        y1 = int(contest.y + contest.h)
        roi = self.binary()[y0:y1, x0:x1]

        min_w = int(MIN_IND_WIDTH_IN  * self.dpi)
        max_w = int(MAX_IND_WIDTH_IN  * self.dpi)
        min_h = int(MIN_IND_HEIGHT_IN * self.dpi)
        max_h = int(MAX_IND_HEIGHT_IN * self.dpi)

        num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(
            roi, connectivity=8)

        indicators = []
        for label in range(1, num_labels):
            sx  = stats[label, cv2.CC_STAT_LEFT]
            sy  = stats[label, cv2.CC_STAT_TOP]
            sw  = stats[label, cv2.CC_STAT_WIDTH]
            sh  = stats[label, cv2.CC_STAT_HEIGHT]
            area = stats[label, cv2.CC_STAT_AREA]

            # Size filter
            if not (min_w <= sw <= max_w and min_h <= sh <= max_h):
                continue

            # Aspect ratio filter (not too elongated)
            aspect = sw / max(sh, 1)
            if aspect > MAX_ASPECT_RATIO or aspect < 1 / MAX_ASPECT_RATIO:
                continue

            # Solidity filter (filled fraction of bounding box)
            box_area = sw * sh
            solidity = area / box_area if box_area > 0 else 0
            # Allow both hollow (border only ~0.2) and filled (~0.8+)
            if solidity < 0.1:
                continue

            # Dark pixel percentage (is it marked?)
            # Sample from the original grayscale
            patch = self.gray[y0+sy:y0+sy+sh, x0+sx:x0+sx+sw]
            dark_px  = np.sum(patch < 128)
            total_px = patch.size
            dark_pct = 100.0 * dark_px / total_px if total_px > 0 else 0.0

            # Classify indicator style from shape
            style = self._classify_shape(roi[sy:sy+sh, sx:sx+sw], aspect)

            # Apply 1pt inset for sampling region
            inset_px = INDICATOR_INSET_PT / 72.0 * self.dpi
            ind = Indicator(
                x=float(x0 + sx),
                y=float(y0 + sy),
                w=float(sw),
                h=float(sh),
                style=style,
                appears_marked=(dark_pct > 20.0),
                dark_pct=dark_pct,
                confidence=solidity,
            )
            indicators.append(ind)

        # Sort top-to-bottom
        indicators.sort(key=lambda i: i.y)
        return indicators

    def _classify_shape(self, component: np.ndarray, aspect: float) -> str:
        """Classify an indicator shape as OVAL, CHECKBOX, or UNKNOWN."""
        h, w = component.shape
        if h < 2 or w < 2:
            return "UNKNOWN"

        # Fit an ellipse to the component
        contours, _ = cv2.findContours(
            component, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        if not contours:
            return "UNKNOWN"
        cnt = max(contours, key=cv2.contourArea)

        # Perimeter-based circularity (4π·area / perimeter²)
        area      = cv2.contourArea(cnt)
        perimeter = cv2.arcLength(cnt, True)
        if perimeter < 1:
            return "UNKNOWN"
        circularity = 4 * math.pi * area / (perimeter ** 2)

        # Check corner sharpness: if corners are sharp → CHECKBOX
        approx = cv2.approxPolyDP(cnt, 0.04 * perimeter, True)
        if len(approx) <= 6 and circularity < 0.6:
            return "CHECKBOX"
        elif circularity >= 0.5:
            return "OVAL"
        else:
            return "UNKNOWN"

    # ── OCR ───────────────────────────────────────────────────────────────────

    def decode_barcode(self, x: float, y: float,
                        w: float, h: float) -> str:
        """
        Attempt to decode a QR code or barcode in the given region.
        Returns decoded string or "" if not found / pyzbar not installed.
        """
        if not PYZBAR_AVAILABLE:
            return ""
        x0 = max(0, int(x)); y0 = max(0, int(y))
        x1 = min(self.w, int(x + w)); y1 = min(self.h, int(y + h))
        roi = self.gray[y0:y1, x0:x1]
        pil = Image.fromarray(roi)
        try:
            codes = _pyzbar.decode(pil)
            if codes:
                return codes[0].data.decode("utf-8", errors="replace")
        except Exception:
            pass
        return ""

    def ocr_region(self, x: float, y: float, w: float, h: float,
                   config: str = "--psm 6") -> str:
        """Run Tesseract OCR on a rectangular region. Returns stripped text."""
        if not TESSERACT_AVAILABLE:
            return ""
        x0, y0 = max(0, int(x)), max(0, int(y))
        x1, y1 = min(self.w, int(x + w)), min(self.h, int(y + h))
        if x1 <= x0 or y1 <= y0:
            return ""
        roi = self.gray[y0:y1, x0:x1]
        # Upscale for better OCR accuracy
        scale = max(1.0, 200.0 / self.dpi)
        if scale > 1.0:
            roi = cv2.resize(roi, None, fx=scale, fy=scale,
                             interpolation=cv2.INTER_CUBIC)
        pil = Image.fromarray(roi)
        try:
            text = pytesseract.image_to_string(pil, config=config)
            return text.strip()
        except Exception:
            return ""

    def ocr_indicator_label(self, ind: Indicator,
                             contest: Contest) -> str:
        """
        OCR the candidate name next to an indicator.
        Tries to the right first (default layout), then to the left.
        Returns the first non-empty result.
        """
        margin = ind.w * 0.5
        label_h = ind.h * 2.5

        # Try right of indicator
        rx = ind.x + ind.w + margin
        rw = (contest.x + contest.w) - rx - margin
        if rw > self.dpi * 0.3:
            text = self.ocr_region(rx, ind.y - ind.h * 0.5,
                                    rw, label_h, "--psm 7")
            if text:
                return text

        # Try left of indicator
        lw = ind.x - contest.x - margin
        if lw > self.dpi * 0.3:
            text = self.ocr_region(contest.x + margin,
                                    ind.y - ind.h * 0.5,
                                    lw, label_h, "--psm 7")
            if text:
                return text
        return ""

    def ocr_contest_title(self, contest: Contest) -> tuple[str, str]:
        """
        OCR the contest title and instruction from the top portion of a
        contest region.  Returns (title, instruction).
        """
        title_h = min(contest.h * 0.35, self.dpi * 0.6)
        title = self.ocr_region(
            contest.x + 4, contest.y + 4,
            contest.w - 8, title_h, "--psm 6")
        instr_y = contest.y + title_h
        instr_h = min(contest.h * 0.15, self.dpi * 0.25)
        instr = self.ocr_region(
            contest.x + 4, instr_y,
            contest.w - 8, instr_h, "--psm 7")
        return title, instr


# ══════════════════════════════════════════════════════════════════════════════
# YAML writer
# ══════════════════════════════════════════════════════════════════════════════

def layout_to_yaml(layout: Layout) -> dict:
    """Convert an inferred Layout to the bCounter YAML schema."""
    dpi = layout.dpi
    pt_per_inch = 72.0

    def px_to_in(px: float) -> float:
        return round(px / dpi, 5)

    def inset_in(px: float) -> float:
        """Apply 1pt inset in inches."""
        return round((px + INDICATOR_INSET_PT / pt_per_inch * dpi) / dpi, 5)

    def inset_dim_in(px: float) -> float:
        """Dimension reduced by 2pt inset."""
        return round(max(0.01, (px - 2 * INDICATOR_INSET_PT / pt_per_inch * dpi) / dpi), 5)

    page_w_in = px_to_in(layout.page_w)
    page_h_in = px_to_in(layout.page_h)
    ca_left   = px_to_in(layout.content_x)
    ca_top    = px_to_in(layout.content_y)
    ca_w      = px_to_in(layout.content_w)
    ca_h      = px_to_in(layout.content_h)

    contests_yaml = []
    for contest in layout.contests:
        candidates = []
        for ind in contest.indicators:
            # Sampling region: inset 1pt from each edge of the detected box
            samp_left = inset_in(ind.x)
            samp_top  = inset_in(ind.y)
            samp_w    = inset_dim_in(ind.w)
            samp_h    = inset_dim_in(ind.h)
            cand = {
                "name":      ind.candidate or "(unknown)",
                "writeIn":   False,
                "indicator": {
                    "offsetFromLeft":  samp_left,
                    "offsetFromTop":   samp_top,
                    "width":           samp_w,
                    "height":          samp_h,
                    "indicatorStyle":  ind.style,
                },
            }
            candidates.append(cand)

        contests_yaml.append({
            "id":          0,
            "title":       contest.title or "(unknown contest)",
            "contestType": "PLURALITY",
            "maxVotes":    1,
            "column":      contest.column,
            "offsetFromLeft":  px_to_in(contest.x),
            "offsetFromTop":   px_to_in(contest.y),
            "width":           px_to_in(contest.w),
            "height":          px_to_in(contest.h),
            "candidates":  candidates,
        })

    doc = {
        "barcodeData":           layout.barcode_key,
        "pageNumber":            1,
        "dpi":                   dpi,
        "pageWidthIn":           page_w_in,
        "pageHeightIn":          page_h_in,
        "contentAreaOffsetLeft": ca_left,
        "contentAreaOffsetTop":  ca_top,
        "contentAreaWidth":      ca_w,
        "contentAreaHeight":     ca_h,
        "inferredLayout":        True,
        "contests":              contests_yaml,
    }
    return doc


def write_yaml(layout: Layout, out_dir: Path) -> Path:
    """Write the YAML file and return its path."""
    out_dir.mkdir(parents=True, exist_ok=True)
    # Sanitise barcode key for filename
    safe_key = "".join(c if c.isalnum() or c in "-_" else "_"
                       for c in layout.barcode_key)
    filename = f"ballot_{safe_key}.yaml"
    out_path = out_dir / filename
    doc = layout_to_yaml(layout)
    with open(out_path, "w") as f:
        yaml.dump(doc, f, default_flow_style=False, sort_keys=False)
    return out_path


class _ContentBoxProxy:
    """
    Thin proxy that makes the Layout content box look like an object
    with x/y/w/h attributes so the generic drag/resize code works on it.
    """
    def __init__(self, layout: Layout):
        self._layout = layout

    @property
    def x(self): return self._layout.content_x
    @x.setter
    def x(self, v): self._layout.content_x = v

    @property
    def y(self): return self._layout.content_y
    @y.setter
    def y(self, v): self._layout.content_y = v

    @property
    def w(self): return self._layout.content_w
    @w.setter
    def w(self, v): self._layout.content_w = v

    @property
    def h(self): return self._layout.content_h
    @h.setter
    def h(self, v): self._layout.content_h = v


# ══════════════════════════════════════════════════════════════════════════════
# GUI
# ══════════════════════════════════════════════════════════════════════════════

class MapperApp:
    """
    Main tkinter application for bMapper.

    Canvas coordinate system:
      - Image is displayed at self.zoom level
      - canvas_to_image(cx, cy) → image pixel coords
      - image_to_canvas(ix, iy) → canvas pixel coords
    """

    # Overlay colours
    COL_CONTENT  = "#00aaff"   # content bounding box
    COL_COLUMN   = "#00cc44"   # column separators
    COL_CONTEST  = "#3355ff"   # contest boxes
    COL_IND      = "#ff3300"   # indicators (empty)
    COL_MARKED   = "#ff9900"   # indicators (appears marked)
    COL_SELECTED = "#ffee00"   # selected box handles
    COL_HANDLE   = "#ffffff"

    def __init__(self, root: tk.Tk, args):
        self.root     = root
        self.args     = args
        self.out_dir  = Path(args.out)
        self.dpi      = args.dpi

        # State
        self.img_orig  = None    # original PIL image (full res)
        self.img_cv    = None    # OpenCV grayscale (full res)
        self.img_path  = None
        self.zoom      = 1.0
        self.pan_x     = 0
        self.pan_y     = 0
        self.layout    = Layout()
        self.detector  = None

        # Editing state
        self.selected_item  = None   # currently selected box tag
        self.drag_mode      = None   # "move" | "resize-tl" | "resize-br" etc.
        self.drag_start     = (0, 0)
        self.drawing_box    = None   # for user-draw-content-box mode
        self._draw_rect_id  = None
        self.mode           = "select"   # "select" | "draw-content" | "add-indicator"

        self._build_ui()
        root.title("bMapper — Ballot Layout Inference")
        root.geometry("1400x900")

        if args.mode == "verify" and args.yaml:
            self.root.after(100, lambda: self._load_verify_mode(args.yaml, args.image))
        elif args.image:
            self.root.after(100, lambda: self.load_image(args.image))

    # ── UI construction ───────────────────────────────────────────────────────

    def _build_ui(self):
        # Top toolbar
        tb = tk.Frame(self.root, bg="#2b2b2b", height=40)
        tb.pack(fill=tk.X, side=tk.TOP)

        def btn(parent, text, cmd, bg="#444", fg="white"):
            b = tk.Button(parent, text=text, command=cmd,
                          bg=bg, fg=fg, relief=tk.FLAT,
                          padx=8, pady=4, font=("Helvetica", 11))
            b.pack(side=tk.LEFT, padx=2, pady=4)
            return b

        btn(tb, "📂 Open Image",    self.open_image)
        btn(tb, "🔍 Auto-Detect",   self.run_detection)
        btn(tb, "✏️ Draw Content",  self.start_draw_content, bg="#556")
        btn(tb, "＋ Add Indicator", self.start_add_indicator, bg="#556")
        btn(tb, "🗑 Delete Selected", self.delete_selected, bg="#744")
        btn(tb, "📷 Decode QR",     self.decode_qr_region, bg="#556")
        btn(tb, "✏️ Edit Title",    self.edit_contest_title, bg="#556")
        btn(tb, "💾 Save YAML",     self.save_yaml, bg="#363", fg="white")
        btn(tb, "▶ Next Image",     self.next_image, bg="#363")

        self.status_var = tk.StringVar(value="Open a ballot image to begin.")
        tk.Label(tb, textvariable=self.status_var,
                 bg="#2b2b2b", fg="#aaa",
                 font=("Helvetica", 10)).pack(side=tk.RIGHT, padx=10)

        # Main area: canvas + right panel
        main = tk.Frame(self.root)
        main.pack(fill=tk.BOTH, expand=True)

        # Canvas with scrollbars
        canv_frame = tk.Frame(main, bg="#111")
        canv_frame.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        self.canvas = tk.Canvas(canv_frame, bg="#111",
                                cursor="crosshair",
                                highlightthickness=0)
        hbar = tk.Scrollbar(canv_frame, orient=tk.HORIZONTAL,
                            command=self.canvas.xview)
        vbar = tk.Scrollbar(canv_frame, orient=tk.VERTICAL,
                            command=self.canvas.yview)
        self.canvas.configure(xscrollcommand=hbar.set,
                               yscrollcommand=vbar.set)
        hbar.pack(side=tk.BOTTOM, fill=tk.X)
        vbar.pack(side=tk.RIGHT,  fill=tk.Y)
        self.canvas.pack(fill=tk.BOTH, expand=True)

        # Right panel
        rp = tk.Frame(main, bg="#222", width=260)
        rp.pack(side=tk.RIGHT, fill=tk.Y)
        rp.pack_propagate(False)

        tk.Label(rp, text="Barcode / YAML Key",
                 bg="#222", fg="#aaa",
                 font=("Helvetica", 10)).pack(pady=(12, 2))
        self.key_var = tk.StringVar()
        tk.Entry(rp, textvariable=self.key_var,
                 font=("Helvetica", 11), width=22).pack(padx=8)

        tk.Label(rp, text="Output Directory",
                 bg="#222", fg="#aaa",
                 font=("Helvetica", 10)).pack(pady=(10, 2))
        self.out_var = tk.StringVar(value=str(self.out_dir))
        tk.Entry(rp, textvariable=self.out_var,
                 font=("Helvetica", 10), width=22).pack(padx=8)
        tk.Button(rp, text="Browse…",
                  command=self._browse_out,
                  bg="#444", fg="white", relief=tk.FLAT,
                  padx=6, pady=2).pack(pady=4)

        tk.Label(rp, text="Selected Box",
                 bg="#222", fg="#aaa",
                 font=("Helvetica", 10)).pack(pady=(12, 2))
        self.sel_info = tk.Text(rp, height=6, width=28,
                                font=("Helvetica", 9),
                                bg="#333", fg="#eee",
                                relief=tk.FLAT, state=tk.DISABLED)
        self.sel_info.pack(padx=8)

        tk.Label(rp, text="Candidate Name",
                 bg="#222", fg="#aaa",
                 font=("Helvetica", 10)).pack(pady=(8, 2))
        self.cand_var = tk.StringVar()
        self.cand_entry = tk.Entry(rp, textvariable=self.cand_var,
                                   font=("Helvetica", 11), width=22)
        self.cand_entry.pack(padx=8)
        tk.Button(rp, text="Apply Name",
                  command=self.apply_candidate_name,
                  bg="#444", fg="white", relief=tk.FLAT,
                  padx=6, pady=2).pack(pady=4)

        # Zoom controls
        zf = tk.Frame(rp, bg="#222")
        zf.pack(pady=12)
        tk.Button(zf, text="＋ Zoom",
                  command=lambda: self.zoom_by(ZOOM_STEP),
                  bg="#444", fg="white", relief=tk.FLAT,
                  padx=6).pack(side=tk.LEFT, padx=2)
        tk.Button(zf, text="－ Zoom",
                  command=lambda: self.zoom_by(1/ZOOM_STEP),
                  bg="#444", fg="white", relief=tk.FLAT,
                  padx=6).pack(side=tk.LEFT, padx=2)
        tk.Button(zf, text="Fit",
                  command=self.zoom_fit,
                  bg="#444", fg="white", relief=tk.FLAT,
                  padx=6).pack(side=tk.LEFT, padx=2)

        # Legend
        legend_items = [
            (self.COL_CONTENT, "Content box"),
            (self.COL_COLUMN,  "Column separator"),
            (self.COL_CONTEST, "Contest box"),
            (self.COL_IND,     "Indicator (empty)"),
            (self.COL_MARKED,  "Indicator (marked)"),
        ]
        tk.Label(rp, text="Legend", bg="#222", fg="#aaa",
                 font=("Helvetica", 10)).pack(pady=(8, 4))
        for colour, label in legend_items:
            lf = tk.Frame(rp, bg="#222")
            lf.pack(anchor=tk.W, padx=10)
            tk.Label(lf, bg=colour, width=2, height=1).pack(side=tk.LEFT)
            tk.Label(lf, text=f"  {label}", bg="#222", fg="#ccc",
                     font=("Helvetica", 9)).pack(side=tk.LEFT)

        # Canvas bindings
        self.canvas.bind("<ButtonPress-1>",   self.on_press)
        self.canvas.bind("<B1-Motion>",        self.on_drag)
        self.canvas.bind("<ButtonRelease-1>",  self.on_release)
        self.canvas.bind("<MouseWheel>",       self.on_scroll)
        self.canvas.bind("<Button-4>",         self.on_scroll)
        self.canvas.bind("<Button-5>",         self.on_scroll)
        self.canvas.bind("<ButtonPress-3>",    self.on_right_click)

    def _browse_out(self):
        d = filedialog.askdirectory(initialdir=str(self.out_dir))
        if d:
            self.out_var.set(d)
            self.out_dir = Path(d)

    # ── Image loading ─────────────────────────────────────────────────────────

    def _load_verify_mode(self, yaml_path: str, image_path: str | None):
        """Load an existing YAML and overlay on the given (or user-chosen) image."""
        try:
            with open(yaml_path) as f:
                doc = yaml.safe_load(f)
        except Exception as e:
            messagebox.showerror("YAML error", f"Could not load {yaml_path}:\n{e}")
            return

        if not image_path:
            image_path = filedialog.askopenfilename(
                title="Open ballot image for verification",
                filetypes=[("Images", "*.png *.jpg *.jpeg *.tif *.tiff"),
                           ("All files", "*.*")])
        if not image_path:
            return

        self.load_image(image_path)

        # Parse YAML into Layout
        dpi = doc.get("dpi", 300)
        pw  = doc.get("pageWidthIn", self.layout.page_w / dpi) * dpi
        ph  = doc.get("pageHeightIn", self.layout.page_h / dpi) * dpi
        self.layout.barcode_key = doc.get("barcodeData", "")
        self.layout.dpi         = dpi
        self.layout.content_x   = doc.get("contentAreaOffsetLeft", 0) * dpi
        self.layout.content_y   = doc.get("contentAreaOffsetTop",  0) * dpi
        self.layout.content_w   = doc.get("contentAreaWidth",      0) * dpi
        self.layout.content_h   = doc.get("contentAreaHeight",     0) * dpi
        self.key_var.set(self.layout.barcode_key)

        self.layout.contests = []
        for cd in doc.get("contests", []):
            contest = Contest(
                x=cd.get("offsetFromLeft", 0) * dpi,
                y=cd.get("offsetFromTop",  0) * dpi,
                w=cd.get("width",          0) * dpi,
                h=cd.get("height",         0) * dpi,
                title=cd.get("title", ""),
                column=cd.get("column", 0),
            )
            for cand in cd.get("candidates", []):
                ind_d = cand.get("indicator", {})
                ind = Indicator(
                    x=ind_d.get("offsetFromLeft", 0) * dpi,
                    y=ind_d.get("offsetFromTop",  0) * dpi,
                    w=ind_d.get("width",          0) * dpi,
                    h=ind_d.get("height",         0) * dpi,
                    candidate=cand.get("name", ""),
                    style=ind_d.get("indicatorStyle", "OVAL"),
                )
                contest.indicators.append(ind)
            self.layout.contests.append(contest)

        n = sum(len(c.indicators) for c in self.layout.contests)
        self.set_status(
            f"VERIFY mode — loaded {len(self.layout.contests)} contest(s), "
            f"{n} indicator(s) from {Path(yaml_path).name}")
        self.redraw()

    def open_image(self):
        path = filedialog.askopenfilename(
            title="Open ballot scan",
            filetypes=[("Images", "*.png *.jpg *.jpeg *.tif *.tiff *.bmp"),
                       ("All files", "*.*")])
        if path:
            self.load_image(path)

    def load_image(self, path: str):
        self.img_path = path
        pil = Image.open(path).convert("RGB")
        self.img_orig = pil
        arr = np.array(pil)
        self.img_cv  = cv2.cvtColor(arr, cv2.COLOR_RGB2GRAY)
        self.layout  = Layout(dpi=self.dpi,
                               page_w=float(pil.width),
                               page_h=float(pil.height))
        self.detector = Detector(self.img_cv, self.dpi)
        self.selected_item = None
        self.root.title(f"bMapper — {Path(path).name}")
        self.zoom_fit()
        self.redraw()
        self.set_status(f"Loaded {Path(path).name} "
                        f"({pil.width}×{pil.height}px, {self.dpi} DPI). "
                        "Click Auto-Detect to begin.")

    # ── Detection ─────────────────────────────────────────────────────────────

    def run_detection(self):
        if self.img_cv is None:
            messagebox.showwarning("No image", "Open a ballot image first.")
            return

        self.set_status("Detecting content bounding box…")
        self.root.update()

        d = self.detector

        # 1. Content box
        cx, cy, cw, ch = d.infer_content_box()
        self.layout.content_x = cx
        self.layout.content_y = cy
        self.layout.content_w = cw
        self.layout.content_h = ch

        # 2. Columns
        self.set_status("Detecting columns…")
        self.root.update()
        col_bounds = d.detect_columns(cx, cy, cw, ch)
        self.layout.columns = col_bounds

        # 3. Contests + indicators per column
        self.set_status("Detecting contests and indicators…")
        self.root.update()
        self.layout.contests = []
        for i in range(len(col_bounds) - 1):
            col_x = col_bounds[i]
            col_w = col_bounds[i + 1] - col_bounds[i]
            contests = d.detect_contests_in_column(
                col_x, cy, col_w, ch, col_idx=i)
            for contest in contests:
                # OCR title
                if TESSERACT_AVAILABLE:
                    self.set_status(
                        f"OCR: column {i+1}, contest at y={int(contest.y)}…")
                    self.root.update()
                    title, instr = d.ocr_contest_title(contest)
                    contest.title       = title
                    contest.instruction = instr

                # Indicators
                inds = d.detect_indicators_in_contest(contest)
                for ind in inds:
                    if TESSERACT_AVAILABLE:
                        ind.candidate = d.ocr_indicator_label(ind, contest)
                contest.indicators = inds
                self.layout.contests.append(contest)

        n_contests = len(self.layout.contests)
        n_inds     = sum(len(c.indicators) for c in self.layout.contests)
        self.set_status(
            f"Detected {n_contests} contest(s), {n_inds} indicator(s). "
            "Review overlays and correct as needed, then Save YAML.")
        self.redraw()

    # ── Drawing ───────────────────────────────────────────────────────────────

    def redraw(self):
        """Redraw the entire canvas from scratch."""
        self.canvas.delete("all")
        if self.img_orig is None:
            return

        # Draw image at current zoom
        w = int(self.img_orig.width  * self.zoom)
        h = int(self.img_orig.height * self.zoom)
        resized = self.img_orig.resize((w, h), Image.LANCZOS)
        self._tk_img = ImageTk.PhotoImage(resized)
        self.canvas.create_image(0, 0, anchor=tk.NW, image=self._tk_img,
                                  tags="image")
        self.canvas.configure(scrollregion=(0, 0, w, h))

        # Content box
        if self.layout.content_w > 0:
            self._draw_rect("content_box",
                            self.layout.content_x, self.layout.content_y,
                            self.layout.content_w, self.layout.content_h,
                            self.COL_CONTENT, width=2)

        # Column separators
        for i, bx in enumerate(self.layout.columns[1:-1], 1):
            cx = self.ix(bx)
            y0 = self.iy(self.layout.content_y)
            y1 = self.iy(self.layout.content_y + self.layout.content_h)
            self.canvas.create_line(cx, y0, cx, y1,
                                    fill=self.COL_COLUMN,
                                    width=2, dash=(6, 4),
                                    tags=f"col_{i}")

        # Contests and indicators
        for ci, contest in enumerate(self.layout.contests):
            self._draw_rect(f"contest_{ci}",
                            contest.x, contest.y,
                            contest.w, contest.h,
                            self.COL_CONTEST, width=2)
            # Title label
            self.canvas.create_text(
                self.ix(contest.x + 4), self.iy(contest.y + 4),
                text=contest.title[:40] if contest.title else "",
                anchor=tk.NW, fill=self.COL_CONTEST,
                font=("Helvetica", max(8, int(9 * self.zoom))),
                tags=f"contest_{ci}_label")

            for ii, ind in enumerate(contest.indicators):
                tag  = f"ind_{ci}_{ii}"
                col  = self.COL_MARKED if ind.appears_marked else self.COL_IND
                self._draw_rect(tag, ind.x, ind.y, ind.w, ind.h,
                                col, width=2)
                # Candidate label
                label = ind.candidate[:30] if ind.candidate else ""
                self.canvas.create_text(
                    self.ix(ind.x + ind.w + 4), self.iy(ind.cy),
                    text=label, anchor=tk.W, fill=col,
                    font=("Helvetica", max(7, int(8 * self.zoom))),
                    tags=f"{tag}_label")

        # Draw handles for selected item
        if self.selected_item:
            self._draw_handles(self.selected_item)

    def _draw_rect(self, tag: str, x: float, y: float,
                   w: float, h: float, colour: str, width: int = 2):
        self.canvas.create_rectangle(
            self.ix(x), self.iy(y),
            self.ix(x + w), self.iy(y + h),
            outline=colour, width=width, fill="",
            tags=(tag, "box"))

    def _draw_handles(self, tag: str):
        """Draw drag handles at corners of a selected box."""
        coords = self.canvas.coords(tag)
        if len(coords) != 4:
            return
        x0, y0, x1, y1 = coords
        r = HANDLE_R
        for hx, hy, htag in [
            (x0, y0, "tl"), (x1, y0, "tr"),
            (x0, y1, "bl"), (x1, y1, "br"),
        ]:
            self.canvas.create_oval(
                hx - r, hy - r, hx + r, hy + r,
                fill=self.COL_SELECTED, outline=self.COL_HANDLE,
                tags=(f"handle_{htag}", "handle"))

    # ── Coordinate helpers ────────────────────────────────────────────────────

    def ix(self, img_x: float) -> float:
        """Image x → canvas x."""
        return img_x * self.zoom

    def iy(self, img_y: float) -> float:
        """Image y → canvas y."""
        return img_y * self.zoom

    def ci(self, canvas_x: float) -> float:
        """Canvas x → image x."""
        return canvas_x / self.zoom

    def cj(self, canvas_y: float) -> float:
        """Canvas y → image y."""
        return canvas_y / self.zoom

    def canvas_pos(self, event) -> tuple[float, float]:
        """Return canvas-scrolled (x, y) for a mouse event."""
        return (self.canvas.canvasx(event.x),
                self.canvas.canvasy(event.y))

    # ── Mouse interaction ─────────────────────────────────────────────────────

    def on_press(self, event):
        cx, cy = self.canvas_pos(event)

        if self.mode in ("draw-content", "decode-qr", "add-indicator"):
            self._draw_rect_start = (cx, cy)
            return

        if self.mode == "add-indicator-skip":
            self._draw_rect_start = (cx, cy)
            return

        # Check if pressing a handle
        items = self.canvas.find_overlapping(
            cx - HANDLE_R, cy - HANDLE_R,
            cx + HANDLE_R, cy + HANDLE_R)
        for item in reversed(items):
            tags = self.canvas.gettags(item)
            if "handle" in tags:
                htag = next((t for t in tags if t.startswith("handle_")), None)
                if htag:
                    self.drag_mode  = htag.replace("handle_", "resize-")
                    self.drag_start = (cx, cy)
                    return

        # Check if pressing a box
        items = self.canvas.find_overlapping(cx-2, cy-2, cx+2, cy+2)
        for item in reversed(items):
            tags = self.canvas.gettags(item)
            if "box" in tags:
                box_tag = next((t for t in tags if t != "box"), None)
                if box_tag:
                    self.selected_item = box_tag
                    self.drag_mode     = "move"
                    self.drag_start    = (cx, cy)
                    self._update_sel_info()
                    self.redraw()
                    return

        # Click on empty area — deselect
        self.selected_item = None
        self.drag_mode     = None
        self.redraw()

    def on_drag(self, event):
        cx, cy = self.canvas_pos(event)
        dx = (cx - self.drag_start[0]) / self.zoom
        dy = (cy - self.drag_start[1]) / self.zoom
        self.drag_start = (cx, cy)

        if self.mode in ("draw-content", "decode-qr") and hasattr(self, '_draw_rect_start'):
            if self._draw_rect_id:
                self.canvas.delete(self._draw_rect_id)
            x0, y0 = self._draw_rect_start
            self._draw_rect_id = self.canvas.create_rectangle(
                x0, y0, cx, cy,
                outline=self.COL_CONTENT, width=2, fill="")
            return

        if self.mode == "decode-qr" and hasattr(self, '_draw_rect_start'):
            if self._draw_rect_id:
                self.canvas.delete(self._draw_rect_id)
                self._draw_rect_id = None
            x0c, y0c = self._draw_rect_start
            x0 = min(self.ci(x0c), self.ci(cx))
            y0 = min(self.cj(y0c), self.cj(cy))
            x1 = max(self.ci(x0c), self.ci(cx))
            y1 = max(self.cj(y0c), self.cj(cy))
            if x1 - x0 > 10 and y1 - y0 > 10:
                decoded = self.detector.decode_barcode(x0, y0, x1-x0, y1-y0)
                if decoded:
                    self.key_var.set(decoded)
                    self.layout.barcode_key = decoded
                    messagebox.showinfo("QR Decoded",
                        f"Decoded: {decoded}\n\nThis has been set as the YAML key.")
                else:
                    ans = simpledialog.askstring(
                        "No barcode found",
                        "Could not decode a QR code in that region.\n"
                        "Enter the key manually:")
                    if ans:
                        self.key_var.set(ans.strip())
            self.mode = "select"
            del self._draw_rect_start
            return

        if self.mode == "add-indicator" and hasattr(self, '_draw_rect_start'):
            if self._draw_rect_id:
                self.canvas.delete(self._draw_rect_id)
            x0, y0 = self._draw_rect_start
            self._draw_rect_id = self.canvas.create_rectangle(
                x0, y0, cx, cy,
                outline=self.COL_IND, width=2, fill="")
            return

        if not self.selected_item or not self.drag_mode:
            return

        obj = self._find_object(self.selected_item)
        if obj is None:
            return

        if self.drag_mode == "move":
            obj.x += dx; obj.y += dy
        elif self.drag_mode == "resize-tl":
            obj.w  = max(4, obj.w - dx)
            obj.h  = max(4, obj.h - dy)
            obj.x += dx; obj.y += dy
        elif self.drag_mode == "resize-tr":
            obj.w  = max(4, obj.w + dx)
            obj.h  = max(4, obj.h - dy)
            obj.y += dy
        elif self.drag_mode == "resize-bl":
            obj.w  = max(4, obj.w - dx)
            obj.h  = max(4, obj.h + dy)
            obj.x += dx
        elif self.drag_mode == "resize-br":
            obj.w  = max(4, obj.w + dx)
            obj.h  = max(4, obj.h + dy)

        self.redraw()
        self._update_sel_info()

    def on_release(self, event):
        cx, cy = self.canvas_pos(event)

        if self.mode in ("draw-content", "decode-qr") and hasattr(self, '_draw_rect_start'):
            if self._draw_rect_id:
                self.canvas.delete(self._draw_rect_id)
                self._draw_rect_id = None
            x0c, y0c = self._draw_rect_start
            x0 = min(self.ci(x0c), self.ci(cx))
            y0 = min(self.cj(y0c), self.cj(cy))
            x1 = max(self.ci(x0c), self.ci(cx))
            y1 = max(self.cj(y0c), self.cj(cy))
            if x1 - x0 > 10 and y1 - y0 > 10:
                self.layout.content_x = x0
                self.layout.content_y = y0
                self.layout.content_w = x1 - x0
                self.layout.content_h = y1 - y0
            self.mode = "select"
            self.redraw()
            del self._draw_rect_start
            return

        if self.mode == "decode-qr" and hasattr(self, '_draw_rect_start'):
            if self._draw_rect_id:
                self.canvas.delete(self._draw_rect_id)
                self._draw_rect_id = None
            x0c, y0c = self._draw_rect_start
            x0 = min(self.ci(x0c), self.ci(cx))
            y0 = min(self.cj(y0c), self.cj(cy))
            x1 = max(self.ci(x0c), self.ci(cx))
            y1 = max(self.cj(y0c), self.cj(cy))
            if x1 - x0 > 10 and y1 - y0 > 10:
                decoded = self.detector.decode_barcode(x0, y0, x1-x0, y1-y0)
                if decoded:
                    self.key_var.set(decoded)
                    self.layout.barcode_key = decoded
                    messagebox.showinfo("QR Decoded",
                        f"Decoded: {decoded}\n\nThis has been set as the YAML key.")
                else:
                    ans = simpledialog.askstring(
                        "No barcode found",
                        "Could not decode a QR code in that region.\n"
                        "Enter the key manually:")
                    if ans:
                        self.key_var.set(ans.strip())
            self.mode = "select"
            del self._draw_rect_start
            return

        if self.mode == "add-indicator" and hasattr(self, '_draw_rect_start'):
            if self._draw_rect_id:
                self.canvas.delete(self._draw_rect_id)
                self._draw_rect_id = None
            x0c, y0c = self._draw_rect_start
            x0 = min(self.ci(x0c), self.ci(cx))
            y0 = min(self.cj(y0c), self.cj(cy))
            x1 = max(self.ci(x0c), self.ci(cx))
            y1 = max(self.cj(y0c), self.cj(cy))
            if x1 - x0 > 4 and y1 - y0 > 4:
                # Find which contest this falls in
                ind = Indicator(x=x0, y=y0, w=x1-x0, h=y1-y0)
                target = self._find_contest_for_point(x0 + (x1-x0)/2,
                                                       y0 + (y1-y0)/2)
                if target is not None:
                    target.indicators.append(ind)
                else:
                    # Create a new contest for it
                    nc = Contest(x=x0-4, y=y0-4, w=x1-x0+8, h=y1-y0+8)
                    nc.indicators.append(ind)
                    self.layout.contests.append(nc)
            self.mode = "select"
            self.redraw()
            del self._draw_rect_start
            return

        self.drag_mode = None

    def on_scroll(self, event):
        if event.num == 4 or event.delta > 0:
            self.zoom_by(ZOOM_STEP)
        else:
            self.zoom_by(1 / ZOOM_STEP)

    def on_right_click(self, event):
        """Right-click context menu on an indicator."""
        cx, cy = self.canvas_pos(event)
        items = self.canvas.find_overlapping(cx-2, cy-2, cx+2, cy+2)
        for item in reversed(items):
            tags = self.canvas.gettags(item)
            if "box" in tags:
                box_tag = next((t for t in tags if t != "box"), None)
                if box_tag and box_tag.startswith("ind_"):
                    self.selected_item = box_tag
                    self._show_context_menu(event, box_tag)
                    return

    def _show_context_menu(self, event, tag: str):
        m = tk.Menu(self.root, tearoff=0)
        m.add_command(label="Set candidate name…",
                      command=lambda: self._prompt_candidate_name(tag))
        m.add_command(label="Toggle marked/unmarked",
                      command=lambda: self._toggle_marked(tag))
        m.add_separator()
        m.add_command(label="Delete indicator",
                      command=self.delete_selected)
        try:
            m.tk_popup(event.x_root, event.y_root)
        finally:
            m.grab_release()

    # ── Object lookup ─────────────────────────────────────────────────────────

    def _parse_tag(self, tag: str):
        """
        Parse a canvas tag back to a Layout object.
        Returns the object (Contest or Indicator) or None.
        """
        parts = tag.split("_")
        if not parts:
            return None, None, None
        if parts[0] == "content" and parts[1] == "box":
            return "content", None, None
        if parts[0] == "contest":
            ci = int(parts[1])
            if ci < len(self.layout.contests):
                return "contest", ci, None
        if parts[0] == "ind":
            ci = int(parts[1]); ii = int(parts[2])
            if ci < len(self.layout.contests):
                c = self.layout.contests[ci]
                if ii < len(c.indicators):
                    return "ind", ci, ii
        return None, None, None

    def _find_object(self, tag: str):
        """Return the mutable object for a tag, or None."""
        kind, ci, ii = self._parse_tag(tag)
        if kind == "contest" and ci is not None:
            return self.layout.contests[ci]
        if kind == "ind" and ci is not None and ii is not None:
            return self.layout.contests[ci].indicators[ii]
        if kind == "content":
            return _ContentBoxProxy(self.layout)
        return None

    def _find_contest_for_point(self, ix: float, iy: float) -> Optional[Contest]:
        for c in self.layout.contests:
            if c.x <= ix <= c.x + c.w and c.y <= iy <= c.y + c.h:
                return c
        return None

    # ── Selection info ────────────────────────────────────────────────────────

    def _update_sel_info(self):
        if not self.selected_item:
            return
        obj = self._find_object(self.selected_item)
        if obj is None:
            return
        self.sel_info.configure(state=tk.NORMAL)
        self.sel_info.delete("1.0", tk.END)
        if isinstance(obj, Indicator):
            self.sel_info.insert(tk.END,
                f"Indicator\n"
                f"x={obj.x:.0f} y={obj.y:.0f}\n"
                f"w={obj.w:.0f} h={obj.h:.0f}\n"
                f"style={obj.style}\n"
                f"dark={obj.dark_pct:.1f}%\n"
                f"marked={'yes' if obj.appears_marked else 'no'}")
            self.cand_var.set(obj.candidate)
        elif isinstance(obj, Contest):
            self.sel_info.insert(tk.END,
                f"Contest\n"
                f"x={obj.x:.0f} y={obj.y:.0f}\n"
                f"w={obj.w:.0f} h={obj.h:.0f}\n"
                f"col={obj.column}\n"
                f"inds={len(obj.indicators)}")
        self.sel_info.configure(state=tk.DISABLED)

    # ── Toolbar actions ───────────────────────────────────────────────────────

    def decode_qr_region(self):
        """
        Prompt the user to draw a rectangle over the barcode/QR area,
        then decode it and use as the YAML key.
        """
        if self.img_cv is None:
            messagebox.showwarning("No image", "Open a ballot image first.")
            return
        self.mode = "decode-qr"
        self.set_status(
            "Draw a rectangle over the QR code or barcode area to decode it.")

    def edit_contest_title(self):
        """Edit the title of the currently selected contest."""
        if not self.selected_item:
            messagebox.showinfo("No selection", "Select a contest box first.")
            return
        kind, ci, ii = self._parse_tag(self.selected_item)
        if kind != "contest" or ci is None:
            messagebox.showinfo("Not a contest", "Select a contest box first.")
            return
        contest = self.layout.contests[ci]
        title = simpledialog.askstring(
            "Contest Title", "Enter contest title:",
            initialvalue=contest.title)
        if title is not None:
            contest.title = title.strip()
            self.redraw()

    def start_draw_content(self):
        self.mode = "draw-content"
        self.set_status("Draw the content bounding box: click and drag.")

    def start_add_indicator(self):
        self.mode = "add-indicator"
        self.set_status("Draw a new indicator box: click and drag.")

    def delete_selected(self):
        if not self.selected_item:
            return
        kind, ci, ii = self._parse_tag(self.selected_item)
        if kind == "ind" and ci is not None and ii is not None:
            self.layout.contests[ci].indicators.pop(ii)
        elif kind == "contest" and ci is not None:
            self.layout.contests.pop(ci)
        self.selected_item = None
        self.redraw()

    def apply_candidate_name(self):
        if not self.selected_item:
            return
        kind, ci, ii = self._parse_tag(self.selected_item)
        if kind == "ind" and ci is not None and ii is not None:
            self.layout.contests[ci].indicators[ii].candidate = \
                self.cand_var.get().strip()
            self.redraw()

    def _prompt_candidate_name(self, tag: str):
        self.selected_item = tag
        kind, ci, ii = self._parse_tag(tag)
        if kind == "ind" and ci is not None and ii is not None:
            ind = self.layout.contests[ci].indicators[ii]
            name = simpledialog.askstring(
                "Candidate Name", "Enter candidate name:",
                initialvalue=ind.candidate)
            if name is not None:
                ind.candidate = name.strip()
                self.redraw()

    def _toggle_marked(self, tag: str):
        kind, ci, ii = self._parse_tag(tag)
        if kind == "ind" and ci is not None and ii is not None:
            ind = self.layout.contests[ci].indicators[ii]
            ind.appears_marked = not ind.appears_marked
            self.redraw()

    # ── Save YAML ─────────────────────────────────────────────────────────────

    def save_yaml(self):
        if not self.layout.contests:
            messagebox.showwarning("Nothing to save",
                                   "Run detection first.")
            return

        key = self.key_var.get().strip()
        if not key:
            key = simpledialog.askstring(
                "YAML Key",
                "Enter the barcode/key string for this ballot type.\n"
                "This will be the YAML filename: ballot_<key>.yaml\n\n"
                "For pbss ballots: e.g. 1|1|1|1|1|1\n"
                "For alien ballots: any text, e.g. PCT-042")
            if not key:
                return
            self.key_var.set(key)

        self.layout.barcode_key = key
        out_dir = Path(self.out_var.get())

        try:
            out_path = write_yaml(self.layout, out_dir)
            messagebox.showinfo("Saved",
                f"YAML written to:\n{out_path}\n\n"
                f"bCounter can now use this layout for ballots "
                f"with key '{key}'.")
            self.set_status(f"Saved: {out_path.name}")
        except Exception as e:
            messagebox.showerror("Save failed", str(e))

    # ── Next image ────────────────────────────────────────────────────────────

    def next_image(self):
        """Prompt for another image of the same ballot type."""
        ans = messagebox.askquestion(
            "Next Image",
            "Load another image?\n\n"
            "• Yes — same ballot type (keeps YAML key and output dir)\n"
            "• No — cancel",
            icon="question")
        if ans == "yes":
            path = filedialog.askopenfilename(
                title="Open next ballot scan",
                filetypes=[("Images", "*.png *.jpg *.jpeg *.tif *.tiff"),
                           ("All files", "*.*")])
            if path:
                # Keep barcode key and out dir
                old_key = self.key_var.get()
                self.load_image(path)
                self.key_var.set(old_key)

    # ── Zoom ─────────────────────────────────────────────────────────────────

    def zoom_by(self, factor: float):
        self.zoom = max(0.05, min(8.0, self.zoom * factor))
        self.redraw()

    def zoom_fit(self):
        if self.img_orig is None:
            return
        cw = self.canvas.winfo_width()  or 800
        ch = self.canvas.winfo_height() or 700
        zx = cw / self.img_orig.width
        zy = ch / self.img_orig.height
        self.zoom = min(zx, zy) * 0.95
        self.redraw()

    # ── Status ────────────────────────────────────────────────────────────────

    def set_status(self, msg: str):
        self.status_var.set(msg)
        self.root.update_idletasks()


# ══════════════════════════════════════════════════════════════════════════════
# Entry point
# ══════════════════════════════════════════════════════════════════════════════

def parse_args():
    p = argparse.ArgumentParser(
        description="bMapper — Ballot Layout Inference Tool")
    p.add_argument("--image",  default=None,
                   help="Path to ballot scan image (optional — can open via UI)")
    p.add_argument("--out",    default=str(Path.home() / "pbss_data/ballot_templates"),
                   help="Output directory for YAML files")
    p.add_argument("--dpi",   type=int, default=300,
                   help="Scanner DPI (default 300)")
    p.add_argument("--mode",  default="learn",
                   choices=["learn", "verify"],
                   help="learn = infer new layout; verify = overlay existing YAML")
    p.add_argument("--yaml",  default=None,
                   help="Existing YAML file (verify mode only)")
    return p.parse_args()


def main():
    args = parse_args()
    root = tk.Tk()
    app = MapperApp(root, args)
    root.mainloop()


if __name__ == "__main__":
    main()
