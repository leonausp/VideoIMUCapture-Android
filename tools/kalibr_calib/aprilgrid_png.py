"""Draw Kalibr's AprilGrid with measuring instructions printed on it.

- PNG for a screen (shown full screen, F11 / slideshow): the size depends on the screen, so the
  caption says how to MEASURE it.
- PDF for paper (A4 landscape, printed at actual size): the caption gives the exact sizes.

Same tags, codes and layout as Kalibr's `kalibr_create_target_pdf` (whose code tables it imports),
drawn on whole pixels: a tag is 10 bits (6 data + 2 border bits each side), the gap 0.3 x tag =
3 bits. Runs inside the Kalibr image (numpy, PIL, Kalibr's detector for --check).

    python3 aprilgrid_png.py --screen 1920x1080 --out target.png [--pdf target.pdf --tag-size 0.03] [--check]
"""
import argparse
import importlib.machinery
import importlib.util
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

KALIBR_PDF_TOOL = "/catkin_ws/src/kalibr/aslam_offline_calibration/kalibr/python/kalibr_create_target_pdf"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
TAG_BITS, BORDER_BITS, GAP_BITS = 10, 2, 3   # gap = tagSpacing 0.3 * 10 bits
A4_MM = (297.0, 210.0)                       # landscape


def kalibr_codes():
    sys.modules.setdefault("pyx", type(sys)("pyx"))   # the tool imports pyx; we only need its codes
    loader = importlib.machinery.SourceFileLoader("kalibr_target_pdf", KALIBR_PDF_TOOL)
    spec = importlib.util.spec_from_loader(loader.name, loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod.AprilTagCodes("t36h11").tagCodes


def pattern_bits(cols, rows):
    """Pattern size in bits, including the corner squares on the outside."""
    return cols * TAG_BITS + (cols + 1) * GAP_BITS, rows * TAG_BITS + (rows + 1) * GAP_BITS


def draw_grid(img, x0, y0, bit, cols, rows):
    """Draw the grid into img (uint8, white) with its top-left outer corner at (x0, y0)."""
    codes = kalibr_codes()
    _, pat_h = pattern_bits(cols, rows)

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


def caption(pil, lines, top, height):
    """Centred text lines in the band [top, top + height)."""
    d = ImageDraw.Draw(pil)
    size = max(8, int(height / (1.45 * len(lines))))
    font = ImageFont.truetype(FONT, size)
    while size > 8 and max(d.textsize(t, font=font)[0] for t in lines) > 0.96 * pil.width:
        size -= 1
        font = ImageFont.truetype(FONT, size)
    y = top + (height - 1.3 * size * len(lines)) / 2
    for t in lines:
        w, _ = d.textsize(t, font=font)
        d.text(((pil.width - w) / 2, y), t, fill=0, font=font)
        y += 1.3 * size


def screen_png(cols, rows, width, height):
    pat_w, pat_h = pattern_bits(cols, rows)
    across, up = pat_w / TAG_BITS, pat_h / TAG_BITS
    margin = 0.03 * min(width, height)
    band = 0.10 * height                       # caption at the bottom
    bit = int(min((width - 2 * margin) / pat_w, (height - 2 * margin - band) / pat_h))
    if bit < 2:
        raise SystemExit("screen too small for %dx%d tags" % (cols, rows))
    img = np.full((height, width), 255, np.uint8)
    x0 = (width - pat_w * bit) // 2
    y0 = int(margin + (height - 2 * margin - band - pat_h * bit) / 2)
    draw_grid(img, x0, y0, bit, cols, rows)
    pil = Image.fromarray(img)
    ex_w = 26.0
    caption(pil, [
        "On a screen the size depends on the screen: MEASURE it. W = width from the outermost black "
        "edge on the left to the outermost black edge on the right.",
        "Tag size (one big square, black border included) = W / %.1f   (check: height / %.1f).   "
        "Example: W = %.1f cm  ->  %.2f cm  ->  --tag-size %.4f"
        % (across, up, ex_w, ex_w / across, ex_w / across / 100),
    ], y0 + pat_h * bit + margin / 2, height - (y0 + pat_h * bit) - margin / 2)
    return pil, bit


def paper_pdf(cols, rows, tag_m, bit_px=50):
    """A4 landscape at a resolution where one bit is exactly bit_px pixels."""
    pat_w, pat_h = pattern_bits(cols, rows)
    across, up = pat_w / TAG_BITS, pat_h / TAG_BITS
    bit_mm = tag_m * 1000 / TAG_BITS
    dpi = bit_px / (bit_mm / 25.4)
    width, height = (int(round(v / 25.4 * dpi)) for v in A4_MM)
    margin = int(8 / 25.4 * dpi)
    band = int(22 / 25.4 * dpi)
    if pat_w * bit_px > width - 2 * margin or pat_h * bit_px > height - 2 * margin - band:
        raise SystemExit("%.1f cm tags don't fit on A4 with %dx%d tags; use a smaller --tag-size"
                         % (tag_m * 100, cols, rows))
    img = np.full((height, width), 255, np.uint8)
    x0 = (width - pat_w * bit_px) // 2
    y0 = margin + (height - 2 * margin - band - pat_h * bit_px) // 2
    draw_grid(img, x0, y0, bit_px, cols, rows)
    pil = Image.fromarray(img)
    t = tag_m * 100
    caption(pil, [
        "Print at ACTUAL SIZE (100 %%, not 'fit to page'). Then each big square (tag, black border "
        "included) is %.2f cm." % t,
        "Outermost black edge to outermost black edge: W = %.2f cm across, %.2f cm high."
        % (across * t, up * t),
        "Check with a ruler: tag size = W / %.1f, and pass THAT as --tag-size (here %.4f m). "
        "Glue it flat onto cardboard." % (across, tag_m),
    ], y0 + pat_h * bit_px + margin // 2, height - (y0 + pat_h * bit_px) - margin)
    return pil, dpi


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
    return (len(obs.getCornersIdx()) if ok else 0), 4 * cols * rows


def report_check(name, pil, cols, rows):
    if pil.width > 2000:   # check at a size a camera would see it
        pil = pil.resize((1920, int(pil.height * 1920 / pil.width)), Image.LANCZOS)
    found, expected = check(pil, cols, rows)
    print("Kalibr detector on %s: %d of %d corners" % (name, found, expected))
    return found == expected


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--cols", type=int, default=6)
    p.add_argument("--rows", type=int, default=4)
    p.add_argument("--screen", default="1920x1080")
    p.add_argument("--out", default="target.png")
    p.add_argument("--pdf", help="also write an A4 PDF for printing")
    p.add_argument("--tag-size", type=float, default=0.03, help="tag size on paper [m], for --pdf")
    p.add_argument("--check", action="store_true", help="detect it with Kalibr's detector")
    a = p.parse_args()
    ok = True

    w, h = (int(v) for v in a.screen.lower().split("x"))
    png, bit = screen_png(a.cols, a.rows, w, h)
    png.save(a.out)
    across = pattern_bits(a.cols, a.rows)[0] / TAG_BITS
    print("wrote %s (%dx%d px, tag = %d px). Measure W across, tag size = W / %.1f"
          % (a.out, w, h, bit * TAG_BITS, across))
    if a.check:
        ok &= report_check(a.out, png, a.cols, a.rows)

    if a.pdf:
        pdf, dpi = paper_pdf(a.cols, a.rows, a.tag_size)
        pdf.save(a.pdf, resolution=dpi)
        print("wrote %s (A4 landscape, tags %.2f cm at actual size, W = %.2f cm)"
              % (a.pdf, a.tag_size * 100, across * a.tag_size * 100))
        if a.check:
            ok &= report_check(a.pdf, pdf, a.cols, a.rows)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
