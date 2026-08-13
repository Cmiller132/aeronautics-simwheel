package dev.aeronauticssimwheel.hal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AxisProcessorTest {

    private static final double DT = 0.016;

    @Test
    void identityPassesThrough() {
        AxisProcessor p = new AxisProcessor(AxisProcessor.Config.identity());
        assertEquals(0.5f, p.process(0.5f, DT), 1e-6f);
        assertEquals(-1f, p.process(-1f, DT), 1e-6f);
        assertEquals(0f, p.process(0f, DT), 1e-6f);
    }

    @Test
    void calibrationMapsAsymmetricRange() {
        // A pedal reporting 0.2 (rest) to 0.9 (floor)
        AxisProcessor p = new AxisProcessor(new AxisProcessor.Config(
                -0.5f, 0.2f, 0.9f, 0f, 0f, false, 0f));
        assertEquals(0f, p.process(0.2f, DT), 1e-6f);
        assertEquals(1f, p.process(0.9f, DT), 1e-6f);
        assertEquals(1f, p.process(1.2f, DT), 1e-6f, "clamps beyond calibration");
        assertEquals(0.5f, p.process(0.55f, DT), 1e-5f);
    }

    @Test
    void deadzoneZeroesSmallInputsAndRescales() {
        AxisProcessor p = new AxisProcessor(new AxisProcessor.Config(
                -1f, 0f, 1f, 0.1f, 0f, false, 0f));
        assertEquals(0f, p.process(0.05f, DT), 1e-6f);
        assertEquals(0f, p.process(-0.09f, DT), 1e-6f);
        assertEquals(1f, p.process(1f, DT), 1e-6f, "full deflection stays full");
        assertTrue(p.process(0.11f, DT) > 0f, "just past deadzone is nonzero");
    }

    @Test
    void expoPreservesEndpointsAndSoftensCenter() {
        AxisProcessor p = new AxisProcessor(new AxisProcessor.Config(
                -1f, 0f, 1f, 0f, 0.5f, false, 0f));
        assertEquals(1f, p.process(1f, DT), 1e-6f);
        assertEquals(-1f, p.process(-1f, DT), 1e-6f);
        assertTrue(Math.abs(p.process(0.5f, DT)) < 0.5f, "expo softens mid-range");
    }

    @Test
    void inversionFlipsSign() {
        AxisProcessor p = new AxisProcessor(new AxisProcessor.Config(
                -1f, 0f, 1f, 0f, 0f, true, 0f));
        assertEquals(-0.5f, p.process(0.5f, DT), 1e-6f);
    }

    @Test
    void smoothingConvergesWithoutOvershoot() {
        AxisProcessor p = new AxisProcessor(new AxisProcessor.Config(
                -1f, 0f, 1f, 0f, 0f, false, 5f));
        float v = p.process(0f, DT);
        for (int i = 0; i < 200; i++) {
            float next = p.process(1f, DT);
            assertTrue(next >= v - 1e-6f && next <= 1f + 1e-6f, "no overshoot");
            v = next;
        }
        assertEquals(1f, v, 1e-2f, "converges to input");
    }
}
