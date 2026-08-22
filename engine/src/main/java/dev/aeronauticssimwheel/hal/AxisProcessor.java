package dev.aeronauticssimwheel.hal;

/**
 * Per-binding axis processing (DESIGN.md §5.1): calibration (min/center/max),
 * deadzone, expo curve, inversion, one-pole smoothing. Stateless except for the
 * smoothing filter; one instance per logical axis binding.
 *
 * <p>Two shapes:
 * <ul>
 *   <li><b>Bipolar</b> (steering): raw maps to −1…1 around {@code center};
 *       deadzone sits at center.</li>
 *   <li><b>Pedal</b>: raw maps to 0…1 across {@code min}…{@code max} — a pedal
 *       resting at raw −1 reads 0, full travel reads 1 (the old bipolar+clamp
 *       path silently discarded half the physical travel). Deadzone sits at
 *       the BOTTOM (rest), where pedal noise actually lives; {@code invert}
 *       flips rest/full for pedals that idle high.</li>
 * </ul>
 */
public final class AxisProcessor {

    /**
     * @param min         raw value at full negative deflection (pedal: at rest)
     * @param center      raw value at rest (bipolar only; ignored for pedals)
     * @param max         raw value at full positive deflection (pedal: full press)
     * @param deadzone    0..1 fraction zeroed — around center (bipolar) or at
     *                    the bottom of travel (pedal)
     * @param expo        0..1 curve strength; 0 = linear, 1 = full cubic
     * @param invert      flip sign (bipolar) / flip rest and full (pedal)
     * @param smoothingHz one-pole low-pass cutoff; <= 0 disables (default for steering)
     * @param pedal       unipolar 0..1 output shape (see class doc)
     */
    public record Config(float min, float center, float max,
                         float deadzone, float expo, boolean invert, float smoothingHz,
                         boolean pedal) {
        public Config(float min, float center, float max,
                      float deadzone, float expo, boolean invert, float smoothingHz) {
            this(min, center, max, deadzone, expo, invert, smoothingHz, false);
        }

        public static Config identity() {
            return new Config(-1f, 0f, 1f, 0f, 0f, false, 0f);
        }

        /** Full-travel pedal: raw −1 = rest, +1 = full, bottom deadzone, smoothed. */
        public static Config pedal(float deadzone, boolean invert, float smoothingHz) {
            return new Config(-1f, 0f, 1f, deadzone, 0f, invert, smoothingHz, true);
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
        float x = cfg.pedal() ? shapePedal(raw) : shapeBipolar(raw);

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

    private float shapeBipolar(float raw) {
        // Calibration: piecewise-linear map to -1..1 around center
        float x = raw >= cfg.center()
                ? (raw - cfg.center()) / (cfg.max() - cfg.center())
                : (raw - cfg.center()) / (cfg.center() - cfg.min());
        x = Math.clamp(x, -1f, 1f);

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

        return cfg.invert() ? -x : x;
    }

    private float shapePedal(float raw) {
        // Calibration: full travel min..max maps to 0..1
        float x = Math.clamp((raw - cfg.min()) / (cfg.max() - cfg.min()), 0f, 1f);
        if (cfg.invert()) {
            x = 1f - x;
        }

        // Bottom deadzone: pedal noise lives at rest, not mid-travel
        float dz = cfg.deadzone();
        if (dz > 0f) {
            x = x <= dz ? 0f : (x - dz) / (1f - dz);
        }

        // Expo on 0..1 (endpoints preserved)
        float e = cfg.expo();
        if (e > 0f) {
            x = (1f - e) * x + e * x * x * x;
        }
        return x;
    }
}
