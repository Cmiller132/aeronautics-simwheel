package dev.aeronauticssimwheel.ffb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventImpulsesTest {

    @Test
    void impulseStartsAtPeakAndDecaysExponentially() {
        EventImpulses ev = new EventImpulses();
        ev.post(2.0f, 0.035);
        float first = ev.step(0.0);
        assertEquals(2.0f, first, 1e-4, "full amplitude on the absorbing step");

        // After one time constant the amplitude is peak/e
        float after = 2.0f;
        for (int i = 0; i < 35; i++) {
            after = ev.step(0.001);
        }
        assertEquals(2.0f * (float) Math.exp(-1), after, 0.02);
    }

    @Test
    void impulsesSumAndExpire() {
        EventImpulses ev = new EventImpulses();
        ev.post(1.0f, 0.020);
        ev.post(-0.5f, 0.020);
        assertEquals(0.5f, ev.step(0.0), 1e-4, "opposing strikes sum");

        for (int i = 0; i < 200; i++) {
            ev.step(0.005); // 1 s total ≫ 5τ
        }
        assertEquals(0f, ev.step(0.001), 1e-3, "fully decayed");
        assertEquals(0, ev.activeCount(), "expired impulses are reclaimed");
    }

    @Test
    void hostileBurstIsBounded() {
        EventImpulses ev = new EventImpulses();
        for (int i = 0; i < 10_000; i++) {
            ev.post(1000f, 10.0);
        }
        assertTrue(ev.activeCount() <= EventImpulses.MAX_ACTIVE);
        // The sum is bounded by MAX_ACTIVE × peak, and the SafetyChain clamp
        // downstream turns even this into ≤ maxTorqueNm at the device.
        float sum = ev.step(0.001);
        assertTrue(Math.abs(sum) <= 1000f * EventImpulses.MAX_ACTIVE);
    }

    @Test
    void invalidEventsAreIgnored() {
        EventImpulses ev = new EventImpulses();
        ev.post(Float.NaN, 0.035);
        ev.post(1f, 0.0);
        ev.post(1f, -1.0);
        assertEquals(0f, ev.step(0.001), 0.0);
        assertEquals(0, ev.activeCount());
    }
}
