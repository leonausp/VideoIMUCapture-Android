# 04 – Features & settings

## What the app records

| Stream | Content | Notes |
|---|---|---|
| **Video** | H.264 (default) or H.265/HEVC MP4, about 30 fps | Presentation time of each frame = its camera (`SurfaceTexture`) timestamp |
| **Per-frame camera metadata** | timestamp, exposure time, ISO, frame duration, rolling-shutter skew, focal length, focus distance, crop region, OIS samples (if available), **applied** OIS / EIS / distortion-correction mode, white balance, noise profile, per-frame intrinsics where the phone provides them | `VideoFrameMetaData`; one per frame |
| **Camera info (per clip)** | resolution, sensor orientation, factory intrinsics and distortion, lens pose relative to the gyroscope, **timestamp source** (REALTIME/UNKNOWN) | `CameraInfo` |
| **IMU** | gyro + accel + magnetometer, uncalibrated with bias, interpolated to gyro times, at the sensor's maximum rate | `IMUData`; see [architecture](03-app-architecture.md#how-imu-samples-are-synchronised) |
| Barometer, rotation vectors, ambient light | optional, if the phone has them | rotation vectors are OS-fused orientation, not raw |
| ~~GNSS~~, ~~step counter~~ | **not recorded.** Location and step counting aren't needed for VIO, so the app has no location or "Physical activity" permission and doesn't log them. The only permission it asks for is the camera. | the `gnss*` and `steps` fields stay empty |
| Thermal / battery | temperature, thermal status, every 5 s | useful to explain drift in long recordings |
| **Stills** | JPEG/RAW, brackets, focus stacks, stereo pairs | for photogrammetry and 3D reconstruction; not needed for VIO |
| `camera_census.json` | every characteristic of every camera, written at each launch | read this first on a new phone |
| `session.json` | what the session contains vs. what was requested, frame accounting, warnings | written at stop |

Everything stays on the phone. There is no network or analytics code.

## Capture modes (hidden in this build)

This app is set up for VIO, so the main screen shows **only the Record (video) button**. The
previous fork's stills features are hidden:
- the stills button and the WALK / OBJECT / PANO strip;
- the **TESTS** menu entry;
- the "Stereo pair every (s)" setting.

"Lenses in the session" defaults to **Video only**. **Recordings** stays: the gallery button,
bottom left.
- **List:** one list, newest first, showing date, length and size. An extra line appears only
  when a session has a warning, e.g. lost frame records.
- **Opening a recording:** tap it to open and play it. **ⓘ Info** shows the details: folder
  name, recorded streams, and delete.

The code is still there. To bring the features back, set
`buildConfigField "boolean", "STILLS_UI", "true"` in `android_app/app/build.gradle` and rebuild.
For reference, this is what the modes do. In this build, video sessions are named
`vid_<date>` with mode `VIDEO`. With the stills features enabled, they're named after the
current mode instead (`walk_vid_<date>` etc.).

| Mode | Stills behaviour | Video |
|---|---|---|
| **WALK** | Press to start/stop. JPEG stills fire automatically at quiet moments of your gait, with one RAW at each end | Record works normally; folder `walk_vid_<date>` |
| **OBJECT** | One press = focus stack + exposure bracket + RAW, for a stationary subject | same |
| **PANO** | Tripod/gimbal: stillness-triggered with an exposure bracket per position | same |

## Focus on the main screen

The top button of the stack in the bottom-right corner shows the focus: **"Focus: tap"** (default),
**"Focus: AF"** or **"Focus 1.10"** (a fixed value). Tapping it opens a bar:

- **TAP**: tap to focus. The preview focuses once on the image centre, then wherever you tap.
- **AF**: continuous autofocus.
- **−** / value / **+**: a fixed focus in 0.1 steps (diopters on the phone's scale, bigger = closer),
  starting from where the lens is now, so you can tap to focus and then freeze exactly that. Tap
  the value to type one. "Focus: fixed lens" means the lens has no focus to set (Pixel 6 ultrawide).

In every mode the focus is **locked when you press RECORD** and stays put for the whole clip; taps
are ignored while recording.

## Exposure on the main screen

Two stacked buttons in the **bottom-right corner**, level with RECORD, show the **exposure time**
and the **ISO**. The **torch** button is hidden: its light moves with the camera, so the scene's
brightness changes with every viewpoint (bad for tracking), and it only reaches ~1–2 m. Set
`TORCH_UI` to `true` in `app/build.gradle` to bring it back.
- **Adjusting:** tap one to open a bar with **−**, the **value** and **+** (⅓-stop steps). Tap
  the value to type an exact number. For ISO there's also **AUTO**. Changes apply to the preview
  immediately, and they're the same settings as on the settings screen.
- **Locked while recording,** so the exposure can't change mid-clip.

The **default mode is shutter priority**:
- **Fixed exposure time:** 5 ms, chosen for little motion blur.
- **ISO is metered per scene:** the preview runs auto exposure purely as a light meter, and the
  ISO button shows the ISO that gives the same brightness at your exposure time ("ISO auto
  4740"). At **record start** that ISO and the exposure are **locked** for the whole clip.
- **Bright scenes:** if even the minimum ISO would over-expose, the exposure is **shortened**
  instead, which means even less blur. The button then shows ☀ after the time.
- **Dark scenes:** if even the maximum ISO isn't enough, the ISO button shows how many stops the
  frames will be too dark ("▼1.5"). Add light, or accept a longer exposure.
- **Fixed ISO:** pressing **+** or **−** on ISO switches to fully manual; **AUTO** switches back
  to shutter priority.

## Settings (gear icon)

The screen has a **basic section** at the top:
- **Exposure Time** and **ISO**, the values you change per scene;
- **Focus**: tap to focus (default), AF, or Manual with a distance, all locked when recording;
  the same choice as the focus button on the main screen;
- **Zoom ratio** (below 1 = wide lens);
- **Show expert settings**, a switch that reveals all the categories below (lens, video size,
  stabilisation, exposure mode, recording);
- **Reset to recommended settings**, which puts everything back to the recommended values for
  VIO: main lens, Video only, 1280×960, shutter priority at 5 ms (manual ISO starts at 300), manual
  tap to focus (locked when recording), OIS off with OIS data on, no EIS or distortion correction.

These defaults live in one place, `RecommendedDefaults.java`. A unit test checks them, and also
checks that `settings.xml` declares the same defaults.


These are defined in `android_app/app/src/main/res/xml/settings.xml`. The **VIO recommendation**
column is our suggestion for SLAM datasets; check each one on your phone.

### General

| Setting | What it does | VIO recommendation |
|---|---|---|
| Camera Lens | "Logical" (default: the phone picks the physical lens) or a specific physical camera ID. With a physical ID the video stream is pinned to that lens, and the per-frame metadata and `CameraInfo` are read for **that lens** (`metadata_camera_id` in the file says which). At zoom 1.0 the phone may crop a wider lens to the main camera's field of view; see [Lens selection on a logical camera](#lens-selection-on-a-logical-camera). | **the main camera's physical ID**, not "Logical", so the lens can never switch |
| Maximize Sensor Area | only list resolutions with the sensor's aspect ratio (within 1%, so standard 4:3 sizes count on a 4080×3072 sensor). Only filters the list: it never changes the stored size. | **on**: full field of view, and the scaled factory intrinsics are only right for full-sensor sizes |
| Video Size | resolution. With a physical lens selected under Camera Lens, only sizes that lens can deliver are offered, and the size is re-checked every time the camera opens. A size the current list doesn't contain is shown as "(current, not full sensor)" and stays selected until you pick another. The main screen shows the size the camera was **opened** with; it updates when the camera reopens. | a moderate **4:3** size, e.g. 1280×960 or 1920×1440; keep it fixed across calibration and data |
| Zoom ratio | zoom / crop. Slider steps are round values (…, 0.9, **1.0**, 1.1, …) plus the exact minimum and maximum. **Fully left** = the widest zoom (0.67 on the Pixel 6), which gives the full ultrawide field of view with Camera Lens = ultrawide. | **1.0** for the main lens; **fully left** for the ultrawide. Never change it after calibration. |
| Enable Distortion Correction | lets the phone undistort the image | **off**, so the SLAM system sees the raw lens model you calibrated |
| Lenses in the session | which extra streams the session holds: "Metric pair" (default; main + ultrawide stereo streams, plus JPEG/RAW), "All rear lenses", or **"Video only"** (nothing but the video stream; stills and stereo pairs are then disabled). Takes effect when the camera reopens. | **Video only** for VIO: every extra stream is a constraint on the camera driver |
| Focus at hyperfocal | in manual focus, use the hyperfocal distance computed from the lens data instead of the Focus Distance slider | **off**: on the Pixel 6 the computed value (0.31) is beyond infinity on the phone's offset focus scale (see Focus Distance) |
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
| Auto Focus Mode: Auto / Tap to focus / Manual | **Tap to focus, locked when recording** (default): the preview focuses once on the image centre when it starts, then wherever you tap; at RECORD the lens is held there for the whole clip, and taps are ignored while recording. **Auto** focuses continuously (it sometimes picks odd targets) and is also locked at RECORD. Focus changes the focal length measurably (Pixel 6 main lens: ~1.4 % per diopter, up to ~7 %; see [lens breathing](#focus-changes-the-focal-length-lens-breathing)), so it must not change **within** a clip. **Between** clips it may: the export takes each clip's per-frame focal length (`LENS_INTRINSIC_CALIBRATION`), and the camera–IMU calibration does not depend on focus. **Manual** fixes a distance (below). |
| Focus Distance | in **diopters** (1 / metres) on the **phone's own scale**, which the Pixel 6 marks APPROXIMATE and which is offset: tap-to-focus on a target at distances measured from the target's size gave **phone value ≈ 0.40 + 1.08 × real diopters** (0.59–2.05 m, 2026-11-11). So the phone's "infinity" is 0.40, and 0.65 is really ~4 m. Used in **Manual** only; **default 1.1 (≈ 1.5 m real)**: defocus blur below ~2 px from ~0.8 m to infinity (calculated, f/1.85 at 1280×960). For mostly distant scenes ~0.55 (≈ 7 m). A fixed number does not carry across phones, which is why the default is autofocus. The ultrawide is fixed-focus and ignores this. |

### Auto Exposure

| Setting | VIO recommendation |
|---|---|
| Auto Exposure Mode / Exposure Time / ISO | **Shutter priority** (default): exposure time fixed at about **3–8 ms** (default 5 ms), ISO metered per scene and locked at record start. Use **Manual** only if you want a fixed ISO. Plain auto exposure indoors picks the whole frame time (our first Pixel 6 test: 33.3 ms at ISO 711), which blurs badly. Sliders snap to ⅓-stop values (exposure max 30 ms to keep 30 fps; ISO up to the phone's maximum, 11,377 on the Pixel 6). **Tap the number or the row to type an exact value.** The same controls are on the main screen. |
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

### Lens selection on a logical camera

Measured on the Pixel 6, 2026-11-10 (camera `0` = logical {main `2`, ultrawide `3`}):

| Setting | What the video actually was |
|---|---|
| Logical, zoom 1.0 | main lens, native field of view |
| Physical ID 3, zoom 1.0 | **ultrawide sensor, but cropped to the main lens's field of view.** The driver matches fields of view at zoom 1.0; the previous fork's author measured the same on an S24 Ultra. |
| Physical ID 3, size not supported by the ultrawide (4080×3072) | ultrawide delivered as a 1920×1920 square and **stretched** into the video. Fixed in `fix/video-size-setting`: only sizes the lens supports are offered now. |
| Logical, zoom 0.67 (widest), with still/stereo streams in the session | **main lens**, native field of view: the logical camera did not switch |

So to get the full ultrawide field of view, use **zoom at the widest value** (slider fully left)
and check the result. The next thing to test is Logical + widest zoom with **"Lenses in the
session: Video only"**. Always check:
- `focal_length_mm` and `active_physical_camera_id` in the frames;
- the `camera.metadata_matches_video_lens` flag in `session.json` (see [05](05-output-data.md#lens-provenance)).

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
