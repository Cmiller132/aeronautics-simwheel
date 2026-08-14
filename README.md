# Aeronautics SimWheel

Drive **Create: Simulated / Aeronautics / Offroad** vehicles with a real sim
racing wheel — analog steering, analog pedals, and (in progress) **true force
feedback** computed from the game's own tire physics. Targets the MOZA R9 and,
by extension, any DirectInput FFB wheel. Nothing in the Minecraft ecosystem
does true wheel FFB today; this is the attempt.

One block does everything: place the **Sim Steering Wheel** on your craft and
it is the steering column (direct 1:1 wheel angle, no slew chase), the pedal
controller (throttle/brake/clutch + 8 buttons over Create redstone-link
frequencies — a drop-in replacement for linked-typewriter wiring), and the
telemetry rig that streams tire forces back to your wheelbase.

> **Here to test?** Read **[TESTING.md](TESTING.md)** — a 10-minute
> walkthrough from download to driving with force feedback.

---

## Installing

### The easy way (recommended)

Grab two files from the
[latest release](https://github.com/Cmiller132/aeronautics-simwheel/releases):

1. **`simwheel-testkit-x.y.z.mrpack`** — open it with
   [Prism Launcher](https://prismlauncher.org/) (Add Instance → Import) or
   the [Modrinth App](https://modrinth.com/app). It contains the entire
   pinned mod stack (Create, Sable, the bundled
   Simulated/Aeronautics/Offroad jar), this mod, **and** the ready-to-drive
   test car schematic. One file, press Play. Works on Windows and Linux.
2. **The FFB bridge for your OS** — only if you want force feedback on a
   real wheel. Windows: `simwheel-ffb-bridge-x.y.z-windows.zip` — unzip,
   double-click `START-FFB-BRIDGE.bat`, leave the window open. Linux:
   `simwheel-ffb-bridge-x.y.z-linux-x86_64.tar.gz` — extract, run
   `./start-ffb-bridge.sh` in a terminal, leave it open (needs kernel
   6.15+ or a hid-universal-pidff backport for MOZA; see
   [TESTING.md](TESTING.md)). The game auto-detects the bridge either way.
   (MOZA users: game-FFB mode in Pit House, in-base spring/damper at 0,
   rotation 1080°.)

No wheel? Everything still works — W/S/A/D drive the car from the keyboard,
and **K** toggles a demo input that sweeps all axes.

### The manual way

Minecraft 1.21.1 + NeoForge 21.1.228+, then from Modrinth: **Create**
(6.0.10+), **Sable**, and **Create Aeronautics** (the 1.3.x bundled jar —
it contains Simulated and Offroad). Build this mod with `./gradlew :mod:build`
(JDK 21) and drop `mod/build/libs/aeronautics-simwheel-x.y.z.jar` into
`mods/`. Copy [`schematics/simwheel_race_car.nbt`](schematics/) into the
instance's `schematics/` folder.

---

## Quickstart

1. Creative world. Use Create's **Schematic** item → load
   `simwheel_race_car.nbt` → position it on flat ground → place (instant
   placement works in creative).
2. Right-click the **physics assembler** (the block with the lever, rear of
   the car) to assemble the craft into a physics body.
3. Sit in the **seat**, look at the Sim Steering Wheel block, press **J**.
4. No wheel attached? Press **K** first — a sine-sweep demo input drives all
   axes so you can watch the whole chain work without hardware.
5. Drive: steering is 1:1 with your wheel (±450° lock by default), throttle
   pedal accelerates, brake pedal brakes each wheel individually (analog,
   load-scaled), a rim button engages reverse. **No pedals or no wheel?**
   The keyboard is always merged in: hold **W** for throttle, **S** for
   brake, and **A/D** steer whenever the wheel is centered or absent.
6. Get out of the seat (or just stop sending input for 1.5 s) — control
   releases, steering recenters, and the failsafe brake latches so the car
   stops instead of coasting away.

The debug HUD (top left while engaged) shows the device, commanded vs.
in-game angle, safety-chain torque, and live telemetry torque.

**If steering or throttle feel reversed** on your build of the car: the
bindings are configurable on the block —
- **sneak-right-click** the wheel: cycles the config target
  (each channel → steering lock → failsafe brake),
- **right-click with an item**: binds the targeted channel to that item's
  frequency pair (held twice — the community typewriter idiom),
- **right-click empty-handed**: cycles the targeted setting's presets, or
  shows the current binding.

To swap steering direction, bind `STEER_LEFT` to the frequency currently on
`STEER_RIGHT` and vice versa (lime glazed terracotta + lime wool, both
orders). Throttle/reverse use lime terracotta + lime wool; brakes use lime
dye ×2.

## Using it on your own craft

- **Steering**: the block emits the stock steering wheel's exact analog
  redstone convention on its side faces — swap it into any wheel-mount car's
  stock-wheel position and the wiring keeps working, now with direct float
  authority.
- **Pedals/buttons**: bind each channel to a redstone-link frequency pair;
  put receivers wherever the signal is needed. Brakes: a receiver directly
  **above** each wheel mount is Offroad's native per-wheel brake input (0–15).
- **Mount linking (recommended for cars)**: hold a **stick**, click the wheel
  (linking on), click each wheel mount, click the wheel again (done). Linked
  mounts take steering and brakes as exact **floats** from the wheel — same
  physics, no 0–15 quantization; unlink any time the same way. (Because the
  stick is the linker tool, it can't be used as a frequency item.)
- **Settings on the block** (survive disassembly): steering lock
  ±180…1080°, failsafe brake level 0–15.

## Force feedback status

| Piece | State |
|---|---|
| Tire-force telemetry (server → your rim) | implemented + gametested — self-aligning torque, bump texture, contact strikes, all from the game's own wheel-mount math |
| Client feel (soft lock, damper, friction, telemetry mix, safety chain) | implemented + unit-tested (`FfbPipeline` — the exact composition the offline harness regression-tests), 250 Hz thread with live hardware-angle input when the bridge runs |
| Feel tuning | hot-reload TOML: `config/aeronautics_simwheel-feel.toml` (written with commented defaults on first run) — save the file, feel it next corner |
| Native output bridge (`sidecar/`, Rust — DirectInput on Windows, evdev on Linux) | implemented + conformance-tested; **hardware-in-the-loop pending** — the [checklist](sidecar/README.md) needs a real R9 |

**Safety, non-negotiable**: a 9 Nm direct-drive base can hurt you. The chain
clamps at 2.5 Nm by default with ramp-in, slew limiting, watchdogs at every
hop, and a hardware self-expiring effect. Read the
[sidecar safety model](sidecar/README.md) before enabling output, keep the
base's own torque limit at 50% for first runs, and never bypass the clamps.

## Reporting problems

Open an issue with: your mod list + versions, `logs/latest.log`, what the
HUD showed, and (for feel/telemetry issues) what craft you were driving —
ideally the schematic. The block is a placeholder cube until the art pass;
that's known.

---

## For developers

- `./gradlew build` — everything (JDK 21; first run downloads the mod stack).
- `./gradlew :engine:test` — pure-JVM engine suite (input HAL, FFB core,
  safety chain, bridge protocol, ground torque model).
- `./gradlew :mod:runGameTest` — headless server gametests against the real
  Create/Simulated/Offroad/Sable stack (steering contract, link transmission,
  failsafe brake, race-car assembly, end-to-end ground telemetry). Note:
  gametests share one level — link-frequency tests must each use a unique
  frequency.
- `./gradlew :engine:sidecarConformance` — builds the Rust sidecar (needs
  cargo) and runs the cross-language conformance harness against it.
- Linux sidecar build: `cd sidecar && CARGO_TARGET_DIR=target-linux cargo
  build --release` (from WSL or any Linux box; the separate target dir keeps
  it from clobbering a Windows build in the same checkout).
- `./gradlew :mod:runClient` / `:mod:runClientSelftest` — dev client; the
  selftest boots, logs input/FFB state, and quits.
- `python tools/make_test_structures.py` — regenerates the gametest
  structures and the shareable schematic (needs `pip install nbtlib`).

Architecture and decisions: [`docs/DESIGN.md`](docs/DESIGN.md) (the decision
record — start with §2's verified integration surface and §11's build order),
[`docs/RESEARCH.md`](docs/RESEARCH.md) (ecosystem/hardware findings).
`engine/` is pure JVM with zero Minecraft imports; `mod/` is a thin NeoForge
adapter; `sidecar/` is the native FFB process.

| Target stack | |
|---|---|
| Minecraft / loader | 1.21.1, NeoForge |
| Against | Create 6.x + The Simulated Project 1.3.x (MIT code) |
| Physics | [Sable](https://github.com/ryanhcode/sable) (Rapier-based, PolyForm Shield) |
| FFB output | `sidecar/` — Rust over localhost UDP; DirectInput (Windows) / evdev (Linux) |

License: MIT (Sable itself is PolyForm Shield — depended on, never vendored;
Simulated's assets are ARR — none shipped).
