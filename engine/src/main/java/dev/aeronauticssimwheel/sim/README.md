# sim/ — the offline driving harness

A headless race car that drives a scripted 26-second lap so the **exact
shipping FFB composition** (`FfbPipeline`) can be regression-tested — and
eyeballed — without Minecraft, without hardware, on any machine.

This is not a toy model of the pipeline: the harness batches substep torque
into 20 Hz "packets" like the real wire, fires strikes through the immediate
event path, runs the client side at 250 Hz, and plays everything through the
same `FfbPipeline` instance the mod ships. When a feel property regresses
(curb latency, ice lightness, the soft-lock wall), a test here goes red.

## Files

| File | Role |
|---|---|
| `DrivingScenarioDemo.java` | The lap script + the wiring. Phases: straight → sweeping corners → curb strike → ice patch → gravel → a final shove past the soft lock. Produces a `Result` with the full 250 Hz trace (`TraceRow`: commanded steer, raw vs. telemetry vs. impulse vs. output Nm) plus peak markers for the latency asserts. Runnable as a main() to dump the trace CSV. |
| `RaceCarFfbSim.java` | The server-side stand-in: a steered front axle (two `QuarterCarWheel`s on a kingpin) producing one column torque per 60 Hz substep — self-aligning from lateral slip, texture from suspension motion. Shaped by the same formulas the real sampler mirrors. |
| `QuarterCarWheel.java` | One corner: unsprung mass on a tire spring, suspension spring + damper, vertical load Fz, and a decaying longitudinal strike force when the tire hits a block-seam riser. |
| `TerrainProfile.java` | 1D Minecraft-style ground: piecewise-constant heights per 1 m block column (the world's real granularity) + per-material sub-cell roughness (gravel/dirt texture), deterministic. |

## Commands

```bash
./gradlew :engine:test --tests '*DrivingScenarioTest*'   # the regression asserts
python tools/render_sim_report.py                        # render the trace to HTML
```

`DrivingScenarioTest` (in `src/test/.../sim/`) asserts, among others: the
curb's raw torque peak reaches the rim within the latency budget, ice makes
the wheel light, the soft-lock phase reads as a wall (sustained > 2 Nm), and
strikes actually render through the event path.

## Honest limits

The car model is a stand-in shaped by Offroad's formulas, not the game
solver — it exists to test the *pipeline*, not to predict exact in-game
feel. In-game truth is the gametests' ground-telemetry assertions
(`mod/gametest/`) and, ultimately, hands on a real wheel.
