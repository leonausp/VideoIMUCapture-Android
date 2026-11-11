package se.lth.math.videoimucapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The defaults are the settings the VIO test recordings were made with, and settings.xml
 * declares the same ones as RecommendedDefaults (the code's fallbacks).
 */
public class RecommendedDefaultsTest {

    @Test
    public void defaultsAreTheRecordingSettings() {
        // Read from the Pixel 6 on 2026-11-11 (shared_prefs of the app used for the recordings).
        assertEquals("Logical", RecommendedDefaults.CAMERA_ID);
        assertEquals("none", RecommendedDefaults.LENS_SET);
        assertEquals("1280x960", RecommendedDefaults.VIDEO_SIZE);
        assertTrue(RecommendedDefaults.USE_FULL_SENSOR);
        assertEquals(1.0f, RecommendedDefaults.ZOOM_RATIO, 0f);
        // Deliberate difference: the recordings used MANUAL with ISO 1000 (right for that room
        // only); the default keeps their 5 ms but meters ISO per scene (shutter priority).
        assertEquals("SHUTTER_PRIORITY", RecommendedDefaults.EXPOSURE_MODE);
        assertEquals(5.0f, RecommendedDefaults.EXPOSURE_MS, 0f);
        assertEquals(1000, RecommendedDefaults.ISO);
        assertEquals("MANUAL", RecommendedDefaults.FOCUS_MODE);
        assertTrue(RecommendedDefaults.FOCUS_HYPERFOCAL);
        assertFalse(RecommendedDefaults.OIS);
        assertTrue(RecommendedDefaults.OIS_DATA);
        assertFalse(RecommendedDefaults.DVS);
        assertFalse(RecommendedDefaults.DISTORTION_CORRECTION);
        assertFalse(RecommendedDefaults.RAW_PIXELS);
        assertTrue(RecommendedDefaults.LOCK_RADIOMETRY);
        assertFalse(RecommendedDefaults.BLUR_BUDGET);
        assertFalse(RecommendedDefaults.BLUR_BUDGET_MANUAL);
        assertEquals(3, RecommendedDefaults.BLUR_BUDGET_PX);
        assertEquals(40, RecommendedDefaults.HOLD_STILL_PX);
        assertEquals("video/avc", RecommendedDefaults.VIDEO_CODEC);
        assertEquals(0, RecommendedDefaults.VIDEO_BITRATE_MBPS);
        assertEquals(0, RecommendedDefaults.IMU_BATCH_MS);
        assertEquals(60, RecommendedDefaults.IDLE_SLEEP_S);
        assertEquals(0, RecommendedDefaults.STEREO_INTERVAL_S);
    }

    @Test
    public void settingsXmlDeclaresTheSameDefaults() throws Exception {
        File xml = new File("src/main/res/xml/settings.xml");   // unit tests run in the module dir
        assertTrue("settings.xml not found from " + new File(".").getAbsolutePath(), xml.exists());
        NodeList all = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
                .getElementsByTagName("*");
        List<String> checked = new ArrayList<>();
        for (int i = 0; i < all.getLength(); i++) {
            Element el = (Element) all.item(i);
            String key = el.getAttribute("app:key");
            String def = el.getAttribute("app:defaultValue");
            if (key.isEmpty() || def.isEmpty()) {
                continue;
            }
            Field f;
            try {
                f = RecommendedDefaults.class.getDeclaredField(key.toUpperCase(Locale.US));
            } catch (NoSuchFieldException e) {
                throw new AssertionError("settings.xml gives \"" + key + "\" a default but "
                        + "RecommendedDefaults has no " + key.toUpperCase(Locale.US));
            }
            f.setAccessible(true);
            assertEquals("default of " + key, String.valueOf(f.get(null)), def);
            checked.add(key);
        }
        assertTrue("expected the XML-only settings to be checked, got " + checked,
                checked.contains("focus_hyperfocal") && checked.contains("lens_set")
                        && checked.contains("lock_radiometry") && checked.contains("show_expert_settings"));
    }
}
