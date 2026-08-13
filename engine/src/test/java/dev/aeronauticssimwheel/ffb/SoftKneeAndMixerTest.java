package dev.aeronauticssimwheel.ffb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftKneeAndMixerTest {

    @Test
    void identityBelowKnee() {
        assertEquals(1.0f, SoftKnee.apply(1.0f, 1.625f, 3f), 1e-6f);
        assertEquals(-1.5f, SoftKnee.apply(-1.5f, 1.625f, 3f), 1e-6f);
        assertEquals(0f, SoftKnee.apply(0f, 1.625f, 3f), 1e-6f);
    }

    @Test
    void compressesAboveKnee() {
        float knee = 1.625f;
        // 3 Nm in: knee + (3 - knee)/3
        float expected = knee + (3f - knee) / 3f;
        assertEquals(expected, SoftKnee.apply(3f, knee, 3f), 1e-6f);
        assertEquals(-expected, SoftKnee.apply(-3f, knee, 3f), 1e-6f);
    }

    @Test
    void continuousAtKnee() {
        float knee = 1.625f;
        float below = SoftKnee.apply(knee - 1e-4f, knee, 3f);
        float above = SoftKnee.apply(knee + 1e-4f, knee, 3f);
        assertTrue(Math.abs(above - below) < 1e-3f, "knee must be continuous");
    }

    @Test
    void monotonicOverRange() {
        float prev = Float.NEGATIVE_INFINITY;
        for (float x = -50f; x <= 50f; x += 0.01f) {
            float y = SoftKnee.apply(x, 1.625f, 3f);
            assertTrue(y >= prev, "must be monotonic at x=" + x);
            prev = y;
        }
    }

    @Test
    void mixerSumsComponentsBelowKnee() {
        Mixer mixer = new Mixer(2.5f); // knee at 1.625
        assertEquals(1.0f, mixer.mix(0.5f, 0.3f, 0.1f, 0.05f, 0.05f), 1e-6f);
    }

    @Test
    void mixerCompressesHeavyLoadProportionally() {
        Mixer mixer = new Mixer(2.5f);
        float a = mixer.mix(3f, 0f, 0f, 0f, 0f);
        float b = mixer.mix(6f, 0f, 0f, 0f, 0f);
        // Still increasing (proportional feel), but compressed
        assertTrue(b > a, "heavier load must still feel heavier");
        assertTrue(b - a < 3f, "growth above knee must be compressed");
    }
}
