#!/usr/bin/env python3
"""
ballot_mapper_v3.py — Interactive ballot layout mapper with pattern matching.

Workflow:
  1. Click orientation markers
  2. Drag column boundaries (snap to lines/gaps)
  3. Drag contest boundaries (OCR title)
  4. For each contest:
     a. Drag over one indicator -> extract template pattern
     b. Connect dashes/dots within 0.1" (30px at 300dpi)
     c. Show bounding box of connected region
     d. Search rest of contest for matching patterns
     e. OCR candidate names beside each found indicator
     f. Present all candidates for group editing
     g. For ranked-choice: repeat for each rank column

Copyright (C) 2026 Mitch Trachtenberg -- GPL v3
"""

import argparse, math, sys, re, copy
from pathlib import Path

try:
    import tkinter as tk
    from tkinter import ttk, messagebox, simpledialog
except ImportError:
    print("tkinter not available"); sys.exit(1)

try:
    from PIL import Image, ImageTk, ImageDraw
    PIL_AVAILABLE = True
except ImportError:
    print("Install pillow"); sys.exit(1)

try:
    import cv2
    import numpy as np
    CV2_AVAILABLE = True
except ImportError:
    CV2_AVAILABLE = False
    print("WARNING: opencv not available")

try:
    import pytesseract
    OCR_AVAILABLE = True
except ImportError:
    OCR_AVAILABLE = False


# v13: Standard button styles — all buttons use these for readability on macOS.
# relief='raised' ensures text is visible regardless of system theme.
_BTN_PRIMARY  = dict(relief='raised', font=('Helvetica', 11, 'bold'),
                     padx=10, pady=4, cursor='hand2')
_BTN_SECONDARY= dict(relief='raised', font=('Helvetica', 10),
                     padx=8,  pady=3, cursor='hand2')
_BTN_TOOLBAR  = dict(relief='raised', font=('Helvetica', 10),
                     padx=8,  pady=3, cursor='hand2')

def label_button(parent, text, command, bg='#223344', fg='white',
                 font=('Helvetica', 11), padx=10, pady=4, **kw):
    """
    v16: Use tk.Label styled as a button.
    tk.Button bg/fg is unreliable on macOS regardless of relief.
    tk.Label always respects bg/fg.
    """
    lbl = tk.Label(parent, text=text, bg=bg, fg=fg,
                   font=font, padx=padx, pady=pady,
                   cursor='hand2', relief='raised', **kw)
    lbl.bind('<Button-1>', lambda e: command())
    lbl.bind('<Enter>',    lambda e: lbl.config(relief='sunken'))
    lbl.bind('<Leave>',    lambda e: lbl.config(relief='raised'))
    lbl.bind('<ButtonRelease-1>', lambda e: lbl.config(relief='raised'))
    return lbl


VERSION = '22.0.0'

COL_MARKER    = '#ff2222'
COL_COLUMN    = '#2266ff'
COL_CONTEST   = '#ff8800'
COL_INDICATOR = '#00cc44'
COL_TEMPLATE  = '#ffff00'   # template match highlight
COL_PENDING   = '#ff00ff'
COL_SNAP      = '#00ffff'

PHASES = [
    ('layout',     'LAYOUT MARKERS',
     'Drag over any layout identification zones: barcodes, QR codes,\n'
     'timing marks, or other vendor-specific identifiers.\n'
     'Label each zone when prompted. Press Enter when done (or if none).'),
    ('markers',    'ORIENTATION MARKERS',
     'Click each corner/timing mark. Right-click to undo. Enter when done.'),
    ('columns',    'COLUMN BOUNDARIES',
     'Drag each column from upper-left to lower-right. Enter when done.'),
    ('contests',   'CONTEST BOUNDARIES',
     'Drag each contest box. Title pre-filled from OCR. Enter when done.'),
    ('indicators', 'INDICATOR CAPTURE',
     'Drag over ONE indicator in the current contest.\n'
     'The tool will find all others automatically.\n'
     'C = next contest  |  c = previous contest  |  Enter = done'),
    ('review',     'CANDIDATE REVIEW',
     'Review and edit the auto-detected candidates. Press Enter to confirm.'),
    ('done',       'COMPLETE', 'Press Q to save and quit.'),
]


# ── Image analysis helpers ─────────────────────────────────────────────────────

def find_blob_centre(gray, cx, cy, search_r, threshold=192):
    h, w = gray.shape
    x0=max(0,int(cx-search_r)); y0=max(0,int(cy-search_r))
    x1=min(w,int(cx+search_r)); y1=min(h,int(cy+search_r))
    zone = gray[y0:y1, x0:x1]
    ret, binary = cv2.threshold(zone, threshold, 255, cv2.THRESH_BINARY_INV)
    if binary is None or ret is None:
        return cx, cy, None
    ys, xs = np.where(binary > 0)
    if not len(xs):
        return cx, cy, None
    dists = np.sqrt((xs-(cx-x0))**2 + (ys-(cy-y0))**2)
    seed = (int(xs[np.argmin(dists)]), int(ys[np.argmin(dists)]))
    mask = np.zeros((binary.shape[0]+2, binary.shape[1]+2), np.uint8)
    cv2.floodFill(binary, mask, seed, 128, 0, 30,
                  cv2.FLOODFILL_FIXED_RANGE)
    bys, bxs = np.where(mask[1:-1,1:-1] > 0)
    if not len(bxs):
        bxs, bys = xs, ys
    bx0=int(bxs.min())+x0; bx1=int(bxs.max())+x0
    by0=int(bys.min())+y0; by1=int(bys.max())+y0
    return (bx0+bx1)/2, (by0+by1)/2, (bx0,by0,bx1,by1)


def snap_vertical_boundary(gray, x_px, y0_px, y1_px, search_px, threshold=192):
    h, w = gray.shape
    y0=max(0,y0_px); y1=min(h,y1_px)
    x_lo=max(0,x_px-search_px); x_hi=min(w,x_px+search_px)
    strip = gray[y0:y1, x_lo:x_hi]
    col_h = y1-y0
    if col_h < 10:
        return x_px, 'none', x_px, x_px
    dark_frac = np.sum(strip < threshold, axis=0) / col_h
    line_cols = np.where(dark_frac > 0.50)[0]
    if len(line_cols):
        target = x_px-x_lo
        closest = line_cols[np.argmin(np.abs(line_cols-target))]
        lx0=closest
        while lx0>0 and dark_frac[lx0-1]>0.50: lx0-=1
        lx1=closest
        while lx1<len(dark_frac)-1 and dark_frac[lx1+1]>0.50: lx1+=1
        return x_lo+closest, 'line', x_lo+lx0, x_lo+lx1
    blank = dark_frac < 0.05
    best=None; best_dist=search_px; in_run=False; rs=0
    for i,b in enumerate(blank):
        if b and not in_run: in_run=True; rs=i
        elif not b and in_run:
            rl=i-rs; rc=(rs+i)//2
            dist=abs((x_lo+rc)-x_px)
            if rl>3 and dist<best_dist:
                best_dist=dist; best=(rs,i,x_lo+rc)
            in_run=False
    if in_run:
        rl=len(blank)-rs; rc=(rs+len(blank))//2
        if rl>3 and abs((x_lo+rc)-x_px)<best_dist:
            best=(rs,len(blank),x_lo+rc)
    if best:
        rs,re,cx_= best
        return cx_,'gap',x_lo+rs,x_lo+re
    return x_px,'none',x_px,x_px


def ocr_region(gray, x0, y0, x1, y1, dpi):
    if not OCR_AVAILABLE: return ''
    h,w=gray.shape
    x0=max(0,x0); y0=max(0,y0); x1=min(w,x1); y1=min(h,y1)
    if x1<=x0 or y1<=y0: return ''
    region = gray[y0:y1,x0:x1]
    scale = min(1.0, 300.0/dpi)
    if scale<0.95:
        region=cv2.resize(region,None,fx=scale,fy=scale,
                          interpolation=cv2.INTER_AREA)
    try:
        text=pytesseract.image_to_string(region,
                                          config='--psm 6 --oem 3').strip()
        return ' '.join(text.split())
    except Exception:
        return ''


def ocr_first_line(gray, x0, y0, x1, y1, dpi):
    text = ocr_region(gray, x0, y0, x1, y1, dpi)
    line = text.split('\n')[0].strip().rstrip(':').strip() if text else ''
    return line


def ocr_contest_title_multiline(gray, contest_ul, contest_lr, dpi):
    """
    OCR the top of a contest box, collecting lines that appear to use
    the same (large) font as the first line.  Stops when a significantly
    smaller font size is detected (preamble / instructions text).

    Uses image_to_data to get per-word bounding box heights so we can
    measure font size per line.
    """
    if not OCR_AVAILABLE:
        return ''
    x0, y0 = int(contest_ul[0]), int(contest_ul[1])
    x1, y1 = int(contest_lr[0]), int(contest_lr[1])
    h_img, w_img = gray.shape
    # Search up to 1.0" from top of contest box
    search_h = min(int(1.0 * dpi), y1 - y0)
    rx0 = max(0, x0+2); ry0 = max(0, y0+2)
    rx1 = min(w_img, x1-2); ry1 = min(h_img, y0 + search_h)
    if rx1 <= rx0 or ry1 <= ry0:
        return ''
    region = gray[ry0:ry1, rx0:rx1]
    scale = min(1.0, 300.0 / dpi)
    if scale < 0.95:
        region = cv2.resize(region, None, fx=scale, fy=scale,
                            interpolation=cv2.INTER_AREA)
    try:
        data = pytesseract.image_to_data(
            region, config='--psm 6 --oem 3',
            output_type=pytesseract.Output.DICT)
    except Exception:
        return ocr_first_line(gray, rx0, ry0, rx1, ry1, dpi)

    # Group words into lines by their top-Y coordinate (within 4px tolerance)
    lines = []   # list of {'y': int, 'h': int, 'words': [str]}
    for i, word in enumerate(data['text']):
        word = word.strip()
        if not word or float(data['conf'][i]) < 20:
            continue
        wy = int(data['top'][i] / scale)
        wh = int(data['height'][i] / scale)
        wx = int(data['left'][i] / scale)
        # Find matching line
        placed = False
        for ln in lines:
            if abs(wy - ln['y']) <= max(4, int(0.05*dpi)):
                ln['words'].append(word)
                ln['h'] = max(ln['h'], wh)
                placed = True
                break
        if not placed:
            lines.append({'y': wy, 'h': wh, 'words': [word]})

    if not lines:
        return ''

    # Sort lines by Y
    lines.sort(key=lambda ln: ln['y'])

    # Title font size = height of first non-tiny line
    title_h = lines[0]['h']
    # Threshold: a line is "smaller font" if its height < 70% of title height
    size_threshold = title_h * 0.70

    title_lines = []
    for ln in lines:
        if ln['h'] < size_threshold and title_lines:
            # Smaller font detected — stop collecting title
            break
        title_lines.append(' '.join(ln['words']))

    return ' '.join(title_lines).strip()


# ── Indicator pattern extraction and matching ──────────────────────────────────

def extract_indicator_pattern(gray, ul, lr, connect_dist_px=30, threshold=192):
    """
    Extract the connected dark pixel pattern from a dragged indicator zone.
    Connects dots/dashes within connect_dist_px (0.1" at 300dpi).

    Returns:
      pattern_mask  -- binary mask (H x W) of connected indicator pixels
      bounding_box  -- (x0,y0,x1,y1) tight bounding box in IMAGE coords
      template_img  -- grayscale crop of the indicator zone
      centre        -- (cx, cy) in image coords
      size          -- (w, h) in pixels
    """
    x0,y0=int(ul[0]),int(ul[1])
    x1,y1=int(lr[0]),int(lr[1])
    h_img,w_img=gray.shape
    x0=max(0,x0); y0=max(0,y0); x1=min(w_img,x1); y1=min(h_img,y1)
    if x1<=x0 or y1<=y0:
        return None,None,None,(x0+x1)/2,(y0+y1)/2,(x1-x0,y1-y0)

    zone = gray[y0:y1, x0:x1]
    _, binary = cv2.threshold(zone, threshold, 255, cv2.THRESH_BINARY_INV)

    # Morphological close to bridge gaps up to connect_dist_px
    # kernel diameter = 2*radius+1 to bridge gaps up to connect_dist_px
    k_sz = 2 * connect_dist_px + 1
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k_sz, k_sz))
    closed = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, k)

    # Find largest connected component
    n_labels, labels, stats, _ = cv2.connectedComponentsWithStats(closed)
    if n_labels <= 1:
        # Fall back: use all dark pixels
        bys,bxs=np.where(binary>0)
        if not len(bxs):
            return None,None,zone,(x0+x1)/2,(y0+y1)/2,(x1-x0,y1-y0)
        bx0=int(bxs.min()); bx1=int(bxs.max())
        by0=int(bys.min()); by1=int(bys.max())
        return binary,(x0+bx0,y0+by0,x0+bx1,y0+by1),zone,\
               x0+(bx0+bx1)/2,y0+(by0+by1)/2,(bx1-bx0,by1-by0)

    # Pick largest non-background component
    best_label = 1+np.argmax(stats[1:,cv2.CC_STAT_AREA])
    component_mask = (labels == best_label).astype(np.uint8)*255

    # Tight bounding box from original binary (not the closed version)
    # intersection of component_mask with binary gives actual pixels
    actual = cv2.bitwise_and(binary, component_mask)
    bys,bxs=np.where(actual>0)
    if not len(bxs):
        bys,bxs=np.where(component_mask>0)
    if not len(bxs):
        return None,None,zone,(x0+x1)/2,(y0+y1)/2,(x1-x0,y1-y0)

    bx0=int(bxs.min()); bx1=int(bxs.max())
    by0=int(bys.min()); by1=int(bys.max())

    cx = x0 + (bx0+bx1)/2
    cy = y0 + (by0+by1)/2
    w = bx1-bx0; h = by1-by0

    # Return closed mask for matching, tight bbox in image coords
    return (closed,
            (x0+bx0, y0+by0, x0+bx1, y0+by1),
            zone, cx, cy, (w, h))



def classify_indicator_style(gray, bbox, connect_dist_px=30, threshold=192):
    """
    Classify the indicator style from its bounding box region:
      'oval'          -- solid or mostly-solid elliptical outline
      'dashed_oval'   -- elliptical outline with visible dash gaps
      'rectangle'     -- solid rectangular outline
      'dashed_rectangle' -- rectangular outline with dash gaps
      'connect_dots'  -- two dark blobs separated by a blank region

    Strategy:
    1. Extract the bounding box region
    2. Threshold to binary
    3. Check for two-blob (connect-dots) pattern
    4. Check aspect ratio for oval vs rectangle
    5. Check gap fraction (ratio of dark pixels to perimeter pixels) for dashed vs solid
    """
    x0, y0, x1, y1 = [int(v) for v in bbox]
    h_img, w_img = gray.shape
    x0=max(0,x0); y0=max(0,y0); x1=min(w_img,x1); y1=min(h_img,y1)
    if x1<=x0 or y1<=y0:
        return 'oval'

    zone = gray[y0:y1, x0:x1]
    _, binary = cv2.threshold(zone, threshold, 255, cv2.THRESH_BINARY_INV)
    bh, bw = binary.shape

    total_dark = int(np.sum(binary > 0))
    total_px   = bh * bw
    if total_dark == 0:
        return 'oval'

    # ── 1. Connect-dots detection ─────────────────────────────────────────
    # Find connected components WITHOUT closing (to preserve gaps)
    n_labels, labels, stats, centroids = cv2.connectedComponentsWithStats(binary)
    if n_labels > 2:   # background + at least 2 blobs
        # Check if two largest blobs are at opposite ends horizontally
        blob_stats = sorted(
            [(stats[i,cv2.CC_STAT_AREA],
              centroids[i][0], centroids[i][1])
             for i in range(1, n_labels)],
            reverse=True)
        if len(blob_stats) >= 2:
            area1, cx1_, _ = blob_stats[0]
            area2, cx2_, _ = blob_stats[1]
            # Two similar-size blobs with large horizontal gap
            gap_frac = abs(cx1_ - cx2_) / bw if bw > 0 else 0
            size_ratio = min(area1,area2)/max(area1,area2) if max(area1,area2)>0 else 0
            # Middle region should be mostly blank
            mid_x0 = int(min(cx1_,cx2_)) + int(bw*0.05)
            mid_x1 = int(max(cx1_,cx2_)) - int(bw*0.05)
            if mid_x0 < mid_x1:
                mid_dark = np.sum(binary[:, mid_x0:mid_x1] > 0)
                mid_total = bh * (mid_x1 - mid_x0)
                mid_blank = (mid_dark / mid_total < 0.10) if mid_total > 0 else False
            else:
                mid_blank = False
            if gap_frac > 0.35 and size_ratio > 0.25 and mid_blank:
                return 'connect_dots'

    # ── 2. Aspect ratio: oval vs rectangle ────────────────────────────────
    aspect = bw / bh if bh > 0 else 1.0
    # Ovals are typically 2:1 to 4:1 wide
    # Rectangles can be any aspect but have straighter edges

    # Check straightness of edges using horizontal/vertical projections
    # For a rectangle: dark pixels cluster at edges (top, bottom, left, right rows)
    top_dark    = np.sum(binary[0, :] > 0) / bw if bw > 0 else 0
    bot_dark    = np.sum(binary[-1,:] > 0) / bw if bw > 0 else 0
    left_dark   = np.sum(binary[:, 0] > 0) / bh if bh > 0 else 0
    right_dark  = np.sum(binary[:,-1] > 0) / bh if bh > 0 else 0
    edge_score  = (top_dark + bot_dark + left_dark + right_dark) / 4

    # High edge_score means strong edges = rectangle-like
    is_rect = edge_score > 0.45

    # ── 3. Dashed vs solid ────────────────────────────────────────────────
    # Apply close to connect dashes, compare dark pixel count before/after
    k_sz = 2 * connect_dist_px + 1
    k    = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k_sz, k_sz))
    closed = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, k)
    dark_after  = int(np.sum(closed > 0))
    # If closing added significantly more dark area, the original was dashed
    # (ratio of new dark to original dark)
    fill_ratio = dark_after / total_dark if total_dark > 0 else 1.0
    is_dashed  = fill_ratio > 2.5   # closing more than doubled dark area = gappy

    if is_rect:
        return 'dashed_rectangle' if is_dashed else 'rectangle'
    else:
        return 'dashed_oval' if is_dashed else 'oval'


def measure_intra_gap(gray, bbox, threshold=192):
    """v17: Largest vertical gap between dark rows inside the template bbox."""
    x0,y0,x1,y1=[int(v) for v in bbox]
    h,w=gray.shape
    x0=max(0,x0);y0=max(0,y0);x1=min(w,x1);y1=min(h,y1)
    if x1<=x0 or y1<=y0: return 8
    zone=gray[y0:y1,x0:x1]
    dark_per_row=np.sum(zone<threshold,axis=1)
    max_gap=1; gap=0
    for d in dark_per_row:
        if d==0: gap+=1; max_gap=max(max_gap,gap)
        else: gap=0
    return max(2,max_gap)


def find_text_line_ys(gray, txt_x0, txt_x1, cy0, cy1, th, threshold=192,
                       start_y=None):
    """
    v18: Y centres of candidate text lines from horizontal dark-pixel projection.

    Improvements over v17:
    - start_y: only collect lines at or below this Y (the dragged indicator Y)
      so we never go above the first indicator
    - Stop collecting when a gap between consecutive lines exceeds 2.5x the
      median spacing — this detects section breaks (e.g. the footer disclaimer
      line separated from the candidate list by extra whitespace)
    - min_dark threshold raised to width//15 so faint lines don't count
    """
    txt_x0=max(0,txt_x0); txt_x1=min(gray.shape[1],txt_x1)
    cy0=max(0,cy0); cy1=min(gray.shape[0],cy1)
    if txt_x1<=txt_x0 or cy1<=cy0: return []
    strip=gray[cy0:cy1,txt_x0:txt_x1]
    dark_per_row=np.sum(strip<threshold,axis=1)
    min_dark=max(3,(txt_x1-txt_x0)//15)   # stricter than v17's //20
    line_ys=[]; in_band=False; band_rows=[]
    for i,d in enumerate(dark_per_row):
        if d>=min_dark:
            if not in_band: in_band=True; band_rows=[]
            band_rows.append(i)
        else:
            if in_band and len(band_rows)>=2:
                bh=len(band_rows)
                if th*0.35<=bh<=th*2.2:
                    abs_y=cy0+band_rows[len(band_rows)//2]
                    # Skip lines above the first dragged indicator
                    if start_y is None or abs_y>=start_y-th:
                        line_ys.append(abs_y)
            in_band=False; band_rows=[]
    if in_band and len(band_rows)>=2:
        bh=len(band_rows)
        if th*0.35<=bh<=th*2.2:
            abs_y=cy0+band_rows[len(band_rows)//2]
            if start_y is None or abs_y>=start_y-th:
                line_ys.append(abs_y)
    line_ys.sort()
    # Deduplicate within th*0.5
    deduped=[]
    for y in line_ys:
        if not deduped or y-deduped[-1]>th*0.5: deduped.append(y)
    # Section-break detection: stop when gap > 2.5x median spacing
    if len(deduped)>=3:
        spacings=sorted([deduped[i+1]-deduped[i]
                         for i in range(len(deduped)-1)])
        median_sp=spacings[len(spacings)//2]
        cutoff=median_sp*2.5
        trimmed=[deduped[0]]
        for y in deduped[1:]:
            if y-trimmed[-1]>cutoff:
                break   # section break — stop here
            trimmed.append(y)
        deduped=trimmed
    return deduped



def compute_dark_signature(gray, bbox, threshold=192):
    """
    v18: Compute 1D vertical dark-pixel-count signature for the template.
    Returns normalised array of dark pixel counts per row (length = bbox height).
    """
    x0,y0,x1,y1=[int(v) for v in bbox]
    h,w=gray.shape
    x0=max(0,x0);y0=max(0,y0);x1=min(w,x1);y1=min(h,y1)
    if x1<=x0 or y1<=y0: return np.array([1.0])
    zone=gray[y0:y1,x0:x1]
    sig=np.sum(zone<threshold,axis=1).astype(float)
    mx=sig.max()
    return sig/mx if mx>0 else sig+1.0


def pattern_correlation(gray, cx, cy, tw, th, template_sig, threshold=192):
    """
    v18: Correlate the template's dark-pixel signature against the region
    at (cx, cy) with the same width/height.
    Returns correlation coefficient [-1..1]; higher = more similar pattern.
    """
    h_img,w_img=gray.shape
    x0=max(0,int(cx)-tw//2); x1=min(w_img,int(cx)+tw//2)
    y0=max(0,int(cy)-th//2); y1=min(h_img,int(cy)+th//2)
    if x1<=x0 or y1<=y0: return 0.0
    zone=gray[y0:y1,x0:x1]
    cand_sig=np.sum(zone<threshold,axis=1).astype(float)
    # Resize to match template length if needed
    tlen=len(template_sig)
    if len(cand_sig)!=tlen:
        if len(cand_sig)==0: return 0.0
        indices=np.round(np.linspace(0,len(cand_sig)-1,tlen)).astype(int)
        cand_sig=cand_sig[indices]
    mx=cand_sig.max()
    if mx>0: cand_sig=cand_sig/mx
    # Pearson correlation
    if template_sig.std()<1e-6 or cand_sig.std()<1e-6: return 0.0
    corr=float(np.corrcoef(template_sig,cand_sig)[0,1])
    return max(-1.0,min(1.0,corr))


def score_kernel(kernel_sz, bin_strip, text_line_ys,
                  template_bbox, search_y0, sx0, tw, th, tcx_search,
                  gray=None, template_sig=None, threshold=192):
    """
    v18: Score morphological close kernel.
    Improvements:
    - Tighter size filter: 40%-200% (was 35%-300%)
    - Pattern correlation against template signature (if gray provided)
    - Pattern correlation score weighted into final score
    - Only accept candidates whose Y is within th of a known text line
    """
    if kernel_sz<1: kernel_sz=1
    k=cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(kernel_sz,kernel_sz))
    closed=cv2.morphologyEx(bin_strip,cv2.MORPH_CLOSE,k)
    n_labels,labels,stats,centroids=cv2.connectedComponentsWithStats(closed)
    LOCK_PX=5
    min_w=max(3,int(tw*0.40)); max_w=int(tw*2.0)   # tighter than v17
    min_h=max(3,int(th*0.40)); max_h=int(th*2.0)   # tighter than v17
    t_asp=tw/th if th>0 else 1.0
    last_y=-999; min_gap=int(th*0.45)
    candidates=[]
    for lbl in sorted(range(1,n_labels),key=lambda l:centroids[l][1]):
        lw=stats[lbl,cv2.CC_STAT_WIDTH]; lh=stats[lbl,cv2.CC_STAT_HEIGHT]
        lcx=sx0+centroids[lbl][0]; lcy=search_y0+centroids[lbl][1]
        if not (min_w<=lw<=max_w and min_h<=lh<=max_h): continue
        l_asp=lw/lh if lh>0 else 1.0
        if abs(l_asp-t_asp)>max(1.2,t_asp*1.0): continue
        if lcy-last_y<min_gap: continue
        if abs(lcx-tcx_search)>LOCK_PX+tw//2: continue
        # v18: must be near a known text line (within th)
        if text_line_ys:
            nearest=min(abs(lcy-ty) for ty in text_line_ys)
            if nearest>th*1.2: continue
        comp_mask=(labels==lbl).astype(np.uint8)*255
        actual=cv2.bitwise_and(bin_strip,comp_mask)
        bys,bxs=np.where(actual>0)
        if not len(bxs): bys,bxs=np.where(comp_mask>0)
        if not len(bxs): continue
        ix0=sx0+int(bxs.min()); ix1=sx0+int(bxs.max())
        iy0=search_y0+int(bys.min()); iy1=search_y0+int(bys.max())
        hw=tw//2; hh=th//2
        fx0=min(ix0,int(lcx)-hw); fx1=max(ix1,int(lcx)+hw)
        fy0=min(iy0,int(lcy)-hh); fy1=max(iy1,int(lcy)+hh)
        candidates.append((lcx,lcy,(fx0,fy0,fx1,fy1)))
        last_y=lcy
    if not text_line_ys or not candidates:
        return 0.0, candidates
    n_exp=len(text_line_ys); n_found=len(candidates)
    count_score=1.0-abs(n_found-n_exp)/(n_exp+1)
    align_scores=[]
    for _,cy,_ in candidates:
        dists=[abs(cy-ty) for ty in text_line_ys]
        align_scores.append(max(0.0,1.0-min(dists)/(th*0.8)))
    align_score=sum(align_scores)/len(align_scores) if align_scores else 0
    # v18: pattern correlation
    corr_score=0.5   # neutral default
    if gray is not None and template_sig is not None and candidates:
        corrs=[pattern_correlation(gray,lcx,lcy,tw,th,template_sig,threshold)
               for lcx,lcy,_ in candidates]
        corr_score=max(0.0,(sum(corrs)/len(corrs)+1.0)/2.0)  # map [-1,1]->[0,1]
    return max(0.0,
               count_score*0.35 + align_score*0.40 + corr_score*0.25
               ), candidates


def snap_bbox_to_edges(gray, bbox, threshold=192, margin=10):
    """
    v20: Confirm a user-dragged indicator bbox by snapping each edge
    inward/outward to the nearest row/column of dark pixels.

    For each edge (top, bottom, left, right), search within ±margin pixels
    for the outermost row/column that has at least 2 dark pixels.
    Returns adjusted (x0,y0,x1,y1) bbox.
    """
    x0,y0,x1,y1 = [int(v) for v in bbox]
    h,w = gray.shape
    x0=max(0,x0); y0=max(0,y0); x1=min(w,x1); y1=min(h,y1)
    if x1<=x0 or y1<=y0: return bbox

    # Top edge: search from y0-margin to y0+margin for first dark row
    top = y0
    for r in range(max(0,y0-margin), min(h,y0+margin)):
        if np.sum(gray[r, x0:x1] < threshold) >= 2:
            top = r; break

    # Bottom edge: search from y1+margin down to y1-margin
    bot = y1
    for r in range(min(h,y1+margin)-1, max(0,y1-margin)-1, -1):
        if np.sum(gray[r, x0:x1] < threshold) >= 2:
            bot = r; break

    # Left edge: search from x0-margin to x0+margin
    left = x0
    for col in range(max(0,x0-margin), min(w,x0+margin)):
        if np.sum(gray[y0:y1, col] < threshold) >= 2:
            left = col; break

    # Right edge: search from x1+margin down to x1-margin
    right = x1
    for col in range(min(w,x1+margin)-1, max(0,x1-margin)-1, -1):
        if np.sum(gray[y0:y1, col] < threshold) >= 2:
            right = col; break

    # Sanity: edges must not invert
    if right > left and bot > top:
        return (left, top, right, bot)
    return bbox


def find_matching_indicators(gray, template_bbox, contest_ul, contest_lr,
                              dpi, connect_dist_px=30, threshold=192,
                              rank_x=None, text_side='right',
                              col_ul=None, col_lr=None):
    """
    v20: Confirm dragged bbox via edge-snapping, then use text-line
    guidance to find all other indicators.

    Step 1: Snap the dragged bbox to actual dark pixels at each edge.
    Step 2: Find text line beside the snapped template; measure height.
    Step 3: Scan downward for more text lines of similar height (>=90%).
            Skip bands that are too short or too tall; do NOT stop on them.
    Step 4: Place indicator at same Y offset below template for each
            text line found.
    """
    # Step 1: snap to edges
    snapped = snap_bbox_to_edges(gray, template_bbox, threshold)
    tx0,ty0,tx1,ty1 = [int(v) for v in snapped]
    tw=tx1-tx0; th=ty1-ty0
    if tw<=0 or th<=0: return []
    tcx=(tx0+tx1)//2
    tcx_search=int(rank_x) if rank_x is not None else tcx

    cx0=int(contest_ul[0]); cy0=int(contest_ul[1])
    cx1=int(contest_lr[0]); cy1=int(contest_lr[1])
    h_img,w_img=gray.shape
    cx0=max(0,cx0); cy0=max(0,cy0)
    cx1=min(w_img,cx1); cy1=min(h_img,cy1)

    # Text zone
    if text_side=='right':
        txt_x0=min(w_img, tx1+int(0.02*dpi))
        txt_x1=min(w_img, int(col_lr[0]) if col_lr else cx1)
    else:
        txt_x0=max(0, int(col_ul[0]) if col_ul else cx0)
        txt_x1=max(0, tx0-int(0.02*dpi))

    ind_tcy=(ty0+ty1)//2
    hw=tw//2; hh=th//2

    if txt_x1<=txt_x0:
        return [(float(tcx_search), float(ind_tcy), snapped)]

    # Step 2: find first text line beside template
    scan_y0=max(cy0, ty0-th)
    scan_y1=min(cy1, ty1+th)
    txt_strip=gray[scan_y0:scan_y1, txt_x0:txt_x1]
    dark_rows=np.sum(txt_strip<threshold, axis=1)
    min_dark=max(3, (txt_x1-txt_x0)//15)

    first_band=[]; in_b=False
    for i,d in enumerate(dark_rows):
        if d>=min_dark:
            if not in_b: in_b=True; first_band=[]
            first_band.append(i)
        else:
            if in_b and len(first_band)>=2:
                break
            in_b=False; first_band=[]

    if len(first_band)<2:
        return [(float(tcx_search), float(ind_tcy), snapped)]

    ref_h       = len(first_band)
    first_txt_cy= scan_y0 + first_band[len(first_band)//2]
    min_h       = int(ref_h * 0.90)
    max_h       = int(ref_h * 1.50)   # ignore much-taller bands (headers etc)
    text_cys    = [first_txt_cy]
    search_from = scan_y0 + first_band[-1] + 1

    # Step 3: scan downward — skip bad bands, do NOT stop on them
    consecutive_skips = 0
    MAX_SKIPS = 4   # stop after 4 consecutive non-qualifying bands

    while search_from < cy1 and consecutive_skips < MAX_SKIPS:
        chunk = gray[search_from:cy1, txt_x0:txt_x1]
        dark_c= np.sum(chunk<threshold, axis=1)
        band=[]; in_b=False; acted=False
        for i,d in enumerate(dark_c):
            if d>=min_dark:
                if not in_b: in_b=True; band=[]
                band.append(i)
            else:
                if in_b and len(band)>=1:
                    bh=len(band)
                    abs_cy=search_from+band[len(band)//2]
                    if min_h<=bh<=max_h:
                        text_cys.append(abs_cy)
                        consecutive_skips=0
                    else:
                        consecutive_skips+=1
                    search_from=search_from+band[-1]+1
                    acted=True
                    band=[]; in_b=False
                    break
                in_b=False
        if in_b and len(band)>=1:
            bh=len(band)
            abs_cy=search_from+band[len(band)//2]
            if min_h<=bh<=max_h:
                text_cys.append(abs_cy)
            break
        if not acted:
            break

    # Step 4: place indicators at same offsets
    matches=[]
    for txt_cy in text_cys:
        offset = txt_cy - first_txt_cy
        ind_cy = ind_tcy + offset
        ind_cy = max(cy0+hh, min(cy1-hh, ind_cy))
        b = (tcx_search-hw, ind_cy-hh, tcx_search+hw, ind_cy+hh)
        # Snap each candidate bbox to its edges too
        b = snap_bbox_to_edges(gray, b, threshold)
        matches.append((float(tcx_search), float(ind_cy), b))

    matches.sort(key=lambda m: m[1])
    return matches


def ocr_candidate_beside(gray, ind_bbox, col_ul, col_lr, dpi, text_side='right'):
    """OCR text beside an indicator. text_side: 'right' or 'left'."""
    ix0,iy0,ix1,iy1 = [int(v) for v in ind_bbox]
    margin = int(0.04*dpi)
    ty0 = max(0, iy0-margin); ty1 = min(gray.shape[0], iy1+margin)
    if text_side=='right':
        tx0 = ix1+int(0.02*dpi)
        tx1 = int(col_lr[0])
    else:
        tx0 = int(col_ul[0])
        tx1 = ix0-int(0.02*dpi)
    return ocr_first_line(gray, tx0, ty0, tx1, ty1, dpi)


# ── Group edit dialog ──────────────────────────────────────────────────────────

def show_indicator_split_dialog(parent, gray, pil_img, dpi, zoom,
                                 contest, matches, template_bbox,
                                 connect_dist_px, threshold,
                                 col_ul, col_lr, text_side,
                                 ind_style, rank):
    """
    Show a dialog with:
    1. A canvas displaying the contest strip with found indicators highlighted
    2. Options to:
       a. Accept the found indicators
       b. Split a merged blob by clicking split points on the canvas
       c. Split by candidate text lines (OCR-derived Y positions)
       d. Re-search with adjusted parameters

    Returns (final_matches, action) where action is 'accept', 'resplit', or 'cancel'.
    """
    win = tk.Toplevel(parent)
    win.title(f'Indicator Review — {contest["title"]}')
    win.grab_set()
    win.transient(parent)
    win.resizable(True, True)
    win.configure(bg='#1e1e2e')

    result = {'action': 'cancel', 'matches': list(matches)}

    # ── Extract contest strip from PIL image ──────────────────────────────
    cu0 = int(contest['ul'][0]); cv0 = int(contest['ul'][1])
    cu1 = int(contest['lr'][0]); cv1 = int(contest['lr'][1])
    h_img, w_img = gray.shape
    cu0 = max(0, cu0); cv0 = max(0, cv0)
    cu1 = min(w_img, cu1); cv1 = min(h_img, cv1)

    # Display zoom: fit to ~600px wide
    strip_w = cu1 - cu0
    strip_h = cv1 - cv0
    disp_zoom = min(2.0, max(0.5, 600 / strip_w if strip_w > 0 else 1.0))

    strip_pil = pil_img.crop((cu0, cv0, cu1, cv1))
    disp_w = int(strip_w * disp_zoom)
    disp_h = int(strip_h * disp_zoom)
    from PIL import ImageDraw as _ID
    strip_disp = strip_pil.resize((disp_w, disp_h),
                                   __import__('PIL').Image.LANCZOS)
    draw = _ID.Draw(strip_disp)

    def ic(x, y):
        """Image coords -> display coords (relative to contest UL)"""
        return int((x - cu0) * disp_zoom), int((y - cv0) * disp_zoom)

    # Draw current matches
    def draw_matches(draw_, matches_):
        for mx, my, mbbox in matches_:
            dx0, dy0 = ic(mbbox[0], mbbox[1])
            dx1, dy1 = ic(mbbox[2], mbbox[3])
            draw_.rectangle([dx0, dy0, dx1, dy1], outline='#00cc44', width=2)
            # Centre dot
            cdx, cdy = ic(mx, my)
            r = max(2, int(0.015 * dpi * disp_zoom))
            draw_.ellipse([cdx-r, cdy-r, cdx+r, cdy+r], fill='#00cc44')
        # Template
        tdx0, tdy0 = ic(template_bbox[0], template_bbox[1])
        tdx1, tdy1 = ic(template_bbox[2], template_bbox[3])
        draw_.rectangle([tdx0, tdy0, tdx1, tdy1],
                        outline='#ffff00', width=3)

    draw_matches(draw, matches)

    # Split lines (horizontal, in display coords)
    split_ys_display = []   # Y positions of user-drawn split lines
    split_ys_image   = []   # Y positions in image coords

    # ── Header ────────────────────────────────────────────────────────────
    hdr = tk.Frame(win, bg='#2d2d44', pady=4)
    hdr.pack(fill='x')
    n_found = len(matches)
    tk.Label(hdr,
             text=f'Found {n_found} indicator(s) in "{contest["title"]}".',
             font=('Helvetica', 12, 'bold'),
             fg='#ffcc00', bg='#2d2d44').pack()
    tk.Label(hdr,
             text='Yellow = template you dragged.  Green = found indicators.\n'
                  'If wrong: use Split options below, then Re-search.',
             fg='#aaaacc', bg='#2d2d44',
             font=('Helvetica', 10)).pack()

    # ── Canvas ────────────────────────────────────────────────────────────
    from PIL import ImageTk as _ITK
    canvas_h = min(disp_h, 500)
    cf = tk.Frame(win, bg='#111122')
    cf.pack(fill='both', expand=True, padx=4, pady=4)
    vsb = __import__('tkinter').ttk.Scrollbar(cf, orient='vertical')
    vsb.pack(side='right', fill='y')
    canvas = tk.Canvas(cf, width=disp_w, height=canvas_h,
                       bg='#111122', highlightthickness=0,
                       yscrollcommand=vsb.set)
    canvas.pack(side='left', fill='both', expand=True)
    vsb.config(command=canvas.yview)
    canvas.configure(scrollregion=(0, 0, disp_w, disp_h))

    # Render strip to canvas
    _tk_img_ref = [None]
    def refresh_canvas(matches_to_show):
        strip_copy = strip_pil.resize((disp_w, disp_h),
                                       __import__('PIL').Image.LANCZOS)
        dr = _ID.Draw(strip_copy)
        draw_matches(dr, matches_to_show)
        # Draw split lines
        for sy in split_ys_display:
            dr.line([(0, sy), (disp_w, sy)], fill='#ff8800', width=2)
        _tk_img_ref[0] = _ITK.PhotoImage(strip_copy)
        canvas.delete('all')
        canvas.create_image(0, 0, anchor='nw', image=_tk_img_ref[0])

    refresh_canvas(matches)

    # ── Click to add split line ───────────────────────────────────────────
    split_mode = [False]
    split_lbl = tk.Label(win,
                          text='Click mode: OFF  (click "Split by click" to enable)',
                          fg='#aaaacc', bg='#1e1e2e',
                          font=('Helvetica', 9))
    split_lbl.pack()

    # v17: kernel size scale for live tuning
    intra_gap = measure_intra_gap(gray, template_bbox)
    inter_gap = 60
    _tw = template_bbox[2]-template_bbox[0]
    _th = template_bbox[3]-template_bbox[1]
    if text_side == 'right':
        _tl_x0 = int(template_bbox[2]) + int(0.02*dpi)
        _tl_x1 = int(col_lr[0]) if col_lr else cu1
    else:
        _tl_x0 = int(col_ul[0]) if col_ul else cu0
        _tl_x1 = int(template_bbox[0]) - int(0.02*dpi)
    tly_s = find_text_line_ys(
        gray, _tl_x0, _tl_x1,
        int(contest['ul'][1]), int(contest['lr'][1]), _th,
        start_y=template_bbox[1])
    if len(tly_s) >= 2:
        sps = [tly_s[i+1]-tly_s[i] for i in range(len(tly_s)-1)]
        inter_gap = max(10, min(sps) - _th)
    k_min_s = max(2, intra_gap+1)
    k_max_s = max(k_min_s+4, min(60, inter_gap//3))

    scale_frame = tk.Frame(win, bg='#1e1e2e')
    scale_frame.pack(fill='x', padx=8, pady=2)
    tk.Label(scale_frame,
             text=f'Kernel size  (intra-gap={intra_gap}px  max={k_max_s}):',
             bg='#1e1e2e', fg='#aaaacc',
             font=('Helvetica', 9)).pack(side='left')
    kernel_var = tk.IntVar(value=min(k_min_s+2, k_max_s))
    _tmpl_sig = compute_dark_signature(gray, template_bbox)

    def on_kernel_change(val):
        k = int(float(val))
        tcx_ = (template_bbox[0]+template_bbox[2])//2
        LOCK_ = 5
        sx0_ = max(int(contest['ul'][0]), tcx_-_tw//2-LOCK_)
        sx1_ = min(int(contest['lr'][0]), tcx_+_tw//2+LOCK_)
        sy0_ = max(int(contest['ul'][1]), template_bbox[1])
        sy1_ = int(contest['lr'][1])
        if sx1_ <= sx0_ or sy1_ <= sy0_: return
        st_  = gray[sy0_:sy1_, sx0_:sx1_]
        _, bin_ = cv2.threshold(st_, 192, 255, cv2.THRESH_BINARY_INV)
        _, new_m = score_kernel(k, bin_, tly_s, template_bbox,
                                 sy0_, sx0_, _tw, _th, tcx_,
                                 gray=gray, template_sig=_tmpl_sig)
        if new_m:
            result['matches'] = new_m
            refresh_canvas(new_m)

    scale_w = tk.Scale(scale_frame, from_=k_min_s, to=k_max_s,
                       orient='horizontal', variable=kernel_var,
                       command=on_kernel_change,
                       bg='#1e1e2e', fg='#aaaacc',
                       troughcolor='#334455',
                       highlightthickness=0, length=300)
    scale_w.pack(side='left', padx=4)

    # v16: Add/remove indicator mode
    adding_mode = [False]
    add_lbl = tk.Label(win,
                        text='Add mode: OFF',
                        fg='#aaaacc', bg='#1e1e2e',
                        font=('Helvetica', 9))
    add_lbl.pack()

    def on_canvas_click(event):
        cy_disp = canvas.canvasy(event.y)
        cx_disp = canvas.canvasx(event.x)
        if adding_mode[0]:
            # Add indicator locked to template X, at clicked Y
            img_y = cv0 + cy_disp / disp_zoom
            tcx_  = (template_bbox[0]+template_bbox[2])//2
            tw_   = template_bbox[2]-template_bbox[0]
            th_   = template_bbox[3]-template_bbox[1]
            new_bb = (tcx_-tw_//2, int(img_y)-th_//2,
                      tcx_+tw_//2, int(img_y)+th_//2)
            result['matches'].append((float(tcx_), float(img_y), new_bb))
            result['matches'].sort(key=lambda m: m[1])
            add_lbl.config(
                text=f'Added at y={int(img_y)}. Total: {len(result["matches"])}')
            refresh_canvas(result['matches'])
        elif split_mode[0]:
            cy_img = cv0 + int(cy_disp / disp_zoom)
            split_ys_display.append(int(cy_disp))
            split_ys_image.append(cy_img)
            split_lbl.config(
                text=f'Split lines: {len(split_ys_image)}')
            refresh_canvas(result['matches'])

    def add_indicator_mode():
        adding_mode[0] = not adding_mode[0]
        split_mode[0] = False
        if adding_mode[0]:
            add_lbl.config(text='Add mode: ON — click canvas to add indicator',
                            fg='#aaffcc')
            split_lbl.config(text='Split mode: OFF', fg='#aaaacc')
        else:
            add_lbl.config(text='Add mode: OFF', fg='#aaaacc')

    def remove_last_indicator():
        if result['matches']:
            result['matches'].pop()
            add_lbl.config(
                text=f'Removed last. {len(result["matches"])} remain.')
            refresh_canvas(result['matches'])

    canvas.bind('<Button-1>', on_canvas_click)

    # v19: keyboard nudge and resize when hovering over an indicator
    # Track which match the cursor is nearest to
    hovered_idx = [None]
    NUDGE_PX = 1   # 1 image pixel per keypress

    def nearest_match_idx(cx_disp, cy_disp):
        """Return index of match nearest to canvas display coords."""
        if not result['matches']: return None
        img_x = cu0 + cx_disp / disp_zoom
        img_y = cv0 + cy_disp / disp_zoom
        dists = [((m[0]-img_x)**2+(m[1]-img_y)**2) for m in result['matches']]
        return int(np.argmin(dists))

    def on_canvas_motion(event):
        cx = canvas.canvasx(event.x)
        cy = canvas.canvasy(event.y)
        hovered_idx[0] = nearest_match_idx(cx, cy)

    canvas.bind('<Motion>', on_canvas_motion)
    canvas.bind('<Enter>', lambda e: canvas.focus_set())

    def nudge_or_resize(event):
        idx = hovered_idx[0]
        if idx is None or idx >= len(result['matches']): return
        lcx, lcy, (bx0,by0,bx1,by1) = result['matches'][idx]
        k = event.keysym
        # Arrow keys: move centre (and bbox) by 1 image pixel
        if   k=='Left':  lcx-=NUDGE_PX; bx0-=NUDGE_PX; bx1-=NUDGE_PX
        elif k=='Right': lcx+=NUDGE_PX; bx0+=NUDGE_PX; bx1+=NUDGE_PX
        elif k=='Up':    lcy-=NUDGE_PX; by0-=NUDGE_PX; by1-=NUDGE_PX
        elif k=='Down':  lcy+=NUDGE_PX; by0+=NUDGE_PX; by1+=NUDGE_PX
        # S = grow by 1px each side; s = shrink by 1px each side
        elif k=='S':
            bx0-=NUDGE_PX; by0-=NUDGE_PX
            bx1+=NUDGE_PX; by1+=NUDGE_PX
        elif k=='s':
            bx0+=NUDGE_PX; by0+=NUDGE_PX
            bx1-=NUDGE_PX; by1-=NUDGE_PX
            if bx1<=bx0 or by1<=by0: return   # don't collapse to nothing
        else:
            return
        result['matches'][idx] = (lcx, lcy, (bx0,by0,bx1,by1))
        refresh_canvas(result['matches'])

    canvas.bind('<Key>', nudge_or_resize)
    canvas.bind('<Left>',  nudge_or_resize)
    canvas.bind('<Right>', nudge_or_resize)
    canvas.bind('<Up>',    nudge_or_resize)
    canvas.bind('<Down>',  nudge_or_resize)

    # ── OCR-based candidate line detection ────────────────────────────────
    def split_by_text_lines():
        """Find text line Y positions in the contest strip and use as splits."""
        if not OCR_AVAILABLE:
            tk.messagebox.showinfo('OCR',
                'pytesseract not available.', parent=win)
            return
        # Get Y centres of text lines in the contest area,
        # on the TEXT side of the indicators
        tx0 = int(col_ul[0]); tx1 = int(col_lr[0])
        strip_gray = gray[cv0:cv1, tx0:tx1]
        scale = min(1.0, 300.0 / dpi)
        import cv2 as _cv2
        if scale < 0.95:
            sg2 = _cv2.resize(strip_gray, None, fx=scale, fy=scale,
                              interpolation=_cv2.INTER_AREA)
        else:
            sg2 = strip_gray

        try:
            import pytesseract as _tess
            data = _tess.image_to_data(sg2, config='--psm 6 --oem 3',
                                        output_type=_tess.Output.DICT)
        except Exception as e:
            tk.messagebox.showerror('OCR Error', str(e), parent=win)
            return

        # Collect line Y centres (in image coords)
        line_ys = []
        for i, txt in enumerate(data['text']):
            if not txt.strip() or float(data['conf'][i]) < 20: continue
            ly = int(data['top'][i] / scale) + cv0
            lh = int(data['height'][i] / scale)
            line_ys.append(ly + lh // 2)

        if not line_ys:
            tk.messagebox.showinfo('No text',
                'No text lines found in contest.', parent=win)
            return

        line_ys.sort()
        # Compute midpoints between consecutive lines as split positions
        split_ys_display.clear()
        split_ys_image.clear()
        for i in range(len(line_ys) - 1):
            mid = (line_ys[i] + line_ys[i+1]) // 2
            split_ys_image.append(mid)
            split_ys_display.append(int((mid - cv0) * disp_zoom))

        split_lbl.config(
            text=f'OCR: {len(split_ys_image)} split line(s) from text.')
        refresh_canvas(result['matches'])

    def resplit_and_search():
        """
        Apply split lines to the template bbox to get sub-bboxes,
        then re-search for each sub-bbox.
        """
        if not split_ys_image:
            tk.messagebox.showinfo('No splits',
                'Add split lines first.', parent=win)
            return

        # Divide the template bbox by split lines
        tx0_, ty0_, tx1_, ty1_ = template_bbox
        boundaries = sorted([ty0_] + split_ys_image + [ty1_])

        new_matches = []
        for i in range(len(boundaries) - 1):
            sub_ul = (contest['ul'][0], boundaries[i])
            sub_lr = (contest['lr'][0], boundaries[i+1])
            sub_bbox = (tx0_, boundaries[i], tx1_, boundaries[i+1])

            # Search in this sub-strip
            sub_matches = find_matching_indicators(
                gray, sub_bbox, sub_ul, sub_lr,
                dpi, connect_dist_px, threshold,
                rank_x=None,
                text_side=text_side,
                col_ul=col_ul, col_lr=col_lr)
            if sub_matches:
                new_matches.extend(sub_matches)
            else:
                # No match found — use sub-strip centre as fallback
                mid_y = (boundaries[i] + boundaries[i+1]) // 2
                new_matches.append((
                    (tx0_+tx1_)//2, mid_y,
                    (tx0_, boundaries[i], tx1_, boundaries[i+1])))

        result['matches'] = new_matches
        split_lbl.config(
            text=f'Re-searched: {len(new_matches)} indicator(s) found.')
        refresh_canvas(new_matches)

    # ── Buttons ───────────────────────────────────────────────────────────
    bf = tk.Frame(win, bg='#1e1e2e', pady=4)
    bf.pack(fill='x')

    def clear_splits():
        split_ys_display.clear()
        split_ys_image.clear()
        split_lbl.config(text='Split lines cleared.')
        refresh_canvas(result['matches'])

    def toggle_split_mode():
        split_mode[0] = not split_mode[0]
        if split_mode[0]:
            split_lbl.config(
                text='Click mode: ON — click on canvas to add split lines',
                fg='#ffaa00')
            toggle_btn.config(text='Stop Clicking', bg='#885500')
        else:
            split_lbl.config(
                text='Click mode: OFF', fg='#aaaacc')
            toggle_btn.config(text='Split by Click', bg='#3d3d5c')

    # v16: label_button guarantees visible colors on macOS
    toggle_btn = label_button(bf, 'Split by Click', toggle_split_mode,
                               bg='#443300', fg='#ffdd88')
    toggle_btn.pack(side='left', padx=4)

    label_button(bf, 'Split by Text Lines', split_by_text_lines,
                 bg='#1a3355', fg='#aaddff').pack(side='left', padx=4)

    label_button(bf, 'Re-search', resplit_and_search,
                 bg='#003366', fg='#ffffff').pack(side='left', padx=4)

    label_button(bf, 'Clear Splits', clear_splits,
                 bg='#331111', fg='#ffaaaa').pack(side='left', padx=4)

    label_button(bf, 'Add Indicator', add_indicator_mode,
                 bg='#003322', fg='#aaffcc').pack(side='left', padx=4)

    label_button(bf, 'Remove Last', remove_last_indicator,
                 bg='#332200', fg='#ffcc88').pack(side='left', padx=4)

    label_button(bf, 'Accept & Continue',
                 lambda: (result.update({'action': 'accept',
                                         'matches': result['matches']}),
                          win.destroy()),
                 bg='#004400', fg='#aaffaa',
                 font=('Helvetica',11,'bold')).pack(side='right', padx=6)

    label_button(bf, 'Cancel', win.destroy,
                 bg='#330000', fg='#ffaaaa').pack(side='right', padx=2)

    win.geometry(f'{disp_w + 40}x{min(disp_h + 180, 700)}')
    win.minsize(500, 350)
    parent.wait_window(win)
    return result['action'], result['matches']


def ask_contest_title(parent, default_text: str, contest_num: int) -> str | None:
    """
    Full-width multiline dialog for contest title.
    Shows all OCR'd text with the ability to delete lines.
    Returns the edited title string, or None if cancelled.
    """
    win = tk.Toplevel(parent)
    win.title(f'Contest {contest_num} Title')
    win.grab_set()
    win.transient(parent)
    win.resizable(True, True)

    tk.Label(win,
             text=f'Contest {contest_num} — edit title below.\n'
                  'Delete unwanted lines (instructions, preamble, etc.).',
             font=('Helvetica', 11), pady=6,
             bg='#2d2d44', fg='#e0e0ff').pack(fill='x')

    # Split OCR text into lines; one Entry widget per line
    lines = [l.strip() for l in default_text.split() if l.strip()]             if '\n' not in default_text             else [l.strip() for l in default_text.split('\n')]
    # Re-split by actual newlines if present
    raw_lines = default_text.split('\n') if '\n' in default_text                 else [default_text]

    frame = tk.Frame(win, bg='#1e1e2e')
    frame.pack(fill='both', expand=True, padx=8, pady=4)

    row_vars = []
    row_frames = []

    def add_line(text=''):
        idx = len(row_vars)
        rf = tk.Frame(frame, bg='#252535')
        rf.pack(fill='x', pady=1)
        var = tk.StringVar(value=text)
        tk.Label(rf, text=f'{idx+1}.',
                 width=3, bg='#252535', fg='#888888').pack(side='left')
        ent = tk.Entry(rf, textvariable=var, width=52,
                       font=('Helvetica', 12),
                       bg='#333355', fg='#e0e0ff',
                       insertbackground='white', relief='flat')
        ent.pack(side='left', fill='x', expand=True, padx=4)
        label_button(rf, 'X',
                     lambda f=rf, v=var: (v.set(''), f.pack_forget()),
                     bg='#440000', fg='#ff9999',
                     font=('Helvetica',10,'bold'),
                     padx=6, pady=2).pack(side='right', padx=2)
        row_vars.append(var)
        row_frames.append(rf)

    for line in raw_lines:
        add_line(line)

    # Buttons
    result = [None]

    def ok():
        parts = [v.get().strip() for v in row_vars if v.get().strip()]
        result[0] = ' '.join(parts)
        win.destroy()

    bf = tk.Frame(win, bg='#2d2d44', pady=4)
    bf.pack(fill='x')
    label_button(bf, '+ Add Line', lambda: add_line(''),
                 bg='#1a2a4a', fg='#aaccff').pack(side='left', padx=6)
    label_button(bf, 'OK', ok,
                 bg='#003300', fg='#aaffaa',
                 font=('Helvetica',11,'bold')).pack(side='right', padx=6)
    label_button(bf, 'Cancel', win.destroy,
                 bg='#330000', fg='#ffaaaa').pack(side='right', padx=2)

    win.geometry('640x360')
    win.minsize(480, 200)
    parent.wait_window(win)
    return result[0]


class CandidateGroupEditor(tk.Toplevel):
    """
    Line-by-line candidate editor.
    Each candidate occupies a labelled frame with:
      - A wider name entry (full width)
      - Write-In checkbox
      - Rank spinbox (RCV only)
      - Delete button
    """
    def __init__(self, parent, contest_title, candidates, ranked=False):
        super().__init__(parent)
        self.title(f'Review: {contest_title}')
        self.grab_set()
        self.result = None
        self.candidates = list(candidates)
        self.ranked = ranked
        self.transient(parent)
        self.resizable(True, True)
        self._rows = []
        self._build(contest_title)

    def _build(self, title):
        self.configure(bg='#1e1e2e')
        # Header
        hdr = tk.Frame(self, bg='#2d2d44', pady=6)
        hdr.pack(fill='x')
        tk.Label(hdr, text=title,
                 font=('Helvetica', 13, 'bold'),
                 fg='#ffcc00', bg='#2d2d44',
                 wraplength=680).pack(padx=8)
        tk.Label(hdr,
                 text='Edit names. Uncheck Write-In if wrong. Delete line to remove. Enter to add missing.',
                 fg='#aaaacc', bg='#2d2d44',
                 font=('Helvetica', 10)).pack()

        # Scrollable area
        outer = tk.Frame(self, bg='#1e1e2e')
        outer.pack(fill='both', expand=True, padx=6, pady=4)
        vsb = ttk.Scrollbar(outer, orient='vertical')
        vsb.pack(side='right', fill='y')
        self._canvas = tk.Canvas(outer, yscrollcommand=vsb.set,
                                  bg='#1e1e2e', highlightthickness=0)
        self._canvas.pack(side='left', fill='both', expand=True)
        vsb.config(command=self._canvas.yview)
        self._inner = tk.Frame(self._canvas, bg='#1e1e2e')
        self._win_id = self._canvas.create_window(
            (0, 0), window=self._inner, anchor='nw')
        self._inner.bind('<Configure>', self._on_inner_configure)
        self._canvas.bind('<Configure>', self._on_canvas_configure)

        # Add rows
        for cand in self.candidates:
            self._add_row(cand)

        # Buttons
        bf = tk.Frame(self, bg='#1e1e2e', pady=4)
        bf.pack(fill='x')
        label_button(bf, '+ Add Candidate', self._add_empty,
                     bg='#1a2a4a', fg='#aaccff').pack(side='left', padx=6)
        label_button(bf, 'OK  (save all)', self._ok,
                     bg='#003300', fg='#aaffaa',
                     font=('Helvetica',11,'bold')).pack(side='right', padx=6)
        label_button(bf, 'Cancel', self.destroy,
                     bg='#330000', fg='#ffaaaa').pack(side='right', padx=2)

        self.geometry('720x500')
        self.minsize(600, 300)

    def _on_inner_configure(self, event):
        self._canvas.configure(scrollregion=self._canvas.bbox('all'))

    def _on_canvas_configure(self, event):
        self._canvas.itemconfig(self._win_id, width=event.width)

    def _add_row(self, cand):
        idx = len(self._rows)
        row_bg = '#252535' if idx % 2 == 0 else '#1e1e2e'
        frame = tk.Frame(self._inner, bg=row_bg, pady=3, padx=4)
        frame.pack(fill='x', pady=1)

        # Row number label
        tk.Label(frame, text=f'{idx+1}.', width=3,
                 bg=row_bg, fg='#888888',
                 font=('Courier', 10)).pack(side='left')

        # Name entry — takes most of the width
        name_var = tk.StringVar(value=cand.get('name', ''))
        name_ent = tk.Entry(frame, textvariable=name_var,
                            font=('Helvetica', 11), width=40,
                            bg='#333355', fg='#e0e0ff',
                            insertbackground='white',
                            relief='flat')
        name_ent.pack(side='left', padx=4, fill='x', expand=True)

        # Write-In checkbox
        wi_var = tk.BooleanVar(value=cand.get('write_in', False))
        tk.Checkbutton(frame, text='WI', variable=wi_var,
                       bg=row_bg, fg='#aaaacc',
                       selectcolor='#333355',
                       font=('Helvetica', 9)).pack(side='left', padx=2)

        # Rank spinbox (RCV only)
        rank_var = tk.IntVar(value=cand.get('rank', 1))
        if self.ranked:
            tk.Label(frame, text='Rank:',
                     bg=row_bg, fg='#aaaacc',
                     font=('Helvetica', 9)).pack(side='left')
            tk.Spinbox(frame, from_=1, to=20,
                       textvariable=rank_var, width=4,
                       bg='#333355', fg='#e0e0ff',
                       relief='flat').pack(side='left', padx=2)

        # Delete button
        label_button(frame, 'X',
                     lambda f=frame, i=idx: self._delete_row(f, i),
                     bg='#440000', fg='#ff9999',
                     font=('Helvetica',10,'bold'),
                     padx=6, pady=2).pack(side='right', padx=2)

        self._rows.append({
            'name_var':  name_var,
            'wi_var':    wi_var,
            'rank_var':  rank_var,
            'bbox':      cand.get('bbox'),
            'ind_bbox':  cand.get('ind_bbox'),
            'frame':     frame,
            'active':    True,
        })

    def _add_empty(self):
        self._add_row({'name': '', 'write_in': False, 'rank': 1})
        # Scroll to bottom
        self._canvas.update_idletasks()
        self._canvas.yview_moveto(1.0)

    def _delete_row(self, frame, idx):
        if 0 <= idx < len(self._rows):
            self._rows[idx]['active'] = False
            frame.configure(bg='#3a1515')
            for w in frame.winfo_children():
                try:
                    w.configure(bg='#3a1515')
                except Exception:
                    pass
            self._rows[idx]['name_var'].set('~~DELETED~~')

    def _ok(self):
        self.result = [
            {
                'name':     r['name_var'].get().strip(),
                'write_in': r['wi_var'].get(),
                'rank':     r['rank_var'].get() if self.ranked else 0,
                'bbox':     r.get('bbox'),
                'ind_bbox': r.get('ind_bbox'),
            }
            for r in self._rows
            if r['active']
            and r['name_var'].get().strip()
            and r['name_var'].get() != '~~DELETED~~'
        ]
        self.destroy()



# ── Main application ───────────────────────────────────────────────────────────

# ── Persistence helpers ──────────────────────────────────────────────────────
# Saves/loads layout zones, markers, and columns to a sidecar JSON file
# so subsequent runs on the same ballot type don't need re-clicking.

PERSIST_VERSION = 1

def persist_path(image_path: str) -> str:
    """Return path to sidecar .ballot_layout.json beside the image."""
    from pathlib import Path
    return str(Path(image_path).with_suffix('.ballot_layout.json'))


def save_layout(image_path, layout_zones, markers, columns, dpi):
    """Save layout zones, markers, columns to sidecar JSON."""
    import json
    # v22: contests NOT persisted — each ballot may have different contest layout
    data = {
        'persist_version': PERSIST_VERSION,
        'dpi': dpi,
        'layout_zones': [
            {'label': lz['label'],
             'ul': list(lz['ul']), 'lr': list(lz['lr'])}
            for lz in layout_zones
        ],
        'markers': [
            {'cx': m[0], 'cy': m[1],
             'bbox': list(m[2]) if m[2] else None}
            for m in markers
        ],
        'columns': [
            {'ul': list(col['ul']), 'lr': list(col['lr'])}
            for col in columns
        ],
    }
    path = persist_path(image_path)
    with open(path, 'w') as f:
        json.dump(data, f, indent=2)
    print(f'Layout saved: {path}')
    return path


def load_layout(image_path):
    """
    Load persisted layout from sidecar JSON.
    Returns (layout_zones, markers, columns) or (None,None,None) if absent.
    """
    import json
    path = persist_path(image_path)
    try:
        with open(path) as f:
            data = json.load(f)
        if data.get('persist_version') != PERSIST_VERSION:
            return None, None, None
        layout_zones = [
            {'label': lz['label'],
             'ul': tuple(lz['ul']), 'lr': tuple(lz['lr'])}
            for lz in data.get('layout_zones', [])
        ]
        markers = [
            (m['cx'], m['cy'],
             tuple(m['bbox']) if m['bbox'] else None)
            for m in data.get('markers', [])
        ]
        columns = [
            {'ul': tuple(col['ul']), 'lr': tuple(col['lr'])}
            for col in data.get('columns', [])
        ]
        return layout_zones, markers, columns
    except (FileNotFoundError, KeyError, json.JSONDecodeError):
        return None, None, None


def refind_from_persisted(gray, layout_zones, markers, columns,
                           dpi, threshold=192):
    """
    v20: Given persisted layout zones/markers/columns, re-locate them
    in a new image by searching near the recorded positions.

    For each marker: find the nearest dark blob centroid within
    ±0.3" of the recorded position.

    For each layout zone: find the largest dark blob within the zone
    (QR code, barcode, etc.).

    For each column: snap left/right edges to nearest vertical line
    or blank gap within ±0.25".

    Returns (layout_zones, markers, columns) with updated positions.
    """
    h, w = gray.shape
    search_r_px = int(0.30 * dpi)

    # Re-find markers
    new_markers = []
    for cx, cy, bbox in markers:
        bcx, bcy, nbbox = find_blob_centre(
            gray, cx, cy, search_r_px, threshold)
        new_markers.append((bcx, bcy, nbbox))

    # Re-find layout zones (just verify dark content in zone)
    new_zones = []
    for lz in layout_zones:
        ul = lz['ul']; lr = lz['lr']
        x0=max(0,int(ul[0])); y0=max(0,int(ul[1]))
        x1=min(w,int(lr[0])); y1=min(h,int(lr[1]))
        zone = gray[y0:y1, x0:x1]
        dark_count = int(np.sum(zone < threshold))
        # If substantial dark content present, keep the zone as-is
        if dark_count > 10:
            new_zones.append(lz)
        else:
            # Expand search ±0.15" and try again
            pad = int(0.15*dpi)
            x0b=max(0,x0-pad); y0b=max(0,y0-pad)
            x1b=min(w,x1+pad); y1b=min(h,y1+pad)
            zone2 = gray[y0b:y1b, x0b:x1b]
            if int(np.sum(zone2 < threshold)) > 10:
                new_zones.append({
                    'label': lz['label'],
                    'ul': (float(x0b), float(y0b)),
                    'lr': (float(x1b), float(y1b))
                })
            # else: drop zone (not found in this image)

    # Re-snap column boundaries
    new_cols = []
    search_px = int(0.25 * dpi)
    for col in columns:
        ul = col['ul']; lr = col['lr']
        y0 = int(ul[1]); y1 = int(lr[1])
        sl, _, _, _ = snap_vertical_boundary(
            gray, int(ul[0]), y0, y1, search_px)
        sr, _, _, _ = snap_vertical_boundary(
            gray, int(lr[0]), y0, y1, search_px)
        new_cols.append({
            'ul': (float(sl), ul[1]),
            'lr': (float(sr), lr[1])
        })

    return new_zones, new_markers, new_cols


class BallotMapper:
    def __init__(self, root, image_path, dpi, out_path):
        self.root     = root
        self.dpi      = dpi
        self.out_path = out_path
        self.connect_dist_px = int(0.10 * dpi)  # 0.1" bridge distance

        self.pil_img  = Image.open(image_path).convert('RGB')
        self.img_w, self.img_h = self.pil_img.size

        if CV2_AVAILABLE:
            rgb = np.array(self.pil_img)
            self.gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
        else:
            self.gray = None

        print(f"Image: {self.img_w}x{self.img_h}  DPI:{dpi}  "
              f"({self.img_w/dpi:.2f}\" x {self.img_h/dpi:.2f}\")")

        self.zoom        = 1.0
        self._drag_start = None
        self._drag_img   = None
        self._rect_id    = None

        self.layout_zones = []  # [{'ul','lr','label'}]
        self.markers  = []   # [(cx,cy,bbox)]
        self._img_path = image_path  # for persistence sidecar
        self.columns  = []   # [{'ul','lr'}]
        self.contests = []   # [{'ul','lr','title','contest_type',
                              #   'max_votes','indicators':[...]}]
        self._snap_lines   = []
        self._highlight_bboxes = []  # [(bbox, color)] to draw

        # Indicator capture state
        self._cap_contest_idx  = 0    # which contest we're capturing for
        self._cap_rank         = 1    # current rank (1 for non-RCV)
        self._cap_phase        = 'drag'  # 'drag' | 'review'
        self._cap_template_bbox = None
        self._cap_matches      = []   # list of (cx,cy,bbox,name)

        self.phase_idx = 0
        self._destroyed = False  # guard against post-destroy callbacks
        # v20: try to load persisted layout zones/markers/columns
        self._try_load_persisted()
        self._build_ui()
        self._update_phase()

    # ── UI ────────────────────────────────────────────────────────────────────

    def _build_ui(self):
        self.root.title(f'Ballot Mapper v{VERSION}')
        self.root.configure(bg='#1e1e2e')

        top = tk.Frame(self.root, bg='#2d2d44', pady=4)
        top.pack(fill='x')
        self.phase_var = tk.StringVar()
        tk.Label(top, textvariable=self.phase_var,
                 font=('Helvetica',13,'bold'),
                 fg='#ffcc00', bg='#2d2d44', padx=12).pack(side='right')
        self.instr_var = tk.StringVar()
        tk.Label(top, textvariable=self.instr_var,
                 font=('Helvetica',11), fg='#e0e0ff', bg='#2d2d44',
                 wraplength=900, justify='left', padx=8).pack(side='left')

        cf = tk.Frame(self.root, bg='#1e1e2e')
        cf.pack(fill='both', expand=True)
        self.canvas = tk.Canvas(cf, bg='#111122', cursor='crosshair',
                                highlightthickness=0)
        hbar=ttk.Scrollbar(cf,orient='horizontal',command=self.canvas.xview)
        vbar=ttk.Scrollbar(cf,orient='vertical',  command=self.canvas.yview)
        self.canvas.configure(xscrollcommand=hbar.set,
                               yscrollcommand=vbar.set)
        hbar.pack(side='bottom',fill='x')
        vbar.pack(side='right', fill='y')
        self.canvas.pack(fill='both', expand=True)

        self.status_var=tk.StringVar(value='Ready')
        tk.Label(self.root, textvariable=self.status_var,
                 font=('Courier',10), fg='#aaaacc', bg='#111122',
                 anchor='w', padx=8).pack(fill='x')

        bf=tk.Frame(self.root,bg='#2d2d44',pady=3)
        bf.pack(fill='x')
        toolbar_items = [
            ('+ Zoom',     self._zoom_in,   '#3d3d5c', '#e0e0ff', '#5555aa'),
            ('- Zoom',     self._zoom_out,  '#3d3d5c', '#e0e0ff', '#5555aa'),
            ('Fit',        self._zoom_fit,  '#3d3d5c', '#e0e0ff', '#5555aa'),
            ('Undo',       self._undo,      '#553333', '#ffcccc', '#994444'),
            ('Skip ->',    self._next_phase,'#335533', '#ccffcc', '#449944'),
            ('Save & Quit',self._save_quit, '#004488', '#ffffff', '#0066bb'),
            ('Save Layout',self._save_layout,'#334422','#ccffaa','#557733'),
        ]
        for lbl, cmd, bg, fg, abg in toolbar_items:
            tk.Button(bf, text=lbl, command=cmd,
                      bg=bg, fg=fg,
                      activebackground=abg, activeforeground='white',
                      **_BTN_TOOLBAR).pack(side='left', padx=3)

        self.canvas.bind('<ButtonPress-1>',   self._on_press)
        self.canvas.bind('<B1-Motion>',       self._on_drag)
        self.canvas.bind('<ButtonRelease-1>', self._on_release)
        self.canvas.bind('<Button-3>',        lambda e: self._undo())
        self.canvas.bind('<MouseWheel>',      self._on_scroll)
        self.canvas.bind('<Button-4>',        self._on_scroll)
        self.canvas.bind('<Button-5>',        self._on_scroll)
        self.root.bind('<Return>',   lambda e: self._next_phase())
        self.root.bind('<KP_Enter>', lambda e: self._next_phase())
        self.root.bind('<Escape>',   lambda e: None)
        self.root.bind('<Control-z>',lambda e: self._undo())
        self.root.bind('<plus>',     lambda e: self._zoom_in())
        self.root.bind('<minus>',    lambda e: self._zoom_out())
        self.root.bind('<q>',        lambda e: self._save_quit())
        self.root.bind('<C>',        lambda e: self._cycle_contest(+1))
        self.root.bind('<c>',        lambda e: self._cycle_contest(-1))
        # Bind Return to canvas too so it fires when canvas has focus
        self.canvas.bind('<Return>',   lambda e: self._next_phase())
        self.canvas.bind('<KP_Enter>', lambda e: self._next_phase())
        # v22: hover nudge for markers, layout zones, columns on main canvas
        self._hovered_item = None   # ('marker',idx)|('zone',idx)|('column',idx)
        self.canvas.bind('<Motion>',   self._on_canvas_motion)
        self.canvas.bind('<Enter>',    lambda e: self.canvas.focus_set())
        self.canvas.bind('<Key>',      self._on_canvas_nudge)
        self.canvas.bind('<Left>',     self._on_canvas_nudge)
        self.canvas.bind('<Right>',    self._on_canvas_nudge)
        self.canvas.bind('<Up>',       self._on_canvas_nudge)
        self.canvas.bind('<Down>',     self._on_canvas_nudge)
        self.root.after(200, self._zoom_fit)

    # ── Render ────────────────────────────────────────────────────────────────

    def _render(self):
        if getattr(self, '_destroyed', False): return
        zw=int(self.img_w*self.zoom); zh=int(self.img_h*self.zoom)
        scaled=self.pil_img.resize((zw,zh),Image.LANCZOS)
        draw=ImageDraw.Draw(scaled)

        def iz(x,y): return int(x*self.zoom),int(y*self.zoom)

        def drect(ul,lr,color,width=2,label=None):
            x0,y0=iz(*ul); x1,y1=iz(*lr)
            draw.rectangle([x0,y0,x1,y1],outline=color,width=width)
            if label:
                tw=len(label)*7+6
                draw.rectangle([x0,y0,x0+tw,y0+16],fill=color)
                draw.text((x0+3,y0+1),label,fill='#ffffff')

        def draw_bbox(bbox,color,width=2,label=None):
            x0,y0,x1,y1=bbox
            drect((x0,y0),(x1,y1),color,width,label)

        def dcross(x,y,color):
            cx,cy=iz(x,y); s=max(5,int(12*self.zoom))
            draw.line([cx-s,cy,cx+s,cy],fill=color,width=2)
            draw.line([cx,cy-s,cx,cy+s],fill=color,width=2)

        # Layout zones (cyan)
        for lz in self.layout_zones:
            drect(lz['ul'],lz['lr'],'#00cccc',2,lz.get('label','')[:16])

        # Snap lines
        for sx0,sy0,sx1,sy1,sc in self._snap_lines:
            x0,y0=iz(sx0,sy0); x1,y1=iz(sx1,sy1)
            draw.line([x0,y0,x1,y1],fill=COL_SNAP,
                      width=max(1,int(2*self.zoom)))

        # Highlight bboxes (template matches)
        for bbox,color in self._highlight_bboxes:
            draw_bbox(bbox,color,2)

        # Markers
        for i,(mx,my,mbbox) in enumerate(self.markers):
            dcross(mx,my,COL_MARKER)
            if mbbox:
                draw_bbox(mbbox,COL_MARKER,1)
            cx,cy=iz(mx,my)
            draw.text((cx+8,cy-8),f'M{i+1}',fill=COL_MARKER)

        # Columns
        for i,col in enumerate(self.columns):
            drect(col['ul'],col['lr'],COL_COLUMN,2,f'Col {i+1}')

        # Contests + indicators
        for ci,contest in enumerate(self.contests):
            active=(ci==self._cap_contest_idx and
                    PHASES[self.phase_idx][0]=='indicators')
            drect(contest['ul'],contest['lr'],COL_CONTEST,
                  3 if active else 2,
                  contest.get('title','?')[:20])
            for ind in contest.get('indicators',[]):
                bb=ind.get('bbox')
                if bb:
                    draw_bbox(bb,COL_INDICATOR,1,
                              ind.get('candidate','?')[:14])

        # Version
        draw.text((4,zh-20),f'ballot_mapper v{VERSION}',fill='#ffffff')

        self._tk_img=ImageTk.PhotoImage(scaled)
        self.canvas.delete('all')
        self.canvas.create_image(0,0,anchor='nw',image=self._tk_img)
        self.canvas.configure(scrollregion=(0,0,zw,zh))

    # ── Coords ────────────────────────────────────────────────────────────────

    def _c2i(self,cx,cy):
        return self.canvas.canvasx(cx)/self.zoom, \
               self.canvas.canvasy(cy)/self.zoom

    # ── Mouse ─────────────────────────────────────────────────────────────────

    def _on_press(self,event):
        self._drag_start=(event.x,event.y)
        self._drag_img=self._c2i(event.x,event.y)
        if self._rect_id:
            self.canvas.delete(self._rect_id); self._rect_id=None

    def _on_drag(self,event):
        if not self._drag_start: return
        x0,y0=self._drag_start
        sx0=self.canvas.canvasx(x0); sy0=self.canvas.canvasy(y0)
        sx1=self.canvas.canvasx(event.x); sy1=self.canvas.canvasy(event.y)
        if self._rect_id: self.canvas.delete(self._rect_id)
        self._rect_id=self.canvas.create_rectangle(
            sx0,sy0,sx1,sy1,outline=COL_PENDING,width=2,dash=(4,2))
        ix0,iy0=self._drag_img
        ix1,iy1=self._c2i(event.x,event.y)
        self.status_var.set(
            f'({ix0/self.dpi:.3f}", {iy0/self.dpi:.3f}") -> '
            f'({ix1/self.dpi:.3f}", {iy1/self.dpi:.3f}")  '
            f'size: {abs(ix1-ix0)/self.dpi:.3f}" x {abs(iy1-iy0)/self.dpi:.3f}"')

    def _on_release(self,event):
        if not self._drag_start: return
        if self._rect_id:
            self.canvas.delete(self._rect_id); self._rect_id=None
        ix0,iy0=self._drag_img
        ix1,iy1=self._c2i(event.x,event.y)
        ul=(min(ix0,ix1),min(iy0,iy1))
        lr=(max(ix0,ix1),max(iy0,iy1))
        dx=abs(ix1-ix0); dy=abs(iy1-iy0)
        self._drag_start=None

        phase=PHASES[self.phase_idx][0]

        if phase=='layout':
            if dx<3 or dy<3:
                self.status_var.set('Drag to define layout zone'); return
            default_lbl=''
            if self.gray is not None:
                default_lbl = ocr_region(
                    self.gray,int(ul[0]),int(ul[1]),
                    int(lr[0]),int(lr[1]),self.dpi)[:40]
            lbl=simpledialog.askstring(
                'Layout Zone Label',
                'Label for this layout zone\n'
                '(e.g. QR Code, Barcode, Timing Marks):',
                initialvalue=default_lbl or 'QR Code',
                parent=self.root)
            if lbl is None: return
            self.layout_zones.append({'ul':ul,'lr':lr,'label':lbl})
            self.status_var.set(f'Added layout zone: {lbl!r}')
            self._render()

        elif phase=='markers':
            cx_c,cy_c=(ix0+ix1)/2,(iy0+iy1)/2
            sr=max(int(0.3*self.dpi),int(max(dx,dy)/2)+5)
            if self.gray is not None:
                bcx,bcy,bbox=find_blob_centre(self.gray,cx_c,cy_c,sr)
            else:
                bcx,bcy,bbox=cx_c,cy_c,None
            desc=(f'Marker at ({bcx/self.dpi:.3f}", {bcy/self.dpi:.3f}")')
            if self._confirm(desc):
                self.markers.append((bcx,bcy,bbox))
                self._render()

        elif phase=='columns':
            if dx<3 and dy<3:
                self.status_var.set('Drag to define column'); return
            sul,slr=self._snap_boundary(ul,lr)
            desc=(f'Column {len(self.columns)+1}')
            if self._confirm(desc):
                self.columns.append({'ul':sul,'lr':slr})
                self._snap_lines=[]
                self._render()

        elif phase=='contests':
            if dx<3 or dy<3:
                self.status_var.set('Drag to define contest'); return
            sul,slr=self._snap_boundary(ul,lr)
            default_title=''
            if self.gray is not None:
                default_title=ocr_contest_title_multiline(
                    self.gray, sul, slr, self.dpi)
            title=ask_contest_title(
                self.root, default_title or '',
                len(self.contests)+1)
            if title is None: return
            max_v=simpledialog.askinteger('Max Votes',
                f'Max votes for "{title}":',
                initialvalue=1,minvalue=1,maxvalue=20,parent=self.root)
            if max_v is None: max_v=1
            ctype=self._ask_contest_type()
            self.contests.append({
                'ul':sul,'lr':slr,'title':title,
                'contest_type':ctype,'max_votes':max_v,
                'indicators':[]
            })
            self._cap_contest_idx=len(self.contests)-1
            self._snap_lines=[]
            self.status_var.set(f'Added "{title}". Enter to continue.')
            self._render()

        elif phase=='indicators':
            if dx<3 or dy<3:
                self.status_var.set('Drag over an indicator'); return
            self._capture_indicator_template(ul,lr)

    def _on_scroll(self,event):
        factor=1.1
        if event.num==5 or (hasattr(event,'delta') and event.delta<0):
            factor=1/factor
        self.zoom=max(0.1,min(8.0,self.zoom*factor))
        self._render()

    # ── Snap ──────────────────────────────────────────────────────────────────

    def _snap_boundary(self,ul,lr):
        if self.gray is None: return ul,lr
        search_px=int(0.25*self.dpi)
        y0,y1=int(ul[1]),int(lr[1])
        self._snap_lines=[]
        sx_l,st_l,hl0,hl1=snap_vertical_boundary(
            self.gray,int(ul[0]),y0,y1,search_px)
        if st_l!='none':
            sl=hl1 if st_l=='line' else hl0
            self._snap_lines+=[(hl0,y0,hl0,y1,COL_SNAP),
                                (hl1,y0,hl1,y1,COL_SNAP)]
        else: sl=ul[0]
        sx_r,st_r,hr0,hr1=snap_vertical_boundary(
            self.gray,int(lr[0]),y0,y1,search_px)
        if st_r!='none':
            sr=hr0 if st_r=='line' else hr1
            self._snap_lines+=[(hr0,y0,hr0,y1,COL_SNAP),
                                (hr1,y0,hr1,y1,COL_SNAP)]
        else: sr=lr[0]
        return (float(sl),ul[1]),(float(sr),lr[1])

    # ── Indicator capture ──────────────────────────────────────────────────────

    def _capture_indicator_template(self, ul, lr):
        """
        v14: User dragged over one indicator.
        Flow:
          1. Extract template + classify style
          2. Compute text_side / col bounds BEFORE searching (fixes v13 NameError)
          3. Search using text-line-guided fallback strategy
          4. Show split/review dialog
          5. Re-OCR after any split
          6. Show candidate group editor
        """
        if not self.contests:
            self.status_var.set('No contests defined!'); return
        ci = self._cap_contest_idx
        if ci >= len(self.contests): ci = len(self.contests)-1
        contest  = self.contests[ci]
        is_rcv   = contest['contest_type'] == 'RANKED_CHOICE'

        if self.gray is None:
            self.status_var.set('OpenCV not available'); return

        # 1. Extract template
        result = extract_indicator_pattern(
            self.gray, ul, lr, self.connect_dist_px)
        closed_mask, bbox, zone_img, tcx, tcy, (tw, th) = result

        if bbox is None:
            self.status_var.set('No dark pixels found in dragged zone')
            return

        tx0, ty0, tx1, ty1 = bbox

        # Classify style (must be before any branch that references it)
        ind_style = classify_indicator_style(
            self.gray, bbox, self.connect_dist_px)

        # 2. Determine text side and column bounds BEFORE search (v14 fix)
        text_side = self._get_text_side(tcx, contest)
        col_ul, col_lr = self._get_col_bounds(tcx)

        self.status_var.set(
            f'Template: {tw}x{th}px  Style: {ind_style}  '
            f'Text side: {text_side}')
        self._highlight_bboxes = [(bbox, COL_TEMPLATE)]
        self._render()
        self.root.update()

        # 3. RCV rank dialog
        rank = 1
        rank_x = None
        if is_rcv:
            rank = simpledialog.askinteger(
                'Rank',
                'Which rank does this indicator represent?\n'
                '(1 = first choice, 2 = second choice, etc.)',
                initialvalue=self._cap_rank,
                minvalue=1, maxvalue=20, parent=self.root)
            if rank is None: rank = self._cap_rank
            rank_x = int(tcx)
            self._cap_rank = rank

        # 4. Search for matching indicators
        self.status_var.set('Searching for matching indicators...')
        self.root.update()

        matches = find_matching_indicators(
            self.gray, bbox, contest['ul'], contest['lr'],
            self.dpi, self.connect_dist_px,
            rank_x=rank_x if is_rcv else None,
            text_side=text_side,
            col_ul=col_ul, col_lr=col_lr)

        # Fallback: if pattern search found nothing, try text-line-guided search
        if not matches:
            matches = self._text_line_guided_search(
                bbox, contest, col_ul, col_lr, text_side)

        # Always include the dragged template itself if not already present
        if not any(abs(m[1] - tcy) < th * 0.5 for m in matches):
            matches = [(tcx, tcy, bbox)] + matches

        matches.sort(key=lambda m: m[1])

        self.status_var.set(
            f'Found {len(matches)} indicator(s). Style: {ind_style}.')
        self._highlight_bboxes = (
            [(bbox, COL_TEMPLATE)] +
            [(m[2], COL_INDICATOR) for m in matches])
        self._render()
        self.root.update()

        # 5. Initial OCR of candidate names
        rank1_names = {}
        if is_rcv and rank > 1:
            for ind in contest.get('indicators', []):
                if ind.get('rank', 0) == 1 and ind.get('bbox'):
                    b = ind['bbox']
                    cy_key = round((b[1]+b[3])/2 / self.dpi, 2)
                    rank1_names[cy_key] = ind.get('candidate', '')

        def ocr_candidates(match_list):
            cands = []
            for mx, my, mbbox in match_list:
                name = ''
                if is_rcv and rank > 1:
                    my_key = round(my / self.dpi, 2)
                    best_dist = 0.2
                    for k, v in rank1_names.items():
                        if abs(k - my_key) < best_dist:
                            best_dist = abs(k - my_key)
                            name = v
                if not name:
                    name = ocr_candidate_beside(
                        self.gray, mbbox, col_ul, col_lr,
                        self.dpi, text_side)
                cands.append({
                    'name':     name,
                    'write_in': 'write' in name.lower(),
                    'rank':     rank,
                    'bbox':     mbbox,
                    'ind_bbox': mbbox,
                    'style':    ind_style,
                    'cx': mx, 'cy': my,
                })
            return cands

        candidates = ocr_candidates(matches)

        # 6. Split/review dialog
        action, matches = show_indicator_split_dialog(
            self.root, self.gray, self.pil_img, self.dpi, self.zoom,
            contest, matches, bbox,
            self.connect_dist_px, 192,
            col_ul, col_lr, text_side,
            ind_style, rank)

        if action == 'cancel':
            self._highlight_bboxes = []
            self._render()
            return

        # Re-OCR after split (matches may have changed)
        candidates = ocr_candidates(matches)

        # 7. Show group editor
        editor = CandidateGroupEditor(
            self.root,
            f'{contest["title"]} — Rank {rank}' if is_rcv
            else contest['title'],
            candidates, ranked=is_rcv)
        self.root.wait_window(editor)

        # 8. Save results
        if editor.result is not None and len(editor.result) > 0:
            for cand in editor.result:
                contest['indicators'].append({
                    'candidate': cand['name'],
                    'write_in':  cand['write_in'],
                    'rank':      cand['rank'],
                    'bbox':      cand['bbox'],
                    'style':     cand.get('style', ind_style),
                    'text_side': text_side,
                })
            n = len(editor.result)
            self.status_var.set(
                f'Added {n} candidate(s) (rank {rank}) to '
                f'"{contest["title"]}".')
            self._render()
            self.root.update()

            if is_rcv:
                next_rank = rank + 1
                if messagebox.askyesno(
                        f'Rank {next_rank}',
                        f'Rank {rank} done ({n} candidates).\n\n'
                        f'Add rank {next_rank} indicators?\n'
                        f'(Yes = drag a rank-{next_rank} box.)\n'
                        f'No = move to next contest.',
                        parent=self.root):
                    self._cap_rank = next_rank
                    self.status_var.set(
                        f'Drag a rank-{next_rank} indicator box '
                        f'in "{contest["title"]}".')
                    self._update_phase()
                else:
                    self._cap_rank = 1
                    self._inset_indicators_dialog(ci)
                    self._advance_contest()
            else:
                self._inset_indicators_dialog(ci)
                self._advance_contest()
        else:
            self.status_var.set('No candidates saved — try again.')

        self._highlight_bboxes = []
        self._render()

    def _text_line_guided_search(self, template_bbox, contest,
                                  col_ul, col_lr, text_side):
        """
        v14 fallback: when pattern search finds no matches, find indicators
        by locating text lines in the candidate-text zone and projecting
        back to the indicator column.

        Strategy:
        1. OCR the text zone beside the indicators
        2. Find all text line Y centres of similar font size to the first line
        3. For each text line Y, search the indicator stripe for any dark blob
        4. Use the template bbox width/height to define each indicator box
        """
        tx0, ty0, tx1, ty1 = [int(v) for v in template_bbox]
        tw = tx1 - tx0; th = ty1 - ty0
        tcx = (tx0 + tx1) // 2

        contest_y0 = int(contest['ul'][1])
        contest_y1 = int(contest['lr'][1])
        h_img, w_img = self.gray.shape

        # ── Step 1: find text line Y positions in candidate text zone ────
        if text_side == 'right':
            txt_x0 = tx1 + int(0.03 * self.dpi)
            txt_x1 = int(col_lr[0])
        else:
            txt_x0 = int(col_ul[0])
            txt_x1 = tx0 - int(0.03 * self.dpi)

        txt_x0 = max(0, txt_x0); txt_x1 = min(w_img, txt_x1)
        if txt_x1 <= txt_x0:
            return []

        text_line_ys = []
        ref_h = th   # reference text height ≈ indicator height

        if OCR_AVAILABLE:
            try:
                txt_region = self.gray[contest_y0:contest_y1, txt_x0:txt_x1]
                scale = min(1.0, 300.0 / self.dpi)
                if scale < 0.95:
                    import cv2 as _cv
                    txt_small = _cv.resize(txt_region, None,
                                           fx=scale, fy=scale,
                                           interpolation=_cv.INTER_AREA)
                else:
                    txt_small = txt_region
                data = pytesseract.image_to_data(
                    txt_small, config='--psm 6 --oem 3',
                    output_type=pytesseract.Output.DICT)
                for i, word in enumerate(data['text']):
                    if not word.strip() or float(data['conf'][i]) < 20:
                        continue
                    wh = int(data['height'][i] / scale)
                    wy = int(data['top'][i] / scale) + contest_y0
                    # Similar height to reference (50%-180%)
                    if ref_h * 0.5 <= wh <= ref_h * 1.8:
                        text_line_ys.append(wy + wh // 2)
            except Exception:
                pass

        # Deduplicate text line Ys (cluster within th/2)
        text_line_ys.sort()
        deduped = []
        for y in text_line_ys:
            if not deduped or y - deduped[-1] > th // 2:
                deduped.append(y)
        text_line_ys = deduped

        # ── Step 2: for each text line Y, search indicator stripe ────────
        ind_x0 = max(0, tcx - tw)
        ind_x1 = min(w_img, tcx + tw)
        threshold = 192
        matches = []

        for line_y in text_line_ys:
            iy0 = max(0, line_y - th)
            iy1 = min(h_img, line_y + th)
            if iy1 <= iy0: continue

            stripe = self.gray[iy0:iy1, ind_x0:ind_x1]
            _, bin_s = cv2.threshold(stripe, threshold, 255,
                                      cv2.THRESH_BINARY_INV)
            dark_count = int(np.sum(bin_s > 0))

            if dark_count < 4:
                # No dark pixels — use geometric position based on template
                # (indicator may be very faint)
                cx_est = float(tcx)
                cy_est = float(line_y)
                half_tw = tw // 2; half_th = th // 2
                est_bbox = (tcx - half_tw, line_y - half_th,
                             tcx + half_tw, line_y + half_th)
                matches.append((cx_est, cy_est, est_bbox))
            else:
                # Find centroid of dark pixels
                bys, bxs = np.where(bin_s > 0)
                cx_loc = float(np.mean(bxs)) + ind_x0
                cy_loc = float(np.mean(bys)) + iy0
                half_tw = tw // 2; half_th = th // 2
                est_bbox = (int(cx_loc) - half_tw, int(cy_loc) - half_th,
                             int(cx_loc) + half_tw, int(cy_loc) + half_th)
                matches.append((cx_loc, cy_loc, est_bbox))

        # Exclude matches too close to each other
        min_gap = int(th * 0.5)
        filtered = []; last_y = -999
        for m in sorted(matches, key=lambda x: x[1]):
            if m[1] - last_y >= min_gap:
                filtered.append(m)
                last_y = m[1]

        return filtered

    def _get_text_side(self, ind_cx, contest):
        """Determine if text is to the right or left of indicators."""
        # Check which column the indicator is in
        for col in self.columns:
            if col['ul'][0] <= ind_cx <= col['lr'][0]:
                col_mid = (col['ul'][0]+col['lr'][0])/2
                return 'right' if ind_cx < col_mid else 'left'
        # Default: text to the right
        return 'right'

    def _get_col_bounds(self, ind_cx):
        """Get column ul/lr for the column containing ind_cx."""
        for col in self.columns:
            if col['ul'][0]-20 <= ind_cx <= col['lr'][0]+20:
                return col['ul'], col['lr']
        # Fallback: full image width
        return (0,0), (self.img_w, self.img_h)


    def _inset_indicators_dialog(self, ci):
        """
        After all indicators for a contest are captured, offer to inset
        each indicator bounding box by 1-5 pixels on each side.
        Shows a preview of the current bounding boxes.
        """
        contest = self.contests[ci]
        inds = contest.get('indicators', [])
        if not inds:
            return

        win = tk.Toplevel(self.root)
        win.title(f'Inset Indicators: {contest["title"]}')
        win.grab_set()
        win.transient(self.root)

        tk.Label(win,
                 text=f'Inset indicator bounding boxes for:\n'
                      f'"{contest["title"]}"\n\n'
                      f'{len(inds)} indicator(s) captured.\n'
                      f'Inset shrinks each box inward on all sides.',
                 justify='center', pady=8).pack()

        frame = tk.Frame(win)
        frame.pack(padx=12, pady=4)
        tk.Label(frame, text='Inset pixels (0 = no change):').grid(
            row=0, column=0, sticky='e', padx=4)
        inset_var = tk.IntVar(value=0)
        spinbox = tk.Spinbox(frame, from_=0, to=5,
                             textvariable=inset_var, width=4)
        spinbox.grid(row=0, column=1, sticky='w')

        # Style summary
        styles = {}
        for ind in inds:
            s = ind.get('style', 'oval')
            styles[s] = styles.get(s, 0) + 1
        style_str = ', '.join(f'{v} {k}' for k, v in styles.items())
        tk.Label(win, text=f'Styles detected: {style_str}',
                 fg='#006600').pack()

        result = {'inset': 0, 'ok': False}

        def apply_inset():
            result['inset'] = inset_var.get()
            result['ok'] = True
            win.destroy()

        bf = tk.Frame(win, bg='#1e1e2e'); bf.pack(pady=8)
        label_button(bf, 'Apply', apply_inset,
                     bg='#003300', fg='#aaffaa',
                     font=('Helvetica',11,'bold')).pack(side='left', padx=4)
        label_button(bf, 'Skip', win.destroy,
                     bg='#1a1a2a', fg='#aaaacc').pack(side='left', padx=4)
        self.root.wait_window(win)

        if result['ok'] and result['inset'] > 0:
            px = result['inset']
            for ind in inds:
                bb = ind.get('bbox')
                if bb:
                    x0,y0,x1,y1 = bb
                    ind['bbox'] = (x0+px, y0+px, x1-px, y1-px)
            self.status_var.set(
                f'Inset {len(inds)} indicators by {px}px in '
                f'"{contest["title"]}".')
            self._render()

    def _try_load_persisted(self):
        """v22: Load persisted layout from sidecar JSON, re-finding items."""
        lz, mk, cols = load_layout(self._img_path)
        if lz is None:
            return
        if self.gray is None:
            return
        print('Loading persisted layout...')
        lz2, mk2, cols2 = refind_from_persisted(
            self.gray, lz, mk, cols, self.dpi)
        self.layout_zones = lz2
        self.markers      = mk2
        self.columns      = cols2
        # Jump to contests phase (index 3) — markers+columns already done
        if self.markers or self.columns:
            self.phase_idx = 3   # contests phase
            print(f'  Restored: {len(lz2)} zones, '
                  f'{len(mk2)} markers, {len(cols2)} columns')
            print('  Skipping to contests phase — drag contest boxes or Enter to proceed.')

    def _save_layout(self):
        """v20: Save current layout zones/markers/columns to sidecar JSON."""
        if not self.markers and not self.columns and not self.layout_zones:
            return  # nothing to save yet
        path = save_layout(self._img_path, self.layout_zones,
                           self.markers, self.columns, self.dpi)
        if hasattr(self, 'status_var'):
            self.status_var.set(f'Layout saved: {path}')


    def _on_canvas_motion(self, event):
        """v22: Track which item is nearest cursor for nudge operations."""
        ix, iy = self._c2i(event.x, event.y)
        best_dist = float('inf')
        best_item = None

        # Check markers
        for i, (mx, my, _) in enumerate(self.markers):
            d = math.hypot(ix-mx, iy-my)
            if d < best_dist:
                best_dist = d; best_item = ('marker', i)

        # Check layout zones (centre of bbox)
        for i, lz in enumerate(self.layout_zones):
            zx = (lz['ul'][0]+lz['lr'][0])/2
            zy = (lz['ul'][1]+lz['lr'][1])/2
            d = math.hypot(ix-zx, iy-zy)
            if d < best_dist:
                best_dist = d; best_item = ('zone', i)

        # Check columns (centre of bbox)
        for i, col in enumerate(self.columns):
            cx_ = (col['ul'][0]+col['lr'][0])/2
            cy_ = (col['ul'][1]+col['lr'][1])/2
            d = math.hypot(ix-cx_, iy-cy_)
            if d < best_dist:
                best_dist = d; best_item = ('column', i)

        # Only claim hover if within 0.5" of something
        self._hovered_item = best_item if best_dist < 0.5*self.dpi else None
        if self._hovered_item:
            self.status_var.set(
                f'Hover: {self._hovered_item[0]} #{self._hovered_item[1]+1} '
                f'— arrows=move  S=grow  s=shrink')

    def _on_canvas_nudge(self, event):
        """v22: Nudge or resize the hovered item by 1 image pixel."""
        item = self._hovered_item
        if item is None: return
        kind, idx = item
        k = event.keysym
        if k not in ('Left','Right','Up','Down','S','s'): return

        def move_point(px, py):
            if k=='Left':  return px-1, py
            if k=='Right': return px+1, py
            if k=='Up':    return px, py-1
            if k=='Down':  return px, py+1
            return px, py

        def move_bbox(ul, lr):
            """Move or resize a bbox."""
            x0,y0 = ul; x1,y1 = lr
            if k=='Left':  x0-=1; x1-=1
            elif k=='Right':x0+=1; x1+=1
            elif k=='Up':   y0-=1; y1-=1
            elif k=='Down': y0+=1; y1+=1
            elif k=='S':    x0-=1; y0-=1; x1+=1; y1+=1
            elif k=='s':
                if x1-x0>4 and y1-y0>4:
                    x0+=1; y0+=1; x1-=1; y1-=1
            return (x0,y0),(x1,y1)

        if kind=='marker' and idx < len(self.markers):
            mx,my,bbox = self.markers[idx]
            mx,my = move_point(mx,my)
            if bbox:
                bx0,by0,bx1,by1 = bbox
                bx0,by0 = move_point(bx0,by0)
                bx1,by1 = move_point(bx1,by1)
                bbox = (bx0,by0,bx1,by1)
            self.markers[idx] = (mx,my,bbox)

        elif kind=='zone' and idx < len(self.layout_zones):
            lz = self.layout_zones[idx]
            ul,lr = move_bbox(lz['ul'],lz['lr'])
            self.layout_zones[idx] = {'label':lz['label'],'ul':ul,'lr':lr}

        elif kind=='column' and idx < len(self.columns):
            col = self.columns[idx]
            ul,lr = move_bbox(col['ul'],col['lr'])
            self.columns[idx] = {'ul':ul,'lr':lr}

        self._render()

    def _cycle_contest(self, direction):
        """C = next contest, c = previous contest (in indicator phase)."""
        if PHASES[self.phase_idx][0] != 'indicators':
            return
        if not self.contests:
            return
        self._cap_contest_idx = (
            (self._cap_contest_idx + direction) % len(self.contests))
        t = self.contests[self._cap_contest_idx]['title']
        n_inds = len(self.contests[self._cap_contest_idx]['indicators'])
        self.status_var.set(
            f'Contest {self._cap_contest_idx+1}/{len(self.contests)}: '
            f'"{t}"  ({n_inds} indicator(s) so far)')
        self._update_phase()

    def _advance_contest(self):
        """Move to the next contest needing indicators."""
        next_ci = None
        for ci in range(self._cap_contest_idx+1, len(self.contests)):
            if not self.contests[ci]['indicators']:
                next_ci = ci; break
        if next_ci is not None:
            self._cap_contest_idx = next_ci
            t = self.contests[next_ci]['title']
            self.status_var.set(
                f'Now drag an indicator for contest: "{t}"')
            self._update_phase()
        else:
            # All contests have indicators
            if messagebox.askyesno('Done',
                    'All contests have indicators. Save and quit?',
                    parent=self.root):
                self._save_quit()

    # ── Helpers ───────────────────────────────────────────────────────────────

    def _ask_contest_type(self):
        win=tk.Toplevel(self.root); win.title('Contest Type'); win.grab_set()
        var=tk.StringVar(value='PLURALITY')
        for v,l in [('PLURALITY','Plurality'),
                     ('RANKED_CHOICE','Ranked Choice'),
                     ('YES_NO','Yes / No')]:
            tk.Radiobutton(win,text=l,variable=var,value=v
                           ).pack(anchor='w',padx=12,pady=2)
        label_button(win, 'OK', win.destroy,
                     bg='#003300', fg='#aaffaa',
                     font=('Helvetica',11,'bold')).pack(pady=6)
        self.root.wait_window(win)
        return var.get()

    def _confirm(self,description):
        self.status_var.set(f'Confirm: {description}')
        return messagebox.askyesno('Confirm',f'Accept?\n\n{description}',
                                    parent=self.root)

    # ── Zoom ──────────────────────────────────────────────────────────────────

    def _zoom_in(self):
        self.zoom=min(8.0,self.zoom*1.25); self._render()
    def _zoom_out(self):
        self.zoom=max(0.1,self.zoom/1.25); self._render()
    def _zoom_fit(self):
        cw=self.canvas.winfo_width() or 900
        ch=self.canvas.winfo_height() or 700
        if cw<10 or ch<10: return
        self.zoom=min(cw/self.img_w,ch/self.img_h)*0.97
        self._render()

    # ── Phase ─────────────────────────────────────────────────────────────────

    def _update_phase(self):
        name,label,instr=PHASES[self.phase_idx]
        self.phase_var.set(f'Phase: {label}')
        msg=instr
        if name=='indicators' and self.contests:
            ci=self._cap_contest_idx
            if ci<len(self.contests):
                ct=self.contests[ci]
                n_inds=len(ct.get('indicators',[]))
                msg=(f'Drag over ONE indicator in the current contest.\n'
                     f'C = next contest  |  c = previous contest  |  Enter = done\n'
                     f'\n'
                     f'► Contest {ci+1}/{len(self.contests)}: '
                     f'"{ct["title"]}"  '
                     f'({n_inds} indicator(s) captured)')
        self.instr_var.set(msg)
        self._render()

    def _next_phase(self):
        phase=PHASES[self.phase_idx][0]
        if phase=='indicators':
            # In indicators phase, Enter advances to next contest
            # rather than leaving the phase entirely
            self._advance_contest()
            return
        if phase=='markers' and len(self.markers)<2:
            if not messagebox.askyesno('Few markers',
                    f'{len(self.markers)} marker(s). Continue?'): return
        elif phase=='columns' and not self.columns:
            if not messagebox.askyesno('No columns',
                    'No columns. Continue?'): return
        elif phase=='contests' and not self.contests:
            if not messagebox.askyesno('No contests',
                    'No contests. Continue?'): return
        if self.phase_idx<len(PHASES)-1:
            self.phase_idx+=1
            if PHASES[self.phase_idx][0]=='indicators':
                self._cap_contest_idx=0
                self._cap_rank=1
                # v20: auto-save layout when reaching indicator phase
                if self.markers or self.columns:
                    self._save_layout()
            self._update_phase()
        else:
            self._save_quit()

    def _undo(self):
        phase=PHASES[self.phase_idx][0]
        if phase=='layout' and self.layout_zones:
            self.layout_zones.pop()
            self.status_var.set('Undone last layout zone')
        elif phase=='markers' and self.markers:
            self.markers.pop()
            self.status_var.set('Undone last marker')
        elif phase=='columns' and self.columns:
            self.columns.pop()
            self.status_var.set('Undone last column')
        elif phase=='contests' and self.contests:
            self.contests.pop()
            self._cap_contest_idx=max(0,len(self.contests)-1)
            self.status_var.set('Undone last contest')
        elif phase=='indicators':
            for ci in range(len(self.contests)-1,-1,-1):
                if self.contests[ci]['indicators']:
                    self.contests[ci]['indicators'].pop()
                    self.status_var.set('Undone last indicator')
                    break
        self._snap_lines=[]
        self._highlight_bboxes=[]
        self._render()

    # ── YAML ──────────────────────────────────────────────────────────────────

    def _px(self,v): return round(v/self.dpi,4)

    def _save_quit(self):
        yaml=self._generate_yaml()
        with open(self.out_path,'w') as f: f.write(yaml)
        print(f'\nSaved: {self.out_path}')
        self._destroyed = True
        self.root.destroy()

    def _generate_yaml(self):
        """
        Generate YAML conforming to bBuilder's format so bCounter can
        read it without modification via BboxReportLoader.loadYaml().

        Key differences from a native bBuilder YAML:
        - contest/candidate IDs are sequential integers (no DB backing)
        - barcodeCentre derived from layout zones labelled 'barcode' or 'qr'
        - cornerMarks from orientation markers (TL/TR/BR/BL order)
        - pageMarks omitted (not captured by mapper; CornerDetectionService
          falls back to YAML hints if pageMarks absent)
        - layoutZones written as YAML comments for human reference only
        """
        # Content area bounds from columns, else contests, else full image
        if self.columns:
            ca_x  = min(c['ul'][0] for c in self.columns)
            ca_y  = min(c['ul'][1] for c in self.columns)
            ca_x2 = max(c['lr'][0] for c in self.columns)
            ca_y2 = max(c['lr'][1] for c in self.columns)
        elif self.contests:
            ca_x  = min(c['ul'][0] for c in self.contests)
            ca_y  = min(c['ul'][1] for c in self.contests)
            ca_x2 = max(c['lr'][0] for c in self.contests)
            ca_y2 = max(c['lr'][1] for c in self.contests)
        else:
            ca_x = ca_y = 0
            ca_x2 = self.img_w; ca_y2 = self.img_h

        # Derive barcode centre from layout zones
        bc_x = bc_y = None
        for lz in self.layout_zones:
            lbl = lz.get('label', '').lower()
            if any(kw in lbl for kw in ('qr', 'barcode', 'bar code')):
                ul = lz['ul']; lr = lz['lr']
                bc_x = (ul[0] + lr[0]) / 2
                bc_y = (ul[1] + lr[1]) / 2
                break

        L = [
            '# pbss ballot template YAML',
            f'# Generated by ballot_mapper.py v{VERSION}',
            f'# Source image: {self.img_w}x{self.img_h}px at {self.dpi} DPI',
            '# IDs are sequential — no pbss database backing.',
            '# Populate the database from this file before scanning.',
            '',
        ]

        # Layout zones as comments (informational only)
        if self.layout_zones:
            L.append('# Layout zones (vendor-specific identifiers):')
            for lz in self.layout_zones:
                ul=lz['ul']; lr=lz['lr']
                L.append(f'#   {lz["label"]}: '
                         f'({self._px(ul[0])}", {self._px(ul[1])}") '
                         f'-> ({self._px(lr[0])}", {self._px(lr[1])}")')
            L.append('')

        L += [
            'sides:',
            '  - side_number: 1',
            f'    page_width:  "{self._px(self.img_w)}"',
            f'    page_height: "{self._px(self.img_h)}"',
            '',
            '    ballotContentArea:',
            f'      offsetFromLeft: "{self._px(ca_x)}"',
            f'      offsetFromTop:  "{self._px(ca_y)}"',
            f'      width:          "{self._px(ca_x2 - ca_x)}"',
            f'      height:         "{self._px(ca_y2 - ca_y)}"',
            '',
        ]

        # Barcode centre
        if bc_x is not None:
            L += [
                '    barcodeCentre:',
                f'      x: "{self._px(bc_x)}"',
                f'      y: "{self._px(bc_y)}"',
                '',
            ]

        # Corner marks (orientation markers → TL/TR/BR/BL)
        if self.markers:
            L.append('    cornerMarks:')
            labels = ['TL', 'TR', 'BR', 'BL']
            for i, (mx, my, _) in enumerate(self.markers):
                lbl = labels[i] if i < 4 else f'M{i+1}'
                L += [
                    f'      - corner: {lbl}',
                    f'        x: "{self._px(mx)}"',
                    f'        y: "{self._px(my)}"',
                ]
            L.append('')

        # Contests
        L.append('    contests:')
        for ci, contest in enumerate(self.contests):
            ul = contest['ul']; lr = contest['lr']
            # Contest bounding box relative to content area
            c_left = self._px(ul[0] - ca_x)
            c_top  = self._px(ul[1] - ca_y)
            c_w    = self._px(lr[0] - ul[0])
            c_h    = self._px(lr[1] - ul[1])

            L += [
                f'      - id: {ci + 1}',
                f'        title: {contest["title"]!r}',
                f'        contestType: {contest["contest_type"]}',
                f'        maxVotes: {contest["max_votes"]}',
                f'        boundingBox:',
                f'          offsetFromLeft: "{c_left}"',
                f'          offsetFromTop:  "{c_top}"',
                f'          width:          "{c_w}"',
                f'          height:         "{c_h}"',
                f'        candidates:',
            ]

            # Group indicators by candidate name to deduplicate RCV ranks
            # Each unique (name, write_in) becomes one candidate entry
            # with one indicator per rank
            seen_names = {}   # name -> candidate index
            cand_list  = []   # [{name, write_in, indicators:[]}]
            for ind in contest.get('indicators', []):
                key = ind['candidate']
                if key not in seen_names:
                    seen_names[key] = len(cand_list)
                    cand_list.append({
                        'name':     ind['candidate'],
                        'write_in': ind['write_in'],
                        'indicators': []
                    })
                cand_list[seen_names[key]]['indicators'].append(ind)

            for cand_idx, cand in enumerate(cand_list):
                L += [
                    f'          - id: {ci * 1000 + cand_idx + 1}',
                    f'            name: {cand["name"]!r}',
                    f'            writeIn: {"true" if cand["write_in"] else "false"}',
                ]
                # For non-RCV or rank-1: write the primary indicator
                # For RCV: write indicator for each rank
                inds = cand['indicators']
                if len(inds) == 1 or contest['contest_type'] != 'RANKED_CHOICE':
                    ind = inds[0]
                    bb  = ind.get('bbox')
                    if bb:
                        x0,y0,x1,y1 = bb
                        L += [
                            f'            indicator:',
                            f'              offsetFromLeft: "{self._px(x0 - ca_x)}"',
                            f'              offsetFromTop:  "{self._px(y0 - ca_y)}"',
                            f'              width:          "{self._px(x1 - x0)}"',
                            f'              height:         "{self._px(y1 - y0)}"',
                            f'              indicatorStyle: '
                            f'{ind.get("style", "OVAL").upper()}',
                        ]
                else:
                    # RCV: multiple indicators per candidate
                    # Write primary (rank 1) as 'indicator', others as 'rankIndicators'
                    rank1 = next((i for i in inds if i.get('rank',1)==1), inds[0])
                    bb = rank1.get('bbox')
                    if bb:
                        x0,y0,x1,y1 = bb
                        L += [
                            f'            indicator:',
                            f'              offsetFromLeft: "{self._px(x0 - ca_x)}"',
                            f'              offsetFromTop:  "{self._px(y0 - ca_y)}"',
                            f'              width:          "{self._px(x1 - x0)}"',
                            f'              height:         "{self._px(y1 - y0)}"',
                            f'              indicatorStyle: '
                            f'{rank1.get("style","OVAL").upper()}',
                        ]
                    # Additional ranks
                    other_ranks = [i for i in inds if i != rank1]
                    if other_ranks:
                        L.append(f'            rankIndicators:')
                        for ri in sorted(other_ranks,
                                         key=lambda x: x.get('rank', 2)):
                            rbb = ri.get('bbox')
                            if rbb:
                                rx0,ry0,rx1,ry1 = rbb
                                L += [
                                    f'              - rank: {ri.get("rank",2)}',
                                    f'                offsetFromLeft: '
                                    f'"{self._px(rx0 - ca_x)}"',
                                    f'                offsetFromTop:  '
                                    f'"{self._px(ry0 - ca_y)}"',
                                    f'                width:  "{self._px(rx1-rx0)}"',
                                    f'                height: "{self._px(ry1-ry0)}"',
                                ]
            L.append('')

        return '\n'.join(L) + '\n'


# ── Entry point ────────────────────────────────────────────────────────────────

def main():
    p=argparse.ArgumentParser(description='Ballot Mapper v3')
    p.add_argument('image')
    p.add_argument('--out',default='ballot_template.yaml')
    p.add_argument('--dpi',type=int,default=None)
    args=p.parse_args()
    dpi=args.dpi
    if dpi is None and PIL_AVAILABLE:
        try:
            pil=Image.open(args.image)
            d=pil.info.get('dpi')
            if d and d[0]>50: dpi=int(round(d[0]))
        except Exception as e:
            print(f'DPI read error: {e}')
    dpi=dpi or 300
    root=tk.Tk()
    root.geometry('1300x900')
    BallotMapper(root,args.image,dpi,args.out)
    root.mainloop()

if __name__=='__main__':
    main()
