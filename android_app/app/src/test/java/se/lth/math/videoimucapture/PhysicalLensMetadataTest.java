package se.lth.math.videoimucapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * The two host-checkable parts of fix/physical-camera, against the Pixel 6 recordings of
 * 2026-11-10 that exposed them.
 */
public class PhysicalLensMetadataTest {

    // Pixel 6 main lens: 6.81 mm, 4080 px across 9.792 mm, focus at 0.308 diopters.
    private static final float MAIN_IMAGE_DISTANCE_MM = 1000f / (1000f / 6.81f - 0.308121f);
    private static final float MAIN_PX_PER_MM = 4080f / 9.792f;

    @Test
    public void zoomOneFullCropMatchesTheRecordedEstimate() {
        // 22:41:38, logical camera, zoom 1.0, crop = full 4080 array, 1280 px long side:
        // the file recorded est_focal_length_pix = 892.068.
        float f = FocalLengthHelper.focalPixels(MAIN_IMAGE_DISTANCE_MM, MAIN_PX_PER_MM,
                1280f / 4080f, 1.0f);
        assertEquals(892.07f, f, 0.1f);
    }

    @Test
    public void zoomRatioCancelsTheZoomRelativeCrop() {
        // 22:46:25, logical camera, zoom 0.6704426, result crop 2735 px wide. The old estimate
        // (crop alone) recorded 1330.76 px; the frames measured as the main lens at native
        // field of view (scale 1.04 against 22:41:38). With the zoom ratio: ~892 px again.
        float old = FocalLengthHelper.focalPixels(MAIN_IMAGE_DISTANCE_MM, MAIN_PX_PER_MM,
                1280f / 2735f, null);
        assertEquals("reproduces the old, wrong value", 1330.76f, old, 0.2f);
        float fixed = FocalLengthHelper.focalPixels(MAIN_IMAGE_DISTANCE_MM, MAIN_PX_PER_MM,
                1280f / 2735f, 0.6704426f);
        assertEquals(892.2f, fixed, 0.5f);
    }

    @Test
    public void logicalCameraSwitchedToUltrawideUsesTheLogicalFocalLength() {
        // 23:09:31: logical camera, zoom 0.6704, active lens = ultrawide, focus 0.325 dpt, crop
        // (1,10)-(4080,3062) in logical coordinates. Using the active lens's 2.35 mm gave 207 px;
        // the logical camera's 6.81 mm gives ~598 px, the ultrawide's real fx at 960x1280
        // (factory 1886.5 x 1280/4032 = 598.9).
        float imageDistance = 1000f / (1000f / 6.81f - 0.325251f);
        float f = FocalLengthHelper.focalPixels(imageDistance, MAIN_PX_PER_MM,
                1280f / 4079f, 0.6704426f);
        assertEquals(598.9f, f, 2f);
    }

    @Test
    public void perFrameHalFocalInVideoPixels() {
        // Main lens, 22:41:38: per-frame fx 2853.06 array px, full 4080 crop, zoom 1, 1280 px.
        assertEquals(895.1f, FocalLengthHelper.arrayToVideoPixels(2853.06f, 1280f / 4080f), 0.2f);
        // Ultrawide pinned, 23:08:32: fx 1886.48 array px, crop 4031 wide, 1280 px -> ~599,
        // matching the estimate (598.4) and the factory value scaled to the video (598.9).
        assertEquals(599.0f, FocalLengthHelper.arrayToVideoPixels(1886.48f, 1280f / 4031f), 0.3f);
        assertTrue(Float.isNaN(FocalLengthHelper.arrayToVideoPixels(2853f, Float.NaN)));
        assertTrue(Float.isNaN(FocalLengthHelper.arrayToVideoPixels(0f, 0.3f)));
    }

    @Test
    public void missingOrInvalidZoomMeansOne() {
        float a = FocalLengthHelper.focalPixels(6.8f, 400f, 0.5f, null);
        assertEquals(a, FocalLengthHelper.focalPixels(6.8f, 400f, 0.5f, 0f), 1e-3f);
        assertEquals(a, FocalLengthHelper.focalPixels(6.8f, 400f, 0.5f, -2f), 1e-3f);
        assertEquals(a, FocalLengthHelper.focalPixels(6.8f, 400f, 0.5f, 1f), 1e-3f);
    }

    private static SessionManifest.LensSummary summary(String requested, List<String> metadataFrom,
                                                       List<String> active, float min, float max,
                                                       float lensFocal, int rows) {
        return new SessionManifest.LensSummary(requested, "0", false, metadataFrom, active,
                min, max, lensFocal, rows);
    }

    @Test
    public void pinnedLensWhoseOwnResultsFilledEveryRowMatches() {
        assertTrue(summary("3", Collections.singletonList("3"), Collections.emptyList(),
                2.35f, 2.35f, 2.35f, 61).metadataMatchesVideoLens());
    }

    @Test
    public void pinnedLensFallingBackToLogicalResultsDoesNotMatch() {
        // The bug this branch fixes: video pinned to 3, rows filled from the logical camera.
        assertFalse(summary("3", Collections.singletonList("Logical"), Collections.emptyList(),
                6.81f, 6.81f, 2.35f, 61).metadataMatchesVideoLens());
        assertFalse("some rows from each",
                summary("3", Arrays.asList("3", "Logical"), Collections.emptyList(),
                        2.35f, 6.81f, 2.35f, 61).metadataMatchesVideoLens());
    }

    @Test
    public void logicalCameraOnItsOwnLensMatches() {
        assertTrue(summary("Logical", Collections.singletonList("Logical"),
                Collections.singletonList("2"), 6.81f, 6.81f, 6.81f, 86).metadataMatchesVideoLens());
    }

    @Test
    public void logicalCameraThatSwitchedLensDoesNotMatch() {
        // Zoom < 1 moved the logical camera to the ultrawide: per-frame focal says 2.35 mm while
        // the static intrinsics describe the 6.81 mm main lens.
        assertFalse(summary("Logical", Collections.singletonList("Logical"),
                Collections.singletonList("3"), 2.35f, 2.35f, 6.81f, 95).metadataMatchesVideoLens());
        assertFalse("switched mid-clip",
                summary("Logical", Collections.singletonList("Logical"), Arrays.asList("2", "3"),
                        2.35f, 6.81f, 6.81f, 95).metadataMatchesVideoLens());
    }

    @Test
    public void nothingRecordedIsNoVerdict() {
        assertNull(summary("3", Collections.emptyList(), Collections.emptyList(),
                Float.NaN, Float.NaN, 2.35f, 0).metadataMatchesVideoLens());
    }
}
