package dev.aeronauticssimwheel.ffb;

/**
 * Sums the feel components in Nm, applies the soft knee to the feel content,
 * then adds the soft-lock torque OUTSIDE the knee (DESIGN.md §6.5):
 * τ_out = softknee(τ_feel) + τ_lock. The knee's job is keeping heavily loaded
 * cornering proportional near the limit; an end stop should be a wall, not
 * compressed 3:1 — the SafetyChain's hard clamp still bounds the total.
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

    /**
     * @param feelNm summed feel content (telemetry, texture, synths, damper,
     *               friction, impulses) — knee-compressed
     * @param lockNm soft-lock end-stop torque — bypasses the knee
     */
    public float mix(float feelNm, float lockNm) {
        return SoftKnee.apply(feelNm, kneeNm, ratio) + lockNm;
    }
}
