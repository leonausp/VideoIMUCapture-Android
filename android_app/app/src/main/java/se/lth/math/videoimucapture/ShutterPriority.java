package se.lth.math.videoimucapture;

/**
 * Shutter priority: a fixed (maximum) exposure time chosen for motion blur, with the ISO derived
 * from what auto exposure metered. Auto exposure runs during the preview purely as a light meter;
 * its exposure x ISO is the scene's "light value", and the same brightness at the chosen exposure
 * time needs ISO = light / exposure.
 *
 * Edge cases, so a fixed setting never silently ruins a recording:
 *  - too bright: even the minimum ISO would over-expose at the chosen time -> keep the minimum
 *    ISO and SHORTEN the exposure instead (less blur, never more);
 *  - too dark: even the maximum ISO is not enough -> maximum ISO at the chosen time, and the
 *    result says by how many stops the frames will be darker than metered.
 * Pure arithmetic, testable on the host.
 */
final class ShutterPriority {

    static final class Result {
        final long exposureNs;
        final int iso;
        /** Stops darker than metered (> 0 only when even the maximum ISO was not enough). */
        final double underexposedStops;
        /** True when the exposure had to be shortened below the chosen time (bright scene). */
        final boolean shortened;

        Result(long exposureNs, int iso, double underexposedStops, boolean shortened) {
            this.exposureNs = exposureNs;
            this.iso = iso;
            this.underexposedStops = underexposedStops;
            this.shortened = shortened;
        }
    }

    private ShutterPriority() {}

    /**
     * @param meteredExposureNs auto exposure's exposure time
     * @param meteredIso        auto exposure's ISO
     * @param targetExposureNs  the chosen (maximum) exposure time
     * @return null if the metered values are unusable
     */
    static Result compute(long meteredExposureNs, int meteredIso, long targetExposureNs,
                          int isoMin, int isoMax, long exposureMinNs) {
        if (meteredExposureNs <= 0 || meteredIso <= 0 || targetExposureNs <= 0
                || isoMin <= 0 || isoMax < isoMin) {
            return null;
        }
        double light = (double) meteredExposureNs * meteredIso;
        double iso = light / targetExposureNs;
        if (iso < isoMin) {
            long exposure = Math.max(exposureMinNs, Math.round(light / isoMin));
            return new Result(Math.min(exposure, targetExposureNs), isoMin, 0.0, true);
        }
        if (iso > isoMax) {
            double stops = Math.log(iso / isoMax) / Math.log(2);
            return new Result(targetExposureNs, isoMax, stops, false);
        }
        return new Result(targetExposureNs, (int) Math.round(iso), 0.0, false);
    }
}
