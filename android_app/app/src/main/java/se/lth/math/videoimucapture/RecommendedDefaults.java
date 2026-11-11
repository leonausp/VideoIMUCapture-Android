package se.lth.math.videoimucapture;

/**
 * The app's defaults in ONE place: what a fresh install (or "Reset to recommended settings")
 * starts with. They are the settings the Pixel 6 VIO test recordings of 2026-11-11 were made
 * with: main lens through the logical camera, video-only session, 1280x960, 5 ms exposure,
 * manual focus, OIS off with OIS data on, no stabilisation or distortion correction, exposure
 * frozen while recording. Two deliberate differences: focus is AUTOMATIC and locked at record start
 * instead of a fixed hyperfocal, and exposure is SHUTTER
 * PRIORITY (5 ms, ISO metered per scene and locked at record start) instead of the fixed ISO 1000
 * the test recordings used, which only suits that room's light.
 *
 * Code that reads a setting uses these as its fallback, and settings.xml must declare the same
 * defaults for the settings it defines; RecommendedDefaultsTest checks both, so the two places
 * cannot drift apart again (before, the XML said one thing and the code another for several).
 */
final class RecommendedDefaults {

    private RecommendedDefaults() {}

    // Camera and session
    static final String CAMERA_ID = "Logical";
    static final String LENS_SET = "none";            // Video only
    static final String VIDEO_SIZE = "1280x960";
    static final boolean USE_FULL_SENSOR = true;
    static final float ZOOM_RATIO = 1.0f;
    static final boolean DISTORTION_CORRECTION = false;
    static final boolean RAW_PIXELS = false;

    // Stabilisation
    static final boolean OIS = false;
    static final boolean OIS_DATA = true;
    static final boolean DVS = false;

    // Focus: TAP TO FOCUS (TOUCH_AUTO; the preview focuses once on the centre at start, then
    // wherever you tap -- continuous autofocus sometimes picks odd targets), LOCKED at record
    // start (CaptureModeManager): the
    // focal length moves with focus (~1.4 %/dpt on the Pixel 6 main lens), so it must not change
    // within a clip, but it may differ between clips -- the export takes the per-frame focal
    // (LENS_INTRINSIC_CALIBRATION, which follows the focus), and the camera-IMU calibration does
    // not depend on focus. A fixed number would not carry across phones: the Pixel 6's focus
    // scale is offset (phone value = 0.40 + 1.08 x real diopters, measured 2026-11-11).
    // FOCUS_DISTANCE is MANUAL's value: 1.1 on that scale = ~1.5 m real.
    static final String FOCUS_MODE = "TOUCH_AUTO";
    static final boolean FOCUS_HYPERFOCAL = false;
    static final float FOCUS_DISTANCE = 1.1f;

    // Exposure: SHUTTER_PRIORITY where supported -- the recordings used MANUAL 5 ms / ISO 1000,
    // which is right for one room only; shutter priority keeps the 5 ms and meters the ISO per
    // scene, then locks it for the clip. ISO is only the value MANUAL starts from.
    static final String EXPOSURE_MODE = "SHUTTER_PRIORITY";
    static final float EXPOSURE_MS = 5f;
    static final int ISO = 300;
    static final boolean LOCK_RADIOMETRY = true;
    static final boolean BLUR_BUDGET = false;
    static final int BLUR_BUDGET_PX = 3;
    static final boolean BLUR_BUDGET_MANUAL = false;
    static final int HOLD_STILL_PX = 40;

    // Recording
    static final String VIDEO_CODEC = "video/avc";    // H.264
    static final int VIDEO_BITRATE_MBPS = 0;           // automatic
    static final int STEREO_INTERVAL_S = 0;
    static final int IMU_BATCH_MS = 0;
    static final int IDLE_SLEEP_S = 60;

    // Settings screen
    static final boolean SHOW_EXPERT_SETTINGS = false;
}
