# Tester guide — SimWheel + MOZA R9 (wheelbase only, no pedals)

You're testing a Minecraft mod that drives Create-mod vehicles with a real
sim racing wheel — including **real force feedback** computed from the game's
physics. Your setup (R9 base, no pedals) is fully supported: pedals are
replaced by keyboard keys. Windows and Linux are both supported.

Everything you need is in the latest
[GitHub release](https://github.com/Cmiller132/aeronautics-simwheel/releases):
`simwheel-testkit-x.y.z.mrpack` (the game side, everything bundled) and the
force-feedback bridge for your OS —
`simwheel-ffb-bridge-x.y.z-windows.zip` or
`simwheel-ffb-bridge-x.y.z-linux-x86_64.tar.gz`.

## Setup — about 10 minutes

### 1. Game

1. Open `simwheel-testkit-x.y.z.mrpack` with **Prism Launcher**
   (Add Instance → Import → pick the file) or the
   [Modrinth App](https://modrinth.com/app) (drag it in). Everything is
   inside — all mods **and** the test car schematic.
2. Press Play once so it downloads and reaches the title screen (first
   launch takes a few minutes).

### 2. Wheel

1. In **MOZA Pit House**: game FFB mode, in-base **spring = 0, damper = 0**,
   rotation **1080°**, and the base's own torque limit at **50%** for now.
2. Plug the base in **before launching Minecraft**.
3. **Linux only**: force feedback comes from the kernel's
   `hid-universal-pidff` driver — check `uname -r` and make sure you're on
   **6.15+** (or the backports: 6.12.24 / 6.13.12 / 6.14.3). On an older
   kernel, install the
   [DKMS driver](https://github.com/JacKeTUs/universal-pidff) first.

### 3. Force feedback bridge

**Windows**: unzip `simwheel-ffb-bridge-...-windows.zip` anywhere,
double-click **START-FFB-BRIDGE.bat**, leave the window open.

**Linux**: from a terminal —

```bash
tar xzf simwheel-ffb-bridge-*-linux-x86_64.tar.gz
cd simwheel-ffb-bridge
./start-ffb-bridge.sh
```

Leave the terminal open. If it reports a permission error on
`/dev/input`, run `sudo usermod -aG input $USER`, log out and back in,
and start it again.

Either way the bridge finds the R9 by itself, and the game finds the
bridge by itself within ~2 seconds, before or after launching. (No bridge
running = everything still works, just no forces.)

## Drive

1. New creative world (superflat is perfect).
2. Grab Create's **Schematic** item (search "schematic" in the creative
   menu, it's the empty paper one) → right-click → select
   `simwheel_race_car` → position the ghost on flat ground → place it
   (instant in creative).
3. Right-click the **physics assembler** on the car (block with the lever) —
   the car becomes a physics object and settles onto its tires.
4. Sit in the **seat**, look at the wheel block, press **J**.
5. Controls, wheelbase-only edition:

   | Action | Input |
   |---|---|
   | Steer | your wheel (1:1, ±450°) — or A/D if the wheel isn't detected |
   | Throttle | hold **W** |
   | Brake | hold **S** (analog per-wheel brakes at full) |
   | Reverse | one of your **rim buttons** (whichever registers as button 3 — try them) |
   | Engage / release | **J** (leaving the seat also releases) |
   | Demo input (no hardware needed) | **K** |

6. Getting out, or 1.5 s of input silence (crash, unplug), recenters the
   steering and applies a parking brake automatically — the car stops itself.

The debug HUD (top-left while engaged) shows the detected device — it should
say `bridge/...your wheelbase...` — plus commanded vs. actual steering angle
and the live FFB torque.

## What you should feel (this is the part we need tested!)

- **Immediately, even parked**: turn past ±450° — a firm end stop ("soft
  lock"). A light damper/friction feel everywhere else. **First time you
  engage, keep your hands OFF the rim** — there's a half-second ramp-in and
  the forces are clamped gentle (2.5 Nm), but be deliberate about the first
  one.
- **Driving**: the wheel loads up in corners (the game's actual tire forces,
  not canned effects), goes light when the front tires let go, kicks on curb
  strikes and landings, and pulls toward straight when you exit a corner.
- **If it ever pulls harder INTO the corner** instead of back toward
  straight: let go, close the bridge, and report it — that's a reversed
  sign convention on your platform, a one-line fix for us, and exactly the
  kind of thing this test exists to catch.
- **Kill tests, once driving feels fine** (this is real safety validation):
  1. While feeling cornering force, **force-quit the game** (Task Manager
     on Windows; `kill -9` its PID on Linux) — the rim must go limp within
     a blink (~150 ms).
  2. While engaged, **close the bridge window/terminal** — same: limp,
     game unbothered.
  3. Re-engage afterward — forces must come back only after a deliberate
     **J** re-engage, never on their own.

## If something's off

| Symptom | Fix |
|---|---|
| HUD says `keyboard` instead of your wheel | Base plugged in before launch? Bridge window open and showing your device name? |
| No forces at all | Bridge running? Pit House in game FFB mode? On Linux: kernel 6.15+/backport (see Setup §2)? HUD torque nonzero while cornering? |
| Bridge prints a permission error (Linux) | `sudo usermod -aG input $USER`, log out and back in |
| Steering direction reversed | Sneak-right-click the wheel block to cycle to `STEER_LEFT`/`STEER_RIGHT`, re-bind each to the other's item pair (lime wool + lime glazed terracotta, both orders) — or just report it, it's a one-line fix for us |
| Car won't move | Assembled (right-clicked the assembler)? Engaged (J while seated)? Holding W? |
| Wheel feels notchy/oscillates | Report it with the HUD torque reading — gain tuning is exactly the feedback we need |

## What to send back

- How the **steering weight** feels driving (corners, straights, over the
  car's own curb strikes) and anything that feels wrong or artificial.
- The kill-test results (the three above) — pass/fail each.
- On any problem: `logs/latest.log` from the instance folder, plus whatever
  the bridge window printed.
- A clip is worth a thousand words.

Thanks for testing — you're literally the first person to feel a Minecraft
vehicle through a direct-drive wheel.
