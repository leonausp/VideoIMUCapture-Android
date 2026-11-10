package se.lth.math.videoimucapture;

/**
 * The size arithmetic behind the "Video Size" and "Maximize Sensor Area" settings, on plain ints
 * so it can be unit-tested on the host (android.util.Size is a stub there).
 *
 * WHY A TOLERANCE. "Uses the full sensor" used to mean "width == height * arrayW / arrayH" in
 * integer arithmetic. Sensors are rarely exactly 4:3: the Pixel 6 main camera's active array is
 * 4080x3072 (1.328), so 1280x960, 1920x1440 and 4032x3024 (all 1.333) failed the test and the
 * only "full sensor" size left was 4080x3072 itself. A standard 4:3 output from a 1.328 array
 * crops about 0.4% of one dimension; that is still the full sensor for every practical purpose.
 */
final class VideoSizeRules {

    /** Relative aspect-ratio difference still treated as "the sensor's aspect ratio". */
    static final double SENSOR_ASPECT_TOLERANCE = 0.01;

    private VideoSizeRules() {}

    /** True if w:h matches refW:refH within {@code tolerance} (relative). Orientation-sensitive. */
    static boolean sameAspect(int w, int h, int refW, int refH, double tolerance) {
        if (w <= 0 || h <= 0 || refW <= 0 || refH <= 0) {
            return false;
        }
        double ratio = ((double) w / h) / ((double) refW / refH);
        return Math.abs(ratio - 1.0) <= tolerance;
    }

    static boolean usesFullSensor(int w, int h, int arrayW, int arrayH) {
        return sameAspect(w, h, arrayW, arrayH, SENSOR_ASPECT_TOLERANCE);
    }

    /**
     * Index of the candidate closest to (w, h): among candidates with the same aspect ratio as
     * (w, h) the one with the nearest pixel count; if none shares the aspect ratio, the nearest
     * pixel count overall. -1 for an empty list. Used only when the stored size cannot be
     * produced by the camera at all (e.g. after switching to a lens with a smaller sensor).
     */
    static int closest(int[][] candidates, int w, int h) {
        if (candidates == null || candidates.length == 0) {
            return -1;
        }
        long target = (long) w * h;
        int best = -1;
        long bestDiff = Long.MAX_VALUE;
        boolean bestSameAspect = false;
        for (int i = 0; i < candidates.length; i++) {
            int cw = candidates[i][0], ch = candidates[i][1];
            boolean same = sameAspect(cw, ch, w, h, SENSOR_ASPECT_TOLERANCE);
            long diff = Math.abs((long) cw * ch - target);
            if ((same && !bestSameAspect) || (same == bestSameAspect && diff < bestDiff)) {
                best = i;
                bestDiff = diff;
                bestSameAspect = same;
            }
        }
        return best;
    }
}
