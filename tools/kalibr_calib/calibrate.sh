#!/usr/bin/env bash
#
# calibrate.sh: camera and camera-IMU calibration with Kalibr, in Docker.
#
# Input is a EuRoC-style folder (any source): <dir>/cam0/data/<t_ns>.png and <dir>/imu0/data.csv
# (header line, then t_ns, gx gy gz [rad/s], ax ay az [m/s^2]). Run with --help for the guide.
# Needs Docker and the image from build.sh (same folder). Self-contained: copy the folder as is.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KALIBR_COMMIT=$(sed -n 's/^KALIBR_COMMIT=\([0-9a-f]*\).*/\1/p' "$HERE/build.sh")
IMAGE=${KALIBR_IMAGE:-zf-kalibr:${KALIBR_COMMIT:0:7}}
ME="$(basename "$0")"

# ---------------------------------------------------------------------------------------------
# Looks
# ---------------------------------------------------------------------------------------------
if [[ -t 1 ]]; then
    B=$'\e[1m' DIM=$'\e[2m' RED=$'\e[31m' GRN=$'\e[32m' YEL=$'\e[33m' BLU=$'\e[34m' R=$'\e[0m'
    BACK=$'\r\e[K'   # back to the line's start and clear it: "… doing" becomes "✓ done"
else
    B="" DIM="" RED="" GRN="" YEL="" BLU="" R="" BACK=$'\n'
fi
title()   { echo; echo "${B}${BLU}━━ $* ${R}"; }
info()    { echo "  $*"; }
ok()      { echo "  ${GRN}✓${R} $*"; }
warn()    { echo "  ${YEL}!${R} $*"; }
fail()    { echo; echo "  ${RED}${B}✗ $1${R}" >&2; shift; for l in "$@"; do echo "    $l" >&2; done; echo >&2; exit 1; }
step()    { echo "  ${B}$1${R}  $2"; }
pretty()  { [[ $1 == "$HOME"* ]] && echo "~${1#"$HOME"}" || echo "$1"; }

usage() {
cat <<EOF

${B}$ME${R}: calibrate a phone camera and its IMU with Kalibr.

${B}The short version${R} (one recording does everything):

  ${B}1. Make the target${R}            $ME target
       A grid of black squares (AprilGrid), as a sheet to print (target.pdf, recommended: its
       size is known) and as an image for a screen (target.png, fastest: measure it with a ruler).

  ${B}2. Record${R} about 70 s with the phone, target lying still, exposure 2-3 ms:
       0-30 s  calm: move the phone slowly so the grid visits every part of the image
       30-70 s brisk: rotate and move the phone along all axes (grid partly visible is fine)

  ${B}3. Calibrate${R}                  $ME all <recording folder> --tag-size 0.032 --cam-until 30
       --tag-size is the size you MEASURED, in metres. Takes 10-30 minutes.

${B}Commands${R}
  target                     make target.pdf (paper) and target.png (screen)
  all    <folder>            lens + camera-IMU from one recording       (recommended)
  cam    <folder>            lens only (focal length, distortion)
  imucam <folder>            camera-IMU only, using an existing lens calibration (--camchain)

  <folder> is a phone recording (video_meta.pb3 + video; converted automatically when
  tools/pb3_export is next to this tool) or a EuRoC folder (cam0/data/*.png + imu0/data.csv).

${B}Options${R}
  --tag-size M               measured size of one big square, in METRES (e.g. 0.032)
  --cam-until S              use only the first S seconds (the calm part) for the lens
  --camchain FILE            lens calibration for 'imucam' (Kalibr camchain.yaml)
  --target FILE              use another Kalibr target file instead (e.g. a checkerboard)
  --imu-yaml FILE            IMU noise values (Kalibr format); default: typical phone values
  --screen WxH               'target': screen resolution (default: the built-in screen)
  --tag-size M               'target': square size on paper (default 0.03 = 3 cm)
  --cols N --rows N          grid size (default 6 x 4)
  --out DIR                  where results go (default: next to the folder)
  --cam-freq / --imu-freq    images per second Kalibr uses (default 5 / 15)

EOF
exit "${1:-0}"
}

# ---------------------------------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------------------------------
abspath() { echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; }
[[ $# -ge 1 ]] || usage 1
MODE=$1; shift
case $MODE in
    target|cam|imucam|all) ;;
    -h|--help|help) usage 0 ;;
    *) fail "Unknown command '$MODE'." "Use one of: target, all, cam, imucam.  ($ME --help)" ;;
esac

IN="" PB3_REC=""
PB3_EXPORT="$HERE/../pb3_export/export.sh"   # optional: converts the phone app's recordings
if [[ $MODE != target ]]; then
    [[ $# -ge 1 && $1 != --* ]] || fail "Which recording?" "Usage: $ME $MODE <folder> --tag-size M ..."
    [[ -d $1 ]] || fail "Folder not found: $1"
    IN="$(cd "$1" && pwd)"; shift
    [[ -d $IN/cam0 ]] || [[ ! -d $IN/mav0 ]] || IN="$IN/mav0"
    if [[ ! -d $IN/cam0/data && -f $IN/video_meta.pb3 && -x $PB3_EXPORT ]]; then
        PB3_REC=$IN; IN="$PB3_REC/export_calib/euroc/mav0"   # converted below, after the checks
    elif [[ ! -d $IN/cam0/data || ! -f $IN/imu0/data.csv ]]; then
        fail "$(pretty "$IN") is not a recording this tool can read." \
            "Give either a EuRoC folder (cam0/data/<timestamp>.png + imu0/data.csv)" \
            "or, with tools/pb3_export next to this tool, a phone recording (video_meta.pb3 + video)."
    fi
fi

PDF=0 SCREEN="" TAG_SIZE="" COLS=6 ROWS=4 CAM_FREQ="" IMU_FREQ="" CAM_UNTIL=""
CAMCHAIN="" IMU_YAML="" OUT="" TARGET=""
need() { [[ $# -ge 2 && $2 != --* ]] || fail "$1 needs a value."; }
while [[ $# -gt 0 ]]; do
    case $1 in
        --tag-size)  need "$@"; TAG_SIZE=$2; shift 2 ;;
        --cols)      need "$@"; COLS=$2; shift 2 ;;
        --rows)      need "$@"; ROWS=$2; shift 2 ;;
        --screen)    need "$@"; SCREEN=$2; shift 2 ;;
        --pdf)       PDF=1; shift ;;
        --cam-freq)  need "$@"; CAM_FREQ=$2; shift 2 ;;
        --imu-freq)  need "$@"; IMU_FREQ=$2; shift 2 ;;
        --cam-until) need "$@"; CAM_UNTIL=$2; shift 2 ;;
        --camchain)  need "$@"; [[ -f $2 ]] || fail "--camchain: file not found: $2"; CAMCHAIN=$(abspath "$2"); shift 2 ;;
        --imu-yaml)  need "$@"; [[ -f $2 ]] || fail "--imu-yaml: file not found: $2"; IMU_YAML=$(abspath "$2"); shift 2 ;;
        --target)    need "$@"; [[ -f $2 ]] || fail "--target: file not found: $2"; TARGET=$(abspath "$2"); shift 2 ;;
        --out)       need "$@"; mkdir -p "$2"; OUT="$(cd "$2" && pwd)"; shift 2 ;;
        -h|--help)   usage 0 ;;
        *) fail "Unknown option '$1'." "$ME --help lists all options." ;;
    esac
done

# The tag size: required for calibrating (unless --target), a sanity check against centimetres.
if [[ $MODE == target ]]; then
    [[ -z $TARGET ]] || fail "--target is for calibrating, not for 'target'."
    TAG_SIZE=${TAG_SIZE:-0.03}
elif [[ -z $TARGET ]]; then
    [[ -n $TAG_SIZE ]] || fail "Please give the measured tag size: --tag-size M (in metres)." \
        "Measure on the target as it says below the grid, e.g. W = 26.0 cm -> 26.0 / 8.1 = 3.21 cm -> --tag-size 0.0321"
fi
if [[ -n $TAG_SIZE ]]; then
    awk -v t="$TAG_SIZE" 'BEGIN{exit !(t+0 > 0)}' || fail "--tag-size '$TAG_SIZE' is not a positive number."
    awk -v t="$TAG_SIZE" 'BEGIN{exit !(t+0 < 0.5)}' || fail "--tag-size $TAG_SIZE looks like centimetres." \
        "It must be in METRES: 3.2 cm -> --tag-size 0.032"
fi
[[ $MODE != imucam || -n $CAMCHAIN ]] || fail "'imucam' needs the lens calibration: --camchain <camchain.yaml>." \
    "No lens calibration yet? Use '$ME all' instead: it does both from one recording."

command -v docker > /dev/null || fail "Docker is not installed." "See https://docs.docker.com/engine/install/"
docker info > /dev/null 2>&1 || fail "Docker is installed but not usable for this user." \
    "Start it, or add yourself to the docker group: sudo usermod -aG docker \$USER (then log in again)."
docker image inspect "$IMAGE" > /dev/null 2>&1 || fail "The Kalibr image ($IMAGE) is not built yet." \
    "Build it once (15-30 minutes): $(pretty "$HERE")/build.sh"

if [[ -z $OUT ]]; then
    if [[ $MODE == target ]]; then OUT="$PWD/kalibr_target"
    elif [[ -n $PB3_REC ]]; then OUT="$PB3_REC/kalibr_$MODE"
    else OUT="$(dirname "$IN")/kalibr_$MODE"; fi
    mkdir -p "$OUT"
fi
LOG="$OUT/kalibr.log"

# ---------------------------------------------------------------------------------------------
# Running things in the Kalibr container
# ---------------------------------------------------------------------------------------------
# kalibr <command>: runs as the calling user, /work = OUT, /in = input (read-only), /tool = here.
kalibr() {
    local mounts=(-v "$OUT:/work" -v "$HERE:/tool:ro")
    [[ -n $IN ]] && mounts+=(-v "$IN:/in:ro")
    docker run --rm --user "$(id -u):$(id -g)" \
        -e HOME=/tmp -e ROS_HOME=/tmp/.ros -e MPLBACKEND=Agg -e PYTHONWARNINGS=ignore \
        "${mounts[@]}" -w /work --entrypoint bash "$IMAGE" \
        -c "source /catkin_ws/devel/setup.bash && $1"
}

# run_step "what" "hint if it fails" <command>: quiet, timed, with the log's end on failure.
run_step() {
    local what=$1 hint=$2 cmd=$3 t0=$SECONDS
    printf "  ${DIM}…${R} %s " "$what"
    echo "==== $what" >> "$LOG"
    if kalibr "$cmd" >> "$LOG" 2>&1; then
        printf "%s  ${GRN}✓${R} %s ${DIM}(%ss)${R}\n" "$BACK" "$what" "$((SECONDS - t0))"
    else
        printf "%s  ${RED}✗${R} %s\n" "$BACK" "$what"
        local known=""
        # Known causes get a plain explanation instead of Kalibr's Python traceback.
        if grep -q -i 'No corners could be extracted' "$LOG"; then
            known="Kalibr found no target in the images. Check that the grid is visible and sharp in the video, and that --cols/--rows match the target (default 6 x 4)."
        elif grep -q 'Could not initialize the intrinsics' "$LOG"; then
            known="Kalibr could not estimate the focal length. The grid needs to be seen from several angles (tilt the phone), not only straight on."
        elif grep -q 'Max. attemps reached' "$LOG"; then
            known="Kalibr gave up: too few good views. Record the calm part longer and slower, with the grid in every part of the image."
        fi
        if [[ -n $known ]]; then
            fail "$what failed." "$known" "${DIM}Details: $(pretty "$LOG")${R}"
        fi
        echo; echo "  ${DIM}Last lines of the log ($(pretty "$LOG")):${R}"
        grep -v -E '^\s*$|Progress' "$LOG" | tail -8 | sed 's/^/    /' | cut -c1-150
        fail "$what failed." "$hint"
    fi
}

summary() {   # summary cam|imucam <prefix>
    echo
    kalibr "python3 /tool/summarize.py $1 /work $2" 2>/dev/null \
        | sed -e "s/ ✓ / ${GRN}✓${R} /" -e "s/ ! / ${YEL}!${R} /" -e "s/ ✗ / ${RED}✗${R} /" \
        || warn "Could not read the results for the summary; see the report PDF."
}

# Connected screens, one per line: "<kind>|<connector>|<native resolution>|<size in cm or ?>",
# built-in first. From the kernel (sysfs), which is right under Wayland too (xrandr there can
# report scaled modes). The size comes from the screen's EDID (bytes 21/22, in cm) if present.
list_screens() {
    local d name kind res size w h
    for d in /sys/class/drm/card*-*; do
        [[ -f $d/status && $(cat "$d/status") == connected && -n $(head -1 "$d/modes" 2>/dev/null) ]] || continue
        name=$(basename "$d"); name=${name#card*-}
        case $name in eDP*|LVDS*|DSI*) kind=0 ;; *) kind=1 ;; esac
        res=$(head -1 "$d/modes")
        size="?"
        if [[ -r $d/edid ]]; then   # sysfs files report size 0, so no -s
            read -r w h < <(od -An -tu1 -j21 -N2 "$d/edid")
            [[ ${w:-0} -gt 0 && ${h:-0} -gt 0 ]] && size="${w} x ${h} cm"
        fi
        echo "$kind|$name|$res|$size"
    done | sort -t'|' -k1,1 -k2,2 | sed -e 's/^0|/built-in|/' -e 's/^1|/external|/'
}

write_target_yaml() {
    if [[ -n $TARGET ]]; then
        cp "$TARGET" "$OUT/target.yaml"
    else
        cat > "$OUT/target.yaml" <<EOT
target_type: aprilgrid
tagCols: $COLS
tagRows: $ROWS
tagSize: $TAG_SIZE   # one big square incl. its black border [m], as MEASURED
tagSpacing: 0.3      # gap between squares / tagSize
EOT
    fi
}

# ---------------------------------------------------------------------------------------------
# target
# ---------------------------------------------------------------------------------------------
if [[ $MODE == target ]]; then
    title "Calibration target"
    info "Two ways to show the target to the camera:"
    echo
    step "Paper " "${B}recommended${R}. Printed at actual size (100 %), its size is known: each big square"
    step "      " "is $(awk -v t="$TAG_SIZE" 'BEGIN{printf "%.2f", t*100}') cm. Glue it flat onto cardboard."
    step "Screen" "${B}fastest${R}, no printer. The size depends on the screen, so you MEASURE it with a"
    step "      " "ruler. Shown full screen, the image fills the screen and says how to measure it."

    title "Screens"
    mapfile -t SCREENS < <(list_screens)
    if [[ ${#SCREENS[@]} -eq 0 ]]; then
        info "No screen detected (e.g. over SSH)."
    else
        for l in "${SCREENS[@]}"; do
            IFS='|' read -r kind name res size <<< "$l"
            info "$(printf '%-9s %-10s %-10s %s' "$kind" "$name" "$res" "$size")"
        done
    fi
    if [[ -z $SCREEN ]]; then
        if [[ ${#SCREENS[@]} -gt 0 ]]; then
            IFS='|' read -r kind name SCREEN size <<< "${SCREENS[0]}"
            info "Making the screen image for: ${B}$kind screen $name, $SCREEN${R}"
        else
            SCREEN=1920x1080
            info "Making the screen image for: ${B}$SCREEN${R} (default)"
        fi
        for l in "${SCREENS[@]:1}"; do
            IFS='|' read -r kind name res size <<< "$l"
            info "${DIM}For the $kind screen $name instead:  $ME target --screen $res${R}"
        done
    else
        info "Making the screen image for: ${B}$SCREEN${R} (--screen)"
    fi

    title "Making it"
    : > "$LOG"
    run_step "Drawing the target and checking it with Kalibr's detector" "" \
        "python3 /tool/aprilgrid_png.py --cols $COLS --rows $ROWS --screen $SCREEN --out target.png \
         --pdf target.pdf --tag-size $TAG_SIZE --check"
    ok "target.pdf  for paper (A4)"
    ok "target.png  for the screen ($SCREEN)"
    info "${DIM}in $(pretty "$OUT")${R}"

    title "Next steps"
    step "1." "Paper:   print target.pdf at ACTUAL SIZE (100 %, not 'fit to page'), glue it flat."
    step "  " "         Check with a ruler: outermost black edges W = $(awk -v t="$TAG_SIZE" 'BEGIN{printf "%.2f", t*810}') cm (printers sometimes scale)."
    step "  " "Screen:  open target.png, press F11 (full screen), brightness up, no reflections."
    step "  " "         Measure W = outermost black edge to outermost black edge, with a ruler."
    step "2." "Tag size = W / 8.1, in metres.  Paper: $TAG_SIZE (if W matched).  Screen: e.g. 26.0 cm -> 0.0321"
    step "3." "Record ~70 s, target still, exposure 2-3 ms:"
    step "  " "         0-30 s calm (grid in every part of the image), 30-70 s brisk (all axes)."
    step "4." "Calibrate:  $ME all <recording> --tag-size <tag size> --cam-until 30"
    echo
    exit 0
fi

# ---------------------------------------------------------------------------------------------
# cam / imucam / all
# ---------------------------------------------------------------------------------------------
if [[ -n $PB3_REC ]]; then
    title "Converting the phone recording"
    info "$(pretty "$PB3_REC")  ->  images + IMU table ${DIM}(timestamps at mid-exposure)${R}"
    t0=$SECONDS
    rm -rf "$PB3_REC/export_calib"
    if "$PB3_EXPORT" "$PB3_REC" --out /data/export_calib --image-time mid-exposure > "$PB3_REC/export_calib.log" 2>&1; then
        ok "Converted ${DIM}($((SECONDS - t0))s; files in $(pretty "$PB3_REC")/export_calib)${R}"
        rm -f "$PB3_REC/export_calib.log"
    else
        tail -5 "$PB3_REC/export_calib.log" | sed 's/^/    /'
        fail "Converting the recording failed." "Full output: $(pretty "$PB3_REC")/export_calib.log"
    fi
fi

BAG=$MODE.bag
NIMG=$(find "$IN/cam0/data" -maxdepth 1 -name '*.png' | wc -l)
IMU_RATE=$(awk -F, 'NR>1{t[++n]=$1} END{if(n>1) printf "%.0f", 1e9*(n-1)/(t[n]-t[1]); else print 0}' "$IN/imu0/data.csv")
SECS=$(ls "$IN/cam0/data" | sed -n '1p;$p' | sed 's/\.png$//' | awk 'NR==1{a=$1} NR==2{printf "%.0f", ($1-a)/1e9}')
[[ $NIMG -gt 0 ]] || fail "No images in $(pretty "$IN")/cam0/data."
[[ $IMU_RATE -gt 0 ]] || fail "No IMU samples in $(pretty "$IN")/imu0/data.csv."

case $MODE in
    all)    title "Calibration: lens + camera-IMU, from one recording" ;;
    cam)    title "Calibration: lens" ;;
    imucam) title "Calibration: camera-IMU" ;;
esac
info "Recording:  $(pretty "${PB3_REC:-$IN}")  ${DIM}($NIMG images, ${SECS} s, IMU $IMU_RATE Hz)${R}"
if [[ -n $TARGET ]]; then info "Target:     $(pretty "$TARGET")"
else info "Target:     AprilGrid $COLS x $ROWS, squares $TAG_SIZE m"; fi
[[ -n $CAM_UNTIL && $MODE != imucam ]] && info "Lens from:  the first $CAM_UNTIL s"
info "Results:    $(pretty "$OUT")"
[[ $MODE == cam ]] || info "${DIM}This takes about 10-30 minutes.${R}"
echo

: > "$LOG"
write_target_yaml
run_step "Packing images and IMU data for Kalibr" "" \
    "rm -rf stage && mkdir stage && ln -s /in/cam0/data stage/cam0 && cp /in/imu0/data.csv stage/imu0.csv \
     && rosrun kalibr kalibr_bagcreater --folder stage --output-bag $BAG && rm -rf stage"

calibrate_cam() {
    local range=""
    [[ -n $CAM_UNTIL ]] && range="--bag-from-to 0 $CAM_UNTIL"
    run_step "Lens calibration (focal length, distortion)" \
        "Check: grid sharp and in many parts of the image; --cam-until covers the calm part." \
        "rosrun kalibr kalibr_calibrate_cameras --bag $BAG --bag-freq ${CAM_FREQ:-5} $range \
         --target target.yaml --models pinhole-radtan --topics /cam0/image_raw --dont-show-report"
}

calibrate_imucam() {   # $1: camchain file inside OUT
    if [[ -n $IMU_YAML ]]; then
        sed -e "s#^rostopic:.*#rostopic: /imu0#" -e "s#^update_rate:.*#update_rate: $IMU_RATE#" "$IMU_YAML" > "$OUT/imu.yaml"
    else
        cat > "$OUT/imu.yaml" <<EOT
rostopic: /imu0
update_rate: $IMU_RATE
# PLACEHOLDERS: typical phone values, inflated. Replace with an Allan-variance result (--imu-yaml).
accelerometer_noise_density: 2.0e-3   # m/s^2/sqrt(Hz)
accelerometer_random_walk:   4.0e-4   # m/s^3/sqrt(Hz)
gyroscope_noise_density:     2.0e-4   # rad/s/sqrt(Hz)
gyroscope_random_walk:       4.0e-5   # rad/s^2/sqrt(Hz)
EOT
    fi
    run_step "Camera-IMU calibration (rotation, offset, time shift)" \
        "Check: brisk motion on all axes, grid visible most of the time, short exposure." \
        "rosrun kalibr kalibr_calibrate_imu_camera --bag $BAG --bag-freq ${IMU_FREQ:-15} \
         --target target.yaml --cams $1 --imu imu.yaml --dont-show-report"
}

case $MODE in
    cam)    calibrate_cam ;;
    imucam) cp "$CAMCHAIN" "$OUT/camchain-in.yaml"; calibrate_imucam camchain-in.yaml ;;
    all)    calibrate_cam; calibrate_imucam all-camchain.yaml ;;
esac
rm -f "$OUT/$BAG"

title "Results"
[[ $MODE == imucam ]] || summary cam "$MODE"
[[ $MODE == cam ]] || summary imucam "$MODE"
[[ -n $IMU_YAML || $MODE == cam ]] || \
    { echo; warn "IMU noise values are typical phone values. For final results, measure yours (Allan variance) and pass --imu-yaml."; }

title "Files"
[[ $MODE == imucam ]] || ok "$MODE-camchain.yaml          lens calibration"
[[ $MODE == cam ]]    || ok "$MODE-camchain-imucam.yaml   lens + camera-IMU (this is what SLAM needs)"
ls "$OUT"/*report*.pdf > /dev/null 2>&1 && ok "$(cd "$OUT" && ls *report*.pdf | tr '\n' ' ')  reports with plots"
info "${DIM}in $(pretty "$OUT")   (full log: kalibr.log)${R}"
echo
