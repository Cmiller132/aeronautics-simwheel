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
 *
 * <p>Sim-grade defaults (feel review 2026-08): slew 300 Nm/s so transients
 * (strikes, countersteer reversals, the lock wall) actually reach the rim —
 * the torque clamp remains the primary safety boundary; at the default
 * 2.5 Nm clamp a worst-case full reversal is a 17 ms, 5 Nm-swing event.
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
        float textureGain,
        float damperNmPerDegPerS,
        float frictionNm,
        double frictionEpsDegPerS,
        float kneeFraction,
        float kneeRatio,
        float lockStiffnessNmPerDeg,
        float lockDampingNmPerDegPerS,
        // --- understeer trail collapse (the limit-grip lightness cue) ---
        float understeerDepth,
        float understeerSlipStart,
        float understeerSlipFull,
        // --- speed scaling ---
        float damperFloor,
        float damperSpeedRefMS,
        float parkingBoost,
        float parkingSpeedMS,
        // --- synthesis ---
        float surfaceTextureNm,
        float rumbleNm,
        // --- reconstruction ---
        float playbackDelayMs) {

    public static FfbTuning defaults() {
        return new FfbTuning(
                1.0f, 2.5f, 300f, 0.5f, 0.150,
                1.0f, 1.0f, 0.0015f, 0.12f, 5.0,
                0.65f, 3f, 3.0f, 0.008f,
                0.55f, 0.35f, 1.2f,
                0.35f, 8f, 2.5f, 3f,
                0.35f, 0.05f,
                0f);
    }

    /** Range-clamped copy; non-finite fields fall back to the default. */
    public FfbTuning sanitized() {
        FfbTuning d = defaults();
        float slipStart = clamp(understeerSlipStart, 0.05f, 2f, d.understeerSlipStart());
        return new FfbTuning(
                clamp(masterGain, 0f, 2f, d.masterGain()),
                clamp(maxTorqueNm, 0f, 10f, d.maxTorqueNm()),
                clamp(slewNmPerSec, 1f, 2000f, d.slewNmPerSec()),
                clamp(rampInSeconds, 0.05f, 5f, d.rampInSeconds()),
                clamp(watchdogSeconds, 0.02, 1.0, d.watchdogSeconds()),
                clamp(telemetryGain, 0f, 4f, d.telemetryGain()),
                clamp(textureGain, 0f, 4f, d.textureGain()),
                clamp(damperNmPerDegPerS, 0f, 0.05f, d.damperNmPerDegPerS()),
                clamp(frictionNm, 0f, 1f, d.frictionNm()),
                clamp(frictionEpsDegPerS, 0.5, 50.0, d.frictionEpsDegPerS()),
                clamp(kneeFraction, 0.3f, 1f, d.kneeFraction()),
                clamp(kneeRatio, 1f, 10f, d.kneeRatio()),
                clamp(lockStiffnessNmPerDeg, 0f, 8f, d.lockStiffnessNmPerDeg()),
                clamp(lockDampingNmPerDegPerS, 0f, 0.05f, d.lockDampingNmPerDegPerS()),
                clamp(understeerDepth, 0f, 0.95f, d.understeerDepth()),
                slipStart,
                // Full-collapse point must sit beyond the start of collapse.
                Math.max(slipStart + 0.05f,
                        clamp(understeerSlipFull, 0.1f, 5f, d.understeerSlipFull())),
                clamp(damperFloor, 0f, 1f, d.damperFloor()),
                clamp(damperSpeedRefMS, 0.5f, 50f, d.damperSpeedRefMS()),
                clamp(parkingBoost, 0f, 10f, d.parkingBoost()),
                clamp(parkingSpeedMS, 0.1f, 20f, d.parkingSpeedMS()),
                clamp(surfaceTextureNm, 0f, 2f, d.surfaceTextureNm()),
                clamp(rumbleNm, 0f, 1f, d.rumbleNm()),
                clamp(playbackDelayMs, 0f, 200f, d.playbackDelayMs()));
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
