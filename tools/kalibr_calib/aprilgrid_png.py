"""Draw Kalibr's AprilGrid as a PNG that fills a screen, for showing full screen (F11 / slideshow).

Same tags, codes and layout as `kalibr_create_target_pdf` (whose code tables it imports), drawn
on whole pixels: a tag is 10 bits (6 data + 2 border bits each side), the gap 0.3 x tag = 3 bits.
Runs inside the Kalibr image (needs numpy and PIL). Writes target.png and prints how to measure.

    python3 aprilgrid_png.py --cols 6 --rows 4 --screen 1920x1080 --out target.png [--check]
"""
import argparse
import importlib.machinery
import importlib.util
import sys

import numpy as np
from PIL import Image

KALIBR_PDF_TOOL = "/catkin_ws/src/kalibr/aslam_offline_calibration/kalibr/python/kalibr_create_target_pdf"
TAG_BITS, BORDER_BITS, GAP_BITS = 10, 2, 3   # gap = tagSpacing 0.3 * 10 bits


def kalibr_codes():
    sys.modules.setdefault("pyx", type(sys)("pyx"))   # the tool imports pyx; we only need its codes
    loader = importlib.machinery.SourceFileLoader("kalibr_target_pdf", KALIBR_PDF_TOOL)
    spec = importlib.util.spec_from_loader(loader.name, loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod.AprilTagCodes("t36h11").tagCodes


def draw(cols, rows, width, height):
    codes = kalibr_codes()
    pat_w = cols * TAG_BITS + (cols + 1) * GAP_BITS   # incl. the corner squares on the outside
    pat_h = rows * TAG_BITS + (rows + 1) * GAP_BITS
    margin = 0.03 * min(width, height)
    bit = int(min((width - 2 * margin) / pat_w, (height - 2 * margin) / pat_h))
    if bit < 2:
        raise SystemExit("screen too small for %dx%d tags" % (cols, rows))
    img = np.full((height, width), 255, np.uint8)
    x0 = (width - pat_w * bit) // 2
    y0 = (height - pat_h * bit) // 2

    def fill(bx, by, bw, bh):   # bit coordinates, y UP from the pattern's bottom (as in the PDF)
        x, y = x0 + bx * bit, y0 + (pat_h - by - bh) * bit
        img[y:y + bh * bit, x:x + bw * bit] = 0

    for r in range(rows):
        for c in range(cols):
            tag_id = cols * r + c
            tx = GAP_BITS + c * (TAG_BITS + GAP_BITS)
            ty = GAP_BITS + r * (TAG_BITS + GAP_BITS)
            fill(tx, ty, TAG_BITS, BORDER_BITS)                           # border: bottom, top,
            fill(tx, ty + TAG_BITS - BORDER_BITS, TAG_BITS, BORDER_BITS)  # left, right
            fill(tx, ty, BORDER_BITS, TAG_BITS)
            fill(tx + TAG_BITS - BORDER_BITS, ty, BORDER_BITS, TAG_BITS)
            code = codes[tag_id]
            m = np.zeros((6, 6))
            for i in range(6):
                for j in range(6):
                    if not code & (1 << (6 * i + j)):
                        m[i, j] = 1
            m = np.rot90(m, 2)
            for i in range(6):
                for j in range(6):
                    if m[i, j]:
                        fill(tx + j + BORDER_BITS, ty + (BORDER_BITS - 1) + 6 - i, 1, 1)
            for dx, dy in [(-GAP_BITS, -GAP_BITS), (TAG_BITS, -GAP_BITS),
                           (TAG_BITS, TAG_BITS), (-GAP_BITS, TAG_BITS)]:
                fill(tx + dx, ty + dy, GAP_BITS, GAP_BITS)                 # symmetric corners
    return img, bit, pat_w, pat_h


def check(img, cols, rows):
    """Detect the grid with Kalibr's own detector; returns (corners found, corners expected)."""
    import aslam_cv as acv
    import aslam_cameras_april as acv_april
    opts = acv_april.AprilgridOptions()
    opts.showExtractionVideo = False
    opts.minTagsForValidObs = 1
    target = acv_april.GridCalibrationTargetAprilgrid(rows, cols, 0.03, 0.3, opts)
    geom = acv.DistortedPinholeCameraGeometry()   # any camera: only the 2D detection is used
    dopts = acv.GridDetectorOptions()
    dopts.filterCornerOutliers = False
    detector = acv.GridDetector(geom, target, dopts)
    ok, obs = detector.findTargetNoTransformation(acv.Time(0.0), np.array(img))
    if not ok:
        return 0, 4 * cols * rows
    return len(obs.getCornersIdx()), 4 * cols * rows


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--cols", type=int, default=6)
    p.add_argument("--rows", type=int, default=4)
    p.add_argument("--screen", default="1920x1080")
    p.add_argument("--out", default="target.png")
    p.add_argument("--check", action="store_true", help="detect it with Kalibr's detector")
    a = p.parse_args()
    w, h = (int(v) for v in a.screen.lower().split("x"))
    img, bit, pat_w, pat_h = draw(a.cols, a.rows, w, h)
    Image.fromarray(img).save(a.out)
    across, up = pat_w / TAG_BITS, pat_h / TAG_BITS
    print("wrote %s (%dx%d px, %d px per bit, tag = %d px)" % (a.out, w, h, bit, bit * TAG_BITS))
    print("MEASURE outermost black edge to outermost black edge (the small corner squares):")
    print("  across = %.1f tags, up = %.1f tags  ->  tag size = across / %.1f  (check: up / %.1f)"
          % (across, up, across, up))
    if a.check:
        found, expected = check(img, a.cols, a.rows)
        print("Kalibr detector: %d of %d corners" % (found, expected))
        if found != expected:
            sys.exit(1)


if __name__ == "__main__":
    main()
