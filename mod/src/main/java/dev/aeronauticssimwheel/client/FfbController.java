package dev.aeronauticssimwheel.client;

import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.ffb.EventImpulses;
import dev.aeronauticssimwheel.ffb.FeelEffects;
import dev.aeronauticssimwheel.ffb.Mixer;
import dev.aeronauticssimwheel.ffb.SafetyChain;
import dev.aeronauticssimwheel.ffb.SoftLock;
import dev.aeronauticssimwheel.ffb.TelemetryBuffer;
import dev.aeronauticssimwheel.hal.Capability;
import dev.aeronauticssimwheel.hal.WheelDevice;
import dev.aeronauticssimwheel.network.FfbEventPacket;
import dev.aeronauticssimwheel.network.FfbTelemetryPacket;
import net.minecraft.client.Minecraft;

/**
 * Client-local FFB loop for the direct-authority Sim Steering Wheel.
 *
 * <p>With direct angle authority there is no slew lag to render, so the old
 * sync-spring/predictor pair is gone. Local feel (DESIGN.md §6.5): the
 * <b>soft lock</b> at the block's configured steering range, a baseline
 * <b>damper</b> and <b>friction</b> so the wheel never feels dead. Phase 2a
 * adds the real content: <b>tire-force telemetry</b> reconstructed by the
 * {@link TelemetryBuffer} (delayed interpolation, extrapolate-then-fade) and
 * <b>contact strikes</b> as decaying {@link EventImpulses} — everything summed
 * by the {@link Mixer}'s soft knee, SafetyChain unchanged and last.
 *
 * <p>Runs on its own 250 Hz daemon thread through the full SafetyChain,
 * exactly like the final architecture; the game thread just publishes an
 * input snapshot each tick, packets land via {@link #onTelemetry}/{@link
 * #onEvent} on the main thread, and the FFB thread reads both through the
 * synchronized buffer/queue.
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

    /** Hostile-server hygiene on the event path (SafetyChain still follows). */
    private static final float MAX_EVENT_PEAK_NM = 3.0f;
    /**
     * Ingress clamp for telemetry samples: far above any legitimate signal,
     * small enough that no downstream float arithmetic (interpolation deltas,
     * mixing) can overflow — two wire-legal ±Float.MAX samples would otherwise
     * reach ±Inf inside the buffer (adversarial finding).
     */
    private static final float MAX_TELEMETRY_NM = 50.0f;

    private final SafetyChain safety = new SafetyChain(SafetyChain.Config.defaults());
    private final SoftLock softLock = new SoftLock(SoftLock.Config.defaults());
    private final TelemetryBuffer telemetry = new TelemetryBuffer();
    private final EventImpulses impulses = new EventImpulses();
    private final Mixer mixer = new Mixer(SafetyChain.Config.defaults().maxTorqueNm());

    private volatile Snapshot snapshot = Snapshot.IDLE;
    /**
     * The device torque output SHOULD go to (game thread writes on engage
     * edges, null = none). Only the FFB thread ever calls ffbStart/ffbStop/
     * ffbUpdateTorque, applying transitions itself — a game-thread ffbStop
     * racing an in-flight torque write was an adversarial finding.
     */
    private volatile WheelDevice desiredDevice;
    private volatile float lastOutputNm;
    private volatile float lastTelemetryNm;
    private volatile boolean running;

    /** Server-timeline − client-monotonic clock offset (s); NaN until known. */
    private volatile double serverClockOffsetS = Double.NaN;

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
                // Input-only backend (GLFW): feel computed, not written
                desiredDevice = d != null && d.capabilities().contains(Capability.FFB_CONSTANT)
                        ? d : null;
            } else {
                safety.disengage();
                desiredDevice = null;
                // Rig teardown: a strike or stale telemetry from this vehicle
                // must not survive into the next engagement (or next server).
                impulses.clear();
                telemetry.clear();
                serverClockOffsetS = Double.NaN;
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
        WheelDevice active = null;
        while (running) {
            long now = System.nanoTime();
            double dt = (now - prev) / 1e9;
            prev = now;

            // Apply device transitions here so every ffb* call is serialized
            // on this thread — no stop/write interleaving.
            WheelDevice want = desiredDevice;
            if (want != active) {
                active = switchDevice(active, want);
            }

            Snapshot s = snapshot;
            boolean fresh = s.engaged() && (now - s.nanos()) < SNAPSHOT_FRESH_NANOS;
            float requested = 0f;
            if (s.engaged()) {
                float telemetryNm = 0f;
                double offset = serverClockOffsetS;
                if (!Double.isNaN(offset)) {
                    telemetryNm = telemetry.sample(now / 1e9 + offset);
                }
                lastTelemetryNm = telemetryNm;
                requested = mixer.mix(
                        telemetryNm,
                        softLock.torqueNm(s.hwDeg(), s.hwVelDegPerS(), s.lockDeg()),
                        FeelEffects.damper(DAMPER_NM_PER_DEG_PER_S, 1.0, 1.0, s.hwVelDegPerS()),
                        FeelEffects.friction(FRICTION_NM, FRICTION_EPS_DEG_PER_S, s.hwVelDegPerS()),
                        impulses.step(dt));
            } else {
                lastTelemetryNm = 0f;
            }
            float out = safety.step(requested, dt, fresh);
            lastOutputNm = out;

            if (active != null) {
                try {
                    active.ffbUpdateTorque(out / RATED_TORQUE_NM);
                } catch (RuntimeException e) {
                    AeronauticsSimwheel.LOGGER.error("FFB device write failed; panicking", e);
                    safety.panic();
                    active.panic();
                    active = null;
                    desiredDevice = null;
                }
            }

            long sleepNanos = periodNanos - (System.nanoTime() - now);
            if (sleepNanos > 0) {
                try {
                    Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        switchDevice(active, null); // thread exit: never leave torque latched
    }

    /** FFB thread only: stop the old device, start the new one. */
    private WheelDevice switchDevice(WheelDevice from, WheelDevice to) {
        if (from != null) {
            try {
                from.ffbStop();
            } catch (RuntimeException e) {
                AeronauticsSimwheel.LOGGER.error("FFB device stop failed", e);
            }
        }
        if (to != null) {
            try {
                to.ffbStart();
            } catch (RuntimeException e) {
                AeronauticsSimwheel.LOGGER.error("FFB device start failed", e);
                desiredDevice = null;
                return null;
            }
        }
        return to;
    }

    // ------------------------------------------------------------------
    // Phase 2a packet ingress (client main thread)
    // ------------------------------------------------------------------

    /**
     * Telemetry batch: map the server timeline onto the local monotonic clock
     * (EMA'd so tick jitter doesn't wobble the playback point — the buffer's
     * 75 ms delay absorbs the residual), then queue the samples.
     */
    public void onTelemetry(FfbTelemetryPacket packet) {
        float[] torques = packet.torqueNm();
        float[] offsets = packet.offsetsS();
        if (torques.length == 0 || torques.length != offsets.length
                || !Double.isFinite(packet.baseTimeS())) {
            return;
        }
        double clientNowS = System.nanoTime() / 1e9;
        double lastSampleT = packet.baseTimeS() + offsets[offsets.length - 1];
        double target = lastSampleT - clientNowS;
        double offset = serverClockOffsetS;
        serverClockOffsetS = Double.isNaN(offset) ? target : offset + 0.05 * (target - offset);

        for (int i = 0; i < torques.length; i++) {
            if (Float.isFinite(torques[i])) {
                telemetry.addSample(packet.baseTimeS() + offsets[i],
                        Math.clamp(torques[i], -MAX_TELEMETRY_NM, MAX_TELEMETRY_NM));
            }
        }
    }

    /** Contact strike: clamp, then hand to the decaying-impulse renderer. */
    public void onEvent(FfbEventPacket packet) {
        float peak = Math.clamp(packet.peakNm(), -MAX_EVENT_PEAK_NM, MAX_EVENT_PEAK_NM);
        double tau = Math.clamp(packet.tauSeconds(), 0.005f, 0.5f);
        impulses.post(peak, tau);
    }

    public float lastOutputNm() {
        return lastOutputNm;
    }

    /** Reconstructed telemetry torque currently in the mix (HUD). */
    public float lastTelemetryNm() {
        return lastTelemetryNm;
    }

    /** True while telemetry playback is serving stale/faded data (HUD). */
    public boolean telemetryStale() {
        return telemetry.isStale();
    }

    public SafetyChain.State safetyState() {
        return safety.state();
    }
}
