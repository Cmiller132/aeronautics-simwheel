package dev.aeronauticssimwheel.ffb;

/**
 * Sums the feel components in Nm and applies the soft knee (DESIGN.md §6.5).
 * τ_out = softknee(τ_telemetry + τ_spring + τ_damper + τ_friction + τ_effects).
 * Working in Nm end-to-end keeps per-craft tuning portable across wheelbases.
 */
public final class Mixer {

    /** Default knee: 65 % of the user torque clamp, 3:1 above it. */
    public static final float DEFAULT_KNEE_FRACTION = 0.65f;
    public static final float DEFAULT_RATIO = 3f;

    private final float kneeNm;
    private final float ratio;

    public Mixer(float userClampNm) {
        this(userClampNm * DEFAULT_KNEE_FRACTION, DEFAULT_RATIO);
    }

    public Mixer(float kneeNm, float ratio) {
        this.kneeNm = kneeNm;
        this.ratio = ratio;
    }

    public float mix(float telemetryNm, float springNm, float damperNm,
                     float frictionNm, float effectsNm) {
        float sum = telemetryNm + springNm + damperNm + frictionNm + effectsNm;
        return SoftKnee.apply(sum, kneeNm, ratio);
    }
}
