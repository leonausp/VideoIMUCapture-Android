# 03 – App architecture

All source files are in `android_app/app/src/main/java/se/lth/math/videoimucapture/`.
This page explains how they fit together, focusing on the **video + IMU path** that matters for
VIO.

## The big picture

```
                         ┌──────────────────────────── phone ───────────────────────────────┐
                         │                                                                   │
  Camera hardware ──► Camera2Proxy ──frames (GPU texture)──► GL renderer ──► TextureMovieEncoder
                         │  │                                  │ (preview)      │ (MediaCodec)
                         │  │ CaptureResult per frame          ▼                ▼
                         │  │ (timestamp, exposure, OIS…)    screen      video_recording.mp4
                         │  ▼
                         │ FrameRecords ──┐
                         │                │      ┌──────────────────┐
  IMU (SensorManager) ──► IMUManager ─────┼────► │ RecordingWriter  │──► video_meta.pb3
  GNSS ────────────────► GnssLogger ──────┤      │ (queue + thread) │    (protobuf)
  baro/light/steps… ───► IMUManager ──────┤      └──────────────────┘
  battery/thermal ─────► ThermalLogger ───┘
                         │
                         │ at stop: SessionManifest ──► session.json
                         └───────────────────────────────────────────────────────────────────┘
```

**Two output paths:**
1. **Pixels:** camera, then the GPU, then the hardware encoder, then `video_recording.mp4`.
2. **Metadata:** every per-frame camera result and every sensor sample becomes a protobuf message.
   Each message is queued to `RecordingWriter`, which appends it to `video_meta.pb3` on its own
   thread.

They are joined later by **timestamp**. Each frame's `SENSOR_TIMESTAMP` is in the metadata. The
video frame's presentation time is the GPU frame's `SurfaceTexture` timestamp, which normally equals
`SENSOR_TIMESTAMP`; `FramePairing` matches the two and counts any frame it cannot match.

## Main classes, grouped by job

Line counts show where the weight is. ★ marks the classes to read first for VIO.

### Screens and user interface

| Class | Lines | Role |
|---|---:|---|
| `CameraCaptureActivity` | 842 | The main screen (app entry point). Owns the camera, sensor managers and the session directory. Based on Google's *grafika* sample. |
| ★ `CameraCaptureFragment` | 1140 | Preview + Record button. `startRecording()` / `stopRecording()` start and stop everything; contains the GL renderer that feeds the encoder. |
| `CameraSettingsManager` | 958 | Reads the camera's capabilities and turns user settings into `CaptureRequest` keys (OIS, DVS, focus, exposure, ISO, distortion correction…). |
| `CaptureModeManager` | 635 | The WALK / OBJECT / PANO modes; names the session folder; locks auto-exposure/white balance for a recording. |
| `RollActivity`, `SessionActivity`, `Thumbs`, `SessionSummary` | | **Recordings** (gallery button): list of sessions, playback, recorded streams and gaps, delete. The class names still say "Roll", the previous fork's name for it. |
| `PermissionHelper`, `InfoDialogFragment`, `AspectFrameLayout`, `SampleGLView`, `FloatSeekBarPreference` | | UI helpers |

### Camera

| Class | Lines | Role |
|---|---:|---|
| ★ `Camera2Proxy` | 1211 | Opens the camera, builds the capture session and repeating request, and receives each frame's `CaptureResult`. While recording, it hands results to `FrameRecords` and on to the writer. |
| ★ `FrameRecords` | 311 | Converts camera metadata into protobuf: `CameraInfo` (once per clip: intrinsics, distortion, lens pose, timestamp source…) and `VideoFrameMetaData` (per frame: timestamp, exposure, ISO, focus, rolling-shutter skew, OIS samples, applied OIS/EIS mode…). |
| `FramePairing` | 186 | Merges the two halves of a frame record (camera result + encoder confirmation) and counts any that are lost. |
| `FocalLengthHelper`, `CameraUtils`, `CaptureSettings`, `LensRoles` | | Focal length in pixels, size selection, which physical lens is which |
| `CameraCensus` | 400 | Writes `camera_census.json` with **all** characteristics of every camera, on every launch. |
| `StillCaptureManager`, `StereoCapture`, `StereoRequests`, `FocusStackSequencer`, `ObjectComposite`, `StillnessTrigger`, `YuvJpeg`, `StillRows` | | Still photos, RAW, brackets, focus stacks, stereo pairs from two lenses (not needed for VIO) |
| `probe/*` | | Diagnostics that test what the cameras can stream at once |

### Video encoding (from Google's grafika)

| Class | Role |
|---|---|
| `TextureMovieEncoder` | Encoder thread. Takes each camera frame as an OpenGL texture and draws it into the encoder's input surface. **Sets the frame's presentation time from the `SurfaceTexture` timestamp** (normally = the camera's `SENSOR_TIMESTAMP`). |
| `VideoEncoderCore` | Wraps Android's `MediaCodec` (hardware H.264/HEVC encoder) and `MediaMuxer` (writes the MP4) |
| `gles/*` | Small OpenGL ES helpers (EGL context, surfaces, shader programs) |

### Sensors

| Class | Lines | Role |
|---|---:|---|
| ★ `IMUManager` | 760 | Registers accelerometer, gyro and magnetometer (uncalibrated) at the sensor's fastest rate, plus barometer, steps, rotation vectors and light. **Synchronises the streams.** See below. |
| `GnssLogger` | 404 | GNSS fixes (1 Hz) plus raw measurements, stamped with `elapsedRealtimeNanos` so they share the sensor clock |
| `ThermalLogger`, `BatteryGuard`, `StorageGuard` | | Temperature and thermal status on the sensor clock; stop safely when the battery or disk runs low |
| `BlurBudgetController` | 415 | Experimental, off by default: limits exposure time based on how fast the gyro says you're rotating |

### Writing files

| Class | Lines | Role |
|---|---:|---|
| ★ `RecordingWriter` | 391 | Bounded queue (8192 messages) plus a writer thread. Appends each message to `video_meta.pb3`. Also counts lost frame records (`FrameAccounting`). |
| `SessionManifest`, `SessionReceipts` | 598 | At stop, writes `session.json`: what the folder **actually** contains versus what was requested |
| `RollProvider` | 289 | Lets a companion app signed with the same key list and delete sessions. Unused by us; protected by signature-level permissions. |
| `TestPlan`, `TestPlanRunner`, `RunState` | | The previous author's on-device test matrix |

## Recording lifecycle

What happens when you press **Record** (`CameraCaptureFragment.startRecording()`):

1. **`CaptureModeManager.beginVideoSession()`** creates the session folder, for example
   `vid_2026_11_11_10_15_00/` (`<mode>_vid_<date>` when the stills features are enabled), and locks auto-exposure/white balance if
   "Freeze exposure while recording" is on.
2. **`RecordingWriter.startRecording("…/video_meta.pb3")`** opens the metadata file and starts the
   writer thread.
3. **`startSensorStreams(writer)`** points the IMU, GNSS and thermal loggers at the writer.
   `IMUManager.startRecording()` **clears its sample queues first**; this is the stale-IMU fix.
   Then it writes the `IMUInfo` header: sensor names, resolution, requested and measured rate.
4. **`Camera2Proxy.startRecordingCaptureResult(writer)`** writes the `CameraInfo` header, then
   from now on every frame's result becomes a `VideoFrameMetaData` message.
5. **The renderer switches to recording.** `TextureMovieEncoder` starts `MediaCodec`, and every
   frame drawn from now on goes into the MP4, timestamped with its `SurfaceTexture` (camera) time.

**Stop** reverses this. The encoder drains and closes the MP4, the camera stops emitting rows,
sensors stop writing, and `RecordingWriter` flushes and closes. Finally `SessionManifest` writes
`session.json`, which records frame accounting, stream counts and any warnings.

## How IMU samples are synchronised

Android delivers accelerometer, gyroscope and magnetometer events **independently**, at slightly
different times. VIO wants one row per time instant with all axes. `IMUManager` does this
(inherited from MARS):

- **The gyroscope is the reference clock.** For every gyro sample, the accelerometer and
  magnetometer are **linearly interpolated** to the gyro's timestamp. If an accelerometer sample
  is within 500 ns, it is used directly.
- Each output `IMUData` row has: timestamp (ns), gyro xyz (rad/s) + gyro drift (bias), accel xyz
  (m/s²) + accel bias, mag xyz (µT) + mag bias, and accuracy flags.
- **Rate:** each sensor is asked for its own `minDelay`, its fastest rate. The previous author
  measured about 472 Hz on the S24 Ultra; yours will differ. The requested and measured rates
  are stored in `IMUInfo`.
- **Known limitation:** a row is only written once **two magnetometer samples** exist. A phone
  without an uncalibrated magnetometer would log **no IMU at all**. Making the magnetometer optional
  is on our to-do list.

## Threads

| Thread | Runs | Talks to |
|---|---|---|
| Main / UI | buttons, settings, lifecycle | everything (posts work to the others) |
| Camera handler | Camera2 callbacks (`CaptureResult` per frame) | `RecordingWriter` queue |
| GL renderer (GLSurfaceView) | draws the preview | encoder thread |
| Encoder (`TextureMovieEncoder`) | MediaCodec / MediaMuxer | MP4 file |
| Sensor `HandlerThread` (`IMUManager`) | `onSensorChanged`, IMU sync | `RecordingWriter` queue |
| `RecordingWriter` | serialises messages to disk | `video_meta.pb3` |

`RecordingWriter.queueData()` **blocks** when its queue is full. A very slow storage moment can
therefore stall the sensor thread rather than silently dropping IMU samples. The queue is sized
to make that unlikely.

## Where to look when…

| You want to… | Start in |
|---|---|
| change which IMU sensors or rates are used | `IMUManager.register()`, `setSensorType()` |
| add or remove a per-frame camera field | `FrameRecords` + `protobuf/recording.proto` (add new field numbers, never reuse old ones) |
| change OIS/DVS/focus/exposure behaviour | `CameraSettingsManager`, `res/xml/settings.xml` |
| change the video codec or bitrate logic | `VideoEncoderCore`, `TextureMovieEncoder` |
| change what goes into `session.json` | `SessionManifest` |
| change the folder naming | `CaptureModeManager.beginVideoSession()`, `CameraCaptureActivity.newCaptureDir()` |
