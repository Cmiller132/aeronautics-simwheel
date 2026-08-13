package dev.aeronauticssimwheel.hal;

import java.util.EnumSet;

/**
 * Device abstraction (DESIGN.md §5.1). Implementations: {@code GlfwWheelDevice}
 * (input only, Phase 1), {@code Sdl3WheelDevice} (input + constant-force FFB,
 * Phase 2), {@link NullWheelDevice} (scriptable fake for tests).
 *
 * <p>This package must never import Minecraft/NeoForge classes.
 */
public interface WheelDevice {

    /** Stable identity: GUID + name + axis count — survives USB port changes. */
    String id();

    /** Normalized raw axis value in -1..1 (calibration/curves live in {@link AxisProcessor}). */
    float axis(int index);

    int axisCount();

    boolean button(int index);

    EnumSet<Capability> capabilities();

    // --- FFB (throw or no-op unless FFB_CONSTANT is advertised) ---

    /** Create the single infinite constant-force effect at gain 0. Never recreated mid-session. */
    void ffbStart();

    /** Update the constant-force effect in place. {@code normalized} is -1..1 of device max torque. */
    void ffbUpdateTorque(float normalized);

    /** Stop and destroy the effect (device change / quit). */
    void ffbStop();

    /** Best-effort immediate zero: stop-all-effects. Wired to every abnormal path (§7). */
    void panic();
}
