package dev.aeronauticssimwheel.hal;

import java.util.EnumMap;
import java.util.Map;

/**
 * Light adapter from a generic wheel + pedals to the mod's logical inputs
 * (DESIGN.md §5.1). Each logical axis binds independently to
 * (device, axisIndex, processing) — never "one device owns everything", because
 * MOZA pedals may enumerate through the base or standalone.
 *
 * <p>Call {@link #poll(double)} once per frame after the devices refreshed;
 * readers then get processed values. Pure JVM — testable with
 * {@link NullWheelDevice}.
 */
public final class WheelAdapter {

    public record AxisBinding(WheelDevice device, int axisIndex, AxisProcessor processor) {
    }

    public record ButtonBinding(WheelDevice device, int buttonIndex) {
    }

    private final Map<LogicalAxis, AxisBinding> axes = new EnumMap<>(LogicalAxis.class);
    private final Map<LogicalButton, ButtonBinding> buttons = new EnumMap<>(LogicalButton.class);
    private final Map<LogicalAxis, Float> values = new EnumMap<>(LogicalAxis.class);
    private final Map<LogicalButton, Boolean> down = new EnumMap<>(LogicalButton.class);
    private final Map<LogicalButton, Boolean> pressedEdge = new EnumMap<>(LogicalButton.class);

    public void bindAxis(LogicalAxis axis, WheelDevice device, int axisIndex, AxisProcessor processor) {
        axes.put(axis, new AxisBinding(device, axisIndex, processor));
    }

    public void bindButton(LogicalButton button, WheelDevice device, int buttonIndex) {
        buttons.put(button, new ButtonBinding(device, buttonIndex));
    }

    /**
     * Sane defaults for an unconfigured generic wheel: axis 0 = steering (raw,
     * no deadzone — direct-drive bases are precise), axis 1 = throttle pedal,
     * axis 2 = brake if present; button 0 = ENGAGE, button 1 = DETENT_MODIFIER.
     * Real per-device mapping (MOZA R9 + pedals) comes from the config UI.
     */
    public static WheelAdapter autoBind(WheelDevice device) {
        WheelAdapter a = new WheelAdapter();
        a.bindAxis(LogicalAxis.STEERING, device, 0,
                new AxisProcessor(AxisProcessor.Config.identity()));
        if (device.axisCount() > 1) {
            a.bindAxis(LogicalAxis.THROTTLE, device, 1, new AxisProcessor(
                    new AxisProcessor.Config(-1f, 0f, 1f, 0.02f, 0f, false, 8f)));
        }
        if (device.axisCount() > 2) {
            a.bindAxis(LogicalAxis.BRAKE, device, 2, new AxisProcessor(
                    new AxisProcessor.Config(-1f, 0f, 1f, 0.02f, 0f, false, 8f)));
        }
        a.bindButton(LogicalButton.ENGAGE, device, 0);
        a.bindButton(LogicalButton.DETENT_MODIFIER, device, 1);
        return a;
    }

    /** Sample every bound axis/button through its processing. Call once per frame. */
    public void poll(double dtSeconds) {
        for (Map.Entry<LogicalAxis, AxisBinding> e : axes.entrySet()) {
            AxisBinding b = e.getValue();
            values.put(e.getKey(), b.processor().process(b.device().axis(b.axisIndex()), dtSeconds));
        }
        for (Map.Entry<LogicalButton, ButtonBinding> e : buttons.entrySet()) {
            boolean now = e.getValue().device().button(e.getValue().buttonIndex());
            boolean was = down.getOrDefault(e.getKey(), false);
            pressedEdge.put(e.getKey(), now && !was);
            down.put(e.getKey(), now);
        }
    }

    /** Processed bipolar value, -1..1. Unbound axes read 0. */
    public float value(LogicalAxis axis) {
        return values.getOrDefault(axis, 0f);
    }

    /** Pedal-style value, 0..1 (negative half clamped off). */
    public float unipolar(LogicalAxis axis) {
        return Math.max(0f, value(axis));
    }

    /** Steering mapped to wheel degrees for a given in-game limit (±limit). */
    public float steeringDeg(float wheelLimitDeg) {
        return value(LogicalAxis.STEERING) * wheelLimitDeg;
    }

    public boolean isDown(LogicalButton button) {
        return down.getOrDefault(button, false);
    }

    /** True exactly once per press (rising edge on the last {@link #poll}). */
    public boolean wasPressed(LogicalButton button) {
        return pressedEdge.getOrDefault(button, false);
    }

    public boolean isBound(LogicalAxis axis) {
        return axes.containsKey(axis);
    }
}
