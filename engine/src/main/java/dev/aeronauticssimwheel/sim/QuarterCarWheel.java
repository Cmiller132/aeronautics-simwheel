package dev.aeronauticssimwheel.sim;

/**
 * One corner of the car: unsprung mass on a tire spring, suspension spring +
 * damper to a (rigid) body. Produces the vertical load Fz and a decaying
 * longitudinal strike force when the tire hits a block-seam riser — the two
 * ingredients of kingpin torque texture. A stand-in for the solver, shaped by
 * physics, tuned by ear (DESIGN.md §10.5).
 */
public final class QuarterCarWheel {

    // Plausible racecar-corner SI values; the profile gain downstream owns scale
    private static final double UNSPRUNG_KG = 25.0;
    private static final double TIRE_N_PER_M = 200_000.0;
    private static final double SUSP_N_PER_M = 35_000.0;
    private static final double SUSP_DAMP_NS_PER_M = 3_000.0;
    private static final double STRIKE_DECAY_S = 0.035;
    private static final double TIRE_RADIUS_M = 0.5; // one-block wheel

    private final double staticLoadN;

    private double u;        // unsprung displacement from nominal, m
    private double v;        // unsprung velocity, m/s
    private double strikeN;  // decaying longitudinal strike force
    private double tireDynN; // dynamic tire-spring force from the last step
    private double lastBlockH = Double.NaN;

    public QuarterCarWheel(double staticLoadN) {
        this.staticLoadN = staticLoadN;
    }

    /**
     * Advance one substep.
     *
     * @param dt        substep seconds
     * @param groundH   terrain height under the contact patch (with micro-texture)
     * @param blockH    block-column height (for seam-strike detection)
     * @param speedMs   forward speed
     */
    public void step(double dt, double groundH, double blockH, double speedMs) {
        // Seam strike: tire face hits a riser when the block height jumps up
        if (!Double.isNaN(lastBlockH)) {
            double rise = blockH - lastBlockH;
            if (rise > 0.05) {
                // Geometric impulse: forward momentum deflected by the riser
                double j = UNSPRUNG_KG * speedMs * Math.min(1.0, rise / TIRE_RADIUS_M);
                strikeN += j / STRIKE_DECAY_S;
            }
        }
        lastBlockH = blockH;
        strikeN *= Math.exp(-dt / STRIKE_DECAY_S);

        // Sub-integrate: the tire/suspension frequencies (~90 rad/s, c/m = 120/s)
        // are unstable under plain Euler at a 60 Hz substep — 8 inner steps of
        // semi-implicit Euler keep it comfortably stable.
        int inner = 8;
        double h = dt / inner;
        double tireForce = 0;
        for (int i = 0; i < inner; i++) {
            tireForce = TIRE_N_PER_M * (groundH - u);
            double suspForce = -SUSP_N_PER_M * u - SUSP_DAMP_NS_PER_M * v;
            v += (tireForce + suspForce) / UNSPRUNG_KG * h;
            u += v * h;
        }
        tireDynN = tireForce;
    }

    /** Vertical load at the contact patch (never negative — wheels can leave the ground). */
    public double loadN() {
        return Math.max(0.0, staticLoadN + tireDynN);
    }

    /** Decaying longitudinal seam-strike force. */
    public double strikeN() {
        return strikeN;
    }

    /** Suspension compression rate, m/s (positive = compressing) — the StrikeDetector's input. */
    public double compressionRateMS() {
        return v;
    }
}
