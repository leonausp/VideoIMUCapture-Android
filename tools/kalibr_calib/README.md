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
tools/kalibr_calib/build.sh                      # once, 15-30 min: the Kalibr image (8 GB)
tools/kalibr_calib/calibrate.sh --help           # the guide
```

**1. Target.** `calibrate.sh target` writes both, in `kalibr_target/`:

- `target.pdf`, **recommended:** printed on A4 at actual size (100 %), its size is known (squares
  3 cm, outermost edges 24.3 cm; check with a ruler, printers sometimes scale). Glue it flat.
- `target.png`, **fastest:** for a screen, shown full screen (F11). Its size depends on the screen,
  so measure it with a ruler; the image says how (W = outermost black edges, tag size = W / 8.1).

The script lists the connected screens (built-in/external, resolution, size from the EDID), makes
the PNG for the built-in one, and prints the `--screen WxH` command for each other screen.

**2. Record** ~60-70 s, target still, exposure 2-3 ms: start calmly, then rotate and move the
phone briskly along all axes (the grid may be partly out of view). Each code cell of the grid
needs ~4 px in the image (A4 with the main lens: 0.6-0.8 m).

**3. Calibrate** (5-10 min):

```bash
tools/kalibr_calib/calibrate.sh imucam <recording> --tag-size 0.03 --camchain factory
```

`<recording>` is a EuRoC folder (`cam0/data/<t_ns>.png` + `imu0/data.csv`, and for
`--camchain factory` the start values in `cam0/sensor.yaml`) or, when `tools/pb3_export` sits next
to this folder, a phone recording (`video_meta.pb3` + video), converted automatically with
mid-exposure timestamps. `--camchain factory` uses those start values as the lens model: on phone
recordings they are the phone's own per-frame focal length and factory distortion, which proved
more reliable than Kalibr's lens calibration (see docs/07). Kalibr's output goes to `kalibr.log`;
the terminal shows the steps, a rated summary (time offset vs. half the rolling-shutter readout,
rotation vs. the phone's axes, camera-IMU distance, reprojection error) and the result files:

- `imucam-camchain-imucam.yaml`: lens + camera-IMU, what SLAM needs
- `imucam-report-imucam.pdf`: Kalibr's plots

Kalibr's lens calibration: `all <recording> --cam-until 30` (lens from the calm first 30 s, then
camera-IMU) or `cam <recording>` (lens only); the summary compares its focal length with the start
value and says when it went wrong. `imucam --camchain FILE` uses any Kalibr camchain. Other options: `--target FILE`
(any Kalibr target, e.g. a checkerboard), `--imu-yaml FILE` (IMU noise from an Allan-variance
run; otherwise marked placeholder values), `--cols/--rows`, `--out DIR`, `--cam-freq/--imu-freq`.
Mistakes such as a tag size in centimetres, a missing image or a wrong folder are reported with
what to do.

`aprilgrid_png.py` draws the PNG and the PDF from Kalibr's own tag codes and layout on whole
pixels, with the measuring instructions below the grid, and checks them with Kalibr's detector
(`--check`). `summarize.py` writes the rated summary from Kalibr's result files.

## Timestamps

`timeshift_cam_imu` is relative to the image timestamps in the input. Export calibration and SLAM
data with the same image-time convention (with `tools/pb3_export`: `--image-time mid-exposure`
for both), then add the timeshift to the image times (t_imu = t_cam + shift).

## Recording tips

- Same settings as for SLAM: lens, resolution, focus, OIS/EIS off.
- **One recording (`all`), ~70 s, target static, good light, exposure 2–3 ms:**
  - **0–30 s calm:** move the phone slowly so the grid visits every part of the image, especially
    the corners; tilt up to ~45°.
  - **30–70 s brisk:** rotate and translate the phone around all three axes; partial views of the
    grid are fine (AprilGrid), losing it completely is not.
- Repeat 2–3× and compare the timeshift (should agree to ~1 ms).

## Notes

- Kalibr's tools are run as `rosrun kalibr <tool>` inside the image; `calibrate.sh` does that.
- The image's entrypoint ignores commands, so `calibrate.sh` uses `--entrypoint bash`.
- Work files (`stage/`, `<mode>.bag`) stay in the output folder; delete them when done.
