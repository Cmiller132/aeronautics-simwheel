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

        assertNull(det.step(0.2, DT), "gentle motion must not fire");
        Strike s = det.step(2.0, DT);
        assertNotNull(s, "spike above threshold must fire");
        assertTrue(s.peakNm() > 0);

        // Still compressing hard: hysteresis holds fire.
        assertNull(det.step(2.0, DT));
        assertNull(det.step(0.8, DT), "above half-threshold: not re-armed yet");

        // Release below half threshold, then a new spike fires again.
        assertNull(det.step(0.1, DT));
        for (int i = 0; i < 4; i++) {
            det.step(0.0, DT); // let the min-interval elapse
        }
        assertNotNull(det.step(2.0, DT));
    }

    @Test
    void minIntervalBoundsTheEventRate() {
        StrikeDetector det = new StrikeDetector(new Config(1.0, 1.0, 3.0, 0.05));
        int fired = 0;
        // 1 second of pathological oscillation across the threshold at 40 Hz.
        for (int i = 0; i < 40; i++) {
            if (det.step(i % 2 == 0 ? 5.0 : 0.0, DT) != null) {
                fired++;
            }
        }
        assertTrue(fired <= 21, "min interval must cap the rate, fired " + fired);
        assertTrue(fired >= 5, "legitimate repeated strikes must still fire, fired " + fired);
    }

    @Test
    void peakIsCappedAndFiniteInputsRequired() {
        StrikeDetector det = new StrikeDetector(new Config(1.0, 1.0, 3.0, 0.0));
        Strike cliff = det.step(500.0, DT);
        assertNotNull(cliff);
        assertEquals(3.0f, cliff.peakNm(), 1e-6, "cliff fall is capped, not a wrist snap");

        det.reset();
        assertNull(det.step(Double.NaN, DT));
        assertNull(det.step(Double.POSITIVE_INFINITY, DT), "infinite rate must not fire");
    }
}
