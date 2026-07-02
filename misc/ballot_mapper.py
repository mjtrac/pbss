#!/usr/bin/env python3
"""
ballot_mapper.py — Interactive Ballot Image → bCounter YAML Mapper
                   with Design Learning and Code Generation

Two modes:

  LEARN mode  (--learn):
    Annotate 1-2 ballot images to teach the tool the ballot design:
    - Click the 4 content-area corners
    - Draw rectangles around each corner/page mark
    - Confirm column dividers and contest structure
    → Saves ballot_design.json + generates corner_detector.py
      and CornerDetectionService_custom.java

  MAP mode  (default):
    Map a ballot image to a bCounter YAML layout file.
    If --profile is given, auto-detects corners and segments contests
    using the learned design. Otherwise uses manual clicks throughout.

Usage:
    # Learn a design from example ballots:
    python ballot_mapper.py ballot_example.png --learn --name "Acme 2026"

    # Add a second training image to refine:
    python ballot_mapper.py ballot_example2.png --learn \
        --profile ballot_design.json

    # Map a new ballot using the learned design:
    python ballot_mapper.py ballot_precinct3.png \
        --profile ballot_design.json --out precinct3.yaml

    # Map without a learned design (fully manual):
    python ballot_mapper.py ballot.png --dpi 300 --out ballot.yaml

Requirements:
    pip install opencv-python-headless Pillow pyzbar pytesseract PyYAML
    brew install tesseract   # macOS  (for OCR)
"""

import argparse
import base64
import json
import re
import sys
import tkinter as tk
from tkinter import ttk, messagebox, simpledialog
from pathlib import Path

import cv2
import numpy as np
import yaml
from PIL import Image, ImageTk

# ── Sibling module ────────────────────────────────────────────────────────────
try:
    from design_profile import (DesignProfile, MarkTemplate,
                                generate_python_detector,
                                generate_java_service)
    HAS_PROFILE = True
except ImportError:
    HAS_PROFILE = False
    print("⚠  design_profile.py not found alongside ballot_mapper.py")
    print("   Learning and code generation will be unavailable.")

# ── Optional imports ──────────────────────────────────────────────────────────
try:
    from pyzbar import pyzbar
    HAS_PYZBAR = True
except ImportError:
    HAS_PYZBAR = False
    print("⚠  pyzbar not installed — barcode detection disabled")

try:
    import pytesseract
    HAS_TESSERACT = True
except ImportError:
    HAS_TESSERACT = False
    print("⚠  pytesseract not installed — OCR disabled")


# ══════════════════════════════════════════════════════════════════════════════
# Image utilities  (same as before)
# ══════════════════════════════════════════════════════════════════════════════

def load_image(path):
    pil = Image.open(path).convert("RGB")
    cv  = cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)
    return pil, cv


def detect_barcodes(cv_img):
    if not HAS_PYZBAR:
        return []
    gray    = cv2.cvtColor(cv_img, cv2.COLOR_BGR2GRAY)
    decoded = pyzbar.decode(gray)
    results = []
    for d in decoded:
        data = d.data.decode("utf-8", errors="replace")
        pts  = d.polygon
        if pts:
            xs = [p.x for p in pts]; ys = [p.y for p in pts]
            bbox = (min(xs), min(ys), max(xs), max(ys))
        else:
            bbox = (d.rect.left, d.rect.top,
                    d.rect.left+d.rect.width, d.rect.top+d.rect.height)
        results.append((data, bbox))
    return results


def ocr_region(cv_img, x0, y0, x1, y1, pad=4):
    if not HAS_TESSERACT:
        return ""
    h, w = cv_img.shape[:2]
    rx0 = max(0, x0-pad); ry0 = max(0, y0-pad)
    rx1 = min(w, x1+pad); ry1 = min(h, y1+pad)
    if rx1 <= rx0 or ry1 <= ry0:
        return ""
    roi  = cv_img[ry0:ry1, rx0:rx1]
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
    _, thresh = cv2.threshold(gray, 0, 255,
                              cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    text = pytesseract.image_to_string(thresh, config="--psm 6 --oem 3")
    return text.strip()


def find_indicator_blobs(cv_img, x0, y0, x1, y1, dpi,
                         candidate_row_profile=None):
    """Find vote indicator blobs. Uses candidate_row_profile if available."""
    h, w = cv_img.shape[:2]
    rx0 = max(0, x0); ry0 = max(0, y0)
    rx1 = min(w, x1); ry1 = min(h, y1)
    if rx1 <= rx0 or ry1 <= ry0:
        return []

    roi  = cv_img[ry0:ry1, rx0:rx1]
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
    _, thresh = cv2.threshold(gray, 0, 255,
                              cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL,
                                   cv2.CHAIN_APPROX_SIMPLE)

    if candidate_row_profile:
        p       = candidate_row_profile
        min_w   = max(2, int(p.indicator_width_in  * dpi * 0.4))
        max_w   = int(p.indicator_width_in  * dpi * 2.5)
        min_h   = max(2, int(p.indicator_height_in * dpi * 0.4))
        max_h   = int(p.indicator_height_in * dpi * 2.5)
    else:
        min_w = min_h = max(4, int(dpi * 0.08))
        max_w = max_h = int(dpi * 0.75)

    blobs = []
    for cnt in contours:
        bx, by, bw, bh = cv2.boundingRect(cnt)
        if bw < min_w or bh < min_h or bw > max_w or bh > max_h:
            continue
        aspect = bw / max(1, bh)
        if aspect < 0.2 or aspect > 5.0:
            continue
        area = cv2.contourArea(cnt)
        if area / max(1, bw*bh) < 0.04:
            continue
        cx = rx0 + bx + bw // 2
        cy = ry0 + by + bh // 2
        blobs.append((cx, cy, bw, bh))

    if not blobs:
        return []

    blobs.sort(key=lambda b: b[1])
    row_gap = max(int(dpi * 0.12), 8)
    rows = []; current = [blobs[0]]
    for b in blobs[1:]:
        if abs(b[1] - current[-1][1]) < row_gap:
            current.append(b)
        else:
            rows.append(current); current = [b]
    rows.append(current)

    result = []
    for row in rows:
        row.sort(key=lambda b: b[0])
        result.append(row[0])
    return result


# ── Data model ────────────────────────────────────────────────────────────────

class Indicator:
    def __init__(self, cx, cy, w, h):
        self.cx=cx; self.cy=cy; self.w=w; self.h=h
    def to_bbox(self, dpi):
        return ((self.cx-self.w/2)/dpi, (self.cy-self.h/2)/dpi,
                self.w/dpi, self.h/dpi)

class Candidate:
    def __init__(self, name="", write_in=False, indicator=None):
        self.name=name; self.write_in=write_in; self.indicator=indicator

class Contest:
    def __init__(self, title="", contest_type="PLURALITY", max_votes=1,
                 x0=0, y0=0, x1=0, y1=0):
        self.title=title; self.contest_type=contest_type
        self.max_votes=max_votes
        self.x0=x0; self.y0=y0; self.x1=x1; self.y1=y1
        self.candidates=[]

class BallotLayout:
    def __init__(self):
        self.barcode_data=""; self.page_number=1; self.dpi=300
        self.img_w=0; self.img_h=0
        self.corners={}; self.contests=[]; self.column_divs=[]


# ══════════════════════════════════════════════════════════════════════════════
# Main Application
# ══════════════════════════════════════════════════════════════════════════════

CORNER_ORDER  = ["TL","TR","BR","BL"]
CORNER_COLORS = {"TL":"#e74c3c","TR":"#e67e22","BR":"#27ae60","BL":"#2980b9"}
CORNER_LABELS = {"TL":"Top-Left content corner","TR":"Top-Right content corner",
                 "BR":"Bottom-Right content corner","BL":"Bottom-Left content corner"}

# Page mark labels for learn mode
PAGE_MARK_ORDER  = ["PTL","PTR"]
PAGE_MARK_COLORS = {"PTL":"#f0e040","PTR":"#c0f040"}
PAGE_MARK_LABELS = {"PTL":"Page Top-Left mark (near top-left of page)",
                    "PTR":"Page Top-Right mark (near top-right of page)"}


class BallotMapperApp:

    def __init__(self, root, pil_img, cv_img, dpi, out_path,
                 profile=None, learn_mode=False, format_name=""):
        self.root       = root
        self.pil_img    = pil_img
        self.cv_img     = cv_img
        self.dpi        = dpi
        self.out_path   = out_path
        self.profile    = profile       # DesignProfile or None
        self.learn_mode = learn_mode
        self.format_name= format_name

        self.layout     = BallotLayout()
        self.layout.dpi = dpi
        self.layout.img_w = pil_img.width
        self.layout.img_h = pil_img.height

        self.scale      = 1.0
        self.tk_img     = None

        # Learn mode extras
        self._mark_crop_mode  = False   # True while drawing a crop rect
        self._mark_crop_label = None
        self._crop_start      = None
        self._crop_rect_id    = None
        self._mark_crops      = {}      # label→(x0,y0,x1,y1) in image coords
        self._page_mark_idx   = 0
        self._draw_page_marks = False

        # Map mode
        self.mode          = "idle"
        self.corner_idx    = 0
        self.col_div_count = 0
        self._col_idx      = 0
        self._col_clicks   = []
        self._current_col_x= None
        self.columns       = []
        self.col_top       = 0
        self.col_bot       = 0

        self._build_ui()
        self._auto_detect()

        if self.learn_mode:
            self._start_learn_corners()
        elif profile and HAS_PROFILE:
            self._auto_apply_profile()
        else:
            self._start_corner_marking()

    # ── UI ────────────────────────────────────────────────────────────────────

    def _build_ui(self):
        title = ("LEARN MODE — " if self.learn_mode else "") + \
                "Ballot Mapper"
        self.root.title(title)
        self.root.geometry("1200x820")

        left = tk.Frame(self.root)
        left.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        self.canvas = tk.Canvas(left, bg="#222", cursor="crosshair")
        hbar = ttk.Scrollbar(left, orient=tk.HORIZONTAL,
                             command=self.canvas.xview)
        vbar = ttk.Scrollbar(left, orient=tk.VERTICAL,
                             command=self.canvas.yview)
        self.canvas.configure(xscrollcommand=hbar.set,
                              yscrollcommand=vbar.set)
        hbar.pack(side=tk.BOTTOM, fill=tk.X)
        vbar.pack(side=tk.RIGHT,  fill=tk.Y)
        self.canvas.pack(fill=tk.BOTH, expand=True)
        self.canvas.bind("<Button-1>",        self._on_click)
        self.canvas.bind("<B1-Motion>",       self._on_drag)
        self.canvas.bind("<ButtonRelease-1>", self._on_release)
        self.canvas.bind("<MouseWheel>",      self._on_scroll)
        self.canvas.bind("<Button-4>",        self._on_scroll)
        self.canvas.bind("<Button-5>",        self._on_scroll)

        zoom_bar = tk.Frame(left)
        zoom_bar.pack(side=tk.BOTTOM, fill=tk.X)
        tk.Button(zoom_bar,text="−",command=self._zoom_out).pack(side=tk.LEFT)
        self.zoom_label=tk.Label(zoom_bar,text="100%")
        self.zoom_label.pack(side=tk.LEFT,padx=4)
        tk.Button(zoom_bar,text="+",command=self._zoom_in).pack(side=tk.LEFT)
        tk.Button(zoom_bar,text="Fit",command=self._zoom_fit).pack(side=tk.LEFT,padx=8)

        # "Next column" button
        self.next_col_btn = tk.Button(
            left, text="▶ Done with this column — next column",
            bg="#334155", fg="white",
            command=self._finish_column)
        self.next_col_btn.pack(side=tk.BOTTOM, fill=tk.X)

        # Right panel
        right = tk.Frame(self.root, width=330, bg="#1a1a2e")
        right.pack(side=tk.RIGHT, fill=tk.Y)
        right.pack_propagate(False)

        mode_label = "🎓 LEARN MODE" if self.learn_mode else "🗳  MAP MODE"
        tk.Label(right, text=mode_label, bg="#1a1a2e", fg="#f59e0b",
                 font=("Helvetica",11,"bold"), pady=6).pack(fill=tk.X)

        self.instruction_var = tk.StringVar(value="Starting…")
        tk.Label(right, textvariable=self.instruction_var,
                 wraplength=310, justify=tk.LEFT, bg="#1a1a2e",
                 fg="#aad4f5", font=("Helvetica",11), pady=8,
                 padx=8).pack(fill=tk.X)

        tk.Label(right, text="Barcode / QR:", bg="#1a1a2e", fg="#94a3b8",
                 font=("Helvetica",9,"bold"), anchor="w",
                 padx=8).pack(fill=tk.X)
        self.barcode_var = tk.StringVar(value="(not detected)")
        tk.Label(right, textvariable=self.barcode_var, bg="#1a1a2e",
                 fg="#e2e8f0", font=("Courier",9), wraplength=310,
                 anchor="w", padx=8).pack(fill=tk.X)

        tk.Label(right, text="Log:", bg="#1a1a2e", fg="#94a3b8",
                 font=("Helvetica",9,"bold"), anchor="w",
                 padx=8, pady=(10,0)).pack(fill=tk.X)
        self.log_text = tk.Text(right, height=18, bg="#0f172a",
                                fg="#cbd5e1", font=("Courier",9),
                                wrap=tk.WORD, padx=6, pady=4,
                                state=tk.DISABLED)
        self.log_text.pack(fill=tk.BOTH, expand=True, padx=6, pady=4)

        btn_frame = tk.Frame(right, bg="#1a1a2e")
        btn_frame.pack(fill=tk.X, pady=4, padx=6)
        tk.Button(btn_frame, text="Undo last click",
                  command=self._undo).pack(fill=tk.X, pady=2)
        tk.Button(btn_frame, text="Edit contests / candidates",
                  command=self._open_editor).pack(fill=tk.X, pady=2)

        if self.learn_mode and HAS_PROFILE:
            tk.Button(btn_frame,
                      text="💾 Save design profile + generate code",
                      bg="#7c3aed", fg="white",
                      command=self._save_profile_and_generate
                      ).pack(fill=tk.X, pady=2)

        tk.Button(btn_frame, text="Export YAML",
                  bg="#1a6a6a", fg="white",
                  command=self._export_yaml).pack(fill=tk.X, pady=2)

        self._render_image()

    def _log(self, msg):
        self.log_text.configure(state=tk.NORMAL)
        self.log_text.insert(tk.END, msg + "\n")
        self.log_text.see(tk.END)
        self.log_text.configure(state=tk.DISABLED)

    def _set_instruction(self, text):
        self.instruction_var.set(text)

    # ── Rendering ─────────────────────────────────────────────────────────────

    def _render_image(self):
        w = int(self.pil_img.width  * self.scale)
        h = int(self.pil_img.height * self.scale)
        resized  = self.pil_img.resize((w, h), Image.LANCZOS)
        self.tk_img = ImageTk.PhotoImage(resized)
        self.canvas.delete("all")
        self.canvas.create_image(0, 0, anchor=tk.NW, image=self.tk_img)
        self.canvas.configure(scrollregion=(0, 0, w, h))
        self.zoom_label.configure(text=f"{int(self.scale*100)}%")
        self._redraw_overlays()

    def _zoom_in(self):
        self.scale = min(4.0, self.scale * 1.25); self._render_image()
    def _zoom_out(self):
        self.scale = max(0.1, self.scale / 1.25); self._render_image()
    def _zoom_fit(self):
        cw = self.canvas.winfo_width()  or 800
        ch = self.canvas.winfo_height() or 600
        self.scale = min(cw/self.pil_img.width, ch/self.pil_img.height)
        self._render_image()
    def _on_scroll(self, event):
        if event.num==4 or event.delta>0: self._zoom_in()
        else: self._zoom_out()

    def _img_to_canvas(self, ix, iy):
        return ix*self.scale, iy*self.scale
    def _canvas_to_image(self, cx, cy):
        x = self.canvas.canvasx(cx); y = self.canvas.canvasy(cy)
        return int(x/self.scale), int(y/self.scale)

    def _redraw_overlays(self):
        for name, (ix,iy) in self.layout.corners.items():
            col = CORNER_COLORS.get(name, PAGE_MARK_COLORS.get(name,"#fff"))
            self._draw_crosshair(ix, iy, col, name)
        if "TL" in self.layout.corners and "BL" in self.layout.corners:
            tly = self.layout.corners["TL"][1]
            bly = self.layout.corners["BL"][1]
            for dx in self.layout.column_divs:
                x,y0=self._img_to_canvas(dx,tly); _,y1=self._img_to_canvas(dx,bly)
                self.canvas.create_line(x,y0,x,y1,fill="#f59e0b",width=2,dash=(6,3))
        for i, c in enumerate(self.layout.contests):
            x0,y0=self._img_to_canvas(c.x0,c.y0)
            x1,y1=self._img_to_canvas(c.x1,c.y1)
            self.canvas.create_rectangle(x0,y0,x1,y1,outline="#a855f7",width=2)
            self.canvas.create_text(x0+4,y0+4,
                text=f"#{i+1} {c.title[:22]}",
                fill="#a855f7",anchor=tk.NW,font=("Helvetica",8))
            for cand in c.candidates:
                if cand.indicator:
                    ind=cand.indicator
                    bx0,by0=self._img_to_canvas(ind.cx-ind.w//2,ind.cy-ind.h//2)
                    bx1,by1=self._img_to_canvas(ind.cx+ind.w//2,ind.cy+ind.h//2)
                    self.canvas.create_rectangle(bx0,by0,bx1,by1,
                                                 outline="#22c55e",width=1)
        # Learn mode: show mark crop boxes
        for label, (cx0,cy0,cx1,cy1) in self._mark_crops.items():
            col = PAGE_MARK_COLORS.get(label, CORNER_COLORS.get(label,"#ff0"))
            bx0,by0=self._img_to_canvas(cx0,cy0)
            bx1,by1=self._img_to_canvas(cx1,cy1)
            self.canvas.create_rectangle(bx0,by0,bx1,by1,
                                         outline=col,width=2,dash=(4,2))
            self.canvas.create_text(bx0+2,by0+2,text=label,
                                    fill=col,anchor=tk.NW,
                                    font=("Helvetica",8,"bold"))

    def _draw_crosshair(self, ix, iy, color, label):
        cx,cy=self._img_to_canvas(ix,iy); r=10
        self.canvas.create_line(cx-r,cy,cx+r,cy,fill=color,width=2)
        self.canvas.create_line(cx,cy-r,cx,cy+r,fill=color,width=2)
        self.canvas.create_oval(cx-r,cy-r,cx+r,cy+r,outline=color,width=2)
        self.canvas.create_text(cx+r+4,cy,text=label,fill=color,
                                anchor=tk.W,font=("Helvetica",9,"bold"))

    # ── Mouse events ──────────────────────────────────────────────────────────

    def _on_click(self, event):
        ix, iy = self._canvas_to_image(event.x, event.y)
        if self._mark_crop_mode:
            self._crop_start = (ix, iy)
            return
        if   self.mode=="corners":   self._handle_corner_click(ix,iy)
        elif self.mode=="page_marks":self._handle_page_mark_click(ix,iy)
        elif self.mode=="col_divs":  self._handle_col_div_click(ix,iy)
        elif self.mode=="contests":  self._handle_contest_click(ix,iy)

    def _on_drag(self, event):
        if not self._mark_crop_mode or not self._crop_start:
            return
        ix, iy = self._canvas_to_image(event.x, event.y)
        if self._crop_rect_id:
            self.canvas.delete(self._crop_rect_id)
        x0,y0=self._img_to_canvas(*self._crop_start)
        x1,y1=self._img_to_canvas(ix,iy)
        col = PAGE_MARK_COLORS.get(self._mark_crop_label,
              CORNER_COLORS.get(self._mark_crop_label,"#ff0"))
        self._crop_rect_id = self.canvas.create_rectangle(
            x0,y0,x1,y1,outline=col,width=2,dash=(3,2))

    def _on_release(self, event):
        if not self._mark_crop_mode or not self._crop_start:
            return
        ix, iy = self._canvas_to_image(event.x, event.y)
        if self._crop_rect_id:
            self.canvas.delete(self._crop_rect_id)
            self._crop_rect_id = None
        sx, sy = self._crop_start
        self._crop_start = None
        x0,y0 = min(sx,ix),min(sy,iy)
        x1,y1 = max(sx,ix),max(sy,iy)
        if x1-x0 < 5 or y1-y0 < 5:
            return
        label = self._mark_crop_label
        self._mark_crops[label] = (x0,y0,x1,y1)
        self._log(f"  Cropped {label}: ({x0},{y0})→({x1},{y1})")
        self._redraw_overlays()
        self._mark_crop_mode  = False
        self._mark_crop_label = None
        self._next_mark_crop()

    def _undo(self):
        if self.mode=="corners" and self.corner_idx>0:
            self.corner_idx-=1
            name=CORNER_ORDER[self.corner_idx]
            self.layout.corners.pop(name,None)
            self._render_image()
            self._set_instruction(
                f"Click {CORNER_LABELS[CORNER_ORDER[self.corner_idx]]}")
        elif self.mode=="col_divs" and self.layout.column_divs:
            self.layout.column_divs.pop(); self._render_image()
        elif self.mode=="contests" and self.layout.contests:
            self.layout.contests.pop()
            if self._col_clicks:
                self._col_clicks.pop()
            self._render_image()

    # ── Auto-detect ───────────────────────────────────────────────────────────

    def _auto_detect(self):
        self._log("Auto-detecting barcodes…")
        codes = detect_barcodes(self.cv_img)
        if codes:
            for data, bbox in codes:
                self._log(f"  Barcode: {data}")
                x0,y0,x1,y1=bbox
                bx0,by0=self._img_to_canvas(x0,y0)
                bx1,by1=self._img_to_canvas(x1,y1)
                self.canvas.create_rectangle(bx0,by0,bx1,by1,
                                             outline="#06b6d4",width=2)
            self.layout.barcode_data = codes[0][0]
            self.barcode_var.set(self.layout.barcode_data)
            parts = self.layout.barcode_data.split("|")
            if len(parts)>=6:
                try: self.layout.page_number=int(parts[5])
                except ValueError: pass
        else:
            self._log("  No barcodes detected")
            val = simpledialog.askstring(
                "Barcode data",
                "No barcode detected. Enter ballot ID / barcode string\n"
                "(or leave blank):", parent=self.root)
            if val:
                self.layout.barcode_data = val.strip()
                self.barcode_var.set(self.layout.barcode_data)

    # ── Profile auto-apply ────────────────────────────────────────────────────

    def _auto_apply_profile(self):
        """Use the loaded design profile to auto-detect corners and segments."""
        self._log("── Applying learned design profile ──")
        self._log(f"  Format: {self.profile.format_name}")

        corners = self.profile.find_corners(self.cv_img, self.dpi)
        if corners:
            self.layout.corners = corners
            self._log(f"  Found {len(corners)} corner/page marks: "
                      f"{list(corners.keys())}")
            self._render_image()
        else:
            self._log("  ⚠  No corners found — switching to manual mode")
            self._start_corner_marking()
            return

        # Build columns from profile
        if "TL" in corners and "TR" in corners:
            ca_x0 = corners["TL"][0]; ca_x1 = corners["TR"][0]
            ca_y0 = corners["TL"][1]
            ca_y1 = corners.get("BL",(0,corners["TL"][1]+
                                   int(self.profile.content_area.height_in*self.dpi)))[1]

            col_ranges = self.profile.column_x_ranges(ca_x0, ca_x1)
            self.columns   = col_ranges
            self.col_top   = ca_y0
            self.col_bot   = ca_y1
            self.layout.column_divs = [c[1] for c in col_ranges[:-1]]
            self._log(f"  {len(col_ranges)} column(s) from profile")

            # Auto-segment contests in each column
            self._log("  Auto-segmenting contests…")
            for col_x0, col_x1 in col_ranges:
                tops = self.profile.find_contest_boundaries(
                    self.cv_img, col_x0, ca_y0, col_x1, ca_y1)
                self._log(f"  Column x={col_x0}–{col_x1}: "
                          f"{len(tops)} separator(s) found")
                for i, top_y in enumerate(tops):
                    bot_y = tops[i+1]-2 if i+1<len(tops) else ca_y1
                    self._add_contest(col_x0, top_y, col_x1, bot_y)

            self._render_image()
            self._set_instruction(
                f"Profile applied — {len(self.layout.contests)} contest(s) detected.\n\n"
                "Review with 'Edit contests / candidates', correct any OCR errors,\n"
                "then 'Export YAML'.")
            self._log(f"  Done — {len(self.layout.contests)} contest(s)")
        else:
            self._log("  ⚠  Insufficient corners — switching to manual mode")
            self._start_corner_marking()

    # ── Learn mode: corners ───────────────────────────────────────────────────

    def _start_learn_corners(self):
        self.mode       = "corners"
        self.corner_idx = 0
        self._set_instruction(
            "LEARN — Step 1/4: Click the 4 content-area corners.\n\n"
            f"First: {CORNER_LABELS[CORNER_ORDER[0]]}")
        self._log("── Learn Step 1: Content area corners ──")

    def _handle_corner_click(self, ix, iy):
        name = CORNER_ORDER[self.corner_idx]
        self.layout.corners[name] = (ix,iy)
        self._draw_crosshair(ix,iy,CORNER_COLORS[name],name)
        self._log(f"  {name}: ({ix},{iy})")
        self.corner_idx+=1
        if self.corner_idx<len(CORNER_ORDER):
            nxt=CORNER_ORDER[self.corner_idx]
            if self.learn_mode:
                self._set_instruction(
                    f"LEARN — Step 1/4: Click {CORNER_LABELS[nxt]}")
            else:
                self._set_instruction(f"Click {CORNER_LABELS[nxt]}")
        else:
            if self.learn_mode:
                self._start_learn_page_marks()
            else:
                self._start_column_marking()

    # ── Learn mode: page marks ────────────────────────────────────────────────

    def _start_learn_page_marks(self):
        self.mode = "page_marks"
        self._page_mark_idx = 0
        has_page_marks = messagebox.askyesno(
            "Page marks",
            "Does this ballot format have page-level orientation marks\n"
            "(PTL/PTR — marks near the top of the page separate from\n"
            "the content box corners)?\n\n"
            "Examples: small rectangles near the top margin outside\n"
            "the content area border.",
            parent=self.root)
        if has_page_marks:
            label = PAGE_MARK_ORDER[0]
            self._set_instruction(
                f"LEARN — Step 2/4: Click the {PAGE_MARK_LABELS[label]}")
            self._log("── Learn Step 2: Page marks ──")
        else:
            self._log("── Skipping page marks (none on this format) ──")
            self._start_learn_mark_crops()

    def _handle_page_mark_click(self, ix, iy):
        label = PAGE_MARK_ORDER[self._page_mark_idx]
        self.layout.corners[label] = (ix,iy)
        self._draw_crosshair(ix,iy,PAGE_MARK_COLORS[label],label)
        self._log(f"  {label}: ({ix},{iy})")
        self._page_mark_idx+=1
        if self._page_mark_idx<len(PAGE_MARK_ORDER):
            nxt=PAGE_MARK_ORDER[self._page_mark_idx]
            self._set_instruction(
                f"LEARN — Step 2/4: Click {PAGE_MARK_LABELS[nxt]}")
        else:
            self._start_learn_mark_crops()

    # ── Learn mode: mark crops ────────────────────────────────────────────────

    def _start_learn_mark_crops(self):
        """Ask user to draw rectangles around each mark."""
        self.mode = "mark_crops"
        all_mark_labels = [k for k in self.layout.corners.keys()
                           if k in CORNER_ORDER+PAGE_MARK_ORDER]
        self._pending_crop_labels = list(all_mark_labels)
        self._log("── Learn Step 3: Draw rectangles around each mark ──")
        self._next_mark_crop()

    def _next_mark_crop(self):
        if not self._pending_crop_labels:
            self._start_column_marking_learn()
            return
        label = self._pending_crop_labels[0]
        self._pending_crop_labels = self._pending_crop_labels[1:]
        self._mark_crop_mode  = True
        self._mark_crop_label = label
        col = PAGE_MARK_COLORS.get(label, CORNER_COLORS.get(label,"#ff0"))
        self._set_instruction(
            f"LEARN — Step 3/4: Draw a rectangle around the {label} mark.\n\n"
            f"Click and drag to select the mark symbol "
            f"(the printed shape that {label} uses for orientation).\n\n"
            "Zoom in first for accuracy.")
        self._log(f"  Waiting for crop rect: {label}")

    def _start_column_marking_learn(self):
        self._log("── Learn Step 4: Column structure ──")
        self._start_column_marking()

    # ── Column marking (shared learn/map) ─────────────────────────────────────

    def _start_column_marking(self):
        self.mode = "col_divs"
        prefix = "LEARN — Step 4/4: " if self.learn_mode else "Step 2/3: "
        n = simpledialog.askinteger(
            "Columns",
            "How many contest columns does this ballot have?",
            minvalue=1, maxvalue=5, initialvalue=2, parent=self.root)
        self.col_div_count = (n or 1)-1
        if self.col_div_count==0:
            self._log("Single-column ballot")
            self._start_contest_marking()
            return
        self._set_instruction(
            f"{prefix}Click {self.col_div_count} column divider(s).")
        self._log(f"── Step: Mark {self.col_div_count} column divider(s) ──")

    def _handle_col_div_click(self, ix, iy):
        self.layout.column_divs.append(ix)
        self.layout.column_divs.sort()
        self._render_image()
        placed=len(self.layout.column_divs)
        self._log(f"  Column divider at x={ix}")
        if placed>=self.col_div_count:
            self._start_contest_marking()
        else:
            self._set_instruction(
                f"Click column divider {placed+1} of {self.col_div_count}.")

    # ── Contest marking (shared) ──────────────────────────────────────────────

    def _start_contest_marking(self):
        self.mode="contests"
        corners=self.layout.corners
        left_x =min(corners.get("TL",(0,0))[0],corners.get("BL",(0,0))[0])
        right_x=max(corners.get("TR",(self.layout.img_w,0))[0],
                    corners.get("BR",(self.layout.img_w,0))[0])
        top_y  =min(corners.get("TL",(0,0))[1],corners.get("TR",(0,0))[1])
        bot_y  =max(corners.get("BL",(0,self.layout.img_h))[1],
                    corners.get("BR",(0,self.layout.img_h))[1])
        divs=[left_x]+sorted(self.layout.column_divs)+[right_x]
        self.columns    =[(divs[i],divs[i+1]) for i in range(len(divs)-1)]
        self.col_top    =top_y; self.col_bot=bot_y
        self._col_idx   =0; self._col_clicks=[]
        self._current_col_x=self.columns[0]
        prefix="LEARN — " if self.learn_mode else ""
        self._set_instruction(
            f"{prefix}Click the TOP edge of each contest (top→bottom, "
            "left column first).\nWhen done with a column click "
            "'Done with this column'.")
        self._log("── Contest boundaries ──")
        self._log(f"  Column 1 of {len(self.columns)}")

    def _handle_contest_click(self, ix, iy):
        col_x0,col_x1=self._current_col_x
        self._col_clicks.append(iy)
        self._log(f"  Contest top at y={iy}")
        if len(self._col_clicks)>=2:
            y0=self._col_clicks[-2]; y1=iy-2
            self._add_contest(col_x0,y0,col_x1,y1)
        self._update_contest_instruction()

    def _add_contest(self, x0, y0, x1, y1):
        title = ocr_region(self.cv_img, x0, y0, x1,
                           min(y1, y0+int(self.dpi*0.4)))
        title = title.split("\n")[0].strip() if title \
                else f"Contest {len(self.layout.contests)+1}"
        title = re.sub(r'\s+', ' ', title)
        contest=Contest(title=title,x0=x0,y0=y0,x1=x1,y1=y1)
        crp = self.profile.candidate_row if self.profile else None
        blobs=find_indicator_blobs(self.cv_img,x0,y0,x1,y1,
                                   self.dpi,crp)
        ind_bboxes=[]
        if blobs:
            for cx,cy,bw,bh in blobs:
                name_x0=cx+bw//2+int(self.dpi*0.05)
                name=ocr_region(self.cv_img,name_x0,cy-bh,x1,cy+bh)
                name=name.split("\n")[0].strip() if name \
                     else f"Candidate {len(contest.candidates)+1}"
                name=re.sub(r'\s+',' ',name)
                ind=Indicator(cx,cy,bw,bh)
                contest.candidates.append(Candidate(name=name,indicator=ind))
                ind_bboxes.append((cx-bw//2,cy-bh//2,cx+bw//2,cy+bh//2))
        self.layout.contests.append(contest)
        self._render_image()
        self._log(f"  '{title}' — {len(contest.candidates)} candidate(s)")
        # Learn: accumulate indicator data for candidate_row profile
        if self.learn_mode and HAS_PROFILE and ind_bboxes:
            if not hasattr(self,'_all_indicator_bboxes'):
                self._all_indicator_bboxes=[]
            self._all_indicator_bboxes.extend(ind_bboxes)

    def _finish_column(self):
        if self.mode!="contests":
            return
        if self._col_clicks:
            col_x0,col_x1=self._current_col_x
            y0=self._col_clicks[-1]; y1=self.col_bot
            self._add_contest(col_x0,y0,col_x1,y1)
            self._col_clicks=[]
        self._col_idx+=1
        if self._col_idx<len(self.columns):
            self._current_col_x=self.columns[self._col_idx]
            self._log(f"  Column {self._col_idx+1} of {len(self.columns)}")
        else:
            self._log("  All columns done.")
            self._set_instruction(
                f"All contests marked ({len(self.layout.contests)} total).\n\n"
                "Review with 'Edit contests / candidates' then 'Export YAML'."
                + ("\n\nIn LEARN mode: click 'Save design profile + generate code'."
                   if self.learn_mode else ""))

    def _update_contest_instruction(self):
        n=len(self.layout.contests)
        self._set_instruction(
            f"{n} contest(s) marked.\n\n"
            "Click top edge of next contest, OR\n"
            "click 'Done with this column' to close column.")

    # ── Profile save + code generation ───────────────────────────────────────

    def _save_profile_and_generate(self):
        if not HAS_PROFILE:
            messagebox.showerror("Missing module",
                                 "design_profile.py not found.")
            return

        # Close any open column
        if self.mode=="contests" and self._col_clicks:
            self._finish_column()

        # Get profile path
        profile_path = simpledialog.askstring(
            "Save profile",
            "Path for ballot_design.json:",
            initialvalue="ballot_design.json",
            parent=self.root)
        if not profile_path:
            return

        profile_path = Path(profile_path)

        # Load existing or create new
        if profile_path.exists() and self.profile:
            p = self.profile
        else:
            p = DesignProfile()
            p.format_name = self.format_name or simpledialog.askstring(
                "Format name",
                "Short name for this ballot format\n(e.g. 'Acme County 2026'):",
                initialvalue="Custom Ballot Format",
                parent=self.root) or "Custom Ballot Format"

        p.dpi_reference = self.dpi

        # Learn from this annotation
        img_name = Path(self.out_path).stem + "_source.png"
        if img_name not in p.training_images:
            p.training_images.append(img_name)

        # Contest separator profile from layout
        sep_type = messagebox.askyesno(
            "Contest separator",
            "Are contests separated by a printed horizontal LINE\n"
            "(yes), or by whitespace gaps only (no)?",
            parent=self.root)
        p.contest_separator.type = "horizontal_line" if sep_type else "whitespace"

        # Call learn_from_annotation with accumulated data
        div_xs = self.layout.column_divs
        contest_tops = []  # already processed
        p.learn_from_annotation(
            self.cv_img, self.dpi,
            corner_clicks  = self.layout.corners,
            col_divider_xs = div_xs,
            contest_tops   = contest_tops,
            mark_crops     = self._mark_crops)

        # Learn candidate row from collected blobs
        if hasattr(self,'_all_indicator_bboxes') and self._all_indicator_bboxes:
            p.learn_candidate_geometry(self._all_indicator_bboxes, self.dpi)

        # Save profile
        p.save(str(profile_path))
        self.profile = p

        # Generate Python detector
        py_path = str(profile_path).replace('.json', '_corner_detector.py')
        py_path = py_path if py_path != str(profile_path) \
                  else str(profile_path.parent / "corner_detector.py")
        generate_python_detector(p, py_path)

        # Generate Java service
        java_path = str(profile_path).replace(
            '.json', '_CornerDetectionService_custom.java')
        if java_path==str(profile_path):
            java_path=str(profile_path.parent/"CornerDetectionService_custom.java")
        generate_java_service(p, java_path)

        self._log(f"✓ Profile: {profile_path}")
        self._log(f"✓ Python:  {py_path}")
        self._log(f"✓ Java:    {java_path}")
        messagebox.showinfo(
            "Saved",
            f"Design profile saved and code generated:\n\n"
            f"Profile:  {profile_path}\n"
            f"Python:   {py_path}\n"
            f"Java:     {java_path}\n\n"
            f"To use on new ballots:\n"
            f"  python ballot_mapper.py new_ballot.png \\\n"
            f"    --profile {profile_path}")

    # ── Contest editor ────────────────────────────────────────────────────────

    def _open_editor(self):
        if self.mode=="contests" and self._col_clicks:
            if messagebox.askyesno("Finish column?",
                                   "Close out the current column first?"):
                self._finish_column()

        ed=tk.Toplevel(self.root); ed.title("Edit Contests and Candidates")
        ed.geometry("720x620")

        for row,(lbl,var_init,w) in enumerate([
            ("DPI:", str(self.dpi), 8),
            ("Barcode/ID:", self.layout.barcode_data, 40),
            ("Page number:", str(self.layout.page_number), 6)]):
            f=tk.Frame(ed); f.pack(fill=tk.X,padx=8,pady=2)
            tk.Label(f,text=lbl).pack(side=tk.LEFT)
            v=tk.StringVar(value=var_init)
            tk.Entry(f,textvariable=v,width=w).pack(side=tk.LEFT)
            if row==0:   dpi_var=v
            elif row==1: bc_var=v
            else:        pg_var=v

        tk.Label(ed,text="Contests:",anchor="w").pack(fill=tk.X,padx=8)
        lf=tk.Frame(ed); lf.pack(fill=tk.BOTH,expand=True,padx=8,pady=4)
        contest_lb=tk.Listbox(lf,height=7,exportselection=False)
        contest_lb.pack(side=tk.LEFT,fill=tk.BOTH,expand=True)
        tk.Scrollbar(lf,command=contest_lb.yview).pack(side=tk.RIGHT,fill=tk.Y)
        for c in self.layout.contests:
            contest_lb.insert(tk.END,c.title or "(untitled)")

        detail=tk.LabelFrame(ed,text="Selected contest")
        detail.pack(fill=tk.BOTH,expand=True,padx=8,pady=4)
        tk.Label(detail,text="Title:").grid(row=0,column=0,sticky="w",padx=4)
        title_var=tk.StringVar()
        tk.Entry(detail,textvariable=title_var,width=42).grid(
            row=0,column=1,sticky="ew",padx=4,pady=2)
        tk.Label(detail,text="Type:").grid(row=1,column=0,sticky="w",padx=4)
        type_var=tk.StringVar()
        ttk.Combobox(detail,textvariable=type_var,width=22,
                     values=["PLURALITY","RANKED_CHOICE","APPROVAL"]
                     ).grid(row=1,column=1,sticky="w",padx=4,pady=2)
        tk.Label(detail,text="Max votes:").grid(row=2,column=0,sticky="w",padx=4)
        max_var=tk.StringVar()
        tk.Entry(detail,textvariable=max_var,width=6).grid(
            row=2,column=1,sticky="w",padx=4,pady=2)
        tk.Label(detail,
                 text="Candidates (one per line; prefix '!' = write-in):"
                 ).grid(row=3,column=0,columnspan=2,sticky="w",padx=4,pady=(8,0))
        cand_text=tk.Text(detail,height=7,width=52)
        cand_text.grid(row=4,column=0,columnspan=2,sticky="nsew",padx=4,pady=2)
        detail.rowconfigure(4,weight=1); detail.columnconfigure(1,weight=1)

        current_idx=[None]

        def save_current():
            i=current_idx[0]
            if i is None: return
            c=self.layout.contests[i]
            c.title=title_var.get().strip()
            c.contest_type=type_var.get().strip() or "PLURALITY"
            try: c.max_votes=int(max_var.get())
            except ValueError: c.max_votes=1
            lines=cand_text.get("1.0",tk.END).strip().split("\n")
            new_cands=[]
            for j,line in enumerate(lines):
                line=line.strip()
                if not line: continue
                wi=line.startswith("!"); name=line.lstrip("!").strip()
                ind=c.candidates[j].indicator if j<len(c.candidates) else None
                new_cands.append(Candidate(name=name,write_in=wi,indicator=ind))
            c.candidates=new_cands
            contest_lb.delete(i); contest_lb.insert(i,c.title or "(untitled)")
            contest_lb.selection_set(i)

        def on_select(event):
            sel=contest_lb.curselection()
            if not sel: return
            save_current(); i=sel[0]; current_idx[0]=i
            c=self.layout.contests[i]
            title_var.set(c.title); type_var.set(c.contest_type)
            max_var.set(str(c.max_votes)); cand_text.delete("1.0",tk.END)
            for cand in c.candidates:
                cand_text.insert(tk.END,("!" if cand.write_in else "")+cand.name+"\n")

        contest_lb.bind("<<ListboxSelect>>",on_select)

        def on_ok():
            save_current()
            try: self.dpi=int(dpi_var.get()); self.layout.dpi=self.dpi
            except ValueError: pass
            self.layout.barcode_data=bc_var.get().strip()
            try: self.layout.page_number=int(pg_var.get())
            except ValueError: pass
            self.barcode_var.set(self.layout.barcode_data)
            self._render_image(); ed.destroy()

        tk.Button(ed,text="OK — save changes",command=on_ok,
                  bg="#1a6a6a",fg="white").pack(pady=6)
        if self.layout.contests:
            contest_lb.selection_set(0); on_select(None)

    # ── YAML export ───────────────────────────────────────────────────────────

    def _export_yaml(self):
        corners=self.layout.corners
        if len([k for k in corners if k in CORNER_ORDER])<4:
            messagebox.showerror("Missing corners",
                                 "Please mark all 4 content area corners first.")
            return
        dpi=self.layout.dpi
        tl=corners["TL"]; tr=corners["TR"]
        br=corners["BR"]; bl=corners["BL"]
        ca_left  =min(tl[0],bl[0])/dpi; ca_top  =min(tl[1],tr[1])/dpi
        ca_right =max(tr[0],br[0])/dpi; ca_bottom=max(bl[1],br[1])/dpi
        ca_w=ca_right-ca_left; ca_h=ca_bottom-ca_top

        def r4(v): return round(v,4)

        contests_yaml=[]
        for i,c in enumerate(self.layout.contests):
            cands_yaml=[]
            for j,cand in enumerate(c.candidates):
                if cand.indicator:
                    il,it,iw,ih=cand.indicator.to_bbox(dpi)
                    ind_yaml={"offsetFromLeft":r4(il),"offsetFromTop":r4(it),
                              "width":r4(iw),"height":r4(ih)}
                else:
                    ind_yaml={"offsetFromLeft":0.0,"offsetFromTop":0.0,
                              "width":0.2,"height":0.1}
                cands_yaml.append({"id":j+1,"name":cand.name,
                                   "writeIn":cand.write_in,"indicator":ind_yaml})
            contests_yaml.append({
                "id":i+1,"title":c.title,"contestType":c.contest_type,
                "maxVotes":c.max_votes,
                "boundingBox":{"offsetFromLeft":r4(c.x0/dpi),
                               "offsetFromTop":r4(c.y0/dpi),
                               "width":r4((c.x1-c.x0)/dpi),
                               "height":r4((c.y1-c.y0)/dpi)},
                "candidates":cands_yaml})

        corner_marks=[{"corner":k,"x":r4(v[0]/dpi),"y":r4(v[1]/dpi)}
                      for k,v in corners.items() if k in CORNER_ORDER]
        page_marks  =[{"corner":k,"x":r4(v[0]/dpi),"y":r4(v[1]/dpi)}
                      for k,v in corners.items() if k in PAGE_MARK_ORDER]

        combo_id=self.layout.barcode_data or "unknown"
        try:
            parts=self.layout.barcode_data.split("|")
            if len(parts)>=5: combo_id=int(parts[4])
        except (ValueError,IndexError): pass

        doc={"combinationId":combo_id,"unit":"inches","source":"ballot_mapper",
             "sides":[{
                 "side_number":self.layout.page_number,
                 "ballotContentArea":{"offsetFromLeft":r4(ca_left),
                                      "offsetFromTop":r4(ca_top),
                                      "width":r4(ca_w),"height":r4(ca_h)},
                 "cornerMarks":corner_marks,
                 "pageMarks":page_marks,
                 "contests":contests_yaml}]}

        with open(self.out_path,"w") as f:
            yaml.dump(doc,f,default_flow_style=False,
                      sort_keys=False,allow_unicode=True)
        self._log(f"✓ YAML: {self.out_path}")
        messagebox.showinfo("Exported",f"YAML written to:\n{self.out_path}")


# ══════════════════════════════════════════════════════════════════════════════
# Entry point
# ══════════════════════════════════════════════════════════════════════════════

def main():
    ap=argparse.ArgumentParser(
        description="Map a ballot image to bCounter YAML (with design learning)")
    ap.add_argument("image",       help="Ballot image file (PNG/JPG/TIFF)")
    ap.add_argument("--dpi",       type=int, default=300)
    ap.add_argument("--out",       default=None, help="Output YAML path")
    ap.add_argument("--profile",   default=None,
                    help="ballot_design.json — load/save design profile")
    ap.add_argument("--learn",     action="store_true",
                    help="Learn ballot design from this image")
    ap.add_argument("--name",      default="",
                    help="Format name (for --learn mode)")
    args=ap.parse_args()

    img_path=Path(args.image)
    if not img_path.exists():
        print(f"✗ Image not found: {img_path}"); sys.exit(1)

    out_path=Path(args.out) if args.out else img_path.with_suffix(".yaml")

    # Detect DPI from metadata
    dpi=args.dpi
    try:
        with Image.open(img_path) as im:
            info_dpi=im.info.get("dpi")
            if info_dpi:
                d=int(info_dpi[0])
                if d>=72: dpi=d; print(f"  DPI from metadata: {dpi}")
    except Exception: pass

    # Load design profile
    profile=None
    if args.profile and HAS_PROFILE:
        p=Path(args.profile)
        if p.exists():
            profile=DesignProfile.load(str(p))
        elif not args.learn:
            print(f"⚠  Profile not found: {p}")

    print(f"Loading {img_path}  ({dpi} DPI)")
    print(f"Output → {out_path}")
    pil_img, cv_img=load_image(str(img_path))
    print(f"Image: {pil_img.width}×{pil_img.height} px")

    root=tk.Tk()
    BallotMapperApp(root, pil_img, cv_img, dpi, str(out_path),
                    profile=profile,
                    learn_mode=args.learn,
                    format_name=args.name)
    root.mainloop()

if __name__=="__main__":
    main()
