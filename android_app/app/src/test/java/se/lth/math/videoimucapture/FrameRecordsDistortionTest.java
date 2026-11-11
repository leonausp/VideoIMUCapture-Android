package se.lth.math.videoimucapture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Which LENS_DISTORTION counts as "reported": the Pixel 6's logical camera gives all zeros. */
public class FrameRecordsDistortionTest {

    @Test
    public void zerosAndNullAreNoDistortion() {
        assertFalse(FrameRecords.hasDistortion(null));
        assertFalse(FrameRecords.hasDistortion(new float[]{0f, 0f, 0f, 0f, 0f}));
    }

    @Test
    public void anyNonZeroTermIsDistortion() {
        // Pixel 6 main lens (camera 2): k1, k2, k3, p1, p2
        assertTrue(FrameRecords.hasDistortion(new float[]{0.1281f, -0.1977f, 0.1214f, 0f, 0f}));
        // The old check looked at k1 only; a lens with k1 == 0 but other terms is still distorted.
        assertTrue(FrameRecords.hasDistortion(new float[]{0f, 0.01f, 0f, 0f, 0f}));
    }
}
