package se.lth.math.videoimucapture;

/**
 * The positions of a float slider: min, then every multiple of the resolution strictly between
 * min and max, then max.
 *
 * WHY. FloatSeekBarPreference used to step as min + n*resolution. With the Pixel 6 zoom range
 * starting at 0.6704 that gave 0.67, 0.77, ... 0.97, 1.07: exactly 1.0 could never be selected
 * again once the slider had moved, and the max (7.0) was cut off by integer truncation of the
 * step count. Combined with a one-decimal display, 0.97 read as "1". Anchoring the steps to
 * multiples of the resolution keeps the round values (0.7, 0.8, 0.9, 1.0) and both endpoints.
 */
final class SliderSteps {

    private static final double EPS = 1e-4;   // in units of the resolution

    final float min;
    final float max;
    final float resolution;
    private final long firstK;   // first multiple index strictly above min
    private final int interior;  // number of multiples strictly between min and max

    SliderSteps(float min, float max, float resolution) {
        if (!(resolution > 0f)) {
            throw new IllegalArgumentException("resolution must be > 0");
        }
        this.min = min;
        this.max = Math.max(min, max);
        this.resolution = resolution;
        long kFirst = (long) Math.floor(min / (double) resolution + EPS) + 1;
        long kLast = (long) Math.ceil(this.max / (double) resolution - EPS) - 1;
        this.firstK = kFirst;
        this.interior = (int) Math.max(0, kLast - kFirst + 1);
    }

    /** Number of slider positions (SeekBar max = count() - 1). */
    int count() {
        return max > min ? interior + 2 : 1;
    }

    float valueAt(int index) {
        if (index <= 0) {
            return min;
        }
        if (index >= count() - 1) {
            return max;
        }
        return (float) ((firstK + index - 1) * (double) resolution);
    }

    /** The position closest to value (an off-grid stored value maps to its nearest step). */
    int indexOf(float value) {
        int last = count() - 1;
        if (value <= min) {
            return 0;
        }
        if (value >= max) {
            return last;
        }
        int best = 0;
        double bestDiff = Math.abs(value - min);
        // Nearest interior step by rounding, then compare with the two endpoints.
        if (interior > 0) {
            long k = Math.round(value / (double) resolution);
            k = Math.max(firstK, Math.min(firstK + interior - 1, k));
            int i = (int) (k - firstK + 1);
            double d = Math.abs(value - valueAt(i));
            if (d < bestDiff) {
                best = i;
                bestDiff = d;
            }
        }
        if (Math.abs(value - max) < bestDiff) {
            best = last;
        }
        return best;
    }
}
