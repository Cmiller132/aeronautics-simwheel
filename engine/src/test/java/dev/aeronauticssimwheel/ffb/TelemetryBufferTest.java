package dev.aeronauticssimwheel.ffb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryBufferTest {

    private static final double D = TelemetryBuffer.PLAYBACK_DELAY_S;

    @Test
    void interpolatesBetweenSamples() {
        TelemetryBuffer buf = new TelemetryBuffer();
        buf.addSample(0.00, 0f);
        buf.addSample(0.05, 1f);
        // playback time 0.025 → halfway
        assertEquals(0.5f, buf.sample(0.025 + D), 1e-5f);
        assertEquals(0.2f, buf.sample(0.010 + D), 1e-5f);
    }

    @Test
    void extrapolatesShortGapsAtLastSlope() {
        TelemetryBuffer buf = new TelemetryBuffer();
        buf.addSample(0.00, 0f);
        buf.addSample(0.05, 1f); // slope 20/s
        // 50 ms past the last sample, inside the 100 ms extrapolation window
        assertEquals(2f, buf.sample(0.10 + D), 1e-4f);
    }

    @Test
    void fadesToZeroAfterExtrapolationWindow() {
        TelemetryBuffer buf = new TelemetryBuffer();
        buf.addSample(0.00, 0f);
        buf.addSample(0.05, 1f); // slope 20/s → value 3.0 at fade start
        // Halfway through the 200 ms fade: gap = 0.1 + 0.1
        float mid = buf.sample(0.05 + 0.20 + D);
        assertEquals(1.5f, mid, 1e-4f);
        assertTrue(buf.isStale());
        // Beyond fade: zero, never a held value
        assertEquals(0f, buf.sample(0.05 + 0.35 + D), 1e-5f);
        assertEquals(0f, buf.sample(10.0 + D), 1e-5f);
    }

    @Test
    void recoveryRampsBackInAfterGap() {
        TelemetryBuffer buf = new TelemetryBuffer();
        buf.addSample(0.00, 2f);
        buf.addSample(0.05, 2f);
        // Go fully stale
        assertEquals(0f, buf.sample(1.0), 1e-5f);
        assertTrue(buf.isStale());
        // Stream resumes around t=2.0
        buf.addSample(2.00, 2f);
        buf.addSample(2.05, 2f);
        double now = 2.025 + D;
        float first = buf.sample(now);
        assertTrue(Math.abs(first) < 0.2f, "recovery must re-ramp, got " + first);
        float later = buf.sample(now + TelemetryBuffer.RECOVERY_RAMP_S + 0.001);
        assertEquals(2f, later, 1e-3f, "must reach full value after the ramp");
    }

    @Test
    void emptyBufferIsSilent() {
        assertEquals(0f, new TelemetryBuffer().sample(5.0), 0f);
    }
}
