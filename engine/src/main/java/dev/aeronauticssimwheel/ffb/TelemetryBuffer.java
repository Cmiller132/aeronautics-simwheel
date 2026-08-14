package dev.aeronauticssimwheel.ffb;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side torque reconstruction (DESIGN.md §6.4). Samples land on a
 * server-time axis; playback runs delayed by 75 ms with linear interpolation.
 * On gap: extrapolate at the last slope for ≤100 ms, then fade to zero over
 * 200 ms — never hold a stale torque. Recovery re-ramps over 100 ms.
 *
 * <p>Pure JVM, single-writer/single-reader; callers synchronize externally or
 * use it from the FFB thread with samples handed over via the packet queue.
 */
public final class TelemetryBuffer {

    public static final double PLAYBACK_DELAY_S = 0.075;
    public static final double EXTRAPOLATION_LIMIT_S = 0.100;
    public static final double FADE_S = 0.200;
    public static final double RECOVERY_RAMP_S = 0.100;

    private record Sample(double t, float torque) {
    }

    private static final int MAX_SAMPLES = 256;

    private final List<Sample> samples = new ArrayList<>();
    // Starts false: cold-start ramp-in is the SafetyChain's job (§7.1); the
    // recovery ramp here only applies after a real telemetry gap (§6.4).
    private boolean stale = false;
    private double recoveryStart = Double.NEGATIVE_INFINITY;

    /** Add one torque sample at a server-timeline instant (seconds). Must be time-ordered. */
    public synchronized void addSample(double timeSeconds, float torqueNm) {
        samples.add(new Sample(timeSeconds, torqueNm));
        if (samples.size() > MAX_SAMPLES) {
            samples.subList(0, samples.size() - MAX_SAMPLES).clear();
        }
    }

    /** Drop all samples and recovery state (rig teardown / server change). */
    public synchronized void clear() {
        samples.clear();
        stale = false;
        recoveryStart = Double.NEGATIVE_INFINITY;
    }

    /** Reconstruct the torque for the current instant (server-timeline seconds). */
    public synchronized float sample(double nowSeconds) {
        double t = nowSeconds - PLAYBACK_DELAY_S;
        if (samples.isEmpty()) {
            return 0f;
        }

        Sample first = samples.get(0);
        Sample last = samples.get(samples.size() - 1);

        float value;
        boolean fresh;

        if (t <= first.t()) {
            value = first.torque();
            fresh = true;
        } else if (t <= last.t()) {
            value = interpolate(t);
            fresh = true;
        } else {
            double gap = t - last.t();
            double slope = slope();
            if (gap <= EXTRAPOLATION_LIMIT_S) {
                value = (float) (last.torque() + slope * gap);
                fresh = true;
            } else if (gap <= EXTRAPOLATION_LIMIT_S + FADE_S) {
                float atFadeStart = (float) (last.torque() + slope * EXTRAPOLATION_LIMIT_S);
                float fade = 1f - (float) ((gap - EXTRAPOLATION_LIMIT_S) / FADE_S);
                value = atFadeStart * fade;
                fresh = false;
            } else {
                value = 0f;
                fresh = false;
            }
        }

        if (fresh && stale) {
            // Fresh data after a stall: re-ramp from zero (§6.4)
            recoveryStart = nowSeconds;
        }
        stale = !fresh;

        if (fresh) {
            double sinceRecovery = nowSeconds - recoveryStart;
            if (sinceRecovery < RECOVERY_RAMP_S) {
                value *= (float) (sinceRecovery / RECOVERY_RAMP_S);
            }
        }
        return value;
    }

    /** True when the last {@link #sample} call was served from stale/faded data. */
    public synchronized boolean isStale() {
        return stale;
    }

    private float interpolate(double t) {
        for (int i = samples.size() - 1; i > 0; i--) {
            Sample a = samples.get(i - 1);
            Sample b = samples.get(i);
            if (t >= a.t() && t <= b.t()) {
                double f = b.t() == a.t() ? 1.0 : (t - a.t()) / (b.t() - a.t());
                return (float) (a.torque() + f * (b.torque() - a.torque()));
            }
        }
        return samples.get(samples.size() - 1).torque();
    }

    private double slope() {
        if (samples.size() < 2) {
            return 0.0;
        }
        Sample a = samples.get(samples.size() - 2);
        Sample b = samples.get(samples.size() - 1);
        double dt = b.t() - a.t();
        return dt <= 0 ? 0.0 : (b.torque() - a.torque()) / dt;
    }
}
