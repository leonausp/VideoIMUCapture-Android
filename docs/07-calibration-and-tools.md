# 07 – Calibration & analysis tools

VIO needs to know four things about the phone:

| What | Meaning | Where it comes from |
|---|---|---|
| **Camera intrinsics** | focal length, image centre, lens distortion | **the phone**: per-frame focal length of the recording lens, factory distortion |
| **Camera–IMU extrinsics** | rotation and offset between camera and IMU | **Kalibr**, from one ~1 min recording of a target |
| **Camera–IMU time offset** | how far the image timestamps are off from the IMU's | **Kalibr**, same recording |
| **IMU noise** | how noisy the gyroscope and accelerometer are | typical phone values for now; Allan variance later (section 6) |

[`tools/kalibr_calib/`](../tools/kalibr_calib/README.md) runs the official
[Kalibr](https://github.com/ethz-asl/kalibr) in Docker. **You calibrate once per phone, lens and
settings**, not per recording; with the recommended settings (1280×960, no OIS/EIS) every later
recording reuses the result. Focus may differ between the calibration and later clips (tap to focus,
locked at RECORD; for the calibration, tap the target): the camera–IMU result does not depend on it, and each clip's own focal length
comes from the phone.

**Why the intrinsics come from the phone.** Kalibr can also calibrate the lens, but on our Pixel 6
recordings it failed or gave a wrong focal length in 5 of 7 attempts (e.g. 447 px instead of ~600
for the ultrawide, with a deceptively small error of 0.43 px): a flat grid seen mostly face-on
leaves focal length and distance ambiguous. The phone's own values are consistent: the HAL reports
the active lens's focal length for every frame, including focus breathing (main lens at 0.65 on its
focus scale: 899 px, 2.6 % more than the factory value), and the factory distortion. The exporter turns them
into start values, and the camera–IMU calibration uses them as they are.

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

In the app: the settings you'll use for SLAM (lens, resolution, focus), plus **exposure 2–3 ms**
(exposure button, bottom right; back to AUTO afterwards). Choose the lens under Settings → **Lens** ("Main" or
"Ultrawide"); calibrate each lens you use separately. The target lies or hangs still;
you move the phone. About **60–70 s**: start calmly, then rotate the phone around **all three
axes** and move it along all three directions, briskly, keeping the grid at least partly in view.
Moving towards and away from the target helps the offset along the viewing direction.

**Distance.** The grid's squares must stay readable: each code cell (1/10 of a square) needs about
4 px. On A4 (3 cm squares) that is ~0.6–0.8 m. With the main lens on a laptop screen (2.8 cm
squares) 0.7 m was too far: cells of ~3 px, nothing decoded. The ultrawide has far more depth of
field and worked on the same screen at 94 of 96 corners per frame.

Record **2–3 times**. The time offsets should agree to about 1 ms.

## 4. Calibrate

Copy the recordings to the computer as usual (`adb pull …`, see [06](06-building-and-installing.md)),
then:

```bash
tools/kalibr_calib/calibrate.sh imucam data/vid_<date> --tag-size 0.03 --camchain factory
```

- `--tag-size` is your measured value in metres (paper at 100 %: `0.03`).
- `--camchain factory` uses the phone's lens values (the start values the export writes).

The script converts the recording (with [`tools/pb3_export`](../tools/pb3_export/README.md),
timestamps at mid-exposure) and runs Kalibr's camera–IMU calibration (5–10 minutes). Kalibr's own
output goes to `kalibr.log`; the terminal shows the steps and a rated summary:

| Value | Good | If not |
|---|---|---|
| Time offset | about half the rolling-shutter readout + 1–2 ms; stable across repeats (±1 ms) | too little brisk motion |
| Rotation vs. phone axes | below ~2° off a 90° axis swap | too little rotation, wrong target size/layout |
| Camera–IMU distance | a few cm | too little translation in the brisk part |
| Reprojection error | below ~1 px | blur (exposure too long), grid too small |

**Measured on the Pixel 6** (2026-11-11, focus 0.65 on the phone's scale, i.e. ~4 m; the default is now 1.1, so fx is a little larger, ~904 px — the per-frame value handles that):

| | Main lens (zoom 1.0) | Ultrawide (zoom 0.67) |
|---|---|---|
| fx (phone, per frame) | 899 px | ~600 px |
| Rolling-shutter readout | 13.6 ms | 32.4 ms |
| Time offset | 8.4 ± 0.4 ms (4 recordings) | 17.2 ± 0.3 ms (2 recordings) |
| Rotation | within ~1° of the axis swap | within ~1° |
| Offset x / y / z | ≈ −5.0 / 1.7 / −0.1 … −1.9 cm | ≈ −3.8 / 1.5 / 0.0 cm |

The main lens and the ultrawide come out 1.2–1.5 cm apart sideways; the phone's factory data put
the ultrawide 1.48 cm from the main lens, which is an independent check of the translation. The
offset along the viewing direction (z) is the least certain component.

The results go to `<recording>/kalibr_imucam/`: `imucam-camchain-imucam.yaml` (intrinsics +
`T_cam_imu` + `timeshift_cam_imu`, **what SLAM needs**), `imucam-report-imucam.pdf` (plots),
`kalibr.log`.

**Kalibr's lens calibration** is still available (`calibrate.sh all <recording> --cam-until 30`,
lens from the calm first 30 s, then camera–IMU; or `cam` for the lens only). The summary compares
its focal length with the phone's and says when it went wrong. It needs many strongly tilted views
with the grid reaching every image corner. `--target <file>` uses another Kalibr target, such as a
checkerboard; `calibrate.sh --help` lists everything.

## 5. Use the calibration for SLAM

Export SLAM recordings with the **same timestamp convention** as the calibration:

```bash
tools/pb3_export/export.sh data/vid_<date> --image-time mid-exposure
```

From `imucam-camchain-imucam.yaml`:

- **Intrinsics** (`intrinsics: [fu, fv, pu, pv]`, `distortion_coeffs: [k1, k2, p1, p2]`,
  model pinhole + radtan) go into the SLAM system's camera settings.
- **`T_cam_imu`** transforms points from the IMU frame into the camera frame. Check which
  direction your SLAM system expects: ORB-SLAM3's `T_b_c1` is the inverse (camera into body/IMU
  frame), and OpenVINS's Kalibr-style config names it `T_imu_cam`. A wrong direction makes VIO
  diverge right at the start.
- **`timeshift_cam_imu`**: `t_imu = t_cam + timeshift`. Add it to the image timestamps, or pass it
  to a system that takes it as a parameter.

The EuRoC export's own `sensor.yaml` files carry the start values: the phone's intrinsics (the
same as in the calibration), but an identity `T_BS` and placeholder IMU noise. Use the Kalibr
result for the transform and the time offset.

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
