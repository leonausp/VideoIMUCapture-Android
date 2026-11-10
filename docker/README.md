# Building in Docker

This builds the app in a pinned, throw-away environment. Nothing besides Docker is installed on
your machine. The image holds JDK 17, Android SDK platform 35 and build-tools 35.0.1; see
`Dockerfile` for the exact versions.

## Usage

```bash
docker/build.sh            # debug APK   -> android_app/app/build/outputs/apk/debug/
docker/build.sh release    # release APK -> android_app/app/build/outputs/apk/release/
docker/build.sh test       # host-side unit tests
docker/build.sh clean
docker/build.sh shell      # shell inside the build container
docker/build.sh -- lint    # any other Gradle task
```

The first run builds the image and downloads Gradle and its dependencies, roughly 2 GB. Gradle's
cache is kept in the Docker volume `zerofriction-vio-gradle`, so later builds are fast. To reset
it: `docker volume rm zerofriction-vio-gradle`.

The image tag is derived from the `Dockerfile`'s contents. Changing a pinned version therefore
builds a new image automatically. To remove old images:
`docker image ls zerofriction-vio-build`, then `docker image rm …`.

## Release signing

Put `android_app/keystore.properties` in place; git ignores it, so it never gets committed:

```properties
storeFile=/home/<user>/keys/<app.id>.jks
storePassword=…
keyAlias=…
keyPassword=…
```

`build.sh release` mounts the keystore's folder **read-only** into the container at the same path.
Without `keystore.properties`, the release APK is built unsigned and the script warns you.

## Installing on the phone (on the host, not in Docker)

```bash
sudo apt install adb          # once
adb devices                   # phone needs USB debugging enabled
adb install -r android_app/app/build/outputs/apk/debug/app-debug.apk
adb pull /sdcard/Android/data/io.github.leonausp.zerofriction_vio_slam/files/ ./data/
```

Android deletes the app's `files/` folder when the app is uninstalled. Pull your recordings
before uninstalling.

## Reproducibility

The build runs as your own user ID, so output files aren't owned by root. `git` inside the
container is allowed to read the mounted repo, so each APK is stamped with the commit it was built
from.

For a recording campaign, build `release` from a clean, tagged tree. The script prints each APK's
SHA-256 so you can archive it alongside the data.
