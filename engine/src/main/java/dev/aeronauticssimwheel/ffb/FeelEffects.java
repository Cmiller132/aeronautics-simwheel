package dev.aeronauticssimwheel.ffb;

/**
 * Small, stateless local feel components (DESIGN.md §6.5). All outputs in Nm.
 * Buffet/rumble synthesizers (stateful noise) come with Phase 3.
 */
public final class FeelEffects {

    private FeelEffects() {
    }

    /** Damper scaled by dynamic pressure: τ = −c_d(q)·θ̇. */
    public static float damper(float cAtQRefNmPerDegPerS, double dynamicPressurePa,
                               double qRefPa, double velDegPerS) {
        double scale = Math.min(1.0, Math.max(0.0, dynamicPressurePa / qRefPa));
        return (float) (-cAtQRefNmPerDegPerS * scale * velDegPerS);
    }

    /**
     * Coulomb friction with a linear core so it doesn't chatter at rest:
     * τ = −min(μ, μ·|θ̇|/ε)·sgn(θ̇). Hides throttle/telemetry quantization noise.
     */
    public static float friction(float muNm, double epsilonDegPerS, double velDegPerS) {
        double magnitude = Math.min(muNm, muNm * Math.abs(velDegPerS) / epsilonDegPerS);
        return (float) (-magnitude * Math.signum(velDegPerS));
    }

    /**
     * Detent bump near every 45° multiple (only while DETENT_MODIFIER is held —
     * parity with vanilla Shift snapping): a short cosine well pulling toward the
     * nearest detent, zero outside ±halfWidth.
     */
    public static float detent(double wheelDeg, float peakNm, double halfWidthDeg) {
        double nearest = Math.round(wheelDeg / 45.0) * 45.0;
        double offset = wheelDeg - nearest;
        if (Math.abs(offset) >= halfWidthDeg) {
            return 0f;
        }
        // Pull toward the detent center, strongest mid-flank, zero at center and edges
        return (float) (-peakNm * Math.sin(Math.PI * offset / halfWidthDeg));
    }
}
