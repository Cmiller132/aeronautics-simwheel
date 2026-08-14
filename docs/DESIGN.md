# Aeronautics SimWheel — Architecture & Design

Design for wheel input and true force feedback against **Create: Simulated / Aeronautics / Offroad (The Simulated Project)** on NeoForge 1.21.1, targeting the MOZA R9 first and any DirectInput/PID FFB wheel by extension.

This document is grounded in the actual Simulated-Project v1.3.x and Sable 2.0.0 sources (file references verified August 2026). Background findings live in [`RESEARCH.md`](RESEARCH.md); this document is the *decision record* — what we build, how the pieces fit, and why.

**The governing decision (2026-08-13, superseding all earlier control-path designs):**
**the `sim_steering_wheel` block is the only control surface.** Sim hardware talks to our block and to nothing else — no `SteeringWheelPacket` injection into Simulated's stock wheel, no typewriter/lectern piggybacking, no standalone link-controller block. The block absorbs the SimControl block's link-transmitter role so it can replace linked typewriters outright. Consequence, accepted: the mod must be installed on the server; the zero-addon compat story is gone by explicit decision.

---

## 1. Scope

### Goals

1. **Sim-racing-grade steering** into our own steering wheel block: 1:1 direct float angle authority (no upstream slew chase), configurable lock, soft-lock end stop on the rim.
2. **Analog pedals** — throttle, brake (and clutch) — carried to existing craft wiring over Create's redstone-link medium, plus the native Offroad per-wheel brake input.
3. **True force feedback** computed from the *actual* physics simulation — for cars, the game's own tire math read from wheel mounts — streamed to the wheelbase. Not canned rumble.
4. **Robust by default**: a 9 Nm direct-drive base must never be able to hurt anyone because of a bug, a lost packet, or a GC pause. Control loss is neutral, never latched — with an optional dead-man's failsafe brake.
5. **Publishable**: clean module boundaries, a small public API, and no fragile reach into Simulated internals beyond a short, documented list (currently: **zero mixins**).

### Non-goals

- **Valkyrien Skies / Clockwork support.** Different ecosystem, different MC version. Simulated-only, permanently.
- Gamepad/HOTAS general-purpose mapping (Create: Tweaked Controllers' turf). We handle *wheel + pedals + FFB*.
- Reimplementing physics. Sable/Rapier and Offroad's own formulas are the single source of truth; we read from them and feed their existing inputs, we never second-guess them.
- Driving the stock steering wheel or vanilla-Minecraft vehicles.

---

## 2. The verified integration surface

Everything below was read from source, not inferred. Each row can break when Simulated updates, so each row gets a startup health check (§10.4) before public release.

| # | Surface | Where (Simulated-Project v1.3.x) | What we use it for | Access | Risk |
|---|---------|----------------------------------|--------------------|--------|------|
| S1 | Create redstone-link network: `IRedstoneLinkable`, `Frequency.of(ItemStack)`, `Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork/updateNetworkOf` | Create 6.x `content/redstone/link/*` | The block's link channels (throttle/brake/clutch/buttons + legacy steer pair). Networks combine transmitters by **max**; receivers output the transmitted 0–15 verbatim. | Public API (Tweaked Controllers ships the same pattern) | Low |
| S2 | `IDirectionalAnalogOutput` | `simulated/api/` | Our block implements it so Simulated's comparator mixin (and any other directional reader) sees the stock steering wheel's output contract. | Public interface (one method) | Low |
| S3 | Wheel mount control inputs | `offroad/content/blocks/wheel_mount/WheelMountBlockEntity` | **Steering**: analog redstone on the mount's side faces, `left − right` signed −15…+15 → ±30° lock, 0.4/tick yaw lerp. **Brake**: analog redstone into the block *above* the mount, 0–15 → `(0.075 + brake×0.3)×min(μ,1)` velocity-proportional drag + `(1−brake)` drive cut. **Drive**: the Create kinetic network (`getSpeed() × 1.75 × μ`). We *feed* these inputs; we add nothing to the physics. | Plain redstone / link receivers | Low — data-driven game mechanics |
| S4 | Stock steering wheel output convention | `simulated/content/blocks/steering_wheel/SteeringWheelBlock.getAnalogOutputSignalFrom` | Mirrored exactly by our block (clockwise face = positive 0–15, ccw = negative, facing = held 15/0, east/south sign flip) so a block swap behaves identically. | Convention copied, no code reach | Low |
| S5 | Veil networking / NeoForge payloads | `SimWheelInputPacket` | One serverbound input frame packet. | Standard payload API | Low |
| S6 | `Sable.HELPER.getContaining(...)` + `SubLevel.logicalPose()` | `dev.ryanhcode.sable.*` | Transforming the wheel's BlockPos to world space for the seat-range check on assembled (moving) craft. | Public API | Low |
| S7 | `BlockEntitySubLevelActor` + `plot.getBlockEntityActors()` + `ServerSubLevel.getMassTracker()`/`getVelocity` | `dev.ryanhcode.sable.api.*`, `sublevel/*` | Phase 2a rig: the wheel BE implements the actor interface for per-substep callbacks; the plot's actor map enumerates the craft's wheel mounts; mass tracker + velocity feed the load/slip reads. | Public API | Low |
| S8 | `WheelMountBlockEntity` state reads: `getLerpedExtension(1)` (server = raw wheel→terrain distance), `getHeldItem()`+`TIRE` component, `getBehaviour(ScrollValueBehaviour.TYPE)` (suspension strength), side-face signals; **reflective**: `touchingFriction`, `chasingYaw` (private, no accessor) | `offroad/.../wheel_mount/` | The telemetry sampler mirrors the mount's own force formulas from these. The two reflected fields are resolved once at class load; on failure the sampler degrades (μ=1, yaw from the public signal without the 0.4 lerp) with one loud log line, and the ground-telemetry gametest fails loudly (`fullFidelity()`). | Public API + 2 reflective field reads | Medium — the only non-public reach in the mod; health-checked |

Parked for later phases (verified, not currently used): `SwivelBearingBlockEntity`'s `RotaryConstraintHandle` PD servo (plane FFB, needs an accessor mixin — Phase 4 with kinetic output), `SablePre/PostPhysicsTickEvent` substep sampling + `RigidBodyHandle` state reads (Phase 2a telemetry), `getJointImpulses` semantics (§12), `BlockSubLevelLiftProvider` aero math (aero fallback).

**The ground-vehicle mechanical chain this design drives** (verified end to end, RESEARCH.md §3):

```
player hand → hardware wheel → [this mod] → SimWheelInputPacket
  → sim_steering_wheel BE (direct angle authority, ±lock)
  ├─ side-face analog redstone (stock convention, ±15) → wheel-mount steering faces (±30°)
  ├─ BRAKE link channel → receiver above each mount → native per-wheel brake (0–15)
  ├─ THROTTLE link channel → receiver → drivetrain (clutches/gearshifts; drive is kinetic RPM)
  └─ BTN_1..8 link channels → anything
  … tire forces (v_side × 0.6 × μ × load) turn the craft, and the same
    formula read back is the FFB torque source (Phase 2a)
```

---

## 3. System overview

One mod jar, strictly layered. `engine/` (hal + ffb) is a separate Gradle module with **zero Minecraft imports** — the module boundary enforces it.

```
┌────────────────────────────────────────────────────────────────────────┐
│  CLIENT                                                                │
│                                                                        │
│   hal/  Device layer (engine module, no Minecraft)                     │
│   ├── WheelDevice: axes, buttons, ffb capability                       │
│   ├── GlfwWheelDevice   (input only — current)                         │
│   └── BridgeWheelDevice (input + torque via MOZA sidecar — Phase 2b)   │
│          ▲                          ▲                                  │
│          │ poll (render thread)     │ update @ 100–200 Hz              │
│   client/                        ffb/  FFB engine (engine module)      │
│   ├── WheelInput                  ├── SoftLock, FeelEffects            │
│   ├── SimWheelLink (engage/send)  ├── TelemetryBuffer (Phase 2a wire)  │
│   ├── FfbController (250 Hz loop) ├── Mixer, SoftKnee                  │
│   └── SimWheelHud                 └── SafetyChain (final, not          │
│                                       bypassable) ──► device           │
├────────────────────────────────────────────────────────────────────────┤
│  NETWORK   serverbound: SimWheelInputPacket (≤20 Hz on change + HB)    │
│            clientbound: BE update tag (angle/lock);                    │
│                         FfbTelemetryPacket + FfbEventPacket (Phase 2a) │
├────────────────────────────────────────────────────────────────────────┤
│  SERVER (required — the block lives here)                              │
│   content/                                                             │
│   ├── SimSteeringWheelBlock       (redstone convention, interactions)  │
│   └── SimSteeringWheelBlockEntity (angle authority, link channels,     │
│        seat mutex, timeout/failsafe, future telemetry attach point)    │
└────────────────────────────────────────────────────────────────────────┘
```

Threading (client): render thread polls devices and publishes an immutable snapshot per tick; a dedicated high-priority FFB thread (currently 250 Hz, 1 kHz with the bridge) computes feel through the safety chain and writes the device. The FFB thread never touches Minecraft objects.

---

## 4. Module & package layout

```
engine/                     # pure JVM, unit-tested, zero Minecraft imports
├── hal/                    # WheelDevice, WheelAdapter, AxisProcessor, glfw/, bridge/
└── ffb/                    # SafetyChain, SoftLock, FeelEffects, TelemetryBuffer,
                            # Mixer, SoftKnee, EngagementStateMachine, …
mod/  dev.aeronauticssimwheel
├── content/                # SimSteeringWheelBlock + BE + SimChannel
├── network/                # SimWheelInputPacket
├── client/                 # WheelInput, SimWheelLink, FfbController, SimWheelHud
├── registry/               # block/item/BE registration
└── gametest/               # headless server tests
```

**Mixin count: zero.** Every reach into the stack is public API or plain game mechanics (redstone, link network). The first mixin returns with wheel-mount linking (Phase 3, §5.5) and gets the §10.4 health-check treatment then.

---

## 5. The Sim Steering Wheel — the only control surface

### 5.1 Device layer (unchanged)

`WheelDevice` (axes/buttons/FFB capability) with per-axis independent binding — `STEERING`, `THROTTLE`, `BRAKE`, `CLUTCH` — through `AxisProcessor` (calibration, deadzone, curve, smoothing). `GlfwWheelDevice` polls Minecraft's own LWJGL on the render thread; the demo device (sine sweep) enables hardware-free testing. Device buttons 2–9 map to `BTN_1..8` (0/1 are ENGAGE and DETENT_MODIFIER).

### 5.2 Occupancy: the seat is the mutex

- **Engage**: sit in the craft's seat, look at the wheel, press ENGAGE. Client requires an input device, `isPassenger()`, and the crosshair on a `sim_steering_wheel`.
- **Server validation, every input frame**: sender must be riding something, and within 8 blocks of the wheel's *effective* position — the wheel's BlockPos transformed through its Sable sub-level pose, so moving craft validate correctly (a raw BlockPos distance would reject every assembled craft).
- **One driver**: the first accepted frame latches `(user, seat)`; frames from other players are dropped until the driver goes silent (timeout) or leaves the seat. Leaving the seat disengages client-side and sends the neutral frame.
- Multi-station craft (two wheels, two seats) work naturally — each wheel latches its own driver. Multiple wheels per seat / explicit persisted seat bindings are a v2 extension (§5.5).

### 5.3 The block

**Identity.** `aeronautics_simwheel:sim_steering_wheel`, horizontal-facing, placed like the stock wheel. Look-alike sibling of Simulated's wheel (own art, their aesthetic) — placeholder cube until the art pass. The BE is server-authoritative for everything.

**Steering — direct authority.** The input packet carries steering as −1…1 of the block's configured lock. The BE sets the target immediately and chases it under a *sanity* slew clamp only (1080°/s ≈ 54°/tick, vs. the stock wheel's 96°/s) — hardware and in-game wheel stay 1:1 for any human input; the clamp exists to bound teleport-snaps from bugs, not to be felt. Lock is per-block configurable (±180/270/360/450/540/720/900/1080°, default ±450°).

**Steering output — the stock convention, exactly** (S4): clockwise face emits positive steer 0–15, counterclockwise negative, the facing face emits engaged 15/0 (the stock "held" output), east/south facings flip sign so physical left/right is placement-independent, and a <0.99° deadband reads 0. Emitted as vanilla weak power (`getSignal`) *and* via `IDirectionalAnalogOutput` *and* as a vanilla comparator magnitude — so wheel mounts, wire, and both comparator kinds all read it. A stock-wheel → sim-wheel block swap changes nothing downstream.

**Link channels — the typewriter replacement.** Everything that isn't the steering column transmits on Create redstone-link frequencies, one `IRedstoneLinkable` transmitter per bound channel (S1):

| Channel | Kind | Source | Notes |
|---|---|---|---|
| `THROTTLE` | analog 0–15 | throttle pedal | drives existing drivetrain wiring |
| `BRAKE` | analog 0–15 | brake pedal | → receivers above wheel mounts = native per-wheel brake |
| `CLUTCH` | analog 0–15 | clutch pedal | unbound by default |
| `STEER_LEFT` / `STEER_RIGHT` | analog 0–15 | signed steering split | **legacy link-steered craft only**; default unbound |
| `BTN_1..8` | digital 15/0 | wheel buttons | momentary or toggle (client-side mode; all momentary until the config UI) |

Each channel binds to a frequency item-pair ("same item twice", the typewriter idiom). Migrating a typewriter car is **rebind, not rewire**: bind `THROTTLE` to the frequency the old W key used, `BRAKE` to the brake key's, and the existing receivers/gearshifts work — proportionally now. Quantization to 0–15 is the link medium's own resolution; accepted and stated. The BE is the future float drive-by-wire seam.

**Timeout & failsafe (control loss is neutral, never latched).** Input silence for 30 ticks (the linked-controller convention): steering target recenters (through the slew clamp — a ramp, not a step), every channel zeroes, the driver latch releases — **except `BRAKE`, which latches the configured failsafe level** (0–15, default 0 = off). With failsafe set, an unattended or disconnected driver's craft brakes to a stop instead of coasting. Same path runs on block break and disengage.

**Client sync.** Angle/lock/engagement sync to clients via the BE update tag on quantized-signal changes — enough for the HUD and the future renderer (spectators see the wheel move).

**Placeholder config UX** (until the proper screen): sneak-right-click cycles a config cursor over each channel, then `STEERING_LOCK`, then `FAILSAFE_BRAKE`; right-click with an item binds the targeted channel to (held, held); right-click empty-handed cycles the targeted setting's presets or describes the targeted channel's binding.

### 5.4 The input packet

`SimWheelInputPacket(BlockPos, steering, throttle, brake, clutch: float, buttons: varint)` — sent on change (>0.5% any axis, any button edge) plus a 10-tick heartbeat to feed the timeout; NaN/Inf rejected server-side; validation per §5.2. The neutral frame (all zero) is sent on every disengage path including logout.

### 5.5 Deferred extensions (decided, sequenced, not in this build)

1. **Direct wheel-mount linking (Phase 3, the flagship upgrade).** A linker interaction: click the wheel, click each wheel mount — the link is stored as craft-local positions in the wheel BE. Linked mounts take their *steering signal* and *brake strength* as floats from the wheel instead of the redstone reads — the same native formulas at un-quantized resolution (steer: exact angle into the ±30° lock with per-mount invert; brake: float 0–1 into `(0.075 + b×0.3)`). **A resolution upgrade of existing inputs, not new physics.** Needs the first mixin (override the two signal reads in `WheelMountBlockEntity`); degrades to stock redstone behavior on upstream churn, health-checked per §10.4. Unlinked mounts stay 100 % stock forever — redstone remains supported.
2. **Kinetic output (Phase 4).** A shaft socket + proportional-speed generator (`speed ∝ angle error`, capped ~128 RPM) for swivel-bearing planes; brings `ServoTorqueSource` FFB and the residual-lag sync-spring with it. Deliberately absent from v1 — cars don't need it.
3. **Multi-station / persisted seat bindings.** The BE's seat/link storage is list-shaped so this is additive.
4. **Config screen** replacing the placeholder flow (frequency ghost-slots per channel, lock/failsafe widgets, per-button toggle mode). Toggle-state persistence moves server-side only if client-side latching proves annoying.

---

## 6. FFB path

### 6.1 The rig is the block

No rig discovery: the Sim Steering Wheel BE *is* the rig root. On engage (Phase 2a) it registers with the telemetry sampler; it knows its sub-level, and it resolves torque sources for whatever the craft actually has. One rig per wheel, keyed by the latched driver.

### 6.2 Torque sources, in priority order

1. **`WheelMountSource` (cars — primary; IMPLEMENTED as `GroundTelemetrySampler` + engine `GroundTorqueModel`).** The game's own tire math, read from the wheel mounts on the wheel's craft (all mounts on the sub-level via the plot actor map; explicit links land in Phase 3): self-aligning torque from the lateral term `v_side × 0.6 × μ × load` reflected through the steering geometry (kingpin τ = −F_lat × trail, column τ = kingpin × ∂yaw/∂column — the negative ratio, sign-pinned by unit test); bump/strike texture from suspension extension-rate; per-block friction (ice fudged 0.1 → mud 0.25 → default 1.0) scales everything for free. A mount backdrives the column iff it has seen a steering signal while the rig is live (sticky flag — the v1 heuristic; Phase 3 linking makes it explicit). **Honesty notes: the game's brake is a linear drag — no lockup, no combined slip — so there is no lockup cue to render, and we don't fake one. Braking also produces no net column torque at all on these mounts (the drag force is parallel to the trail arm and symmetric scrub moments cancel), so the model carries no brake term; decel-scaled cues are a Phase 2c client concern. The model's `gain` owns the unit conversion — Offroad forces are not newtons; calibrated to the reference race car (strength 180 → strengthMul ≈ 3600 ⇒ ≈1–2 Nm sustained cornering).**
2. **`CraftStateSource` (cues, always on).** Craft linear/angular velocity and accel spikes from Sable handles: damper baseline scaling, ground-contact rumble triggers, strike events. Modulation scalars for locally synthesized texture, never torques.
3. **`ServoTorqueSource` / joint impulses (planes)** — parked with kinetic output (Phase 4); design retained from the pre-pivot record (PD reconstruction on swivel-bearing servos; `getJointImpulses` free-axis behavior still to verify empirically).

### 6.3–6.4 Telemetry wire & reconstruction (IMPLEMENTED end to end, Phase 2a)

Per-substep torque samples ring-buffer in the wheel BE's sampler, flushed each game tick as `FfbTelemetryPacket` (base server-time + uniform substep dt + sample array, length-capped at 64 — the irFFB trick: a 40+ Hz torque signal reconstructed from 20 Hz packets); suspension-compression transients bypass batching as immediate `FfbEventPacket`s (engine `StrikeDetector`: threshold + hysteresis + min-interval; peak hard-capped, clamped again on receipt) treated strictly as local-synthesis triggers (`EventImpulses`). Client `TelemetryBuffer` (unit-tested): 75 ms delayed interpolation, ≤100 ms extrapolation on gaps, then fade to zero — never hold a stale torque. The client maps the server timeline onto its monotonic clock with an EMA'd offset; the buffer's playback delay absorbs tick jitter.

### 6.5 Client: local feel

Computed at high rate against the live hardware wheel state, zero added latency:

| Component | Role |
|---|---|
| **Soft lock** (`SoftLock`, unit-tested) | The end stop at ±lock: stiff spring + one-way damper past the range; deliberately stiff so the SafetyChain clamp saturates and the stop feels like a wall at the user's torque ceiling. Standard sim-racing behavior. |
| **Damper + friction** (`FeelEffects`) | Baseline so the wheel never feels dead; hides link quantization noise. Small by default — telemetry torque carries the real content once 2a lands. |
| **Sync-spring** | **Retired for cars.** It existed to render the stock wheel's 16 RPM slew lag; direct authority has no lag. Returns only with kinetic output (Phase 4) to render *actual* kinetic-consumer lag. |
| Detents, buffet, rumble synth | Phase 2c/4 per the original design. |

Mixer: `τ_out = softknee(Σ components)` (soft-knee compressor at ~65 % of the user clamp) — implemented and unit-tested; the SafetyChain hard clamp remains downstream and untouched.

What the hands feel on a car, and why it's honest (all emergent from the game's own formulas once 2a lands): wheel loads up with speed and grip; goes light exactly when the front tires saturate (understeer cue); pulls into a rear slide (countersteer cue — the lateral term reverses); goes instantly light on ice (μ→0.1 fudge floor); kicks on curbs (accel spikes); loses aligning torque under heavy braking only via load transfer, not lockup (none exists).

### 6.6 Device output — native bridge sidecar (Phase 2b, IMPLEMENTED; HIL pending hardware)

The protocol (localhost-UDP v1, `AWFB` magic — TORQUE with torque cap + watchdog ms, PANIC latched, START/STOP, STATE @250 Hz with steering deg/vel/buttons, HELLO handshake) lives in `engine/hal/bridge` (codec round-trip + fuzz + watchdog-conformance tests, `FakeBridgeServer`) and now in the **Rust sidecar (`sidecar/`)** that mirrors it byte-for-byte (golden-vector-pinned).

**Backend decision (2026-08-14): raw DirectInput, not the MOZA SDK.** The SDK is access-gated (RESEARCH.md §2); the R9 is a standard DirectInput PID device, and the sidecar drives it with the proven recipe — exclusive+background acquisition, autocenter off, one infinite constant-force effect updated in place (`DIEP_TYPESPECIFICPARAMS | DIEP_NORESTART`). This also makes the sidecar wheel-agnostic from day one; the planned SDL3 variant becomes a fallback, not a requirement. The MOZA SDK remains an optional future enhancement layer.

Safety layers in the sidecar, independent of the mod's SafetyChain (hardened by adversarial review 2026-08-14): per-frame cap clamp AND a `--max-torque` bridge ceiling (negative/non-finite wire values fail closed), per-frame watchdog with a bounded socket-drain budget (a datagram flood can't starve it), **hardware self-expiry** (the constant-force effect has a finite 250 ms duration re-triggered every 100 ms — a hung or killed sidecar zeroes in firmware), confirmed-write torque caching with `Stop`+`STOPALL` escalation on a failed zero, wrap-aware sequence gating (stale/duplicate datagrams can't defeat PANIC or re-arm the watchdog), PANIC latched until START, zero on client change/STOP/10 s client silence, explicit per-device rated torque (MOZA R-series recognized; others require `--rated-torque`), and an operator acknowledgement gate if device autocenter can't be disabled. The mod-side `BridgeWheelDevice.Config` validates its own numbers at construction.

Conformance without hardware: `cargo test` (state machine + golden vectors) plus `SidecarConformanceTest` in the engine suite — the real `BridgeWheelDevice` against the real sidecar in `--sim` mode (a critically-damped synthetic wheel) over live UDP: handshake, STATE stream, torque physically deflecting the sim wheel, watchdog recentering, panic→START recovery. The §7 hardware trip checklist ships in `sidecar/README.md` and runs when an R9 is attached.

### 6.7 Vehicle matrix

| Craft | Steering path | FFB path | Status |
|-------|---------------|----------|--------|
| Wheel-mount car (Offroad) | side redstone → mounts (±15) now; float links Phase 3 | `WheelMountSource` — solver-honest tire feel | The design's home turf |
| Legacy link car (typewriter wiring) | `STEER_L/R` channels → gearshifts (bang-bang actuators) | `CraftStateSource` cues only, labeled honestly | Supported via rebind; inherently non-analog downstream |
| Swivel-bearing plane | kinetic output — Phase 4 | `ServoTorqueSource` — Phase 4 | Deferred with kinetics |
| Exotic (thrust-vector etc.) | link channels | cue-only, HUD badge says so | — |

### 6.8 Reactivity budget

Unchanged targets: local feel ≤10 ms; contact events ≈ substep + ms (singleplayer); sustained loading ≈100–135 ms via telemetry — inside the envelope proven sim bridges occupy. The direct-authority change *removes* the biggest latency of the old design (the 16 RPM chase).

---

## 7. Safety chain (non-negotiable, implemented, unit-tested)

The last stage before the device, fixed order, no code path around it: master gain + 500 ms ramp-in on every engagement → torque clamp (default 2.5 Nm out of the box) → slew-rate limiter (25 Nm/s default) → watchdog (stale input/missed deadline → fade to zero, never freeze) → panic conditions (exceptions, device errors, JVM shutdown hook) → FAULT requires deliberate re-engage. Focus loss / GUI open = disengage.

Server-side complement: the 30-tick input timeout with recenter + failsafe brake (§5.3), and the seat mutex.

---

## 8. Degraded modes

The addon is required on both sides — the block *is* the mod. What remains matrixed:

| Situation | Behavior |
|-----------|----------|
| Client without FFB hardware (any joystick/gamepad) | Full input; feel computed but not written |
| No input device | Engage refused with a hint; K = demo sine input for testing |
| Input silence (client crash, unplug, lag) | 30-tick timeout: recenter, channels zero, failsafe brake latches |
| Driver leaves seat / block broken / craft disassembled | Neutral frame + disengage |
| Telemetry loss mid-drive (Phase 2a) | Fade out per §6.4, local feel continues |
| Upstream update breaks a §2 surface | §10.4 health check: feature off + one loud log line, never a crash |

---

## 9. Public API (Phase 5 freeze)

Unchanged plan: `TorqueSourceProvider` (server), `FeelEffectProvider` (client, safety-chained like everything else), `SimWheelClientApi` (read-only state). `FloatSteeringSource` joins with mount linking.

---

## 10. Configuration, tuning, project hygiene

### 10.1 Client config
Device bindings per axis, FFB device, master gain, max torque Nm, update rate, feel gains, per-button toggle modes. (TOML + screen — Phase 3.)

### 10.2 Per-block settings
Steering lock and failsafe brake live **on the block** (NBT, survives disassembly) — they're craft properties, not client preferences. Craft profiles (gain trims etc.) stay client-side.

### 10.3 Server config
Telemetry rate caps, max rigs, permission hook. (Phase 2a.)

### 10.4 Compatibility health check
At startup, reflectively verify every §2 surface; failure ⇒ one loud line + per-surface feature disable + HUD badge. Never crash, never silently misbehave. (Required before public release; the surface list is currently small and mixin-free.)

### 10.5 Testing without a cockpit
- `engine/` is pure-JVM unit tests (safety chain property tests, buffer, bridge codec fuzz, soft lock, driving-scenario regression).
- Gametests (headless, real server, full mod stack): steering redstone contract incl. direct authority + timeout recenter; link-channel analog transmission; failsafe-brake latch; race-car physics assembly. Phase 2a adds: race car emits plausible ground telemetry over a bump course; determinism check.
- Record & replay for feel tuning; the CSV report renderer (`tools/render_sim_report.py`).

### 10.6 Licensing
Ours MIT (suggested); Sable PolyForm Shield (depend, never vendor); Simulated assets ARR — we ship none.

---

## 11. Build order

**Done — the control surface.** The block + BE (direct authority, stock redstone convention, link channels, seat mutex, timeout + failsafe brake), client link/FFB retool (soft lock, damper/friction), gametests, docs. *Exit met 2026-08-14: full gametest suite green on a network-enabled machine.*

**Done — Phase 2a: ground telemetry (2026-08-14).** `GroundTelemetrySampler` (server rig on the wheel BE via `BlockEntitySubLevelActor`) + engine `GroundTorqueModel`/`StrikeDetector`, `FfbTelemetryPacket`/`FfbEventPacket`, client `TelemetryBuffer`/`EventImpulses` mixed into the 250 Hz loop through the soft knee, SafetyChain unchanged. *Exit met: the race-car gametest drives the craft's own link-steering from the sim wheel, shoves it into side-slip, and asserts the emitted telemetry — present at substep rate, finite, bounded, nonzero under steered slip, and dead after the input timeout. `CraftStateSource` velocity/accel cue scalars fold into Phase 2c where their consumers (damper scaling, rumble triggers) land.*

**Phase 2b — bridge sidecar (Windows). Software HALF DONE (2026-08-14): the Rust/DirectInput sidecar is implemented and conformance-tested** (`cargo test` + the cross-language `SidecarConformanceTest` against the real mod client — see §6.6). **Remaining: hardware-in-the-loop.** *Exit: the four §7 trip series in `sidecar/README.md` run green on a real R9 with measured numbers committed.*

**Phase 2c — end-to-end reactivity.** Curb strike < 150 ms to the rim, bump texture tracks block seams, dropout fade/recover — recorded traces. *Exit: traces committed.*

**Phase 3 — mount linking + polish.** The linker (float steering/brake into native formulas, first mixin + health check), config screen, art pass, per-mount invert, brake bias. *Exit: A/B trace showing the ±15 → float steering upgrade on the race car.*

**Phase 4 — kinetic output + planes.** Proportional-speed generator, `ServoTorqueSource`, residual-lag sync-spring, buffet. *Exit: §6.7 plane row demonstrated.*

**Phase 5 — public release.** API freeze, health check hardened, docs site, packaging, SDL3 sidecar variant, upstream conversations (public constraint getter; analog inputs).

---

## 12. Risks & open questions

| Item | Exposure | Mitigation / next step |
|------|----------|------------------------|
| S8 reflective fields (`touchingFriction`, `chasingYaw`) rename on an Offroad update | Telemetry fidelity (μ, exact yaw) | Degrades in place with a loud log; `fullFidelity()` asserted by the ground-telemetry gametest so CI catches it |
| Server extension semantics (raw distance vs client spring length) drift upstream | Airborne detection, bump texture | Mirrored constants documented at `AIRBORNE_PARKED`; the gametest's nonzero-torque assert trips if grounding breaks. Known accepted edge: a grounded wheel at exactly 0.65 raw distance reads as the parked-airborne sentinel for that substep |
| Substep coherence: the wheel and the mounts are peer actors in Sable's iteration, so per-substep reads (extension, μ, yaw) can be one substep stale per mount, and one torque sample can mix temporal frames | ≤1 substep (25 ms) of extra latency/blur on telemetry texture; a landing strike can register one substep late | Accepted for 2a — imperceptible under the 75 ms playback delay. If 2c wants exactness, move sampling to `SablePostPhysicsTickEvent` (fires after all actors) |
| Strike impulses are monopolar (always the same column direction) with no per-side attribution | Repeated seams read as a directional bias rather than texture | Accepted for 2a (strikes are rare transients); 2c feel pass owns bipolar/positional strike shaping |
| Create's kinetic "flicker score" trips on rapid analog link updates (Tweaked Controllers mixins it to 0) | Link-channel feel under fast pedal work | Rate already bounded by ≤20 Hz on-change sends; if flicker still trips, mirror the scoped config-gated mixin |
| Wheel-mount signal-face encoding details (which side face is "left") | Redstone steering compat path | Convention copied from stock verbatim + gametest asserts the signed read; verify against a real mount in the 2a bump-course test |
| Offroad mixin churn once mount linking lands | Phase 3 flagship | Health check + degrade to stock redstone; upstream PR for an official float-steering hook is the long-term fix |
| `getJointImpulses` free-axis semantics (motor impulses unbridged in rapier fork) | Plane FFB (Phase 4) | Verify empirically then; `ServoTorqueSource` PD reconstruction needs no new API |
| Software spring/damper oscillation on direct-drive at high gain | Feel quality | Damper always paired; device-native spring effect is the escape hatch (bridge protocol reserves it) |
| Sable physics substeps: default 2/tick (40 Hz), server-configurable 1–10 | Telemetry tuning | Wire format is rate-agnostic (per-sample dt) |
| MOZA pedals enumerate via base vs standalone | Binding UX | Per-axis device binding already covers both; verify on hardware |

---

## 13. Why this design holds up

- **One surface, no seams.** A single block owns input, output, occupancy, and (soon) telemetry. There is no compat matrix of injection paths to maintain, and ripping out the alternatives made every remaining piece simpler — rig resolution became "the BE".
- **The game does the physics; we feed its own inputs.** Steering, brake, and drive all enter through mechanisms Offroad already ships (side-face redstone, above-mount brake, kinetic RPM, link receivers). We added zero physics and zero mixins — nothing we drive can disagree with what the craft actually does.
- **Fidelity lives where the game can accept it.** Float end-to-end up to the block; the two remaining quantizations (±15 redstone steering, 0–15 link channels) are the media's own resolution, stated honestly, with mount linking sequenced as the targeted fix for the one that matters.
- **Safety is a pipeline stage, not a convention** — clamp/slew/watchdog client-side, timeout/recenter/failsafe-brake server-side, and the seat mutex closing the loop.
- **Every fragile touch point is enumerated and small.** The §2 table is six rows, all public API or plain redstone. The health check turns upstream churn into a feature flag, not a crash.
