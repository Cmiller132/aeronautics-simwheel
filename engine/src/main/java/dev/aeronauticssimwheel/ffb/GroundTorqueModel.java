package dev.aeronauticssimwheel.ffb;

/**
 * Phase 2a ground-vehicle torque source (DESIGN.md §6.2, "WheelMountSource"):
 * reflects the game's own per-mount tire forces into one signed steering-column
 * torque per physics substep. Pure JVM — the server sampler feeds it numbers it
 * read from the wheel mounts; this class adds no physics of its own, only the
 * steering-geometry reflection and gains.
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
 * <p>What is deliberately NOT here: a brake-lockup cue (the game's brake is a
 * linear drag — no lockup exists, RESEARCH.md §3), and any brake-force torque
 * at all — on Offroad's symmetric mounts the drag force is parallel to the
 * trail arm (zero moment) and left/right scrub moments cancel, so the honest
 * net column torque from braking is zero. Decel-scaled cues are a Phase 2c
 * client-side concern.
 */
public final class GroundTorqueModel {

    /** Offroad wheel mounts steer ±30° at full signal (±15). */
    public static final double MOUNT_LOCK_DEG = 30.0;

    /**
     * @param trailM        pneumatic + mechanical trail reflecting lateral force
     *                      to the kingpin (m)
     * @param bumpNmSPerM   suspension-velocity texture gain at the column
     *                      (Nm per m/s of extension rate, steered mounts)
     * @param unsteeredBump fraction of bump texture transmitted from unsteered
     *                      mounts (chassis-borne, weaker)
     * @param gain          overall trim — this owns the unit conversion:
     *                      Offroad's force units are not newtons. Calibrated so
     *                      the reference race car (strength 180 → capped mass
     *                      scaling → strengthMul ≈ 3600) sustains ≈1–2 Nm in a
     *                      2 m/s-slip corner; per-craft trim is a client
     *                      config concern (Phase 2c HIL calibration).
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
     * @param suspensionVelMS d(extension)/dt this substep (m/s; + = drooping)
     * @param yawRad          the mount's current steered yaw (Offroad sign:
     *                        positive signal → negative yaw); kept for future
     *                        per-mount geometry, not used by the v1 reflection
     * @param steered         whether this mount backdrives the column (has ever
     *                        seen a steering signal while the rig was active)
     */
    public record MountSample(double lateralForceN, double kingpinSign,
                              double suspensionVelMS, double yawRad, boolean steered) {
    }

    private final Config cfg;

    public GroundTorqueModel(Config cfg) {
        this.cfg = cfg;
    }

    /**
     * Reflect one substep's mount samples into the column torque.
     *
     * @param columnLockDeg the sim wheel's configured half-lock (steering
     *                      ratio = columnLockDeg / 30°)
     * @return signed column torque in Nm (+ = clockwise)
     */
    public float columnTorqueNm(Iterable<MountSample> mounts, double columnLockDeg) {
        if (columnLockDeg <= 0) {
            return 0f;
        }
        // ∂yaw/∂column — negative (see class doc); scales the reflection down
        // by the steering ratio and flips kingpin torque into column space.
        double yawPerColumn = -MOUNT_LOCK_DEG / columnLockDeg;

        double kingpinNm = 0;
        double bumpNm = 0;
        for (MountSample m : mounts) {
            if (m.steered()) {
                // Self-aligning torque about the kingpin: sign × F_lat × trail
                // (handedness per mount axis — see class doc). Understeer
                // lightness (front μ/load collapse), countersteer pull (the
                // lateral term reverses in a slide), and ice-lightness all
                // emerge from the upstream force, not from us.
                kingpinNm += m.kingpinSign() * m.lateralForceN() * cfg.trailM();
                bumpNm += m.suspensionVelMS() * cfg.bumpNmSPerM();
            } else {
                bumpNm += m.suspensionVelMS() * cfg.bumpNmSPerM() * cfg.unsteeredBump();
            }
        }

        double columnNm = (kingpinNm * yawPerColumn + bumpNm) * cfg.gain();
        return sanitize(columnNm);
    }

    /** NaN/Inf from upstream reads must never reach the wire (§7). */
    private static float sanitize(double nm) {
        if (!Double.isFinite(nm)) {
            return 0f;
        }
        return (float) nm;
    }
}
