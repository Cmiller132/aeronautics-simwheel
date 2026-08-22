package dev.aeronauticssimwheel.ffb;

/**
 * Small, stateless local feel components (DESIGN.md §6.5). All outputs in Nm.
 * Speed scaling uses the telemetry frame's craft speed: the damper firms up
 * with speed (tire-relaxation damping, and hands-off stability against the
 * ~100 ms telemetry feedback loop) while friction is heaviest at standstill
 * (parking scrub) and fades to its baseline as the craft rolls.
 */
public final class FeelEffects {

    private FeelEffects() {
    }

    /**
     * Damper: τ = −c·scale(v)·θ̇ with scale rising from {@code floor} at
     * standstill to 1 at {@code speedRefMS}. The floor keeps a direct-drive
     * wheel from ever being fully undamped.
     */
    public static float damper(float cNmPerDegPerS, double speedMS, double floor,
                               double speedRefMS, double velDegPerS) {
        double f = Math.clamp(floor, 0.0, 1.0);
        double scale = f + (1.0 - f) * Math.min(1.0, Math.max(0.0, speedMS) / Math.max(0.1, speedRefMS));
        return (float) (-cNmPerDegPerS * scale * velDegPerS);
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
     * Friction multiplier for parking feel: {@code 1 + boost} at standstill,
     * fading linearly to 1 by {@code parkingSpeedMS}. Callers gate this on
     * fresh telemetry — a dropout must not fabricate a heavy wheel.
     */
    public static float parkingScale(double speedMS, double boost, double parkingSpeedMS) {
        double fade = Math.max(0.0, 1.0 - Math.max(0.0, speedMS) / Math.max(0.1, parkingSpeedMS));
        return (float) (1.0 + Math.max(0.0, boost) * fade);
    }
}
