package dev.aeronauticssimwheel.ffb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryBufferTest {

    private static final double D = TelemetryBuffer.DEFAULT_PLAYBACK_DELAY_S;

    /** SAT-only frame with recognizable context values. */
    private static TelemetryFrame sat(float satNm) {
        return new TelemetryFrame(satNm, 0f, 12f, 0.4f, 0.9f, 100f);
    }

    private static float sampleSat(TelemetryBuffer buf, double now) {
        float[] out = new float[TelemetryFrame.CHANNELS];
        buf.sample(now, out);
        return out[TelemetryFrame.CH_SAT];
    }

    private static float[] sampleAll(TelemetryBuffer buf, double now) {
        float[] out = new float[TelemetryFrame.CHANNELS];
        buf.sample(now, out);
        return out;
    }

    @Test
    void interpolatesBetweenSamples() {
        TelemetryBuffer buf = new TelemetryBuffer();
        buf.addSample(0.00, sat(0f));
        buf.addSample(0.05, sat(1f));
        // playback time 0.025 → halfway
        assertEquals(0.5f, sampleSat(buf, 0.025 + D), 1e-5f);
        assertEquals(0.2f, sampleSat(buf, 0.010 + D), 1e-5f);
    }

    @Test
    void extrapolatesShortGapsAtLastSlope() {
        TelemetryBuffer buf = new TelemetryBuffer();
        buf.addSample(0.00, sat(0f));
        buf.addSample(0.05, sat(1f)); // slope 20/s
        // 50 ms past the last sample, inside the 100 ms extrapolation window
        assertEquals(2f, sampleSat(buf, 0.10 + D), 1e-4f);
    }

    @Test
    void fadesToZeroAfterExtrapolationWindow() {
        TelemetryBuffer buf = new TelemetryBuffer();
        buf.addSample(0.00, sat(0f));
        buf.addSample(0.05, sat(1f)); // slope 20/s → value 3.0 at fade start
        // Halfway through the 200 ms fade: gap = 0.1 + 0.1
        float mid = sampleSat(buf, 0.05 + 0.20 + D);
        assertEquals(1.5f, mid, 1e-4f);
        assertTrue(buf.isStale());
        // Beyond fade: zero, never a held value
        assertEquals(0f, sampleSat(buf, 0.05 + 0.35 + D), 1e-5f);
        assertEquals(0f, sampleSat(buf, 10.0 + D), 1e-5f);
    }

    @Test
    void contextChannelsHoldInsteadOfFading() {
        TelemetryBuffer buf = new TelemetryBuffer();
        buf.addSample(0.00, sat(2f));
        buf.addSample(0.05, sat(2f));
        float[] deepStale = sampleAll(buf, 5.0);
        assertTrue(buf.isStale());
        // Torque channels faded to zero...
        assertEquals(0f, deepStale[TelemetryFrame.CH_SAT], 1e-5f);
        assertEquals(0f, deepStale[TelemetryFrame.CH_TEXTURE], 1e-5f);
        // ...context channels hold their last value (consumers gate on isStale):
        // a fade-to-zero speed would fabricate parking feel mid-dropout.
        assertEquals(12f, deepStale[TelemetryFrame.CH_SPEED], 1e-5f);
        assertEquals(0.9f, deepStale[TelemetryFrame.CH_MU], 1e-5f);
        assertEquals(100f, deepStale[TelemetryFrame.CH_RPM], 1e-5f);
    }

    @Test
    void recoveryRampsBackInAfterGap() {
        TelemetryBuffer buf = new TelemetryBuffer();
        buf.addSample(0.00, sat(2f));
        buf.addSample(0.05, sat(2f));
        // Go fully stale
        assertEquals(0f, sampleSat(buf, 1.0), 1e-5f);
        assertTrue(buf.isStale());
        // Stream resumes around t=2.0
        buf.addSample(2.00, sat(2f));
        buf.addSample(2.05, sat(2f));
        double now = 2.025 + D;
        float first = sampleSat(buf, now);
        assertTrue(Math.abs(first) < 0.2f, "recovery must re-ramp, got " + first);
        float later = sampleSat(buf, now + TelemetryBuffer.RECOVERY_RAMP_S + 0.001);
        assertEquals(2f, later, 1e-3f, "must reach full value after the ramp");
    }

    @Test
    void playbackDelayIsSettableAndClamped() {
        TelemetryBuffer buf = new TelemetryBuffer();
        buf.setPlaybackDelayS(0.040);
        assertEquals(0.040, buf.playbackDelayS(), 1e-9);
        buf.setPlaybackDelayS(0.001); // below floor
        assertEquals(TelemetryBuffer.MIN_PLAYBACK_DELAY_S, buf.playbackDelayS(), 1e-9);
        buf.setPlaybackDelayS(5.0); // above ceiling
        assertEquals(TelemetryBuffer.MAX_PLAYBACK_DELAY_S, buf.playbackDelayS(), 1e-9);
        buf.setPlaybackDelayS(Double.NaN); // ignored
        assertEquals(TelemetryBuffer.MAX_PLAYBACK_DELAY_S, buf.playbackDelayS(), 1e-9);

        // A shorter delay serves the same samples earlier.
        buf.setPlaybackDelayS(0.030);
        buf.addSample(0.00, sat(0f));
        buf.addSample(0.05, sat(1f));
        assertEquals(0.5f, sampleSat(buf, 0.025 + 0.030), 1e-5f);
    }

    @Test
    void emptyBufferIsSilentWithNeutralContext() {
        float[] out = sampleAll(new TelemetryBuffer(), 5.0);
        assertEquals(0f, out[TelemetryFrame.CH_SAT], 0f);
        assertEquals(0f, out[TelemetryFrame.CH_SPEED], 0f);
        assertEquals(1f, out[TelemetryFrame.CH_MU], 0f, "neutral μ, not ice");
    }
}
