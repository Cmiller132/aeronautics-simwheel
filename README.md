# Aeronautics SimWheel

Sim racing wheel control and **true force feedback** for [Create: Aeronautics / The Simulated Project](https://github.com/Creators-of-Aeronautics/Simulated-Project), targeting the MOZA R9 wheelbase + pedals (and, by extension, any DirectInput/PID force-feedback wheel).

> Status: research complete, implementation not started. See [`docs/RESEARCH.md`](docs/RESEARCH.md) for the full technical findings this plan is based on.

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

1. **Phase 0 — validate with existing mods (no code).** Use [Create: Tweaked Controllers](https://github.com/getItemFromBlock/Create-Tweaked-Controllers) (explicitly compatible with Create: Simulated) to map the R9's axes onto link channels and fly something. Learn what feels wrong.
2. **Phase 1 — input addon.** Client mod reading the wheel via GLFW and injecting analog values into the `SteeringWheelPacket` / throttle path. Binding UI, deadzones, axis curves.
3. **Phase 2 — FFB v1.** Server-side `BlockEntitySubLevelActor` attachment computes steering torque from Sable's `RigidBodyHandle` state (hinge moment ≈ deflection × dynamic pressure × area, plus damping); telemetry to client each tick; dedicated FFB thread upsamples to ~200 Hz and updates one infinite-duration SDL3 constant-force effect in place. Requires porting `SDL_haptic.h` into a Java SDL3 binding (small, well-scoped).
4. **Phase 3 — FFB v2.** Stall buffet, ground/landing effects, per-craft tuning, multiplayer telemetry packets, optional native 1 kHz helper process, optional MOZA SDK extras (LEDs, per-craft wheel rotation limits).

## Safety (direct-drive wheels can hurt you)

The R9 is a 9 Nm direct-drive base. Non-negotiable rules for the FFB output path:

- Clamp and slew-rate-limit all torque commands; never allow an instantaneous sign flip at full gain.
- Start every session at zero gain and ramp in.
- Users must set the base to "game FFB" mode with the in-base spring/damper zeroed in Pit House — MOZA's compatibility mode adds its own spring that fights DirectInput software.

## Key injection points in Simulated-Project (verified against source, v1.3.0)

- `content/blocks/steering_wheel/SteeringWheelHandler.java` — client-side; accumulates a float angle from mouse movement and sends `SteeringWheelPacket(boolean shouldStop, float targetAngle, BlockPos pos)` via Veil networking. **Feed the wheel axis here.**
- `network/packets/ThrottleLeverSignalPacket(BlockPos, int signal)` — throttle is 0–15 quantized; pedals may deserve a custom analog input block instead.
- Sable: `RigidBodyHandle.of(ServerSubLevel)` → `getLinearVelocity`, `getAngularVelocity`, `applyTorqueImpulse`, …; `BlockEntitySubLevelActor.sable$physicsTick(...)` for per-physics-step hooks; `SubLevelHelper.getVelocityRelativeToAir(...)` for airspeed.

## License

TBD (MIT suggested, matching Simulated-Project's code license). Note Sable itself is PolyForm Shield 1.0.0 — fine to depend on for an addon, but read it before shipping.
