# 04 – Features & settings

## What the app records

| Stream | Content | Notes |
|---|---|---|
| **Video** | H.264 (default) or H.265/HEVC MP4, about 30 fps | Presentation time of each frame = its camera (`SurfaceTexture`) timestamp |
| **Per-frame camera metadata** | timestamp, exposure time, ISO, frame duration, rolling-shutter skew, focal length, focus distance, crop region, OIS samples (if available), **applied** OIS / EIS / distortion-correction mode, white balance, noise profile, per-frame intrinsics where the phone provides them | `VideoFrameMetaData`; one per frame |
| **Camera info (per clip)** | resolution, sensor orientation, factory intrinsics and distortion, lens pose relative to the gyroscope, **timestamp source** (REALTIME/UNKNOWN) | `CameraInfo` |
| **IMU** | gyro + accel + magnetometer, uncalibrated with bias, interpolated to gyro times, at the sensor's maximum rate | `IMUData`; see [architecture](03-app-architecture.md#how-imu-samples-are-synchronised) |
| Barometer, step counter, rotation vectors, ambient light | optional, if the phone has them | rotation vectors are OS-fused orientation, not raw |
| GNSS | 1 Hz fixes + raw measurements, joined to the sensor clock | needs the location permission |
| Thermal / battery | temperature, thermal status, every 5 s | useful to explain drift in long recordings |
| **Stills** | JPEG/RAW, brackets, focus stacks, stereo pairs | for photogrammetry and 3D reconstruction; not needed for VIO |
| `camera_census.json` | every characteristic of every camera, written at each launch | read this first on a new phone |
| `session.json` | what the session contains vs. what was requested, frame accounting, warnings | written at stop |

Everything stays on the phone. There is no network or analytics code.

## Capture modes

The mode is chosen on the main screen. For VIO you only need the **Record** (video) button; the
camera (stills) button is for photogrammetry.

| Mode | Stills behaviour | Video |
|---|---|---|
| **WALK** | Press to start/stop. JPEG stills fire automatically at quiet moments of your gait, with one RAW at each end | Record works normally; folder `walk_vid_<date>` |
| **OBJECT** | One press = focus stack + exposure bracket + RAW, for a stationary subject | same |
| **PANO** | Tripod/gimbal: stillness-triggered with an exposure bracket per position | same |

## Settings (gear icon)

These are defined in `android_app/app/src/main/res/xml/settings.xml`. The **VIO recommendation**
column is our suggestion for SLAM datasets; check each one on your phone.

### General

| Setting | What it does | VIO recommendation |
|---|---|---|
| Camera Lens | "Logical" (default: the phone picks the physical lens) or a specific physical camera ID | **the main camera's physical ID**, not "Logical", so the lens can never switch; see [Focal length, resolution and lens selection](#focal-length-resolution-and-lens-selection) |
| Maximize Sensor Area | only list resolutions that use the full sensor (4:3 on most phones) | **on**: full field of view, and the scaled factory intrinsics are only right for full-sensor sizes |
| Video Size | resolution | a moderate **4:3** size, e.g. 1280×960 or 1920×1440; keep it fixed across calibration and data |
| Zoom ratio | digital zoom / crop; on a logical camera a ratio **below 1.0** typically switches to the ultrawide | **1.0**, and never change it after calibration |
| Enable Distortion Correction | lets the phone undistort the image | **off**, so the SLAM system sees the raw lens model you calibrated |
| Lenses in the session | which physical cameras get a stream (default "Metric pair": main + ultrawide) | the video still comes from the main camera; check on your phone whether the extra stream limits the sizes or frame rates you can pick |
| Focus at hyperfocal | in manual focus, set focus to the hyperfocal distance | useful with manual focus |
| Raw pixels for the matcher | turns the phone's sharpening and noise reduction off | worth testing: sharpening halos can confuse feature matchers |

### Stabilization: the important part

| Setting | What it does | VIO recommendation |
|---|---|---|
| **Enable OIS** | optical image stabilization (the lens physically moves) | **off** if your phone allows it. OIS changes the effective intrinsics frame by frame. |
| **Enable OIS data** | log the OIS lens-shift samples per frame | **on**. Some phones accept this but send no samples (S24 Ultra), so check the data. The Pixel 6 lists `android.statistics.oisSamples` as a result key, but this hasn't been tested yet. |
| **Enable DVS** | digital/electronic stabilization (warps and crops the image) | **off, always**. EIS breaks the camera model. |

Whatever you request, the **actually applied** OIS/EIS mode is stored per frame. You can prove
in your data that stabilization was off.

### Auto Focus

| Setting | VIO recommendation |
|---|---|
| Auto Focus Mode: Continuous / Fix on Touch / Manual | **Manual**. Focus changes the focal length measurably (on the Pixel 6, up to ~7%; see [lens breathing](#focus-changes-the-focal-length-lens-breathing)), so keep it constant during a recording and between calibration and data. With Fix on Touch, focus locks wherever you tapped; in our first test that was ~27 cm. |
| Focus Distance | in **diopters** (1 / metres): 0 = infinity, 0.5 = 2 m, 3.65 = 27 cm. For a room, about 0.3–0.5 diopters (2–3 m). |

### Auto Exposure

| Setting | VIO recommendation |
|---|---|
| Auto Exposure Mode / Exposure Time / ISO | **Manual, about 3–8 ms**, raising ISO as needed. Auto exposure indoors picks the whole frame time: our first Pixel 6 test got **33.3 ms at ISO 711**, which blurs badly when moving. |
| Freeze exposure while recording (default **on**) | on: one fixed exposure per clip, consistent images. Off only if light changes a lot during the recording. |
| Blur budget (experimental, default off) | leave off unless you test it; untested on a moving phone |

### Recording

| Setting | Default | VIO recommendation |
|---|---|---|
| Video codec | H.264 (`video/avc`) | H.264 is the most compatible with SLAM tools and converters. HEVC halves file size. |
| Video bitrate | 0 = automatic | automatic is high quality; raise it if you see compression artefacts |
| Stereo pair every (s) | 0 (off) | off |
| **IMU batching** | 0 (off) | **off**. Batching only delays delivery (timestamps stay correct), but there's no need for it. |
| Camera sleep after | 60 s idle | fine; the camera wakes when you tap |

## Focal length, resolution and lens selection

### What is recorded

| Field | Source | Changes with frame size? |
|---|---|---|
| `video_meta[i].focal_length_mm` | per frame, from the phone (`LENS_FOCAL_LENGTH`): the lens's **physical** focal length | **no**; it depends only on which lens is used |
| `video_meta[i].est_focal_length_pix` | per frame, computed by the app (`FocalLengthHelper.getFocalLengthPixel()`): focal length corrected for focus distance (thin lens) × pixels per mm × (image width ÷ crop width) | **yes**; it scales with resolution, and changes slightly when focus moves |
| `camera_meta.intrinsic_params` | factory calibration (`LENS_INTRINSIC_CALIBRATION`), rescaled by the app to the video resolution and rotated to the stored orientation (`FocalLengthHelper.getTransformedIntrinsic()`) | **yes**, scaled; see the caveat below |
| `camera_meta.original_intrinsic_params` | the same factory values, unscaled (full-sensor coordinates) | no |
| `video_meta[i].lens_intrinsic_calibration` | per frame, only if the phone reports intrinsics dynamically | phone-dependent |

In **pixels**, the focal length is proportional to the resolution. 4080 px versus 1280 px wide is a factor of 3.19, with the same
field of view. A Kalibr calibration is therefore valid for **one resolution**. `data2rosbag.py
--calibration` can rescale it, but it's simplest to record calibration and data at the same
size.

### Frame size never switches lenses

Which physical lens supplies the image depends only on:

1. **Camera Lens.** With the default **"Logical"**, the phone decides which physical lens to use.
   With a physical camera ID, the app pins the stream to that lens
   (`OutputConfiguration.setPhysicalCameraId`).
2. **Zoom ratio.** On a logical camera, a ratio below 1.0 typically switches to the ultrawide.
   Some phones also switch lenses on their own in certain conditions, such as close focus or low
   light.

For VIO, select the **main camera's physical ID** and keep zoom at 1.0. Find the ID in
`camera_census.json`: it's the physical camera with the main lens's focal length
(`LENS_INFO_AVAILABLE_FOCAL_LENGTHS`). Then check every recording: `focal_length_mm` must be
**constant**. A jump to a much smaller value, around 2 mm, means the ultrawide was used.

### Use 4:3 (full-sensor) sizes

Most phone sensors are 4:3.

- **4:3 sizes** (e.g. 1280×960, 1920×1440, full resolution) use the whole sensor. Every size has
  the same field of view; only the pixel count changes.
- **16:9 sizes** (1920×1080, 4K) **crop** the top and bottom of the sensor, so the vertical field
  of view is narrower.

> **Known issue: scaled factory intrinsics are wrong for cropped sizes.**
> `getTransformedIntrinsic()` scales fx, fy, cx and cy by `image width ÷ sensor width` (it assumes
> the longer side is uncropped), but it **doesn't subtract the crop offset** from the principal point.
> Example: for a 1920×1080 video from a 4:3 sensor, cy comes out at about 720 instead of about 540.
> `est_focal_length_pix` uses the real crop region and is not affected. For full-sensor 4:3 sizes
> the scaled values are fine (apart from small active-array offsets). Either way, treat
> factory intrinsics as a sanity check and **calibrate with Kalibr** at your recording size.

Much larger frames don't help VIO. They cost encoder load, heat and disk space, while
feature trackers usually work at about 640–1280 px width. Pick a moderate 4:3 size.

### Focus changes the focal length (lens breathing)

Moving the focus moves the lens, which changes the effective focal length. A single Pixel 6 test
recording (960×1280, focus locked at 3.65 diopters ≈ 27 cm) contains three different values:

| Source | fx (scaled to 960×1280) |
|---|---:|
| `camera_meta.intrinsic_params` (static factory calibration) | 876.7 px |
| `est_focal_length_pix` (app's thin-lens estimate from the focus distance) | 912.9 px |
| `lens_intrinsic_calibration` per frame (phone's dynamic value, 2980.8 × 0.3137) | 935.1 px |

That's up to a 7% difference. So: **manual focus, the same value for calibration and data, and
calibrate with Kalibr at that focus.** `lens_intrinsic_calibration` is stored in full-sensor,
unrotated coordinates (4080×3072). Scale it by `resolution ÷ 4080` and rotate it like
`intrinsic_params` before comparing.

### Orientation

The app is locked to portrait, and phone camera sensors are usually mounted rotated
(`SENSOR_ORIENTATION` 90°). The stored width and height may therefore be swapped relative to the
size you selected (e.g. 960×1280 instead of 1280×960). The app swaps fx/fy and cx/cy accordingly.
Check `camera_meta.resolution` in your first recording.

### Pixel 6 (measured)

| | Value |
|---|---|
| Main lens | physical camera **ID 2**, 6.81 mm, sensor 9.79×7.37 mm, 4080×3072 |
| Ultrawide | physical camera **ID 3**, 2.35 mm |
| Logical rear camera | ID **0** = {2, 3}, zoom 0.67–7.0 (below 1.0 means the ultrawide) |
| Recommended Camera Lens setting | **Physical ID 2**. The logical camera reports zero distortion; ID 2 reports `[0.128, -0.198, 0.121, 0, 0]`. |

All Pixel 6 values are collected in [08 – Tested devices](08-tested-devices.md).

## Things to check once per phone

1. Open the app once, then look at `camera_census.json`:
   - `SENSOR_INFO_TIMESTAMP_SOURCE`: **REALTIME** (1) means camera and IMU share a clock. UNKNOWN
     (0) means they may not; tell the maintainers.
   - Can OIS be turned off? (`LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` lists 0 = OFF.)
   - Does the phone advertise OIS data (`STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES`)?
   - Are factory intrinsics, distortion and lens pose present?
   - Which physical camera ID is the main lens? Select it under **Camera Lens**.
2. Record about 20 s while shaking and rotating the phone, then check
   (see [05 – Output data](05-output-data.md#sanity-checks)):
   - the IMU covers the whole video;
   - the measured IMU rate;
   - there are no frame holes;
   - the applied OIS/EIS mode is 0 on all frames.
   - `focal_length_mm` is constant (no lens switch) and `camera_meta.resolution` is what you expect.
3. Calibrate with Kalibr ([07](07-calibration-and-tools.md)) and repeat a few times. The
   camera–IMU time offset should be stable between recordings.
