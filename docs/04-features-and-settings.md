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
| Camera Lens | which camera to record | the main rear camera, and keep the same one for calibration and data |
| Maximize Sensor Area | only list resolutions that use the full sensor | on (no crop, so the full field of view) |
| Video Size | resolution | moderate, e.g. 1280×960 or 1920×1440; keep it fixed across calibration and data |
| Zoom ratio | digital zoom / crop | 1.0, and never change it after calibration |
| Enable Distortion Correction | lets the phone undistort the image | **off**, so the SLAM system sees the raw lens model you calibrated |
| Lenses in the session | which physical cameras get a stream (default "Metric pair": main + ultrawide) | the default is fine; streams only matter for stereo stills |
| Focus at hyperfocal | in manual focus, set focus to the hyperfocal distance | useful with manual focus |
| Raw pixels for the matcher | turns the phone's sharpening and noise reduction off | worth testing: sharpening halos can confuse feature matchers |

### Stabilization: the important part

| Setting | What it does | VIO recommendation |
|---|---|---|
| **Enable OIS** | optical image stabilization (the lens physically moves) | **off** if your phone allows it. OIS changes the effective intrinsics frame by frame. |
| **Enable OIS data** | log the OIS lens-shift samples per frame | **on**. Some phones accept this but send no samples (S24 Ultra), so check the data. |
| **Enable DVS** | digital/electronic stabilization (warps and crops the image) | **off, always**. EIS breaks the camera model. |

Whatever you request, the **actually applied** OIS/EIS mode is stored per frame. You can prove
in your data that stabilization was off.

### Auto Focus

| Setting | VIO recommendation |
|---|---|
| Auto Focus Mode: Continuous / Fix on Touch / Manual | **Manual** or **Fix on Touch**. Focus changes the focal length slightly, so keep it constant during a recording and between calibration and data. |
| Focus Distance | for manual mode, e.g. hyperfocal or the typical scene distance |

### Auto Exposure

| Setting | VIO recommendation |
|---|---|
| Auto Exposure Mode / Exposure Time / ISO | Short exposure to limit motion blur (e.g. ≤ 5–10 ms), raising ISO if needed. Manual mode gives a stable frame rate. |
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

## Things to check once per phone

1. Open the app once, then look at `camera_census.json`:
   - `SENSOR_INFO_TIMESTAMP_SOURCE`: **REALTIME** (1) means camera and IMU share a clock. UNKNOWN
     (0) means they may not; tell the maintainers.
   - Can OIS be turned off? (`LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` lists 0 = OFF.)
   - Does the phone advertise OIS data (`STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES`)?
   - Are factory intrinsics, distortion and lens pose present?
2. Record about 20 s while shaking and rotating the phone, then check
   (see [05 – Output data](05-output-data.md#sanity-checks)):
   - the IMU covers the whole video;
   - the measured IMU rate;
   - there are no frame holes;
   - the applied OIS/EIS mode is 0 on all frames.
3. Calibrate with Kalibr ([07](07-calibration-and-tools.md)) and repeat a few times. The
   camera–IMU time offset should be stable between recordings.
