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
 * μ/load emergence, differential texture, the frame's context channels, and
 * NaN hygiene. The mount samples are built exactly the way the server sampler
 * builds them from Offroad's formulas, so these tests document the whole
 * chain's physics, not just arithmetic.
 */
class GroundTorqueModelTest {

    private static final double LOCK = 450;
    /** Offroad basis handedness: X-axis facings −1, Z-axis facings +1. */
    private static final double X_AXIS = -1;
    private static final double Z_AXIS = +1;
    private static final double SPEED = 10;
    private static final double TRIM = 1.0;

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
        return new MountSample(lateralForce, axisSign, 0, 0, vSide, v, mu, 0, true);
    }

    private static MountSample bumpSample(double sideSign, double suspVel, boolean steered) {
        return new MountSample(0, Z_AXIS, sideSign, suspVel, 0, SPEED, 1.0, 0, steered);
    }

    /** Column +10° (clockwise) → mount yaw −(10/450)·30°. */
    private static double mountYawForColumn(double columnDeg) {
        return Math.toRadians(-columnDeg * GroundTorqueModel.MOUNT_LOCK_DEG / LOCK);
    }

    private float satNm(List<MountSample> mounts, double lock) {
        return model.frame(mounts, lock, SPEED, TRIM).satNm();
    }

    @Test
    void steadyCorneringTorqueOpposesTheSteerOnBothAxes() {
        double yaw = mountYawForColumn(90);
        for (double axis : new double[] {X_AXIS, Z_AXIS}) {
            // Steer clockwise (+column) at speed: the wheel must pull back
            // counterclockwise (negative torque) — self-aligning, never
            // amplifying — regardless of which way the car faces in the world.
            float t = satNm(List.of(
                    corneringSample(axis, 10, yaw, 1.0, 3600),
                    corneringSample(axis, 10, yaw, 1.0, 3600)), LOCK);
            assertTrue(t < -0.05f, "axis sign " + axis
                    + ": clockwise steer must produce counterclockwise torque, got " + t);

            // And symmetric for the other direction.
            double yawL = mountYawForColumn(-90);
            float tL = satNm(List.of(
                    corneringSample(axis, 10, yawL, 1.0, 3600),
                    corneringSample(axis, 10, yawL, 1.0, 3600)), LOCK);
            assertEquals(-t, tL, 1e-4, "axis sign " + axis + ": left/right must mirror");
        }

        // Both orientations of the same car must feel identical.
        float x = satNm(List.of(corneringSample(X_AXIS, 10, yaw, 1.0, 3600)), LOCK);
        float z = satNm(List.of(corneringSample(Z_AXIS, 10, yaw, 1.0, 3600)), LOCK);
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
        float t = satNm(List.of(
                new MountSample(lateralForce, X_AXIS, 0, 0, vSide, 10, 1.0, 0, true)), LOCK);
        assertTrue(t > 0.05f, "slide toward −side must pull the wheel clockwise, got " + t);
    }

    @Test
    void torqueScalesWithFrictionAndLoad() {
        double yaw = mountYawForColumn(90);
        float asphalt = satNm(List.of(corneringSample(Z_AXIS, 10, yaw, 1.0, 3600)), LOCK);
        float ice = satNm(List.of(corneringSample(Z_AXIS, 10, yaw, 0.1, 3600)), LOCK);
        float lightCar = satNm(List.of(corneringSample(Z_AXIS, 10, yaw, 1.0, 900)), LOCK);

        assertEquals(asphalt * 0.1f, ice, 1e-4, "μ scales torque linearly (ice goes light)");
        assertEquals(asphalt * 0.25f, lightCar, 1e-4, "load scales torque linearly");
    }

    @Test
    void tighterLockMeansStrongerReflection() {
        // Same mount forces through a quicker rack: 180° lock reflects 2.5×
        // harder than 450° (ratio 6 vs 15).
        double yaw = mountYawForColumn(90);
        List<MountSample> mounts = List.of(corneringSample(Z_AXIS, 10, yaw, 1.0, 3600));
        float slow = satNm(mounts, 450);
        float quick = satNm(mounts, 180);
        assertEquals(slow * 2.5f, quick, 1e-4);
    }

    @Test
    void bumpTextureIsDifferentialBySide() {
        // A square-on speed bump — both sides compressing together — must
        // cancel: left/right kick acts through opposite kingpin lever arms.
        TelemetryFrame squareOn = model.frame(List.of(
                bumpSample(-1, -2.0, true), bumpSample(+1, -2.0, true)), LOCK, SPEED, TRIM);
        assertEquals(0f, squareOn.textureNm(), 1e-6, "symmetric bump must cancel");

        // A one-wheel hit tugs toward its own side: a left compression
        // (suspVel < 0, sideSign −1) reads negative (counterclockwise), a
        // right compression positive; mirrored.
        TelemetryFrame leftHit = model.frame(List.of(
                bumpSample(-1, -2.0, true), bumpSample(+1, 0, true)), LOCK, SPEED, TRIM);
        TelemetryFrame rightHit = model.frame(List.of(
                bumpSample(-1, 0, true), bumpSample(+1, -2.0, true)), LOCK, SPEED, TRIM);
        assertTrue(leftHit.textureNm() < 0f,
                "left compression must read counterclockwise, got " + leftHit.textureNm());
        assertEquals(-leftHit.textureNm(), rightHit.textureNm(), 1e-6,
                "left/right hits must mirror");
    }

    @Test
    void unsteeredMountsContributeOnlyDampedBumpTexture() {
        // A rear wheel's lateral force must NOT reach the column...
        TelemetryFrame rear = model.frame(List.of(
                new MountSample(500, Z_AXIS, 1, 0, -1, 10, 1.0, 0, false)), LOCK, SPEED, TRIM);
        assertEquals(0f, rear.satNm(), 1e-6);

        // ...but its bump texture does, at the unsteered fraction.
        Config cfg = Config.defaults();
        float steeredBump = model.frame(List.of(bumpSample(1, 2.0, true)),
                LOCK, SPEED, TRIM).textureNm();
        float rearBump = model.frame(List.of(bumpSample(1, 2.0, false)),
                LOCK, SPEED, TRIM).textureNm();
        assertEquals(steeredBump * (float) cfg.unsteeredBump(), rearBump, 1e-5);
        assertTrue(Math.abs(rearBump) > 0);
    }

    @Test
    void frameCarriesContextChannels() {
        double yaw = mountYawForColumn(90);
        MountSample steered = new MountSample(100, Z_AXIS, -1, 0, 2.0, 8.0, 0.25, 64, true);
        MountSample rear = new MountSample(0, Z_AXIS, 1, 0, 0, 8.0, 1.0, 128, false);
        TelemetryFrame f = model.frame(List.of(steered, rear), LOCK, 8.0, TRIM);

        assertEquals(8f, f.speedMS(), 1e-6);
        assertEquals(2.0f / 8.0f, f.slip(), 1e-5, "slip = |vSide|/|vForward| of steered");
        assertEquals(0.25f, f.mu(), 1e-6, "μ from the steered mount, min-of");
        assertEquals(128f, f.driveRpm(), 1e-6, "rpm = fastest mount, steered or not");

        // Slip denominator floors at SLIP_MIN_FORWARD_MS so a standstill
        // slide can't explode the proxy.
        MountSample crawling = new MountSample(0, Z_AXIS, 0, 0, 1.0, 0.01, 1.0, 0, true);
        TelemetryFrame slow = model.frame(List.of(crawling), LOCK, 0.01, TRIM);
        assertEquals(1.0 / GroundTorqueModel.SLIP_MIN_FORWARD_MS, slow.slip(), 1e-5);
    }

    @Test
    void trimScalesTorqueChannelsOnly() {
        double yaw = mountYawForColumn(90);
        List<MountSample> mounts = List.of(
                corneringSample(Z_AXIS, 10, yaw, 1.0, 3600), bumpSample(1, 2.0, true));
        TelemetryFrame base = model.frame(mounts, LOCK, SPEED, 1.0);
        TelemetryFrame trimmed = model.frame(mounts, LOCK, SPEED, 0.5);
        assertEquals(base.satNm() * 0.5f, trimmed.satNm(), 1e-5);
        assertEquals(base.textureNm() * 0.5f, trimmed.textureNm(), 1e-5);
        assertEquals(base.speedMS(), trimmed.speedMS(), 0f, "context is not trimmed");
        assertEquals(base.slip(), trimmed.slip(), 0f);
    }

    @Test
    void magnitudesLandInTheWorkingBand() {
        // The reference race car (testdata): suspension strength 180, mass
        // scaling capped → strengthMul ≈ 3600 game units. Sustained cornering
        // must land where the 2.5 Nm SafetyChain clamp and 65% soft knee were
        // designed to operate — not micro, not insane.
        double yaw = mountYawForColumn(120);
        float t = Math.abs(satNm(List.of(
                corneringSample(Z_AXIS, 10, yaw, 1.0, 3600),
                corneringSample(Z_AXIS, 10, yaw, 1.0, 3600)), LOCK));
        assertTrue(t > 0.2f && t < 6f, "sustained cornering torque out of band: " + t);
    }

    @Test
    void nonFiniteInputsProduceZeroNotPoison() {
        TelemetryFrame nan = model.frame(List.of(
                new MountSample(Double.NaN, Z_AXIS, 0, 0, 0, 10, 1.0, 0, true)),
                LOCK, SPEED, TRIM);
        assertEquals(0f, nan.satNm());
        TelemetryFrame inf = model.frame(List.of(
                new MountSample(Double.POSITIVE_INFINITY, Z_AXIS, 1, 0, 0, 10, 1.0, 0, true)),
                LOCK, SPEED, TRIM);
        assertEquals(0f, inf.satNm());
        assertEquals(TelemetryFrame.ZERO, model.frame(List.of(), 0, SPEED, TRIM));
    }
}
