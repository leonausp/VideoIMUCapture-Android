package se.lth.math.videoimucapture;

/**
 * Slider positions for manual exposure: the standard photographic 1/3-stop series. They make the
 * round values (ISO 1000, 1/200 s = 5 ms...) one drag away; any other value can still be typed by
 * tapping the number. SliderSteps.ofValues() adds the device's exact min and max as end points.
 */
final class CameraValueSteps {

    private CameraValueSteps() {}

    /** ISO, 1/3 stops. */
    static final float[] ISO = {
            25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
            1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800,
            16000, 20000, 25600, 32000, 40000, 51200,
    };

    /** Shutter times in milliseconds, 1/3 stops: 1/8000 s ... 30 s. */
    static final float[] SHUTTER_MS = {
            1000f / 8000, 1000f / 6400, 1000f / 5000, 1000f / 4000, 1000f / 3200, 1000f / 2500,
            1000f / 2000, 1000f / 1600, 1000f / 1250, 1000f / 1000, 1000f / 800, 1000f / 640,
            1000f / 500, 1000f / 400, 1000f / 320, 1000f / 250, 1000f / 200, 1000f / 160,
            1000f / 125, 1000f / 100, 1000f / 80, 1000f / 60, 1000f / 50, 1000f / 40,
            1000f / 30, 1000f / 25, 1000f / 20, 1000f / 15, 1000f / 13, 1000f / 10,
            1000f / 8, 1000f / 6, 1000f / 5, 1000f / 4, 300, 400, 500, 600, 800,
            1000, 1300, 1600, 2000, 2500, 3200, 4000, 5000, 6000, 8000, 10000, 13000,
            15000, 20000, 25000, 30000,
    };
}
