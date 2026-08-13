package dev.aeronauticssimwheel.client;

import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.ffb.SafetyChain;
import dev.aeronauticssimwheel.ffb.SyncSpring;
import dev.aeronauticssimwheel.ffb.VirtualWheelPredictor;
import dev.aeronauticssimwheel.hal.Capability;
import dev.aeronauticssimwheel.hal.WheelDevice;
import net.minecraft.client.Minecraft;

/**
 * MVP force-feedback loop: vanilla-compat client-only mode (DESIGN.md §6.5
 * degraded path). No server telemetry yet — the virtual wheel is dead-reckoned
 * from what we commanded (16 RPM slew) and corrected by the BE's synced angle,
 * and the only feel source is the sync-spring + damper. Runs on its own 250 Hz
 * daemon thread through the full SafetyChain, exactly like the final
 * architecture; the game thread just publishes an input snapshot each tick.
 */
public final class FfbController {

    /** MOZA R9 rated torque; normalizes SafetyChain Nm to device units. */
    private static final float RATED_TORQUE_NM = 9.0f;
    private static final double LOOP_HZ = 250.0;
    private static final long SNAPSHOT_FRESH_NANOS = 150_000_000L;

    private record Snapshot(boolean engaged, float hwDeg, float hwVelDegPerS,
                            double virtualDeg, long nanos) {
        static final Snapshot IDLE = new Snapshot(false, 0f, 0f, 0.0, 0L);
    }

    private final SafetyChain safety = new SafetyChain(SafetyChain.Config.defaults());
    private final SyncSpring spring = new SyncSpring(SyncSpring.Config.defaults());
    private final VirtualWheelPredictor predictor = new VirtualWheelPredictor();

    private volatile Snapshot snapshot = Snapshot.IDLE;
    private volatile WheelDevice device;
    private volatile float lastOutputNm;
    private volatile boolean running;

    private boolean wasEngaged;
    private float prevHwDeg;

    public void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this::loop, "simwheel-ffb");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
    }

    /** Game thread, once per client tick: publish inputs for the FFB thread. */
    public void updateFromGame(Minecraft mc, WheelInput input, SteeringWheelLink link) {
        boolean engaged = link.isEngaged() && input.hasInput();

        if (engaged != wasEngaged) {
            if (engaged) {
                safety.engage();
                WheelDevice d = input.activeDevice();
                if (d != null && d.capabilities().contains(Capability.FFB_CONSTANT)) {
                    d.ffbStart();
                    device = d;
                } else {
                    device = null; // input-only backend (GLFW): feel computed, not written
                }
            } else {
                safety.disengage();
                WheelDevice d = device;
                if (d != null) {
                    d.ffbStop();
                }
                device = null;
            }
            wasEngaged = engaged;
        }

        if (!engaged) {
            snapshot = Snapshot.IDLE;
            return;
        }

        double dt = 1 / 20.0;
        predictor.setCommandedTarget(link.commandedDeg());
        predictor.step(dt);
        float measured = link.measuredDeg(mc);
        if (!Float.isNaN(measured)) {
            predictor.onMeasurement(measured);
        }

        float hwDeg = link.commandedDeg(); // hardware position in wheel-space == the command
        float hwVel = (float) ((hwDeg - prevHwDeg) / dt);
        prevHwDeg = hwDeg;

        snapshot = new Snapshot(true, hwDeg, hwVel, predictor.angleDeg(), System.nanoTime());
    }

    private void loop() {
        long prev = System.nanoTime();
        long periodNanos = (long) (1e9 / LOOP_HZ);
        while (running) {
            long now = System.nanoTime();
            double dt = (now - prev) / 1e9;
            prev = now;

            Snapshot s = snapshot;
            boolean fresh = s.engaged() && (now - s.nanos()) < SNAPSHOT_FRESH_NANOS;
            // Ground-vehicle MVP: zero dynamic pressure → sync-spring at k_min.
            float requested = s.engaged()
                    ? spring.torqueNm(s.hwDeg(), s.hwVelDegPerS(), s.virtualDeg(), 0.0)
                    : 0f;
            float out = safety.step(requested, dt, fresh);
            lastOutputNm = out;

            WheelDevice d = device;
            if (d != null) {
                try {
                    d.ffbUpdateTorque(out / RATED_TORQUE_NM);
                } catch (RuntimeException e) {
                    AeronauticsSimwheel.LOGGER.error("FFB device write failed; panicking", e);
                    safety.panic();
                    d.panic();
                    device = null;
                }
            }

            long sleepNanos = periodNanos - (System.nanoTime() - now);
            if (sleepNanos > 0) {
                try {
                    Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public float lastOutputNm() {
        return lastOutputNm;
    }

    public SafetyChain.State safetyState() {
        return safety.state();
    }

    public double virtualWheelDeg() {
        return predictor.angleDeg();
    }
}
