# 05 – Output data

## Where recordings are stored

On the phone, every session is a folder in the app's private external storage:

```
/sdcard/Android/data/io.github.leonausp.zerofriction_vio_slam/files/
├── camera_census.json                      ← all camera characteristics (rewritten at each launch)
├── walk_vid_2026_11_10_18_05_12/           ← one recording session: <mode>_vid_<yyyy_MM_dd_HH_mm_ss>
│   ├── video_recording.mp4                 ← the video
│   ├── video_meta.pb3                      ← ALL metadata + sensor data (protobuf)
│   ├── session.json                        ← what the session contains, frame accounting, warnings
│   └── *.jpg / *.dng                       ← stills, if any were taken
└── …
```

A session started with the stills button instead is named `<mode>_<date>`. Test-matrix sessions
start with `test<cell>_`.

Copy recordings to your computer:

```bash
adb pull /sdcard/Android/data/io.github.leonausp.zerofriction_vio_slam/files/ ./sessions/
```

> ⚠️ Android **deletes this folder when the app is uninstalled.** Always pull your data first.

## `video_meta.pb3`: the protobuf file

The schema is `protobuf/recording.proto`. The top-level message is `VideoCaptureData`:

```proto
message VideoCaptureData {
  google.protobuf.Timestamp time = 1;   // wall-clock start
  CameraInfo  camera_meta = 2;          // once: resolution, intrinsics, distortion, lens pose, timestamp source…
  IMUInfo     imu_meta = 3;             // once: sensor names, resolution, requested/measured rate
  repeated IMUData             imu = 4;         // ← the IMU stream
  repeated VideoFrameMetaData  video_meta = 5;  // ← one per video frame
  repeated EnvironmentData     environment = 6; // barometer
  repeated StepData            steps = 7;
  repeated OrientationData     orientation = 8; // OS-fused rotation vectors
  repeated GnssData            gnss = 9;
  repeated StillMetaData       stills = 10;
  repeated ThermalData         thermal = 11;
  repeated LightData           light = 12;
  repeated GnssMeasurementData gnss_measurement = 13;
  … gnss_status, gnss_navigation, gnss_antenna …
  FrameAccounting frame_accounting = 17;        // how many frame records were written / lost
}
```

**How the file is written.** `RecordingWriter` appends many small serialized `VideoCaptureData`
messages one after another. Protobuf has a useful property: **concatenated messages of the same
type parse as one merged message**, with the `repeated` fields appended. So you can read the
whole file in one call:

```bash
pip install protobuf
protoc --python_out=. protobuf/recording.proto     # generates recording_pb2.py
```

```python
import recording_pb2

data = recording_pb2.VideoCaptureData()
with open("walk_vid_2026_11_10_18_05_12/video_meta.pb3", "rb") as f:
    data.ParseFromString(f.read())

print(data.camera_meta)                 # intrinsics, timestamp source, …
print(len(data.imu), "IMU rows,", len(data.video_meta), "frames")
first = data.imu[0]
print(first.time_ns, list(first.gyro), list(first.accel))
```

The schema has grown only by **adding** new field numbers, so files from older builds still parse,
and the upstream scripts in `calibration/` still read new files. If you change the schema, keep it
that way: add fields, never renumber or reuse old ones.

**Focal length and intrinsics fields** (`focal_length_mm`, `est_focal_length_pix`,
`intrinsic_params`, …): what each one means, which change with resolution, and a known issue with
the scaled principal point for cropped (16:9) sizes are covered in
[04 – Focal length, resolution and lens selection](04-features-and-settings.md#focal-length-resolution-and-lens-selection).

## Timestamps and clocks

| Data | Timestamp field | Clock |
|---|---|---|
| Video frame metadata | `VideoFrameMetaData.time_ns` = Camera2 `SENSOR_TIMESTAMP` (**start of exposure** of the first row) | camera clock |
| MP4 presentation time | `SurfaceTexture.getTimestamp()` of the GPU frame, set by `TextureMovieEncoder`; on most phones this equals `SENSOR_TIMESTAMP`. The app pairs the two explicitly (`VideoFrameToTimestamp`, `FramePairing`) | camera clock |
| IMU | `IMUData.time_ns` = `SensorEvent.timestamp` (of the gyro sample) | `elapsedRealtimeNanos` (boot time) |
| GNSS, thermal, environment… | each message's time field | joined to the sensor clock |

- **If the camera's timestamp source is REALTIME,** camera and IMU timestamps are on the same clock
  and directly comparable. There is still a small, roughly constant offset from the hardware
  pipeline, which Kalibr or online estimation in VINS/OpenVINS should estimate.
- **If it is UNKNOWN,** the camera clock may differ from the IMU clock. This build records the
  flag but doesn't correct for it.
- **Rolling shutter:** rows are read out over `frame_readout_ns` ns (Camera2 `SENSOR_ROLLING_SHUTTER_SKEW`) after `time_ns`. For
  rolling-shutter-aware estimators (VINS-Mono's `rolling_shutter_tr`, OpenVINS), use this value.
- **Matching MP4 frames to metadata rows:** match by timestamp, not by index. `FrameAccounting`
  and `session.json` tell you whether any rows were lost; if they were, matching by position
  would shift every later frame.

## `session.json`

This is a human-readable summary written at stop. It contains:
- the build that recorded the session (`git_sha`, app version);
- the mode;
- what was requested ("expected") versus what the folder actually holds (counted on disk);
- frame accounting;
- warnings.

If the two halves disagree, something was requested and did not land. Check this file before
using a session.

## `camera_census.json`

This file holds every `CameraCharacteristics` key for every camera ID, including physical cameras
behind a logical one. It is written locally at each launch. Use it to answer "what can this phone
do?" (see [04](04-features-and-settings.md#things-to-check-once-per-phone)).

## Sanity checks

These are worth running on every new phone and before every recording campaign:

```python
import numpy as np
t_imu = np.array([m.time_ns for m in data.imu]) * 1e-9
t_cam = np.array([m.time_ns for m in data.video_meta]) * 1e-9

print("IMU rate  %.1f Hz" % (1 / np.median(np.diff(t_imu))))
print("max IMU gap %.1f ms" % (np.max(np.diff(t_imu)) * 1e3))
print("video %.1f fps, max gap %.1f ms" % (1 / np.median(np.diff(t_cam)), np.max(np.diff(t_cam)) * 1e3))
print("IMU covers video: start %.3f s before, end %.3f s after" % (t_cam[0] - t_imu[0], t_imu[-1] - t_cam[-1]))
print("gravity |a| ≈ %.2f m/s²" % np.median(np.linalg.norm([list(m.accel) for m in data.imu[:200]], axis=1)))
```

Expected results:
- the IMU rate is high and steady;
- there are no large gaps;
- the IMU starts before and ends after the video, both by a small amount (not by seconds);
- |a| ≈ 9.81 while the phone is still.

`calibration/data2statistics.py` prints a similar summary.
