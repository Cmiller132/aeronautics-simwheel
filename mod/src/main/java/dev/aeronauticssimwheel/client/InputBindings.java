package dev.aeronauticssimwheel.client;

import java.util.Locale;

/**
 * Pedal-axis bindings from the feel config (hot-reloaded): which device axis
 * each pedal reads and whether it idles high. Steering is always the primary
 * device's axis 0 (the bridge STATE stream / GLFW axis 0).
 *
 * <p>Axis values: {@link #AUTO} resolves by device shape at bind time — a
 * dedicated pedal set (its own USB device, e.g. a standalone Simagic P700 or
 * MOZA SR-P) starts its axes at 0 (throttle 0 / brake 1 / clutch 2), while
 * pedals that share the wheel's device sit after its steering axis
 * (1 / 2 / 3). −1 disables a pedal; an explicit index (≥ 0) always wins.
 *
 * <p>{@code pedalDevice} is a case-insensitive substring matched against GLFW
 * joystick names to pick which device the pedals read from when several are
 * attached (empty = automatic: prefer a device whose name says "pedal").
 */
public record InputBindings(int throttleAxis, int brakeAxis, int clutchAxis,
                            boolean throttleInvert, boolean brakeInvert, boolean clutchInvert,
                            String pedalDevice) {

    /** Axis sentinel: resolve by device shape at bind time. */
    public static final int AUTO = -2;

    public static InputBindings defaults() {
        return new InputBindings(AUTO, AUTO, AUTO, false, false, false, "");
    }

    /** Resolve one configured axis: AUTO → shape default, else as configured. */
    public static int resolveAxis(int configured, int autoDefault) {
        return configured == AUTO ? autoDefault : configured;
    }

    /** True when {@code deviceId} names a dedicated pedal set. */
    public boolean matchesPedalDevice(String deviceId) {
        if (deviceId == null) {
            return false;
        }
        String id = deviceId.toLowerCase(Locale.ROOT);
        if (!pedalDevice.isBlank()) {
            return id.contains(pedalDevice.toLowerCase(Locale.ROOT));
        }
        return id.contains("pedal");
    }
}
