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
| Maximize Sensor Area | only list resolutions with the sensor's aspect ratio (within 1%, so standard 4:3 sizes count on a 4080×3072 sensor). Only filters the list: it never changes the stored size. | **on**: full field of view, and the scaled factory intrinsics are only right for full-sensor sizes |
| Video Size | resolution. With a physical lens selected under Camera Lens, only sizes that lens can deliver are offered, and the size is re-checked every time the camera opens. A size the current list doesn't contain is shown as "(current, not full sensor)" and stays selected until you pick another. The main screen shows the size the camera was **opened** with; it updates when the camera reopens. | a moderate **4:3** size, e.g. 1280×960 or 1920×1440; keep it fixed across calibration and data |
| Zoom ratio | digital zoom / crop; on a logical camera a ratio **below 1.0** typically switches to the ultrawide | **1.0**, and never change it after calibration |
| Enable Distortion Correction | lets the phone undistort the image | **off**, so the SLAM system sees the raw lens model you calibrated |
| Lenses in the session | which physical cameras get a stream (default "Metric pair": main + ultrawide) | the video still comes from the main camera; check on your phone whether the extra stream limits the sizes or frame rates you can pick |
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

### Orientation

The app is locked to portrait, and phone camera sensors are usually mounted rotated
(`SENSOR_ORIENTATION` 90°). The stored width and height may therefore be swapped relative to the
size you selected (e.g. 960×1280 instead of 1280×960). The app swaps fx/fy and cx/cy accordingly.
Check `camera_meta.resolution` in your first recording.

### Pixel 6 (our phone; not yet measured)

Spec sheets give the main camera about **6.8 mm** physical focal length: a 50 MP sensor, normally
used binned to 12.5 MP (4080×3072, 4:3). The ultrawide is about 114°. **Replace these numbers with the values from
the Pixel 6's `camera_census.json` and a first recording**, including the physical camera IDs,
timestamp source and OIS modes.


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
