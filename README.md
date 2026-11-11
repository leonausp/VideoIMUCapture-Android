# Zero-Friction VIO SLAM: Android recorder

An Android app that records **video and IMU on one clock** for visual odometry (VO) and
visual-inertial odometry / SLAM (VIO), plus the tools to get from a recording to a calibrated
dataset: export to CSV/EuRoC, and camera + camera–IMU calibration with Kalibr.

The goal is a **fast path from recording to SLAM**: record with the phone, copy the folder to a
computer, convert it, and run ORB-SLAM3, OpenVINS, VINS-Fusion or Basalt on it. It's the recording
side of the Zero-Friction VIO/SLAM project, developed and tested on a **Google Pixel 6**
(Android 17).

## What it records

One folder per recording, `vid_<date>/`:

| File | Contents |
|---|---|
| `video_recording.mp4` | H.264 video, 1280×960 at 30 fps, main lens (6.81 mm) by default |
| `video_meta.pb3` | protobuf: camera info once (factory intrinsics and distortion, lens, timestamp source), then **per frame** timestamp, exposure, ISO, focus, rolling-shutter time and OIS samples, and **every IMU sample** (gyroscope, accelerometer, magnetometer, uncalibrated, ~445 Hz on the Pixel 6) |
| `session.json` | what the recording actually contains: frame and sample counts, lost records, warnings, app version and build |

Camera frames and IMU samples share the phone's boot clock when the camera reports
`SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME`, as the Pixel 6 does. Details:
[docs/05 – Output data](docs/05-output-data.md).

## Set up for VIO

The defaults ("Reset to recommended settings") are chosen for VIO, so everything that would
change the camera's geometry stays fixed:

- **Main lens, 1280×960, no OIS, no EIS, no distortion correction.**
- **Tap to focus, locked at RECORD:** the preview focuses on the image centre at start and wherever
  you tap; when you press RECORD the lens is held there for the whole clip, because the focal
  length changes with focus. The export
  records each clip's own focal length, so clips may use different focus.
- **Shutter priority:** exposure fixed at **5 ms** against motion blur; the ISO is metered for the
  scene in the preview and **locked when you press RECORD**, so every frame of a clip has the same
  exposure and ISO.
- The exposure and ISO buttons sit next to RECORD; tap to adjust, AUTO returns to shutter priority.

Settings explained: [docs/04 – Features & settings](docs/04-features-and-settings.md).

## Install

Download **[apk/ZF-VIO-SLAM.apk](apk/ZF-VIO-SLAM.apk)** and install it (`adb install -r ZF-VIO-SLAM.apk`,
or open it on the phone). Details, version and checksum: [apk/README.md](apk/README.md).

## Quick start

```bash
docker/build.sh                                    # build the APK in Docker (no Android SDK needed)
adb install -r android_app/app/build/outputs/apk/debug/app-debug.apk
# record on the phone, then copy the recordings to the computer:
adb pull /sdcard/Android/data/io.github.leonausp.zerofriction_vio_slam/files/ ./data/
tools/pb3_export/export.sh data/vid_<date> --image-time mid-exposure   # -> CSV, JSON, EuRoC
```

Building, installing and running the tests: [docs/06](docs/06-building-and-installing.md).

## Tools

Both are self-contained folders that run in Docker, so they can be copied into other projects.

- **[`tools/pb3_export/`](tools/pb3_export/README.md)** converts a recording into `frames.csv`,
  `imu.csv`, `camera.json` and a **EuRoC** folder (`cam0/data/*.png`, `imu0/data.csv`) for SLAM.
- **[`tools/kalibr_calib/`](tools/kalibr_calib/README.md)** calibrates the camera–IMU rotation,
  offset and time shift with the official [Kalibr](https://github.com/ethz-asl/kalibr), from
  **one ~1 min recording** of an AprilGrid printed on paper or shown on a screen. The lens values
  (focal length, distortion) come from the phone itself:

  ```bash
  tools/kalibr_calib/build.sh                                  # once: the Kalibr image
  tools/kalibr_calib/calibrate.sh target                       # the target (PDF + screen PNG)
  tools/kalibr_calib/calibrate.sh imucam data/vid_<date> --tag-size 0.03 --camchain factory
  ```

## Privacy and permissions

- **Nothing leaves the phone.** The app has **no internet permission**, so Android doesn't let it
  send anything. There is no analytics or tracking code.
- The only permission it asks for is the **camera**. No location, no activity recognition.
- Recordings stay in the app's own folder on the phone until you copy or delete them
  (the gallery button, bottom left, lists them).

## Documentation

Written for people who can program but haven't built an Android app before:
[docs/](docs/README.md), from a repository overview and an Android primer to the app's
architecture, the output format, calibration and the measured Pixel 6 values.

## Thanks

This app stands on other people's work, and we're grateful to all of them:

- **[SometimesYouNeverKnow/VideoIMUCapture-Android](https://github.com/SometimesYouNeverKnow/VideoIMUCapture-Android)**,
  the fork this one is based on. Its author brought the app up to current Android tooling, found
  and fixed the stale-IMU bug, and added the per-frame metadata, frame accounting, camera census,
  thermal logging and much more that this project relies on, and shared it all openly. Thank you.
- **[DavidGillsjo/VideoIMUCapture-Android](https://github.com/DavidGillsjo/VideoIMUCapture-Android)**
  (David Gillsjö, Lund University), the original app and its Kalibr calibration workflow, and the
  idea of recording video and IMU on one clock for VIO.
- **[mobile-ar-sensor-logger / MARS logger](https://github.com/OSUPCVLab/mobile-ar-sensor-logger)**
  (OSU PCV Lab), where the IMU synchronisation comes from.
- **[grafika](https://github.com/google/grafika)** (Google), the camera-to-encoder video pipeline.
- **[Kalibr](https://github.com/ethz-asl/kalibr)** (ETH Zurich ASL), used for calibration.

## License

[GPL-3.0](LICENSE), as inherited from the original project. The Google-derived files keep their
Apache 2.0 notices.

## Feedback

Bugs and questions: [open an issue on this repository](https://github.com/leonausp/VideoIMUCapture-Android/issues).
Please don't report problems with this version to the upstream projects.
