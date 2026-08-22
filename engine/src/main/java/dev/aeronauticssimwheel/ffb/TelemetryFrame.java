package dev.aeronauticssimwheel.ffb;

/**
 * One telemetry sample as it crosses the wire (DESIGN.md §6.3, v2): the
 * server's per-substep component frame instead of a pre-mixed torque scalar.
 * Composition — understeer shaping, gains, speed scaling, synthesis — happens
 * client-side in {@link FfbPipeline} where it is hot-reload tunable.
 *
 * <p>Channels 0–1 are torque (Nm at the column, gain/fade/ramp apply);
 * channels 2–5 are context (held, never faded — the pipeline gates their
 * consumers on telemetry freshness instead).
 *
 * @param satNm     self-aligning torque at the reference trail (Nm, signed,
 *                  + = clockwise), before the client's understeer collapse
 * @param textureNm differential suspension texture (Nm, signed by side:
 *                  left-side events negative)
 * @param speedMS   craft speed (m/s, ≥ 0)
 * @param slip      steered-axle slip proxy |v_side| / max(|v_forward|, ε),
 *                  dimensionless; drives the understeer trail collapse
 * @param mu        most slippery steered-contact friction (ice 0.1 … 1.0)
 * @param driveRpm  fastest wheel-mount kinetic speed (|RPM|), for the
 *                  drivetrain rumble synth
 */
public record TelemetryFrame(float satNm, float textureNm, float speedMS,
                             float slip, float mu, float driveRpm) {

    public static final TelemetryFrame ZERO = new TelemetryFrame(0f, 0f, 0f, 0f, 1f, 0f);

    /** Channel count and indices — the buffer and codecs share this layout. */
    public static final int CHANNELS = 6;
    public static final int CH_SAT = 0;
    public static final int CH_TEXTURE = 1;
    public static final int CH_SPEED = 2;
    public static final int CH_SLIP = 3;
    public static final int CH_MU = 4;
    public static final int CH_RPM = 5;
    /** Channels 0..TORQUE_CHANNELS-1 fade/ramp; the rest hold (context). */
    public static final int TORQUE_CHANNELS = 2;

    // Ingress hygiene (hostile-server bounds; the SafetyChain still follows).
    public static final float MAX_SAT_NM = 50f;
    public static final float MAX_TEXTURE_NM = 20f;
    public static final float MAX_SPEED_MS = 150f;
    public static final float MAX_SLIP = 10f;
    public static final float MAX_MU = 2f;
    public static final float MAX_RPM = 4096f;

    /** Clamped copy; non-finite fields collapse to their neutral value. */
    public TelemetryFrame sanitizedForIngress() {
        return new TelemetryFrame(
                clamp(satNm, -MAX_SAT_NM, MAX_SAT_NM, 0f),
                clamp(textureNm, -MAX_TEXTURE_NM, MAX_TEXTURE_NM, 0f),
                clamp(speedMS, 0f, MAX_SPEED_MS, 0f),
                clamp(slip, 0f, MAX_SLIP, 0f),
                clamp(mu, 0f, MAX_MU, 1f),
                clamp(driveRpm, 0f, MAX_RPM, 0f));
    }

    public void toArray(float[] out) {
        out[CH_SAT] = satNm;
        out[CH_TEXTURE] = textureNm;
        out[CH_SPEED] = speedMS;
        out[CH_SLIP] = slip;
        out[CH_MU] = mu;
        out[CH_RPM] = driveRpm;
    }

    public static TelemetryFrame fromArray(float[] v) {
        return new TelemetryFrame(v[CH_SAT], v[CH_TEXTURE], v[CH_SPEED],
                v[CH_SLIP], v[CH_MU], v[CH_RPM]);
    }

    private static float clamp(float v, float lo, float hi, float fallback) {
        return Float.isFinite(v) ? Math.clamp(v, lo, hi) : fallback;
    }
}
