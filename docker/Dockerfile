# Reproducible Android build environment for this app.
#
# Pinned versions so that a given git tag + this Dockerfile rebuilds the same APK later
# (e.g. for a paper's artifact evaluation). Only build tools live here; the phone is
# driven with adb on the host (see docker/README.md).
#
#   JDK            17   (required by AGP 8.5.2 / Gradle 8.7, see android_app/)
#   cmdline-tools  11076708 (sdkmanager)
#   platform       android-35  (compileSdkVersion 35)
#   build-tools    35.0.1      (buildToolsVersion "35.0.1")
#
# Gradle itself (8.7) is fetched by ./gradlew into the cached /gradle-home volume.

FROM eclipse-temurin:17.0.13_11-jdk-jammy

ARG CMDLINE_TOOLS_VERSION=11076708
# SHA-256 of commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip. The build fails
# if the download does not match. If Google ever republishes the file, verify the new
# checksum from developer.android.com/studio before changing it here.
ARG CMDLINE_TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
ARG ANDROID_PLATFORM=android-35
ARG ANDROID_BUILD_TOOLS=35.0.1

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle-home \
    LANG=C.UTF-8
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${PATH}"

# git: the app's build.gradle runs `git` to stamp each APK with the commit it was built from.
RUN apt-get update \
 && apt-get install -y --no-install-recommends git unzip curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    mkdir -p "${ANDROID_HOME}/cmdline-tools"; \
    curl -fsSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"; \
    echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdline-tools.zip" | sha256sum -c -; \
    unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools"; \
    mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"; \
    rm /tmp/cmdline-tools.zip; \
    yes | sdkmanager --licenses > /dev/null; \
    sdkmanager --install "platforms;${ANDROID_PLATFORM}" "build-tools;${ANDROID_BUILD_TOOLS}"; \
    rm -rf /root/.android

# The container runs as the host user's UID/GID (so build outputs are not root-owned).
#  - SDK writable: AGP may write licence/metadata files next to the SDK.
#  - /gradle-home world-writable: a new named volume mounted here copies these permissions.
#  - safe.directory: the mounted repo is owned by a UID git does not know; without this,
#    `git rev-parse` fails and the APK's build stamp silently becomes "unknown".
RUN chmod -R a+rwX "${ANDROID_HOME}" \
 && mkdir -p /gradle-home && chmod 1777 /gradle-home \
 && git config --system --add safe.directory '*'

WORKDIR /work/android_app
