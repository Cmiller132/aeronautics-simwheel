package dev.aeronauticssimwheel.ffb;

/**
 * Client-side road-texture synthesizer (DESIGN.md §6.5, feel pass): continuous
 * surface character keyed to the telemetry frame's μ and craft speed. Honest
 * inputs, synthesized rendering — the 40 Hz sampled bump path aliases block
 * seams at speed (20 m/s over 1 m blocks = 20 Hz = Nyquist), so continuous
 * texture is synthesized locally at loop rate instead: gravel/mud granular,
 * firm ground a faint hum, ice glassy (silent — its cue is the μ-scaled SAT
 * going light, not noise).
 *
 * <p>Implementation: seedable xorshift noise through a one-pole low-pass whose
 * cutoff rises with speed, so texture "pitch" tracks how fast the ground moves
 * under the tires. Output is amplitude-bounded; the SafetyChain still follows.
 *
 * <p>FFB-thread only (stateful filter + RNG). Deterministic for a fixed seed
 * and step sequence.
 */
public final class SurfaceTexture {

    /** μ at or below this reads as ice: glassy, no texture. */
    public static final double MU_ICE = 0.15;
    /** μ at or below this reads as loose surface (mud, gravel): full texture. */
    public static final double MU_LOOSE = 0.6;
    /** Texture fraction on firm ground (μ above {@link #MU_LOOSE}). */
    public static final double FIRM_FRACTION = 0.12;
    /** Speed (m/s) at which texture reaches full amplitude. */
    public static final double SPEED_REF_MS = 6.0;
    /** Below this speed the tires aren't moving over ground: silence. */
    public static final double SPEED_FLOOR_MS = 0.3;

    private long rng;
    private double lp;

    public SurfaceTexture(long seed) {
        this.rng = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
    }

    /**
     * One FFB step.
     *
     * @param ampNm peak amplitude at full speed on loose ground (0 disables)
     * @return texture torque in Nm, bounded to ±ampNm
     */
    public float step(double dtSeconds, double speedMS, double mu, float ampNm) {
        if (ampNm <= 0 || speedMS < SPEED_FLOOR_MS || mu <= MU_ICE) {
            lp = 0;
            return 0f;
        }
        double surface = mu <= MU_LOOSE ? 1.0 : FIRM_FRACTION;
        double envelope = Math.min(1.0, speedMS / SPEED_REF_MS);

        // White noise ±1 via xorshift64, low-passed with a speed-tracking cutoff.
        rng ^= rng << 13;
        rng ^= rng >>> 7;
        rng ^= rng << 17;
        double white = (rng >> 11) / (double) (1L << 52); // ±1
        double cutoffHz = Math.min(40.0, 3.0 + speedMS * 1.5);
        double alpha = 1.0 - Math.exp(-2.0 * Math.PI * cutoffHz * dtSeconds);
        lp += alpha * (white - lp);

        // The one-pole attenuates noise RMS; ×3 restores a usable level.
        double out = lp * 3.0 * ampNm * surface * envelope;
        return (float) Math.clamp(out, -ampNm, ampNm);
    }

    /** Rig teardown: silence and forget filter state. */
    public void reset() {
        lp = 0;
    }
}
