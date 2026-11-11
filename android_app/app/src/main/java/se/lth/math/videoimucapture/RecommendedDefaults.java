package se.lth.math.videoimucapture;

/**
 * The app's defaults in ONE place: what a fresh install (or "Reset to recommended settings")
 * starts with. They are the settings the Pixel 6 VIO test recordings of 2026-11-11 were made
 * with: main lens through the logical camera, video-only session, 1280x960, 5 ms exposure,
 * manual focus, OIS off with OIS data on, no stabilisation or distortion correction, exposure
 * frozen while recording. Two deliberate differences: focus is 1.1 on the phone's scale (~1.5 m
 * real, for indoor scenes) instead of the computed hyperfocal, and exposure is SHUTTER
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

    // Focus: MANUAL (fixed, so the focal length doesn't breathe), at 1.1 on the phone's focus
    // scale = ~1.5 m. The Pixel 6 main lens's scale is APPROXIMATE and offset: tap-to-focus on a
    // target at distances measured from the target's own size (0.59-2.05 m, 2026-11-11) gave
    //     phone value = 0.40 + 1.08 x real diopters,
    // so its "infinity" is 0.40, 0.65 was really ~4 m and the computed hyperfocal (0.31) beyond
    // infinity. 1.1 = real ~0.65 dpt: sharp enough (calculated, <~2 px) from ~0.8 m outwards,
    // which suits indoor scenes and calibration targets at 0.6-0.8 m. Calibrate at the same focus.
    static final String FOCUS_MODE = "MANUAL";
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
