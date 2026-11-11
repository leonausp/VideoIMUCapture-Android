# pb3_export: recordings → CSV, JSON and EuRoC

Converts a recording from the app (`video_recording.mp4` + `video_meta.pb3`) into plain files that
any tool can read, plus a **EuRoC** dataset folder for ORB-SLAM3, Basalt, OpenVINS, VINS-Fusion and
others.

The folder is self-contained: `pb3_export.py`, `Dockerfile`, `export.sh`, `test_pb3_export.py`.
The only outside input is the schema, `recording.proto`.

## Usage (Docker, nothing to install)

```bash
tools/pb3_export/export.sh data/vid_2026_11_11_10_15_00      # one recording
tools/pb3_export/export.sh data                              # every recording in data/
tools/pb3_export/export.sh data --image-time mid-exposure --every 2
```

The output goes to `<recording>/export/`. Pass `--out <dir>` (inside the given folder) to put it
elsewhere. The first run builds a small Docker image with pinned versions (Python 3.12, protobuf
5.28, OpenCV 4.10, numpy 2.1).

If the tool lives in another repository, point it at the schema:
`PROTO=/path/to/recording.proto ./export.sh <recordings>`.

Without Docker, use Python 3 with `protobuf grpcio-tools numpy opencv-python-headless pyyaml`:
`python3 pb3_export.py <recordings> --proto recording.proto`.

## Options

| Option | Default | Effect |
|---|---|---|
| `--image-time sensor\|mid-exposure` | `sensor` | EuRoC image timestamp: start of exposure, or + exposure/2 |
| `--imu raw\|bias-corrected` | `raw` | EuRoC IMU: raw (uncalibrated) samples, or minus the phone's bias estimates |
| `--color` | off | colour PNGs (default greyscale, as in EuRoC) |
| `--every N` | 1 | keep every Nth video frame |
| `--no-trim` | off | keep images outside the IMU time span (normally dropped, see below) |
| `--no-euroc` | off | only the CSV/JSON files |

## Output

```
export/
├── frames.csv          one row per video frame with metadata
├── imu.csv             every IMU sample
├── ois.csv             OIS lens-shift samples (only if the phone reported any)
├── camera.json         full CameraInfo + IMUInfo, plus "derived": fx, fy, cx, cy, distortion…
├── export_report.txt   counts, rates, what was skipped and why
└── euroc/mav0/
    ├── cam0/data/<timestamp_ns>.png, cam0/data.csv, cam0/sensor.yaml
    ├── imu0/data.csv, imu0/sensor.yaml
    └── body.yaml
```

**`frames.csv`** columns: `frame_number` (position in the MP4), `time_ns` (logical/buffer timestamp),
`lens_time_ns` (pinned lens's own exposure start, if recorded), `exposure_time_ns`,
`frame_duration_ns`, `rolling_shutter_ns`, `iso`, `focal_length_mm`, `est_focal_length_px`,
`focus_distance_diopters`, `ois_mode`, `video_stabilization_mode`, `distortion_correction_mode`,
`zoom_ratio`, `logical_zoom_ratio`, `metadata_camera_id`, `active_physical_camera_id`.

**`imu.csv`** columns: `time_ns`, `gx gy gz` (rad/s, uncalibrated), `ax ay az` (m/s², uncalibrated),
`gyro_drift_*` and `accel_bias_*` (the OS's bias estimates; accelerometer bias is 0 on phones that
don't estimate one), `mx my mz` (µT). Rows are gyro samples, with accelerometer and magnetometer
interpolated to the gyro time by the app.

All timestamps are nanoseconds on the phone's boot clock. The camera and IMU share it when the
camera's timestamp source is REALTIME (`camera.json` → `camera_info.timestamp_source`).

## What the EuRoC export does, and what it doesn't

- **Images are matched to metadata by `frame_number`.** A video frame without a metadata row is
  skipped, as is a row without a frame. Both are counted in `export_report.txt`.
- **Image timestamp:** `lens_time_ns` when present, i.e. the real exposure start of a pinned
  physical lens (on the Pixel 6 ultrawide it differs from `time_ns` by up to ~0.8 ms). Otherwise
  `time_ns`.
- **Trimming:** images before the first and after the last IMU sample are dropped, because the IMU
  starts ~100 ms after the first frame. VIO systems need IMU data before the first image.
- **`sensor.yaml` files are START VALUES.**
  - **Camera intrinsics:** the phone's **per-frame** focal length and principal point of the lens
    that recorded (`LENS_INTRINSIC_CALIBRATION`, median over the frames), converted from that
    lens's sensor pixels to the video (centred crop to the video's aspect, scale, rotation by
    `SENSOR_ORIENTATION`; tested). They include focus breathing: Pixel 6 main lens at 0.65 dpt,
    899 px vs. the factory 877 px. Valid when the zoom shows the lens's full field (1.0 for the
    main lens, the minimum zoom for the ultrawide). The static factory values stay in
    `camera.json` as `factory_intrinsics_logical`.
  - **Distortion:** the recording lens's factory `LENS_DISTORTION`, rotated to the video
    (tangential terms; tested). EuRoC's radial-tangential has no k3, and dropping it moves the
    Pixel 6 main lens's corners by ~56 px, so k1, k2 are **refit** over the image to include k3
    (~0.6 px mean, a few px in the far corner; tested). All five terms are in `camera.json`.
    Recordings from app versions before 2026-11-11 carry no distortion; the report warns.
  - **Transforms:** `T_BS` is identity for both sensors. **IMU noise values** are placeholders.

  Use these intrinsics with `tools/kalibr_calib/calibrate.sh imucam ... --camchain factory`, and
  take `T_cam_imu` and `timeshift_cam_imu` from that result; IMU noise from an **Allan-variance**
  analysis of a long static recording.
- **Frames:** the IMU follows the Android device frame (x right, y up, z out of the screen). The
  camera follows the image frame of the stored (portrait) video. The rotation between them comes
  from the calibration, not from this tool.

## Tests

```bash
docker run --rm -v "$PWD/tools/pb3_export:/tool:ro" pb3-export:<tag> python -I /tool/test_pb3_export.py
```

The tests check the tangential-distortion rotation numerically: distort-then-rotate equals
rotate-then-distort with the converted coefficients, for all four orientations. They also check
that a wrong rotation direction would fail.
