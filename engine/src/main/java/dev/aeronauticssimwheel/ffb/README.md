# ffb/ — the torque math

Everything between "the game said X" and "write this many Nm to the device".
Two classes are the spine; the rest are the components they compose.

## The spine

| File | Role |
|---|---|
| `FfbPipeline.java` | **The one shipping composition** (DESIGN.md §6.5): telemetry + soft lock + damper/friction + strike impulses → soft-knee mixer → SafetyChain. The mod, the unit tests, and the sim harness all run this exact class. Owns ingress hygiene (clamps/drops hostile values), the server↔client clock mapping (EMA offset), engage/disengage edges, and tuning swaps. FAULT survives everything except a deliberate disengage → re-engage. |
| `FfbService.java` | **The 250 Hz loop** (DESIGN.md §3): dedicated daemon thread, absolute-deadline `parkNanos` pacing with rolling jitter stats, device lifecycle (attach/detach/panic), and input-source selection — prefers a `HardwareAngleSource` (bridge STATE) over the game-tick snapshot. All math delegates to `FfbPipeline`. `runOnce()` is package-private so tests drive the loop deterministically. |
| `FfbTuning.java` | Every feel + safety gain in one immutable record, `sanitized()` range-clamps on construction. Loaded from the hot-reload TOML by the mod; built directly by tests/harness. Swapped atomically between steps; safety-parameter changes re-ramp from zero. |

## Components (composed by the pipeline)

| File | Role |
|---|---|
| `TelemetryBuffer.java` | Reconstructs the 40+ Hz torque signal from 20 Hz packets: 75 ms delayed interpolation, ≤100 ms extrapolation on gaps, then fade to zero — never hold a stale torque. |
| `EventImpulses.java` | Renders strike events (curbs, landings) as exponentially decaying impulses — the low-latency path around the telemetry batch. Bounded queue, clamped peaks. |
| `SoftLock.java` | The end stop at ±lock: zero inside the range, stiff spring + one-way damper past it. Deliberately stiff so the SafetyChain clamp saturates — the stop feels like a wall at the user's own torque ceiling. |
| `FeelEffects.java` | Baseline damper + friction so the wheel never feels dead. Stateless, Nm out. |
| `Mixer.java` / `SoftKnee.java` | Sum in Nm, then compress above the knee (65 % of the clamp, 3:1) so heavy loading stays proportional instead of hitting the clamp wall. |
| `SafetyChain.java` | **The last stage, unbypassable** (DESIGN.md §7): master gain + ramp-in → watchdog (fade, never freeze) → clamp → slew limit → panic/FAULT latch. Property-tested invariants. |
| `GroundTorqueModel.java` | Server-side: reflects per-mount tire lateral force into one signed column torque (kingpin trail × steering ratio). Fed by the mod's `GroundTelemetrySampler`; owns the game-units→Nm gain. |
| `StrikeDetector.java` | Server-side: suspension-compression spikes → immediate event packets (threshold + hysteresis + min-interval, peak-capped). |

## Parked (deliberately kept, not composed)

| File | Why it's still here |
|---|---|
| `SyncSpring.java` | Rendered the stock wheel's 16 RPM slew lag; direct authority has no lag. Returns in Phase 4 to render *actual* kinetic-consumer lag on planes. |
| `VirtualWheelPredictor.java` | Dead-reckons the in-game kinetic wheel for the sync-spring — same Phase 4 return ticket. |

## Invariants worth knowing before editing

- Everything is **Nm at the steering column**; conversion to device units
  happens in the HAL backend, never here.
- `FfbPipeline.step()` is single-threaded by contract (the FFB thread);
  cross-thread ingress (`postTelemetry`, `postEvent`, `setTuning`) is
  lock-free and clamped at the door.
- The SafetyChain sits **after** the soft knee. Nothing may be added
  downstream of it.
- A FAULT is sticky: tuning edits, device swaps, and telemetry never clear
  it — only the engage falling edge does.

Tests: `engine/src/test/java/dev/aeronauticssimwheel/ffb/` — one test class
per component, plus `FfbPipelineTest`/`FfbServiceTest` for the spine.
