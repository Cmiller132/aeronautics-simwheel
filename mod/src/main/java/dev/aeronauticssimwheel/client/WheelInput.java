package dev.aeronauticssimwheel.client;

import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.hal.WheelAdapter;
import dev.aeronauticssimwheel.hal.WheelDevice;
import dev.aeronauticssimwheel.hal.glfw.GlfwWheelDevice;
import org.lwjgl.glfw.GLFW;

/**
 * Owns the active input device + adapter. Rescans GLFW joysticks (Minecraft's
 * own LWJGL, render thread) until one appears; the demo keybind swaps in a
 * {@link DemoWheelDevice} for hardware-free testing. autoBind defaults apply —
 * per-device mapping config comes later (DESIGN.md §5.1).
 */
public final class WheelInput {

    private static final int RESCAN_TICKS = 20;

    private GlfwWheelDevice glfw;
    private DemoWheelDevice demo;
    private WheelAdapter adapter;
    private int rescanCooldown;

    /** Client tick (render thread — GLFW calls are legal here). */
    public void tick() {
        if (demo == null && (glfw == null || !glfw.present()) && --rescanCooldown <= 0) {
            rescanCooldown = RESCAN_TICKS;
            scan();
        }
        if (demo == null && glfw != null && glfw.present()) {
            glfw.poll();
        }
        if (demo != null) {
            demo.tick();
        }
        if (adapter != null) {
            adapter.poll(1 / 20.0);
        }
    }

    private void scan() {
        for (int jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
            if (GLFW.glfwJoystickPresent(jid)) {
                glfw = new GlfwWheelDevice(jid);
                adapter = WheelAdapter.autoBind(glfw);
                AeronauticsSimwheel.LOGGER.info("SimWheel input device: {}", glfw.id());
                return;
            }
        }
    }

    /** Demo keybind: swap the sine-sweep device in/out. */
    public void toggleDemo() {
        if (demo == null) {
            demo = new DemoWheelDevice();
            adapter = WheelAdapter.autoBind(demo);
            AeronauticsSimwheel.LOGGER.info("SimWheel demo input ON");
        } else {
            demo = null;
            adapter = (glfw != null && glfw.present()) ? WheelAdapter.autoBind(glfw) : null;
            AeronauticsSimwheel.LOGGER.info("SimWheel demo input OFF");
        }
    }

    public boolean hasInput() {
        return adapter != null;
    }

    public WheelAdapter adapter() {
        return adapter;
    }

    /** The device torque writes should go to (null when nothing is attached). */
    public WheelDevice activeDevice() {
        return demo != null ? demo : glfw;
    }

    public String label() {
        WheelDevice d = activeDevice();
        return d == null ? "no device (K = demo input)" : d.id();
    }
}
