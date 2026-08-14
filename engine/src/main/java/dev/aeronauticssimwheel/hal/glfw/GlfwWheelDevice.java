package dev.aeronauticssimwheel.hal.glfw;

import dev.aeronauticssimwheel.hal.Capability;
import dev.aeronauticssimwheel.hal.WheelDevice;
import org.lwjgl.glfw.GLFW;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.EnumSet;

/**
 * Phase 1 input-only backend (DESIGN.md §5.1). Wraps the GLFW joystick API that
 * ships inside Minecraft's LWJGL. {@link #poll()} must run on the render thread
 * (GLFW main-thread requirement); readers get the cached snapshot.
 */
public final class GlfwWheelDevice implements WheelDevice {

    private final int jid;
    private final String id;

    private volatile float[] axes = new float[0];
    private volatile boolean[] buttons = new boolean[0];

    /** Render-thread only (constructor polls GLFW). */
    public GlfwWheelDevice(int jid) {
        this.jid = jid;
        String guid = GLFW.glfwGetJoystickGUID(jid);
        String name = GLFW.glfwGetJoystickName(jid);
        // Populate axes/buttons NOW: WheelAdapter.autoBind reads axisCount()
        // immediately after construction, and empty arrays here meant pedals
        // never bound until a device replug (found by review).
        poll();
        this.id = guid + "/" + name + "/" + axes.length;
    }

    /** Render-thread only. Snapshots axes and buttons for the other threads. */
    public void poll() {
        FloatBuffer a = GLFW.glfwGetJoystickAxes(jid);
        if (a != null) {
            float[] out = new float[a.remaining()];
            a.get(out);
            axes = out;
        }
        ByteBuffer b = GLFW.glfwGetJoystickButtons(jid);
        if (b != null) {
            boolean[] out = new boolean[b.remaining()];
            for (int i = 0; i < out.length; i++) {
                out[i] = b.get(i) == GLFW.GLFW_PRESS;
            }
            buttons = out;
        }
    }

    public boolean present() {
        return GLFW.glfwJoystickPresent(jid);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public float axis(int index) {
        float[] a = axes;
        return index < a.length ? a[index] : 0f;
    }

    @Override
    public int axisCount() {
        return axes.length;
    }

    @Override
    public boolean button(int index) {
        boolean[] b = buttons;
        return index < b.length && b[index];
    }

    @Override
    public EnumSet<Capability> capabilities() {
        return EnumSet.noneOf(Capability.class); // GLFW has no FFB API at all
    }

    @Override
    public void ffbStart() {
        throw new UnsupportedOperationException("GLFW backend is input-only");
    }

    @Override
    public void ffbUpdateTorque(float torqueNm) {
        throw new UnsupportedOperationException("GLFW backend is input-only");
    }

    @Override
    public void ffbStop() {
        // no-op: nothing to stop
    }

    @Override
    public void panic() {
        // no-op: no effects exist on this backend
    }
}
