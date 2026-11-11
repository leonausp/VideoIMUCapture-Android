# kalibr_calib: Kalibr calibration from a EuRoC folder

Camera intrinsics and camera–IMU extrinsics + time offset with the official
[Kalibr](https://github.com/ethz-asl/kalibr) (ROS Noetic, in Docker, pinned commit).

The folder is self-contained and doesn't depend on this app: the input is a EuRoC-style folder,
so any source works (`tools/pb3_export`, or another project's importer).

```
<euroc_dir>/                 (EuRoC's mav0/)
├── cam0/data/<t_ns>.png     greyscale or colour, named by timestamp in ns
└── imu0/data.csv            header line, then: t_ns, gx, gy, gz [rad/s], ax, ay, az [m/s²]
```

## Usage

```bash
tools/kalibr_calib/build.sh        # once, ~15-30 min: image zf-kalibr:<commit> (8 GB)

# 1. Target (or skip, and pass an existing Kalibr target yaml with --target FILE)
tools/kalibr_calib/calibrate.sh target --tag-size 0.03            # -> kalibr_target/target.pdf
#    show it full screen or print it, MEASURE one tag's black edge, use that as --tag-size

# 2. Camera intrinsics (pinhole-radtan), Kalibr uses 5 images/s
tools/kalibr_calib/calibrate.sh cam    <euroc_dir> --tag-size 0.031
#    -> <euroc_dir>/../kalibr_cam/cam-camchain.yaml (+ report PDF)

# 3. Camera–IMU extrinsics + time offset, 15 images/s
tools/kalibr_calib/calibrate.sh imucam <euroc_dir> --tag-size 0.031 \
    --camchain <...>/kalibr_cam/cam-camchain.yaml [--imu-yaml allan.yaml]
#    -> <euroc_dir>/../kalibr_imucam/imucam-camchain-imucam.yaml (T_cam_imu, timeshift_cam_imu)
```

Options: `--cols/--rows` (AprilGrid, default 6×4: fits a laptop screen or A4 landscape),
`--target FILE` (any Kalibr target yaml, e.g. a checkerboard), `--freq` (images per second Kalibr
uses), `--out DIR`, `--imu-yaml FILE` (IMU noise, Kalibr format). Without `--imu-yaml` the IMU
noise values are marked placeholders (typical phone values, inflated); replace them with an
Allan-variance result.

`--camchain` can be any Kalibr camchain file, including one from another intrinsics tool, as long as
its resolution matches the images.

## Timestamps

`timeshift_cam_imu` is relative to the image timestamps in the input. Export calibration and SLAM
data with the same image-time convention (with `tools/pb3_export`: `--image-time mid-exposure`
for both), then add the timeshift to the image times (t_imu = t_cam + shift).

## Recording tips

- Same settings as for SLAM: lens, resolution, focus, OIS/EIS off.
- **cam:** ~1 min, slow, target in every part of the image, especially corners; tilt up to ~45°.
- **imucam:** ~1 min, target static and always in view, good light, short exposure; rotate and
  translate the phone briskly around all three axes. Repeat 2–3× and compare the timeshift
  (should agree to ~1 ms).

## Notes

- Kalibr's tools are run as `rosrun kalibr <tool>` inside the image; `calibrate.sh` does that.
- The image's entrypoint ignores commands, so `calibrate.sh` uses `--entrypoint bash`.
- Work files (`stage/`, `<mode>.bag`) stay in the output folder; delete them when done.
