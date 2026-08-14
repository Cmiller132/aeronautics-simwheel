# hal/ — the device layer

Hardware abstraction for wheels and pedals. This package (like all of
`engine/`) never imports Minecraft; the GLFW backend wraps the joystick API
that happens to ship inside Minecraft's LWJGL, but through plain LWJGL
classes.

## Core abstractions

| File | Role |
|---|---|
| `WheelDevice.java` | The device interface: axes, buttons, FFB capability, `ffbUpdateTorque(float nm)` — **torque is Nm**; each backend owns its own conversion and clamps. |
| `WheelAdapter.java` | Maps a device (or several) to the mod's logical inputs. Each logical axis binds independently to (device, axisIndex, processing) — never "one device owns everything", because MOZA pedals may enumerate through the base or standalone. |
| `AxisProcessor.java` | Per-binding processing: calibration (min/center/max), deadzone, expo curve, inversion, one-pole smoothing. |
| `LogicalAxis.java` / `LogicalButton.java` | The logical input vocabulary (`STEERING`, `THROTTLE`, `BRAKE`, `CLUTCH`; `ENGAGE`, `DETENT_MODIFIER`, `BTN_1..8`). |
| `Capability.java` | FFB capabilities a device may advertise (`FFB_CONSTANT`, …). |

## Capability interfaces (checked by `FfbService` at runtime)

| File | Role |
|---|---|
| `HardwareAngleSource.java` | A device that reports the physical wheel's angle/velocity at its own native rate. The FFB loop prefers this over the 20 Hz game snapshot — it's what makes damper/friction/soft-lock react to the *physical* wheel (and lets the soft lock see a wheel pushed past the range). |
| `FaultingDevice.java` | A device whose backend can report output-path integrity loss (e.g. the sidecar's `FLAG_FAULT`). A rising fault edge = panic: torque stops until a deliberate re-engage. |

## Backends

| File | Role |
|---|---|
| `glfw/GlfwWheelDevice.java` | Input-only backend over LWJGL's GLFW joystick API. `poll()` must run on the render thread (GLFW requirement); readers get the cached snapshot. Polls once at construction so axis counts are valid before auto-binding. |
| `bridge/BridgeProtocol.java` | The AWFB wire protocol (UDP localhost, little-endian, magic `AWFB`): HELLO/START/STOP/TORQUE/STATE/PANIC. Decode is total — malformed input returns empty, never throws. Mirrored byte-for-byte by `sidecar/src/protocol.rs`, pinned by golden vectors on both sides. |
| `bridge/BridgeWheelDevice.java` | The full-duplex backend over the sidecar: STATE stream is the input (angle/vel/buttons at 250 Hz — implements both capability interfaces above), TORQUE frames are the output (Nm, carrying the mod's cap + watchdog interval). Validates HELLO's rated torque (0.5–30 Nm) before trusting it. |
| `NullWheelDevice.java` | Scriptable fake for tests: set axes/buttons/hardware-angle/fault fields, inspect every FFB call the engine made, schedule a one-shot torque-write failure. |

## Who talks to what

```
render thread ──poll──► GlfwWheelDevice ─┐
sidecar UDP  ──STATE──► BridgeWheelDevice ├─► WheelAdapter ─► input snapshot
                                          │        (AxisProcessor per axis)
FfbService (250 Hz) ──ffbUpdateTorque(Nm)─┘  (+ HardwareAngleSource reads)
```

Tests: `engine/src/test/java/dev/aeronauticssimwheel/hal/` — adapter +
processor units, bridge protocol fuzz/golden vectors, bridge device
behavior, and the cross-language `SidecarConformanceTest` (see
[`engine/README.md`](../../../../../../README.md)).
