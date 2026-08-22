package dev.aeronauticssimwheel.ffb;

import dev.aeronauticssimwheel.ffb.StrikeDetector.Config;
import dev.aeronauticssimwheel.ffb.StrikeDetector.Strike;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrikeDetectorTest {

    private static final double DT = 0.025; // 40 Hz substeps

    @Test
    void firesOnceOnASpikeThenRearmsOnlyAfterRelease() {
        StrikeDetector det = new StrikeDetector(Config.defaults());

        assertNull(det.step(0.2, 1, DT), "gentle motion must not fire");
        Strike s = det.step(2.0, 1, DT);
        assertNotNull(s, "spike above threshold must fire");
        assertTrue(s.peakNm() > 0);

        // Still compressing hard: hysteresis holds fire.
        assertNull(det.step(2.0, 1, DT));
        assertNull(det.step(0.8, 1, DT), "above half-threshold: not re-armed yet");

        // Release below half threshold, then a new spike fires again.
        assertNull(det.step(0.1, 1, DT));
        for (int i = 0; i < 4; i++) {
            det.step(0.0, 1, DT); // let the min-interval elapse
        }
        assertNotNull(det.step(2.0, 1, DT));
    }

    @Test
    void strikesAreSignedByOriginatingSide() {
        StrikeDetector det = new StrikeDetector(new Config(1.0, 1.0, 3.0, 0.0));
        Strike right = det.step(2.0, +1, DT);
        assertNotNull(right);
        assertTrue(right.peakNm() > 0, "right-side strike kicks clockwise");

        det.reset();
        Strike left = det.step(2.0, -1, DT);
        assertNotNull(left);
        assertTrue(left.peakNm() < 0, "left-side strike kicks counterclockwise");
        assertEquals(-right.peakNm(), left.peakNm(), 1e-6, "sides must mirror");

        det.reset();
        Strike center = det.step(2.0, 0, DT);
        assertNotNull(center);
        assertTrue(center.peakNm() > 0, "centerline strikes still render (positive)");
    }

    @Test
    void minIntervalBoundsTheEventRate() {
        StrikeDetector det = new StrikeDetector(new Config(1.0, 1.0, 3.0, 0.05));
        int fired = 0;
        // 1 second of pathological oscillation across the threshold at 40 Hz.
        for (int i = 0; i < 40; i++) {
            if (det.step(i % 2 == 0 ? 5.0 : 0.0, 1, DT) != null) {
                fired++;
            }
        }
        assertTrue(fired <= 21, "min interval must cap the rate, fired " + fired);
        assertTrue(fired >= 5, "legitimate repeated strikes must still fire, fired " + fired);
    }

    @Test
    void peakIsCappedAndFiniteInputsRequired() {
        StrikeDetector det = new StrikeDetector(new Config(1.0, 1.0, 3.0, 0.0));
        Strike cliff = det.step(500.0, 1, DT);
        assertNotNull(cliff);
        assertEquals(3.0f, cliff.peakNm(), 1e-6, "cliff fall is capped, not a wrist snap");

        det.reset();
        assertNull(det.step(Double.NaN, 1, DT));
        assertNull(det.step(Double.POSITIVE_INFINITY, 1, DT), "infinite rate must not fire");
    }
}
