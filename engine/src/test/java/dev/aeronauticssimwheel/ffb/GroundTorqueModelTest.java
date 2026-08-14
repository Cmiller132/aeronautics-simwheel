package dev.aeronauticssimwheel.ffb;

import dev.aeronauticssimwheel.ffb.GroundTorqueModel.Config;
import dev.aeronauticssimwheel.ffb.GroundTorqueModel.MountSample;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the §6.2 reflection: sign conventions (the load-bearing part — for BOTH
 * mount facing axes, whose bases have opposite handedness), ratio scaling,
 * μ/load emergence, and NaN hygiene. The mount samples are built exactly the
 * way the server sampler builds them from Offroad's formulas, so these tests
 * document the whole chain's physics, not just arithmetic.
 */
class GroundTorqueModelTest {

    private static final double LOCK = 450;
    /** Offroad basis handedness: X-axis facings −1, Z-axis facings +1. */
    private static final double X_AXIS = -1;
    private static final double Z_AXIS = +1;

    private final GroundTorqueModel model = new GroundTorqueModel(Config.defaults());

    /**
     * The game's lateral term for a steered mount on a car driving straight
     * ahead at {@code v} with the mount steered to {@code yawRad}, in each
     * axis frame. X-axis mounts (side=+X, rolling=+Z, car along +Z):
     * v_side = −v·sin(yaw). Z-axis mounts (side=+Z, rolling=+X, car along +X):
     * v_side = +v·sin(yaw). Applied force = −v_side × 0.6 × μ × load.
     */
    private static MountSample corneringSample(double axisSign, double v, double yawRad,
                                               double mu, double load) {
        double vSide = (axisSign == X_AXIS ? -1 : 1) * v * Math.sin(yawRad);
        double lateralForce = -vSide * 0.6 * mu * load;
        return new MountSample(lateralForce, axisSign, 0, yawRad, true);
    }

    /** Column +10° (clockwise) → mount yaw −(10/450)·30°. */
    private static double mountYawForColumn(double columnDeg) {
        return Math.toRadians(-columnDeg * GroundTorqueModel.MOUNT_LOCK_DEG / LOCK);
    }

    @Test
    void steadyCorneringTorqueOpposesTheSteerOnBothAxes() {
        double yaw = mountYawForColumn(90);
        for (double axis : new double[] {X_AXIS, Z_AXIS}) {
            // Steer clockwise (+column) at speed: the wheel must pull back
            // counterclockwise (negative torque) — self-aligning, never
            // amplifying — regardless of which way the car faces in the world.
            float t = model.columnTorqueNm(List.of(
                    corneringSample(axis, 10, yaw, 1.0, 3600),
                    corneringSample(axis, 10, yaw, 1.0, 3600)), LOCK);
            assertTrue(t < -0.05f, "axis sign " + axis
                    + ": clockwise steer must produce counterclockwise torque, got " + t);

            // And symmetric for the other direction.
            double yawL = mountYawForColumn(-90);
            float tL = model.columnTorqueNm(List.of(
                    corneringSample(axis, 10, yawL, 1.0, 3600),
                    corneringSample(axis, 10, yawL, 1.0, 3600)), LOCK);
            assertEquals(-t, tL, 1e-4, "axis sign " + axis + ": left/right must mirror");
        }

        // Both orientations of the same car must feel identical.
        float x = model.columnTorqueNm(
                List.of(corneringSample(X_AXIS, 10, yaw, 1.0, 3600)), LOCK);
        float z = model.columnTorqueNm(
                List.of(corneringSample(Z_AXIS, 10, yaw, 1.0, 3600)), LOCK);
        assertEquals(x, z, 1e-4, "X-axis and Z-axis cars must render the same torque");
    }

    @Test
    void slideProducesCountersteerPull() {
        // Rear breaks loose: the craft slides sideways while the steered
        // mounts are centered. X-axis frame, sliding toward −side axis →
        // ground pushes +side → the column must be pulled clockwise, toward
        // the slide — the countersteer cue.
        double vSide = -3.0;
        double lateralForce = -vSide * 0.6 * 1.0 * 3600; // positive
        float t = model.columnTorqueNm(
                List.of(new MountSample(lateralForce, X_AXIS, 0, 0, true)), LOCK);
        assertTrue(t > 0.05f, "slide toward −side must pull the wheel clockwise, got " + t);
    }

    @Test
    void torqueScalesWithFrictionAndLoad() {
        double yaw = mountYawForColumn(90);
        float asphalt = model.columnTorqueNm(
                List.of(corneringSample(Z_AXIS, 10, yaw, 1.0, 3600)), LOCK);
        float ice = model.columnTorqueNm(
                List.of(corneringSample(Z_AXIS, 10, yaw, 0.1, 3600)), LOCK);
        float lightCar = model.columnTorqueNm(
                List.of(corneringSample(Z_AXIS, 10, yaw, 1.0, 900)), LOCK);

        assertEquals(asphalt * 0.1f, ice, 1e-4, "μ scales torque linearly (ice goes light)");
        assertEquals(asphalt * 0.25f, lightCar, 1e-4, "load scales torque linearly");
    }

    @Test
    void tighterLockMeansStrongerReflection() {
        // Same mount forces through a quicker rack: 180° lock reflects 2.5×
        // harder than 450° (ratio 6 vs 15).
        double yaw = mountYawForColumn(90);
        List<MountSample> mounts = List.of(corneringSample(Z_AXIS, 10, yaw, 1.0, 3600));
        float slow = model.columnTorqueNm(mounts, 450);
        float quick = model.columnTorqueNm(mounts, 180);
        assertEquals(slow * 2.5f, quick, 1e-4);
    }

    @Test
    void unsteeredMountsContributeOnlyDampedBumpTexture() {
        // A rear wheel's lateral force must NOT reach the column...
        float rearLateral = model.columnTorqueNm(
                List.of(new MountSample(500, Z_AXIS, 0, 0, false)), LOCK);
        assertEquals(0f, rearLateral, 1e-6);

        // ...but its bump texture does, at the unsteered fraction.
        Config cfg = Config.defaults();
        float steeredBump = model.columnTorqueNm(
                List.of(new MountSample(0, Z_AXIS, 2.0, 0, true)), LOCK);
        float rearBump = model.columnTorqueNm(
                List.of(new MountSample(0, Z_AXIS, 2.0, 0, false)), LOCK);
        assertEquals(steeredBump * (float) cfg.unsteeredBump(), rearBump, 1e-5);
        assertTrue(Math.abs(rearBump) > 0);
    }

    @Test
    void magnitudesLandInTheWorkingBand() {
        // The reference race car (testdata): suspension strength 180, mass
        // scaling capped → strengthMul ≈ 3600 game units. Sustained cornering
        // must land where the 2.5 Nm SafetyChain clamp and 65% soft knee were
        // designed to operate — not micro, not insane.
        double yaw = mountYawForColumn(120);
        float t = Math.abs(model.columnTorqueNm(List.of(
                corneringSample(Z_AXIS, 10, yaw, 1.0, 3600),
                corneringSample(Z_AXIS, 10, yaw, 1.0, 3600)), LOCK));
        assertTrue(t > 0.2f && t < 6f, "sustained cornering torque out of band: " + t);
    }

    @Test
    void nonFiniteInputsProduceZeroNotPoison() {
        assertEquals(0f, model.columnTorqueNm(
                List.of(new MountSample(Double.NaN, Z_AXIS, 0, 0, true)), LOCK));
        assertEquals(0f, model.columnTorqueNm(
                List.of(new MountSample(Double.POSITIVE_INFINITY, Z_AXIS, 1, 0, true)), LOCK));
        assertEquals(0f, model.columnTorqueNm(List.of(), 0));
    }
}
