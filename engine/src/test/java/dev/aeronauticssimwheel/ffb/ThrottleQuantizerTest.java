package dev.aeronauticssimwheel.ffb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrottleQuantizerTest {

    @Test
    void mapsEndpoints() {
        ThrottleQuantizer q = new ThrottleQuantizer();
        assertEquals(0, q.update(0f));
        assertEquals(15, q.update(1f));
        assertEquals(0, q.update(0f));
    }

    @Test
    void hysteresisPreventsChatterAtBoundary() {
        ThrottleQuantizer q = new ThrottleQuantizer();
        q.update(0f);
        // The 7/8 boundary sits at 7.5 steps = 0.5 analog. Oscillate tightly around it.
        float boundary = 7.5f / 15f;
        q.update(boundary + 0.06f); // push clearly past boundary+hysteresis → 8
        assertEquals(8, q.current());
        for (int i = 0; i < 100; i++) {
            // ±0.2 steps of noise around the boundary must not change the value
            int v = q.update(boundary + (i % 2 == 0 ? 0.013f : -0.013f));
            assertEquals(8, v, "chattered at iteration " + i);
        }
    }

    @Test
    void monotonicSweepHitsEveryStep() {
        ThrottleQuantizer q = new ThrottleQuantizer();
        q.update(0f);
        int prev = 0;
        for (float a = 0f; a <= 1f; a += 0.001f) {
            int v = q.update(a);
            assertTrue(v >= prev, "must be monotonic on an upward sweep");
            assertTrue(v - prev <= 1, "must not skip steps on a slow sweep");
            prev = v;
        }
        assertEquals(15, prev);
    }

    @Test
    void firstSampleSeedsWithoutHysteresis() {
        ThrottleQuantizer q = new ThrottleQuantizer();
        assertEquals(8, q.update(8f / 15f), "first sample rounds directly");
    }
}
