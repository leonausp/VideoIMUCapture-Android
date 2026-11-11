package se.lth.math.videoimucapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Shutter priority with the Pixel 6 ranges (ISO 44..11377, exposure from 26.5 us). */
public class ShutterPriorityTest {

    private static final long MS = 1_000_000L;

    @Test
    public void indoorSceneKeepsTheShutterAndRaisesIso() {
        // The first Pixel 6 test: auto exposure chose 33.33 ms at ISO 711. At 5 ms that is ~4740.
        ShutterPriority.Result r = ShutterPriority.compute(33_330_700L, 711, 5 * MS, 44, 11377, 26_503L);
        assertEquals(5 * MS, r.exposureNs);
        assertEquals(4740, r.iso, 2);
        assertEquals(0.0, r.underexposedStops, 0.0);
        assertFalse(r.shortened);
    }

    @Test
    public void brightSceneShortensTheExposureInsteadOfOverexposing() {
        // Outdoors: 1 ms at ISO 50 metered -> at 5 ms it would need ISO 10 < 44.
        ShutterPriority.Result r = ShutterPriority.compute(1 * MS, 50, 5 * MS, 44, 11377, 26_503L);
        assertEquals(44, r.iso);
        assertEquals(Math.round(1e6 * 50 / 44.0), r.exposureNs);   // same light at ISO 44
        assertTrue(r.shortened);
    }

    @Test
    public void darkSceneSaysHowManyStopsAreMissing() {
        // 33 ms at ISO 11377 metered -> at 5 ms it would need ~75000: 2.7 stops short.
        ShutterPriority.Result r = ShutterPriority.compute(33 * MS, 11377, 5 * MS, 44, 11377, 26_503L);
        assertEquals(11377, r.iso);
        assertEquals(5 * MS, r.exposureNs);
        assertEquals(Math.log(33.0 / 5.0) / Math.log(2), r.underexposedStops, 1e-9);
    }

    @Test
    public void unusableMeteringGivesNoResult() {
        assertNull(ShutterPriority.compute(0, 100, 5 * MS, 44, 11377, 26_503L));
        assertNull(ShutterPriority.compute(5 * MS, 0, 5 * MS, 44, 11377, 26_503L));
        assertNull(ShutterPriority.compute(5 * MS, 100, 0, 44, 11377, 26_503L));
    }
}
