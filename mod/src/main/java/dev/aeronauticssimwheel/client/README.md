# client/ — input in, torque out, everything visible

The client wiring: hardware → input snapshot → packets to the server, and
telemetry/events → the engine's FFB stack → the device. Every class here is
an adapter; the engine does the thinking.

## Files

| File | Role |
|---|---|
| `WheelInput.java` | Owns the input devices + adapter. Devices **compose** per axis: **demo** (K toggle) → **bridge** (sidecar running: STATE is steering input *and* torque path) **+ GLFW pedals into the same adapter** (the bridge carries no pedal axes) → **GLFW alone** (steering + pedals, no FFB) → **pedals alone** (steering stays on keyboard). Scans ALL joysticks and separates a standalone pedal set (Simagic P700, MOZA SR-P…) from the wheelbase by `bindings.pedalDevice` substring, then "pedal" in the name, then shape (axes, zero buttons); AUTO axes count from 0 on a dedicated set, 1/2/3 on a shared wheel device. Pedal axes/inversion hot-reload from the feel config's `[bindings]`. Handles rescan/reconnect, re-arms a disarmed bridge (v2 FLAG_ARMED); keyboard axes are always merged in. |
| `SimWheelLink.java` | The engage/send loop: sit + look + ENGAGE latches a wheel; each tick sends `SimWheelInputPacket` on change (>0.5 % any axis, any button edge) plus a 10-tick heartbeat; every disengage path (seat exit, GUI, focus loss, logout) sends the neutral frame. |
| `FfbController.java` | Thin adapter around `FfbService` + `FfbPipeline`: game tick → input snapshot (commanded angle + measured-dt velocity), packets → pipeline ingress, engine callbacks → log lines, tuning/stats accessors for the HUD. No math. |
| `FeelConfig.java` | Hot-reload tuning: `config/aeronautics_simwheel-feel.toml` (nightconfig), written with commented defaults on first run, mtime-polled ~1 Hz, range-clamped via `FfbTuning.sanitized()`; a parse error keeps the last good tuning and reports on the HUD. Also carries the pedal `[bindings]` (device axes + inversion). |
| `InputBindings.java` | The pedal-binding record the config parses and `WheelInput` applies: axes (AUTO −2 / off −1 / explicit), inversion, and the `pedalDevice` name match. |
| `SimWheelHud.java` | Debug HUD (top-left while engaged): device, commanded vs. in-game angle, safety-chain torque + per-component breakdown (sat/tex/synth/rumble/damper/friction/impulse/lock), frame context (speed/slip/μ/rpm + playback delay), FFB loop rate/jitter, clamp/gain readout, config status, test-signal mode. |
| `DemoWheelDevice.java` | The K-key fake: axis sine sweep so the entire chain runs with no hardware; accepts torque writes like a real base (HUD shows them). |
| `SimWheelClient.java` | Bootstrap: keybinds (J engage, K demo, L test signal), tick wiring, HUD registration, config init, FFB thread lifecycle (JVM shutdown hook calls `stopAndJoin` so the device detaches on exit). |

## Threading picture

```
render thread: WheelInput.poll → SimWheelLink.tick → FfbController.updateFromGame
                                                   (publishes immutable snapshot)
network thread: packets → rate gate → main thread → FfbController.onTelemetry/onEvent
FFB thread (engine FfbService, 250 Hz): pipeline.step → device.ffbUpdateTorque(Nm)
```

The FFB thread never touches Minecraft objects; everything crosses as
values. Engage edges are derived on the FFB thread (DESIGN.md §3).
