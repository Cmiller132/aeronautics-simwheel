package dev.aeronauticssimwheel.ffb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipping composition's wiring contract: engage edges, rig teardown,
 * fault recovery, tuning hot-swap, clock mapping, ingress hygiene, understeer
 * collapse, speed-scaled feel, test signals — the glue the per-component
 * tests can't see.
 */
class FfbPipelineTest {

    private static final double DT = 1 / 250.0;

    /** Drive N steps at a fixed hardware state, returning the last output. */
    private static float run(FfbPipeline p, int steps, double startT, boolean engaged,
                             double hwDeg, double hwVel, double lock) {
        float out = 0f;
        for (int i = 0; i < steps; i++) {
            out = p.step(engaged, startT + i * DT, hwDeg, hwVel, lock, true, DT);
        }
        return out;
    }

    /** SAT-only frame with quiet context (no speed → no synths/parking edge cases). */
    private static TelemetryFrame satFrame(float nm) {
        return new TelemetryFrame(nm, 0f, 0f, 0f, 1f, 0f);
    }

    private static void feedSteadyTelemetry(FfbPipeline p, double fromS, double toS, float nm) {
        feedFrames(p, fromS, toS, satFrame(nm));
    }

    private static void feedFrames(FfbPipeline p, double fromS, double toS, TelemetryFrame f) {
        for (double t = fromS; t <= toS; t += 0.05) {
            p.postTelemetry(t, f);
        }
        p.noteTelemetryBatch(toS, toS);
    }

    /** defaults() with a single field overridden — keeps tests readable. */
    private static FfbTuning withTelemetryGain(float g) {
        FfbTuning d = FfbTuning.defaults();
        return new FfbTuning(d.masterGain(), d.maxTorqueNm(), d.slewNmPerSec(),
                d.rampInSeconds(), d.watchdogSeconds(), g, d.textureGain(),
                d.damperNmPerDegPerS(), d.frictionNm(), d.frictionEpsDegPerS(),
                d.kneeFraction(), d.kneeRatio(), d.lockStiffnessNmPerDeg(),
                d.lockDampingNmPerDegPerS(), d.understeerDepth(), d.understeerSlipStart(),
                d.understeerSlipFull(), d.damperFloor(), d.damperSpeedRefMS(),
                d.parkingBoost(), d.parkingSpeedMS(), d.surfaceTextureNm(), d.rumbleNm(),
                d.playbackDelayMs());
    }

    private static FfbTuning withClamp(float clampNm) {
        FfbTuning d = FfbTuning.defaults();
        return new FfbTuning(d.masterGain(), clampNm, d.slewNmPerSec(),
                d.rampInSeconds(), d.watchdogSeconds(), d.telemetryGain(), d.textureGain(),
                d.damperNmPerDegPerS(), d.frictionNm(), d.frictionEpsDegPerS(),
                d.kneeFraction(), d.kneeRatio(), d.lockStiffnessNmPerDeg(),
                d.lockDampingNmPerDegPerS(), d.understeerDepth(), d.understeerSlipStart(),
                d.understeerSlipFull(), d.damperFloor(), d.damperSpeedRefMS(),
                d.parkingBoost(), d.parkingSpeedMS(), d.surfaceTextureNm(), d.rumbleNm(),
                d.playbackDelayMs());
    }

    @Test
    void engagementRampsInFromZero() {
        FfbPipeline p = new FfbPipeline();
        // Feed telemetry PAST the playback window of the whole run — playback
        // beyond the last sample would fade to zero by design and mask the ramp.
        feedSteadyTelemetry(p, 0.0, 5.0, 1.5f);

        float first = p.step(true, 0.5, 0, 0, 450, true, DT);
        assertTrue(Math.abs(first) < 0.1, "first engaged step must start near zero: " + first);
        float later = run(p, 500, 0.5 + DT, true, 0, 0, 450); // 2 s ≫ 500 ms ramp
        assertEquals(1.5f, later, 0.05, "steady telemetry should reach the rim after ramp-in");
    }

    @Test
    void disengageClearsAllRigState() {
        FfbPipeline p = new FfbPipeline();
        feedSteadyTelemetry(p, 0.0, 2.0, 1.5f);
        p.postEvent(2.0f, 0.2);
        run(p, 250, 0.5, true, 0, 0, 450);
        assertTrue(Math.abs(p.lastOutputNm()) > 0.5, "precondition: torque flowing");

        run(p, 100, 1.5, false, 0, 0, 450); // disengage; output slews out
        assertEquals(0f, p.lastOutputNm(), 1e-3, "disengaged output must reach zero");

        // Re-engage on a fresh clock: no old telemetry, no old impulses may survive
        float out = run(p, 500, 100.0, true, 0, 0, 450);
        assertEquals(0f, out, 1e-3, "old rig state must not leak into the next engagement");
        assertEquals(0f, p.lastComponents().telemetryNm(), 1e-6);
        assertEquals(0f, p.lastComponents().impulseNm(), 1e-6);
    }

    @Test
    void faultLatchesUntilADeliberateReEngage() {
        FfbPipeline p = new FfbPipeline();
        feedSteadyTelemetry(p, 0.0, 5.0, 1.5f);
        run(p, 250, 0.5, true, 0, 0, 450);

        p.panic();
        assertEquals(SafetyChain.State.FAULT, p.safetyState());
        assertEquals(0f, p.lastOutputNm(), 0f, "panic zeroes immediately");

        // Still engaged: FAULT holds, output stays zero — never self-recovers
        float held = run(p, 250, 1.5, true, 0, 0, 450);
        assertEquals(0f, held, 0f, "FAULT must hold output at zero while engaged");
        assertEquals(SafetyChain.State.FAULT, p.safetyState());

        // The deliberate disengage acknowledges the fault; re-engage ramps back in
        run(p, 10, 2.5, false, 0, 0, 450);
        assertEquals(SafetyChain.State.DISENGAGED, p.safetyState());
        feedSteadyTelemetry(p, 3.0, 6.0, 1.5f);
        float back = run(p, 500, 3.0, true, 0, 0, 450);
        assertTrue(back > 1.0f, "forces must return after a deliberate re-engage: " + back);
    }

    @Test
    void softLockFiresOnHardwareAngleBeyondTheRange() {
        FfbPipeline p = new FfbPipeline();
        float out = run(p, 500, 0.0, true, 480.0, 0, 450.0); // 30° past the stop
        assertTrue(out < -2.0f, "the stop must saturate the clamp: " + out);
        assertEquals(0f, run(new FfbPipeline(), 500, 0.0, true, 449.0, 0, 450.0), 1e-3,
                "no lock torque inside the range");
    }

    @Test
    void understeerCollapseLightensAtHighSlip() {
        // Same SAT, low vs deep slip: the rendered torque must shed the
        // configured depth as the steered axle slides — the limit-grip
        // lightness cue the linear tire can't produce.
        FfbPipeline low = new FfbPipeline();
        feedFrames(low, 0.0, 5.0, new TelemetryFrame(1.5f, 0f, 0f, 0.1f, 1f, 0f));
        run(low, 500, 0.5, true, 0, 0, 450);
        float gripped = low.lastComponents().satNm();

        FfbPipeline deep = new FfbPipeline();
        feedFrames(deep, 0.0, 5.0, new TelemetryFrame(1.5f, 0f, 0f, 3.0f, 1f, 0f));
        run(deep, 500, 0.5, true, 0, 0, 450);
        float sliding = deep.lastComponents().satNm();

        assertEquals(1.5f, gripped, 0.05, "below slipStart: full SAT");
        float depth = FfbTuning.defaults().understeerDepth();
        assertEquals(1.5f * (1f - depth), sliding, 0.05,
                "past slipFull: SAT shed by understeerDepth");
        assertTrue(Math.abs(sliding) < Math.abs(gripped), "deep slip must read lighter");
    }

    @Test
    void parkingFrictionFadesWithSpeed() {
        // Same wheel motion, standstill vs rolling: friction must be heavier
        // parked (scrub weight) and fall to baseline as the craft rolls.
        FfbPipeline parked = new FfbPipeline();
        feedFrames(parked, 0.0, 5.0, new TelemetryFrame(0f, 0f, 0f, 0f, 1f, 0f));
        run(parked, 500, 0.5, true, 0, 60, 450);
        float parkedFriction = Math.abs(parked.lastComponents().frictionNm());

        FfbPipeline rolling = new FfbPipeline();
        feedFrames(rolling, 0.0, 5.0, new TelemetryFrame(0f, 0f, 15f, 0f, 1f, 0f));
        run(rolling, 500, 0.5, true, 0, 60, 450);
        float rollingFriction = Math.abs(rolling.lastComponents().frictionNm());

        FfbTuning d = FfbTuning.defaults();
        assertEquals(d.frictionNm() * (1f + d.parkingBoost()), parkedFriction, 0.01,
                "standstill friction carries the full parking boost");
        assertEquals(d.frictionNm(), rollingFriction, 0.01,
                "rolling friction is the baseline");
    }

    @Test
    void synthsAreKeyedToContextAndMuteWhenStale() {
        // Loose surface at speed: the texture synth must produce output.
        FfbPipeline p = new FfbPipeline();
        feedFrames(p, 0.0, 5.0, new TelemetryFrame(0f, 0f, 10f, 0f, 0.25f, 100f));
        boolean sawTexture = false;
        boolean sawRumble = false;
        for (int i = 0; i < 500; i++) {
            p.step(true, 0.5 + i * DT, 0, 0, 450, true, DT);
            sawTexture |= Math.abs(p.lastComponents().synthNm()) > 0.01f;
            sawRumble |= Math.abs(p.lastComponents().rumbleNm()) > 0.001f;
        }
        assertTrue(sawTexture, "loose surface at speed must synthesize texture");
        assertTrue(sawRumble, "spinning drivetrain must synthesize rumble");

        // Far past the last sample: context is stale → synths must be silent.
        run(p, 250, 50.0, true, 0, 0, 450);
        assertEquals(0f, p.lastComponents().synthNm(), 0f, "stale context: no synth");
        assertEquals(0f, p.lastComponents().rumbleNm(), 0f, "stale context: no rumble");
    }

    @Test
    void testSignalReplacesFeelAndStaysInsideTheChain() {
        FfbPipeline p = new FfbPipeline();
        feedSteadyTelemetry(p, 0.0, 10.0, 2.0f);
        p.setTestSignal(FfbPipeline.TestSignal.SWEEP);
        boolean sawOutput = false;
        for (int i = 0; i < 750; i++) {
            float out = p.step(true, 0.5 + i * DT, 0, 0, 450, true, DT);
            assertTrue(Math.abs(out) <= 2.5f + 1e-3, "test signal obeys the clamp");
            sawOutput |= Math.abs(out) > 0.1f;
            assertEquals(0f, p.lastComponents().telemetryNm(), 0f,
                    "feel content is replaced while the generator runs");
        }
        assertTrue(sawOutput, "the sweep must actually produce torque");

        p.setTestSignal(FfbPipeline.TestSignal.NONE);
        float back = run(p, 500, 3.5, true, 0, 0, 450);
        assertNotEquals(0f, back, "feel returns when the generator stops");
    }

    @Test
    void feelTuningAppliesLiveWithoutReRamp() {
        FfbPipeline p = new FfbPipeline();
        feedSteadyTelemetry(p, 0.0, 5.0, 1.5f);
        run(p, 500, 0.5, true, 0, 0, 450);
        assertEquals(1.5f, p.lastOutputNm(), 0.05);

        // telemetryGain 0 is a feel change: applied at the next step, no re-ramp —
        // the output slews down at the configured slew rate, it doesn't restart
        p.setTuning(withTelemetryGain(0f));
        float out = run(p, 500, 2.6, true, 0, 0, 450);
        assertEquals(0f, out, 1e-3, "telemetryGain 0 must silence the telemetry path");
        assertEquals(0f, p.lastComponents().telemetryNm(), 1e-6);
    }

    @Test
    void safetyTuningChangeReRampsAndRespectsTheNewClamp() {
        FfbPipeline p = new FfbPipeline();
        feedSteadyTelemetry(p, 0.0, 8.0, 5.0f); // asks for more than either clamp
        run(p, 500, 0.5, true, 0, 0, 450);
        assertEquals(2.5f, p.lastOutputNm(), 0.05, "precondition: at the default clamp");

        p.setTuning(withClamp(1.0f));
        float first = p.step(true, 2.6, 0, 0, 450, true, DT);
        assertTrue(Math.abs(first) < 0.5, "safety change re-ramps from zero: " + first);
        float steady = run(p, 500, 2.6 + DT, true, 0, 0, 450);
        assertTrue(steady <= 1.0f + 1e-3, "new clamp must hold: " + steady);
    }

    @Test
    void tuningEditNeverClearsALatchedFault() {
        FfbPipeline p = new FfbPipeline();
        run(p, 10, 0.0, true, 0, 0, 450);
        p.panic();
        p.setTuning(withClamp(1.0f));
        run(p, 10, 1.0, true, 0, 0, 450);
        assertEquals(SafetyChain.State.FAULT, p.safetyState(),
                "editing the config must not be a fault acknowledgement");
    }

    @Test
    void hostileIngressStaysBounded() {
        FfbPipeline p = new FfbPipeline();
        p.postTelemetry(0.0, new TelemetryFrame(Float.MAX_VALUE, Float.MAX_VALUE,
                Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE));
        p.postTelemetry(0.05, new TelemetryFrame(-Float.MAX_VALUE, -Float.MAX_VALUE,
                Float.NaN, Float.NEGATIVE_INFINITY, Float.NaN, -1f));
        p.postTelemetry(Double.NaN, satFrame(1f));
        p.postEvent(1e9f, 1e-9);
        p.postEvent(Float.NaN, 0.1);
        p.noteTelemetryBatch(0.05, 0.05);
        for (int i = 0; i < 500; i++) {
            float out = p.step(true, 0.05 + i * DT, 0, 0, 450, true, DT);
            assertTrue(Float.isFinite(out) && Math.abs(out) <= 2.5f + 1e-3,
                    "hostile ingress must never exceed the clamp: " + out);
        }
    }

    @Test
    void adaptiveDelayTightensOnLowJitterAndRespectsOverride() {
        FfbPipeline p = new FfbPipeline();
        // Perfectly regular batches: jitter EMA falls, delay approaches the floor.
        for (int i = 0; i < 200; i++) {
            double t = i * 0.05;
            p.postTelemetry(t, satFrame(1f));
            p.noteTelemetryBatch(t, t);
        }
        assertTrue(p.playbackDelayS() < 0.045,
                "regular batches must tighten the adaptive delay: " + p.playbackDelayS());

        // Explicit override wins and pins the delay.
        FfbTuning d = FfbTuning.defaults();
        p.setTuning(new FfbTuning(d.masterGain(), d.maxTorqueNm(), d.slewNmPerSec(),
                d.rampInSeconds(), d.watchdogSeconds(), d.telemetryGain(), d.textureGain(),
                d.damperNmPerDegPerS(), d.frictionNm(), d.frictionEpsDegPerS(),
                d.kneeFraction(), d.kneeRatio(), d.lockStiffnessNmPerDeg(),
                d.lockDampingNmPerDegPerS(), d.understeerDepth(), d.understeerSlipStart(),
                d.understeerSlipFull(), d.damperFloor(), d.damperSpeedRefMS(),
                d.parkingBoost(), d.parkingSpeedMS(), d.surfaceTextureNm(), d.rumbleNm(),
                100f));
        p.step(true, 10.0, 0, 0, 450, true, DT); // applies the tuning
        assertEquals(0.100, p.playbackDelayS(), 1e-6, "explicit delay override must pin");
    }

    @Test
    void clockMappingPlaysTelemetryBackAtTheOffsetTimeline() {
        FfbPipeline p = new FfbPipeline();
        // Server timeline runs 1000 s ahead of the client clock; samples cover
        // the whole playback range of the run below (fade-out is by design).
        double offset = 1000.0;
        for (double t = 0; t <= 3.5; t += 0.05) {
            p.postTelemetry(offset + t, satFrame(2.0f));
        }
        p.noteTelemetryBatch(offset + 3.5, 3.5);
        run(p, 500, 1.0, true, 0, 0, 450);
        // Assert the telemetry component itself — the rim output is soft-knee
        // compressed above 1.625 Nm, which is the Mixer's business, not the clock's.
        assertEquals(2.0f, p.lastComponents().telemetryNm(), 0.05,
                "samples on the server timeline must play back through the mapped clock");
        assertTrue(p.lastOutputNm() > 1.5f, "and reach the rim");
    }
}
