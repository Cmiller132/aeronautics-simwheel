# client/ — input in, torque out, everything visible

The client wiring: hardware → input snapshot → packets to the server, and
telemetry/events → the engine's FFB stack → the device. Every class here is
an adapter; the engine does the thinking.

## Files

| File | Role |
|---|---|
| `WheelInput.java` | Owns the active input device + adapter, priority: **demo** (K toggle) → **bridge** (sidecar running: STATE is input *and* torque path — one device, one truth) → **GLFW** (input only). Handles device rescan/reconnect; keyboard axes are always merged in. |
| `SimWheelLink.java` | The engage/send loop: sit + look + ENGAGE latches a wheel; each tick sends `SimWheelInputPacket` on change (>0.5 % any axis, any button edge) plus a 10-tick heartbeat; every disengage path (seat exit, GUI, focus loss, logout) sends the neutral frame. |
| `FfbController.java` | Thin adapter around `FfbService` + `FfbPipeline`: game tick → input snapshot (commanded angle + measured-dt velocity), packets → pipeline ingress, engine callbacks → log lines, tuning/stats accessors for the HUD. No math. |
| `FeelConfig.java` | Hot-reload tuning: `config/aeronautics_simwheel-feel.toml` (nightconfig), written with commented defaults on first run, mtime-polled ~1 Hz, range-clamped via `FfbTuning.sanitized()`; a parse error keeps the last good tuning and reports on the HUD. |
| `SimWheelHud.java` | Debug HUD (top-left while engaged): device, commanded vs. in-game angle, safety-chain + telemetry torque, FFB loop rate/jitter (`loop … Hz late …`), clamp/gain readout, config status. |
| `DemoWheelDevice.java` | The K-key fake: axis sine sweep so the entire chain runs with no hardware; accepts torque writes like a real base (HUD shows them). |
| `SimWheelClient.java` | Bootstrap: keybinds (J engage, K demo), tick wiring, HUD registration, config init, FFB thread lifecycle (shutdown hook included). |

## Threading picture

```
render thread: WheelInput.poll → SimWheelLink.tick → FfbController.updateFromGame
                                                   (publishes immutable snapshot)
network thread: packets → rate gate → main thread → FfbController.onTelemetry/onEvent
FFB thread (engine FfbService, 250 Hz): pipeline.step → device.ffbUpdateTorque(Nm)
```

The FFB thread never touches Minecraft objects; everything crosses as
values. Engage edges are derived on the FFB thread (DESIGN.md §3).
