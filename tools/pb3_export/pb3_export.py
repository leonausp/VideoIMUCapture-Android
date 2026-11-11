#!/usr/bin/env python3
"""
Export Zero-Friction VIO SLAM / VideoIMUCapture recordings to plain files and EuRoC.

A recording is a directory holding video_recording.mp4 and video_meta.pb3 (protobuf, schema
recording.proto). For each one this writes, by default into <session>/export/:

    frames.csv      one row per video frame that has metadata (timestamps, exposure, lens...)
    imu.csv         every IMU sample (gyro, accelerometer, bias estimates, magnetometer)
    ois.csv         OIS lens-shift samples, if the phone reported any
    camera.json     camera and IMU description (intrinsics, distortion, lens, clocks...)
    export_report.txt  what was exported and what was dropped, and why
    euroc/mav0/     EuRoC layout: cam0/data/<t>.png + data.csv + sensor.yaml,
                    imu0/data.csv + sensor.yaml, body.yaml

Run it through export.sh (Docker) or directly with Python 3 + protobuf, grpcio-tools, numpy,
opencv-python-headless and pyyaml installed. The schema is compiled at start-up, so the only
input besides the recordings is recording.proto (--proto).
"""

import argparse
from collections import Counter as collections_counter
import csv
import importlib
import json
import math
import os
import sys
import tempfile

import numpy as np

# ----------------------------------------------------------------------------- schema

def load_schema(proto_path):
    """Compile recording.proto with grpc_tools.protoc into a temp dir and import it."""
    from grpc_tools import protoc
    import grpc_tools
    out = tempfile.mkdtemp(prefix="pb3_export_")
    include = os.path.join(os.path.dirname(grpc_tools.__file__), "_proto")
    rc = protoc.main(["protoc", "-I", os.path.dirname(os.path.abspath(proto_path)),
                      "-I", include, "--python_out", out, os.path.abspath(proto_path)])
    if rc != 0:
        sys.exit("protoc failed on %s" % proto_path)
    sys.path.insert(0, out)
    return importlib.import_module(os.path.splitext(os.path.basename(proto_path))[0] + "_pb2")


# ----------------------------------------------------------------------------- distortion

def rotate_tangential(p1, p2, sensor_orientation):
    """
    Android reports LENS_DISTORTION [k1, k2, k3, p1, p2] in SENSOR orientation; the app stores
    the video rotated by SENSOR_ORIENTATION (and rotates the intrinsics to match). Radial terms
    do not change under a rotation; the tangential pair does. The app's rotation (see
    FocalLengthHelper.getTransformedIntrinsic) maps sensor (x, y) to image
        90:  ( -y,  x)      180: (-x, -y)      270: ( y, -x)
    in normalised coordinates, which turns (p1, p2) into
        90:  ( p2, -p1)     180: (-p1, -p2)    270: (-p2,  p1).
    """
    o = int(sensor_orientation) % 360
    if o == 90:
        return p2, -p1
    if o == 180:
        return -p1, -p2
    if o == 270:
        return -p2, p1
    return p1, p2


def distort(x, y, k1, k2, k3, p1, p2):
    """Brown-Conrady (OpenCV radial-tangential) on normalised coordinates."""
    r2 = x * x + y * y
    radial = 1 + k1 * r2 + k2 * r2 * r2 + k3 * r2 ** 3
    xd = x * radial + 2 * p1 * x * y + p2 * (r2 + 2 * x * x)
    yd = y * radial + p1 * (r2 + 2 * y * y) + 2 * p2 * x * y
    return xd, yd


def fit_radial_k1k2(k1, k2, k3, r_max, n=400):
    """k1', k2' of a 2-term radial model that best matches k1, k2, k3 for radii 0..r_max.

    EuRoC/Kalibr radial-tangential has no k3, and dropping it is not small: on the Pixel 6
    main lens (k3 = 0.12) the image corners move by ~56 px. Least squares on the radial
    displacement r * (k1 r^2 + k2 r^4 + k3 r^6), weighted by r (image area), leaves ~0.6 px
    mean and ~4 px in the outermost corner. Returns (k1', k2', max error in normalized units).
    """
    r = np.linspace(0.0, r_max, n)
    target = r * (k1 * r ** 2 + k2 * r ** 4 + k3 * r ** 6)
    A = np.vstack([r ** 3, r ** 5]).T
    w = np.sqrt(np.maximum(r, 1e-9))[:, None]
    c, *_ = np.linalg.lstsq(A * w, target * w[:, 0], rcond=None)
    return float(c[0]), float(c[1]), float(np.max(np.abs(A @ c - target)))


def sensor_to_video(xs, ys, arr_w, arr_h, vid_w, vid_h, sensor_orientation):
    """A point in sensor pixels -> video pixels, and the scale (video px per sensor px).

    The video is the sensor image cropped to the video's aspect ratio (centred), scaled, and
    rotated by SENSOR_ORIENTATION into the stored orientation -- the same rotation as the app's
    FocalLengthHelper (90: x_video = W-1 - y, y_video = x, in pixels after the scale).
    """
    o = int(sensor_orientation) % 360
    sw, sh = (vid_h, vid_w) if o in (90, 270) else (vid_w, vid_h)   # video in sensor orientation
    s = max(sw / arr_w, sh / arr_h)
    u = (xs - (arr_w - sw / s) / 2) * s
    v = (ys - (arr_h - sh / s) / 2) * s
    if o == 90:
        return (sh - 1) - v, u, s
    if o == 180:
        return (sw - 1) - u, (sh - 1) - v, s
    if o == 270:
        return v, (sw - 1) - u, s
    return u, v, s


def active_array(cm):
    """The active lens's pre-correction array (newer recordings), else None."""
    name = "active_pre_correction_active_array_size"
    if has_field(cm, name) and cm.HasField(name) and getattr(cm, name).width > 0:
        return getattr(cm, name)
    return None


def start_intrinsics(cm, rows):
    """fx, fy, cx, cy in video pixels from the per-frame HAL LENS_INTRINSIC_CALIBRATION.

    Through a logical camera the HAL reports the ACTIVE lens's intrinsics in that lens's own
    sensor pixels, including focus breathing (Pixel 6 main lens at 0.65 dpt: +2.6 % over the
    factory value). The static intrinsic_params are the logical camera's (= main lens), wrong for
    the ultrawide. Returns (dict, note) or (None, reason).
    """
    k = [list(r.lens_intrinsic_calibration) for r in rows if len(r.lens_intrinsic_calibration) >= 4
         and r.lens_intrinsic_calibration[0] > 0]
    if not k:
        return None, "no per-frame LENS_INTRINSIC_CALIBRATION in the recording"
    k = np.median(np.array(k)[:, :4], axis=0)
    active_arr = active_array(cm)
    arr = active_arr or cm.pre_correction_active_array_size
    if arr.width <= 0:
        return None, "no sensor array size in the recording"
    cx, cy, s = sensor_to_video(k[2], k[3], arr.width, arr.height,
                                cm.resolution.width, cm.resolution.height, cm.sensor_orientation)
    ids = collections_counter(r.active_physical_camera_id for r in rows if r.active_physical_camera_id)
    lens = getattr(cm, "active_camera_id", "") or (ids.most_common(1)[0][0] if ids else "") \
        or cm.metadata_camera_id or "?"
    note = ("per-frame HAL LENS_INTRINSIC_CALIBRATION (median), lens %s, array %dx%d, centred crop, "
            "scaled x%.5f and rotated %d deg. Valid when the zoom shows the lens's full field "
            "(1.0 for the main lens, the minimum zoom for the ultrawide)"
            % (lens, arr.width, arr.height, s, cm.sensor_orientation))
    if active_arr is None:
        note += "; OLD RECORDING without the active lens's array size: logical array assumed"
    return {"fx": float(k[0] * s), "fy": float(k[1] * s), "cx": float(cx), "cy": float(cy)}, note


def rotate_point(x, y, sensor_orientation):
    o = int(sensor_orientation) % 360
    if o == 90:
        return -y, x
    if o == 180:
        return -x, -y
    if o == 270:
        return y, -x
    return x, y


# ----------------------------------------------------------------------------- export

IMU_HEADER = ["time_ns", "gx", "gy", "gz", "ax", "ay", "az",
              "gyro_drift_x", "gyro_drift_y", "gyro_drift_z",
              "accel_bias_x", "accel_bias_y", "accel_bias_z",
              "mx", "my", "mz"]

FRAME_FIELDS = [  # (csv column, proto field, has_presence)
    ("frame_number", "frame_number", False),
    ("time_ns", "time_ns", False),
    ("lens_time_ns", "lens_time_ns", True),
    ("exposure_time_ns", "exposure_time_ns", False),
    ("frame_duration_ns", "frame_duration_ns", False),
    ("rolling_shutter_ns", "frame_readout_ns", False),
    ("iso", "iso", False),
    ("focal_length_mm", "focal_length_mm", False),
    ("est_focal_length_px", "est_focal_length_pix", False),
    ("focus_distance_diopters", "focus_distance_diopters", False),
    ("ois_mode", "lens_optical_stabilization_mode", True),
    ("video_stabilization_mode", "video_stabilization_mode", True),
    ("distortion_correction_mode", "distortion_correction_mode", True),
    ("zoom_ratio", "zoom_ratio", True),
    ("logical_zoom_ratio", "logical_zoom_ratio", True),
    ("metadata_camera_id", "metadata_camera_id", False),
    ("active_physical_camera_id", "active_physical_camera_id", False),
]


def has_field(msg, name):
    return name in msg.DESCRIPTOR.fields_by_name


def get(msg, name, presence):
    if not has_field(msg, name):
        return ""
    if presence:
        return getattr(msg, name) if msg.HasField(name) else ""
    return getattr(msg, name)


def image_time(m, mode):
    """Start of exposure of the recorded pixels: the pinned lens's own timestamp if recorded."""
    t = m.lens_time_ns if has_field(m, "lens_time_ns") and m.HasField("lens_time_ns") else m.time_ns
    if mode == "mid-exposure":
        t += m.exposure_time_ns // 2
    return int(t)


def write_csv(path, header, rows):
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)


def export_session(sess, R, args, report):
    from google.protobuf.json_format import MessageToDict
    import cv2
    import yaml

    out = args.out or os.path.join(sess, "export")
    if args.out and len(args.sessions) > 1:
        out = os.path.join(args.out, os.path.basename(os.path.normpath(sess)))
    os.makedirs(out, exist_ok=True)

    d = R.VideoCaptureData()
    with open(os.path.join(sess, "video_meta.pb3"), "rb") as f:
        d.ParseFromString(f.read())
    cm, im = d.camera_meta, d.imu_meta
    say = report.append
    say("session: %s" % os.path.basename(os.path.normpath(sess)))

    # ---- IMU -------------------------------------------------------------------------
    imu_rows = []
    for m in d.imu:
        g, a = list(m.gyro), list(m.accel)
        gd = list(m.gyro_drift) or [0.0] * 3
        ab = list(m.accel_bias) or [0.0] * 3
        mg = list(m.mag) or ["", "", ""]
        imu_rows.append([m.time_ns] + g[:3] + a[:3] + gd[:3] + ab[:3] + mg[:3])
    imu_rows.sort(key=lambda r: r[0])
    write_csv(os.path.join(out, "imu.csv"), IMU_HEADER, imu_rows)
    ti = np.array([r[0] for r in imu_rows], dtype=np.int64)
    imu_hz = float(1e9 / np.median(np.diff(ti))) if len(ti) > 1 else 0.0
    say("imu: %d samples, %.1f Hz" % (len(imu_rows), imu_hz))

    # ---- frames ----------------------------------------------------------------------
    rows_by_frame = {}
    for m in d.video_meta:
        rows_by_frame[m.frame_number] = m
    frame_rows = [[get(m, p, pr) for _, p, pr in FRAME_FIELDS]
                  for m in sorted(rows_by_frame.values(), key=lambda m: m.frame_number)]
    write_csv(os.path.join(out, "frames.csv"), [c for c, _, _ in FRAME_FIELDS], frame_rows)
    say("frame rows: %d" % len(frame_rows))

    ois_rows = [[m.frame_number, s.time_ns, s.x_shift, s.y_shift]
                for m in sorted(d.video_meta, key=lambda m: m.frame_number) for s in m.OIS_samples]
    if ois_rows:
        write_csv(os.path.join(out, "ois.csv"), ["frame_number", "time_ns", "x_shift_px", "y_shift_px"],
                  ois_rows)
    say("ois samples: %d" % len(ois_rows))

    # ---- camera.json -----------------------------------------------------------------
    cam = MessageToDict(cm, preserving_proto_field_name=True)
    orient = cm.sensor_orientation
    intr = list(cm.intrinsic_params)
    dist_sensor = list(cm.distortion_params)
    derived = {"image_width": cm.resolution.width, "image_height": cm.resolution.height}
    if len(intr) >= 4:
        derived["factory_intrinsics_logical"] = {
            "fx": intr[0], "fy": intr[1], "cx": intr[2], "cy": intr[3],
            "note": "static LENS_INTRINSIC_CALIBRATION of the camera the app opened (logical = main "
                    "lens on the Pixel 6), scaled and rotated by the app"}
    per_frame, why = start_intrinsics(cm, list(d.video_meta))
    if per_frame:
        derived.update(per_frame)
        derived["intrinsics_source"] = why
    elif len(intr) >= 4:
        derived.update({"fx": intr[0], "fy": intr[1], "cx": intr[2], "cy": intr[3],
                        "intrinsics_source": "static factory intrinsics (%s)" % why})
        say("start intrinsics: static factory values (%s)" % why)
    if per_frame or len(intr) >= 4:
        say("start intrinsics: fx %.1f cx %.1f cy %.1f" % (derived["fx"], derived["cx"], derived["cy"]))
    lens_ids = sorted({r.active_physical_camera_id for r in d.video_meta if r.active_physical_camera_id})
    dist_from = getattr(cm, "distortion_camera_id", "")
    if len(lens_ids) == 1 and dist_from and dist_from != lens_ids[0]:
        say("WARNING: frames come from lens %s but the distortion is lens %s's" % (lens_ids[0], dist_from))
    if not list(cm.distortion_params):
        say("WARNING: no factory distortion in the recording (the phone reports none, or an app "
            "version before 2026-11-11); start values assume an undistorted lens. Calibrate the lens "
            "(calibrate.sh cam/all) or let the SLAM system estimate it")
    if len(dist_sensor) >= 5:
        k1, k2, k3, p1, p2 = dist_sensor[:5]
        p1i, p2i = rotate_tangential(p1, p2, orient)
        derived["distortion_radtan_image"] = {
            "k1": k1, "k2": k2, "p1": p1i, "p2": p2i, "k3": k3,
            "note": "factory LENS_DISTORTION, tangential terms rotated from sensor to video "
                    "orientation; OpenCV order is [k1, k2, p1, p2, k3]"}
        if k3 != 0 and derived.get("fx", 0) > 0:
            # Radius of the image corner farthest from the principal point, normalized.
            w, h = cm.resolution.width, cm.resolution.height
            fx0, cx0, cy0 = derived["fx"], derived["cx"], derived["cy"]
            r_max = max(math.hypot(x - cx0, y - cy0) for x in (0, w) for y in (0, h)) / fx0
            f1, f2, err = fit_radial_k1k2(k1, k2, k3, r_max)
            derived["distortion_radtan4_image"] = {
                "k1": f1, "k2": f2, "p1": p1i, "p2": p2i,
                "max_error_px": err * fx0,
                "note": "k1, k2 refit over the image (r <= %.3f) to include k3, for 4-coefficient "
                        "models (EuRoC, Kalibr radtan); used in euroc cam0/sensor.yaml" % r_max}
    rows_meta = list(d.video_meta)
    if rows_meta:
        derived["focal_length_mm"] = rows_meta[0].focal_length_mm
        derived["rolling_shutter_ns"] = rows_meta[0].frame_readout_ns
    camera = {"camera_info": cam,
              "imu_info": MessageToDict(im, preserving_proto_field_name=True),
              "derived": derived}
    with open(os.path.join(out, "camera.json"), "w") as f:
        json.dump(camera, f, indent=2)

    if args.no_euroc:
        return

    # ---- EuRoC -----------------------------------------------------------------------
    mav = os.path.join(out, "euroc", "mav0")
    cam_dir = os.path.join(mav, "cam0", "data")
    imu_dir = os.path.join(mav, "imu0")
    os.makedirs(cam_dir, exist_ok=True)
    os.makedirs(imu_dir, exist_ok=True)

    imu_lo, imu_hi = (int(ti[0]), int(ti[-1])) if len(ti) else (None, None)
    cap = cv2.VideoCapture(os.path.join(sess, "video_recording.mp4"))
    cam_rows, no_row, outside_imu, written, idx = [], 0, 0, 0, -1
    last_t = None
    while True:
        ok, img = cap.read()
        if not ok:
            break
        idx += 1
        m = rows_by_frame.get(idx)
        if m is None:
            no_row += 1
            continue
        if args.every > 1 and idx % args.every:
            continue
        t = image_time(m, args.image_time)
        if not args.no_trim and imu_lo is not None and not (imu_lo <= t <= imu_hi):
            outside_imu += 1
            continue
        if last_t is not None and t <= last_t:
            continue                                  # never two images on one timestamp
        last_t = t
        if not args.color:
            img = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        name = "%d.png" % t
        cv2.imwrite(os.path.join(cam_dir, name), img)
        cam_rows.append([t, name])
        written += 1
    cap.release()
    say("video frames decoded: %d; images written: %d; skipped: %d without metadata row, "
        "%d outside the IMU time span%s" % (idx + 1, written, no_row, outside_imu,
                                            "" if not args.no_trim else " (trimming off)"))
    with open(os.path.join(mav, "cam0", "data.csv"), "w", newline="") as f:
        f.write("#timestamp [ns],filename\n")
        csv.writer(f).writerows(cam_rows)

    euroc_imu = [[r[0]] + list(np.array(r[1:4]) - (np.array(r[7:10]) if args.imu == "bias-corrected" else 0))
                 + list(np.array(r[4:7]) - (np.array(r[10:13]) if args.imu == "bias-corrected" else 0))
                 for r in imu_rows]
    with open(os.path.join(imu_dir, "data.csv"), "w", newline="") as f:
        f.write("#timestamp [ns],w_RS_S_x [rad s^-1],w_RS_S_y [rad s^-1],w_RS_S_z [rad s^-1],"
                "a_RS_S_x [m s^-2],a_RS_S_y [m s^-2],a_RS_S_z [m s^-2]\n")
        w = csv.writer(f)
        for r in euroc_imu:
            w.writerow([int(r[0])] + ["%.9g" % v for v in r[1:]])

    tc = np.array([r[0] for r in cam_rows], dtype=np.int64)
    cam_hz = float(1e9 / np.median(np.diff(tc))) if len(tc) > 1 else 0.0
    identity = [1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0]
    dist = derived.get("distortion_radtan4_image") or derived.get("distortion_radtan_image")
    cam_yaml = {
        "sensor_type": "camera",
        "comment": "Zero-Friction VIO SLAM export. START VALUES: intrinsics are the phone's per-frame "
                   "values for the recording lens, distortion its factory values (k3 refit), "
                   "T_BS is identity. Good enough for 'calibrate.sh imucam --camchain factory'; "
                   "replace T_BS with the Kalibr result (T_cam_imu, timeshift).",
        "T_BS": {"cols": 4, "rows": 4, "data": identity},
        "rate_hz": round(cam_hz, 3),
        "resolution": [cm.resolution.width, cm.resolution.height],
        "camera_model": "pinhole",
        "intrinsics": [derived.get("fx", 0.0), derived.get("fy", 0.0),
                       derived.get("cx", 0.0), derived.get("cy", 0.0)],
        "distortion_model": "radial-tangential",
        "distortion_coefficients": ([dist["k1"], dist["k2"], dist["p1"], dist["p2"]]
                                    if dist else [0.0, 0.0, 0.0, 0.0]),
    }
    imu_yaml = {
        "sensor_type": "imu",
        "comment": "Zero-Friction VIO SLAM export. Noise values are PLACEHOLDERS (VideoIMUCapture "
                   "defaults); estimate them with an Allan-variance tool on a long static "
                   "recording. Samples are %s." % ("raw (uncalibrated)" if args.imu == "raw"
                                                   else "minus the OS bias estimates"),
        "T_BS": {"cols": 4, "rows": 4, "data": identity},
        "rate_hz": round(imu_hz, 3),
        "gyroscope_noise_density": 1.2217e-4,
        "gyroscope_random_walk": 1.2217e-4,
        "accelerometer_noise_density": 1.764e-3,
        "accelerometer_random_walk": 1.764e-3,
    }
    for path, content in ((os.path.join(mav, "cam0", "sensor.yaml"), cam_yaml),
                          (os.path.join(imu_dir, "sensor.yaml"), imu_yaml)):
        with open(path, "w") as f:
            f.write("%YAML:1.0\n")
            yaml.safe_dump(content, f, default_flow_style=None, sort_keys=False)
    with open(os.path.join(mav, "body.yaml"), "w") as f:
        f.write("%YAML:1.0\ncomment: 'Body frame = IMU frame (identity), as in EuRoC.'\n")
    if len(tc):
        say("euroc: cam0 %d images at %.2f Hz, imu0 %d samples at %.1f Hz; image time = %s"
            % (len(tc), cam_hz, len(euroc_imu), imu_hz,
               "lens_time_ns" if any(has_field(m, "lens_time_ns") and m.HasField("lens_time_ns")
                                     for m in rows_meta) else "time_ns")
            + (" + exposure/2" if args.image_time == "mid-exposure" else ""))
    else:
        say("euroc: NO IMAGES written (no frame has a metadata row inside the IMU span)")


def find_sessions(paths):
    out = []
    for p in paths:
        if os.path.isfile(os.path.join(p, "video_meta.pb3")):
            out.append(p)
            continue
        for n in sorted(os.listdir(p)):
            q = os.path.join(p, n)
            if os.path.isfile(os.path.join(q, "video_meta.pb3")):
                out.append(q)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sessions", nargs="+", help="session dir(s), or dir(s) containing sessions")
    ap.add_argument("--proto", required=True, help="path to recording.proto")
    ap.add_argument("--out", help="output dir (default: <session>/export; with several sessions "
                                  "one subdir per session)")
    ap.add_argument("--image-time", choices=["sensor", "mid-exposure"], default="sensor",
                    help="EuRoC image timestamp: start of exposure (default) or + exposure/2")
    ap.add_argument("--imu", choices=["raw", "bias-corrected"], default="raw",
                    help="EuRoC IMU samples: raw (default) or minus the OS bias estimates")
    ap.add_argument("--color", action="store_true", help="EuRoC images in colour (default: grey)")
    ap.add_argument("--every", type=int, default=1, help="keep every Nth video frame (default 1)")
    ap.add_argument("--no-trim", action="store_true",
                    help="keep images outside the IMU time span (default: dropped)")
    ap.add_argument("--no-euroc", action="store_true", help="only CSV/JSON, no EuRoC folder")
    args = ap.parse_args()

    R = load_schema(args.proto)
    sessions = find_sessions(args.sessions)
    args.sessions = sessions
    if not sessions:
        sys.exit("no recordings (directories with video_meta.pb3) found")
    for s in sessions:
        report = []
        export_session(s, R, args, report)
        out = args.out and len(sessions) > 1 and os.path.join(args.out, os.path.basename(os.path.normpath(s))) \
            or args.out or os.path.join(s, "export")
        with open(os.path.join(out, "export_report.txt"), "w") as f:
            f.write("\n".join(report) + "\n")
        print("\n".join(report) + "\n  -> " + out + "\n")


if __name__ == "__main__":
    main()
