# mixin/ — one mixin, by design

The mod's entire mixin footprint is a single class. Everything else the mod
does goes through public API or plain game mechanics (DESIGN.md §2); this is
the one place we rewrite upstream behavior, and it exists because there is
no other way to feed Offroad's wheel mounts an un-quantized steering/brake
value.

## `WheelMountBlockEntityMixin.java`

Target: `dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity`
(`remap = false` — Offroad ships unobfuscated). Two injections:

1. **`computeYaw()D` — `@Inject(at = HEAD, cancellable = true)`.** If this
   mount is linked to a Sim Steering Wheel (`MountLinks.steeringYawRad`),
   return the wheel's steering as yaw directly — the narrowest un-quantized
   point: it bypasses only the ±15 int read, while Offroad's own 0.4/tick
   chase lerp (and everything else) still runs downstream. Unlinked mounts
   fall through to stock code untouched.
2. **`brakeStrength` — `@ModifyVariable(at = STORE)` inside
   `sable$physicsTick(ServerSubLevel, RigidBodyHandle, double)`.** One local
   store covers both of Offroad's brake uses (the velocity drag term and the
   `(1−brake)` drive cut). The published Offroad jar ships its debug LVT, so
   the local resolves by name.

## The degrade contract

`aeronautics_simwheel.mixins.json` sets `required: false` and
`injectors.defaultRequire: 0`: if an Offroad update renames the method,
strips the LVT, or otherwise drifts, the mixin **silently doesn't apply**
and every mount behaves stock (redstone steering keeps working — it's the
same formulas). `HealthCheck` S9 reports the drift at startup. No refmap is
needed (mojmap runtime + unobfuscated target).

Verified against Offroad 1.3.0 bytecode (`javap` on the published jar). The
long-term fix is an upstream float-steering hook, which would delete this
package.

Proof it works: the `mount_linking_gives_float_steering` gametest A/Bs a
mount unlinked vs. linked and asserts a yaw no integer redstone signal can
produce (`gametest/README.md`).
