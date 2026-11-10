# 01 – Repository overview

## Top level

```
VideoIMUCapture-Android/
├── android_app/          ← THE ANDROID APP (the only part that runs on the phone)
├── docker/               ← Docker environment for BUILDING the app (JDK + Android SDK)
├── protobuf/             ← recording.proto: the schema of the recorded metadata file
├── calibration/          ← Python scripts + a SECOND Docker image (Kalibr) for calibration/conversion
├── analysis/             ← Python scripts from the previous fork for checking recordings
├── libs/dockers/         ← git submodule with generic Docker helper scripts (used by calibration/)
├── docs/                 ← this documentation
├── images/, graphics/    ← screenshots and icons used in the README
├── .github/              ← GitHub issue templates
├── README.md             ← the previous fork's README (feature list, lineage)
└── LICENSE               ← GPL-3.0
```

Only `android_app/` becomes the app. Everything else is tooling that runs on your computer:
building, calibrating, converting and checking recordings.

## `android_app/`: the Android project

An Android project is built with **Gradle**, a build tool similar to CMake or Maven. Gradle files
are scripts, not plain config. The layout below follows the standard Android convention, which
is why the actual code sits so deep:

```
android_app/
├── build.gradle              ← project-wide build settings (which plugins and versions)
├── settings.gradle           ← which modules exist (just one: "app")
├── gradle.properties         ← build flags
├── gradlew, gradle/wrapper/  ← "Gradle wrapper": downloads the pinned Gradle version (8.7) and runs it
├── tools/                    ← a PowerShell helper from the previous fork for driving test cells over adb
└── app/                      ← the one module = the app
    ├── build.gradle          ← app ID, version, SDK levels, dependencies, protobuf code generation
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml   ← declares the app: permissions, screens, exported components
        │   ├── java/se/lth/math/videoimucapture/   ← ALL THE JAVA SOURCE CODE (≈ 17,600 lines)
        │   │   ├── *.java            ← ~50 classes (see 03-app-architecture.md)
        │   │   ├── gles/             ← OpenGL helpers for drawing camera frames into the video encoder
        │   │   └── probe/            ← diagnostic "probes" that test what the phone's cameras can do
        │   └── res/                  ← "resources": everything that is not code
        │       ├── layout/           ← XML screen layouts (where the buttons and views go)
        │       ├── xml/settings.xml  ← definition of the Settings screen (every user setting)
        │       ├── values/           ← strings (incl. app name), colours, styles, option lists
        │       ├── menu/             ← toolbar menu
        │       └── mipmap-*/, drawable*/  ← icons at different screen densities
        ├── test/             ← unit tests that run on your computer (JUnit), ~110 tests
        └── androidTest/      ← tests that would run on a phone (only a template example here)
```

**Why is the code under `se/lth/math/videoimucapture/`?** In Java, the folder path must match
the **package name** declared at the top of each file (`package se.lth.math.videoimucapture;`).
By convention a package name is a reversed domain, here Lund University's `math.lth.se`.

We deliberately **kept this package name** even though our app has its own app ID
(`io.github.leonausp.zerofriction_vio_slam`). Renaming the package would touch every file and make
it hard to pull fixes from the upstream fork. The package name and the app ID are independent; see
the [primer](02-android-primer.md#application-id-vs-package-name).

Generated code doesn't live in `src/`. The Java classes for the protobuf messages (for example
`RecordingProtos`) and `BuildConfig` are generated during the build into `app/build/`, which
git ignores.

## `docker/`: the build environment

Building an Android app needs a JDK (Java Development Kit) and the Android SDK, which together
are a few GB. Instead of installing them on your machine, `docker/Dockerfile` defines an image with
**pinned versions** (JDK 17, SDK platform 35, build-tools 35.0.1), and `docker/build.sh` runs
Gradle inside it. This keeps your machine clean, and it means the same git tag rebuilds the same
APK later, which matters for publishing datasets. Details: [06 – Building & installing](06-building-and-installing.md).

## `protobuf/`: the data format

`recording.proto` is a **Protocol Buffers** schema. Protobuf is Google's binary serialization
format: you describe messages in a `.proto` file, and a compiler (`protoc`) generates reader and
writer code for Java, Python, C++ and so on. During the build, the app generates its Java classes
from this file. To read recordings, you generate Python classes from the same file. See
[05 – Output data](05-output-data.md).

- `compile.sh`: one-line `protoc` call that generates Python code.
- `test_proto/`: tiny examples (`proto2yaml.py` dumps a recording as YAML).

## `calibration/`: Kalibr and conversion (a second Docker image)

These scripts come from the original VideoIMUCapture project. They turn a recording into what
calibration and SLAM tools expect:

| Script | Does |
|---|---|
| `data2statistics.py` | Summary of a recording (rates, factory intrinsics if the phone provides them) |
| `data2kalibr.py` | Builds a Kalibr bag plus `target.yaml`/`imu.yaml`/`camchain.yaml` for camera or camera–IMU calibration |
| `data2rosbag.py` | Converts recordings to ROS bags, rescaling a calibration to the video resolution |
| `data2images.py` | Extracts frames as images |
| `data2lth_vision.py` | Converter for the Lund (LTH) vision system |
| `find_T.py`, `utils.py`, `intrinsic2txt.m` | Helpers (transforms; MATLAB calibration export) |

They run inside a **separate Docker image built on Kalibr** (`calibration/Dockerfile`). The
original author also published it on Docker Hub as `davidgillsjo/videoimucapture-calibration`.
**There are two unrelated Docker setups:**

| | `docker/` | `calibration/` |
|---|---|---|
| Purpose | build the Android app | calibrate and convert recordings |
| Base image | eclipse-temurin JDK 17 + Android SDK | stereolabs/kalibr (ROS + Kalibr) |
| Written by | us | the original VideoIMUCapture author |
| Entry point | `docker/build.sh` | `calibration/run_dockerhub.sh` / `run_docker.sh` |

The calibration scripts use the `libs/dockers` submodule, which you fetch with
`git submodule update --init`. See [07 – Calibration & analysis tools](07-calibration-and-tools.md).

## `analysis/`: checks from the previous fork

Python scripts and a `FINDINGS.md` lab notebook, written by the previous fork's author while
validating their phone. Examples: do the per-frame rows match the video frames, what lens does each
stream really use, frame-hole detection (`frame_holes.py`). They are useful templates for your own
checks, but many target the stereo and stills features, not VIO.

## Git branches and tags in this repository

| Name | Meaning |
|---|---|
| tag `base/fork-6618b88` | The exact upstream-fork commit we audited and started from. Never moved. |
| `main` | Our stable code. Changes arrive via `--no-ff` merges of `setup/*`, `fix/*`, `docs/*` branches. |
| `campaign/<date>-vN` tags (planned) | Frozen build used for a recording campaign. |

The audit, the decision behind this fork and the workflow are documented outside this repo, in the
project's `logger-selection/` notes (`LOGGER_CODE_AUDIT.md`, `FORK_SETUP.md`).
