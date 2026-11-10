package se.lth.math.videoimucapture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Method;

import org.junit.Test;

/**
 * Regression guard for the ISO slider that showed only 0 and 1. SeekBarPreference has FINAL
 * setMin(int)/setMax(int)/setValue(int); a float overload of the same name lets an Integer
 * argument (an ISO Range<Integer>) silently bind to the int version, leaving this slider at
 * 0..1. So this class must not declare setMin/setMax/setValue at all, and offers setRange()
 * and setNumber() instead. (The preference needs a Context, so this checks declarations.)
 */
public class FloatSeekBarPreferenceOverloadTest {

    @Test
    public void noLookAlikeOverloadsOfTheFinalIntSetters() {
        for (Method m : FloatSeekBarPreference.class.getDeclaredMethods()) {
            String n = m.getName();
            assertFalse("FloatSeekBarPreference must not declare " + n + "(): it shadows "
                            + "SeekBarPreference's final int version for Integer arguments",
                    n.equals("setMax") || n.equals("setMin") || n.equals("setValue"));
        }
    }

    @Test
    public void distinctSettersExist() throws Exception {
        assertNotNull(FloatSeekBarPreference.class.getDeclaredMethod("setRange", float.class, float.class));
        assertNotNull(FloatSeekBarPreference.class.getDeclaredMethod("setNumber", float.class));
    }
}
