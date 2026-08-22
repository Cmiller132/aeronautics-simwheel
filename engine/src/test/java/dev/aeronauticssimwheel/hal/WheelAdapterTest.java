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
    void pedalsUseFullRawTravel() {
        // The feel-review regression: pedals resting at raw −1 used to lose
        // half their travel through a bipolar map + negative clamp. Pedal
        // shaping maps the full raw range onto 0..1.
        NullWheelDevice dev = NullWheelDevice.ffbCapable();
        WheelAdapter a = WheelAdapter.autoBind(dev);

        dev.setAxis(1, -1f); // at rest
        settle(a);
        assertEquals(0f, a.unipolar(LogicalAxis.THROTTLE), 1e-4f);

        dev.setAxis(1, 0f); // halfway
        settle(a);
        assertEquals(0.49f, a.unipolar(LogicalAxis.THROTTLE), 0.02f);

        dev.setAxis(1, 1f); // full press
        settle(a);
        assertEquals(1f, a.unipolar(LogicalAxis.THROTTLE), 1e-3f);
    }

    @Test
    void invertedPedalIdlesHigh() {
        NullWheelDevice dev = NullWheelDevice.ffbCapable();
        WheelAdapter a = new WheelAdapter();
        WheelAdapter.bindPedal(a, LogicalAxis.BRAKE, dev, 2, true);
        dev.setAxis(2, 1f); // inverted pedal: raw +1 IS rest
        settle(a);
        assertEquals(0f, a.unipolar(LogicalAxis.BRAKE), 1e-4f);
        dev.setAxis(2, -1f);
        settle(a);
        assertEquals(1f, a.unipolar(LogicalAxis.BRAKE), 1e-3f);
    }

    @Test
    void bindPedalIgnoresOutOfRangeAxes() {
        NullWheelDevice dev = NullWheelDevice.ffbCapable(); // 3 axes
        WheelAdapter a = new WheelAdapter();
        WheelAdapter.bindPedal(a, LogicalAxis.CLUTCH, dev, 7, false);
        assertFalse(a.isBound(LogicalAxis.CLUTCH), "axis 7 doesn't exist on this device");
    }

    /** Run the 8 Hz pedal smoothing to steady state. */
    private static void settle(WheelAdapter a) {
        for (int i = 0; i < 60; i++) {
            a.poll(DT);
        }
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
