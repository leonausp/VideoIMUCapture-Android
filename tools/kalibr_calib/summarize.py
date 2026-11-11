"""Short, readable summary of Kalibr's results, with a rating for each number.

    python3 summarize.py cam    <dir> <prefix>    # <prefix>-camchain.yaml, <prefix>-results-cam.txt
    python3 summarize.py imucam <dir> <prefix>    # <prefix>-camchain-imucam.yaml, -results-imucam.txt

Runs inside the Kalibr image (numpy, PyYAML). Prints plain text; calibrate.sh adds the frame.
"""
import math
import os
import re
import sys

import numpy as np
import yaml

GOOD, CHECK, BAD = "good", "check", "BAD"


def rate(value, good_below, ok_below):
    return GOOD if value < good_below else CHECK if value < ok_below else BAD


def line(label, value, verdict="", note=""):
    mark = {GOOD: "✓", CHECK: "!", BAD: "✗", "": " "}[verdict]
    text = "  %s %-28s %s" % (mark, label, value)
    if note:
        text += "   (%s)" % note
    print(text)


def reprojection(path, pattern):
    """Mean reprojection error in px from a Kalibr results file, or None."""
    if not os.path.exists(path):
        return None
    text = open(path).read()
    m = re.search(pattern, text)
    if not m:
        return None
    nums = [abs(float(v)) for v in re.findall(r"[-+]?\d*\.\d+(?:e[-+]?\d+)?|\d+", m.group(1))]
    return nums


def cam(folder, prefix):
    chain = yaml.safe_load(open(os.path.join(folder, prefix + "-camchain.yaml")))["cam0"]
    fu, fv, pu, pv = chain["intrinsics"]
    w, h = chain["resolution"]
    d = chain["distortion_coeffs"]
    print("Camera (lens) calibration")
    line("Image size", "%d x %d px" % (w, h))
    aspect = abs(fu / fv - 1) * 100
    line("Focal length fx / fy", "%.1f / %.1f px" % (fu, fv), rate(aspect, 1, 3),
         "fx and fy differ by %.1f %%; phone pixels are square" % aspect)
    off = math.hypot(pu - w / 2, pv - h / 2) / math.hypot(w, h) * 100
    line("Image centre cx / cy", "%.1f / %.1f px" % (pu, pv), rate(off, 3, 6),
         "%.1f %% of the diagonal from the middle" % off)
    line("Distortion k1 k2 p1 p2", " ".join("%+.4f" % v for v in d))
    # Kalibr prints "[mean x, mean y] +- [std x, std y]"; the signed mean is ~0, the std is the size.
    err = reprojection(os.path.join(folder, prefix + "-results-cam.txt"),
                       r"reprojection error:\s*\[[^\]]*\]\s*\+-\s*\[([^\]]*)\]")
    if err:
        e = math.hypot(err[0], err[1]) if len(err) > 1 else err[0]
        line("Reprojection error", "%.2f px" % e, rate(e, 0.5, 1.0), "below 0.5 px is good")


def imucam(folder, prefix):
    chain = yaml.safe_load(open(os.path.join(folder, prefix + "-camchain-imucam.yaml")))["cam0"]
    T = np.array(chain["T_cam_imu"])
    R, t = T[:3, :3], T[:3, 3]
    shift_ms = chain.get("timeshift_cam_imu", 0.0) * 1000
    print("Camera-IMU calibration")
    line("Time offset camera->IMU", "%+.2f ms" % shift_ms, rate(abs(shift_ms), 10, 30),
         "t_imu = t_cam + offset; repeat recordings should agree to ~1 ms")
    # A phone's camera is mounted along the body axes: R should be a 90-degree axis swap.
    nearest = np.round(R)
    cos = (np.trace(nearest.T @ R) - 1) / 2
    tilt = math.degrees(math.acos(max(-1.0, min(1.0, cos))))
    line("Rotation vs. phone axes", "%.2f deg off a 90-degree axis swap" % tilt,
         rate(tilt, 2, 5), "the camera sits along the phone's axes")
    p_imu_in_cam = t * 100
    dist = float(np.linalg.norm(p_imu_in_cam))
    line("Camera-IMU distance", "%.1f cm" % dist, rate(dist, 8, 15),
         "phone-sized: a few cm")
    text_path = os.path.join(folder, prefix + "-results-imucam.txt")
    err = reprojection(text_path, r"Reprojection error \(cam0\) \[px\]:\s*mean\s*([^,]*)")
    if err:
        line("Reprojection error", "%.2f px" % err[0], rate(err[0], 1.0, 2.0),
             "mean; fast motion makes it larger than for the lens step")
    print()
    print("  T_cam_imu (IMU -> camera):")
    for row in T:
        print("     [" + "  ".join("%+.4f" % v for v in row) + "]")


def main():
    mode, folder, prefix = sys.argv[1:4]
    {"cam": cam, "imucam": imucam}[mode](folder, prefix)


if __name__ == "__main__":
    main()
