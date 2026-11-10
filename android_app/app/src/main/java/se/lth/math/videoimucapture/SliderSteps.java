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
    // Explicit positions instead of a grid (null in grid mode): sorted, min first, max last.
    private final float[] list;

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
        this.list = null;
    }

    private SliderSteps(float min, float max, float[] list) {
        this.min = min;
        this.max = max;
        this.resolution = 0f;
        this.firstK = 0;
        this.interior = 0;
        this.list = list;
    }

    /**
     * Positions at chosen values -- e.g. ISO or shutter times in 1/3-stop steps -- instead of a
     * fixed grid. Candidates outside (min, max) are dropped; min and max are always the first
     * and last position, so the device's whole range stays reachable.
     */
    static SliderSteps ofValues(float min, float max, float[] candidates) {
        float hi = Math.max(min, max);
        float[] sorted = candidates.clone();
        java.util.Arrays.sort(sorted);
        java.util.ArrayList<Float> out = new java.util.ArrayList<>();
        out.add(min);
        for (float c : sorted) {
            double tol = 1e-4 * Math.max(1.0, Math.abs(c));
            if (c > min + tol && c < hi - tol && c > out.get(out.size() - 1) + tol) {
                out.add(c);
            }
        }
        if (hi > min) {
            out.add(hi);
        }
        float[] arr = new float[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return new SliderSteps(min, hi, arr);
    }

    /** Number of slider positions (SeekBar max = count() - 1). */
    int count() {
        if (list != null) {
            return list.length;
        }
        return max > min ? interior + 2 : 1;
    }

    float valueAt(int index) {
        if (list != null) {
            return list[Math.max(0, Math.min(list.length - 1, index))];
        }
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
        if (list != null) {
            int best = 0;
            for (int i = 1; i < list.length; i++) {
                if (Math.abs(list[i] - value) < Math.abs(list[best] - value)) {
                    best = i;
                }
            }
            return best;
        }
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
