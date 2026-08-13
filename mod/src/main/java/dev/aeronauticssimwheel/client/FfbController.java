package dev.aeronauticssimwheel.client;

import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.ffb.FeelEffects;
import dev.aeronauticssimwheel.ffb.SafetyChain;
import dev.aeronauticssimwheel.ffb.SoftLock;
import dev.aeronauticssimwheel.hal.Capability;
import dev.aeronauticssimwheel.hal.WheelDevice;
import net.minecraft.client.Minecraft;

/**
 * Client-local FFB loop for the direct-authority Sim Steering Wheel.
 *
 * <p>With direct angle authority there is no slew lag to render, so the old
 * sync-spring/predictor pair is gone. What remains local (DESIGN.md §6.5) is
 * what a standard sim rig renders without telemetry: the <b>soft lock</b> at
 * the block's configured steering range, a baseline <b>damper</b> and
 * <b>friction</b> so the wheel never feels dead. Tire-force telemetry
 * (WheelMountSource) lands in Phase 2a and adds into the same mix.
 *
 * <p>Runs on its own 250 Hz daemon thread through the full SafetyChain,
 * exactly like the final architecture; the game thread just publishes an
 * input snapshot each tick.
 */
public final class FfbController {

    /** MOZA R9 rated torque; normalizes SafetyChain Nm to device units. */
    private static final float RATED_TORQUE_NM = 9.0f;
    private static final double LOOP_HZ = 250.0;
    private static final long SNAPSHOT_FRESH_NANOS = 150_000_000L;

    /** Baseline feel constants (config UI later). */
    private static final float DAMPER_NM_PER_DEG_PER_S = 0.0015f;
    private static final float FRICTION_NM = 0.12f;
    private static final double FRICTION_EPS_DEG_PER_S = 5.0;

    private record Snapshot(boolean engaged, float hwDeg, float hwVelDegPerS,
                            float lockDeg, long nanos) {
        static final Snapshot IDLE = new Snapshot(false, 0f, 0f, 450f, 0L);
    }

    private final SafetyChain safety = new SafetyChain(SafetyChain.Config.defaults());
    private final SoftLock softLock = new SoftLock(SoftLock.Config.defaults());

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
    public void updateFromGame(Minecraft mc, WheelInput input, SimWheelLink link) {
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
        float hwDeg = link.commandedDeg(mc); // direct authority: command == position
        float hwVel = (float) ((hwDeg - prevHwDeg) / dt);
        prevHwDeg = hwDeg;

        snapshot = new Snapshot(true, hwDeg, hwVel, link.lockDeg(mc), System.nanoTime());
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
            float requested = 0f;
            if (s.engaged()) {
                requested = softLock.torqueNm(s.hwDeg(), s.hwVelDegPerS(), s.lockDeg())
                        + FeelEffects.damper(DAMPER_NM_PER_DEG_PER_S, 1.0, 1.0, s.hwVelDegPerS())
                        + FeelEffects.friction(FRICTION_NM, FRICTION_EPS_DEG_PER_S, s.hwVelDegPerS());
            }
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
}
