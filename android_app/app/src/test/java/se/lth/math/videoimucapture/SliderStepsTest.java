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
    public void isoStepsAreThirdStopsWithinThePixel6Range() {
        // Pixel 6 ISO range 44 .. 11377.
        SliderSteps iso = SliderSteps.ofValues(44f, 11377f, CameraValueSteps.ISO);
        assertEquals(44f, iso.valueAt(0), 0f);                       // device minimum
        assertEquals(50f, iso.valueAt(1), 0f);                       // then the series
        assertEquals(1000f, iso.valueAt(iso.indexOf(1000f)), 0f);    // ISO 1000 is a position
        assertEquals(1250f, iso.valueAt(iso.indexOf(1000f) + 1), 0f);
        assertEquals(11377f, iso.valueAt(iso.count() - 1), 0f);      // device maximum
        assertEquals(10000f, iso.valueAt(iso.count() - 2), 0f);
        assertEquals("958 snaps to 1000", 1000f, iso.valueAt(iso.indexOf(958f)), 0f);
    }

    @Test
    public void shutterStepsHitTheUsualVioExposures() {
        // Pixel 6 exposure range 0.026503 .. 8310.34 ms.
        SliderSteps sh = SliderSteps.ofValues(0.026503f, 8310.343667f, CameraValueSteps.SHUTTER_MS);
        assertEquals(0.026503f, sh.valueAt(0), 0f);
        assertEquals(8310.343667f, sh.valueAt(sh.count() - 1), 0f);
        for (float ms : new float[]{1f, 2f, 4f, 5f, 8f, 10f, 1000f / 60, 1000f / 30}) {
            assertEquals(ms, sh.valueAt(sh.indexOf(ms)), 1e-4f);
        }
        assertEquals("4.99 snaps to 1/200 s", 5f, sh.valueAt(sh.indexOf(4.99121f)), 1e-4f);
        // Values beyond the device maximum (10 s ... 30 s) are dropped.
        assertEquals(8000f, sh.valueAt(sh.count() - 2), 1e-3f);
    }

    @Test
    public void valueListIsStrictlyIncreasingAndRoundTrips() {
        SliderSteps iso = SliderSteps.ofValues(44f, 11377f, CameraValueSteps.ISO);
        for (int i = 1; i < iso.count(); i++) {
            assertEquals("increasing at " + i, true, iso.valueAt(i) > iso.valueAt(i - 1));
            assertEquals("round trip " + i, i, iso.indexOf(iso.valueAt(i)));
        }
    }

    @Test
    public void degenerateRangeHasOnePosition() {
        SliderSteps d = new SliderSteps(1f, 1f, 0.1f);
        assertEquals(1, d.count());
        assertEquals(1f, d.valueAt(0), 0f);
        assertEquals(0, d.indexOf(3f));
    }

    @Test
    public void stepFromGoesToNeighboursOfAnOffStepValue() {
        SliderSteps iso = SliderSteps.ofValues(44, 11377, CameraValueSteps.ISO);
        assertEquals(320f, iso.stepFrom(300, +1), 0f);   // not 400 (snap to 320, then +1)
        assertEquals(250f, iso.stepFrom(300, -1), 0f);
        assertEquals(400f, iso.stepFrom(320, +1), 0f);   // on-step value: plain neighbours
        assertEquals(250f, iso.stepFrom(320, -1), 0f);
        assertEquals(44f, iso.stepFrom(44, -1), 0f);     // clamps at the ends
        assertEquals(11377f, iso.stepFrom(11377, +1), 0f);
    }
}
