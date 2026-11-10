package se.lth.math.videoimucapture;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.Test;

/** The Recordings list must show every session's mode; VIDEO sessions were once hidden. */
public class RollGroupOrderTest {

    @Test
    public void videoSessionsGetAGroupAndComeFirst() {
        assertEquals(Arrays.asList("VIDEO", "WALK"),
                RollActivity.groupOrder(new HashSet<>(Arrays.asList("WALK", "VIDEO"))));
    }

    @Test
    public void unknownModesAreShownNotDropped() {
        assertEquals(Arrays.asList("VIDEO", "OTHER", "ZOOMED"),
                RollActivity.groupOrder(new HashSet<>(Arrays.asList("ZOOMED", "OTHER", "VIDEO"))));
    }

    @Test
    public void nothingPresentNothingListed() {
        assertEquals(0, RollActivity.groupOrder(new HashSet<>()).size());
    }
}
