package dev.aeronauticssimwheel.sim;

/**
 * Server-side stand-in for what {@code getJointImpulses} would report on a
 * steered front axle: the race car from testdata/ retrofitted with analog
 * steering (two steered front wheels on a kingpin). Per physics substep it
 * produces one steering-column torque from:
 *
 * <ul>
 *   <li>self-aligning torque: lateral tire force × trail, load-sensitive</li>
 *   <li>per-block bump texture: vertical load variation through the same path</li>
 *   <li>seam strikes: longitudinal riser force × scrub radius</li>
 * </ul>
 *
 * Shaped by real steering physics, but a stand-in — the real numbers come from
 * the solver once the server addon exists (DESIGN.md §6.2).
 */
public final class RaceCarFfbSim {

    // Racecar-ish parameters (testdata car: ~9×4×5 blocks, netherite-heavy)
    private static final double CORNER_LOAD_N = 3_000.0;   // ~1.2 t car, front-biased
    private static final double CORNERING_STIFF_N_PER_RAD = 30_000.0;
    private static final double FRICTION_MU = 0.9;
    private static final double TRAIL_M = 0.04;            // pneumatic + mechanical
    private static final double SCRUB_M = 0.06;
    private static final double STEER_RATIO = 12.0;        // column deg : road-wheel deg
    private static final double SLIP_FRACTION = 0.4;       // road-wheel angle → slip angle
    private static final double PROFILE_GAIN = 1.45;       // per-craft hinge gain trim

    private final TerrainProfile leftLane;
    private final TerrainProfile rightLane;
    private final QuarterCarWheel left = new QuarterCarWheel(CORNER_LOAD_N);
    private final QuarterCarWheel right = new QuarterCarWheel(CORNER_LOAD_N);

    private double x;

    public RaceCarFfbSim(TerrainProfile leftLane, TerrainProfile rightLane) {
        this.leftLane = leftLane;
        this.rightLane = rightLane;
    }

    /**
     * One physics substep. Returns the steering-column torque in Nm
     * (positive = clockwise from the driver's view).
     */
    public double substep(double dt, double speedMs, double steerColumnDeg) {
        x += speedMs * dt;

        left.step(dt, leftLane.height(x), leftLane.blockHeight(x), speedMs);
        right.step(dt, rightLane.height(x), rightLane.blockHeight(x), speedMs);

        double roadWheelRad = Math.toRadians(steerColumnDeg / STEER_RATIO);
        double slipRad = roadWheelRad * SLIP_FRACTION;

        double kingpinL = kingpinNm(left, slipRad, +1);
        double kingpinR = kingpinNm(right, slipRad, -1);

        // Reflect both kingpins to the column through the linkage
        return (kingpinL + kingpinR) / STEER_RATIO * PROFILE_GAIN;
    }

    private static double kingpinNm(QuarterCarWheel wheel, double slipRad, int strikeSign) {
        double fz = wheel.loadN();
        double fy = clamp(CORNERING_STIFF_N_PER_RAD * slipRad, FRICTION_MU * fz);
        double sat = -fy * TRAIL_M;                        // aligns against the steer
        double strike = strikeSign * wheel.strikeN() * SCRUB_M;
        return sat + strike;
    }

    public double positionM() {
        return x;
    }

    /** Left-corner suspension compression rate, m/s (StrikeDetector input). */
    public double leftCompressionRateMS() {
        return left.compressionRateMS();
    }

    /** Right-corner suspension compression rate, m/s (StrikeDetector input). */
    public double rightCompressionRateMS() {
        return right.compressionRateMS();
    }

    private static double clamp(double v, double magnitude) {
        return Math.max(-magnitude, Math.min(magnitude, v));
    }
}
