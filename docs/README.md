# Zero-Friction VIO SLAM: documentation

**Zero-Friction VIO SLAM** is an Android app that records **video, IMU and other phone sensors on
a single clock**. That makes the recordings usable for visual odometry (VO) and visual-inertial
odometry/SLAM (VIO), for example with ORB-SLAM3, VINS-Mono, OpenVINS or Basalt, after calibrating
with Kalibr.

These docs are written for people who **can program, but have never written Java or built an
Android app**. Android-specific ideas are explained where they first come up, and collected in the
[Android & Java primer](02-android-primer.md).

## Where to start

| If you want to… | Read |
|---|---|
| understand what is where in this repository | [01 – Repository overview](01-repository-overview.md) |
| understand the Android and Java words used everywhere else | [02 – Android & Java primer](02-android-primer.md) |
| understand how the app works inside (classes, threads, data flow) | [03 – App architecture](03-app-architecture.md) |
| know what the app records and which settings to use for VIO | [04 – Features & settings](04-features-and-settings.md) |
| read the recorded data (folders, files, protobuf, timestamps) | [05 – Output data](05-output-data.md) |
| build the APK, install it on a phone, run the tests | [06 – Building & installing](06-building-and-installing.md) |
| calibrate the camera and IMU and convert recordings for SLAM | [07 – Calibration & analysis tools](07-calibration-and-tools.md) |

## Quick start

```bash
docker/build.sh                                   # build the debug APK in Docker (no SDK on the host)
adb install -r android_app/app/build/outputs/apk/debug/app-debug.apk
# record on the phone, then copy the recordings to the computer:
adb pull /sdcard/Android/data/io.github.leonausp.zerofriction_vio_slam/files/ ./sessions/
```

## Where the code comes from

This app is a fork of a fork. Credit goes to each project in the chain:

1. **MARS logger** (OSUPCVLab, Jianzhu Huai et al., 2019). The original Camera2 and IMU logger.
   Paper: *"The Mobile AR Sensor Logger for Android and iOS Devices"*, arXiv:2001.00470.
2. **VideoIMUCapture-Android** (David Gillsjö, Lund University, 2020–2023). Copied from MARS in
   2020. It added OIS/DVS control, the protobuf output format and the Kalibr tooling.
3. **SometimesYouNeverKnow/VideoIMUCapture-Android** (2026). Modernized the build and fixed a
   stale-IMU bug. It also added high-rate IMU, per-frame stabilization state, extra sensors and
   still-capture modes.
4. **This repository.** Our own app ID and name, plus a Docker build. Based on tag
   `base/fork-6618b88`.

Licence: **GPL-3.0**, inherited from the projects above. The video-encoding code (`gles/`,
`TextureMovieEncoder`) comes from Google's Apache-2.0 *grafika* samples.

> **Scope of these docs.** They describe the code at the time of writing. Behaviour measured on a
> real phone (rates, OIS results, encoder bitrates) comes from the previous fork's author, who
> tested on **one device: a Galaxy S24 Ultra on Android 16**. Treat those numbers as examples and
> check them on your own phone.
