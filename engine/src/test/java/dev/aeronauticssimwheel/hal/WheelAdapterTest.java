package dev.aeronauticssimwheel.hal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelAdapterTest {

    private static final double DT = 0.016;

    @Test
    void autoBindMapsGenericWheel() {
        NullWheelDevice dev = NullWheelDevice.ffbCapable();
        WheelAdapter a = WheelAdapter.autoBind(dev);
        dev.setAxis(0, 0.5f);
        a.poll(DT);
        assertEquals(0.5f, a.value(LogicalAxis.STEERING), 1e-6f);
        assertEquals(45f, a.steeringDeg(90f), 1e-4f);
    }

    @Test
    void axesBindAcrossSeparateDevices() {
        // MOZA base + standalone pedals: steering and throttle on different devices
        NullWheelDevice base = NullWheelDevice.ffbCapable();
        NullWheelDevice pedals = new NullWheelDevice(3, 0, java.util.EnumSet.noneOf(Capability.class));
        WheelAdapter a = new WheelAdapter();
        a.bindAxis(LogicalAxis.STEERING, base, 0, new AxisProcessor(AxisProcessor.Config.identity()));
        a.bindAxis(LogicalAxis.THROTTLE, pedals, 1, new AxisProcessor(AxisProcessor.Config.identity()));
        base.setAxis(0, -0.25f);
        pedals.setAxis(1, 0.8f);
        a.poll(DT);
        assertEquals(-0.25f, a.value(LogicalAxis.STEERING), 1e-6f);
        assertEquals(0.8f, a.unipolar(LogicalAxis.THROTTLE), 1e-6f);
    }

    @Test
    void unipolarClampsNegative() {
        NullWheelDevice dev = NullWheelDevice.ffbCapable();
        WheelAdapter a = WheelAdapter.autoBind(dev);
        dev.setAxis(1, -0.5f);
        a.poll(DT);
        a.poll(DT); // let smoothing settle a little
        assertEquals(0f, a.unipolar(LogicalAxis.THROTTLE), 1e-6f);
    }

    @Test
    void buttonEdgeFiresOncePerPress() {
        NullWheelDevice dev = NullWheelDevice.ffbCapable();
        WheelAdapter a = WheelAdapter.autoBind(dev);
        a.poll(DT);
        assertFalse(a.wasPressed(LogicalButton.ENGAGE));
        dev.setButton(0, true);
        a.poll(DT);
        assertTrue(a.wasPressed(LogicalButton.ENGAGE));
        a.poll(DT);
        assertFalse(a.wasPressed(LogicalButton.ENGAGE), "held button must not re-fire");
        assertTrue(a.isDown(LogicalButton.ENGAGE));
        dev.setButton(0, false);
        a.poll(DT);
        dev.setButton(0, true);
        a.poll(DT);
        assertTrue(a.wasPressed(LogicalButton.ENGAGE), "re-press fires again");
    }

    @Test
    void unboundAxisReadsZero() {
        WheelAdapter a = new WheelAdapter();
        a.poll(DT);
        assertEquals(0f, a.value(LogicalAxis.CLUTCH));
        assertFalse(a.isBound(LogicalAxis.CLUTCH));
    }
}
