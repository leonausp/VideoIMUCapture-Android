# 08 – Tested devices

These are values measured on phones we have actually used. Add a section for each new phone, using
the app's `camera_census.json` and a short test recording.

## Google Pixel 6 (`oriole`)

**Tested:** 2026-11-10. App `0.25-zf.1` (git `02014bf`). OS: Android 17 (SDK 37), build
`google/oriole/oriole:17/CP3A.261005.002.A1/2026110201:user/release-keys`.

### Cameras (from `camera_census.json`)

| ID | Type | Focal length | Sensor | Array (pre-correction) | Orientation | OIS modes | Notes |
|---|---|---|---|---|---|---|---|
| **0** | rear, **logical** = {2, 3} | 6.81 mm | 9.79×7.37 mm | 4080×3072 | 90° | [0, 1] | zoom 0.67–7.0; reports **zero** distortion |
| **2** | rear, physical: **main** | 6.81 mm | 9.79×7.37 mm | 4080×3072 | 90° | [0, 1] | distortion `[0.128, -0.198, 0.121, 0, 0]` |
| **3** | rear, physical: ultrawide | 2.35 mm | 5.04×3.77 mm | 4032×3016 | 90° | [0] | pose vs. main: t ≈ (-14.8, -0.7, -1.0) mm |
| 1 | front | 2.51 mm | 3.67×2.76 mm | 3280×2464 | 270° | [0] | |

| Property | Value | Meaning |
|---|---|---|
| `SENSOR_INFO_TIMESTAMP_SOURCE` | **REALTIME (1)** on all cameras | Camera frames and IMU share one clock ✅ |
| Hardware level | FULL (1) | |
| OIS off possible (main) | yes, `[0, 1]` | ✅ |
| `STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES` | not advertised | but `android.statistics.oisSamples` **is** in the result keys, so try "Enable OIS data" |
| Factory intrinsics (main, full array) | fx = fy = 2794.39, cx = 2031.59, cy = 1547.81 | static; the per-frame value differs with focus (see below) |
| `LENS_POSE_REFERENCE` | PRIMARY_CAMERA (0) | No factory camera-to-IMU extrinsic: **calibrate with Kalibr** |
| Video stabilization modes | [0, 1, 2] | keep at 0 (OFF) |
| AE frame-rate ranges | [15,15] [15,30] [30,30] [15,60] [60,60] | |
| Exposure range / ISO range | 26.5 µs – 8.3 s / 44 – 11377 | |
| Minimum focus distance | 9.52 diopters (≈ 10.5 cm), calibration APPROXIMATE | |

### IMU and other sensors

| Sensor | Part | Min period | Max range | Resolution |
|---|---|---|---|---|
| Accelerometer (uncalibrated) | STMicro LSM6DSR | 2404 µs (416 Hz) | 156.9 m/s² | 0.0048 m/s² |
| Gyroscope (uncalibrated) | STMicro LSM6DSR | 2404 µs (416 Hz) | 34.9 rad/s | 0.0012 rad/s |
| Magnetometer (uncalibrated) | MEMSIC MMC56X3X | 10000 µs (100 Hz) | 3198 µT | 0.098 µT |
| Barometer | InvenSense ICP10101 | 40000 µs | 1100 hPa | 0.0001 hPa |

### First test recording (`walk_vid_2026_11_10_21_30_04`, 5.5 s, phone almost still; recorded with an earlier build, hence the old folder name)

Settings: logical camera, 960×1280, H.264, auto exposure frozen, focus fixed by tap, OIS/DVS off.

| Check | Result |
|---|---|
| Video | 128 metadata rows, **29.87 fps**, every interval exactly 33.48 ms, frame numbers contiguous, `FrameAccounting.complete = true`; MP4 has 129 frames (one without a row) |
| Resolution / orientation | stored **960×1280** (portrait), `sensor_orientation` 90 |
| Lens | `focal_length_mm` = 6.81 on every frame, so no lens switching |
| Stabilization actually applied | OIS = 0, EIS = 0, distortion correction = 0 on **all** frames ✅ |
| OIS data | off in this test (`ois_data_mode` = 0) |
| Rolling-shutter readout (`frame_readout_ns`) | **13.57 ms** |
| Exposure / ISO | 33.33 ms / 711, AE locked. **Too long for VIO**; use manual 3–8 ms |
| Focus | locked at 3.65 diopters (≈ 27 cm, from the tap) |
| Intrinsics at 960×1280 | factory scaled: fx = 876.7, cx = 473.4, cy = 637.4 (correct for this 4:3 size); thin-lens estimate 912.9; per-frame HAL 935.1, i.e. **focus breathing up to ~7%** |
| IMU | **444.8 Hz**, interval 2.22–2.28 ms, no gaps, monotonic; 1899 samples |
| IMU units | median \|a\| = 9.81 m/s² ✅; `accel_bias` all 0 (not estimated by the OS); `gyro_drift` populated; magnetometer present |
| IMU coverage | starts **134 ms after** the first frame, ends 150 ms after the last; drop the first ~4 frames |
| Other streams | barometer 107, OS orientation 1428, thermal 1, light 2, steps 1 (baseline); GNSS 0 (indoors / no permission) |

### Ultrawide (physical camera 3), measured 2026-11-10

Measured with the `fix/physical-camera` build, phone still, compared with the main lens at 960×1280:

| Setting | Field of view vs. main | Notes |
|---|---|---|
| Camera Lens 3, zoom 1.0 | 1.02 (same as main) | the driver crops the ultrawide to the main lens's field of view |
| **Camera Lens 3, zoom fully left (0.67)** | **0.72: full ultrawide** | metadata all from camera 3 ✅ |
| Logical, zoom 0.67, "pair" | 1.04 (main lens) | the logical camera does **not** switch with extra streams in the session |
| Logical, zoom 0.67, **Video only** | 0.69: full ultrawide | switches (`active_physical_camera_id` = 3), but static intrinsics are the main lens's (flagged) |

Ultrawide values (pinned, 960×1280):

| | Value |
|---|---|
| Focal length | 2.35 mm, fixed focus (no OIS, no focus breathing) |
| fx (factory, scaled) / app estimate | 598.9 px / 598.4 px |
| Distortion (factory) | `[0.0956, -0.1026, 0.0163, -0.0001, -0.0005]`: calibrate with a fisheye/equidistant model |
| **Rolling-shutter readout** | **32.4 ms** (main lens: 13.6 ms) |
| `lens_time_ns − time_ns` | median +0.28 ms, range −0.37…+0.79 ms (sensor sync "approximate") |
| ISO reported at 5 ms | 1354 (main: 957 for the same scene) |

**For VIO with the ultrawide: Camera Lens = 3, zoom fully left, Lenses in the session = Video only.** Use `lens_time_ns` as the image time.

### Recommended settings for this phone

- **Camera Lens:** physical ID **2**; zoom 1.0.
- **Video Size:** 4:3 with "Maximize Sensor Area", e.g. 1280×960 (stored as 960×1280), or
  1920×1440 if you also want Gaussian splats.
- **Exposure:** manual, 3–8 ms, ISO as needed; "Freeze exposure while recording" on.
- **Focus:** tap to focus (centre at start), **locked when recording** (the default). The main lens's focus scale is
  offset: phone value ≈ 0.40 + 1.08 × real diopters (tap-to-focus on a target, distances from its
  size, 2026-11-11), so "infinity" is 0.40, the computed hyperfocal (0.31) is beyond it, and a fixed
  number is phone-specific. For Manual, 1.1 ≈ 1.5 m. The ultrawide is fixed-focus.
- **OIS off, DVS off, distortion correction off; OIS data on** (to be verified).

### Still to measure

- Whether OIS samples arrive with "Enable OIS data" on.
- Recording with physical ID 2: does `camera_meta.distortion_params` get filled, and do the
  available sizes and frame rates change?
- Kalibr camera–IMU calibration: time offset and its repeatability across recordings.
- Long recordings: thermal throttling and IMU/frame stability over several minutes.

## Samsung Galaxy S21 (`SM-G991B`, Android 15)

First check on 2026-11-11: one 6.6 s clip with the recommended settings (tap to focus), plus the
camera census (`data/other_phones/census_SM-G991B.json`, not committed).

| | Value | Consequence |
|---|---|---|
| Timestamp source | **REALTIME** | camera and IMU on one clock |
| IMU | uncalibrated gyro, accelerometer and magnetometer; **500 Hz**, perfectly regular | |
| Rear camera 0 | standalone (not a logical multi-camera), 5.4 mm, zoom 1.0–8.0 | the ultrawide (camera 2, 2.2 mm) is a separate id the app does not open; zoom < 1 does not exist here |
| Video size | **1440×1080** (stored 1080×1440); 1280×960 is not offered | the app picks the next 4:3 size |
| Factory intrinsics | fx 3073.8 sensor px → **1097.8 px** in the video; per-frame value constant (no focus breathing modelled) | start values fine |
| Factory distortion | **zeros**; no distortion-correction control | unknown whether the image is corrected internally: calibrate the lens or let the SLAM system estimate it; the export warns |
| Focus scale | `CALIBRATED` (the Pixel 6's is `APPROXIMATE`) | |
| Rolling-shutter readout | 16.6 ms | expected camera–IMU time offset ≈ 8.3 ms + 1–2 ms |
| ISO | maximum **3200** | at 5 ms, dim rooms are underexposed (▼2.6 stops seen); add light |
| Camera–IMU pose | not reported (`LENS_POSE_REFERENCE` = PRIMARY_CAMERA) | Kalibr as on the Pixel |
| Clip | 149 frame rows, none lost; spacing 33.29–33.36 ms; exposure 5 ms, ISO 3200, focus 2.05 constant; OIS/EIS off | |
