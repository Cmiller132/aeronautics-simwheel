package dev.aeronauticssimwheel.hal;

/**
 * A device whose backend can report a fault (e.g. the bridge sidecar's STATE
 * FLAG_FAULT: a failed effect write, a quarantined effect, a lost device). The
 * FFB loop treats a rising fault edge as a panic — output-path integrity is
 * unknown, so torque stops until a deliberate re-engage (DESIGN.md §7).
 */
public interface FaultingDevice {

    /** True while the device/backend reports an output-path fault. */
    boolean deviceFault();
}
