# Aeronautics SimWheel

Sim racing wheel control and **true force feedback** for [Create: Aeronautics / The Simulated Project](https://github.com/Creators-of-Aeronautics/Simulated-Project), targeting the MOZA R9 wheelbase + pedals (and, by extension, any DirectInput/PID force-feedback wheel).

> Status: **Phase 1 MVP wired and tested in-game (headless).** The pure-JVM engine (`engine/`: device HAL + FFB core with safety chain, telemetry buffer, feel components) is unit-tested; the mod (`mod/`) compiles against the real Create + Simulated + Offroad + Sable stack and implements wheel input → latch-mode `SteeringWheelPacket` injection → client-only FFB feel (sync-spring through the safety chain on a 250 Hz thread). GameTests verify the injected-packet steering contract (16 RPM slew) and that the test race car assembles into a Sable physics craft, on a real headless server.
> - [`docs/RESEARCH.md`](docs/RESEARCH.md) — technical findings (ecosystem, hardware, FFB routes)
> - [`docs/DESIGN.md`](docs/DESIGN.md) — the architecture & design: module layout, input/FFB pipelines, torque model, safety chain, degraded modes, phased build order

Build: `./gradlew build` (JDK 21). Engine tests only: `./gradlew :engine:test`.

## Running & testing in-game

- `./gradlew :mod:runGameTest` — headless server gametests: steering-wheel packet contract + race-car physics assembly (primary test vehicle: [`testdata/tones_template_race_car.nbt`](testdata/README.md), with the DnDecor cog swapped for `create:large_cogwheel` by `tools/make_test_structures.py`).
- `./gradlew :mod:runClient` — full dev client with the whole mod stack. In a world: look at a `simulated:steering_wheel` and press **J** to engage/disengage; **K** toggles a hardware-free sine-sweep demo input; a debug HUD shows device, commanded vs. actual wheel angle, and safety-chain torque.
- `./gradlew :mod:runClientSelftest` — same client, logs SimWheel input/FFB state and quits by itself (CI-ish smoke test).

No FFB hardware output yet (GLFW is input-only; the SDL3 haptics backend is Phase 2) — torque is computed through the real pipeline and shown on the HUD.

## Goal

Fly (and drive) Simulated-Project vehicles with a real wheel and pedals:

- **Analog steering** injected directly into the mod's Steering Wheel block (which is float-analog end to end — no quantization).
- **Analog throttle/brake** from pedals.
- **Real force feedback**: control-surface hinge-moment torque computed from the Sable physics engine's state (airspeed, angular velocity, attitude), streamed to the wheelbase as a constant-force effect at 100+ Hz — plus stall buffet, ground rumble, and damping effects.

Nothing in the Minecraft ecosystem does true wheel FFB today (existing controller mods are input-only or rumble-only, and direct-drive bases ignore rumble), so this would be a first.

## Target stack

| Component | Choice |
|---|---|
| Minecraft | 1.21.1 |
| Loader | NeoForge (21.1.228+) |
| Java | 21 |
| Against | Create 6.x + Create: Simulated / Aeronautics 1.3.x (MIT code) |
| Physics API | [Sable](https://github.com/ryanhcode/sable) `dev.ryanhcode.sable.api.*` (Maven: `maven.ryanhcode.dev`) |
| Input | GLFW joystick API (already in Minecraft's LWJGL) to start; SDL3 later |
| FFB output | SDL3 haptics (constant-force on `SDL_HAPTIC_STEERING_AXIS`) via an extended JNA binding, or a native 1 kHz helper process over localhost UDP (irFFB pattern) |

## Roadmap

See [`docs/DESIGN.md` §11](docs/DESIGN.md) for the full phase plan with exit criteria. In short:

1. **Phase 0 — feel scouting (no code).** Fly with [Create: Tweaked Controllers](https://github.com/getItemFromBlock/Create-Tweaked-Controllers) to learn what feels wrong.
2. **Phase 1 — input (GLFW, client-only).** Analog steering + throttle via the mod's own packets; binding/calibration UI; works on servers without the addon.
3. **Phase 2 — FFB core.** `SDL_haptic` port, dedicated FFB thread + safety chain, server-side rig resolution reading the **actual hinge constraint reaction torque** from Sable's solver (`getJointImpulses` on the swivel bearing's `RotaryConstraintHandle`), telemetry, sync-spring + damper feel.
4. **Phase 3 — feel & degraded modes.** Buffet, ground rumble, detents; aero-model + craft-state fallback sources; client-estimated FFB on vanilla servers; craft profiles.
5. **Phase 4 — public release.** API freeze, docs, packaging, upstream contributions (analog throttle block, libsdl4j haptics PR).

## Safety (direct-drive wheels can hurt you)

The R9 is a 9 Nm direct-drive base. Non-negotiable rules for the FFB output path:

- Clamp and slew-rate-limit all torque commands; never allow an instantaneous sign flip at full gain.
- Start every session at zero gain and ramp in.
- Users must set the base to "game FFB" mode with the in-base spring/damper zeroed in Pit House — MOZA's compatibility mode adds its own spring that fights DirectInput software.

## Key injection points in Simulated-Project (verified against source, v1.3.0)

- `content/blocks/steering_wheel/SteeringWheelHandler.java` — client-side; accumulates a float angle from mouse movement and sends `SteeringWheelPacket(boolean shouldStop, float targetAngle, BlockPos pos)` via Veil networking. **Feed the wheel axis here.** (The block entity is a kinetic *generator* that chases the target at a fixed 16 RPM — the design's sync-spring renders that slew as FFB resistance.)
- `network/packets/ThrottleLeverSignalPacket(BlockPos, int signal)` — throttle is 0–15 quantized; pedals may deserve a custom analog input block instead.
- `content/blocks/swivel_bearing/SwivelBearingBlockEntity` — control surfaces hinge on a Sable `RotaryConstraintHandle` driven as a PD servo from the kinetic network. **`getJointImpulses(...)` on that handle is the physically-true hinge moment — the primary FFB torque source.**
- Sable: `SablePre/PostPhysicsTickEvent` for substep-rate sampling; `RigidBodyHandle.of(ServerSubLevel)` → `getLinearVelocity`, `getAngularVelocity`, …; `SubLevelHelper.getVelocityRelativeToAir(...)` for airspeed; `BlockSubLevelLiftProvider` holds the game's exact sail lift/drag math for the fallback torque model.

## License

TBD (MIT suggested, matching Simulated-Project's code license). Note Sable itself is PolyForm Shield 1.0.0 — fine to depend on for an addon, but read it before shipping.
