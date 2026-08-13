package dev.aeronauticssimwheel.ffb;

/**
 * The load-bearing feel trick (DESIGN.md §6.5): τ = −k_s·(θ_hw − θ_virt) − c_s·θ̇_hw.
 * Renders the 16 RPM kinetic slew as honest mechanical resistance, keeps hardware
 * and virtual wheel converged, and gives the wheel weight even at zero airspeed.
 * k_s scales with dynamic pressure between min and max.
 */
public final class SyncSpring {

    public record Config(float kMinNmPerDeg, float kMaxNmPerDeg, float qRefPa,
                         float dampingNmPerDegPerS) {
        public static Config defaults() {
            return new Config(0.005f, 0.03f, 500f, 0.002f);
        }
    }

    private final Config cfg;

    public SyncSpring(Config cfg) {
        this.cfg = cfg;
    }

    public float torqueNm(double hardwareDeg, double hardwareVelDegPerS,
                          double virtualDeg, double dynamicPressurePa) {
        double qScale = Math.min(1.0, Math.max(0.0, dynamicPressurePa / cfg.qRefPa()));
        double k = cfg.kMinNmPerDeg() + (cfg.kMaxNmPerDeg() - cfg.kMinNmPerDeg()) * qScale;
        return (float) (-k * (hardwareDeg - virtualDeg)
                - cfg.dampingNmPerDegPerS() * hardwareVelDegPerS);
    }
}
