package dev.aeronauticssimwheel.ffb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualWheelPredictorTest {

    @Test
    void slewsTowardTargetAtKineticRate() {
        VirtualWheelPredictor p = new VirtualWheelPredictor();
        p.setCommandedTarget(96.0);
        for (int i = 0; i < 500; i++) {
            p.step(0.001); // 0.5 s total
        }
        assertEquals(48.0, p.angleDeg(), 0.1, "96°/s for 0.5 s = 48°");
    }

    @Test
    void neverOvershootsTarget() {
        VirtualWheelPredictor p = new VirtualWheelPredictor();
        p.setCommandedTarget(10.0);
        for (int i = 0; i < 5000; i++) {
            p.step(0.001);
            assertTrue(p.angleDeg() <= 10.0 + 1e-9, "overshoot at " + p.angleDeg());
        }
        assertEquals(10.0, p.angleDeg(), 1e-9);
    }

    @Test
    void snapsOnLargeTelemetryDisagreement() {
        VirtualWheelPredictor p = new VirtualWheelPredictor();
        p.onMeasurement(40.0); // way off the predictor's 0°
        assertEquals(40.0, p.angleDeg(), 1e-9);
    }

    @Test
    void correctsGentlyOnSmallDisagreement() {
        VirtualWheelPredictor p = new VirtualWheelPredictor();
        p.onMeasurement(2.0); // small error: gain-corrected, not snapped
        assertEquals(0.4, p.angleDeg(), 1e-9);
        // Repeated measurements converge
        for (int i = 0; i < 50; i++) {
            p.onMeasurement(2.0);
        }
        assertEquals(2.0, p.angleDeg(), 1e-3);
    }
}
