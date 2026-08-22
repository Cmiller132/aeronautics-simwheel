package dev.aeronauticssimwheel.ffb;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side telemetry reconstruction (DESIGN.md §6.4), multi-channel:
 * {@link TelemetryFrame#CHANNELS} values per sample land on a server-time
 * axis; playback runs delayed with linear interpolation. On gap: extrapolate
 * at the last slope for ≤100 ms, then fade to zero over 200 ms — never hold a
 * stale torque; recovery re-ramps over 100 ms.
 *
 * <p>Fade and recovery ramp apply only to the torque channels
 * ({@link TelemetryFrame#TORQUE_CHANNELS}); context channels (speed, slip, μ,
 * rpm) hold their last value instead — their consumers gate on
 * {@link #isStale()} rather than reacting to a fabricated zero (a fade-to-zero
 * speed would spuriously trigger parking-feel effects mid-dropout).
 *
 * <p>The playback delay is settable ({@link #setPlaybackDelayS}) so the
 * pipeline can adapt it to observed batch jitter (§6.4: fixed 75 ms was sized
 * for a jittery remote server; an integrated server can run far tighter).
 *
 * <p>Pure JVM, single-writer/single-reader; callers synchronize externally or
 * use it from the FFB thread with samples handed over via the packet queue.
 */
public final class TelemetryBuffer {

    public static final double DEFAULT_PLAYBACK_DELAY_S = 0.075;
    public static final double MIN_PLAYBACK_DELAY_S = 0.025;
    public static final double MAX_PLAYBACK_DELAY_S = 0.200;
    public static final double EXTRAPOLATION_LIMIT_S = 0.100;
    public static final double FADE_S = 0.200;
    public static final double RECOVERY_RAMP_S = 0.100;

    private record Sample(double t, float[] v) {
    }

    private static final int MAX_SAMPLES = 256;
    private static final int N = TelemetryFrame.CHANNELS;

    private final List<Sample> samples = new ArrayList<>();
    // Starts false: cold-start ramp-in is the SafetyChain's job (§7.1); the
    // recovery ramp here only applies after a real telemetry gap (§6.4).
    private boolean stale = false;
    private double recoveryStart = Double.NEGATIVE_INFINITY;
    private volatile double playbackDelayS = DEFAULT_PLAYBACK_DELAY_S;

    /** Add one frame at a server-timeline instant (seconds). Must be time-ordered. */
    public synchronized void addSample(double timeSeconds, TelemetryFrame frame) {
        float[] v = new float[N];
        frame.toArray(v);
        samples.add(new Sample(timeSeconds, v));
        if (samples.size() > MAX_SAMPLES) {
            samples.subList(0, samples.size() - MAX_SAMPLES).clear();
        }
    }

    /** Any thread: playback delay in seconds, clamped to the sane window. */
    public void setPlaybackDelayS(double delayS) {
        if (Double.isFinite(delayS)) {
            playbackDelayS = Math.clamp(delayS, MIN_PLAYBACK_DELAY_S, MAX_PLAYBACK_DELAY_S);
        }
    }

    public double playbackDelayS() {
        return playbackDelayS;
    }

    /** Drop all samples and recovery state (rig teardown / server change). */
    public synchronized void clear() {
        samples.clear();
        stale = false;
        recoveryStart = Double.NEGATIVE_INFINITY;
    }

    /**
     * Reconstruct all channels for the current instant (server-timeline
     * seconds) into {@code out} (length {@link TelemetryFrame#CHANNELS}).
     */
    public synchronized void sample(double nowSeconds, float[] out) {
        double t = nowSeconds - playbackDelayS;
        if (samples.isEmpty()) {
            java.util.Arrays.fill(out, 0f);
            out[TelemetryFrame.CH_MU] = 1f;
            return;
        }

        Sample first = samples.get(0);
        Sample last = samples.get(samples.size() - 1);

        boolean fresh;
        float torqueScale = 1f;

        if (t <= first.t()) {
            System.arraycopy(first.v(), 0, out, 0, N);
            fresh = true;
        } else if (t <= last.t()) {
            interpolate(t, out);
            fresh = true;
        } else {
            double gap = t - last.t();
            if (gap <= EXTRAPOLATION_LIMIT_S) {
                extrapolate(last, gap, out);
                fresh = true;
            } else if (gap <= EXTRAPOLATION_LIMIT_S + FADE_S) {
                extrapolate(last, EXTRAPOLATION_LIMIT_S, out);
                torqueScale = 1f - (float) ((gap - EXTRAPOLATION_LIMIT_S) / FADE_S);
                fresh = false;
            } else {
                System.arraycopy(last.v(), 0, out, 0, N);
                torqueScale = 0f;
                fresh = false;
            }
        }

        if (fresh && stale) {
            // Fresh data after a stall: re-ramp torque from zero (§6.4)
            recoveryStart = nowSeconds;
        }
        stale = !fresh;

        if (fresh) {
            double sinceRecovery = nowSeconds - recoveryStart;
            if (sinceRecovery < RECOVERY_RAMP_S) {
                torqueScale = (float) (sinceRecovery / RECOVERY_RAMP_S);
            }
        }
        for (int c = 0; c < TelemetryFrame.TORQUE_CHANNELS; c++) {
            out[c] *= torqueScale;
        }
    }

    /** True when the last {@link #sample} call was served from stale/faded data. */
    public synchronized boolean isStale() {
        return stale;
    }

    private void interpolate(double t, float[] out) {
        for (int i = samples.size() - 1; i > 0; i--) {
            Sample a = samples.get(i - 1);
            Sample b = samples.get(i);
            if (t >= a.t() && t <= b.t()) {
                double f = b.t() == a.t() ? 1.0 : (t - a.t()) / (b.t() - a.t());
                for (int c = 0; c < N; c++) {
                    out[c] = (float) (a.v()[c] + f * (b.v()[c] - a.v()[c]));
                }
                return;
            }
        }
        System.arraycopy(samples.get(samples.size() - 1).v(), 0, out, 0, N);
    }

    /** Torque channels extrapolate on the last slope; context channels hold. */
    private void extrapolate(Sample last, double gap, float[] out) {
        System.arraycopy(last.v(), 0, out, 0, N);
        if (samples.size() < 2) {
            return;
        }
        Sample a = samples.get(samples.size() - 2);
        double dt = last.t() - a.t();
        if (dt <= 0) {
            return;
        }
        for (int c = 0; c < TelemetryFrame.TORQUE_CHANNELS; c++) {
            double slope = (last.v()[c] - a.v()[c]) / dt;
            out[c] = (float) (last.v()[c] + slope * gap);
        }
    }
}
