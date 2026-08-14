# simwheel-bridge — the native FFB sidecar (Phase 2b)

The process that actually talks to the wheelbase. The Minecraft mod speaks the
AWFB UDP protocol (localhost, `engine/hal/bridge/BridgeProtocol.java` is the
Java side, `src/protocol.rs` the mirror); this sidecar drives **DirectInput
PID force-feedback wheels** — MOZA R-series recognized out of the box, any
other base with an explicit `--rated-torque` (Nm scaling is only as correct
as the rated maximum you tell it). No vendor SDK: the R9 is a standard
DirectInput FFB device (RESEARCH.md §2), and the classic recipe applies — one
constant-force effect on the steering axis, magnitude updated in place at
high rate (`DIEP_TYPESPECIFICPARAMS | DIEP_NORESTART`), autocenter off,
finite duration re-triggered ahead of expiry so the hardware self-zeroes if
this process ever stops running.

Being a separate process is the point: FFB output is immune to JVM GC pauses,
and the **bridge-side watchdog zeroes torque even if the game hard-crashes
mid-stream** — every TORQUE frame carries its own watchdog interval, and
silence past it cuts output.

## Build

Rust (stable) + the MSVC linker (VS Build Tools). Then:

```bash
cargo build --release
```

Binary: `target/release/simwheel-bridge.exe` (~230 KB, zero runtime deps).

## Usage

```
simwheel-bridge --list             enumerate FFB-capable devices, exit
simwheel-bridge --sim              simulated wheel (protocol conformance mode)
simwheel-bridge [--device N]       drive a real wheel (default: first FFB device)
  --port <p>           UDP port (default 46910)
  --max-torque <Nm>    bridge-side hard ceiling, default 5.0 (refuses > 25)
  --range <deg>        rotation range as configured in the vendor tool, default 1080
  --rated-torque <Nm>  the base's rated maximum. REQUIRED for wheelbases the
                       sidecar doesn't recognize (it knows MOZA R3/R5/R9/R12/
                       R16/R21) — a wrong rating rescales every torque cap
  --ack-autocenter     proceed although device autocenter could not be disabled
                       via DirectInput (only after verifying it's off in the
                       vendor tool)
  --verbose            once-per-second status line
```

## Safety model (read before plugging in a 9 Nm base)

Layers, all independent:

1. **Mod-side SafetyChain** (clamp 2.5 Nm default, slew limit, ramp-in,
   watchdog, panic) — computed before anything reaches the wire; the client
   config validates its own numbers (finite, non-negative cap ≤ 25 Nm,
   watchdog 10–1000 ms) so nonsense fails at construction.
2. **Frame cap**: every TORQUE frame carries the mod's own cap; the bridge
   clamps to it. A negative or non-finite wire cap fails CLOSED (frame
   dropped), never "interpreted".
3. **Bridge ceiling**: `--max-torque` clamps again, whatever the frames say.
4. **Bridge watchdog**: no TORQUE within the frame's `watchdog_ms`
   (sanity-bounded 10–1000 ms) → torque zeroes instantly. An instant zero is
   always safe; an instant spike never is. Socket draining is budgeted per
   tick so a datagram flood cannot starve the watchdog.
5. **Hardware self-expiry**: the constant-force effect runs with a FINITE
   250 ms duration, re-triggered every 100 ms. If this process hangs or is
   killed mid-force, the base zeroes itself without another instruction
   executing. A failed zero-write escalates to `Stop` +
   `SendForceFeedbackCommand(STOPALL)` and keeps retrying — the magnitude
   cache only ever advances on a *confirmed* write.
6. **PANIC latch**: a PANIC frame zeroes and latches until the next START.
   New client address, STOP, or 10 s of client silence also zero. Late or
   duplicated datagrams are dropped by wrap-aware sequence gating, so a
   stale in-flight TORQUE can't outlive a newer PANIC and a duplicate can't
   re-arm the watchdog.
7. **The base's own limits**: set MOZA Pit House to game-FFB mode, in-base
   spring/damper at zero, and a torque limit you are comfortable with. The
   bridge cannot override the base's firmware limits — good.

**Trust model**: the protocol is unauthenticated by design and the socket
binds `127.0.0.1` only — the boundary is the local machine. The sequence
gating defends against *accidental* staleness/duplication (and loopback
essentially never reorders); a hostile local process could always forge
valid frames, and no localhost protocol fixes that. Residual quirk: a client
restart that reuses the exact same UDP source port within the 10 s silence
window is indistinguishable from the old session and its early frames may be
dropped until the window resets.

## Conformance (no hardware needed)

- `cargo test` — protocol codec round-trips + hand-derived golden byte
  vectors matching the Java layout, sim-device physics, and the full bridge
  state machine (HELLO handshake, double clamp, watchdog, panic latch, STOP,
  torque-before-START).
- `../gradlew :engine:sidecarConformance` — builds the sidecar via cargo,
  then runs `SidecarConformanceTest`: the REAL mod client
  (`BridgeWheelDevice`) against the REAL sidecar in `--sim` mode over live
  UDP. Asserts the HELLO handshake, the STATE stream, that sustained torque
  physically deflects the simulated wheel, that silence cuts torque *within
  a 400 ms deadline* (then recenters), and panic→START recovery. This task
  cannot skip; in the plain `:engine:test` run the same test skips politely
  when the binary isn't built (hermetic for machines without Rust).

## Hardware-in-the-loop checklist (DESIGN.md §7 trip tests — requires the R9)

Human at the wheel, hands OFF the rim unless a step says otherwise. Wheel
firmly mounted. Pit House: game-FFB mode, in-base effects zeroed, base torque
limit at 50% for the first pass.

1. **Input-only sanity.** `simwheel-bridge --list`, then run with the device
   and `--verbose`; turn the rim by hand lock to lock: reported angle must
   track (sign: clockwise = positive) and buttons must register. No torque is
   commanded yet — the rim must feel free (autocenter off).
2. **Ramp-in trip.** Start the game client, engage on a craft: the
   SafetyChain's 500 ms ramp must be *felt* — no snap at engage. Record
   perceived behavior.
3. **Watchdog trip.** While feeling light sustained torque (steer held
   off-center on a craft), kill the game process (Task Manager). The rim must
   go limp within ~watchdog + one loop (≈150 ms). Record.
4. **Panic trip.** Trigger a device-write failure or press the panic path
   (unplug USB briefly): torque must drop and STAY zero until a deliberate
   re-engage. Record.
5. Only after 1–4: raise the Pit House limit and `--max-torque` toward
   driving levels, and tune feel (Phase 2c owns calibration).

Commit the recorded numbers to this file when done — that is the Phase 2b
exit criterion (DESIGN.md §11).

## Known-unproven (needs the hardware)

- High-rate `SetParameters` behavior on the R9 specifically (SDL issue
  #12511 catalogues driver quirks on other bases; MOZA is strictly
  PID-compliant, expected fine — verify).
- Whether MOZA pedals enumerate through the base or standalone (affects the
  mod's per-axis binding UX, not the bridge).
- `DIPROP_AUTOCENTER` support (non-fatal if rejected; Pit House owns it).
