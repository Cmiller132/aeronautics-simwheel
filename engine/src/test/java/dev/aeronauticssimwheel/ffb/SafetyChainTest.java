package dev.aeronauticssimwheel.ffb;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyChainTest {

    private static final double DT = 0.001; // 1 kHz loop
    private static final float EPS = 1e-4f;

    @Test
    void outputStartsAtZeroAndRampsIn() {
        SafetyChain chain = new SafetyChain(SafetyChain.Config.defaults());
        chain.engage();
        float first = chain.step(100f, DT, true);
        // First step is bounded by both the ramp and the slew limit
        assertTrue(Math.abs(first) <= 25f * DT + EPS, "first output must be near zero, was " + first);
    }

    @Test
    void clampInvariantUnderRandomInput() {
        SafetyChain chain = new SafetyChain(SafetyChain.Config.defaults());
        chain.engage();
        Random rng = new Random(42);
        for (int i = 0; i < 100_000; i++) {
            float request = (rng.nextFloat() - 0.5f) * 200f; // ±100 Nm garbage
            float out = chain.step(request, DT, true);
            assertTrue(Math.abs(out) <= 2.5f + EPS, "clamp violated: " + out);
        }
    }

    @Test
    void slewInvariantUnderRandomInput() {
        SafetyChain chain = new SafetyChain(SafetyChain.Config.defaults());
        chain.engage();
        Random rng = new Random(7);
        float prev = 0f;
        for (int i = 0; i < 100_000; i++) {
            // Random torque, random sign flips, random staleness — the works
            float request = (rng.nextFloat() - 0.5f) * 200f;
            boolean fresh = rng.nextFloat() > 0.01f;
            float out = chain.step(request, DT, fresh);
            assertTrue(Math.abs(out - prev) <= 25f * DT + EPS,
                    "slew violated at step " + i + ": " + prev + " -> " + out);
            prev = out;
        }
    }

    @Test
    void watchdogFadesToZeroOnStaleInput() {
        SafetyChain chain = new SafetyChain(SafetyChain.Config.defaults());
        chain.engage();
        // Drive to a steady torque
        for (int i = 0; i < 2000; i++) {
            chain.step(2f, DT, true);
        }
        assertTrue(chain.step(2f, DT, true) > 1.9f);
        // Inputs go stale: output must fall to zero, slew-limited, never hold
        float out = 0f;
        for (int i = 0; i < 200; i++) {
            out = chain.step(2f, DT, false);
        }
        assertEquals(0f, out, EPS, "output must fade to zero on stale input");
    }

    @Test
    void missedDeadlineDropsTorque() {
        SafetyChain chain = new SafetyChain(SafetyChain.Config.defaults());
        chain.engage();
        for (int i = 0; i < 2000; i++) {
            chain.step(2f, DT, true);
        }
        // A 300 ms GC-pause-sized step: watchdog treats it as a fade-to-zero step
        float out = chain.step(2f, 0.300, true);
        assertTrue(out < 2f, "oversized dt must not hold torque, was " + out);
    }

    @Test
    void panicZeroesImmediatelyAndLatchesFault() {
        SafetyChain chain = new SafetyChain(SafetyChain.Config.defaults());
        chain.engage();
        for (int i = 0; i < 2000; i++) {
            chain.step(2f, DT, true);
        }
        chain.panic();
        assertEquals(SafetyChain.State.FAULT, chain.state());
        assertEquals(0f, chain.step(2f, DT, true), "FAULT must output zero");
        // engage() must not work while faulted
        chain.engage();
        assertEquals(SafetyChain.State.FAULT, chain.state());
        assertEquals(0f, chain.step(2f, DT, true));
        // Only a deliberate reset leaves FAULT
        chain.reset();
        assertEquals(SafetyChain.State.DISENGAGED, chain.state());
        chain.engage();
        assertEquals(SafetyChain.State.ENGAGED, chain.state());
    }

    @Test
    void disengageSlewsOutputToZero() {
        SafetyChain chain = new SafetyChain(SafetyChain.Config.defaults());
        chain.engage();
        for (int i = 0; i < 2000; i++) {
            chain.step(2f, DT, true);
        }
        chain.disengage();
        float prev = Float.MAX_VALUE;
        float out = Float.MAX_VALUE;
        for (int i = 0; i < 200; i++) {
            out = chain.step(2f, DT, true);
            assertTrue(Math.abs(out) <= Math.abs(prev) + EPS, "must decay monotonically");
            prev = out;
        }
        assertEquals(0f, out, EPS);
    }

    @Test
    void reEngageAlwaysRampsFromZeroGain() {
        SafetyChain chain = new SafetyChain(SafetyChain.Config.defaults());
        chain.engage();
        for (int i = 0; i < 2000; i++) {
            chain.step(2f, DT, true);
        }
        chain.disengage();
        for (int i = 0; i < 300; i++) {
            chain.step(0f, DT, true);
        }
        chain.engage();
        float first = chain.step(100f, DT, true);
        assertTrue(Math.abs(first) <= 25f * DT + EPS, "re-engage must restart the ramp");
    }
}
