# 07 – Calibration & analysis tools

VIO needs to know four things about the phone:

| What | Meaning | Changes when… |
|---|---|---|
| **Camera intrinsics** | focal length, image centre, lens distortion | lens, resolution, zoom, focus or stabilisation change |
| **Camera–IMU extrinsics** | rotation and offset between camera and IMU | never on one phone |
| **Camera–IMU time offset** | how far the image timestamps are off from the IMU's | (should be stable; checked by repeating) |
| **IMU noise** | how noisy the gyroscope and accelerometer are | never on one phone |

The first three come from **one ~70 s recording** of a calibration target, with
[`tools/kalibr_calib/`](../tools/kalibr_calib/README.md), which runs the official
[Kalibr](https://github.com/ethz-asl/kalibr) in Docker. **You calibrate once per phone and
settings**, not per recording. With the recommended settings (main lens, 1280×960, fixed focus,
no OIS/EIS) every later recording reuses the same calibration.

IMU noise needs a separate, long static recording (section 6). Until then, the tool uses typical
phone values.

## 1. Build the Kalibr image (once)

```bash
tools/kalibr_calib/build.sh
```

This builds Kalibr at a pinned commit into the Docker image `zf-kalibr:<commit>` (about 8 GB,
15–30 minutes). Everything else runs inside it; nothing is installed on the computer.

## 2. Make the target

```bash
tools/kalibr_calib/calibrate.sh target --out ~/kalibr_target
```

The target is an **AprilGrid**: 6 × 4 black squares (tags), each with its own code, so Kalibr
recognises the grid even when part of it is out of view. The script writes two versions:

- **`target.pdf`, paper (recommended).** Print it on A4 at **actual size** (100 %, not "fit to
  page"). Then each big square is 3.00 cm, and the outermost black edges are 24.30 cm apart.
  Check that with a ruler, as printers sometimes scale. Glue the sheet **flat** onto cardboard;
  a bent sheet is a real source of error.
- **`target.png`, screen (fastest).** For showing full screen (F11). Its size depends on the
  screen, so **measure it with a ruler**. The script lists the connected screens, makes the PNG for
  the built-in one and prints the command for the others (`--screen WxH`). Turn the brightness up
  and avoid reflections. A PDF can't keep its size on a screen: the viewer doesn't know the
  screen's real pixel density, so "100 %" is a different size on every laptop.

**Measuring.** W = the width from the outermost black edge on the left to the outermost black edge
on the right (the small corner squares). **Tag size = W / 8.1**, in metres: 26.0 cm → 3.21 cm →
`--tag-size 0.0321`. The height / 5.5 should give the same value. A tag size that's off by 1 %
doesn't change the intrinsics and hardly matters for the rest.

## 3. Record

In the app: the recommended settings, plus **exposure 2–3 ms** (exposure button, bottom right).
The target lies or hangs still; you move the phone. About **70 s**:

| Time | Motion | Used for |
|---|---|---|
| **0–30 s, calm** | Move slowly so the grid passes through **every part of the image**, especially the corners. Tilt up to ~45° in each direction, vary the distance (~0.5–0.8 m). | intrinsics |
| **30–70 s, brisk** | Rotate the phone around **all three axes** and move it along all three directions. The grid may leave the image partly, but not completely. | camera–IMU |

Why the two parts: the intrinsics need **sharp** images with the grid everywhere in the frame, while
the camera–IMU calibration needs **motion**, because the IMU only measures what moves. The short
exposure keeps the brisk part sharp enough (at 5 ms a quick turn smears corners by ~9 px).

The calibration is still valid for SLAM recordings at 5 ms: exposure doesn't change the geometry,
and the timestamps are taken at mid-exposure (section 5).

Record **2–3 times**. The time offsets should agree to about 1 ms.

## 4. Calibrate

Copy the recordings to the computer as usual (`adb pull …`, see [06](06-building-and-installing.md)),
then:

```bash
tools/kalibr_calib/calibrate.sh all data/vid_<date> --tag-size 0.0321 --cam-until 30
```

- `--tag-size` is your measured value in metres (paper at 100 %: `0.03`).
- `--cam-until 30` takes the intrinsics from the calm first 30 seconds only.

The script converts the recording (with [`tools/pb3_export`](../tools/pb3_export/README.md),
timestamps at mid-exposure), runs Kalibr's lens calibration, then the camera–IMU calibration with
that result. It takes 10–30 minutes. Kalibr's own output goes to `kalibr.log`; the terminal shows
the steps and then a rated summary:

| Value | Good | If not |
|---|---|---|
| Reprojection error, lens | below 0.5 px | target not flat, blurry frames in the calm part, wrong `--cam-until` |
| fx vs fy | within 1 % | too few tilted views |
| Image centre | within ~3 % of the middle | grid didn't reach the image edges |
| Time offset | stable across repeats (±1 ms), a few ms | too little brisk motion; check the timestamp source |
| Rotation vs. phone axes | below ~2° off a 90° axis swap | too little rotation, or a wrong target size/layout |
| Camera–IMU distance | a few cm | too little translation in the brisk part |

The results go to `data/vid_<date>/kalibr_all/`:

| File | Contents |
|---|---|
| `all-camchain.yaml` | intrinsics only |
| `all-camchain-imucam.yaml` | intrinsics + `T_cam_imu` + `timeshift_cam_imu`: **what SLAM needs** |
| `all-report-cam.pdf`, `all-report-imucam.pdf` | Kalibr's plots |
| `kalibr.log` | Kalibr's full output |

If something fails, the script says why in plain words for the common causes (no target found,
focal length not estimable, too few good views), otherwise it shows the end of the log.

**Separate steps** are possible too: `calibrate.sh cam <recording>` (intrinsics only) and
`calibrate.sh imucam <recording> --camchain <file>` (camera–IMU with an existing intrinsics file,
e.g. from another tool). `--target <file>` uses another Kalibr target, such as a checkerboard.
`calibrate.sh --help` lists everything.

## 5. Use the calibration for SLAM

Export SLAM recordings with the **same timestamp convention** as the calibration:

```bash
tools/pb3_export/export.sh data/vid_<date> --image-time mid-exposure
```

From `all-camchain-imucam.yaml`:

- **Intrinsics** (`intrinsics: [fu, fv, pu, pv]`, `distortion_coeffs: [k1, k2, p1, p2]`,
  model pinhole + radtan) go into the SLAM system's camera settings.
- **`T_cam_imu`** transforms points from the IMU frame into the camera frame. Check which
  direction your SLAM system expects: ORB-SLAM3's `T_b_c1` is the inverse (camera into body/IMU
  frame), and OpenVINS's Kalibr-style config names it `T_imu_cam`. A wrong direction makes VIO
  diverge right at the start.
- **`timeshift_cam_imu`**: `t_imu = t_cam + timeshift`. Add it to the image timestamps, or pass it
  to a system that takes it as a parameter.

The EuRoC export's own `sensor.yaml` files only carry start values (factory intrinsics, identity
transforms, placeholder noise); replace them with these results.

## 6. IMU noise (Allan variance)

The camera–IMU calibration and the VIO systems need the IMU's noise density and random walk.
They are measured from a **long static recording**: phone lying still for 2–3 hours, plugged in,
lens covered, then analysed with an Allan-variance tool. The values are usually inflated a few
times for phone IMUs. Pass the result to Kalibr with `--imu-yaml <file>`.

Until then, `calibrate.sh` uses typical phone values, marked as placeholders in `imu.yaml` and in
the summary. They are good enough to calibrate; for final results, measure.

## The old `calibration/` folder

`calibration/` holds the original VideoIMUCapture calibration scripts (`data2kalibr.py`,
`data2rosbag.py`, …). They need an old Docker image (Ubuntu 16.04, ROS Kinetic, Python 2) with an
outdated copy of the recording schema, and are **not used** here. `tools/kalibr_calib` replaces
them: current Kalibr, pinned, reading the EuRoC export.

## `analysis/`: checks from the previous fork

| Script | Checks |
|---|---|
| `frame_holes.py` | gaps in the per-frame metadata |
| `dump_meta.py` | prints metadata from a session |
| `analyze_pairs.py`, `overlay.py`, `zoom_probe.py` | stereo-pair and lens/crop checks |
| `wn_compare.py` | compares noise/white-noise between recordings |

`analysis/FINDINGS.md` is the previous author's lab notebook from validating the S24 Ultra. Install
the Python requirements with `pip install -r analysis/requirements.txt`.
