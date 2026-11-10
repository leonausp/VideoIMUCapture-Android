package se.lth.math.videoimucapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The "Maximize Sensor Area" filter and the fallback size choice, checked against the Pixel 6
 * values that exposed the bug (main active array 4080x3072, ultrawide 4032x3016).
 */
public class VideoSizeRulesTest {

    private static final int MAIN_W = 4080, MAIN_H = 3072;   // Pixel 6 main, ratio 1.328
    private static final int UW_W = 4032, UW_H = 3016;       // Pixel 6 ultrawide, ratio 1.337

    @Test
    public void standardFourThreeSizesCountAsFullSensorOnTheMainCamera() {
        // These all failed the old exact integer test (e.g. 960*4080/3072 = 1275 != 1280),
        // which left 4080x3072 as the only "full sensor" size.
        assertTrue(VideoSizeRules.usesFullSensor(1280, 960, MAIN_W, MAIN_H));
        assertTrue(VideoSizeRules.usesFullSensor(1920, 1440, MAIN_W, MAIN_H));
        assertTrue(VideoSizeRules.usesFullSensor(4032, 3024, MAIN_W, MAIN_H));
        assertTrue(VideoSizeRules.usesFullSensor(640, 480, MAIN_W, MAIN_H));
        assertTrue(VideoSizeRules.usesFullSensor(4080, 3072, MAIN_W, MAIN_H));
    }

    @Test
    public void croppedAspectRatiosAreNotFullSensor() {
        assertFalse("16:9 crops the sensor", VideoSizeRules.usesFullSensor(1920, 1080, MAIN_W, MAIN_H));
        assertFalse("16:9 crops the sensor", VideoSizeRules.usesFullSensor(3840, 2160, MAIN_W, MAIN_H));
        assertFalse("square crops the sensor", VideoSizeRules.usesFullSensor(1920, 1920, MAIN_W, MAIN_H));
        assertFalse("portrait-oriented size is not the sensor's landscape ratio",
                VideoSizeRules.usesFullSensor(960, 1280, MAIN_W, MAIN_H));
    }

    @Test
    public void fourThreeIsAlsoFullSensorOnTheUltrawide() {
        assertTrue(VideoSizeRules.usesFullSensor(1280, 960, UW_W, UW_H));
        assertTrue(VideoSizeRules.usesFullSensor(4032, 3024, UW_W, UW_H));
        assertFalse(VideoSizeRules.usesFullSensor(1920, 1920, UW_W, UW_H));
    }

    @Test
    public void degenerateInputsAreRejectedNotDividedByZero() {
        assertFalse(VideoSizeRules.sameAspect(0, 960, MAIN_W, MAIN_H, 0.01));
        assertFalse(VideoSizeRules.sameAspect(1280, 960, 0, 0, 0.01));
        assertFalse(VideoSizeRules.sameAspect(-1280, 960, MAIN_W, MAIN_H, 0.01));
    }

    @Test
    public void closestPrefersTheSameAspectRatioOverASimilarPixelCount() {
        // Request 1600x1200 (1.92 MP, 4:3). By pixel count 1920x1080 (2.07 MP) is nearest, but a
        // 4:3 request should stay 4:3: 1280x960 (1.23 MP) beats 1920x1440 (2.76 MP).
        int[][] candidates = {{1920, 1080}, {1280, 960}, {1920, 1440}};
        assertEquals(1, VideoSizeRules.closest(candidates, 1600, 1200));
    }

    @Test
    public void closestPicksTheNearestPixelCountWithinTheAspectRatio() {
        // A lens that cannot do 4080x3072: fall back to its largest 4:3 size, not to 640x480.
        int[][] candidates = {{640, 480}, {1280, 960}, {4032, 3024}, {1920, 1080}};
        assertEquals(2, VideoSizeRules.closest(candidates, 4080, 3072));
    }

    @Test
    public void closestFallsBackToPixelCountWhenNoAspectMatches() {
        int[][] candidates = {{1920, 1080}, {3840, 2160}};
        assertEquals(0, VideoSizeRules.closest(candidates, 1280, 960));
    }

    @Test
    public void closestOfNothingIsMinusOne() {
        assertEquals(-1, VideoSizeRules.closest(new int[0][], 1280, 960));
        assertEquals(-1, VideoSizeRules.closest(null, 1280, 960));
    }
}
