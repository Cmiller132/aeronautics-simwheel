# gametest/ — headless in-game proof

`./gradlew :mod:runGameTest` boots a real dedicated server with the full
Create + Simulated + Offroad + Sable stack and runs every `@GameTest` in
`SimWheelGameTests.java`. This is the layer that proves the mod against the
*actual* upstream mods — the engine's unit tests can't catch an Offroad
behavior change; these can.

## The tests

| Test | Proves |
|---|---|
| `sim_wheel_emits_stock_steering_signals` | The block's side-face redstone matches the stock steering wheel's convention exactly (direct authority arrives through the 54°/tick sanity clamp; timeout recenters). |
| `sim_wheel_transmits_link_channels` | A bound channel transmits its analog 0–15 over a real Create redstone-link network to a real receiver. |
| `sim_wheel_failsafe_brake_latches_on_timeout` | Input silence → recenter + the configured brake level latches on the BRAKE channel. |
| `race_car_assembles_into_physics_craft` | The bundled race-car structure assembles into a live Sable physics body and survives simulation. |
| `race_car_emits_ground_telemetry` | End to end: the sim wheel drives the car's own link steering, the craft is shoved into side-slip, and the telemetry rig emits finite, bounded, nonzero column torque at substep rate — and goes dead after the input timeout. Also asserts `fullFidelity()` (the S8 reflective reads still resolve). |
| `mount_linking_gives_float_steering` | The mixin, A/B: the same mount unlinked (stock, yaw 0) then linked chases 0.5·π/6 ≈ 0.2618 rad — strictly between the two nearest integer-signal yaws, so only the float path can produce it. |

Plus `HealthCheck` runs at server startup — the log must show
`all 7 integration surfaces verified`.

## Writing more tests — the sharp edges

- **Gametests share one level.** Anything using Create link frequencies
  must pick a frequency no other test uses, or the networks cross-talk.
- Structures are **generated**, not hand-built: edit
  `tools/make_test_structures.py`, rerun it, and the templates land in
  `mod/src/main/resources/data/aeronautics_simwheel/structure/`.
- The race-car template carries its sim wheel already placed; the wheel's
  channel bindings are done in-test (`bindChannel`) so each test can pick
  its own frequencies.
- Mount ticking runs in the static (unassembled) world too — the
  mount-linking test exploits that to avoid a full physics assembly.
- Timing: physics and link propagation take real ticks; the existing tests
  show the settle margins that work (e.g. 9 ticks for a full-lock slew).
