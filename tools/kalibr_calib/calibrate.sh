#!/usr/bin/env bash
# Kalibr calibration from a EuRoC-style folder: <dir>/cam0/data/<t_ns>.png + <dir>/imu0/data.csv
# (columns: t_ns, gx gy gz [rad/s], ax ay az [m/s^2]; first line is a header).
# Self-contained: needs only Docker and the image from build.sh (same folder).
#
#   calibrate.sh target [--cols 6 --rows 4] [--screen 1920x1080] [--pdf [--tag-size M]] [--out DIR]
#       AprilGrid as target.png, for showing full screen (F11 / slideshow). --pdf also writes
#       target.pdf for PRINTING at actual size (tags --tag-size, default 3 cm). Then MEASURE from outermost black edge to outermost black edge (the small corner
#       squares) and divide as printed (6 columns: width / 8.1): that is --tag-size in metres.
#   calibrate.sh all    <euroc_dir> --tag-size M [--cam-until S]       <- ONE recording, both steps
#       intrinsics, then camera-IMU with them      -> DIR/all-camchain.yaml, all-camchain-imucam.yaml
#       Record: first ~30 s calm (target in every part of the image), then ~40 s brisk motion on
#       all axes; --cam-until 30 makes the intrinsics use only the calm part.
#   calibrate.sh cam    <euroc_dir> --tag-size M [--cols 6 --rows 4] [--cam-until S] [--out DIR]
#       camera intrinsics (pinhole-radtan)          -> DIR/cam-camchain.yaml
#   calibrate.sh imucam <euroc_dir> --tag-size M --camchain FILE [--out DIR]
#       camera-IMU extrinsics + time offset          -> DIR/imucam-camchain-imucam.yaml
#
# <euroc_dir> is the folder holding cam0/ and imu0/ (EuRoC's mav0/). DIR defaults to
# <euroc_dir>/../kalibr_<mode>. --cam-freq (5) / --imu-freq (15): images per second Kalibr uses.
# Target: by default an AprilGrid (--tag-size, --cols, --rows). --target FILE uses any Kalibr
# target yaml instead (e.g. an existing checkerboard); then --tag-size is not needed.
# IMU noise: pass --imu-yaml FILE (Kalibr format, e.g. from an Allan-variance run); without it
# typical, inflated phone values are used and marked as placeholders.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KALIBR_COMMIT=$(sed -n 's/^KALIBR_COMMIT=\([0-9a-f]*\).*/\1/p' "$HERE/build.sh")
IMAGE=${KALIBR_IMAGE:-zf-kalibr:${KALIBR_COMMIT:0:7}}

usage() { sed -n '2,27p' "$0"; exit "${1:-0}"; }
abspath() { echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; }
[[ $# -ge 1 ]] || usage 1
MODE=$1; shift
case $MODE in target|cam|imucam|all) ;; -h|--help) usage 0 ;; *) usage 1 ;; esac
IN=""
if [[ $MODE != target ]]; then
    [[ $# -ge 1 && -d "$1" ]] || { echo "missing <euroc_dir>" >&2; usage 1; }
    IN="$(cd "$1" && pwd)"; shift
    [[ -d "$IN/cam0" ]] || IN="$IN/mav0"
    [[ -d "$IN/cam0/data" && -f "$IN/imu0/data.csv" ]] \
        || { echo "$IN: expected cam0/data/*.png and imu0/data.csv" >&2; exit 1; }
fi

PDF=0 SCREEN=1920x1080 TAG_SIZE="" COLS=6 ROWS=4 CAM_FREQ="" IMU_FREQ="" CAM_UNTIL="" CAMCHAIN="" IMU_YAML="" OUT="" TARGET=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --tag-size) TAG_SIZE=$2; shift 2 ;;
        --cols)     COLS=$2; shift 2 ;;
        --rows)     ROWS=$2; shift 2 ;;
        --screen)   SCREEN=$2; shift 2 ;;
        --pdf)      PDF=1; shift ;;
        --cam-freq) CAM_FREQ=$2; shift 2 ;;
        --imu-freq) IMU_FREQ=$2; shift 2 ;;
        --cam-until) CAM_UNTIL=$2; shift 2 ;;
        --camchain) CAMCHAIN=$(abspath "$2"); shift 2 ;;
        --imu-yaml) IMU_YAML=$(abspath "$2"); shift 2 ;;
        --target)   TARGET=$(abspath "$2"); shift 2 ;;
        --out)      mkdir -p "$2"; OUT="$(cd "$2" && pwd)"; shift 2 ;;
        -h|--help)  usage 0 ;;
        *) echo "unknown option $1" >&2; usage 1 ;;
    esac
done
if [[ -n "$TARGET" ]]; then
    [[ $MODE != target && -f "$TARGET" ]] || { echo "--target needs an existing yaml (not with 'target')" >&2; exit 1; }
elif [[ $MODE == target ]]; then
    TAG_SIZE=${TAG_SIZE:-0.03}   # only the PDF's nominal size; the measured value is what counts
else
    [[ -n "$TAG_SIZE" ]] || { echo "--tag-size is required (metres, black edge of one tag), or --target FILE" >&2; exit 1; }
fi
docker image inspect "$IMAGE" > /dev/null 2>&1 \
    || { echo "image $IMAGE missing: run $HERE/build.sh" >&2; exit 1; }
if [[ -z "$OUT" ]]; then
    if [[ $MODE == target ]]; then OUT="$PWD/kalibr_target"; else OUT="$(dirname "$IN")/kalibr_$MODE"; fi
    mkdir -p "$OUT"
fi

# Run bash in the Kalibr container as the calling user: /work = OUT, /in = input (read-only).
kalibr() {
    local mounts=(-v "$OUT:/work" -v "$HERE:/tool:ro")
    [[ -n "$IN" ]] && mounts+=(-v "$IN:/in:ro")
    docker run --rm --user "$(id -u):$(id -g)" \
        -e HOME=/tmp -e ROS_HOME=/tmp/.ros -e MPLBACKEND=Agg \
        "${mounts[@]}" -w /work --entrypoint bash "$IMAGE" \
        -c "source /catkin_ws/devel/setup.bash && $1"
}

if [[ -n "$TARGET" ]]; then cp "$TARGET" "$OUT/target.yaml"; else cat > "$OUT/target.yaml" <<EOT
target_type: aprilgrid
tagCols: $COLS
tagRows: $ROWS
tagSize: $TAG_SIZE   # black edge of one tag [m], as MEASURED on the screen/print
tagSpacing: 0.3      # gap between tags / tagSize
EOT
fi

if [[ $MODE == target ]]; then
    kalibr "python3 /tool/aprilgrid_png.py --cols $COLS --rows $ROWS --screen $SCREEN --out target.png --check"
    echo "Show $OUT/target.png full screen (F11), measure, and pass the MEASURED tag size as --tag-size."
    if [[ $PDF == 1 ]]; then
        kalibr "rosrun kalibr kalibr_create_target_pdf --type apriltag --nx $COLS --ny $ROWS --tsize $TAG_SIZE --tspace 0.3 target"
        echo "For printing: $OUT/target.pdf, print at ACTUAL SIZE (not fit to page): tags $TAG_SIZE m. Measure anyway."
    fi
    exit 0
fi

# kalibr_bagcreater wants <dir>/cam0/<t_ns>.png and <dir>/imu0.csv.
BAG=$MODE.bag
make_bag() {
    echo "rm -rf stage && mkdir stage && ln -s /in/cam0/data stage/cam0 && cp /in/imu0/data.csv stage/imu0.csv \
          && rosrun kalibr kalibr_bagcreater --folder stage --output-bag $BAG"
}

calibrate_cam() {   # intrinsics; with --cam-until S only the first S seconds (the calm part)
    local range=""
    [[ -n "$CAM_UNTIL" ]] && range="--bag-from-to 0 $CAM_UNTIL"
    kalibr "rosrun kalibr kalibr_calibrate_cameras --bag $BAG --bag-freq ${CAM_FREQ:-5} $range \
        --target target.yaml --models pinhole-radtan --topics /cam0/image_raw --dont-show-report"
}

calibrate_imucam() {   # $1: camchain file inside OUT
    local rate
    rate=$(awk -F, 'NR>1{t[++n]=$1} END{printf "%.1f", 1e9*(n-1)/(t[n]-t[1])}' "$IN/imu0/data.csv")
    if [[ -n "$IMU_YAML" ]]; then
        sed -e "s#^rostopic:.*#rostopic: /imu0#" -e "s#^update_rate:.*#update_rate: $rate#" "$IMU_YAML" > "$OUT/imu.yaml"
    else
        cat > "$OUT/imu.yaml" <<EOT
rostopic: /imu0
update_rate: $rate
# PLACEHOLDERS: typical phone values, inflated. Replace with an Allan-variance result (--imu-yaml).
accelerometer_noise_density: 2.0e-3   # m/s^2/sqrt(Hz)
accelerometer_random_walk:   4.0e-4   # m/s^3/sqrt(Hz)
gyroscope_noise_density:     2.0e-4   # rad/s/sqrt(Hz)
gyroscope_random_walk:       4.0e-5   # rad/s^2/sqrt(Hz)
EOT
    fi
    kalibr "rosrun kalibr kalibr_calibrate_imu_camera --bag $BAG --bag-freq ${IMU_FREQ:-15} \
        --target target.yaml --cams $1 --imu imu.yaml --dont-show-report"
}

kalibr "$(make_bag)"
case $MODE in
    cam)
        calibrate_cam
        echo "result: $OUT/cam-camchain.yaml  (report: cam-report-cam.pdf)" ;;
    imucam)
        [[ -f "$CAMCHAIN" ]] || { echo "imucam needs --camchain <a Kalibr camchain.yaml>" >&2; exit 1; }
        cp "$CAMCHAIN" "$OUT/camchain-in.yaml"
        calibrate_imucam camchain-in.yaml
        echo "result: $OUT/imucam-camchain-imucam.yaml  (report: imucam-report-imucam.pdf)" ;;
    all)
        calibrate_cam
        calibrate_imucam all-camchain.yaml
        echo "intrinsics: $OUT/all-camchain.yaml  (report: all-report-cam.pdf)"
        echo "camera-IMU: $OUT/all-camchain-imucam.yaml  (report: all-report-imucam.pdf)" ;;
esac
