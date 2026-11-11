/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.lth.math.videoimucapture;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.util.Log;
import android.util.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Camera-related utility functions.
 */
public class CameraUtils {
    private static final String TAG = CameraCaptureActivity.TAG;
    private static final float BPP = 0.25f;

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    public static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    /**
     * The rear camera to open: {@code preferred} if it exists and faces back, else the first
     * rear camera. Phones expose their lenses differently: the Pixel 6 has ONE rear camera (a
     * logical multi-camera; the ultrawide is reached by zoom), the Galaxy S21 has the ultrawide
     * as a separate rear camera id ("2"), which zoom cannot reach.
     */
    public static String getRearCameraId(CameraManager manager, String preferred) {
        if (preferred != null && !preferred.isEmpty()) {
            try {
                for (String id : manager.getCameraIdList()) {
                    if (id.equals(preferred)) {
                        Integer facing = manager.getCameraCharacteristics(id)
                                .get(CameraCharacteristics.LENS_FACING);
                        if (facing != null && facing == CameraMetadata.LENS_FACING_BACK) {
                            return id;
                        }
                    }
                }
            } catch (CameraAccessException | IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
        return getRearCameraId(manager);
    }

    /**
     * The rear lenses an app can reach, as {key, label}. key = "cameraId:zoom". Phones differ:
     * a logical multi-camera (Pixel 6) reaches its ultrawide by zoom below 1 -- offered as
     * "Ultrawide" at the minimum zoom; a standalone rear camera (Galaxy S21 ultrawide, id "2") is
     * opened by id at zoom 1. Kinds are relative to the first rear camera's main lens. A lens the
     * phone does not expose to apps cannot be listed.
     */
    public static java.util.List<String[]> listLenses(CameraManager manager) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        Float mainMm = null;
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraMetadata.LENS_FACING_BACK) {
                    continue;
                }
                float[] f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                float mm = (f != null && f.length > 0) ? f[0] : 0f;
                if (mainMm == null) {
                    mainMm = mm;
                }
                out.add(new String[]{id + ":1.0", lensLabel(mm, mainMm)});
                // A logical multi-camera whose zoom goes below 1 reaches a wider lens that way.
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.util.Range<Float> z = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                    if (z != null && z.getLower() < 0.95f) {
                        float wideMm = mm * z.getLower();   // nominal; the label uses the physical lens if known
                        if (android.os.Build.VERSION.SDK_INT >= 28) {
                            for (String pid : c.getPhysicalCameraIds()) {
                                float[] pf = manager.getCameraCharacteristics(pid)
                                        .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                                if (pf != null && pf.length > 0 && pf[0] < 0.95f * mm) {
                                    wideMm = Math.min(wideMm, pf[0]);
                                }
                            }
                        }
                        out.add(new String[]{id + ":" + z.getLower(), lensLabel(wideMm, mainMm)});
                    }
                }
            }
        } catch (CameraAccessException | IllegalArgumentException e) {
            e.printStackTrace();
        }
        return out;
    }

    private static String lensLabel(float mm, float mainMm) {
        String kind = mm < 0.75f * mainMm ? "Ultrawide" : mm > 1.4f * mainMm ? "Tele" : "Main";
        return String.format(java.util.Locale.US, "%s · %.1f mm", kind, mm);
    }

    public static String getRearCameraId(CameraManager manager) {
        try {
            String[] cameraIdList = manager.getCameraIdList();
            // Loop through our camera list
            for (String cameraId : cameraIdList) {
                // Get the camera
                CameraCharacteristics characteristics =
                        manager.getCameraCharacteristics(cameraId);
                try {
                    // Check if the camera is facing the back
                    if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
                            CameraMetadata.LENS_FACING_BACK) {
                        return cameraId;
                    }
                } catch (NullPointerException e) {
                    e.printStackTrace();
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return "0";
    }

    public static int calcBitRate(int width, int height, int frame_rate) {
        final int bitrate = (int) (BPP * frame_rate * width * height);
        Log.i(TAG, "bitrate=" + bitrate);
        return bitrate;
    }
}
