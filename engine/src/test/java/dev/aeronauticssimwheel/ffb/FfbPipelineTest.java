package dev.aeronauticssimwheel.ffb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipping composition's wiring contract: engage edges, rig teardown,
 * fault recovery, tuning hot-swap, clock mapping, ingress hygiene — the glue
 * the per-component tests can't see.
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

    private static void feedSteadyTelemetry(FfbPipeline p, double fromS, double toS, float nm) {
        for (double t = fromS; t <= toS; t += 0.05) {
            p.postTelemetry(t, nm);
        }
        p.noteTelemetryBatch(toS, toS);
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
    void feelTuningAppliesLiveWithoutReRamp() {
        FfbPipeline p = new FfbPipeline();
        feedSteadyTelemetry(p, 0.0, 5.0, 1.5f);
        run(p, 500, 0.5, true, 0, 0, 450);
        assertEquals(1.5f, p.lastOutputNm(), 0.05);

        // telemetryGain 0 is a feel change: applied at the next step, no re-ramp —
        // the output slews down at the configured slew rate, it doesn't restart
        FfbTuning muted = new FfbTuning(1.0f, 2.5f, 25f, 0.5f, 0.150,
                0.0f, 0.0015f, 0.12f, 5.0, 0.65f, 3f, 0.5f, 0.008f);
        p.setTuning(muted);
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

        FfbTuning lower = new FfbTuning(1.0f, 1.0f, 25f, 0.5f, 0.150,
                1.0f, 0.0015f, 0.12f, 5.0, 0.65f, 3f, 0.5f, 0.008f);
        p.setTuning(lower);
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
        p.setTuning(new FfbTuning(1.0f, 1.0f, 25f, 0.5f, 0.150,
                1.0f, 0.0015f, 0.12f, 5.0, 0.65f, 3f, 0.5f, 0.008f));
        run(p, 10, 1.0, true, 0, 0, 450);
        assertEquals(SafetyChain.State.FAULT, p.safetyState(),
                "editing the config must not be a fault acknowledgement");
    }

    @Test
    void hostileIngressStaysBounded() {
        FfbPipeline p = new FfbPipeline();
        p.postTelemetry(0.0, Float.MAX_VALUE);
        p.postTelemetry(0.05, -Float.MAX_VALUE);
        p.postTelemetry(Double.NaN, 1f);
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
    void clockMappingPlaysTelemetryBackAtTheOffsetTimeline() {
        FfbPipeline p = new FfbPipeline();
        // Server timeline runs 1000 s ahead of the client clock; samples cover
        // the whole playback range of the run below (fade-out is by design).
        double offset = 1000.0;
        for (double t = 0; t <= 3.5; t += 0.05) {
            p.postTelemetry(offset + t, 2.0f);
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
