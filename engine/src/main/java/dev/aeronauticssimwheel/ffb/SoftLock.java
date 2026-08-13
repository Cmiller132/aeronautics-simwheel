package dev.aeronauticssimwheel.ffb;

/**
 * Steering end-stop ("soft lock", DESIGN.md §6.5): zero torque inside the
 * configured lock range, a stiff spring + damper past it — the standard
 * sim-racing way to render the end of the steering range on hardware whose
 * physical rotation exceeds the vehicle's. Stiffness is deliberately high so
 * the downstream safety-chain clamp saturates and the stop feels like a wall
 * at whatever torque ceiling the user allows.
 */
public final class SoftLock {

    public record Config(float stiffnessNmPerDeg, float dampingNmPerDegPerS) {
        public static Config defaults() {
            return new Config(0.5f, 0.008f);
        }
    }

    private final Config cfg;

    public SoftLock(Config cfg) {
        this.cfg = cfg;
    }

    /**
     * @param angleDeg  hardware wheel angle (± from center)
     * @param velDegPerS hardware wheel angular velocity
     * @param lockDeg   half-range: the stop sits at ±lockDeg
     * @return torque in Nm; zero while inside the lock range
     */
    public float torqueNm(double angleDeg, double velDegPerS, double lockDeg) {
        if (lockDeg <= 0 || Math.abs(angleDeg) <= lockDeg) {
            return 0f;
        }
        double overshoot = Math.abs(angleDeg) - lockDeg;
        double side = Math.signum(angleDeg);
        // Damping acts only against motion pushing further into the stop, so the
        // stop never "sucks" the wheel outward on the way back in.
        double intoStop = velDegPerS * side > 0 ? velDegPerS * side : 0;
        return (float) (-side * (cfg.stiffnessNmPerDeg() * overshoot
                + cfg.dampingNmPerDegPerS() * intoStop));
    }
}
