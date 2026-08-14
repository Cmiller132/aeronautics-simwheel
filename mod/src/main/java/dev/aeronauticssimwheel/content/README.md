# content/ — the block and everything server-side around it

The Sim Steering Wheel block, its server-authoritative block entity, the
FFB telemetry rig, and mount linking. This is where game state lives; the
math it feeds is in `engine/`.

## Files

| File | Role |
|---|---|
| `SimSteeringWheelBlock.java` | The block: stock-steering-wheel redstone convention on the side faces (weak power + `IDirectionalAnalogOutput` + comparator), the placeholder config UX (sneak-click cycles target, item click binds frequencies, empty click cycles presets), and the stick special case (linker tool, so a stick can't be a frequency item). |
| `SimSteeringWheelBlockEntity.java` | Server truth: direct float angle authority under the 1080°/s sanity slew, the driver latch (seat mutex + 8-block pose-transformed range), the 30-tick timeout → recenter + failsafe brake, link-channel transmitters, per-block settings NBT (lock, failsafe, **mount-link offsets**), and the telemetry rig attach point. Also exposes the linked-mount float reads (`linkedSteerYawRad` with the deadband + facing flips mirroring the redstone convention, `linkedBrake01` incl. failsafe while neutralized). |
| `SimChannel.java` | The link-channel vocabulary (`THROTTLE`, `BRAKE`, `CLUTCH`, `STEER_LEFT/RIGHT`, `BTN_1..8`) and per-channel binding storage. |
| `GroundTelemetrySampler.java` | The FFB rig (DESIGN.md §6.2): every Sable physics substep, reads the craft's wheel mounts (public API + two health-checked reflective fields, `touchingFriction`/`chasingYaw`) and feeds `GroundTorqueModel`; ring-buffers samples for the per-tick telemetry flush; `StrikeDetector` transients bypass as immediate events. Degrades loudly (μ=1, unlerped yaw) if reflection breaks — `fullFidelity()` is gametested. |
| `MountLinks.java` | The runtime mount→wheel index the mixin consults (per-level, thread-tolerant, `OptionalDouble` = "stock"). Also owns the stick-linker session state. Fast empty-path: zero cost while nothing is linked. |
| `MountLinkerInteraction.java` | The linker flow: stick-click wheel (session on) → stick-click mounts (toggle, 48-block range) → stick-click wheel (done). The mount side hooks `RightClickBlock` because the mount isn't our block. |

## Invariants

- **The BE is server-authoritative for everything**; clients get the update
  tag (angle/lock/engagement/links) and render.
- Mount links are stored as **offsets from the wheel** in the wheel's NBT —
  translation-safe across assembly; a rotated re-paste degrades those mounts
  to stock.
- Control loss is neutral, never latched: every disengage path (timeout,
  seat exit, block break) recenters and zeroes — except the failsafe brake,
  which latches by design.
- The sampler adds **no physics**: it reads the same numbers Offroad's own
  physics tick computes from, and reflects them into a torque *report*.
