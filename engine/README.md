# engine/ — the pure-JVM input + FFB engine

Everything that computes — device abstraction, torque math, safety, the
bridge protocol, the offline driving harness — with **zero Minecraft
imports**. The Gradle module boundary enforces it: this module does not (and
must never) depend on NeoForge, Minecraft, or the mod. That is what makes
the entire shipping FFB path unit-testable on any machine with a JDK.

The `mod/` module is a thin adapter around this one; the classes here are
the real product.

## Packages

| Package | Role | Map |
|---|---|---|
| `hal/` | Devices: `WheelDevice` abstraction, GLFW input backend, the bridge-sidecar backend, axis processing, scriptable test fake | [README](src/main/java/dev/aeronauticssimwheel/hal/README.md) |
| `ffb/` | The torque math: `FfbPipeline` (the one shipping composition), `FfbService` (the 250 Hz loop), tuning, safety chain, every feel component | [README](src/main/java/dev/aeronauticssimwheel/ffb/README.md) |
| `sim/` | The offline driving harness: a scripted 26 s lap that regression-tests the exact shipping pipeline, no game needed | [README](src/main/java/dev/aeronauticssimwheel/sim/README.md) |

## Tests (`src/test/`)

Mirrors the main packages. Highlights:

- `ffb/FfbPipelineTest` + `ffb/FfbServiceTest` — the shipping composition
  and loop: wiring, engage/fault edges, live tuning swaps, device handoff,
  hostile-ingress bounds.
- `ffb/SafetyChainTest` — property tests on the §7 invariants (clamp, slew,
  ramp, watchdog, panic).
- `sim/DrivingScenarioTest` — feel regressions over the scripted lap (curb
  latency bound, ice lightness, soft-lock wall, strike rendering).
- `hal/bridge/BridgeProtocolTest` — codec round-trip + fuzz + golden byte
  vectors (mirrored in the sidecar's Rust tests).
- `hal/bridge/SidecarConformanceTest` — the real `BridgeWheelDevice` against
  the **real Rust sidecar** (`--sim` mode) over live UDP. Skips politely in
  `:engine:test` when the binary isn't built; `:engine:sidecarConformance`
  builds it via cargo and cannot skip.
- `hal/bridge/FakeBridgeServer` — in-process protocol peer for the
  non-conformance bridge tests.

## Commands

```bash
./gradlew :engine:test                 # the whole suite (~86 tests)
./gradlew :engine:sidecarConformance   # + builds the Rust sidecar (needs cargo)
```

## Rules of the module

- **No Minecraft imports.** If a change needs game state, pass numbers in
  (see how `mod/` feeds `FfbPipeline.postTelemetry` / `publishGameState`).
- **Torque is Nm everywhere.** `WheelDevice.ffbUpdateTorque(float nm)` —
  each backend owns its own device-unit conversion and clamps.
- **The FFB thread never blocks.** `FfbService` runs lock-free against
  published snapshots; anything the game thread wants to say arrives as an
  immutable value.
