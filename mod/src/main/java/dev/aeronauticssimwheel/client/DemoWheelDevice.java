package dev.aeronauticssimwheel.client;

import dev.aeronauticssimwheel.hal.Capability;
import dev.aeronauticssimwheel.hal.WheelDevice;

import java.util.EnumSet;

/**
 * Hardware-free stand-in for a wheel: axis 0 sweeps a slow sine so the whole
 * input → packet → FFB path can be exercised in-game with no device attached
 * (toggled with the demo keybind). Accepts torque writes like a real base
 * would (the HUD's FFB line shows the commanded Nm).
 */
public final class DemoWheelDevice implements WheelDevice {

    private static final double PERIOD_S = 8.0;
    private static final float AMPLITUDE = 0.6f;

    private final long startNanos = System.nanoTime();
    private volatile float steering;
    private volatile float lastTorque;
    private boolean ffbActive;

    /** Client tick: advance the sweep. */
    public void tick() {
        double t = (System.nanoTime() - startNanos) / 1e9;
        steering = AMPLITUDE * (float) Math.sin(2 * Math.PI * t / PERIOD_S);
    }

    @Override
    public String id() {
        return "demo-sine";
    }

    @Override
    public float axis(int index) {
        return index == 0 ? steering : 0f;
    }

    @Override
    public int axisCount() {
        return 1;
    }

    @Override
    public boolean button(int index) {
        return false;
    }

    @Override
    public EnumSet<Capability> capabilities() {
        return EnumSet.of(Capability.FFB_CONSTANT);
    }

    @Override
    public void ffbStart() {
        ffbActive = true;
    }

    @Override
    public void ffbUpdateTorque(float torqueNm) {
        if (ffbActive) {
            lastTorque = torqueNm;
        }
    }

    @Override
    public void ffbStop() {
        ffbActive = false;
        lastTorque = 0f;
    }

    @Override
    public void panic() {
        ffbActive = false;
        lastTorque = 0f;
    }
}
