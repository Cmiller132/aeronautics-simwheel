package dev.aeronauticssimwheel.ffb;

/**
 * The last stage before the device (DESIGN.md §7) — non-negotiable, no code path
 * around it. Fixed order: master gain + ramp-in, watchdog, torque clamp,
 * slew-rate limit. Pure function of its own state; fully unit-tested.
 *
 * <p>Invariants (property-tested):
 * <ul>
 *   <li>|output| ≤ maxTorqueNm, always</li>
 *   <li>|Δoutput| ≤ slewNmPerSec · dt, always — except panic, which zeroes instantly</li>
 *   <li>engagement always starts from zero and ramps in</li>
 *   <li>stale inputs or a missed loop deadline fade output to zero, never hold it</li>
 *   <li>FAULT latches; only a deliberate {@link #reset()} leaves it</li>
 * </ul>
 */
public final class SafetyChain {

    public record Config(float masterGain, float maxTorqueNm, float slewNmPerSec,
                         float rampInSeconds, double watchdogSeconds) {
        /** §7 defaults: 2.5 Nm clamp, 25 Nm/s slew, 500 ms ramp, 150 ms watchdog. */
        public static Config defaults() {
            return new Config(1.0f, 2.5f, 25f, 0.5f, 0.150);
        }
    }

    public enum State { DISENGAGED, ENGAGED, FAULT }

    private final Config cfg;
    private State state = State.DISENGAGED;
    private float rampGain;
    private float lastOutput;

    public SafetyChain(Config cfg) {
        this.cfg = cfg;
    }

    /** Begin an engagement: gain always restarts from zero (§7.1). No-op in FAULT. */
    public void engage() {
        if (state != State.FAULT) {
            state = State.ENGAGED;
            rampGain = 0f;
        }
    }

    /** Normal disengage: output slews to zero over the following steps. */
    public void disengage() {
        if (state == State.ENGAGED) {
            state = State.DISENGAGED;
        }
    }

    /** Immediate zero + latched FAULT (§7.5). Caller also fires device.panic(). */
    public void panic() {
        state = State.FAULT;
        lastOutput = 0f;
        rampGain = 0f;
    }

    /** Deliberate user action required to leave FAULT (§7.1). */
    public void reset() {
        if (state == State.FAULT) {
            state = State.DISENGAGED;
            lastOutput = 0f;
        }
    }

    public State state() {
        return state;
    }

    /**
     * One FFB-thread step.
     *
     * @param requestedNm mixer output
     * @param dtSeconds   time since the previous step
     * @param inputFresh  false when telemetry/feel inputs are stale (§6.4 / §7.4)
     * @return torque to write to the device, in Nm
     */
    public float step(float requestedNm, double dtSeconds, boolean inputFresh) {
        if (state == State.FAULT) {
            return 0f; // panic() already zeroed lastOutput
        }
        // The safety boundary trusts nothing upstream: a NaN/Inf request would
        // otherwise poison lastOutput permanently through clamp arithmetic.
        if (!Float.isFinite(requestedNm)) {
            requestedNm = 0f;
        }

        float target;
        if (state == State.ENGAGED) {
            rampGain = Math.min(1f, rampGain + (float) (dtSeconds / cfg.rampInSeconds()));
            target = requestedNm * cfg.masterGain() * rampGain;
        } else {
            target = 0f;
        }

        // Watchdog: stale inputs or a missed deadline never hold torque (§7.4)
        if (!inputFresh || dtSeconds > cfg.watchdogSeconds()) {
            target = 0f;
        }

        target = clamp(target, cfg.maxTorqueNm());

        float maxDelta = (float) (cfg.slewNmPerSec() * dtSeconds);
        lastOutput += clamp(target - lastOutput, maxDelta);
        return lastOutput;
    }

    private static float clamp(float v, float magnitude) {
        return Math.max(-magnitude, Math.min(magnitude, v));
    }
}
