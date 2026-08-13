package dev.aeronauticssimwheel.hal;

/**
 * Per-binding axis processing (DESIGN.md §5.1): calibration (min/center/max),
 * deadzone, expo curve, inversion, one-pole smoothing. Stateless except for the
 * smoothing filter; one instance per logical axis binding.
 */
public final class AxisProcessor {

    /**
     * @param min         raw value at full negative deflection
     * @param center      raw value at rest
     * @param max         raw value at full positive deflection
     * @param deadzone    0..1 fraction of normalized range zeroed around center
     * @param expo        0..1 curve strength; 0 = linear, 1 = full cubic
     * @param invert      flip sign after shaping
     * @param smoothingHz one-pole low-pass cutoff; <= 0 disables (default for steering)
     */
    public record Config(float min, float center, float max,
                         float deadzone, float expo, boolean invert, float smoothingHz) {
        public static Config identity() {
            return new Config(-1f, 0f, 1f, 0f, 0f, false, 0f);
        }
    }

    private final Config cfg;
    private float smoothed;
    private boolean primed;

    public AxisProcessor(Config cfg) {
        if (cfg.max() <= cfg.center() || cfg.center() <= cfg.min()) {
            throw new IllegalArgumentException("calibration must satisfy min < center < max");
        }
        this.cfg = cfg;
    }

    /** Process one raw sample. dt only matters when smoothing is enabled. */
    public float process(float raw, double dtSeconds) {
        // Calibration: piecewise-linear map to -1..1 around center
        float x = raw >= cfg.center()
                ? (raw - cfg.center()) / (cfg.max() - cfg.center())
                : (raw - cfg.center()) / (cfg.center() - cfg.min());
        x = clamp(x);

        // Deadzone (rescaled so the usable range stays -1..1)
        float dz = cfg.deadzone();
        if (dz > 0f) {
            float a = Math.abs(x);
            x = a <= dz ? 0f : Math.copySign((a - dz) / (1f - dz), x);
        }

        // Expo curve: y = (1-e)·x + e·x³ (endpoints preserved)
        float e = cfg.expo();
        if (e > 0f) {
            x = (1f - e) * x + e * x * x * x;
        }

        if (cfg.invert()) {
            x = -x;
        }

        if (cfg.smoothingHz() > 0f) {
            if (!primed) {
                smoothed = x;
                primed = true;
            } else {
                float alpha = (float) (1.0 - Math.exp(-2.0 * Math.PI * cfg.smoothingHz() * dtSeconds));
                smoothed += alpha * (x - smoothed);
            }
            x = smoothed;
        }
        return x;
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
