package dev.aeronauticssimwheel.ffb;

/**
 * Server-side transient detector (DESIGN.md §6.3, Phase 2a): suspension
 * compression spikes (curb strikes, hard landings) become immediate
 * {@code FfbEventPacket}s instead of riding the 20 Hz telemetry batch. The
 * client renders them via {@link EventImpulses} — strictly local synthesis
 * triggers, never sustained torque.
 *
 * <p>Detection: the fastest compressing steered mount this substep. A strike
 * fires when compression rate exceeds the threshold, with hysteresis (must
 * fall below half threshold before re-arming) and a hard minimum interval so
 * a burst can never flood the wire.
 */
public final class StrikeDetector {

    /**
     * @param thresholdMS   compression rate (m/s, positive = compressing) that
     *                      fires a strike
     * @param peakNmPerMS   event peak torque per m/s of compression rate above
     *                      zero (clamped to maxPeakNm)
     * @param maxPeakNm     peak amplitude cap — a cliff fall is not a wrist
     *                      snap; the SafetyChain clamps again downstream
     * @param minIntervalS  minimum seconds between fired events
     */
    public record Config(double thresholdMS, double peakNmPerMS,
                         double maxPeakNm, double minIntervalS) {
        public static Config defaults() {
            return new Config(1.2, 0.9, 3.0, 0.05);
        }
    }

    /** One fired strike, ready to become an event packet. Peak is signed by
     *  the originating side (left-side strikes negative = counterclockwise). */
    public record Strike(float peakNm, float tauSeconds) {
    }

    private final Config cfg;
    private boolean armed = true;
    private double sinceLastFire = Double.MAX_VALUE;

    public StrikeDetector(Config cfg) {
        this.cfg = cfg;
    }

    /**
     * One substep.
     *
     * @param compressionRateMS fastest steered-mount compression this substep
     *                          (m/s, positive = compressing; 0 when airborne/idle)
     * @param sideSign          that mount's side of the craft centerline
     *                          (−1 left / +1 right / 0 centerline) — signs the
     *                          rendered impulse so seams kick toward their side
     *                          instead of always clockwise
     * @return a strike to send, or null
     */
    public Strike step(double compressionRateMS, double sideSign, double dtSeconds) {
        sinceLastFire = Math.min(sinceLastFire + dtSeconds, Double.MAX_VALUE);

        if (!armed && compressionRateMS < cfg.thresholdMS() / 2) {
            armed = true;
        }
        if (!armed || compressionRateMS < cfg.thresholdMS()
                || sinceLastFire < cfg.minIntervalS() || !Double.isFinite(compressionRateMS)) {
            return null;
        }
        armed = false;
        sinceLastFire = 0;
        float peak = (float) Math.min(compressionRateMS * cfg.peakNmPerMS(), cfg.maxPeakNm());
        // A centerline mount (sideSign 0) still strikes — alternate is worse
        // than a fixed sign there; use positive.
        float sign = sideSign < 0 ? -1f : 1f;
        return new Strike(sign * peak, (float) EventImpulses.DEFAULT_TAU_S);
    }

    public void reset() {
        armed = true;
        sinceLastFire = Double.MAX_VALUE;
    }
}
