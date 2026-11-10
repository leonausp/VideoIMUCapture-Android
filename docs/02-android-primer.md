# 02 – Android & Java primer

This is just enough Android and Java to read this code base. It assumes you know some other
language, such as Python or C++.

## Java in five minutes

- **Everything is a class.** One public class per file, and the file name matches the class name:
  `IMUManager.java` contains `public class IMUManager`.
- **Packages** are namespaces. `package se.lth.math.videoimucapture;` at the top of a file puts it
  in that namespace, and the folder path must match. `import android.hardware.Sensor;` is like
  Python's `from android.hardware import Sensor`.
- **Statically typed, garbage-collected.** You declare types (`long timestamp;`), but you never
  free memory yourself.
- **Naming conventions in this code base:** `mSomething` is a member variable (field), `sSomething`
  is a static field, and `ALL_CAPS` is a constant.
- **Interfaces and callbacks.** Much of Android works by *you* implementing a method that the
  system calls later. For example, `IMUManager` implements `onSensorChanged(SensorEvent e)`, and
  Android calls it for every IMU sample. `->` is a lambda: `x -> doSomething(x)`.
- **`@Override`** marks a method that replaces one from a parent class or interface.
- **`null`** is everywhere. Optional camera metadata comes back as `null` when the phone doesn't
  provide it, so you'll see many `if (x != null)` checks.
- **`final`** means "can't be reassigned"; it is roughly `const`.
- **`synchronized` / `volatile`** are thread-safety keywords. You'll see them because camera, IMU,
  GPS, encoding and file writing all run on different threads.

## What an Android app is

An app is an **APK** file: a zip of compiled code (DEX bytecode), resources and a manifest. You
install it from an app store or by **sideloading** it with `adb install`. This app is sideloaded.

### The manifest (`AndroidManifest.xml`)

The manifest declares to the operating system:

- **Permissions** the app needs. Here: `CAMERA`, `ACCESS_FINE_LOCATION` (GNSS), `ACTIVITY_RECOGNITION`
  (step counter) and `HIGH_SAMPLING_RATE_SENSORS`. Since Android 12, IMU rates above 200 Hz need
  that last one.
  "Dangerous" permissions such as camera and location must **also** be granted by the user at
  runtime; that is the pop-up dialog. `PermissionHelper.java` handles it.
- **Components**: the screens (activities) and the `RollProvider` content provider.

### Application ID vs package name

| | Value here | What it's for |
|---|---|---|
| **Application ID** (`applicationId` in `app/build.gradle`) | `io.github.leonausp.zerofriction_vio_slam` | The app's identity on the phone: what's installed, which data folder it gets, update compatibility |
| **Namespace / Java package** (`namespace`) | `se.lth.math.videoimucapture` | Where the Java classes live; where the generated `R` and `BuildConfig` classes go |

The two may differ. We changed only the application ID, so our app installs alongside the original
and its data folder is separate, while the code stays easy to merge with upstream.

### Activities, fragments and the lifecycle

- An **Activity** is one screen. This app has three: `CameraCaptureActivity` (the main camera
  screen, the launcher), `RollActivity` (list of past recordings) and `SessionActivity` (one
  recording's details).
- A **Fragment** is a reusable piece of a screen. `CameraCaptureFragment` is the camera preview
  plus record button; `SettingsFragment`-style preference screens show the settings.
- **The lifecycle.** Android calls `onCreate`, `onResume`, `onPause`, `onDestroy` and so on as the
  user opens the app, switches away or rotates the phone. Cameras and sensors are typically opened
  in `onResume` and released in `onPause`. That is why the IMU listeners run from app resume, not
  from when you press Record, which was the root of the old stale-IMU bug.

### Resources (`res/`)

Anything that isn't code: layouts (XML describing views), strings, icons, and the settings screen
definition. The build generates a class **`R`** with an integer ID for each resource. Code refers
to them as `R.id.toggleRecording_button`, `R.string.app_name` and so on.

**Settings** are defined declaratively in `res/xml/settings.xml`. The `androidx.preference`
library renders that screen and stores each value in **SharedPreferences**, a small key–value
store kept per app. Code reads a value like this:
`PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("ois", false)`.

### Threads, `Handler` and `HandlerThread`

Android's UI runs on one **main thread**. Anything slow or high-rate runs elsewhere:

- `HandlerThread` is a thread with a message loop.
- A `Handler` lets you post work to that thread.

This app uses one thread each for sensors, the camera, the encoder (OpenGL) and the file writer. See
[03 – App architecture](03-app-architecture.md#threads).

## The two APIs that matter for VIO

### Camera2 (`android.hardware.camera2`)

The camera API used here (the older `Camera` API is deprecated):

- **`CameraManager`** lists cameras. On multi-camera phones there's a *logical* camera made of
  several *physical* ones (main, ultrawide, tele).
- **`CameraCharacteristics`**: static facts about a camera, such as sensor size, the
  `LENS_INTRINSIC_CALIBRATION` intrinsics, `LENS_DISTORTION`, the OIS modes available and
  `SENSOR_INFO_TIMESTAMP_SOURCE`. This app dumps them all to `camera_census.json`.
- **`CaptureRequest`**: what you ask for each frame (exposure, ISO, focus, OIS on/off…). A
  *repeating request* is re-sent for every frame of the video.
- **`CaptureResult`**: what actually happened for each frame. It includes `SENSOR_TIMESTAMP`
  (start of exposure, in ns), exposure time, rolling-shutter skew, OIS samples and the applied
  OIS mode. The app writes these per frame.

### Sensors (`SensorManager`)

The IMU arrives as `SensorEvent`s through `onSensorChanged`. Each one carries `event.timestamp`
in nanoseconds on the `elapsedRealtimeNanos` clock (time since boot, including sleep). If the
camera's `SENSOR_INFO_TIMESTAMP_SOURCE` is **REALTIME**, camera frame timestamps use the **same
clock**. That is the property VIO needs. If it is **UNKNOWN**, the clocks may be offset, and this
app currently records the flag but doesn't correct for it.

"Uncalibrated" sensor types (`TYPE_GYROSCOPE_UNCALIBRATED` etc.) give raw values plus the OS's bias
estimate separately. This app records those, because VIO noise models assume raw data.

## Tooling vocabulary

| Term | Meaning |
|---|---|
| **Gradle** / `./gradlew` | Build tool; the wrapper script downloads the pinned Gradle version |
| **AGP** | Android Gradle Plugin (8.5.2 here): the Android-specific part of the build |
| **SDK / compileSdk / targetSdk / minSdk** | Android API levels: compiled against 35, behaves as 34, runs on 24+ (Android 7) |
| **JDK** | Java compiler and runtime used *to build* (17). The phone runs its own runtime (ART). |
| **adb** | Android Debug Bridge: install apps, copy files, read logs (`adb logcat`) over USB |
| **Logcat** | The phone's system log; `Log.d(TAG, "...")` in the code writes to it |
| **Signing** | Every APK is signed. Debug builds use an auto-generated debug key; releases use your key. Android refuses to update an app with an APK signed by a different key. |
| **Debug vs release build** | Debug: debuggable, debug-signed. Release: optimized, signed with your release key. |
