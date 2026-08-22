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

    /** One substep's column torque, split the way the telemetry frame carries it. */
    public record SubstepTorques(double satNm, double textureNm) {
        public double totalNm() {
            return satNm + textureNm;
        }
    }

    /**
     * One physics substep. Column torques in Nm (positive = clockwise from the
     * driver's view), split into self-aligning and texture components.
     */
    public SubstepTorques substep(double dt, double speedMs, double steerColumnDeg) {
        x += speedMs * dt;

        left.step(dt, leftLane.height(x), leftLane.blockHeight(x), speedMs);
        right.step(dt, rightLane.height(x), rightLane.blockHeight(x), speedMs);

        double roadWheelRad = Math.toRadians(steerColumnDeg / STEER_RATIO);
        double slipRad = roadWheelRad * SLIP_FRACTION;

        double sat = satNm(left, slipRad) + satNm(right, slipRad);
        // Seam strikes reflect through the scrub radius with opposite lever
        // arms per side — right-side events positive (clockwise), matching
        // the frame/strike convention.
        double texture = right.strikeN() * SCRUB_M - left.strikeN() * SCRUB_M;

        double toColumn = PROFILE_GAIN / STEER_RATIO;
        return new SubstepTorques(sat * toColumn, texture * toColumn);
    }

    /** Steered-axle slip proxy for the telemetry frame (|v_side|/|v_forward| ≈ tan α). */
    public double slipProxy(double steerColumnDeg) {
        double roadWheelRad = Math.toRadians(Math.abs(steerColumnDeg) / STEER_RATIO);
        return Math.tan(roadWheelRad * SLIP_FRACTION);
    }

    private static double satNm(QuarterCarWheel wheel, double slipRad) {
        double fz = wheel.loadN();
        double fy = clamp(CORNERING_STIFF_N_PER_RAD * slipRad, FRICTION_MU * fz);
        return -fy * TRAIL_M; // aligns against the steer
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
