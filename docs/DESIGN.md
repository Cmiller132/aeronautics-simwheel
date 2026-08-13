# Aeronautics SimWheel — Architecture & Design

Design for wheel input and true force feedback against **Create: Simulated / Aeronautics (The Simulated Project)** on NeoForge 1.21.1, targeting the MOZA R9 first and any DirectInput/PID FFB wheel by extension.

This document is grounded in the actual Simulated-Project v1.3.0 and Sable 2.0.0 sources (all file references verified August 2026). Background findings live in [`RESEARCH.md`](RESEARCH.md); this document is the *decision record* — what we build, how the pieces fit, and why.

---

## 1. Scope

### Goals

1. **Analog steering** into the Steering Wheel block, feeling 1:1 with the hardware wheel.
2. **Analog throttle** from pedals into the Throttle Lever block.
3. **True force feedback**: the torque the craft's control linkage would put into the pilot's hands, computed from the *actual* physics simulation and streamed to the wheelbase — not canned rumble.
4. **Robust by default**: a 9 Nm direct-drive base must never be able to hurt anyone because of a bug, a lost packet, or a GC pause.
5. **Publishable**: clean module boundaries, a small public API, degraded-mode behavior on servers that don't run the addon, and no fragile reach into Simulated internals beyond a short, documented list.

### Non-goals

- **Valkyrien Skies / Clockwork support.** Different ecosystem, different MC version, incompatible architecture. This project is Simulated-only, permanently.
- Gamepad/HOTAS general-purpose mapping (Create: Tweaked Controllers already does this well). We handle *wheel + pedals + FFB*; anything else is out of scope.
- Reimplementing flight physics. Sable/Rapier is the single source of truth; we read from it, we never second-guess it.
- Vanilla-Minecraft vehicle support (boats, horses, etc.).

---

## 2. The verified integration surface

Everything below was read from source, not inferred. This table is the project's **compatibility contract** — each row is a thing that can break when Simulated updates, so each row must be covered by a startup check (see §10.4).

| # | Surface | Where (Simulated-Project v1.3.0) | What we use it for | Access mechanism | Risk |
|---|---------|----------------------------------|--------------------|-----------------|------|
| S1 | `SteeringWheelPacket(boolean shouldStop, float targetAngle, BlockPos pos)` | `network/packets/SteeringWheelPacket.java` | Steering injection. Float degrees, analog end-to-end. Server handler sets `targetAngleToUpdate` and toggles `startHolding()`/`stopHolding()`; **no server-side range validation** (we self-impose one anyway, §5.4). | Construct + send via Veil (public record) | Low — wire format is effectively API |
| S2 | `ThrottleLeverSignalPacket(BlockPos pos, int signal)` | `network/packets/ThrottleLeverSignalPacket.java` | Throttle injection, 0–15. Server validates `inInteractionRange(player, pos, 1)`. | Construct + send via Veil | Low |
| S3 | `SteeringWheelBlockEntity` | `content/blocks/steering_wheel/` | Read `angleInput` (±1…360° limit, default 180), `held`, `getInteractionAngle()`, facing/`directionConvert`. It is a `GeneratingKineticBlockEntity` with **fixed RPM = 16** → the in-game wheel slews toward the target at ~96°/s regardless of input speed. | Public fields/methods | Low |
| S4 | `SteeringWheelHandler` (client) | same package | Vanilla mouse-hold interaction. `rawAngle += yaw/10`, Shift = 45° detents, sends packet only on change. We either piggyback on it (mixin mode) or bypass it (latch mode, §5.3). | Mixin (mode A only) | Medium |
| S5 | `HoldInteractionManager` / `BlockHoldInteraction` | `util/hold_interaction/` | Understanding hold semantics; `isBlockActive` gates the client BE's angle lerp. | Read-only / public statics | Low |
| S6 | `SwivelBearingBlockEntity` | `content/blocks/swivel_bearing/` | **The FFB ground truth.** Holds a private `RotaryConstraintHandle handle` driving the control surface as a Rapier PD servo (`setMotor(axis, goalRad, kP·I, kD·I)`), with kP/kD scaled by attached sub-level inertia. Public getters exist for target angle; the constraint handle needs an accessor mixin. | Accessor mixin for `handle`; public API otherwise | **Medium — the one real internal reach** |
| S7 | Veil networking | `VeilPacketManager.create(modid, version)` | Our own telemetry channel, same pattern Simulated uses (`SimPacketManager`). | Public Veil API | Low |

And from **Sable 2.0.0** (`dev.ryanhcode.sable.api.*`, PolyForm Shield — fine to depend on):

| # | Surface | What we use it for |
|---|---------|--------------------|
| A1 | `SablePrePhysicsTickEvent` / `SablePostPhysicsTickEvent` — `(SubLevelPhysicsSystem, double timeStep)` | Server-side sampling at **physics substep rate** (faster than 20 Hz) without owning any block entity on the craft. |
| A2 | `PhysicsConstraintHandle.getJointImpulses(Vector3d linear, Vector3d angular)` | **The hinge moment.** The solver's actual joint reaction impulses on the control-surface hinge — includes the servo motor holding torque, aero load, inertia, buffet, ground strikes. τ = (angularImpulse · hingeAxis) / timeStep. |
| A3 | `RigidBodyHandle.of(...)` → `getLinearVelocity/getAngularVelocity(dest)` | Craft state for the cue synthesizer (damping, buffet, rumble). |
| A4 | `SubLevelHelper.getVelocityRelativeToAir(level, pos, dest)` | True airspeed including wind providers → dynamic pressure. |
| A5 | `BlockSubLevelLiftProvider.sable$contributeLiftAndDrag(...)` | Reference implementation of the game's exact per-block aero math (parallel drag 0.75, directionless drag ≈0.0689, lift 0.475, × air pressure). Used by the **model fallback** torque source (§6.3) — we replicate it, never call it. |
| A6 | `SubLevelPhysicsSystem.getPhysicsHandle(subLevel)` | Bulk handle access inside the physics tick. |

**The mechanical chain this design exploits** (verified end to end):

```
player hand → hardware wheel → [this mod] → SteeringWheelPacket(targetAngle)
  → SteeringWheelBlockEntity (kinetic generator, 16 RPM toward target)
  → Create shaft/cog network (fixed gear ratio g)
  → SwivelBearingBlockEntity (integrates cog speed into servo target)
  → RotaryConstraintHandle PD motor (Rapier joint)
  → control-surface plate sub-level (sail blocks = lift providers)
  → aerodynamic + inertial + contact loads … which react back through
  → the same joint  ⇒  getJointImpulses()  ⇒  [this mod]  ⇒  wheelbase torque
```

The joint reaction is the *physically correct* thing to put in the player's hands: it already contains hinge aero moment, surface inertia, prop-wash buffet transmitted through the airframe, and ground strikes — because it is what the simulation actually computed. We do not model aerodynamics for the primary path; we read the answer out of the solver.

---

## 3. System overview

One mod jar, four strictly layered parts. Arrows are the only allowed dependency directions:

```
┌────────────────────────────────────────────────────────────────────────┐
│  CLIENT                                                                │
│                                                                        │
│   hal/  Device layer (no Minecraft imports)                            │
│   ├── WheelDevice interface: axes, buttons, ffb capability             │
│   ├── GlfwWheelDevice   (input only — Phase 1)                         │
│   └── Sdl3WheelDevice   (input + constant-force FFB — Phase 2)         │
│          ▲                          ▲                                  │
│          │ poll (render thread)     │ update @ 100–200 Hz              │
│   client/                        ffb/  FFB engine (no Minecraft        │
│   ├── InputRouter                 │    imports; unit-testable)         │
│   ├── EngagementController        ├── TelemetryBuffer (interp/extrap)  │
│   ├── SteeringInjector ──────┐    ├── LocalFeel: sync-spring, damper,  │
│   ├── ThrottleInjector       │    │   friction, detents, buffet synth  │
│   ├── HUD overlay, config UI │    ├── Mixer                            │
│   └── TelemetryReceiver ─────┼──► └── SafetyChain (final, not          │
│                              │        bypassable) ──► device           │
├──────────────────────────────┼─────────────────────────────────────────┤
│  NETWORK (Veil channel "aeronautics_simwheel")                         │
│   serverbound: EngageRequest, DisengagePacket                          │
│   clientbound: EngageResponse, FfbTelemetryPacket (20 Hz),             │
│                RigInvalidatedPacket, FfbEventPacket (immediate)        │
├──────────────────────────────┼─────────────────────────────────────────┤
│  SERVER (optional — degraded client-only mode without it, §8)          │
│                              ▼                                         │
│   server/                                                              │
│   ├── RigResolver: steering wheel → kinetic net → swivel bearings      │
│   ├── FfbRig (per engaged player): ratio g, hinge axes, sources        │
│   ├── TorqueSources: JointImpulseSource | AeroModelSource |            │
│   │                  CraftStateSource                                  │
│   └── TelemetrySampler: SablePostPhysicsTickEvent → ring buffer        │
│                          → FfbTelemetryPacket each game tick           │
└────────────────────────────────────────────────────────────────────────┘
```

Threading (client):

| Thread | Runs | Rate |
|--------|------|------|
| Render/main thread | GLFW axis polling, injectors, engagement, HUD | per frame |
| Netty IO → main | telemetry packet decode → `TelemetryBuffer` (lock-free SPSC handoff) | 20 Hz |
| **FFB thread** (dedicated, high priority) | buffer reconstruction, local feel, mixer, safety chain, device update | 1 kHz loop, device writes coalesced to 100–200 Hz |
| SDL event thread (SDL backend only) | SDL event pump, device hotplug | internal |

The FFB thread never touches Minecraft objects. Everything it consumes crosses via immutable snapshots or primitive ring buffers. A GC pause can delay a device update; the safety chain's slew limiter and the wheelbase's own interpolation make that a non-event, and the watchdog handles real stalls (§7).

---

## 4. Module & package layout

Single Gradle project (ModDevGradle, NeoForge 21.1.228+), single artifact. Multi-loader scaffolding is deliberately **not** used — the target is NeoForge-only by upstream constraint, and abstraction layers we don't need are how mods rot.

```
dev.aeronauticssimwheel  (final package TBD by repo owner)
├── api/            # stable, semver'd public API for other addons (§9)
├── hal/            # device abstraction; ZERO minecraft/neoforge imports
│   ├── glfw/
│   └── sdl3/       # incl. the SDL_haptic JNA port (upstreamable)
├── ffb/            # FFB engine; ZERO minecraft imports; pure-JVM unit tests
├── net/            # packet records + codecs (shared client/server)
├── client/         # input routing, engagement, injectors, HUD, config UI
├── server/         # rig resolution, torque sources, telemetry sampler
└── mixin/          # the complete list of mixins (kept intentionally tiny):
    ├── SwivelBearingBlockEntityAccessor   (read `handle`)            [S6]
    └── SteeringWheelHandlerMixin          (mode A only, §5.3)        [S4]
```

Rules that keep this clean:

- `hal/` and `ffb/` compile without Minecraft on the classpath (enforced by a separate source set or an architecture test). These two packages are where all the hard, testable logic lives.
- `client/` and `server/` are thin adapters between the game and the engine.
- Every reach into Simulated internals lives in `mixin/` plus one `compat/` facade class per touched Simulated class, so a Simulated update breaks *one file per surface*, with a startup health check (§10.4) turning breakage into a clean "integration disabled" instead of a crash.

---

## 5. Input path

### 5.1 Device layer (`hal/`)

```java
interface WheelDevice {
    String id();                    // stable: GUID + name + axis count
    float axis(int index);          // normalized -1..1, raw
    int axisCount();
    boolean button(int index);
    EnumSet<Capability> capabilities();   // FFB_CONSTANT, FFB_SPRING, ...
    // FFB (throws or no-ops if !FFB_CONSTANT):
    void ffbStart();                      // create infinite constant-force effect, gain 0
    void ffbUpdateTorque(float normalized); // -1..1 of device max, updated in place
    void ffbStop();                       // stop + destroy effect
    void panic();                         // best-effort immediate zero: stop-all-effects
}
```

- **`GlfwWheelDevice`** (Phase 1): wraps `glfwGetJoystickAxes/Buttons`, polled on the render thread (GLFW main-thread requirement; this is what Create: Tweaked Controllers does and it's proven fine). Input only; `capabilities()` is empty.
- **`Sdl3WheelDevice`** (Phase 2): isXander's `libsdl4j` fork + our port of `SDL_haptic.h` (~a dozen functions + the effect union struct — see RESEARCH.md §2). One infinite-duration `SDL_HAPTIC_CONSTANT` effect on `SDL_HAPTIC_STEERING_AXIS`, parameters updated in place via `SDL_UpdateHapticEffect`; the effect is **never** destroyed/recreated during a session (that's the industry pattern and works around update-latency bugs). The haptic port is written as a clean standalone addition so it can be PR'd upstream to the binding.
- Device identity uses GUID+name so bindings survive USB port changes; MOZA pedals may enumerate through the base or standalone (open hardware question) — therefore **each logical axis binds independently to (deviceId, axisIndex)**, never "one device owns everything."
- Axis processing per binding: calibration (min/center/max), deadzone, curve (linear / expo / custom points), inversion, smoothing (one-pole, default off for steering, mild for pedals).

Logical axes v1: `STEERING`, `THROTTLE`, `BRAKE` (reserved), `CLUTCH` (reserved). Buttons v1: `ENGAGE`, `DETENT_MODIFIER` (maps to the Shift 45°-detent behavior), plus freeform button→key bindings later.

### 5.2 Engagement model

A state machine owned by `EngagementController`:

```
DISENGAGED ──(look at Steering Wheel + press ENGAGE)──► ENGAGING
ENGAGING ──(EngageResponse ok │ timeout→client-only mode)──► ENGAGED
ENGAGED ──(ENGAGE again │ range exit │ wheel BE removed │ craft disassembled
           │ GUI opened │ window focus lost │ FAULT)──► DISENGAGING
DISENGAGING ──(send SteeringWheelPacket(shouldStop=true) + Disengage)──► DISENGAGED
FAULT (safety trip, §7) ──(manual re-engage only)──► DISENGAGED
```

- Engagement is **per steering-wheel block**, targeted by crosshair, exactly like the vanilla interaction — so it works with multi-station craft and multiplayer (two players, two wheels, one craft is fine; the server keys rigs by `(player, wheelPos)`).
- On engage we mirror vanilla semantics on the wire: the first `SteeringWheelPacket(false, angle, pos)` sets `held=true` server-side, so other players see the wheel grabbed; disengage sends `shouldStop=true`. Vanilla clients render everything correctly without knowing we exist.
- We **self-impose the interaction range check** client-side each tick (using the same `BlockHoldInteraction.inInteractionRange` logic through the compat facade) and auto-disengage on exit, even though the current server handler wouldn't stop us [S1]. Reason: the missing validation upstream is a bug we should assume gets fixed, and honest behavior keeps us server-legal everywhere.

### 5.3 Steering injection — two modes, one default

**Mode B — "latch" (default).** While `ENGAGED`, the mod owns the wheel: each client tick it maps hardware angle → target angle and sends `SteeringWheelPacket(false, θ, pos)` **only when the value changed** (matching vanilla's `updated`-flag behavior; at rest, zero packets). The player's mouse stays free for camera. No mixin into the input path at all — pure packet-level integration, immune to most Simulated refactors.

**Mode A — "hold passthrough" (compat option).** A small mixin into `SteeringWheelHandler.activeTick` overwrites `rawAngle` with the hardware value while the vanilla hold interaction is active. The player holds right-click as usual; we ride the vanilla lifecycle entirely. Fewer moving parts, but the camera-capture and RMB-hold UX is worse for flying. Kept as a config option and as a fallback if latch mode ever conflicts with an upstream change.

Shared injection details (both modes):

- **Mapping**: hardware degrees → wheel degrees, default 1:1 against the block's `angleInput` limit (±1…360°, per-block). Steering ratio configurable per craft profile (§10.2). Users are told to set base rotation ≥ 2×limit in Pit House; we additionally clamp in software.
- **The 16 RPM slew** [S3]: the in-game wheel chases the target at ~96°/s. We do **not** rate-limit the player's input to match — the commanded target is instant, matching vanilla behavior, and the lag is *conveyed through FFB* by the sync-spring (§6.5), which is both honest and self-correcting: push faster than the craft's steering can follow and the wheel physically resists.
- **Detents**: `DETENT_MODIFIER` snaps the *commanded* angle to 45° steps client-side (vanilla Shift parity); optional FFB detent bumps at those angles (§6.5).
- **Hygiene**: never send NaN/Inf (vanilla has NaN paths we refuse to enter); clamp to ±limit before send; on disengage always send the stop packet, including from a shutdown hook.

### 5.4 Throttle injection

- Pedal (or wheel-mounted paddle/axis) → 0–15 int → `ThrottleLeverSignalPacket` [S2]. Target throttle lever selected the same way as the wheel (look + bind key), remembered per craft profile.
- **Hysteresis** so the quantized signal doesn't chatter at step boundaries: value changes only when the analog input crosses a step boundary by > 0.35 step. Send only on change (server range check at buffer 1 means the player must stay near the lever — the HUD indicates when the lever went out of range).
- Quantization to 16 steps is an upstream reality, not fixable client-side. A custom analog throttle block is explicitly deferred to Phase 4 (needs the server addon and new content; contribution upstream is the better path — tracked as an open question §12).
- `BRAKE` axis stays unbound in v1 (Simulated/Offroad has no brake input block to feed); reserved in config so bindings don't shuffle later.

### 5.5 Optional custom steering block — decided: later, not v1

A SimWheel-native steering column block (our own content, server addon required) could fix everything the vanilla blocks can't:

- **No 16 RPM slew** — configurable or instant target tracking, so hardware and virtual wheel stay 1:1 without the sync-spring carrying the difference.
- **True analog throttle** on the same block or a sibling lever (kills the 0–15 quantization at the source).
- **Server-authoritative back-drive** — the server could turn the block's rendered wheel from the joint torque, so *spectators* see the wheel jerk on a curb strike, which the vanilla one-way chain can never show.

It stays out of v1 deliberately: the whole point of the packet-level design is working on **public servers without any addon** (§8), and custom content forfeits that wherever the addon isn't installed. The vanilla-block path also keeps us honest — every feel feature has to work through the real upstream mechanics first. The right sequence is: ship v1 against vanilla blocks, propose an analog throttle upstream (§12), and only add our own block in Phase 3+ if upstream declines — as an *enhancement* with the vanilla path still first-class, never a replacement.

---

## 6. FFB path

### 6.1 Server: rig resolution

On `EngageRequest(wheelPos)`, the server builds an **`FfbRig`**:

1. Verify the wheel BE exists, player is in range, and the wheel sits on a sub-level (or static grid — rigs on unassembled builds are valid but produce zero hinge torque).
2. **Discover driven bearings**: walk the Create kinetic network from the wheel's shaft (same network id / RotationPropagator reachability) and collect every `SwivelBearingBlockEntity` on it.
3. **Gear ratio `g` per bearing**: Create maintains exact speed ratios inside a network. Sample `bearing.cogSpeed / wheel.generatedSpeed` during the first wheel motion (the wheel only generates while turning); until first calibration the rig reports `unratioed` and the client keeps hinge-source gain at 0 (cue sources still work). Cache per rig; re-sample on network change (Create fires kinetic updates; we also invalidate on any bearing list change).
4. Resolve each bearing's **hinge axis in world space**: bearing `FACING` normal transformed by its containing sub-level pose each sample.
5. Subscribe the rig to the telemetry sampler. One active rig per player; rigs are torn down on disengage, disconnect, wheel/bearing removal, or craft disassembly (→ `RigInvalidatedPacket`).

Multi-surface rigs (one wheel driving several bearings — e.g. both ailerons, or aileron+rudder mixes) sum naturally: **τ_column = Σᵢ gᵢ · τ_hinge,ᵢ** (virtual work; the sign of gᵢ carries the linkage direction).

### 6.2 Server: torque sources

Priority-ordered; the rig uses the best available and reports which (HUD shows it):

1. **`JointImpulseSource` (ground truth).** Per physics tick, via the accessor mixin [S6] + A2: `handle.getJointImpulses(lin, ang)`; `τ_hinge = (ang · hingeAxis) / timeStep`, reflected to the column through gᵢ. Contains everything the sim knows — aero, inertia, buffet, ground contact. Caveat verified against source: impulses include the servo motor's holding torque, which is exactly the "what the linkage must resist" quantity we want.
2. **`AeroModelSource` (fallback).** If constraint access breaks (Simulated refactor) or a rig has sail surfaces without bearings: recompute the game's own sail math (A5 — lift 0.475 / parallel drag 0.75 / directionless drag ≈0.0689 × air pressure) over the control surface's blocks about the hinge → same formula shape as the classic `M = Ch·q·S·c̄`, but with *the game's* coefficients so it feels like the sim it runs in.
3. **`CraftStateSource` (cues, always on).** From A3/A4: dynamic pressure → aero loading scale; angular rates → damper baseline; stall detection (lift-provider AoA proxy) → buffet envelope; ground contact events → rumble triggers. These are *modulation scalars*, not torques — the client synthesizes the actual high-frequency content locally (§6.5) so cue texture isn't limited to 20 Hz.

### 6.3 Server: sampling & wire format

- `TelemetrySampler` hooks `SablePostPhysicsTickEvent` (A1) and, for each active rig, appends one sample per physics substep into a small ring: `(τ_column_Nm: float, substep_dt: float)`.
- Each **game tick**, per rig, it flushes one `FfbTelemetryPacket`:

```
FfbTelemetryPacket {
  int   serverTick;
  byte  sourceKind;          // JOINT | AERO_MODEL | NONE
  byte  sampleCount;         // physics substeps this tick (typ. 1–4)
  float torqueNm[sampleCount];
  float virtualWheelDeg;     // where the kinetic wheel actually is
  float airspeedMs;          // |getVelocityRelativeToAir| at wheel pos
  float dynamicPressure;     // ½ρv² using dimension air pressure
  byte  flags;               // ON_GROUND | STALL_BUFFET | RIG_UNRATIOED | ...
}
```

~30–40 bytes at 20 Hz per engaged player — negligible. Sending the substep *array* (not just the last value) is the irFFB trick: the client reconstructs a 60–80 Hz torque signal from a 20 Hz packet stream.

Transients don't wait for the tick flush. When a physics substep observes a contact-impulse spike above threshold or a stall-onset edge, the sampler additionally sends a tiny `FfbEventPacket { byte event; float magnitude; }` immediately, shaving up to 50 ms of tick-batching latency off exactly the effects where latency is perceptible (§6.8). Rate-limited server-side (≤10/s per rig). The client treats events strictly as *triggers* for locally synthesized transients, never as torque values — a duplicated or malformed event can at worst make the wheel rumble, never snap it.

### 6.4 Client: reconstruction

`TelemetryBuffer` (in `ffb/`, pure JVM):

- Samples are timestamped onto a server-tick timeline; playback runs **delayed by 1.5 ticks** (75 ms) with linear interpolation between samples — jitter-free at the cost of latency that is fine for *loading* torque (the latency-critical feel comes from the local layer below).
- On gap (lost packet / server stall): extrapolate at last slope for ≤100 ms, then **fade to zero over 200 ms** (never hold a stale torque — §7). Recovery re-ramps over 100 ms.
- A one-pole low-pass (default fc ≈ 20 Hz, configurable) smooths substep steps before mixing.

### 6.5 Client: local feel layer (the part that makes it feel like a sim)

Telemetry gives the low-rate *load*; feel comes from effects computed at 1 kHz **locally**, from the latest telemetry scalars + the live hardware wheel state (zero added latency):

| Component | Formula (per 1 kHz step) | Driven by |
|-----------|--------------------------|-----------|
| **Sync-spring** | τ = −k_s·(θ_hw − θ_virt) − c_s·θ̇_hw | `virtualWheelDeg` — this is the load-bearing trick: it renders the 16 RPM kinetic slew as honest mechanical resistance, keeps hardware and virtual wheel converged (no desync accumulation), and gives the wheel "weight" even at zero airspeed. k_s scales with dynamic pressure between configurable min/max. |
| **Damper** | τ = −c_d(q)·θ̇_hw | dynamic pressure |
| **Friction** | τ = −min(μ, μ·|θ̇|/ε)·sgn(θ̇_hw) | constant; hides quantization noise |
| **Detents** | short cosine bump crossing each 45° | only while `DETENT_MODIFIER` held (parity with Shift snapping) |
| **Stall buffet** | band-limited noise × envelope, 8–14 Hz | `STALL_BUFFET` flag + q |
| **Ground rumble** | filtered noise burst | `ON_GROUND` + airspeed |

Two rules keep the local layer honest against the telemetry:

- **Virtual-wheel predictor.** The sync-spring does not wait for `virtualWheelDeg` at 20 Hz: the client dead-reckons the kinetic wheel at 1 kHz — it knows the commanded target (it sent it) and the fixed 16 RPM slew — and corrects toward the telemetry value with a small gain (snap on >5° disagreement, e.g. after a kinetic network change). The strongest feedback component thus runs against a zero-latency estimate, with telemetry only keeping it honest. The same predictor, fed by the block entity's synced angle instead, is the spring source in no-addon degraded mode (§8).
- **No double-counting.** Telemetry torque already contains *all* simulated physics (aero damping, surface inertia, contacts). Local components render only what telemetry structurally cannot: the linkage itself (sync-spring — you cannot out-turn the 96°/s slew), loop stability (damper — kept small by default precisely because aero damping is already inside the joint torque), texture above the telemetry Nyquist rate, and UX detents. When a local effect and the telemetry fight, the local gain is what gets turned down.

Mixer: `τ_out = softknee( τ_telemetry + τ_spring + τ_damper + τ_friction + τ_effects )` — a soft-knee compressor (knee at ~65 % of the user torque clamp, ≈3:1 above it) rather than a bare sum-and-clamp, so a heavily loaded craft near the limit still feels *proportional* instead of hitting a wall; the safety chain's hard clamp (§7) remains downstream and untouched. Everything is in **Nm**, converted to the device's normalized range using the configured device max torque (R9 = 9 Nm). Working in Nm end-to-end is what makes per-craft tuning portable across wheelbases.

Sign convention: positive column torque = clockwise from the pilot's view. Per-rig auto-calibration on first engage ("push test": deflect, compare hinge torque sign against deflection) with a manual invert flag in the profile — never trust geometry inference alone.

### 6.6 Device output

- FFB thread computes at 1 kHz; device writes are coalesced to a configurable 100–200 Hz (default 150) — above that, some PID firmwares choke (SDL issue #12511); the base's own firmware interpolates between updates.
- Session lifecycle: `ffbStart()` on first engage (effect created at gain 0 → ramp §7), effect persists across engage cycles within a session, `ffbStop()` on device change/quit; `panic()` wired to every abnormal path.
- **Phase 3 experiment (config-gated, default off)**: render the sync-spring as a device-native `SDL_HAPTIC_SPRING` condition effect whose center point tracks the predicted virtual wheel at the device update rate, leaving only telemetry torque + textures in the constant-force effect. A firmware-side spring is unconditionally stable at any software update rate; if the software spring shows oscillation on a direct-drive base at high k_s (a classic failure mode of software-rendered springs), this is the escape hatch. Requires the `FFB_SPRING` capability; feel parity validated with the record/replay harness.

### 6.7 Vehicle matrix — where the torque comes from, per craft type

The rig resolver never special-cases craft types — it discovers whatever the steering wheel's kinetic network actually drives, and the source priority (§6.2) does the rest. But it's worth being explicit about what that yields per vehicle class:

| Craft | Steering drives | Torque path | What the hands feel |
|-------|-----------------|-------------|---------------------|
| Fixed-wing plane | Ailerons/elevator/rudder on swivel bearings | Joint ground truth | Loading grows with dynamic pressure; the wheel lightens as airspeed bleeds; buffet near stall; sustained trim forces in turns. The design's home turf. |
| Airship / blimp | Large rudder(s) on bearings | Joint ground truth, low q | Heavy, slow, inertia-dominated — honest for a multi-ton rudder; the spring floor keeps the wheel from feeling dead at 5 m/s. |
| Ground vehicle (Create: Offroad), *physical* steering linkage | Steered-wheel assembly on a bearing-driven sub-level | Joint ground truth | The dream case for a sim wheel: genuine kingpin torque — self-aligning torque from tire side force, per-block bumps, curb strikes — because terrain contact forces on the steered sub-level react through the very joint we read. |
| Ground vehicle, *non-physical* steering (if Offroad wheels turn out to be body-attached force providers) | Nothing hinged | `CraftStateSource` synthesis | The classic racing-game fallback: τ ≈ f(lateral acceleration, yaw rate, speed) from A3 — responsive and plausible, but modeled rather than solver-true. Which of the two Offroad actually is is an open question (§12); the resolver auto-detects by what it finds on the kinetic net. |
| Boat (Deep Seas addon) | Rudder — hinge mechanism unverified | Joint truth if bearing-hinged, else synthesis | Verify when targeting boats (§12). |
| Thrust-vector / exotic craft | No bearings resolve | Cue-only | Spring, damper, rumble from craft state; the HUD badge says so honestly. |

Two consequences worth calling out:

- **Torque magnitude varies by orders of magnitude across this table** (a 2-block elevon vs. a 40-block airship rudder: hinge moment scales with area × chord). The soft-knee mixer (§6.5) keeps heavy craft proportional near the limit; per-craft profiles (§10.2) carry a hinge-gain trim; and a one-time **"trim run"** suggestion (measure RMS column torque over the first minute of flight, propose a gain) makes a new craft usable without manual tuning.
- **Mixed rigs come for free**: a wheel driving ailerons *and* a steered axle (amphibious and taxiing contraptions) just sums Σ gᵢτᵢ — on the runway the ground-contact terms dominate, after rotation the aero terms take over, with no mode switch anywhere.

### 6.8 Surfaces, contacts, and how reactive this really is

What Simulated's physics actually propagates into our torque source, and with what latency:

- **Contacts on the hinged assembly are felt directly.** Sub-levels are real Rapier rigid bodies with colliders. Any contact force on the *hinged* body — a control surface scraping the runway, a steered wheel hitting a curb, a block pushing up into a steered axle — produces a reaction in the hinge constraint, and `getJointImpulses` [A2] *is* that reaction. Nothing synthesized; it's the solver's own answer.
- **Contacts on the craft body are felt indirectly.** A strike on the fuselage reaches the hinge only through inertial coupling: the body jolts, the hinged assembly's inertia resists, and the joint carries τ ≈ m·a·r for the surface's mass offset from the hinge line. Physically correct (a hard landing reaches a real aircraft's stick the same way) but small for light surfaces — which is why `ON_GROUND`/contact cues also drive the local rumble synthesizer.
- **Upstream, the control chain is one-way — our FFB is the back-drive.** The steering wheel block is a kinetic *generator* chasing a target; nothing in Simulated pushes forces back into it, and the in-game wheel model never jerks on impact. The back-drive loop closes through the player's hands: a curb strike torques the hardware rim; if it overpowers grip, the rim moves; the moved rim is new input; the craft steers. That emergent loop is exactly how a real steering column behaves — and one more reason the §7 clamp is non-negotiable.
- **Terrain texture is granular and can alias.** Blocks are 1 m; at 20 m/s a wheel crosses block seams at 20 Hz, right at the telemetry substep floor (substeps observed 1–4 per tick → 20–80 Hz). Discrete, large strikes come through the joint cleanly; continuous high-speed rolling texture would alias, so it is *synthesized* locally (speed-scaled noise) with the joint path providing the events and the envelope.
- **Surface-material variety depends on upstream friction data.** If Sable assigns per-block friction/restitution to colliders, ice-vs-dirt reaches the joint free of charge; if collider material is uniform, material feel is out of scope for v1 (open question, §12).

Reactivity budget — what "how reactive is it really?" comes down to:

| Feedback component | Path | Latency to the rim |
|--------------------|------|--------------------|
| Spring, damper, friction, detents | 1 kHz local loop against live wheel state | ≤ 10 ms (device write interval + base firmware) — perceptually instant |
| Wheel weight tracking the craft's actual steering | Virtual-wheel predictor (§6.5) | ~0 ms (prediction), corrected at 20 Hz |
| Contact / stall transients | `FfbEventPacket` (§6.3) → local synthesis | singleplayer ≈ one physics substep + a few ms; multiplayer ≈ one-way ping on top |
| Sustained loading (hinge torque, q-scaling) | Telemetry: tick flush (0–50 ms) + 1.5-tick interpolation delay (75 ms) + smoothing group delay (~8 ms) | ≈ 100–135 ms singleplayer, + ping in multiplayer — imperceptible for slowly varying load, and by design nothing sharp rides this path |

For calibration: iRacing ships 60 Hz torque telemetry and its FFB is considered excellent; our reconstructed torque stream is 60–80 Hz (substep arrays over 20 Hz packets) with all sharp content generated locally at 1 kHz. This architecture sits inside the envelope proven sim-FFB bridges (irFFB, SimHub) already occupy.

---

## 7. Safety chain (non-negotiable)

The last stage before the device, in fixed order, no code path around it — the mixer's output goes in one end, the device write comes out the other. Pure functions, fully unit-tested:

1. **Master gain + ramp-in**: every engagement starts at gain 0 and ramps to the user gain over 500 ms. Re-engage after FAULT requires a deliberate user action.
2. **Torque clamp**: |τ| ≤ user max (default **2.5 Nm** out of the box — strong enough to feel, safe for wrists; the user consciously raises it).
3. **Slew-rate limiter**: |dτ/dt| ≤ limit (default 25 Nm/s at default clamp, scales with clamp). Kills sign-flip snaps from bugs, extrapolation errors, or GC-delayed bursts.
4. **Watchdog**: if the FFB thread misses its deadline by >150 ms, or telemetry goes stale (§6.4), output fades to zero — never freezes at a value.
5. **Panic conditions** → immediate `panic()` + FAULT state: any exception in the FFB loop, device error/disconnect, JVM shutdown hook, disengage-with-exception.
6. **Focus/GUI rule**: window unfocused or any screen opened ⇒ treated as disengage (fade out, stop packet sent). No torque while the player isn't actively flying.

Documentation duties that ship with v1 (README + first-run HUD notice): MOZA Pit House must be in game-FFB mode with in-base spring/damper at zero; hands off the rim on first engage of an untested craft profile.

---

## 8. Degraded modes — explicit, tested behavior for every combination

| Situation | Steering | Throttle | FFB |
|-----------|----------|----------|-----|
| Full install (client + server addon) | ✅ analog | ✅ | ✅ ground truth |
| **Server without addon** (vanilla Simulated — public servers) | ✅ analog (S1/S2 are vanilla packets!) | ✅ | **Client-estimated**: sync-spring to the BE's synced angle + damper/friction from client-visible sub-level pose deltas. No hinge telemetry — honest "reduced FFB" HUD badge. |
| Client without FFB hardware (any joystick) | ✅ | ✅ | off |
| GLFW backend only (Phase 1, or SDL unavailable) | ✅ | ✅ | off (input still first-class) |
| Telemetry loss mid-flight | ✅ | ✅ | fade-out → client-estimated until recovery |
| Craft disassembled / wheel broken while engaged | auto-disengage, stop packet, fade out | — | — |

The "server without addon" row is a deliberate architectural constraint, not an accident: **input must never require the server addon**, because that's what makes the mod usable on day one on public Simulated servers. It also cleanly splits the release: the client half is a normal client mod; the server half is an optional enhancement.

Handshake: Veil channel version negotiation (S7 pattern). Client detects server addon presence at login; `EngageRequest` timeout (2 s) additionally guards against filtered channels.

---

## 9. Public API (`api/`, for the addon scene)

Small on purpose; semver'd independently of internals from 1.0:

```java
// Let other mods (Cosmonautics, Propulsion: Simulated, ...) feed torque:
interface TorqueSourceProvider {          // registered server-side
    @Nullable TorqueSource createFor(FfbRigView rig);   // rig: wheel pos, bearings, sub-level
}
// Let other mods add client-side texture effects:
interface FeelEffectProvider {            // registered client-side
    @Nullable FeelEffect createFor(TelemetryView t);    // 1 kHz callback, output in Nm, safety-chained like everything else
}
// Read-only wheel state for HUD/overlay mods:
interface SimWheelClientApi { EngagementState state(); float hardwareAngle(); float virtualAngle(); }
```

Everything third-party flows through the same mixer and safety chain — no API consumer can bypass the clamp/slew/watchdog.

---

## 10. Configuration, tuning, project hygiene

### 10.1 Client config (TOML + in-game screen)
Device bindings (per-axis: device, index, calibration, curve, deadzone), FFB device selection, master gain, max torque Nm, device max torque Nm, update rate, telemetry smoothing fc, all local-feel gains, mode A/B toggle, safety limits (clamp floor/slew are user-raisable but have hard ceilings compiled in).

### 10.2 Craft profiles
Per-craft (keyed by wheel block's craft, nameable): steering ratio, hinge gain trim, invert flag, spring min/max, buffet gain. Stored client-side; shareable as JSON snippets (public-release feature: community profile exchange).

### 10.3 Server config
Enable/disable FFB telemetry, max rigs per player (1), max bearings per rig (guard against degenerate kinetic networks — default 8), telemetry rate cap, permission hook (Neo permissions) for engage.

### 10.4 Compatibility health check
At startup, reflectively verify every surface in §2 (constructor signatures, field presence for the two mixins, event classes). Any failure ⇒ log one loud, clear line + disable the dependent feature (per-surface granularity: e.g., joint source off but aero fallback on) + HUD badge. Never crash the game over an integration break; never silently misbehave either. This is the single most important public-release robustness feature: Simulated updates monthly, we degrade gracefully and patch fast.

### 10.5 Testing without a cockpit
- `ffb/` and `hal/` logic (filters, buffer, mixer, safety chain, hysteresis, state machine) are pure-JVM **unit tests** — the safety chain especially gets exhaustive property tests (clamp/slew invariants under random input streams).
- **Record & replay**: the telemetry receiver can dump packet streams to file; the FFB engine replays them headless (or against a real wheel) for tuning without launching Minecraft. This is also the regression harness for feel changes.
- **Feel acceptance checklist**: a reference-maneuver spec — taxi turn, takeoff roll, climbing turn, stall entry and recovery, max-speed dive, aileron doublet, hard landing, curb strike at speed — with the expected sensation written down for each. Every tuning change replays the golden traces *and* gets flown against the checklist, so "feels good" is a testable claim rather than a vibe. Written during Phase 0 feel scouting, refined on hardware in Phase 2.
- `NullWheelDevice` (scriptable fake) for engagement/injection integration tests in a dev client.
- Real-hardware checklist (R9 on Win + Linux 6.15 pidff) kept as `docs/HW-TESTING.md`, tracking RESEARCH.md's open items: high-rate `SDL_UpdateHapticEffect` behavior [#12511], pedal enumeration path, steering-axis selection.

### 10.6 Licensing for public release
- Our code: **MIT** (matches Simulated-Project's code license; final call is the repo owner's).
- Sable: PolyForm Shield 1.0.0 — dependency use is fine; we never vendor its code. The `AeroModelSource` re-derivation stays a clean-room reimplementation of the published formulas from *Simulated's* MIT'd `BlockSubLevelLiftProvider` reference, which we may quote — it lives in Simulated core (MIT), not in Sable.
- SDL3: zlib. `libsdl4j` fork: confirm license before shipping (open item); our haptic port offered upstream.
- Simulated assets are ARR — we ship none of them; our HUD art is original.

---

## 11. Build order (revised phases with exit criteria)

**Phase 0 — feel scouting (no code).** Fly with Create: Tweaked Controllers mapping the R9 axes to link channels. Deliverable: notes on what feels wrong (slew, quantization, camera) feeding defaults above. *Exit: notes committed.*

**Phase 1 — input (GLFW, client-only).** `hal/glfw` + bindings/calibration UI + engagement state machine + latch-mode steering + throttle hysteresis + HUD. Works on servers without the addon from day one. *Exit: fly a stock Simulated plane start-to-landing on wheel+pedals, on a vanilla server, no keyboard for flight controls.*

**Phase 2 — FFB core.** `SDL_haptic` JNA port; `Sdl3WheelDevice`; FFB thread + buffer + safety chain (fully unit-tested **before** first hardware plug-in); server addon: rig resolver + `JointImpulseSource` + sampler + telemetry; sync-spring & damper. *Exit: hinge-moment torque felt in hand tracking airspeed & deflection; all §7 trips demonstrated on hardware; record/replay harness working.*

**Phase 3 — feel & degraded modes.** Buffet, ground rumble, detents, friction; `AeroModelSource` + `CraftStateSource`; client-estimated fallback FFB; craft profiles; compat health check; Linux pass. *Exit: the §8 matrix demonstrated row by row.*

**Phase 4 — public release & ecosystem.** `api/` freeze at 1.0, docs site/README rewrite, community profile format, CurseForge/Modrinth packaging, upstream conversations (analog throttle block in Simulated; haptics PR to libsdl4j; optional native 1 kHz helper process evaluation — only if JVM-side quality proves insufficient, which Phase 2 will answer with data).

---

## 12. Risks & open questions

| Item | Exposure | Mitigation / next step |
|------|----------|------------------------|
| `SwivelBearingBlockEntity.handle` access breaks on Simulated update | FFB ground truth | Compat health check → auto-fallback to `AeroModelSource`; ask upstream for a public constraint getter (they're addon-friendly; CTC is "explicitly compatible") — ideally an official `getConstraintHandle()` lands and mixin count drops to ≤1 |
| Joint impulses semantics (does `getJointImpulses` return exactly the per-substep total including motor?) | Torque correctness | Verify empirically in Phase 2 week 1 with a logging rig on a test craft; formula isolated in one class either way |
| SDL3 high-rate update on R9 (#12511 class of bugs) | FFB smoothness | Rate configurable 60–333 Hz; DirectInput-native helper process is the researched escape hatch (Phase 4, only if needed) |
| Steering packet gains server-side validation upstream | Latch mode | We already self-impose range + held semantics, so a vanilla-equivalent validator shouldn't reject us; mode A is the fallback |
| Kinetic ratio sampling (first-motion calibration) mis-signs on exotic gearing | Wrong-direction FFB | Gain 0 until calibrated + push-test sign check + per-profile invert; worst case is weak wrong feel at 0 gain, never a snap |
| GLFW polling adds a frame of input latency vs SDL | Input feel | Acceptable for Phase 1; SDL3 becomes the input backend too in Phase 2 |
| Sable physics substep count/rate unknown ahead of time | Buffer tuning | Wire format is rate-agnostic (`sampleCount` + per-sample dt); measure in Phase 2 |
| Pedals enumerate via base vs standalone | Binding UX | Per-axis device binding already covers both; verify on hardware |
| Simulated adds a native analog throttle | Our hysteresis path obsolete (good) | Track upstream; our packet layer isolates the change to `ThrottleInjector` |
| Offroad steering internals: bearing-hinged steered axles vs body-attached wheel forces | Ground-vehicle FFB quality (real kingpin torque vs synthesized self-aligning torque) | Read Offroad source early in Phase 2; both paths already designed (§6.7) and the rig resolver auto-detects which applies. First evidence (`testdata/tones_template_race_car.nbt`): `offroad:wheel_mount` is kinetic-driven and that build steers via a redstone `directional_gearshift` (digital skid-steer, no bearings) — so `CraftStateSource` synthesis is likely the common ground-vehicle path; still check whether `wheel_mount`'s suspension constraint is readable |
| Per-block friction/restitution on Sable colliders | Surface-material feel (ice vs dirt vs stone) | Inspect collider setup in Sable; if uniform, material feel is out of scope for v1 and the local rumble stays speed-scaled only |
| Software sync-spring oscillates on direct-drive at high k_s | Feel quality | Damper always paired with the spring; device-native `SDL_HAPTIC_SPRING` experiment (§6.6) is the escape hatch |

---

## 13. Why this design holds up

- **The physics does the physics.** The primary torque source is the constraint solver's own reaction — every force the sim knows about arrives in the player's hands for free, and the FFB can never disagree with what the craft actually did.
- **Latency lives where it's cheap.** Low-rate telemetry carries slowly-varying load; everything latency-critical (spring, damper, texture) is synthesized at 1 kHz against the live wheel state. This is the architecture every serious sim bridge (irFFB, SimHub) converged on.
- **The sync-spring turns an upstream limitation into feel.** The 16 RPM kinetic slew isn't fought or hidden — it's rendered as mechanical resistance, which simultaneously solves hardware/virtual desync.
- **Safety is a pipeline stage, not a convention.** Nothing — not our code, not API consumers, not a GC pause — writes to the device except through the clamp/slew/watchdog chain.
- **Every fragile touch point is enumerated, faceted, and checked at startup**, with a defined degraded mode per failure. Public release means surviving upstream updates gracefully; that behavior is designed in, not patched in.
