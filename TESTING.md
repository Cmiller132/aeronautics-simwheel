# Tester guide — SimWheel with a real wheelbase

You're testing a Minecraft mod that drives Create-mod vehicles with a real
sim racing wheel — including **real force feedback** computed from the game's
physics. Supported out of the box: **MOZA R-series** and **Simagic Alpha
EVO** bases (any other PID FFB base works with one extra flag), with or
without pedals — missing pedals fall back to keyboard keys. Windows and
Linux are both supported.

Everything you need is in the latest
[GitHub release](https://github.com/Cmiller132/aeronautics-simwheel/releases):
`simwheel-testkit-x.y.z.mrpack` (the game side, everything bundled — the
test car **and** the feature track schematics) and the force-feedback bridge
for your OS — `simwheel-ffb-bridge-...-windows.zip` or
`simwheel-ffb-bridge-...-linux-x86_64.tar.gz`.

> **Version match matters**: the wire protocol is v2 and hard-matched — a
> bridge from an older release is silently ignored by the current mod. Use
> the bridge from the same release as the mod.

## Setup — about 10 minutes

### 1. Game

1. Open `simwheel-testkit-x.y.z.mrpack` with **Prism Launcher**
   (Add Instance → Import → pick the file) or the
   [Modrinth App](https://modrinth.com/app) (drag it in). Everything is
   inside — all mods **and** both schematics.
2. Press Play once so it downloads and reaches the title screen (first
   launch takes a few minutes).

### 2. Wheel

**MOZA (Pit House):** game FFB mode, in-base **spring = 0, damper = 0**,
rotation **1080°**, and the base's own torque limit at **50%** for now.

**Simagic Alpha EVO (SimPro Manager V3):**

1. Install **SimPro Manager** — it ships the Windows drivers; the base
   won't work without it.
2. In SimPro: **force-feedback gain 100%** (this slider *rescales* all game
   torque — anything but 100% silently breaks the Nm calibration; the safety
   limits live in the game/bridge clamps instead, which are gentle by
   default), in-base **spring/damper/centering 0**, rotation **1080°**.
3. Make sure no per-game SimPro profile with **inverted FFB** is active —
   profile auto-switching has been seen loading the wrong game's profile.

Either way: plug the base in **before launching Minecraft**.

**Linux only**: force feedback comes from the kernel. MOZA needs
`hid-universal-pidff` (mainline **6.15+**, or backports 6.12.24 / 6.13.12 /
6.14.3; older kernels: the
[DKMS driver](https://github.com/JacKeTUs/universal-pidff)). Simagic
firmware v171+ needs the out-of-tree
[simagic-ff](https://github.com/JacKeTUs/simagic-ff) driver.

### 3. Pedals (optional — keyboard works without them)

Plug your pedal set in **before launching** (standalone USB straight to the
PC is the well-tested path; through-the-base connections also work if the
base then exposes the pedal axes — check the HUD as below). Analog
throttle and brake beat W/S by a mile on this mod: per-wheel analog brakes
and proper throttle modulation on ice.

After engaging (see Drive below), the debug HUD's device line should read
`bridge/…your base…  + pedals: …your pedal set…`. If it says
`(pedals: keyboard)` instead:

- open `config/aeronautics_simwheel-feel.toml` and set
  `bindings.pedalDevice = "…"` to part of the pedal set's name — device
  names are printed in `logs/latest.log` on lines starting with
  `SimWheel input devices:`. Save; it applies within a second.
- If the wrong pedal responds (throttle revs when you brake), set the axes
  explicitly: `throttleAxis` / `brakeAxis` (a dedicated pedal set counts
  from 0; the auto default is throttle 0, brake 1, clutch 2).
- If a pedal reads **pressed with your foot off** (car creeps or brakes by
  itself), flip its `…Invert = true`.

All of this hot-reloads — no restart.

### 4. Force feedback bridge

**Windows**: unzip `simwheel-ffb-bridge-...-windows.zip` anywhere, then:

- MOZA: double-click **START-FFB-BRIDGE.bat** (recognizes R3/R5/R9/R12
  automatically; R16/R21 share a USB id — edit the .bat to add
  `--rated-torque 16` or `21`).
- Simagic Alpha EVO Sport: double-click
  **START-FFB-BRIDGE-SIMAGIC-EVO-SPORT.bat** (preset for 9 Nm). Other EVO
  models: edit its `--rated-torque` (EVO 12 / Pro 18 / Ultra 28) — Simagic
  bases share USB ids across ratings, so the bridge deliberately refuses to
  guess.
- Rotation set to something other than 1080° in the vendor tool? Add
  `--range <deg>` to match — the bridge can't read it back, and steering
  scale depends on it.

Leave the window open while playing.

**Linux**: from a terminal —

```bash
tar xzf simwheel-ffb-bridge-*-linux-x86_64.tar.gz
cd simwheel-ffb-bridge
./start-ffb-bridge.sh
```

(Simagic on Linux: `./simwheel-bridge --rated-torque 9 --range 1080`.)
If it reports a permission error on `/dev/input`, run
`sudo usermod -aG input $USER`, log out and back in, and start it again.

Either way the game finds the bridge by itself within ~2 seconds, before or
after launching. (No bridge running = everything still works, just no
forces.)

## First engage — commissioning (do this before driving)

The first minutes with new hardware are a checklist, not a drive. **Hands
OFF the rim on the first engage** — forces are clamped gentle (2.5 Nm) and
ramp in over half a second, but be deliberate.

1. New creative world (superflat is perfect). Place the **test car**
   (Create's Schematic item → `simwheel_race_car` → place on flat ground —
   instant in creative). Right-click the **physics assembler** (block with
   the lever); the car settles onto its tires. Sit in the **seat**, look at
   the wheel block, press **J**.
2. **Angle tracking**: turn the rim slowly lock to lock. The HUD's
   commanded-vs-actual line must track 1:1 with no jumps. If the in-game
   wheel turns the wrong way, that's the input sign — sneak-right-click
   config on the wheel block can re-bind, but report it first.
3. **Test signal** (press **L**): cycles NONE → SWEEP → STEP. SWEEP is a
   slow torque sine — the rim should swing smoothly left-right-left with no
   grinding or stutter. STEP is a small square wave — crisp taps, not mush.
   Press L until it reads NONE again when done. This runs through the whole
   live chain (clamps, slew, device), so it proves the output path without
   any driving.
4. **Polarity** (the important one): drive forward at moderate speed and
   turn left. The wheel must load up **resisting the turn / pulling back
   toward straight**. If it pulls you deeper INTO the corner: let go,
   close the bridge, and report your base + OS — on Simagic also check no
   inverted per-game SimPro profile is active. (`--invert-ffb` on the
   bridge is the temporary escape hatch, on our instruction.)
5. **Side attribution**: drive one wheel — say the left side — over the
   track's diagonal curb (below) at low speed. A left-side hit should tug
   the rim one way, a right-side hit the other. If they're consistently
   swapped, report it — that's a one-constant fix on our side and exactly
   what this test exists to catch.
6. **Kill tests** (real safety validation, do all three):
   1. While feeling cornering force, **force-quit the game** (Task
      Manager / `kill -9`) — the rim must go limp within a blink (~150 ms).
   2. While engaged, **close the bridge window** — same: limp, game
      unbothered.
   3. Re-engage afterward — forces must come back only after a deliberate
      **J** re-engage, never on their own.

## Drive — the feature track

Place the second schematic, `simwheel_feature_track`, on flat ground (it's
a long strip — line it up so the **start pad is at the west end**, then
paste the car on the pad facing east, along the track). Every section
exists to make one part of the FFB audible through your hands, west to
east; yellow stripes divide sections:

| Section | Surface | What you should feel |
|---|---|---|
| Start pad | stone | At standstill: **parking scrub** — the rim is heavy to turn, and lightens the moment you roll. Turn past ±450°: a firm **end-stop wall**. |
| Baseline straight | stone | Accelerating: a subtle **drivetrain hum** that rises with wheel RPM. Steering loads up with speed; small weaves build and release **cornering weight** (this is the game's actual tire force). |
| Slalom posts | stone | Rhythmic weight transfer. Overcook a swing and the front slides — the wheel goes **light mid-corner** (understeer cue). Clipping a red post = a **thump** through the rim. |
| Mud | mud | Grip drops by ~2/3: lighter steering, easy throttle-slides, and **granular, gravelly texture** in the rim. |
| Ice | packed ice | Near-zero grip and **glassy silence** — no texture at all. Steering is eerily light; slides need big slow hands. This contrast (gravel loud / ice silent) is deliberate and honest — it tracks the game's actual surface. |
| Soul soil | soul soil | The opposite: **extra grip**, noticeably heavier cornering weight than the stone sections. |
| Curb strips | stone + slabs | The straight full-width strip: both front wheels strike **at once** — one clean symmetric thump (the left/right texture tug cancels square-on, by design). The diagonal strip: left wheels hit **before** right — distinct left-then-right tugs. |
| Collision alley | red walls | Graze a wall at LOW speed: a solid **collision thump** scaled by how hard you hit. |
| Ramp + finish pad | stone | Off the ramp the wheel goes **weightless mid-air** (no tire force), then a landing **strike** as suspension compresses. Brake to a stop on the pad; practice reverse (rim **button 3** by default). |

The car's front mounts come **pre-linked** — steering and brake reach them
as exact floats, no redstone quantization (the mod's flagship feature, on
by default). To feel the difference: hold a **stick**, right-click the
wheel block ("linking on"), right-click each front wheel mount (unlinks
them), right-click the wheel again — now it steers over quantized redstone.
Small corrections around center feel steppier. Re-link the same way. Tell
us if you can feel it — that's the point of the feature.

Getting out, or 1.5 s of input silence (crash, unplug), recenters the
steering and applies a parking brake automatically — the car stops itself.

### Controls

| Action | Input |
|---|---|
| Steer | your wheel (1:1, ±450°) — or A/D if no wheel is detected |
| Throttle / Brake | your pedals — or hold **W** / **S** |
| Reverse | a **rim button** (whichever registers as button 3 — try them) |
| Engage / release | **J** (leaving the seat also releases) |
| Test signal | **L** (NONE → SWEEP → STEP) |
| Demo input (no hardware needed) | **K** |

## Tuning the feel yourself

Everything is live-tunable — no restart, no rebuild. In the instance folder,
open `config/aeronautics_simwheel-feel.toml` (written with commented
defaults on first launch), edit, save: the change applies on the next
corner. Interesting knobs: `telemetryGain` (how much tire force you feel),
`surfaceTextureNm` / `rumbleNm` (the synthesis layers), `understeerDepth`
(how light the wheel goes past the limit), `maxTorqueNm` (the hard clamp —
raise carefully; on a 9 Nm base there is a LOT of headroom above the 2.5
default). A broken edit keeps the last good values; the HUD's config line
tells you what state the file is in.

Per-craft strength lives on the wheel block itself: sneak-right-click with
an empty hand cycles the config cursor to **FFB_TRIM** and clicks cycle
×1 → ×1.5 → ×2 → ×3 → ×0.25 → … — use it if a particular vehicle feels
weak or strong relative to others (physics stays absolute; this is the
per-vehicle trim).

If you find numbers that feel great, send us the file.

## If something's off

| Symptom | Fix |
|---|---|
| HUD says `keyboard` instead of your wheel | Base plugged in before launch? Bridge window open and showing your device name? Same-release bridge and mod (protocol v2 is hard-matched)? |
| Bridge exits immediately naming `--rated-torque` | That's the Simagic path working as designed: use the Simagic launcher .bat, or add `--rated-torque <your base's Nm>`. |
| No forces at all | Bridge running? Vendor tool in game-FFB mode? Simagic: SimPro installed + FFB gain not at 0? Linux: right kernel driver (see Setup §2)? HUD torque nonzero while cornering? |
| Pedals dead or wrong-way | See Setup §3 — `pedalDevice`, explicit axes, invert flags. All hot-reload. |
| Steering direction reversed | Sneak-right-click the wheel block to cycle to `STEER_LEFT`/`STEER_RIGHT`, re-bind each to the other's item pair (lime wool + lime glazed terracotta, both orders) — or just report it. |
| FFB pulls INTO corners | Stop. Report base + OS. Simagic: check the SimPro per-game profile isn't inverted. Escape hatch: bridge `--invert-ffb`. |
| Wheel angle scale wrong (90° of rim ≠ 90° in HUD) | The vendor tool's rotation and the bridge's `--range` disagree — set both to the same number. |
| Car won't move | Assembled (right-clicked the assembler)? Engaged (J while seated)? Throttle pressed? |
| Steering feels notchy / oscillates | Report it with the HUD torque reading and your feel TOML — gain tuning is exactly the feedback we need. |
| HUD loop rate far below 250 Hz | Report OS + CPU — the loop stats line (`loop … Hz late …`) is designed for this report. |
| Old feel TOML from a previous version | Delete it and relaunch — old defaults (notably `slewNmPerSec = 25`) mute every transient the current tuning is built around. |

## What to send back

- The **commissioning results**: angle tracking, sweep/step behavior,
  polarity, side attribution, and the three kill tests — pass/fail each.
- How each **track section** feels versus its table row above — especially
  anything that feels wrong, artificial, or backwards.
- Whether float steering (the stick unlink/relink A/B) is feelable.
- Your hardware: base + rating, pedals + how connected, rotation setting.
- On any problem: `logs/latest.log` from the instance folder, plus whatever
  the bridge window printed.
- A clip is worth a thousand words.

Thanks for testing — you're literally the first person to feel a Minecraft
vehicle through a direct-drive wheel.
