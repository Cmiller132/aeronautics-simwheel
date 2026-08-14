package dev.aeronauticssimwheel.hal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Scriptable fake device (DESIGN.md §10.5): tests set axes/buttons and inspect
 * every FFB call the engine made. Also scriptable as a {@link
 * HardwareAngleSource} and {@link FaultingDevice} so the service's input
 * selection and fault handling are testable.
 */
public final class NullWheelDevice implements WheelDevice, HardwareAngleSource, FaultingDevice {

    private final float[] axes;
    private final boolean[] buttons;
    private final EnumSet<Capability> capabilities;

    public final List<Float> torqueWrites = new ArrayList<>();
    public boolean ffbStarted;
    public boolean ffbStopped;
    public int panicCount;

    // Scriptable live-state knobs for FfbService tests
    public boolean hardwareAngleValid;
    public float hardwareDeg;
    public float hardwareVelDegPerS;
    public boolean deviceFault;
    /** When set, the next torque write throws it (then clears). */
    public RuntimeException nextTorqueFailure;

    public NullWheelDevice(int axisCount, int buttonCount, EnumSet<Capability> capabilities) {
        this.axes = new float[axisCount];
        this.buttons = new boolean[buttonCount];
        this.capabilities = capabilities;
    }

    public static NullWheelDevice ffbCapable() {
        return new NullWheelDevice(4, 8, EnumSet.of(Capability.FFB_CONSTANT));
    }

    public void setAxis(int index, float value) {
        axes[index] = value;
    }

    public void setButton(int index, boolean down) {
        buttons[index] = down;
    }

    @Override
    public String id() {
        return "null-device";
    }

    @Override
    public float axis(int index) {
        return axes[index];
    }

    @Override
    public int axisCount() {
        return axes.length;
    }

    @Override
    public boolean button(int index) {
        return buttons[index];
    }

    @Override
    public EnumSet<Capability> capabilities() {
        return capabilities;
    }

    @Override
    public void ffbStart() {
        ffbStarted = true;
    }

    @Override
    public void ffbUpdateTorque(float torqueNm) {
        if (nextTorqueFailure != null) {
            RuntimeException e = nextTorqueFailure;
            nextTorqueFailure = null;
            throw e;
        }
        torqueWrites.add(torqueNm);
    }

    @Override
    public void ffbStop() {
        ffbStopped = true;
    }

    @Override
    public void panic() {
        panicCount++;
        torqueWrites.add(0f);
    }

    @Override
    public boolean hardwareAngleValid() {
        return hardwareAngleValid;
    }

    @Override
    public float hardwareDeg() {
        return hardwareDeg;
    }

    @Override
    public float hardwareVelDegPerS() {
        return hardwareVelDegPerS;
    }

    @Override
    public boolean deviceFault() {
        return deviceFault;
    }
}
