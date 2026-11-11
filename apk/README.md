# Ready-to-install APK

**[ZF-VIO-SLAM.apk](ZF-VIO-SLAM.apk)**: the app, built from this repository, for installing without
building it yourself.

| | |
|---|---|
| Version | 0.25-zf.1 |
| Built from commit | `9d4d434` (2026-11-12) |
| SHA-256 | `a7c33ada4b040a658719818fb81c309b58bcc2c27626e0bb6daed0e11d08baa0` |
| Android | 7.0 or newer |
| Tested on | Google Pixel 6, Google Pixel 9 Pro, Samsung Galaxy S21 |

## Install

**With a cable** (developer options and USB debugging on):

```bash
adb install -r ZF-VIO-SLAM.apk
```

**Without a cable:** copy the file to the phone and open it in a file manager. Android asks once to
allow installing apps from that file manager ("Install unknown apps").

On first start the app asks for the **camera**. It has no internet permission: nothing leaves the
phone (see the main README).

## Notes

- This is a **debug build**, signed with a development key. To update from a build signed with
  another key (e.g. one you built yourself), uninstall the app first; Android refuses updates across
  keys. Uninstalling deletes the recordings on the phone, so copy them off first.
- Check the download: `sha256sum ZF-VIO-SLAM.apk` must print the SHA-256 above.
- Newer code may already be on `main`; build it yourself with `docker/build.sh`
  ([docs/06](../docs/06-building-and-installing.md)).
