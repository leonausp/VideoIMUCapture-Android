#!/usr/bin/env bash
# Builds the official Kalibr (ethz-asl/kalibr, ROS Noetic / Ubuntu 20.04) as a Docker image,
# pinned to one commit so the calibration is reproducible. Takes ~15-30 min the first time.
#
#   tools/kalibr_calib/build.sh          -> image zf-kalibr:<commit>
set -euo pipefail

KALIBR_REPO=https://github.com/ethz-asl/kalibr.git
KALIBR_COMMIT=1f60227442d25e36365ef5f72cd80b9666d73467   # master, 2024-03-08 (PR #673)
IMAGE=zf-kalibr:${KALIBR_COMMIT:0:7}

CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/zf-kalibr-src
if [ ! -d "$CACHE/.git" ]; then
    git clone -q "$KALIBR_REPO" "$CACHE"
fi
git -C "$CACHE" fetch -q origin
git -C "$CACHE" checkout -q "$KALIBR_COMMIT"

docker build -t "$IMAGE" -f "$CACHE/Dockerfile_ros1_20_04" "$CACHE"
echo "built $IMAGE"
