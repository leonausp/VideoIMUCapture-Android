# 07 – Calibration & analysis tools

VIO needs three calibrations: **camera intrinsics** (focal length, principal point, distortion),
**camera–IMU extrinsics** (rotation and translation between them), and the **camera–IMU time
offset**. IMU noise parameters are a fourth input. The `calibration/` folder (from the original
VideoIMUCapture project) does the first three with **Kalibr**, inside its own Docker image.

The original instructions are in [`calibration/README.md`](../calibration/README.md). This page is
the short version, adapted to our app ID.

## 0. Get the calibration environment

```bash
git submodule update --init          # fetches libs/dockers (helper scripts used by the run scripts)
cd calibration
DATA=$HOME/sessions ./run_dockerhub.sh   # pulls davidgillsjo/videoimucapture-calibration, mounts your data at /data
```

Inside the container you have Kalibr, Python, the compiled protobuf module and the
`calibration/*.py` scripts. Alternatively, build the image yourself: `./build_docker.sh`, then
`./run_docker.sh`.

> The submodule URL in `.gitmodules` is relative (`../dockers.git`). From our fork that may
> resolve to a repository that doesn't exist. If `git submodule update` fails, point it at
> `https://github.com/DavidGillsjo/dockers.git`.

## 1. Look at a recording

```bash
python data2statistics.py /data/vid_<date>/video_meta.pb3
```

This prints rates and shows whether the phone supplied `intrinsic_params` / `distortion_params`.
Factory values are a good starting point, but you usually still calibrate yourself.

## 2. Camera intrinsics

Record a slow video of an **AprilGrid** target from many angles, with the same resolution, focus
and zoom you'll use for data. Then:

```bash
python data2kalibr.py /data/<session> --tag-size <tag size in m> --subsample 30
cd /data/<session>/kalibr
kalibr_calibrate_cameras --bag kalibr.bag --target target.yaml --models pinhole-equi --topics /cam0/image_raw
```

The result is `camchain-kalibr.yaml`.

## 3. Camera–IMU calibration (extrinsics + time offset)

Record a **separate** sequence in front of the target, exciting all rotation and translation axes
with good lighting and a short exposure. Then:

```bash
python data2kalibr.py /data/<session> --tag-size <m> --subsample 3 --kalibr-calibration <path>/camchain-kalibr.yaml
cd /data/<session>/kalibr
kalibr_calibrate_imu_camera --target target.yaml --imu imu.yaml --cams camchain.yaml --bag kalibr.bag
```

The result is `camchain-imucam-kalibr.yaml` (T_cam_imu and `timeshift_cam_imu`), plus a PDF
report.

**IMU noise parameters** (`imu.yaml`). The script writes defaults. For better results, estimate
your phone's values with an Allan-variance tool from a long static recording (a few hours, phone
lying still), then inflate them a few times, as is usual for phone IMUs.

**Check:** repeat the camera–IMU calibration 2–3 times. The time offset should agree to within
about a millisecond. A large or varying offset points to a timing problem; check the timestamp
source in `camera_census.json`.

## 4. Convert recordings for SLAM

```bash
python data2rosbag.py /data/<session-or-folder> --calibration camchain-imucam-kalibr.yaml [--raw-image]
```

This writes ROS bags with image and IMU topics, and copies the calibration rescaled to the video
resolution. It crawls subfolders, so you can pass a whole folder of sessions. ORB-SLAM3, VINS-Mono,
OpenVINS and Basalt can consume ROS bags, or you can extract images and CSVs from them for
EuRoC-style input.

`data2images.py` just extracts frames. `data2lth_vision.py` is specific to Lund's pipeline.

## `analysis/`: checks from the previous fork

| Script | Checks |
|---|---|
| `frame_holes.py` | gaps in the per-frame metadata |
| `dump_meta.py` | prints metadata from a session |
| `analyze_pairs.py`, `overlay.py`, `zoom_probe.py` | stereo-pair and lens/crop checks |
| `wn_compare.py` | compares noise/white-noise between recordings |

`analysis/FINDINGS.md` is the previous author's lab notebook from validating the S24 Ultra. Install
the Python requirements with `pip install -r analysis/requirements.txt`.
