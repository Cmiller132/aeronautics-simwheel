# simwheel-bridge — the native FFB sidecar (Phase 2b)

The process that actually talks to the wheelbase. The Minecraft mod speaks the
AWFB UDP protocol (localhost, `engine/hal/bridge/BridgeProtocol.java` is the
Java side, `src/protocol.rs` the mirror); this sidecar drives **PID
force-feedback wheels** through the platform's native FFB stack —
**DirectInput on Windows** (`src/dinput.rs`), **kernel evdev on Linux**
(`src/evdev.rs`) — MOZA R-series recognized out of the box, Simagic bases
recognized but requiring `--rated-torque` (the Alpha EVO family shares USB
ids and one device name across its 9/12/18/28 Nm models, so the rating is
deliberately never guessed — the error message hands over the exact flag),
any other base with an explicit `--rated-torque` (Nm scaling is only as
correct as the rated maximum you tell it). No vendor SDK: the R9 is a
standard PID FFB device on both platforms (RESEARCH.md §2), Simagic EVO
bases likewise speak DirectInput constant force once SimPro Manager is
installed, and the same recipe applies on each — one constant-force effect on the steering axis, magnitude updated in
place at high rate (`DIEP_TYPESPECIFICPARAMS | DIEP_NORESTART` on Windows;
`EVIOCSFF` on Linux — on the hid-pidff driver family an update rides the
playing effect without restarting it, and the play policy no longer depends
on that either way), autocenter off, finite duration re-triggered ahead of
expiry so the hardware self-zeroes if this process ever stops running.

**Protocol v2**: the version byte is hard-matched on both sides (anything
except 2 is rejected), so bridge and mod ship together — there is no
cross-version compatibility window. v2 adds `range_deg` to HELLO (the
sidecar's `--range`, so the mod learns the wheel's configured rotation range
instead of assuming one) and `FLAG_ARMED` to STATE (bit 3 — set exactly when
the bridge would accept a TORQUE frame: started, not panic-latched, not
rearm-blocked; the mod can tell "engaged" from "streaming but ignored" at a
glance).

Being a separate process is the point: FFB output is immune to JVM GC pauses,
and the **bridge-side watchdog zeroes torque even if the game hard-crashes
mid-stream** — every TORQUE frame carries its own watchdog interval, and
silence past it cuts output. Ctrl-C / SIGTERM (and console close on Windows)
shut down cleanly: the device is stopped and the effect erased on the way
out.

File-by-file source map: [`src/README.md`](src/README.md).

## Build

**Windows**: Rust (stable) + the MSVC linker (VS Build Tools). Then:

```bash
cargo build --release
```

Binary: `target/release/simwheel-bridge.exe` (~230 KB, zero runtime deps).

**Linux**: Rust (stable) + gcc. From a Linux shell (WSL included):

```bash
CARGO_TARGET_DIR=target-linux cargo build --release
```

Binary: `target-linux/release/simwheel-bridge`. The separate target dir
keeps a Windows build in the same checkout from being clobbered.

Linux runtime requirements: the wheelbase's kernel FFB driver (MOZA needs
`hid-universal-pidff` — mainline 6.15+, backported to 6.12.24 / 6.13.12 /
6.14.3; older kernels: the [DKMS driver](https://github.com/JacKeTUs/universal-pidff))
and read-write access to the `/dev/input/event*` node (add yourself to the
`input` group, or a udev rule).

## Usage

```
simwheel-bridge --list             enumerate FFB-capable devices, exit
simwheel-bridge --sim              simulated wheel (protocol conformance mode)
simwheel-bridge [--device N]       drive a real wheel (default: first FFB device)
  --port <p>           UDP port (default 46910)
  --max-torque <Nm>    bridge-side hard ceiling, default 5.0 (refuses > 25)
  --max-slew <Nm/s>    output slew limit, default 500 (accepts 10..=10000) —
                       shapes rises only; every zeroing path stays instant
  --range <deg>        rotation range as configured in the vendor tool, default
                       1080 — reported to the mod in HELLO (protocol v2)
  --rated-torque <Nm>  the base's rated maximum. REQUIRED for wheelbases the
                       sidecar can't rate on its own: it knows MOZA R3/R5/R9/
                       R12/R16/R21; Simagic bases are recognized but share USB
                       ids across ratings (Alpha EVO Sport 9 / EVO 12 / Pro 18 /
                       Ultra 28 Nm), so pass the number for YOUR base — a wrong
                       rating rescales every torque cap
  --ack-autocenter     proceed although device autocenter could not be disabled
                       via DirectInput/evdev (only after verifying it's off in
                       the vendor tool)
  --invert-ffb         flip the torque sign at the output (inside every clamp) —
                       the escape hatch if a platform/firmware combination
                       turns out polarity-reversed; only on our instruction
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
4. **Bridge output shaping** (after the double clamp, before the device):
   drained TORQUE frames are **coalesced** — every frame still runs the full
   state machine in order (sequence gate, watchdog re-arm, latches, clamps),
   but only the final commanded torque reaches the device, at most one write
   per tick; a **200 ms linear ramp** scales output 0→1 after every
   transition into the armed state; and a **slew limiter** (`--max-slew`,
   default 500 Nm/s) bounds how fast the commanded torque may move toward
   its target. Both shape RISES only: every zeroing path (panic, stop,
   watchdog expiry, epoch disarm, client change/timeout) writes an instant
   zero that bypasses them — an instant zero is always safe, an instant
   spike never is.
5. **Bridge watchdog**: no TORQUE within the frame's `watchdog_ms`
   (sanity-bounded 10–1000 ms) → torque zeroes instantly. Socket draining is
   budgeted per tick so a datagram flood cannot starve the watchdog.
6. **Hardware self-expiry, strengthened**: the constant-force effect runs
   with a FINITE 250 ms duration and is **only ever played while the
   confirmed torque is nonzero** — a zero needs no playing effect, so at
   zero the lease simply lapses. That closes the failure mode userspace
   cannot see: an OS "success" means the driver accepted the write, not
   that the USB transfer landed, and a lost zero on a replayed effect would
   otherwise sustain phantom torque. Unconfirmed parameters QUARANTINE the
   effect (never played, and nonzero commands are REJECTED until a zero is
   confirmed — the retry loop's only permitted action is forcing that
   zero), and a failed zero-write escalates — `Stop` +
   `SendForceFeedbackCommand(STOPALL)` on Windows, a stop write +
   `EVIOCRMFF` erase on Linux (only a successful erase re-authorizes). The
   magnitude cache only ever advances on an accepted write, and the
   parameters are RE-WRITTEN with every 100 ms retrigger — so an
   accepted-but-lost nonzero update is bounded by one retrigger period
   instead of hiding behind the dedup cache forever.
7. **Disarm on every connection change**: unplug/acquisition loss and
   successful return/re-acquisition each bump the connection epoch; the bridge
   zeroes, drops the session, and requires a fresh client START, so a START
   received during the outage cannot carry over to the re-attached wheel.
   On Windows, re-acquisition re-applies the device properties
   (autocenter off, axis range) before acquiring — a driver may restore
   autocenter across the loss, which would be torque outside every software
   clamp. On Linux, re-attach also verifies the device's identity
   (serial/topology captured at open) on the very fd that will receive
   output, refuses to guess between indistinguishable duplicates, and
   re-runs the full init gate including a kernel-accepted zero-level effect
   before the fd is committed.
8. **PANIC latch**: a PANIC frame zeroes and latches until the next START.
   New client address, STOP, or 10 s of client silence also zero. Late or
   duplicated datagrams are dropped by wrap-aware sequence gating, so a
   stale in-flight TORQUE can't outlive a newer PANIC and a duplicate can't
   re-arm the watchdog.
9. **The base's own limits**: in the vendor tool (MOZA Pit House / Simagic
   SimPro Manager), game-FFB mode, in-base spring/damper at zero, and a
   torque limit you are comfortable with. The bridge cannot override the
   base's firmware limits — good. Simagic caveat: SimPro's force-feedback
   slider *rescales* all game torque proportionally, so it must sit at 100%
   for the Nm calibration to be true — use `--max-torque` (and the mod's
   `maxTorqueNm`) as the comfort limit instead, they clamp without
   rescaling.

**Which latch is the user-facing one?** The sidecar's PANIC latch and epoch
disarm are transport-level protections, not the deliberate re-engage gate:
the mod auto-sends START as a probe/keepalive, so both self-clear within
seconds by design once the transport is healthy again. The latch that
actually requires a deliberate human re-engage is the mod's SafetyChain
FAULT — that is where "torque stays off until the user acts" lives.

**Trust model**: the protocol is unauthenticated by design and the socket
binds `127.0.0.1` only — the boundary is the local machine. The sequence
gating defends against *accidental* staleness/duplication (and loopback
essentially never reorders); a hostile local process could always forge
valid frames, and no localhost protocol fixes that. On Linux specifically,
evdev has no exclusive-writer mode: any local process with device access
can play its own FF effects alongside ours (DirectInput's exclusive
cooperative level has no evdev equivalent). Local processes are inside the
trust boundary; the *accidental* two-bridges case is blocked by the UDP
port singleton (the second bind on the default port fails — running a
second bridge with a different `--port` is a deliberate operator act).
Residual quirk: a client restart that reuses the exact same UDP source port
is indistinguishable from the old session, so its early low-sequence frames
are dropped as stale — but dropped frames do not refresh the silence timer,
so the dead session times out (10 s from its last accepted frame), the new
client then latches fresh, and torque resumes on its next START. Bounded
delay, not a wedge.

## Conformance (no hardware needed)

- `cargo test` — protocol codec round-trips + hand-derived golden byte
  vectors matching the Java layout (v2: HELLO with range_deg, the armed
  STATE flag), sim-device physics, and the full bridge state machine (HELLO
  handshake, double clamp, watchdog, panic latch, STOP, torque-before-START,
  slew/ramp shaping, per-tick write coalescing, and same-port client-restart
  recovery through the silence timeout).
- `../gradlew :engine:sidecarConformance` — builds the sidecar via cargo,
  then runs `SidecarConformanceTest`: the REAL mod client
  (`BridgeWheelDevice`) against the REAL sidecar in `--sim` mode over live
  UDP. Asserts the HELLO handshake, the STATE stream, that sustained torque
  physically deflects the simulated wheel, that silence cuts torque *within
  a 400 ms deadline* (then recenters), and panic→START recovery. This task
  cannot skip; in the plain `:engine:test` run the same test skips politely
  when the binary isn't built (hermetic for machines without Rust).

## Hardware-in-the-loop checklist (DESIGN.md §7 trip tests — any real base)

Human at the wheel, hands OFF the rim unless a step says otherwise. Wheel
firmly mounted. Vendor tool (Pit House / SimPro Manager): game-FFB mode,
in-base effects zeroed, and a comfortable torque limit for the first pass
(MOZA: the Pit House limit at 50% works; Simagic: keep SimPro's FFB slider
at 100% — it rescales rather than clamps — and use `--max-torque 3` as the
first-pass ceiling instead). The checklist applies per platform —
Windows and Linux are separate backends and each needs its own pass. On
Linux specifically, step 2 doubles as the **sign-convention check**: the
evdev direction/level convention has not been felt on hardware yet, so the
first cornering force must visibly pull toward straight — if it pulls INTO
the corner, stop immediately and report (one-line fix).

1. **Input-only sanity.** `simwheel-bridge --list`, then run with the device
   and `--verbose`; turn the rim by hand lock to lock: reported angle must
   track (sign: clockwise = positive) and buttons must register. No torque is
   commanded yet — the rim must feel free (autocenter off).
2. **Ramp-in trip.** Start the game client, engage on a craft: the
   SafetyChain's 500 ms ramp (and the bridge's own 200 ms arming ramp under
   it) must be *felt* — no snap at engage. Record perceived behavior.
3. **Watchdog trip.** While feeling light sustained torque (steer held
   off-center on a craft), kill the game process (Task Manager / `kill -9`).
   The rim must go limp within ~watchdog + one loop (≈150 ms). Record.
4. **Panic trip.** Trigger a device-write failure or press the panic path
   (unplug USB briefly): torque must drop and STAY zero until the mod's
   SafetyChain FAULT is deliberately re-engaged in game. (The sidecar's own
   latch/disarm self-clears within seconds via the mod's START keepalive —
   by design; the mod-side FAULT is the gate that must hold.) Record.
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
- **Simagic Alpha EVO (first target: EVO Sport 9 Nm + P700 pedals)**:
  DirectInput constant force is confirmed working in other sims with SimPro
  Manager V3 installed, but this bridge has never driven one. Unverified on
  hardware: whether SimPro must be *running* (vs merely installed), the
  reported axis logical range (21-bit encoder — our DIPROP_RANGE rescale to
  ±32767 should normalize it; verify angle tracking lock-to-lock),
  `DIPROP_AUTOCENTER` acceptance, torque polarity (SimPro's per-game
  profiles can invert FFB — if the rim pulls INTO corners use the profile
  or `--invert-ffb`), and the P700's standalone USB identity (unpublished
  anywhere — capture VID/PID + axis order from the first `--list` /
  Windows device manager and record it here). Physical rotation range is
  set in SimPro (90–2520°, default 1080°), NOT commandable via
  DirectInput — `--range` must be told the same number.
- **Linux**: the evdev sign convention (direction `0x4000` + signed level)
  versus the DirectInput one — conformance proves magnitudes and timing,
  not which way the rim turns; HIL step 2 verifies it.
- **Linux**: high-rate `EVIOCSFF` updates through `hid-universal-pidff` on
  the R9 specifically (expected fine — the driver batches PID set-effect
  reports — verify).
- **Both platforms**: an OS-accepted torque write is not proof the USB
  transfer landed — the HID transport is asynchronous on Windows and Linux
  alike. The never-play-at-zero policy bounds a lost *zero* to one 250 ms
  lease, and the per-retrigger parameter refresh bounds a lost *nonzero*
  (which could otherwise leave a stronger stale level playing) to one
  100 ms retrigger period. HIL should include a transport-fault trip
  (unplug mid-force) to see both bounds hold physically.
