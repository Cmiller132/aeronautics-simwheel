# Aeronautics SimWheel

Sim racing wheel control and **true force feedback** for [Create: Aeronautics / The Simulated Project](https://github.com/Creators-of-Aeronautics/Simulated-Project), targeting the MOZA R9 wheelbase + pedals (and, by extension, any DirectInput/PID force-feedback wheel).

> Status: **rebuilt around the Sim Steering Wheel block — the single control surface.** The pure-JVM engine (`engine/`: device HAL + FFB core with safety chain, telemetry buffer, feel components incl. the new soft lock) is unit-tested and green. The mod (`mod/`) implements the block, its link channels, seat occupancy, and the client link/FFB loop; gametests cover the steering redstone contract, link transmission, the failsafe brake, and race-car physics assembly. *The gametest suite was rewritten in a session without access to the mod maven repos — run `./gradlew :mod:runGameTest` before trusting a build.*
> - [`docs/RESEARCH.md`](docs/RESEARCH.md) — technical findings (ecosystem, hardware, FFB routes, wheel-mount physics)
> - [`docs/DESIGN.md`](docs/DESIGN.md) — the architecture & decision record

Build: `./gradlew build` (JDK 21). Engine tests only: `./gradlew :engine:test`.

## The one block: `sim_steering_wheel`

Sim hardware talks to the Sim Steering Wheel block and to **nothing else** — no injection into stock blocks, no typewriter piggybacking. One block, three roles:

1. **Steering column** — direct float angle authority from the hardware wheel (no 16 RPM chase; a 1080°/s sanity clamp only), with a per-block configurable lock (±180…1080°). Output is the stock steering wheel's exact analog-redstone side convention, so **Offroad wheel mounts and existing wiring work as a drop-in swap**: clockwise face = positive steer 0–15, counterclockwise = negative, facing face = engaged 15/0. Comparators (vanilla and Simulated's directional read) work too.
2. **Link controller — the typewriter replacement.** Throttle, brake, clutch, and eight wheel buttons each bind to a Create redstone-link frequency pair (the "same item twice" idiom). Rebind your existing craft's frequencies and the old wiring works — proportionally now. Brake wiring for cars is native Offroad: an analog signal into the block above each wheel mount is that wheel's brake (0–15), so `BRAKE` channel → link receiver above the mounts is all it takes.
3. **FFB rig root** — the block entity is where tire-force telemetry attaches (Phase 2a).

**Occupancy:** sit in the craft's seat, look at the wheel, press **J**. The seat is the mutex — one driver, and leaving the seat releases control. Input silence (30 ticks) neutralizes everything: steering recenters, channels zero, and the **failsafe brake** (configurable 0–15) latches so an unattended craft stops instead of coasting.

**Placeholder config UX** (until the screen lands): sneak-right-click cycles the config target (each channel → steering lock → failsafe brake); right-click with an item binds the targeted channel's frequency; right-click empty-handed cycles the targeted setting or shows the binding.

## Running & testing in-game

If the structure templates are stale (e.g. `control_rig.nbt` still contains the old block id), regenerate them first: `python3 -m pip install nbtlib && python3 tools/make_test_structures.py`.

- `./gradlew :mod:runGameTest` — headless server gametests: steering redstone contract (stock convention, direct authority, timeout recenter), link-channel transmission at analog levels, failsafe-brake latch, race-car physics assembly (primary test vehicle: [`testdata/tones_template_race_car.nbt`](testdata/README.md)).
- `./gradlew :mod:runClient` — full dev client with the whole mod stack. Place a `sim_steering_wheel` on a craft, sit in a seat, look at it and press **J**; **K** toggles a hardware-free sine-sweep demo input; the debug HUD shows device, commanded vs. in-game angle, lock, and safety-chain torque.
- `./gradlew :mod:runClientSelftest` — same client, logs SimWheel input/FFB state and quits by itself (CI-ish smoke test).

No FFB hardware output yet (GLFW is input-only; the MOZA bridge sidecar is Phase 2b) — torque is computed through the real pipeline (soft lock + damper/friction through the safety chain on a 250 Hz thread) and shown on the HUD.

## How a car is wired (all existing Offroad mechanics, nothing custom)

| Function | Path | Resolution |
|---|---|---|
| Steering | wheel's side redstone → wheel-mount steering faces (left − right) | ±15 steps → ±30° lock (float-to-mount linking is the planned upgrade) |
| Throttle | `THROTTLE` link channel → receiver → your drivetrain (engines/gearshifts — drive **is** the kinetic network) | 0–15 |
| Brake | `BRAKE` link channel → receiver feeding the block above each wheel mount (native Offroad brake input) | 0–15 per wheel, load-scaled, cuts drive |
| Buttons | `BTN_1..8` link channels → anything | 15/0 |

## Goal

Drive (and later fly) Simulated-Project vehicles with a real wheel and pedals: analog steering, analog pedals, and **real force feedback** — the game's own tire math (lateral slip × per-block friction, suspension deltas, contact spikes) streamed to the wheelbase as torque at 100+ Hz. Nothing in the Minecraft ecosystem does true wheel FFB today; this would be a first.

## Target stack

| Component | Choice |
|---|---|
| Minecraft | 1.21.1 |
| Loader | NeoForge (21.1.228+) |
| Java | 21 |
| Against | Create 6.x + Create: Simulated / Aeronautics / Offroad 1.3.x (MIT code) |
| Physics API | [Sable](https://github.com/ryanhcode/sable) `dev.ryanhcode.sable.api.*` (Maven: `maven.ryanhcode.dev`) |
| Input | GLFW joystick API (already in Minecraft's LWJGL); MOZA bridge sidecar later |
| FFB output | MOZA SDK bridge sidecar over localhost UDP (protocol implemented + unit-tested in `engine/`), SDL3 sidecar variant later |

## Roadmap

See [`docs/DESIGN.md` §11](docs/DESIGN.md) for phases with exit criteria. In short: **now** — this block, gametested; **2a** — tire-force telemetry (`WheelMountSource`); **2b** — MOZA bridge on hardware; **2c** — end-to-end reactivity; **3** — direct wheel-mount linking (float steering/brake into the native formulas), art pass, config screen; **4** — kinetic output + swivel-bearing planes; **5** — public release.

## Safety (direct-drive wheels can hurt you)

The R9 is a 9 Nm direct-drive base. Non-negotiable rules for the FFB output path:

- Clamp and slew-rate-limit all torque commands; never allow an instantaneous sign flip at full gain.
- Start every session at zero gain and ramp in.
- Users must set the base to "game FFB" mode with the in-base spring/damper zeroed in Pit House — MOZA's compatibility mode adds its own spring that fights DirectInput software.

## License

TBD (MIT suggested, matching Simulated-Project's code license). Note Sable itself is PolyForm Shield 1.0.0 — fine to depend on for an addon, but read it before shipping.
