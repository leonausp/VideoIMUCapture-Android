package se.lth.math.videoimucapture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Slider positions for the three FloatSeekBarPreferences, with the Pixel 6 ranges. */
public class SliderStepsTest {

    private static final float ZOOM_MIN = 0.6704426f, ZOOM_MAX = 7.0f;

    @Test
    public void zoomStepsIncludeTheWidestValueAndExactlyOne() {
        SliderSteps z = new SliderSteps(ZOOM_MIN, ZOOM_MAX, 0.1f);
        assertEquals(ZOOM_MIN, z.valueAt(0), 0f);           // fully left = widest
        assertEquals(0.7f, z.valueAt(1), 1e-6f);
        assertEquals(1.0f, z.valueAt(z.indexOf(1.0f)), 1e-6f);
        assertEquals("1.0 is its own step, not 0.97", 4, z.indexOf(1.0f));
    }

    @Test
    public void zoomMaxIsReachable() {
        // The old step count (6.33 truncated to 6) stopped at 6.67.
        SliderSteps z = new SliderSteps(ZOOM_MIN, ZOOM_MAX, 0.1f);
        assertEquals(ZOOM_MAX, z.valueAt(z.count() - 1), 0f);
        assertEquals(6.9f, z.valueAt(z.count() - 2), 1e-5f);
    }

    @Test
    public void oldOffGridValuesSnapToTheNearestStep() {
        SliderSteps z = new SliderSteps(ZOOM_MIN, ZOOM_MAX, 0.1f);
        assertEquals("a stored 0.9704 shows the 1.0 position", 1.0f,
                z.valueAt(z.indexOf(0.9704426f)), 1e-6f);
        assertEquals("0.6704 stays the widest position", 0, z.indexOf(0.6704426f));
        assertEquals(0, z.indexOf(0.5f));
        assertEquals(z.count() - 1, z.indexOf(9f));
    }

    @Test
    public void stepsAreMonotonic() {
        SliderSteps z = new SliderSteps(ZOOM_MIN, ZOOM_MAX, 0.1f);
        for (int i = 1; i < z.count(); i++) {
            assertEquals("index " + i, true, z.valueAt(i) > z.valueAt(i - 1));
            assertEquals("round trip " + i, i, z.indexOf(z.valueAt(i)));
        }
    }

    @Test
    public void focusRangeFromZeroIsUnchangedExceptTheEndpoint() {
        // Focus distance 0 .. 9.523809 diopters in 0.1 steps: 0, 0.1, ... 9.5, 9.523809.
        SliderSteps f = new SliderSteps(0f, 9.523809f, 0.1f);
        assertEquals(0f, f.valueAt(0), 0f);
        assertEquals(0.3f, f.valueAt(3), 1e-6f);
        assertEquals(9.5f, f.valueAt(f.count() - 2), 1e-5f);
        assertEquals(9.523809f, f.valueAt(f.count() - 1), 0f);
    }

    @Test
    public void exposureFiveMillisecondsIsAStep() {
        // Exposure 0.0265 .. 8310 ms in 0.01 ms steps.
        SliderSteps e = new SliderSteps(0.026503f, 8310.343667f, 0.01f);
        assertEquals(5.0f, e.valueAt(e.indexOf(5.0f)), 1e-4f);
        assertEquals(0.03f, e.valueAt(1), 1e-6f);
    }

    @Test
    public void degenerateRangeHasOnePosition() {
        SliderSteps d = new SliderSteps(1f, 1f, 0.1f);
        assertEquals(1, d.count());
        assertEquals(1f, d.valueAt(0), 0f);
        assertEquals(0, d.indexOf(3f));
    }
}
