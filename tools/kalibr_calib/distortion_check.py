"""Check the lens distortion of a calibration recording, and fit it where the phone gives none.

Phones differ (measured 2026-11-11, AprilGrid recordings, curvature of the grid in the image corners):
  - distorted, factory values fit      Pixel 6 ultrawide: 1.77 px raw -> 0.57 px corrected
  - already straight                   Pixel 9 Pro ultrawide (corrected by the phone despite OFF), S21 main
  - distorted, NO values               Galaxy S21 ultrawide: 1.47 px raw, the phone reports nothing

The test needs no focal length: a flat grid seen by an undistorted camera is an exact homography of
the target, so what a homography cannot explain is lens distortion (plus corner noise, ~0.3 px).
Views whose grid reaches towards the image corners show it most.

If the start values do not straighten the grid, k1 and k2 are fitted with the phone's focal length
and principal point held FIXED (OpenCV calibrateCamera with those parameters frozen): the curvature
determines k1, k2 well, while a full lens calibration on phone recordings often finds a wrong focal
length (Kalibr: 5 of 7 attempts). The fit is kept only if it straightens the grid.

    python3 distortion_check.py <euroc_dir> <camchain.yaml in/out> <target.yaml> <report.txt>

Runs inside the Kalibr image (Kalibr's AprilGrid detector, OpenCV, numpy, PyYAML). Rewrites the
camchain's distortion_coeffs only when the fit is used; the report says what was decided and why.
"""
import glob
import sys

import cv2
import numpy as np
import yaml

import aslam_cameras_april as acv_april
import aslam_cv as acv

STRAIGHT_PX = 0.6      # corner-view curvature at or below this: straight enough (noise ~0.3 px)
FAR = 0.75             # a view "reaches the corners" when its grid gets this far (fraction of half-diagonal)
MAX_VIEWS = 160        # frames examined (spread over the recording)
FIT_VIEWS = 40         # views used to fit k1, k2


def load_target(path):
    t = yaml.safe_load(open(path))
    if t.get("target_type") != "aprilgrid":
        return None
    opts = acv_april.AprilgridOptions()
    opts.minTagsForValidObs = 6
    opts.showExtractionVideo = False
    return acv_april.GridCalibrationTargetAprilgrid(t["tagRows"], t["tagCols"], t["tagSize"],
                                                     t["tagSpacing"], opts)


def detect(euroc, grid):
    dopts = acv.GridDetectorOptions()
    dopts.filterCornerOutliers = False
    det = acv.GridDetector(acv.DistortedPinholeCameraGeometry(), grid, dopts)
    files = sorted(glob.glob(euroc + "/cam0/data/*.png"))
    step = max(1, len(files) // (MAX_VIEWS * 2))
    views = []
    size = None
    for f in files[::step]:
        img = cv2.imread(f, cv2.IMREAD_GRAYSCALE)
        size = img.shape[::-1]
        ok, obs = det.findTargetNoTransformation(acv.Time(0.0), np.array(img))
        if not ok:
            continue
        ip = np.array(obs.getCornersImageFrame()).reshape(-1, 2)
        tp = np.array(obs.getCornersTargetFrame()).reshape(-1, 3)
        if len(ip) >= 40:
            views.append((ip.astype(np.float64), tp.astype(np.float64)))
    return views, size


def curvature(ip, tp):
    """RMS of what a homography target -> image cannot explain, in px."""
    H, _ = cv2.findHomography(tp[:, :2], ip, 0)
    pr = cv2.perspectiveTransform(tp[:, :2].reshape(-1, 1, 2), H).reshape(-1, 2)
    return float(np.sqrt(np.mean(np.sum((pr - ip) ** 2, axis=1))))


def undistort(ip, K, D):
    if not np.any(D):
        return ip
    return cv2.undistortPoints(ip.reshape(-1, 1, 2), K, D, P=K).reshape(-1, 2)


def corner_curvature(views, K, D, size):
    """Median curvature over the views reaching the corners, and how many there were."""
    w, h = size
    half = np.hypot(w / 2, h / 2)
    vals = [curvature(undistort(ip, K, D), tp) for ip, tp in views
            if np.max(np.hypot(ip[:, 0] - w / 2, ip[:, 1] - h / 2)) / half >= FAR]
    return (float(np.median(vals)) if vals else float("nan")), len(vals)


def fit_views(views, size, n=FIT_VIEWS):
    """Up to n views for the fit, those reaching furthest into the corners first (they carry the
    distortion; every view adds 6 pose unknowns, and OpenCV's solver slows down steeply with them)."""
    w, h = size
    reach = [np.max(np.hypot(ip[:, 0] - w / 2, ip[:, 1] - h / 2)) for ip, _ in views]
    order = np.argsort(reach)[::-1]
    return [views[i] for i in sorted(order[:n])]


def fit_k1k2(views, K, size):
    views = fit_views(views, size)
    flags = (cv2.CALIB_USE_INTRINSIC_GUESS | cv2.CALIB_FIX_FOCAL_LENGTH | cv2.CALIB_FIX_PRINCIPAL_POINT
             | cv2.CALIB_FIX_ASPECT_RATIO | cv2.CALIB_ZERO_TANGENT_DIST | cv2.CALIB_FIX_K3)
    obj = [tp.astype(np.float32) for _, tp in views]
    img = [ip.astype(np.float32).reshape(-1, 1, 2) for ip, _ in views]
    rms, K2, D2, _, _ = cv2.calibrateCamera(obj, img, size, K.copy(), np.zeros(5), flags=flags)
    return np.array([D2.ravel()[0], D2.ravel()[1], 0.0, 0.0]), float(rms)


def main():
    euroc, camchain_path, target_path, report_path = sys.argv[1:5]
    out = []
    say = out.append
    cc = yaml.safe_load(open(camchain_path))
    cam = cc["cam0"]
    fu, fv, pu, pv = cam["intrinsics"]
    K = np.array([[fu, 0, pu], [0, fv, pv], [0, 0, 1.0]])
    D0 = np.array((list(cam.get("distortion_coeffs") or []) + [0, 0, 0, 0])[:4], dtype=float)

    grid = load_target(target_path)
    if grid is None:
        say("verdict: skipped (only AprilGrid targets are checked)")
        open(report_path, "w").write("\n".join(out) + "\n")
        return
    views, size = detect(euroc, grid)
    raw, n_far = corner_curvature(views, K, np.zeros(4), size)
    start, _ = corner_curvature(views, K, D0, size)
    say("views: %d with the grid, %d reaching the image corners" % (len(views), n_far))
    say("curvature in the corners: raw %.2f px, with the start distortion %.2f px%s"
        % (raw, start, "" if np.any(D0) else " (none given)"))
    if n_far < 5:
        say("verdict: not enough views reaching the corners to judge; start values kept")
    elif np.any(D0) and start <= STRAIGHT_PX + 0.1 and start < raw:
        say("verdict: FACTORY distortion fits (straightens the grid); kept")
    elif raw <= STRAIGHT_PX:
        if np.any(D0) and start > raw + 0.1:
            cam["distortion_coeffs"] = [0.0, 0.0, 0.0, 0.0]
            say("verdict: image already STRAIGHT (the phone corrects it); the start distortion made it "
                "worse, so none is used")
        else:
            say("verdict: image already STRAIGHT (the phone corrects it, or the lens barely distorts)")
    else:
        D, rms = fit_k1k2(views, K, size)
        fitted, _ = corner_curvature(views, K, D, size)
        say("distortion fitted with the phone's focal length fixed: k1 %+.4f k2 %+.4f (reprojection %.2f px)"
            % (D[0], D[1], rms))
        say("curvature in the corners with the fit: %.2f px" % fitted)
        if fitted < min(raw, start) * 0.7:
            cam["distortion_coeffs"] = [float(v) for v in D]
            say("verdict: DISTORTED, values missing or wrong; the FITTED distortion is used")
        else:
            say("verdict: DISTORTED, but the fit does not straighten the grid either; start values kept "
                "(record views with the grid in every image corner)")
    yaml.safe_dump(cc, open(camchain_path, "w"), default_flow_style=None)
    open(report_path, "w").write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
