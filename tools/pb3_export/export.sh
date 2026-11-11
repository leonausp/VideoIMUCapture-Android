#!/usr/bin/env bash
# Export recordings (video_meta.pb3 + video_recording.mp4) to CSV, camera.json and EuRoC.
#
# Usage: tools/pb3_export/export.sh <session-dir | dir-of-sessions> [pb3_export.py options]
#   e.g. tools/pb3_export/export.sh data/vid_2026_11_11_10_15_00
#        tools/pb3_export/export.sh data --image-time mid-exposure
# Output goes to <session>/export/ unless --out is given (a path inside the mounted dir).
#
# Environment: PROTO  path to recording.proto (default: ../../protobuf/recording.proto,
#                     i.e. this repo's schema; set it when the tool lives elsewhere)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ $# -lt 1 || "$1" == "-h" || "$1" == "--help" ]]; then
  sed -n '2,11p' "$0"; exit 0
fi
TARGET="$(cd "$1" && pwd)"; shift
PROTO="$(cd "$(dirname "${PROTO:-$HERE/../../protobuf/recording.proto}")" && pwd)/$(basename "${PROTO:-recording.proto}")"
[[ -f "$PROTO" ]] || { echo "recording.proto not found at $PROTO (set PROTO=...)" >&2; exit 1; }

IMAGE="pb3-export:$(sha256sum "$HERE/Dockerfile" | cut -c1-12)"
if ! docker image inspect "$IMAGE" > /dev/null 2>&1; then
  echo ">> building image $IMAGE"
  docker build -q -t "$IMAGE" "$HERE" > /dev/null
fi

docker run --rm \
  --user "$(id -u):$(id -g)" -e HOME=/tmp \
  -v "$TARGET:/data" \
  -v "$HERE:/tool:ro" \
  -v "$PROTO:/proto/recording.proto:ro" \
  "$IMAGE" python -I /tool/pb3_export.py /data --proto /proto/recording.proto "$@"
