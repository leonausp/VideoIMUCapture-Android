#!/usr/bin/env bash
# Build the Android app inside the pinned Docker environment (docker/Dockerfile).
#
# Usage: docker/build.sh [debug|release|test|clean|shell|-- <gradle args...>]
#   debug    ./gradlew assembleDebug                         (default)
#   release  ./gradlew assembleRelease  (signed if android_app/keystore.properties exists)
#   test     ./gradlew testDebugUnitTest
#   clean    ./gradlew clean
#   shell    interactive shell in the build container
#   -- ...   any other gradle arguments, e.g.  docker/build.sh -- lint
#
# Environment overrides:
#   REPO_DIR       repository to build (default: the repo containing this script)
#   GRADLE_VOLUME  named volume caching Gradle + dependencies (default: zerofriction-vio-gradle)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${REPO_DIR:-$SCRIPT_DIR/..}" && pwd)"
GRADLE_VOLUME="${GRADLE_VOLUME:-zerofriction-vio-gradle}"

# The image tag is derived from the Dockerfile's content, so editing a pinned version
# produces a new image instead of silently reusing a stale one.
IMAGE="zerofriction-vio-build:$(sha256sum "$SCRIPT_DIR/Dockerfile" | cut -c1-12)"

mode="${1:-debug}"
gradle_args=()
case "$mode" in
  debug)   gradle_args=(assembleDebug) ;;
  release) gradle_args=(assembleRelease) ;;
  test)    gradle_args=(testDebugUnitTest) ;;
  clean)   gradle_args=(clean) ;;
  shell)   ;;
  --)      shift; gradle_args=("$@") ;;
  -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
  *) echo "unknown mode '$mode' (try --help)" >&2; exit 2 ;;
esac

if [[ ! -x "$REPO_DIR/android_app/gradlew" ]]; then
  echo "error: $REPO_DIR/android_app/gradlew not found or not executable" >&2
  exit 1
fi

if ! docker image inspect "$IMAGE" > /dev/null 2>&1; then
  echo ">> building image $IMAGE (first time: downloads JDK base image + Android SDK)"
  docker build -t "$IMAGE" "$SCRIPT_DIR"
fi

run_args=(
  --rm
  --user "$(id -u):$(id -g)"
  -e HOME=/tmp
  -v "$GRADLE_VOLUME:/gradle-home"
  -v "$REPO_DIR:/work"
  -w /work/android_app
)
[[ -t 0 && -t 1 ]] && run_args+=(-it)

# Release signing: keystore.properties normally points at a keystore OUTSIDE the repo
# (it must never be committed). Mount that keystore's directory read-only at the same
# absolute path so the path in keystore.properties also works inside the container.
props="$REPO_DIR/android_app/keystore.properties"
if [[ "$mode" == "release" ]]; then
  if [[ -f "$props" ]]; then
    store_file="$(sed -n 's/^[[:space:]]*storeFile[[:space:]]*=[[:space:]]*//p' "$props" | tail -n1 | tr -d '\r')"
    if [[ "$store_file" == /* ]]; then
      if [[ ! -f "$store_file" ]]; then
        echo "error: storeFile '$store_file' from keystore.properties does not exist" >&2
        exit 1
      fi
      key_dir="$(dirname "$store_file")"
      case "$key_dir/" in
        "$REPO_DIR"/*) ;;  # already visible through the repo mount
        *) run_args+=(-v "$key_dir:$key_dir:ro") ;;
      esac
    fi
  else
    echo "warning: no android_app/keystore.properties -> release APK will be UNSIGNED" >&2
  fi
fi

if [[ "$mode" == "shell" ]]; then
  exec docker run "${run_args[@]}" "$IMAGE" bash
fi

echo ">> repo:   $REPO_DIR ($(git -C "$REPO_DIR" describe --always --dirty --tags 2>/dev/null || echo 'no git'))"
echo ">> image:  $IMAGE"
echo ">> gradle: ${gradle_args[*]}"
stamp="$(mktemp)"; trap 'rm -f "$stamp"' EXIT
docker run "${run_args[@]}" "$IMAGE" ./gradlew --no-daemon --console=plain "${gradle_args[@]}"

# List the APKs produced, with checksums (handy for archiving a recording campaign).
apk_dir="$REPO_DIR/android_app/app/build/outputs/apk"
if [[ "$mode" == "debug" || "$mode" == "release" ]] && [[ -d "$apk_dir" ]]; then
  echo ">> APKs:"
  find "$apk_dir" -name '*.apk' -newer "$stamp" -print0 2>/dev/null \
    | xargs -0 -r sha256sum | sed 's/^/   /'
fi
