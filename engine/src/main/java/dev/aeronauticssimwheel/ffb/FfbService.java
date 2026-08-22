package dev.aeronauticssimwheel.ffb;

import dev.aeronauticssimwheel.hal.Capability;
import dev.aeronauticssimwheel.hal.FaultingDevice;
import dev.aeronauticssimwheel.hal.HardwareAngleSource;
import dev.aeronauticssimwheel.hal.WheelDevice;

import java.util.concurrent.locks.LockSupport;

/**
 * The FFB loop (DESIGN.md §3): owns the 250 Hz daemon thread, absolute-deadline
 * pacing, the device lifecycle and the input-source selection; all torque math
 * lives in {@link FfbPipeline}. Pure JVM so the whole shipping loop — device
 * handoff, panic paths, freshness, pacing — is unit-testable via
 * {@link #runOnce}.
 *
 * <p>Threading: the game thread publishes {@link #publishGameState} and
 * {@link #setDesiredDevice}; only the loop thread ever calls
 * ffbStart/ffbStop/ffbUpdateTorque/panic, applying device transitions itself —
 * a game-thread ffbStop racing an in-flight torque write was an adversarial
 * finding.
 *
 * <p>Input source: when the active device reports live hardware angle
 * ({@link HardwareAngleSource} — the bridge STATE stream at ~250 Hz), the
 * pipeline sees the true physical wheel at loop rate. Otherwise it falls back
 * to the commanded angle from the last game tick.
 */
public final class FfbService {

    /** Callbacks for logging/diagnostics; invoked on the loop thread. */
    public interface Listener {
        default void onDeviceSwitch(WheelDevice from, WheelDevice to) { }

        default void onDeviceError(String stage, RuntimeException error) { }

        /** The backend reported an output-path fault; the loop has panicked. */
        default void onDeviceFault(WheelDevice device) { }
    }

    /** Immutable per-tick input snapshot published by the game thread. */
    public record GameState(boolean engaged, float commandedDeg, float commandedVelDegPerS,
                            float lockDeg, long nanos) {
        static final GameState IDLE = new GameState(false, 0f, 0f, 450f, 0L);
    }

    /** Rolling loop-timing health over ~1 s windows (HUD). */
    public record LoopStats(double actualHz, double avgLateMs, double maxLateMs) {
        public static final LoopStats NONE = new LoopStats(0, 0, 0);
    }

    public static final double LOOP_HZ = 250.0;
    static final long SNAPSHOT_FRESH_NANOS = 150_000_000L;
    /** Resync (rather than sprint) after a stall longer than this many periods. */
    private static final int RESYNC_PERIODS = 4;

    private final FfbPipeline pipeline;
    private final Listener listener;

    private volatile boolean running;
    private volatile WheelDevice desiredDevice;
    private volatile GameState gameState = GameState.IDLE;
    private volatile LoopStats loopStats = LoopStats.NONE;

    // Loop-thread state
    private WheelDevice active;
    private boolean faultSeen;
    private long windowStartNanos;
    private int windowSteps;
    private double windowLateSumMs;
    private double windowLateMaxMs;

    public FfbService(FfbPipeline pipeline, Listener listener) {
        this.pipeline = pipeline;
        this.listener = listener;
    }

    private volatile Thread loopThread;

    public void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this::loop, "simwheel-ffb");
        t.setDaemon(true);
        loopThread = t;
        t.start();
    }

    public void stop() {
        running = false;
    }

    /**
     * Stop and wait briefly for the loop's teardown (device ffbStop) to run —
     * for JVM shutdown hooks, where a daemon thread would otherwise just die
     * mid-write and leave the last torque to the backend watchdogs.
     */
    public void stopAndJoin(long millis) {
        running = false;
        Thread t = loopThread;
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Game thread, once per client tick. */
    public void publishGameState(boolean engaged, float commandedDeg,
                                 float commandedVelDegPerS, float lockDeg) {
        gameState = new GameState(engaged, commandedDeg, commandedVelDegPerS,
                lockDeg, System.nanoTime());
    }

    /**
     * Game thread: where torque output SHOULD go (null = none). Filtered to
     * FFB-capable devices here; the loop thread applies the transition.
     */
    public void setDesiredDevice(WheelDevice device) {
        desiredDevice = device != null
                && device.capabilities().contains(Capability.FFB_CONSTANT) ? device : null;
    }

    private void loop() {
        long periodNanos = (long) (1e9 / LOOP_HZ);
        long prev = System.nanoTime();
        long deadline = prev + periodNanos;
        windowStartNanos = prev;
        while (running) {
            long now = System.nanoTime();
            runOnce(now, (now - prev) / 1e9);
            prev = now;

            long afterWork = System.nanoTime();
            if (deadline > afterWork) {
                LockSupport.parkNanos(deadline - afterWork);
            }
            long woke = System.nanoTime();
            noteTiming(woke, Math.max(0, woke - deadline), periodNanos);
            deadline += periodNanos;
            if (woke - deadline > (long) RESYNC_PERIODS * periodNanos) {
                deadline = woke + periodNanos; // long stall: don't sprint to catch up
            }
        }
        teardown();
    }

    /** One loop iteration. Package-private so tests drive it deterministically. */
    void runOnce(long nowNanos, double dtSeconds) {
        // A backend-reported fault on the ACTIVE device means output-path
        // integrity is unknown: panic exactly once, drop the device. This runs
        // BEFORE the attach logic — the attach guard below would otherwise
        // detach a faulted device silently and the panic would never fire.
        if (active instanceof FaultingDevice f && f.deviceFault()) {
            if (!faultSeen) {
                faultSeen = true;
                listener.onDeviceFault(active);
                panicActive();
            }
        } else {
            faultSeen = false;
        }

        WheelDevice want = desiredDevice;
        if (want != active && want instanceof FaultingDevice fd && fd.deviceFault()) {
            want = null; // never ATTACH a device currently reporting a fault
        }
        if (want != active) {
            active = switchDevice(active, want);
        }

        GameState s = gameState;
        boolean fresh = s.engaged() && (nowNanos - s.nanos()) < SNAPSHOT_FRESH_NANOS;

        float hwDeg = s.commandedDeg();
        float hwVel = s.commandedVelDegPerS();
        if (active instanceof HardwareAngleSource h && h.hardwareAngleValid()) {
            hwDeg = h.hardwareDeg();
            hwVel = h.hardwareVelDegPerS();
        }

        float out = pipeline.step(s.engaged(), nowNanos / 1e9, hwDeg, hwVel,
                s.lockDeg(), fresh, dtSeconds);

        if (active != null) {
            try {
                active.ffbUpdateTorque(out);
            } catch (RuntimeException e) {
                listener.onDeviceError("torque write", e);
                panicActive();
            }
        }
    }

    /** Loop exit: never leave torque latched. Package-private for tests. */
    void teardown() {
        active = switchDevice(active, null);
    }

    /** Loop thread only: pipeline FAULT + device panic + drop the device. */
    private void panicActive() {
        pipeline.panic();
        if (active != null) {
            try {
                active.panic();
            } catch (RuntimeException e) {
                listener.onDeviceError("panic", e);
            }
            active = null;
        }
        desiredDevice = null; // the game thread re-publishes next tick if it disagrees
    }

    /** Loop thread only: stop the old device, start the new one. */
    private WheelDevice switchDevice(WheelDevice from, WheelDevice to) {
        if (from != null) {
            try {
                from.ffbStop();
            } catch (RuntimeException e) {
                listener.onDeviceError("stop", e);
            }
        }
        if (to != null) {
            try {
                to.ffbStart();
            } catch (RuntimeException e) {
                listener.onDeviceError("start", e);
                desiredDevice = null;
                listener.onDeviceSwitch(from, null);
                return null;
            }
        }
        listener.onDeviceSwitch(from, to);
        return to;
    }

    private void noteTiming(long nowNanos, long lateNanos, long periodNanos) {
        windowSteps++;
        double lateMs = lateNanos / 1e6;
        windowLateSumMs += lateMs;
        windowLateMaxMs = Math.max(windowLateMaxMs, lateMs);
        long windowNanos = nowNanos - windowStartNanos;
        if (windowNanos >= 1_000_000_000L) {
            loopStats = new LoopStats(windowSteps / (windowNanos / 1e9),
                    windowLateSumMs / windowSteps, windowLateMaxMs);
            windowStartNanos = nowNanos;
            windowSteps = 0;
            windowLateSumMs = 0;
            windowLateMaxMs = 0;
        }
    }

    public LoopStats loopStats() {
        return loopStats;
    }
}
