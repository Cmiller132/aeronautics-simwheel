# Test craft

## `tones_template_race_car.nbt`

A Create: Offroad race car (vanilla structure-template NBT, 9×4×5). Requires:
Create, Create: Simulated, Create: Offroad, and [DnDecor](https://createmod.com/mods/dndecor)
(two decorative cogwheels only — swappable if DnDecor is unwanted in the test env).

What's in it (parsed from the palette + block-entity NBT):

- Drivetrain: 2× `simulated:portable_engine` (±64 RPM, blaze-cake fed) → belts /
  gearboxes / RSCs → 6× `offroad:wheel_mount` holding `offroad:tire` items.
  **Wheel mounts are kinetic block entities** (Create `Speed`/`Network` NBT) —
  drive torque arrives over the shaft network.
- Steering: `simulated:directional_gearshift` (`left_powered`/`right_powered`)
  driven by a `create:lectern_controller` through ~20 `create:redstone_link`s —
  **digital skid-steer, no steering wheel block, no swivel bearings.**
- `simulated:physics_assembler` ×2 for assembly.

## Why it matters for SimWheel

1. **Evidence for the §6.7 Offroad question**: at least this build style steers
   non-physically (differential redstone), so ground-vehicle FFB would come from
   `CraftStateSource` synthesis, not joint impulses. Whether `wheel_mount`
   internally uses a Sable constraint (suspension) that our rig resolver could
   also read remains to be verified in the Offroad source.
2. **Phase 0/1 test target once retrofitted**: add a `simulated:steering_wheel`
   + a swivel-bearing steering rack (or throttle lever on the engine line) to get
   an analog-steerable variant; keep this original as the control for comparing
   feel against the lectern/link path (which is what Create: Tweaked Controllers
   drives).
3. As-built it is a good **integration fixture**: assembling it in a dev world
   exercises Simulated + Offroad + physics assembly without any of our code.
