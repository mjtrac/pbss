#!/usr/bin/env python3
"""
distort_ballots.py — Apply geometric distortions to marked ballot PNGs.

For each marked PNG, produces variants:
  clean           — no distortion (baseline)
  rot_cw_1        — rotated 1° clockwise
  rot_cw_1_5      — rotated 1.5° clockwise
  rot_ccw_1       — rotated 1° counter-clockwise
  rot_ccw_1_5     — rotated 1.5° counter-clockwise
  trans_up        — translated up ¼"
  trans_down      — translated down ¼"
  trans_left      — translated left ¼"
  trans_right     — translated right ¼"
  rot_cw1_trans   — 1° CW + ¼" right translation
  rot_ccw1_trans  — 1° CCW + ¼" up translation
  skew_right      — affine shear (right lean)
  skew_left       — affine shear (left lean)
  perspective     — mild perspective warp (as if page not flat on scanner)
  upside_down     — 180° rotation (tests auto-detection)

All variants add white padding so the original ballot content stays within
the image bounds after transformation.

Usage: python distort_ballots.py [--in-dir marked_ballots] [--out-dir images]
                                  [--dpi 300] [--copies N]
"""
import argparse, json, math, shutil
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

DPI = 300

def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--in-dir",   default="marked_ballots")
    p.add_argument("--out-dir",  default="images")
    p.add_argument("--dpi",      type=int, default=300)
    p.add_argument("--distortions", default=None,
                   help="Comma-separated list of distortion names to apply (default: all)")
    p.add_argument("--copies",   type=int, default=1,
                   help="How many copies of each variant to produce")
    p.add_argument("--gt-in",    default="marked_ballots/ground_truth.json")
    p.add_argument("--gt-out",   default="images/ground_truth_all.json")
    return p.parse_args()

# ── Geometric transforms ──────────────────────────────────────────────────────

def pad_image(img: np.ndarray, pad_px: int) -> tuple[np.ndarray, tuple]:
    """Add white padding around an image. Returns (padded, (top, left))."""
    h, w = img.shape[:2]
    out = np.full((h + 2*pad_px, w + 2*pad_px, 3), 255, dtype=np.uint8)
    out[pad_px:pad_px+h, pad_px:pad_px+w] = img
    return out, (pad_px, pad_px)

def rotate_image(img: np.ndarray, degrees: float, dpi: int) -> np.ndarray:
    """Rotate image by degrees (positive=CW) with white background fill."""
    pad = int(dpi * 0.25)  # ¼" padding — stays within corner detection tolerance
    padded, _ = pad_image(img, pad)
    h, w = padded.shape[:2]
    cx, cy = w / 2, h / 2
    M = cv2.getRotationMatrix2D((cx, cy), -degrees, 1.0)
    return cv2.warpAffine(padded, M, (w, h),
                          flags=cv2.INTER_LINEAR,
                          borderValue=(255, 255, 255))

def translate_image(img: np.ndarray, dx_in: float, dy_in: float,
                    dpi: int) -> np.ndarray:
    """Translate image by (dx_in, dy_in) inches. Positive = right/down."""
    dx = int(dx_in * dpi)
    dy = int(dy_in * dpi)
    pad = int(dpi * 0.1)   # minimal padding — translation is 0.25", total shift 0.35"
    padded, _ = pad_image(img, pad)
    h, w = padded.shape[:2]
    M = np.float32([[1, 0, dx], [0, 1, dy]])
    return cv2.warpAffine(padded, M, (w, h), borderValue=(255, 255, 255))

def rotate_and_translate(img: np.ndarray, degrees: float,
                          dx_in: float, dy_in: float, dpi: int) -> np.ndarray:
    img2 = rotate_image(img, degrees, dpi)
    return translate_image(img2, dx_in, dy_in, dpi)

def skew_image(img: np.ndarray, shear_x: float = 0.0,
               shear_y: float = 0.0, dpi: int = 300) -> np.ndarray:
    """Apply an affine shear. shear_x/shear_y are small fractions (e.g. 0.05)."""
    pad = int(80 * dpi / 300)  # ~0.27" at any DPI
    padded, _ = pad_image(img, pad)
    h, w = padded.shape[:2]
    M = np.float32([[1, shear_x, 0], [shear_y, 1, 0]])
    new_w = int(w + abs(shear_x) * h)
    new_h = int(h + abs(shear_y) * w)
    return cv2.warpAffine(padded, M, (new_w, new_h), borderValue=(255, 255, 255))

def perspective_warp(img: np.ndarray, strength: float = 0.015,
                     dpi: int = 300) -> np.ndarray:
    """
    Mild perspective warp simulating a document not lying perfectly flat.
    strength: fraction of image dimension to warp corners by.
    pad is scaled by DPI so the physical padding is constant regardless
    of image resolution.
    """
    pad = int(80 * dpi / 300)  # ~0.27" at any DPI
    padded, _ = pad_image(img, pad)
    h, w = padded.shape[:2]
    dx = int(w * strength)
    dy = int(h * strength)
    src = np.float32([[0,0],[w,0],[w,h],[0,h]])
    dst = np.float32([[dx, dy],[w-dx//2, 0],[w, h-dy],[0+dx//3, h]])
    M = cv2.getPerspectiveTransform(src, dst)
    return cv2.warpPerspective(padded, M, (w, h), borderValue=(255, 255, 255))

def upside_down(img: np.ndarray) -> np.ndarray:
    return cv2.rotate(img, cv2.ROTATE_180)

# ── Distortion table ─────────────────────────────────────────────────────────

def get_distortions(dpi: int) -> dict:
    QUARTER_INCH = 0.25
    return {
        "clean":          lambda img: img,
        "rot_cw_1":       lambda img: rotate_image(img,  1.0, dpi),
        "rot_cw_1_5":     lambda img: rotate_image(img,  1.5, dpi),
        "rot_cw_2_0":     lambda img: rotate_image(img,  2.0, dpi),
        "rot_ccw_1":      lambda img: rotate_image(img, -1.0, dpi),
        "rot_ccw_1_5":    lambda img: rotate_image(img, -1.5, dpi),
        "rot_ccw_2_0":     lambda img: rotate_image(img,  -2.0, dpi),
        "trans_up":       lambda img: translate_image(img, 0, -QUARTER_INCH, dpi),
        "trans_down":     lambda img: translate_image(img, 0,  QUARTER_INCH, dpi),
        "trans_left":     lambda img: translate_image(img, -QUARTER_INCH, 0, dpi),
        "trans_right":    lambda img: translate_image(img,  QUARTER_INCH, 0, dpi),
        "rot_cw1_trans":  lambda img: rotate_and_translate(img, 1.0,  QUARTER_INCH, 0, dpi),
        "rot_ccw1_trans": lambda img: rotate_and_translate(img,-1.0,  0, -QUARTER_INCH, dpi),
        "skew_right":     lambda img: skew_image(img, shear_x= 0.015),
        "skew_left":      lambda img: skew_image(img, shear_x=-0.015),
        "perspective":    lambda img: perspective_warp(img, strength=0.012, dpi=dpi),
        "upside_down":    lambda img: upside_down(img),
    }

# ── Folder structure ──────────────────────────────────────────────────────────
#
# images/
#   precinct-1/
#     clean/
#     rotated/
#     translated/
#     skewed/
#     perspective/
#     upside_down/
#   precinct-2/ ...

DIST_FOLDERS = {
    "clean":          "clean",
    "rot_cw_1":       "rotated",
    "rot_cw_1_5":     "rotated",
    "rot_ccw_1":      "rotated",
    "rot_ccw_1_5":    "rotated",
    "rot_cw_2_0":     "rotated2",
    "rot_ccw_2_0":    "rotated2",
    "trans_up":       "translated",
    "trans_down":     "translated",
    "trans_left":     "translated",
    "trans_right":    "translated",
    "rot_cw1_trans":  "rotated",
    "rot_ccw1_trans": "rotated",
    "skew_right":     "skewed",
    "skew_left":      "skewed",
    "perspective":    "perspective",
    "upside_down":    "upside_down",
}

def main():
    args = parse_args()
    dpi  = args.dpi
    in_dir  = Path(args.in_dir)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    distortions = get_distortions(dpi)

    # Load per-image ground truth from mark_ballots step
    with open(args.gt_in) as f:
        src_gt = json.load(f)

    all_gt = {}        # per-image ground truth for distorted images
    agg_gt: dict = {}  # aggregate counts: combo_id -> contest -> candidate -> count

    total = 0
    src_pngs = sorted(in_dir.rglob("*.png"))
    print(f"Found {len(src_pngs)} marked PNGs in {in_dir}")

    for src_path in src_pngs:
        gt_entry = src_gt.get(str(src_path))
        if gt_entry is None:
            # Try relative path match
            for k, v in src_gt.items():
                if Path(k).name == src_path.name:
                    gt_entry = v
                    break

        precinct_folder = src_path.parent.name  # e.g. "Precinct_1"

        # Load image as OpenCV array
        pil_img = Image.open(src_path).convert("RGB")
        cv_img  = cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)
        for dist_name, dist_fn in distortions.items():
            sub_folder = DIST_FOLDERS[dist_name]
            dest_dir = out_dir / precinct_folder / sub_folder
            dest_dir.mkdir(parents=True, exist_ok=True)

            for copy_idx in range(1, args.copies + 1):
                suffix = f"__{dist_name}__c{copy_idx:02d}.png"
                out_name = src_path.stem + suffix
                out_path = dest_dir / out_name

                distorted = dist_fn(cv_img)
                # Save with DPI metadata so bCounter's heuristic is not needed
                from PIL import Image as PilImage
                rgb = cv2.cvtColor(distorted, cv2.COLOR_BGR2RGB)
                pil_img = PilImage.fromarray(rgb)
                pil_img.save(str(out_path), dpi=(args.dpi, args.dpi))

                # Ground truth for this distorted copy
                if gt_entry:
                    all_gt[str(out_path)] = {
                        **gt_entry,
                        "distortion":  dist_name,
                        "copy":        copy_idx,
                        "source_png":  str(src_path),
                    }
                    # Accumulate aggregate
                    combo_id = gt_entry.get("combo_id", "unknown")
                    key = str(combo_id)
                    if key not in agg_gt:
                        agg_gt[key] = {
                            "precinct": gt_entry.get("precinct"),
                            "party":    gt_entry.get("party"),
                            "contests": {}
                        }
                    for ind in gt_entry.get("indicators", []):
                        if ind.get("counted_as") != "VOTED":
                            continue
                        c = ind["contest"]
                        cand = ind["candidate"]
                        agg_gt[key]["contests"].setdefault(c, {})
                        agg_gt[key]["contests"][c][cand] = (
                            agg_gt[key]["contests"][c].get(cand, 0) + 1
                        )
                total += 1

        if total % 50 == 0:
            print(f"  ... {total} distorted images written")

    print(f"\n── Total distorted images: {total}")

    # Write combined ground truth
    gt_out = Path(args.gt_out)
    gt_out.parent.mkdir(parents=True, exist_ok=True)
    with open(gt_out, "w") as f:
        json.dump({
            "per_image":    all_gt,
            "aggregate":    agg_gt,
            "total_images": total,
        }, f, indent=2)
    print(f"── Ground truth written to {gt_out}")

if __name__ == "__main__":
    main()
