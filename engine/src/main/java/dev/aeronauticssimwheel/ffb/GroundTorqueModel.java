package dev.aeronauticssimwheel.ffb;

/**
 * Ground-vehicle telemetry source (DESIGN.md §6.2): reflects the game's own
 * per-mount tire forces into one {@link TelemetryFrame} per physics substep.
 * Pure JVM — the server sampler feeds it numbers it read from the wheel
 * mounts; this class adds no physics of its own, only the steering-geometry
 * reflection, side attribution, and gains. Composition (understeer collapse,
 * client gains, synthesis) happens client-side in {@link FfbPipeline}.
 *
 * <p>Column sign convention: positive = clockwise from the driver's view
 * (matches {@code RaceCarFfbSim} and the whole FFB pipeline). Offroad's mount
 * yaw runs opposite to the column (positive side signal → negative yaw, see
 * {@code WheelMountBlockEntity.computeYaw}), so the kingpin→column reflection
 * carries a sign flip along with the ratio.
 *
 * <p>Sign derivation, pinned by unit test: the ground applies lateral force
 * {@code F = −v_side × 0.6 μ L} along the steered side axis; acting a trail
 * behind the kingpin its yaw-axis torque is {@code τ_kp = sign × F × trail},
 * and the column sees {@code τ_col = τ_kp × (∂yaw/∂column)} with the negative
 * ratio. The {@code sign} is the handedness of Offroad's per-axis basis —
 * (side × rolling)·ŷ — which FLIPS between mount facing axes: X-axis mounts
 * build side=+X, rolling=+Z (down, sign −1); Z-axis mounts build side=+Z,
 * rolling=+X (up, sign +1). A fixed sign would render restoring FFB on one
 * car orientation and positive-feedback FFB on the rotated one (found by
 * adversarial review; both orientations are unit-pinned).
 *
 * <p>Texture is <em>differential</em>: each mount's suspension-velocity
 * contribution is signed by its side of the craft centerline, so a square-on
 * speed bump (both sides compressing together) cancels to ≈0 column torque
 * while a one-wheel pothole tugs toward its own side — the physical behavior
 * of left/right kick through opposite kingpin lever arms. (The previous
 * unsigned sum yanked the wheel one way on symmetric bumps.)
 *
 * <p>What is deliberately NOT here: a brake-lockup cue (the game's brake is a
 * linear drag — no lockup exists, RESEARCH.md §3), and any brake-force torque
 * at all — on Offroad's symmetric mounts the drag force is parallel to the
 * trail arm (zero moment) and left/right scrub moments cancel, so the honest
 * net column torque from braking is zero.
 */
public final class GroundTorqueModel {

    /** Offroad wheel mounts steer ±30° at full signal (±15). */
    public static final double MOUNT_LOCK_DEG = 30.0;

    /** Slip-proxy denominator floor: below this forward speed, slip is meaningless. */
    public static final double SLIP_MIN_FORWARD_MS = 0.5;

    /**
     * @param trailM        pneumatic + mechanical trail reflecting lateral force
     *                      to the kingpin (m) — the REFERENCE trail; the client's
     *                      understeer model collapses it with slip
     * @param bumpNmSPerM   suspension-velocity texture gain at the column
     *                      (Nm per m/s of extension rate, steered mounts)
     * @param unsteeredBump fraction of bump texture transmitted from unsteered
     *                      mounts (chassis-borne, weaker)
     * @param gain          overall trim — this owns the unit conversion:
     *                      Offroad's force units are not newtons. Calibrated so
     *                      the reference race car (strength 180 → capped mass
     *                      scaling → strengthMul ≈ 3600) sustains ≈1–2 Nm in a
     *                      2 m/s-slip corner; per-craft character differences are
     *                      preserved (absolute physics), with a per-block trim
     *                      on the wheel BE for outliers.
     */
    public record Config(double trailM, double bumpNmSPerM,
                         double unsteeredBump, double gain) {
        public static Config defaults() {
            return new Config(0.05, 3.0, 0.25, 0.07);
        }
    }

    /**
     * One wheel mount, one substep — everything in Offroad's own units, read
     * from the game (see GroundTelemetrySampler for the exact sources).
     *
     * @param lateralForceN   tire lateral force along the steered side axis,
     *                        sign as the ground applies it (the game's
     *                        {@code −v_side × 0.6 × μ × load} term)
     * @param kingpinSign     handedness of this mount's side/rolling basis
     *                        (see class doc): −1 for X-axis facings, +1 for
     *                        Z-axis facings
     * @param sideSign        which side of the craft centerline this mount sits
     *                        on, from the driver's view: −1 = left, +1 = right,
     *                        0 = on the centerline (no differential texture)
     * @param suspensionVelMS d(extension)/dt this substep (m/s; + = drooping)
     * @param vSideMS         contact-patch sideways velocity along the steered
     *                        side axis (m/s) — slip-proxy numerator
     * @param vForwardMS      contact-patch velocity along the rolling axis
     *                        (m/s) — slip-proxy denominator
     * @param mu              this mount's contact friction (ice 0.1 … 1.0)
     * @param driveRpm        the mount's kinetic speed (RPM, signed)
     * @param steered         whether this mount backdrives the column
     */
    public record MountSample(double lateralForceN, double kingpinSign, double sideSign,
                              double suspensionVelMS, double vSideMS, double vForwardMS,
                              double mu, double driveRpm, boolean steered) {
    }

    private final Config cfg;

    public GroundTorqueModel(Config cfg) {
        this.cfg = cfg;
    }

    /**
     * Reflect one substep's mount samples into a telemetry frame.
     *
     * @param columnLockDeg the sim wheel's configured half-lock (steering
     *                      ratio = columnLockDeg / 30°)
     * @param craftSpeedMS  craft speed (m/s, ≥ 0) for the frame's context
     * @param trim          per-block gain trim (wheel BE setting, ≥ 0)
     */
    public TelemetryFrame frame(Iterable<MountSample> mounts, double columnLockDeg,
                                double craftSpeedMS, double trim) {
        if (columnLockDeg <= 0) {
            return TelemetryFrame.ZERO;
        }
        // ∂yaw/∂column — negative (see class doc); scales the reflection down
        // by the steering ratio and flips kingpin torque into column space.
        double yawPerColumn = -MOUNT_LOCK_DEG / columnLockDeg;

        double kingpinNm = 0;
        double textureNm = 0;
        double slip = 0;
        double mu = Double.NaN;
        double rpm = 0;
        for (MountSample m : mounts) {
            // Differential texture, side-signed: a compression (negative
            // suspension velocity) on the RIGHT reads positive (clockwise),
            // on the LEFT negative — matching the strike-event convention.
            double bump = -m.suspensionVelMS() * cfg.bumpNmSPerM() * m.sideSign();
            if (m.steered()) {
                // Self-aligning torque about the kingpin: sign × F_lat × trail
                // (handedness per mount axis — see class doc). Understeer
                // lightness is rendered client-side by collapsing the trail
                // with this frame's slip; countersteer pull and ice-lightness
                // emerge from the upstream force and μ, not from us.
                kingpinNm += m.kingpinSign() * m.lateralForceN() * cfg.trailM();
                textureNm += bump;
                slip = Math.max(slip, Math.abs(m.vSideMS())
                        / Math.max(Math.abs(m.vForwardMS()), SLIP_MIN_FORWARD_MS));
                mu = Double.isNaN(mu) ? m.mu() : Math.min(mu, m.mu());
            } else {
                textureNm += bump * cfg.unsteeredBump();
            }
            rpm = Math.max(rpm, Math.abs(m.driveRpm()));
        }

        return new TelemetryFrame(
                sanitize(kingpinNm * yawPerColumn * cfg.gain() * trim),
                sanitize(textureNm * cfg.gain() * trim),
                sanitize(Math.max(0, craftSpeedMS)),
                sanitize(slip),
                Double.isNaN(mu) ? 1f : sanitize(mu),
                sanitize(rpm)).sanitizedForIngress();
    }

    /** NaN/Inf from upstream reads must never reach the wire (§7). */
    private static float sanitize(double v) {
        if (!Double.isFinite(v)) {
            return 0f;
        }
        return (float) v;
    }
}
