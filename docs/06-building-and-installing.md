# 06 – Building & installing

## Build with Docker (recommended)

You only need Docker, and your user must be in the `docker` group.

```bash
docker/build.sh            # debug APK  → android_app/app/build/outputs/apk/debug/app-debug.apk
docker/build.sh release    # release APK (signed if android_app/keystore.properties exists)
docker/build.sh test       # run the unit tests on your computer
docker/build.sh clean
docker/build.sh shell      # a shell inside the build container, for debugging
docker/build.sh -- lint    # any other Gradle task
```

What happens:

1. `build.sh` builds the image from `docker/Dockerfile` the first time. The image tag is a hash of
   the Dockerfile's contents, so changing a pinned version builds a new image automatically.
2. It runs `./gradlew <task>` in the container:
   - as **your** user ID, so output files belong to you;
   - with the repo mounted at `/work`;
   - with Gradle's cache, the downloaded Gradle and the **debug signing key** stored in the Docker
     volume `zerofriction-vio-gradle`, so repeat builds are fast and keep the same debug key.
3. It prints the SHA-256 of each APK it produced.

The first build downloads roughly 2 GB and takes a few minutes. Later builds take seconds to a
minute. See [`docker/README.md`](../docker/README.md) for details, including resetting the cache.

**Pinned versions** (in `docker/Dockerfile` and `android_app/`):

| | Version | Defined in |
|---|---|---|
| JDK | 17 (eclipse-temurin 17.0.13) | `docker/Dockerfile` |
| Android SDK platform / build-tools | 35 / 35.0.1 | `docker/Dockerfile`, `app/build.gradle` |
| Gradle | 8.7 | `android_app/gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.5.2 | `android_app/build.gradle` |
| protobuf (javalite + protoc) | 3.25.5 | `app/build.gradle` |

Expected, harmless build messages:
- "*We recommend using a newer Android Gradle plugin to use compileSdk = 35*": AGP 8.5.2 was
  tested up to SDK 34. The build works.
- "*Picked up JAVA_TOOL_OPTIONS*": printed because `build.sh` sets the Java home directory.
- "*deprecated API* / *unchecked operations*": Java compiler notes from the inherited code.

## Build without Docker (alternative)

Install Android Studio, or JDK 17 plus the Android command-line tools. Open `android_app/` in
Android Studio, or run `cd android_app && ./gradlew assembleDebug`.

## Install on a phone

Use `adb` on the **host**. USB access from inside Docker is possible but not worth the effort.

```bash
sudo apt install adb                 # once
# on the phone: Settings → About → tap "Build number" 7× → Developer options → enable USB debugging
adb devices                          # confirm the phone on the phone's screen
adb install -r android_app/app/build/outputs/apk/debug/app-debug.apk
```

- `-r` reinstalls and keeps the app's data. This only works if the new APK is signed with the
  **same key** as the installed one.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a different key: a debug build from another machine,
  or a release build replacing a debug one. Uninstall first, **after pulling your recordings**,
  because uninstalling deletes them.
- The app installs alongside the original VideoIMUCapture apps because it has its own app ID.

Useful commands:

```bash
adb logcat -s IMUManager Camera2Proxy RecordingWriter     # live logs from those classes (TAGs)
adb shell getprop ro.product.model                        # phone model
adb shell getprop ro.build.fingerprint                    # exact OS build (record it per campaign)
adb pull /sdcard/Android/data/io.github.leonausp.zerofriction_vio_slam/files/ ./data/
```

## Release builds and signing

Debug builds are fine for development. For recordings you will publish, use a **release** build
signed with your own key, and keep that key safe: you can't update installed apps without it.

```bash
keytool -genkeypair -v -keystore ~/keys/zerofriction.jks -alias vio -keyalg RSA -keysize 4096 -validity 10000
cat > android_app/keystore.properties <<'EOF'
storeFile=/home/<user>/keys/zerofriction.jks
storePassword=…
keyAlias=vio
keyPassword=…
EOF
docker/build.sh release
```

`keystore.properties` and `*.jks` are git-ignored. `build.sh` mounts the keystore's folder
**read-only** into the container.

## Which build recorded my data?

At build time, the app embeds the git commit (`git_sha`), and every session's `session.json`
records it. For a recording campaign:

1. Build `release` from a **clean, tagged** commit (`campaign/<date>-vN`).
2. Archive the APK with its SHA-256 (printed by `build.sh`).
3. Write down the phone model and build fingerprint.

## Tests

```bash
docker/build.sh test
```

This runs about 110 JUnit tests from `app/src/test/` on your computer: session manifest, frame
pairing, storage and battery guards, and so on. They test logic only. Anything involving the real
camera or sensors needs a phone, and there are no automated on-device tests yet.
