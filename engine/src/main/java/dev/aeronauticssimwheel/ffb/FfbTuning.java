package dev.aeronauticssimwheel.ffb;

/**
 * Every client-side feel and safety gain in one immutable record (DESIGN.md
 * §10.1). The mod loads this from the hot-reloadable feel config; the harness
 * and tests build it directly. {@link FfbPipeline#setTuning} swaps it in
 * atomically between FFB steps.
 *
 * <p>All values pass through {@link #sanitized()} before use: each field is
 * clamped into a safe range and non-finite values fall back to the default —
 * a broken config file can make the wheel feel wrong, never unsafe.
 */
public record FfbTuning(
        // --- SafetyChain (§7) — changes re-ramp the chain from zero ---
        float masterGain,
        float maxTorqueNm,
        float slewNmPerSec,
        float rampInSeconds,
        double watchdogSeconds,
        // --- feel — applied live ---
        float telemetryGain,
        float damperNmPerDegPerS,
        float frictionNm,
        double frictionEpsDegPerS,
        float kneeFraction,
        float kneeRatio,
        float lockStiffnessNmPerDeg,
        float lockDampingNmPerDegPerS) {

    public static FfbTuning defaults() {
        return new FfbTuning(
                1.0f, 2.5f, 25f, 0.5f, 0.150,
                1.0f, 0.0015f, 0.12f, 5.0,
                0.65f, 3f, 0.5f, 0.008f);
    }

    /** Range-clamped copy; non-finite fields fall back to the default. */
    public FfbTuning sanitized() {
        FfbTuning d = defaults();
        return new FfbTuning(
                clamp(masterGain, 0f, 2f, d.masterGain()),
                clamp(maxTorqueNm, 0f, 10f, d.maxTorqueNm()),
                clamp(slewNmPerSec, 1f, 500f, d.slewNmPerSec()),
                clamp(rampInSeconds, 0.05f, 5f, d.rampInSeconds()),
                clamp(watchdogSeconds, 0.02, 1.0, d.watchdogSeconds()),
                clamp(telemetryGain, 0f, 4f, d.telemetryGain()),
                clamp(damperNmPerDegPerS, 0f, 0.05f, d.damperNmPerDegPerS()),
                clamp(frictionNm, 0f, 1f, d.frictionNm()),
                clamp(frictionEpsDegPerS, 0.5, 50.0, d.frictionEpsDegPerS()),
                clamp(kneeFraction, 0.3f, 1f, d.kneeFraction()),
                clamp(kneeRatio, 1f, 10f, d.kneeRatio()),
                clamp(lockStiffnessNmPerDeg, 0f, 2f, d.lockStiffnessNmPerDeg()),
                clamp(lockDampingNmPerDegPerS, 0f, 0.05f, d.lockDampingNmPerDegPerS()));
    }

    /** True when any SafetyChain parameter differs — those swaps re-ramp. */
    boolean safetyDiffers(FfbTuning o) {
        return masterGain != o.masterGain()
                || maxTorqueNm != o.maxTorqueNm()
                || slewNmPerSec != o.slewNmPerSec()
                || rampInSeconds != o.rampInSeconds()
                || watchdogSeconds != o.watchdogSeconds();
    }

    SafetyChain.Config safetyConfig() {
        return new SafetyChain.Config(masterGain, maxTorqueNm, slewNmPerSec,
                rampInSeconds, watchdogSeconds);
    }

    SoftLock.Config lockConfig() {
        return new SoftLock.Config(lockStiffnessNmPerDeg, lockDampingNmPerDegPerS);
    }

    private static float clamp(float v, float lo, float hi, float fallback) {
        return Float.isFinite(v) ? Math.clamp(v, lo, hi) : fallback;
    }

    private static double clamp(double v, double lo, double hi, double fallback) {
        return Double.isFinite(v) ? Math.clamp(v, lo, hi) : fallback;
    }
}
