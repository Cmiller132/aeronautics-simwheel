# Aeronautics SimWheel — Architecture & Design

How this mod drives **Create: Simulated / Aeronautics / Offroad (The Simulated
Project)** vehicles from a real sim-racing wheel with true force feedback, on
NeoForge 1.21.1. First target: the MOZA R9; by extension, any PID FFB wheel
DirectInput (Windows) or evdev (Linux) can drive.

This document describes the system **as built**, with the decisions that
shaped it folded in as context. Facts about upstream mods were read from the
Simulated-Project v1.3.x and Sable 2.0.0 sources (verified August 2026), not
inferred. Companion documents:

- [`RESEARCH.md`](RESEARCH.md) — the ecosystem/hardware findings the decisions rest on.
- [`RELEASING.md`](RELEASING.md) — how a release is built and published.
- Package `README.md` files throughout the tree — file-by-file maps
  ([`engine/`](../engine/README.md), [`mod/`](../mod/README.md),
  [`sidecar/`](../sidecar/README.md), [`tools/`](../tools/README.md)).

**The governing decision (2026-08-13): the `sim_steering_wheel` block is the
only control surface.** Sim hardware talks to our block and to nothing else —
no packet injection into Simulated's stock wheel, no typewriter/lectern
piggybacking, no standalone link-controller block. The block absorbed the
link-transmitter role so it can replace linked-typewriter wiring outright.
Accepted consequence: the mod must be installed on the server; there is no
zero-addon compat story.

---

## 1. Scope

### Goals

1. **Sim-racing-grade steering** into our own steering wheel block: 1:1
   direct float angle authority (no upstream slew chase), configurable lock,
   soft-lock end stop on the rim.
2. **Analog pedals** — throttle, brake (and clutch) — carried to existing
   craft wiring over Create's redstone-link medium, plus Offroad's native
   per-wheel brake input.
3. **True force feedback** computed from the *actual* physics simulation —
   for cars, the game's own tire math read from the wheel mounts — streamed
   to the wheelbase. Not canned rumble.
4. **Robust by default**: a 9 Nm direct-drive base must never be able to hurt
   anyone because of a bug, a lost packet, or a GC pause. Control loss is
   neutral, never latched — with an optional dead-man's failsafe brake.
5. **Publishable**: clean module boundaries, a small public API, and no
   fragile reach into Simulated internals beyond a short, documented,
   health-checked list (§2) — currently public API, two reflective field
   reads, and **one mixin**.

### Non-goals

- **Valkyrien Skies / Clockwork support.** Different ecosystem, different MC
  version. Simulated-only, permanently.
- Gamepad/HOTAS general-purpose mapping (Create: Tweaked Controllers' turf).
  We handle *wheel + pedals + FFB*.
- Reimplementing physics. Sable/Rapier and Offroad's own formulas are the
  single source of truth; we read from them and feed their existing inputs,
  we never second-guess them.
- Driving the stock steering wheel or vanilla-Minecraft vehicles.

---

## 2. The verified integration surface

Every reach into upstream code, enumerated. Each row was read from source and
each can break when Simulated updates, so each gets a startup health check
(§10.4) — upstream churn becomes a loud log line and a degraded feature,
never a crash.

| # | Surface | Where (Simulated-Project v1.3.x) | What we use it for | Access | Risk |
|---|---------|----------------------------------|--------------------|--------|------|
| S1 | Create redstone-link network: `IRedstoneLinkable`, `Frequency.of(ItemStack)`, `Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork/updateNetworkOf` | Create 6.x `content/redstone/link/*` | The block's link channels (throttle/brake/clutch/buttons + legacy steer pair). Networks combine transmitters by **max**; receivers output the transmitted 0–15 verbatim. | Public API (Tweaked Controllers ships the same pattern) | Low |
| S2 | `IDirectionalAnalogOutput` | `simulated/api/` | Our block implements it so Simulated's comparator mixin (and any other directional reader) sees the stock steering wheel's output contract. | Public interface (one method) | Low |
| S3 | Wheel mount control inputs | `offroad/content/blocks/wheel_mount/WheelMountBlockEntity` | **Steering**: analog redstone on the mount's side faces, `left − right` signed −15…+15 → ±30° lock, 0.4/tick yaw lerp. **Brake**: analog redstone into the block *above* the mount, 0–15 → `(0.075 + brake×0.3)×min(μ,1)` velocity-proportional drag + `(1−brake)` drive cut. **Drive**: the Create kinetic network (`getSpeed() × 1.75 × μ`). We *feed* these inputs; we add nothing to the physics. | Plain redstone / link receivers | Low — data-driven game mechanics |
| S4 | Stock steering wheel output convention | `simulated/content/blocks/steering_wheel/SteeringWheelBlock.getAnalogOutputSignalFrom` | Mirrored exactly by our block (clockwise face = positive 0–15, ccw = negative, facing = held 15/0, east/south sign flip) so a block swap behaves identically. | Convention copied, no code reach | Low |
| S5 | Veil networking / NeoForge payloads | `SimWheelInputPacket`, `FfbTelemetryPacket`, `FfbEventPacket` | Input frames serverbound; telemetry/events clientbound. | Standard payload API | Low |
| S6 | `Sable.HELPER.getContaining(...)` + `SubLevel.logicalPose()` | `dev.ryanhcode.sable.*` | Transforming the wheel's BlockPos to world space for the seat-range check on assembled (moving) craft. | Public API | Low |
| S7 | `BlockEntitySubLevelActor` + `plot.getBlockEntityActors()` + `ServerSubLevel.getMassTracker()`/`getVelocity` | `dev.ryanhcode.sable.api.*`, `sublevel/*` | The telemetry rig: the wheel BE implements the actor interface for per-substep callbacks; the plot's actor map enumerates the craft's wheel mounts; mass tracker + velocity feed the load/slip reads. | Public API | Low |
| S8 | `WheelMountBlockEntity` state reads: `getLerpedExtension(1)` (server = raw wheel→terrain distance), `getHeldItem()`+`TIRE` component, `getBehaviour(ScrollValueBehaviour.TYPE)` (suspension strength), side-face signals; **reflective**: `touchingFriction`, `chasingYaw` (private, no accessor) | `offroad/.../wheel_mount/` | The telemetry sampler mirrors the mount's own force formulas from these. The two reflected fields resolve once at class load; on failure the sampler degrades (μ=1, yaw from the public signal without the 0.4 lerp) with one loud log line, and the ground-telemetry gametest fails loudly (`fullFidelity()`). | Public API + 2 reflective field reads | Medium — health-checked |
| S9 | `WheelMountBlockEntityMixin` — `computeYaw()D` @Inject(HEAD, cancellable) + `@ModifyVariable` on the `brakeStrength` local in `sable$physicsTick` (the published Offroad jar ships its debug LVT, so the name resolves) | `offroad/.../wheel_mount/` | Mount linking (§5.5): linked mounts take steering yaw and brake strength as **floats** from their wheel — the same native formulas at un-quantized resolution. `defaultRequire = 0`: if upstream drifts the mixin silently doesn't apply and every mount is stock redstone. | One mixin, degrade-to-stock | Medium — health-checked (`computeYaw` presence; the brake LVT degrades silently by design) |

**The ground-vehicle mechanical chain this design drives** (verified end to
end, RESEARCH.md §3):

```
player hand → hardware wheel → [this mod] → SimWheelInputPacket
  → sim_steering_wheel BE (direct angle authority, ±lock)
  ├─ side-face analog redstone (stock convention, ±15) → wheel-mount steering faces (±30°)
  ├─ mount links (stick-linked) → computeYaw/brakeStrength as exact floats   [S9]
  ├─ BRAKE link channel → receiver above each mount → native per-wheel brake (0–15)
  ├─ THROTTLE link channel → receiver → drivetrain (clutches/gearshifts; drive is kinetic RPM)
  └─ BTN_1..8 link channels → anything
  … tire forces (v_side × 0.6 × μ × load) turn the craft, and the same
    formula read back is the FFB torque source
```

---

## 3. System overview

One mod jar plus an optional native process, strictly layered. `engine/`
(hal + ffb) is a separate Gradle module with **zero Minecraft imports** — the
module boundary enforces it.

```
┌────────────────────────────────────────────────────────────────────────┐
│  CLIENT                                                                │
│                                                                        │
│   hal/  Device layer (engine module, no Minecraft)                     │
│   ├── WheelDevice: axes, buttons, ffb capability                       │
│   ├── GlfwWheelDevice   (input only — no bridge running)               │
│   └── BridgeWheelDevice (input + torque via the native sidecar;        │
│          ▲               HardwareAngleSource + FaultingDevice)         │
│          │ poll (render thread)     ▲ STATE / TORQUE @ 250 Hz (UDP)    │
│   client/                        ffb/  FFB engine (engine module)      │
│   ├── WheelInput                  ├── FfbService  (250 Hz thread,      │
│   ├── SimWheelLink (engage/send)  │    pacing, device lifecycle)       │
│   ├── FfbController (thin         ├── FfbPipeline (TelemetryBuffer +   │
│   │    adapter, owns the two)     │    SoftLock + FeelEffects +        │
│   ├── FeelConfig (hot TOML)       │    EventImpulses → Mixer/SoftKnee  │
│   └── SimWheelHud                 │    → SafetyChain, unbypassable)    │
│                                   └── FfbTuning (hot-swappable gains)  │
├────────────────────────────────────────────────────────────────────────┤
│  NETWORK   serverbound: SimWheelInputPacket (≤20 Hz on change + HB)    │
│            clientbound: BE update tag (angle/lock);                    │
│                         FfbTelemetryPacket + FfbEventPacket            │
├────────────────────────────────────────────────────────────────────────┤
│  SERVER (required — the block lives here)                              │
│   content/                                                             │
│   ├── SimSteeringWheelBlock       (redstone convention, interactions)  │
│   ├── SimSteeringWheelBlockEntity (angle authority, link channels,     │
│   │    seat mutex, timeout/failsafe, mount-link storage, telemetry     │
│   │    rig attach point)                                               │
│   ├── GroundTelemetrySampler      (per-substep tire-force reads)       │
│   └── MountLinks + MountLinkerInteraction (stick-linker, mount index)  │
│   mixin/ WheelMountBlockEntityMixin (float steering/brake for links)   │
└────────────────────────────────────────────────────────────────────────┘
```

**Threading (client).** The render thread polls devices and publishes an
immutable snapshot per tick. `FfbService` owns a dedicated 250 Hz FFB thread
(absolute-deadline `parkNanos` pacing; rolling jitter stats surface on the
HUD) that computes feel through `FfbPipeline` and writes the device. When the
bridge is active, its STATE stream supplies true hardware angle/velocity at
loop rate — damper, friction and the soft lock react to the physical wheel,
not the 20 Hz snapshot. The FFB thread never touches Minecraft objects, and
engage/disengage edges are derived *on the FFB thread* from the snapshot, so
no SafetyChain state ever crosses threads.

**Why a separate native process for output.** FFB written from inside the JVM
inherits GC pauses and dies with the game. The sidecar (§6.6) is immune to
both: its own watchdog zeroes torque when the game goes silent, even on a
hard crash mid-force.

---

## 4. Module & package layout

```
engine/                     # pure JVM, unit-tested, zero Minecraft imports
├── hal/                    # WheelDevice + adapters: glfw/ (input), bridge/
│                           # (sidecar protocol + device), HardwareAngleSource,
│                           # FaultingDevice, NullWheelDevice (scriptable fake)
├── ffb/                    # FfbPipeline (the SHIPPING composition),
│                           # FfbService (250 Hz loop), FfbTuning,
│                           # + the components they compose (TelemetryBuffer,
│                           # SoftLock, FeelEffects, EventImpulses, Mixer,
│                           # SoftKnee, SafetyChain, GroundTorqueModel,
│                           # StrikeDetector; SyncSpring/VirtualWheelPredictor
│                           # parked for Phase 4)
└── sim/                    # headless driving harness (26 s scripted lap)
                            # that regression-tests the same FfbPipeline

mod/  dev.aeronauticssimwheel   # thin NeoForge adapter
├── content/                # the block + BE, telemetry rig, mount linking
├── network/                # 3 packets + per-sender rate gate
├── client/                 # input devices, engage/send, FfbController,
│                           # FeelConfig (hot TOML), HUD
├── mixin/                  # WheelMountBlockEntityMixin (the only mixin)
├── registry/               # block/item/BE registration
├── HealthCheck             # §10.4 startup surface verification
└── gametest/               # headless server tests, full mod stack

sidecar/                    # Rust: the native FFB bridge process
                            # (DirectInput on Windows, evdev on Linux)
```

Each of these directories carries its own `README.md` with a file-by-file
map.

**Torque is Nm end-to-end**, including the HAL: `WheelDevice.ffbUpdateTorque`
takes Nm and each backend owns its own conversion and clamps. (The original
normalized −1…1 contract silently rescaled the SafetyChain clamp per device
through a hardcoded rated-torque double conversion — found and removed in the
2026-08-14 quality pass.)

**Mixin count: one** (§2 S9). Everything else is public API or plain game
mechanics. The mixin ships with `defaultRequire = 0` so upstream drift means
"mounts behave stock", reported by the health check, never a crash.

---

## 5. The Sim Steering Wheel — the only control surface

### 5.1 Device layer

`WheelDevice` (axes/buttons/FFB capability) with per-axis independent
binding — `STEERING`, `THROTTLE`, `BRAKE`, `CLUTCH` — through `AxisProcessor`
(calibration, deadzone, curve, smoothing). Bindings are per-axis because MOZA
pedals may enumerate through the base *or* standalone; "one device owns
everything" would be wrong either way.

Input sources, in priority order (`WheelInput`):

1. **Demo** (K key) — a sine-sweep fake device; the whole chain runs with no
   hardware attached.
2. **Bridge** — when the sidecar is running, its STATE stream is the input
   *and* the torque path (one device, one truth).
3. **GLFW** — Minecraft's own LWJGL joystick API, input only, polled on the
   render thread.

Device buttons 2–9 map to `BTN_1..8` (0/1 are ENGAGE and DETENT_MODIFIER).

### 5.2 Occupancy: the seat is the mutex

- **Engage**: sit in the craft's seat, look at the wheel, press ENGAGE.
  Client requires an input device, `isPassenger()`, and the crosshair on a
  `sim_steering_wheel`.
- **Server validation, every input frame**: the sender must be riding
  something, and within 8 blocks of the wheel's *effective* position — its
  BlockPos transformed through the Sable sub-level pose, so moving craft
  validate correctly (a raw BlockPos distance would reject every assembled
  craft).
- **One driver**: the first accepted frame latches `(user, seat)`; frames
  from other players are dropped until the driver goes silent (timeout) or
  leaves the seat. Leaving the seat disengages client-side and sends the
  neutral frame.
- Multi-station craft (two wheels, two seats) work naturally — each wheel
  latches its own driver.

### 5.3 The block

**Identity.** `aeronautics_simwheel:sim_steering_wheel`, horizontal-facing,
placed like the stock wheel. Placeholder cube until the art pass. The BE is
server-authoritative for everything.

**Steering — direct authority.** The input packet carries steering as −1…1 of
the block's configured lock. The BE sets the target immediately and chases it
under a *sanity* slew clamp only (1080°/s ≈ 54°/tick, vs. the stock wheel's
96°/s) — hardware and in-game wheel stay 1:1 for any human input; the clamp
exists to bound teleport-snaps from bugs, not to be felt. Lock is per-block
configurable (±180/270/360/450/540/720/900/1080°, default ±450°).

**Steering output — the stock convention, exactly** (S4): clockwise face
emits positive steer 0–15, counterclockwise negative, the facing face emits
engaged 15/0 (the stock "held" output), east/south facings flip sign so
physical left/right is placement-independent, and a <0.99° deadband reads 0.
Emitted as vanilla weak power (`getSignal`) *and* via
`IDirectionalAnalogOutput` *and* as a vanilla comparator magnitude — wheel
mounts, wire, and both comparator kinds all read it. A stock-wheel →
sim-wheel block swap changes nothing downstream.

**Link channels — the typewriter replacement.** Everything that isn't the
steering column transmits on Create redstone-link frequencies, one
`IRedstoneLinkable` transmitter per bound channel (S1):

| Channel | Kind | Source | Notes |
|---|---|---|---|
| `THROTTLE` | analog 0–15 | throttle pedal | drives existing drivetrain wiring |
| `BRAKE` | analog 0–15 | brake pedal | → receivers above wheel mounts = native per-wheel brake |
| `CLUTCH` | analog 0–15 | clutch pedal | unbound by default |
| `STEER_LEFT` / `STEER_RIGHT` | analog 0–15 | signed steering split | **legacy link-steered craft only**; default unbound |
| `BTN_1..8` | digital 15/0 | wheel buttons | momentary |

Each channel binds to a frequency item-pair ("same item twice", the
typewriter idiom). Migrating a typewriter car is **rebind, not rewire**: bind
`THROTTLE` to the frequency the old W key used, `BRAKE` to the brake key's,
and the existing receivers/gearshifts work — proportionally now.
Quantization to 0–15 is the link medium's own resolution; accepted and
stated. Mount linking (§5.5) is the float path for the quantization that
actually matters.

**Timeout & failsafe (control loss is neutral, never latched).** Input
silence for 30 ticks (the linked-controller convention): steering target
recenters (through the slew clamp — a ramp, not a step), every channel
zeroes, the driver latch releases — **except `BRAKE`, which latches the
configured failsafe level** (0–15, default 0 = off). With failsafe set, an
unattended or disconnected driver's craft brakes to a stop instead of
coasting. The same path runs on block break and disengage.

**Client sync.** Angle/lock/engagement (and the mount-link set) sync to
clients via the BE update tag — enough for the HUD and the future renderer.

**Placeholder config UX** (until the proper screen): sneak-right-click cycles
a config cursor over each channel, then `STEERING_LOCK`, then
`FAILSAFE_BRAKE`; right-click with an item binds the targeted channel to
(held, held); right-click empty-handed cycles the targeted setting's presets
or describes the targeted channel's binding. One special case: a held
**stick** is the mount-linker tool (§5.5) and therefore can't be used as a
frequency item.

### 5.4 The input packet

`SimWheelInputPacket(BlockPos, steering, throttle, brake, clutch: float,
buttons: varint)` — sent on change (>0.5% any axis, any button edge) plus a
10-tick heartbeat to feed the timeout; NaN/Inf rejected server-side;
validation per §5.2; a per-sender rate gate (60/s) bounds ingress before any
work is enqueued. The neutral frame (all zero) is sent on every disengage
path including logout.

### 5.5 Mount linking — float steering and brake (shipped 2026-08-14)

The fidelity upgrade for cars: linked wheel mounts bypass the ±15 redstone
quantization entirely and take steering and brake as **exact floats** from
their wheel, through the same native Offroad formulas (S9).

- **Linker flow**: hold a **stick**, click the wheel (session on), click each
  wheel mount (toggle link), click the wheel again (done). Range-checked
  (48 blocks), server-side, survives across saves.
- **Storage**: links live in the wheel BE's NBT as *offsets from the wheel*
  (`LinkedMounts` long array) — translation-safe across assembly and
  schematic re-paste at the same orientation; a rotated re-paste degrades
  that mount to stock (offsets no longer resolve to mounts).
- **Runtime**: `MountLinks` keeps a mount→wheel index per level (both sides
  registered/unregistered on load/unload/remove). The mixin consults it:
  `computeYaw()` returns the wheel's steering as yaw (the deadband and
  east/south facing flips mirror the redstone convention exactly, and
  Offroad's own 0.4/tick chase lerp still applies downstream — linked
  steering *feels* native because it is); `brakeStrength` is replaced by the
  wheel's float brake, which also drives the failsafe-brake level while
  neutralized.
- **Degrade path**: unlinked mounts run 100 % stock code forever — redstone
  steering remains fully supported. If Offroad drifts, `require = 0` means
  the mixin silently doesn't apply and *linked* mounts also behave stock;
  §10.4 reports it.
- **Proof**: the `mount_linking_gives_float_steering` gametest A/Bs the same
  mount unlinked (stock, yaw 0) then linked, asserting it chases
  0.5·π/6 ≈ 0.2618 rad — strictly between the two nearest
  integer-signal-reachable yaws, so only a float path can produce it.

Still open from the original scope: per-mount invert, brake bias, the config
screen.

### 5.6 Deferred extensions (decided, sequenced, not built)

1. **Kinetic output (Phase 4).** A shaft socket + proportional-speed
   generator (`speed ∝ angle error`, capped ~128 RPM) for swivel-bearing
   planes; brings `ServoTorqueSource` FFB and the residual-lag sync-spring
   with it. Cars don't need it.
2. **Multi-station / persisted seat bindings.** The BE's seat/link storage is
   list-shaped so this is additive.
3. **Config screen** replacing the placeholder flow (frequency ghost-slots
   per channel, lock/failsafe widgets, per-button toggle mode, per-mount
   link options).

---

## 6. FFB path

### 6.1 The rig is the block

No rig discovery: the Sim Steering Wheel BE *is* the rig root. While a driver
is latched it registers with the telemetry sampler; it knows its sub-level,
and it resolves torque sources for whatever the craft actually has. One rig
per wheel, keyed by the latched driver.

### 6.2 Torque sources, in priority order

1. **`GroundTelemetrySampler` + `GroundTorqueModel` (cars — primary,
   shipped).** The game's own tire math, read from the wheel mounts on the
   wheel's craft every physics substep: self-aligning torque from the
   lateral term `v_side × 0.6 × μ × load` reflected through the steering
   geometry (kingpin τ = −F_lat × trail, column τ = kingpin × ∂yaw/∂column —
   the negative ratio, sign-pinned by unit test); bump/strike texture from
   suspension extension rate; per-block friction (ice fudged 0.1 → mud
   0.25 → default 1.0) scales everything for free. A mount backdrives the
   column iff it has seen a steering signal while the rig is live (the
   sticky-flag heuristic; explicit links refine it). **Honesty notes: the
   game's brake is a linear drag — no lockup, no combined slip — so there is
   no lockup cue to render, and we don't fake one. Braking produces no net
   column torque on these mounts (the drag force is parallel to the trail
   arm and symmetric scrub moments cancel), so the model carries no brake
   term. The model's `gain` owns the unit conversion — Offroad forces are
   not newtons; calibrated to the reference race car (strength 180 →
   strengthMul ≈ 3600 ⇒ ≈1–2 Nm sustained cornering).**
2. **`CraftStateSource` (cues)** — craft velocity/accel scalars for damper
   scaling and rumble triggers. Folded into the Phase 2c feel pass with its
   consumers.
3. **`ServoTorqueSource` / joint impulses (planes)** — parked with kinetic
   output (Phase 4); PD reconstruction on swivel-bearing servos per the
   original design record.

### 6.3 Telemetry wire (shipped end to end)

Per-substep torque samples ring-buffer in the wheel BE's sampler, flushed
each game tick as `FfbTelemetryPacket` (base server-time + uniform substep
dt + sample array, length-capped at 64) — the irFFB trick: a 40+ Hz torque
signal reconstructed from 20 Hz packets. Suspension-compression transients
bypass batching as immediate `FfbEventPacket`s (`StrikeDetector`: threshold +
hysteresis + min-interval; peak hard-capped, clamped again on receipt),
treated strictly as local-synthesis triggers (`EventImpulses`). Client-side,
both packet types pass a per-type rate gate before any work is enqueued —
a hostile or buggy server cannot flood the main-thread executor.

### 6.4 Reconstruction

`TelemetryBuffer`: samples land on a server-time axis; playback runs 75 ms
delayed with linear interpolation; on a gap it extrapolates the last slope
for ≤100 ms, then fades to zero over 200 ms — never hold a stale torque;
recovery re-ramps over 100 ms. The pipeline maps the server timeline onto
the client's monotonic clock with an EMA'd offset; the playback delay
absorbs tick jitter.

### 6.5 Client: the FFB composition

`FfbPipeline` is the one shipping composition — the mod, the unit tests, and
the offline driving harness all run this exact class:

```
telemetry (TelemetryBuffer, ×telemetryGain)
  + soft lock (SoftLock: stiff spring + one-way damper past ±lock)
  + damper + friction (FeelEffects — baseline so the wheel never feels dead)
  + strike impulses (EventImpulses — decaying transients)
  → Mixer → SoftKnee (knee at 65 % of the clamp, 3:1 — proportional near
    the limit instead of a wall)
  → SafetyChain (§7 — last, unbypassable)
```

Everything is Nm at the steering column. Ingress is hygienic by
construction: posted telemetry clamps to ±50 Nm, events to ±3 Nm with
bounded decay constants, and non-finite values are dropped — a hostile
server can make the wheel feel bad, never unsafe. Engage edges are derived
inside `step()`; a falling edge clears telemetry, impulses and the clock
offset; a FAULT survives tuning changes and clears only via deliberate
disengage → re-engage.

Retired/parked components, kept deliberately: **SyncSpring** existed to
render the stock wheel's 16 RPM slew lag — direct authority has no lag; it
returns with kinetic output (Phase 4) to render *actual* kinetic-consumer
lag, alongside **VirtualWheelPredictor**. Detents/buffet/rumble synth are
Phase 2c/4 feel-pass items.

What the hands feel on a car, and why it's honest (all emergent from the
game's own formulas): the wheel loads up with speed and grip; goes light
exactly when the front tires saturate (understeer cue); pulls into a rear
slide (countersteer cue — the lateral term reverses); goes instantly light
on ice (μ→0.1 floor); kicks on curbs (strike events); loses aligning torque
under braking only via load transfer, not lockup (none exists in the game).

### 6.6 Device output — the native bridge sidecar

The AWFB protocol (localhost UDP, port 46910, little-endian, magic `AWFB`)
lives in `engine/hal/bridge` on the Java side and `sidecar/src/protocol.rs`
as a byte-for-byte mirror (golden-vector-pinned in both languages): HELLO
handshake (device name, rated torque — validated 0.5…30 Nm on receipt),
TORQUE frames carrying the mod's own cap + watchdog interval, STATE at
250 Hz (steering deg/vel, buttons, FLAG_FAULT), START/STOP, PANIC latched
until START.

**Backend decision (2026-08-14): platform-native PID stacks, not the MOZA
SDK.** The SDK is access-gated (RESEARCH.md §2) while the R9 is a standard
PID device on both supported stacks. Windows: raw DirectInput —
exclusive+background acquisition, autocenter off, one constant-force effect
updated in place (`DIEP_TYPESPECIFICPARAMS | DIEP_NORESTART`). Linux: kernel
evdev through `hid-universal-pidff` (mainline 6.15+, backported to
6.12.24/6.13.12/6.14.3), one constant-force effect updated by `EVIOCSFF`.
No vendor SDK on either platform; the sidecar stays wheel-agnostic.

The sidecar's own safety layers (independent of the mod's SafetyChain,
hardened through three rounds of adversarial review) are specified in
[`sidecar/README.md`](../sidecar/README.md) — the short version: double
clamp (frame cap + `--max-torque` ceiling, non-finite fails closed),
bridge-side watchdog with a bounded socket-drain budget, wrap-aware sequence
gating, a **finite 250 ms effect lease only ever played while confirmed
torque is nonzero** (an OS-accepted write is not proof the USB transfer
landed — the policy time-bounds both loss directions), parameter quarantine
on unconfirmed writes, escalating stop/erase on a failed zero, and a
connection-epoch DISARM on every device loss *and* return so a stale START
can never carry over to a re-attached wheel. `FLAG_FAULT` in STATE reports
output-path integrity loss; the client treats its rising edge as a panic.

Conformance without hardware: `cargo test` (state machine + golden vectors +
sim-device physics) plus `SidecarConformanceTest` — the real
`BridgeWheelDevice` against the real sidecar binary in `--sim` mode over
live UDP (handshake, STATE stream, torque deflecting the sim wheel, watchdog
recentering, panic→START recovery). Runs on any platform with cargo.

### 6.7 Vehicle matrix

| Craft | Steering path | FFB path | Status |
|-------|---------------|----------|--------|
| Wheel-mount car (Offroad) | side redstone (±15) or **mount links (float)** | ground telemetry — solver-honest tire feel | The design's home turf |
| Legacy link car (typewriter wiring) | `STEER_L/R` channels → gearshifts (bang-bang) | cues only, labeled honestly | Supported via rebind; inherently non-analog downstream |
| Swivel-bearing plane | kinetic output — Phase 4 | `ServoTorqueSource` — Phase 4 | Deferred with kinetics |
| Exotic (thrust-vector etc.) | link channels | cue-only | — |

### 6.8 Reactivity budget

Local feel ≤10 ms (250 Hz loop on live hardware angle); contact events
≈ substep + ms (singleplayer); sustained loading ≈100–135 ms via the
telemetry playback delay — inside the envelope proven sim bridges occupy.
Direct authority removed the old design's biggest latency (the 16 RPM
chase).

---

## 7. Safety chain (non-negotiable)

The last stage before the device, fixed order, no code path around it:
master gain + ramp-in on every engagement (500 ms default) → watchdog (stale
input → fade to zero, never freeze; 150 ms default) → torque clamp (2.5 Nm
default) → slew-rate limit (25 Nm/s default) → panic conditions (exceptions,
device write failures, backend faults, JVM shutdown hook) → FAULT requires
deliberate re-engage. Focus loss / GUI open = disengage. Changing any
safety parameter at runtime re-ramps from zero — a config edit can dip
output, never spike it.

Server-side complement: the 30-tick input timeout with recenter + failsafe
brake (§5.3), and the seat mutex. Sidecar complement: §6.6's independent
layers. Three parties, each assuming the other two are broken.

---

## 8. Degraded modes

The addon is required on both sides — the block *is* the mod. What remains
matrixed:

| Situation | Behavior |
|-----------|----------|
| Client without FFB hardware (any joystick/gamepad) | Full input; feel computed but not written |
| No input device | Engage refused with a hint; K = demo sine input |
| Bridge not running | GLFW input only; no forces; auto-detects the bridge within ~2 s of it starting |
| Input silence (client crash, unplug, lag) | 30-tick timeout: recenter, channels zero, failsafe brake latches |
| Driver leaves seat / block broken / craft disassembled | Neutral frame + disengage |
| Telemetry loss mid-drive | Extrapolate ≤100 ms → fade per §6.4; local feel continues |
| Device write failure / backend fault | Panic: torque zero, FAULT until deliberate re-engage |
| Upstream update breaks a §2 surface | §10.4 health check: feature degrades + one loud log line, never a crash |

---

## 9. Public API (Phase 5 freeze)

Planned, not yet frozen: `TorqueSourceProvider` (server),
`FeelEffectProvider` (client, safety-chained like everything else),
`SimWheelClientApi` (read-only state), `FloatSteeringSource` (the
mount-linking seam, generalized).

---

## 10. Configuration, tuning, project hygiene

### 10.1 Client feel tuning (shipped)

Every `FfbTuning` gain lives in `config/aeronautics_simwheel-feel.toml`,
written with commented defaults on first run, hot-reloaded on save (mtime
poll ~1 Hz), range-clamped on load. A parse error keeps the last good tuning
(status shown on the HUD); a safety-parameter edit re-ramps from zero.

| Key | Default | What it is |
|---|---|---|
| `masterGain` | 1.0 | Final scale on everything |
| `maxTorqueNm` | 2.5 | SafetyChain hard clamp (Nm at the column) |
| `slewNmPerSec` | 25 | SafetyChain slew limit |
| `rampInSeconds` | 0.5 | Engage ramp |
| `watchdogSeconds` | 0.15 | Stale-input fade deadline |
| `telemetryGain` | 1.0 | Tire-telemetry mix |
| `damperNmPerDegPerS` | 0.0015 | Baseline damper |
| `frictionNm` | 0.12 | Baseline friction |
| `frictionEpsDegPerS` | 5.0 | Friction stiction band |
| `kneeFraction` | 0.65 | Soft-knee start, as a fraction of the clamp |
| `kneeRatio` | 3.0 | Compression above the knee |
| `lockStiffnessNmPerDeg` | 0.5 | Soft-lock spring |
| `lockDampingNmPerDegPerS` | 0.008 | Soft-lock one-way damper |

Device bindings per axis + the config screen remain future work.

### 10.2 Per-block settings

Steering lock and failsafe brake live **on the block** (NBT, survives
disassembly) — they're craft properties, not client preferences. So do the
mount links (§5.5). Craft profiles (gain trims etc.) stay client-side.

### 10.3 Server config

Telemetry rate caps, max rigs, permission hook — future work; the per-sender
packet rate gate is the shipped baseline.

### 10.4 Compatibility health check (shipped)

`HealthCheck.runAndLog()` at common setup verifies the §2 reaches that
survive classloading but can drift on upstream updates: the Sable actor
callback signature (S7), `logicalPose` (S6), the two S8 reflective fields
(via `fullFidelity()`), the mount state-read methods (S8), the TIRE
component (S3), the link-network handler (S1), and the mount-linking mixin
target (S9). One loud line per failure + a summary line always. HUD badge
comes with the config screen.

### 10.5 Testing without a cockpit

Layered so everything short of "what does 2.5 Nm feel like" is provable on
any machine — see [`TESTING.md`](../TESTING.md) for the tester guide and
`engine/sim/README.md` for the harness:

- **Engine unit tests** (`./gradlew :engine:test`): safety-chain property
  tests, buffer/impulse/lock/knee behavior, pipeline wiring + engage/fault
  edges, service loop + device lifecycle via a scriptable fake, bridge codec
  round-trip + fuzz + golden vectors, torque-model sign conventions.
- **Offline driving harness** (`DrivingScenarioDemo` + `DrivingScenarioTest`):
  a scripted 26 s lap (straight, corners, curb strike, ice, gravel,
  soft-lock shove) through the real `FfbPipeline`; regression-asserts feel
  properties (curb latency bound, ice lightness, lock wall, strike
  rendering). `tools/render_sim_report.py` renders the trace for eyeballs.
- **Cross-language conformance** (`./gradlew :engine:sidecarConformance`):
  builds the Rust sidecar and runs the real Java client against it over live
  UDP.
- **Gametests** (`./gradlew :mod:runGameTest`): headless server with the full
  Create/Simulated/Offroad/Sable stack — steering redstone contract, link
  transmission, failsafe brake, race-car physics assembly, end-to-end ground
  telemetry under slip, mount-linking float steering A/B. Note: gametests
  share one level — link-frequency tests must each use a unique frequency.
- **Client selftest** (`./gradlew :mod:runClientSelftest`): boots the real
  client, exercises input/FFB state and the feel-config write/load, quits.
- `cargo test` in `sidecar/`: protocol golden vectors, state machine,
  sim-device physics.

### 10.6 Licensing

Ours MIT; Sable PolyForm Shield (depend, never vendor); Simulated's code MIT,
assets ARR — we ship none of their assets.

---

## 11. History & roadmap

Shipped, in order (all exit criteria met on the dates given):

- **Phase 1 — the control surface** (2026-08-13): block + BE (direct
  authority, stock redstone convention, link channels, seat mutex, timeout +
  failsafe brake), client link/FFB retool, gametests.
- **Phase 2a — ground telemetry** (2026-08-14): sampler rig + torque model,
  telemetry/event packets, client reconstruction mixed into the loop.
  *Exit: the race-car gametest drives the craft's own steering from the sim
  wheel, shoves it into side-slip, and asserts the emitted telemetry.*
- **Phase 2b (software) — the bridge sidecar** (2026-08-14): Rust sidecar,
  DirectInput + evdev backends, both conformance-tested cross-language.
  **Hardware-in-the-loop still pending** — the trip checklist in
  `sidecar/README.md` needs a real R9, once per platform.
- **The quality pass (M0–M4)** (2026-08-14): FfbPipeline/FfbService
  extraction (mod and harness run the same class), hardware angle at 250 Hz
  (reviving the dead soft lock), Nm end-to-end, hot-reload feel TOML, health
  check, sidecar signal handling, dead code deleted, and **Phase 3 core:
  mount linking** (§5.5) via the first mixin, A/B-gametested.

Remaining, in rough order:

- **Phase 2c — end-to-end reactivity traces**: curb strike < 150 ms to the
  rim, bump texture tracks block seams, dropout fade/recover — recorded
  traces committed. Plus the feel pass (bipolar strike shaping, damper
  scaling by craft state).
- **Phase 2b exit — hardware-in-the-loop**: the four trip series on a real
  R9, per platform, measured numbers committed. Blocked on hardware.
- **Phase 3 polish**: config screen, per-mount invert, brake bias, art pass.
- **Phase 4 — kinetic output + planes**: proportional-speed generator,
  `ServoTorqueSource`, sync-spring returns, buffet.
- **Phase 5 — public release**: API freeze (§9), docs site, packaging,
  upstream conversations (a public float-steering hook would delete our one
  mixin).

---

## 12. Risks & open questions

| Item | Exposure | Mitigation / next step |
|------|----------|------------------------|
| S8 reflective fields (`touchingFriction`, `chasingYaw`) rename on an Offroad update | Telemetry fidelity (μ, exact yaw) | Degrades in place with a loud log; `fullFidelity()` asserted by the ground-telemetry gametest |
| S9 mixin targets drift (method rename, LVT stripped) | Mount linking | `require = 0` → linked mounts behave stock; health check reports `computeYaw`; the brake LVT degrades silently by design — upstream PR for an official float hook is the long-term fix |
| Server extension semantics (raw distance vs client spring length) drift upstream | Airborne detection, bump texture | Mirrored constants documented at `AIRBORNE_PARKED`; the gametest's nonzero-torque assert trips if grounding breaks |
| Substep coherence: wheel and mounts are peer actors, so per-substep reads can be one substep stale per mount | ≤1 substep (25 ms) of blur on telemetry texture | Accepted — imperceptible under the 75 ms playback delay; move to `SablePostPhysicsTickEvent` if 2c wants exactness |
| Strike impulses are monopolar with no per-side attribution | Repeated seams read as directional bias | Accepted for now; the 2c feel pass owns bipolar/positional strike shaping |
| Create's kinetic "flicker score" trips on rapid analog link updates | Link-channel feel under fast pedal work | Sends already bounded at ≤20 Hz on-change; if flicker still trips, mirror Tweaked Controllers' scoped config-gated mixin |
| `getJointImpulses` free-axis semantics (motor impulses unbridged in the rapier fork) | Plane FFB (Phase 4) | Verify empirically then; PD reconstruction needs no new API |
| Software spring/damper oscillation on direct-drive at high gain | Feel quality | Damper always paired; device-native spring effect is the escape hatch (protocol reserves it) |
| Sable physics substeps: default 2/tick (40 Hz), server-configurable 1–10 | Telemetry tuning | Wire format is rate-agnostic (per-sample dt) |
| MOZA pedals enumerate via base vs standalone | Binding UX | Per-axis device binding covers both; verify on hardware |
| evdev has no exclusive-writer equivalent to DirectInput's cooperative level | A second local FF writer can add torque outside our clamp | Accepted — local processes are inside the trust boundary; the UDP port singleton blocks accidental duplicate bridges |
| Linux physical FFB polarity unproven | First force can pull the wrong way | `--invert-ffb` + hands-off commissioning; the first cornering-force trip verifies sign |

---

## 13. Why this design holds up

- **One surface, no seams.** A single block owns input, output, occupancy,
  and telemetry. There is no compat matrix of injection paths to maintain,
  and ripping out the alternatives made every remaining piece simpler — rig
  resolution became "the BE".
- **The game does the physics; we feed its own inputs.** Steering, brake,
  and drive all enter through mechanisms Offroad already ships — and the one
  mixin doesn't change that: it feeds the *same* formulas at higher
  resolution. Nothing we drive can disagree with what the craft actually
  does.
- **One composition, one truth.** The mod, the unit tests, and the offline
  harness run the same `FfbPipeline`. There is no "the test passed but the
  shipping wiring differed" class of bug left to have.
- **Fidelity lives where the game can accept it.** Float end-to-end up to
  the block; mount linking carries it the last step for the quantization
  that mattered; the remaining 0–15 link channels are the medium's own
  resolution, stated honestly.
- **Safety is a pipeline stage, not a convention** — clamp/slew/watchdog
  client-side, timeout/recenter/failsafe-brake server-side, the sidecar's
  independent layers, and the seat mutex closing the loop.
- **Every fragile touch point is enumerated and small.** The §2 table is
  nine rows, health-checked at startup. Upstream churn is a feature flag,
  not a crash.
