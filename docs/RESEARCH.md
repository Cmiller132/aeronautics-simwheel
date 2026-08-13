# Research notes — August 2026

Findings from a deep-dive into (a) the Create Aeronautics ecosystem and (b) driving a MOZA R9 from a Java Minecraft mod. Kept as the factual base for implementation decisions; place at `docs/RESEARCH.md` in the repo.

## 1. The mod ecosystem

### Create Aeronautics is released, and it does NOT use Valkyrien Skies

After ~5 years of development, Create Aeronautics shipped **April 2026** as part of **"The Simulated Project"** — three interoperable mods:

- **Create: Simulated** — core: physics assembly, redstone/analog input components, interaction API
- **Create: Aeronautics** — propellers, hot-air lift, levitite, airships, planes
- **Create: Offroad** — wheels, suspension, land vehicles

Facts verified against the cloned source:

- Version **1.3.0** (June 2026), **Minecraft 1.21.1 only**, **NeoForge only** (requires Create 6.0.10, NeoForge 21.1.228, Java 21). No Forge/Fabric.
- Source: https://github.com/Creators-of-Aeronautics/Simulated-Project — **code MIT**, assets All Rights Reserved (`LICENSE.md` splits by directory).
- Physics engine: **Sable** (https://github.com/ryanhcode/sable) — Rust natives on the **Rapier** engine (Dimforge). PolyForm Shield 1.0.0 license. Maven: `dev.ryanhcode.sable:sable-common-1.21.1` at `maven.ryanhcode.dev`. Ships are "**sub-levels**" (real chunks at a dynamic pose inside the level).
- Active addon scene: Cosmonautics, Propulsion: Simulated, CreateAvionics, Deep Seas.

### The other ecosystem (not our target, incompatible)

**Valkyrien Skies 2** (engine "Krunch") + **Eureka** + **Create: Clockwork** — MC **1.20.1**, Forge+Fabric. Clockwork went stable Dec 2025 (v0.5.x, source now public, Apache-2.0). VS2 official 1.21/NeoForge support still unstable (issue #1768). An addon cannot cover both ecosystems with one codebase.

VS2 notes, in case a Clockwork port is ever wanted:

- Eureka's Ship Helm spawns a `ShipMountingEntity`; client sends `PacketPlayerDriving(Vector3f impulse, sprint, cruise)` every tick — the wire format is float-capable but the vanilla client **quantizes to −1/0/+1**. Server writes into a `SeatedControllingPlayer` attachment; `EurekaShipControl.physTick` applies forces via `physShip.applyWorldForce/Torque`.
- `SeatedControllingPlayer` is deprecated; the sanctioned addon pattern is your own seat entity + packets + ship attachment.
- No hardware-input addon exists for the helm path — a genuine gap.

### How control works in Create: Simulated (our injection points)

There is no pilot seat capturing WASD. The pilot physically holds/uses input blocks; inputs propagate mechanically. All paths under `simulated/common/src/main/java/dev/simulated_team/simulated/`:

- **Steering Wheel** — `content/blocks/steering_wheel/SteeringWheelHandler.java` (client) extends `BlockHoldInteraction`; while held, `activeOnMouseMove(yaw, pitch)` accumulates `rawAngle += yaw / 10` (clamped; Shift = 45° detents) and sends **`SteeringWheelPacket(boolean shouldStop, float targetAngle, BlockPos pos)`** (`network/packets/SteeringWheelPacket.java`) via **Veil** networking (`VeilPacketManager.server().sendPacket`). Server sets `SteeringWheelBlockEntity.targetAngleToUpdate`. **True analog float in degrees — perfect for a wheel.**
- **Throttle Lever** — `ThrottleLeverSignalPacket(BlockPos, int signal)` → `ThrottleLeverBlockEntity.setSignal(int)`; **0–15 quantized**.
- Sensors: `gimbal_sensor`, `velocity_sensor`, `altitude_sensor` blocks expose craft state as redstone/display values.
- Create's Linked Controller supported via `mixin/linked_controller_binding/LinkedControllerItemMixin.java` (digital).

### Sable API surface for FFB telemetry

`dev.ryanhcode.sable.api.*`:

- **`RigidBodyHandle`** (`api/physics/handle/RigidBodyHandle.java`): `RigidBodyHandle.of(ServerSubLevel)` → `getLinearVelocity(dest)`, `getAngularVelocity(dest)`, `applyImpulseAtPoint`, `applyLinearAndAngularImpulse`, `applyTorqueImpulse`, `teleport(pos, quat)`.
- **`BlockEntitySubLevelActor`**: `sable$tick(ServerSubLevel)` (game tick) and `sable$physicsTick(ServerSubLevel, RigidBodyHandle, double timeStep)` (per physics substep — faster than 20 Hz).
- Also: `BlockEntityPropeller`, lift providers, constraint handles, `ForceGroup`/`QueuedForceGroup`, `MassData`, **`SubLevelHelper.getVelocityRelativeToAir(...)`**, wind providers.

### Existing controller mods (prior art)

- **Create: Tweaked Controllers** (https://github.com/getItemFromBlock/Create-Tweaked-Controllers) — reads real gamepads/joysticks/wheels via GLFW, transmits analog axes over Create redstone-link channels; explicitly compatible with Clockwork and Create: Simulated. Best Phase-0 validation tool and a code reference.
- **Controlify** (Fabric) — SDL3 via isXander's JNA binding fork (`dev.isxander.sdl3java:libsdl4j`, https://github.com/isXander/libsdl4j-controlify); sees wheels as generic joysticks; **rumble only** — SDL haptic module not ported.
- **Controllable** (Forge/NeoForge) — SDL2 via ControllableSDL fork; no haptics either.
- **MidnightControls** — pure GLFW; no haptics (GLFW has no FFB API at all).
- "Create Aeronautics: Joystick | Control Stick" and "Create: Aeroworks" add **in-game joystick blocks** (redstone 0–15), not hardware bridges — don't confuse.

## 2. MOZA R9 and force feedback from Java

### The device

- The R9 is a **standard USB HID PID / DirectInput FFB device** — works with any DirectInput-FFB title, no SDK needed (PCGamingWiki: Controller:MOZA_R9). Windows needs Pit House installed (carries the driver).
- **Gotcha:** in MOZA's DirectInput/compatibility mode the base enables its own in-firmware spring by default, which fights game FFB. Docs must tell users: "game FFB" mode, in-base spring/damper at zero.
- **MOZA SDK** exists (mozaracing.com/pages/sdk): device-parameter API + six native FFB effects; access gated via MOZA dev support, not public. Optional enhancement only.
- **Linux is first-class**: `hid-universal-pidff` driver merged in **Linux 6.15** (backported to 6.12.24+); MOZA bases are primary tested devices. So SDL's Linux evdev haptic backend drives the R9 too.

### Output APIs

- **DirectInput FFB** (Windows): constant force, ramp, periodic, condition effects via `IDirectInputEffect`. Industry standard; what every sim uses.
- **SDL3 haptics**: full DirectInput-style effect set (`SDL_HAPTIC_CONSTANT`, `SPRING`, `DAMPER`, `SINE`…); on Windows the backend *is* DirectInput; SDL3 adds `SDL_HAPTIC_STEERING_AXIS`. Per SDL maintainers this is *the* supported path for wheels (issue #8498). Known open bugs to test around: #12511 (`SDL_UpdateHapticEffect` sends extra `DIEP_*` flags degrading high-rate constant-force updates on some devices), #12947 (G923/G920 regression in SDL ≥2.30/3.2.10), #6081 (G29 silent failure). None are MOZA reports; MOZA is strictly PID-compliant, but verify on real hardware.
- **SDL rumble is NOT FFB** — direct-drive bases ignore it. Dead end.
- **GLFW**: input only, no FFB, long-standing open request.

### Java routes, ranked

1. **In-process SDL3 haptics via extended `sdl3java` (JNA)** — best effort/quality ratio. Port `SDL_haptic.h` into isXander's binding (~a dozen functions + one effect union struct); proven to coexist with Minecraft class-loading (Controlify uses it). One infinite-duration constant-force effect on the steering axis, updated in place ~100–200 Hz from a dedicated thread. Cross-platform for free. Coordinate with Controlify if both load SDL3.
2. **Native helper process** (Rust/C++, SDL3 or raw DirectInput) on a 500–1000 Hz loop; the mod streams a torque scalar over localhost UDP or shared memory. Highest quality ceiling — immune to JVM GC pauses, crash-isolated; the pattern irFFB/OpenTrack/SimHub already use (localhost UDP latency is sub-ms). Cost: per-OS native builds, process lifecycle, distribution.
3. **LWJGL 3.4 `lwjgl-sdl`** — official SDL3 binding incl. complete haptic API, but Minecraft ships LWJGL 3.3.x and modules require a matching core; mixing is unsupported/fragile. Only viable with a shaded, relocated private LWJGL copy. Park it.
4. **MOZA SDK in the native helper** — vendor lock-in, gated access; optional layer over 1/2.
5. Direct DirectInput COM from Java via JNA — most painful, Windows-only, no maintained library. Avoid.

**No Java game with real wheel FFB was found — this would be a first.** Minecraft precedents are input-only (MC Steering Wheel Support) or rumble-only (Controlify).

### The torque model (how sims compute FFB)

Each physics step, compute one signed steering-column torque; stream it as a single **infinite-duration constant-force effect whose parameters are updated in place** (`SDL_UpdateHapticEffect` / `IDirectInputEffect::SetParameters` with type-specific params only — never recreate the effect). Real sims update at 60 Hz (iRacing) to 333–400 Hz (AC, rF2); wheelbase firmware smooths between updates.

- Cars: torque ≈ tire lateral force (slip angle) × (pneumatic + mechanical trail) × steering geometry.
- Aircraft (our case): control-surface **hinge moment** `M = Ch(α, δ) · q · S · c̄` — hinge-moment coefficient (function of angle of attack and deflection) × dynamic pressure (½ρv², from `getVelocityRelativeToAir`) × surface area × chord. Add: damper term from roll/yaw rate, stall buffet as a low-frequency periodic effect, ground rumble, prop vibration.
- **Minecraft's 20 Hz tick is far too coarse**: compute torque targets per tick server-side, ship to client, and interpolate/extrapolate on a dedicated FFB thread to 100+ Hz (irFFB-style upsampling). Sable's physics substeps run faster than the game tick, which helps the source data.

### Safety

A 9 Nm direct-drive base can injure wrists. Clamp + slew-rate-limit all output; no instantaneous sign flips at gain; start at zero gain and ramp in; document the Pit House setup.

## 3. Ground vehicles: wheel-mount control & physics (source-verified Aug 2026)

Read directly from `offroad/.../wheel_mount/WheelMountBlockEntity.java` (Simulated-Project repo, v1.3.x). These are the formulas the Sim Steering Wheel drives and the FFB reads — the ground-vehicle ground truth.

### Drive (throttle is the kinetic network, not a lever)

- The mount is a `KineticBlockEntity`. Propulsion per physics substep: `kineticSpeed × (1 − brake) × min(friction, 1) × 1.75 × timeStep` along the wheel's rolling direction (`kineticSpeed` sign depends on the facing axis). Cars accelerate by controlling shaft RPM/direction (engines, gearshifts); there is no throttle block in the ground path.

### Brake (native, analog, per wheel — nothing to add)

- **Input: analog redstone into the block directly above the mount**, read as `getSignal(pos.above(), UP) / 15`. 0–15 per wheel.
- Braking force: velocity-proportional drag along the rolling direction with coefficient `(0.075 + brake × 0.3) × min(friction, 1)`, scaled by suspension load (`strengthMul`). The 0.075 baseline is always-on rolling resistance; full brake ≈ 5× drag.
- Full brake also multiplies the drive term by `(1 − brake)` → the brake doubles as a clutch/kill.
- Client visual: wheel spin rate scaled by `(15 − brakeSignal)/15` — wheels visibly stop under braking.
- **No lockup**: it is a linear drag, not a grip-clamped friction torque. Wheels cannot lock, there is no slip under braking, and lateral grip is unaffected (no combined-slip model). FFB must not fake a lockup cue.

### Steering (side faces, signed by subtraction)

- The mount reads redstone on both side faces relative to its facing: `signal = signalLeft − signalRight` (signed −15…+15), where left/right are `facing.getClockWise()/getCounterClockWise()`.
- Yaw = `−signal/15 × 30°` (±15 steps → ±30° lock), smoothed by a 0.4-per-tick lerp (`chasingYaw`) — a built-in ~2–3 tick relaxation that behaves like tire relaxation.
- The stock steering wheel emits this via Simulated's `IDirectionalAnalogOutput` (clockwise face = positive 0–15, counterclockwise = negative, facing face = held 15/0), with the sign flipped for east/south facings so physical left/right stays consistent; bridged to plain redstone by a comparator mixin. Our block emits the same convention directly as vanilla weak power.

### Tire lateral force (the FFB torque source)

- Per substep: `v_side × 0.6 × touchingFriction × strengthMul` opposing the contact patch's sideways velocity — this is what turns the car, and reflected through the steering geometry it is the self-aligning torque for the wheel rim.
- `touchingFriction` is per-contacted-block (`sable:friction` datapack multipliers: ice 0.0 → fudged to 0.1, mud 0.25, default 1.0), floored by the tire item's `minimumFriction`.
- Suspension: raycast spring (`strength × normalMassScaling × 40`) + damper (`× 1`), strength scroll-valued 5–180; extension deltas are the bump/strike texture source.

## 4. Open items to verify on hardware

- SDL3 haptics against a real R9 on Windows (expected fine — strictly PID-compliant — but unproven; no MOZA-specific SDL bug reports exist either way).
- Behavior of high-rate `SDL_UpdateHapticEffect` on the R9 (SDL issue #12511).
- Whether pedals enumerate through the wheelbase USB or as a separate HID device (affects axis discovery UX).
- Exact MOZA SDK terms/platforms, if ever pursued.

## Key links

- https://github.com/Creators-of-Aeronautics/Simulated-Project
- https://github.com/ryanhcode/sable
- https://github.com/getItemFromBlock/Create-Tweaked-Controllers
- https://github.com/isXander/libsdl4j-controlify
- https://wiki.libsdl.org/SDL3/CategoryHaptic
- https://github.com/JacKeTUs/universal-pidff
- https://github.com/nlp80/irFFB (the canonical telemetry→FFB bridge)
- https://www.pcgamingwiki.com/wiki/Controller:MOZA_R9
- https://learn.microsoft.com/en-us/previous-versions/windows/desktop/bb153254(v=vs.85) (DirectInput FFB)
