# Aeronautics SimWheel

Sim racing wheel control and **true force feedback** for [Create: Aeronautics / The Simulated Project](https://github.com/Creators-of-Aeronautics/Simulated-Project), targeting the MOZA R9 wheelbase + pedals (and, by extension, any DirectInput/PID force-feedback wheel).

> Status: **Phase 2a landed — the wheel block is the single control surface AND a live telemetry rig.** The pure-JVM engine (`engine/`: device HAL + FFB core with safety chain, telemetry buffer, ground torque model, strike detector, soft lock) is unit-tested and green. The mod (`mod/`) implements the block, its link channels, seat occupancy, the server-side ground-telemetry sampler (reads the craft's wheel mounts every physics substep, ships column torque to the driver at substep resolution), and the client link/FFB loop with telemetry + contact-strike reconstruction in the mix. Full gametest suite green on a real headless server (steering contract, link transmission, failsafe brake, race-car assembly, race-car ground telemetry end to end).
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

- `./gradlew :mod:runGameTest` — headless server gametests: steering redstone contract (stock convention, direct authority, timeout recenter), link-channel transmission at analog levels, failsafe-brake latch, race-car physics assembly, and race-car ground telemetry (the sim wheel rides the assembled craft, steers it over its own link frequencies, and the sampled tire-force torque is asserted plausible; primary test vehicle: [`testdata/tones_template_race_car.nbt`](testdata/README.md)). Note: gametests in a batch share one level — every test using link frequencies binds a frequency unique to that test.
- `./gradlew :mod:runClient` — full dev client with the whole mod stack. Place a `sim_steering_wheel` on a craft, sit in a seat, look at it and press **J**; **K** toggles a hardware-free sine-sweep demo input; the debug HUD shows device, commanded vs. in-game angle, lock, and safety-chain torque.
- `./gradlew :mod:runClientSelftest` — same client, logs SimWheel input/FFB state and quits by itself (CI-ish smoke test).

FFB hardware output goes through the **native bridge sidecar** ([`sidecar/`](sidecar/README.md), Rust + DirectInput — the R9 needs no vendor SDK): `cd sidecar && cargo build --release`, run `simwheel-bridge`, and the mod's bridge device takes over from GLFW. Without the sidecar (or hardware), torque is still computed through the real pipeline (telemetry + soft lock + damper/friction through the safety chain on a 250 Hz thread) and shown on the HUD. The sidecar's hardware trip checklist (`sidecar/README.md`) gates real-wheel use.

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
| FFB output | Native Rust/DirectInput bridge sidecar over localhost UDP ([`sidecar/`](sidecar/README.md) — no vendor SDK; the R9 is a standard PID device), SDL3 variant later for exotic wheels |

## Roadmap

See [`docs/DESIGN.md` §11](docs/DESIGN.md) for phases with exit criteria. In short: **done** — the block (gametested), **2a** tire-force telemetry (wheel-mount sampler → substep torque packets → client reconstruction, gametested end to end), and the **2b sidecar software** (Rust/DirectInput, cross-language conformance-tested); **next:** 2b hardware-in-the-loop on the R9 (checklist in [`sidecar/README.md`](sidecar/README.md)); **2c** — end-to-end reactivity + cue scalars; **3** — direct wheel-mount linking (float steering/brake into the native formulas), art pass, config screen; **4** — kinetic output + swivel-bearing planes; **5** — public release.

## Safety (direct-drive wheels can hurt you)

The R9 is a 9 Nm direct-drive base. Non-negotiable rules for the FFB output path:

- Clamp and slew-rate-limit all torque commands; never allow an instantaneous sign flip at full gain.
- Start every session at zero gain and ramp in.
- Users must set the base to "game FFB" mode with the in-base spring/damper zeroed in Pit House — MOZA's compatibility mode adds its own spring that fights DirectInput software.

## License

TBD (MIT suggested, matching Simulated-Project's code license). Note Sable itself is PolyForm Shield 1.0.0 — fine to depend on for an addon, but read it before shipping.
