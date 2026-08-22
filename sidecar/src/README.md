# sidecar/src/ — source map

The native FFB bridge, ~6 files of Rust. Read the safety model in
[`../README.md`](../README.md) first — every odd-looking property in this
code (finite effect leases, never-play-at-zero, parameter quarantine,
connection epochs) is a deliberate answer to a failure mode documented
there.

| File | Role |
|---|---|
| `main.rs` | Entry point: strict argument parsing (`--list`, `--sim`, `--device N`, `--port`, `--max-torque`, `--max-slew`, `--range`, `--rated-torque`, `--ack-autocenter`, `--invert-ffb`, `--verbose`), backend selection per platform, the run loop, and SIGINT/SIGTERM (+ Windows console-ctrl) handlers that break the loop so `Drop` runs — device stopped, effect erased, on every exit path. |
| `protocol.rs` | The AWFB wire protocol **v2** — byte-for-byte mirror of the Java `BridgeProtocol.java`, pinned by golden byte vectors in both languages. The version byte is hard-matched (bridge and mod ship together); v2 adds `range_deg` to HELLO and `FLAG_ARMED` to STATE. Decoding is total: malformed input yields `None`, never a panic. |
| `bridge.rs` | The session state machine: one UDP socket (localhost, port singleton), one device, START/HELLO/TORQUE/STATE/STOP/PANIC semantics, the double clamp (frame cap + `--max-torque`), the per-frame watchdog with a bounded socket-drain budget, wrap-aware sequence gating (dropped frames don't refresh the silence timer, so a same-port client restart recovers via the 10 s timeout), client-change/silence zeroing, the connection-epoch disarm on device loss *and* return, and the output stage: drained frames coalesce into at most one device write per tick, shaped by the 200 ms arming ramp and the `--max-slew` limiter — every zeroing path bypasses both with an instant zero. |
| `device.rs` | The `FfbDevice` trait behind the bridge, the `--sim` conformance device (a critically damped synthetic wheel the Java `SidecarConformanceTest` physically deflects), and rated-torque resolution shared by both backends (the MOZA VID/PID table — R16/R21 ids deliberately never guessed — with name matching as fallback; anything else requires `--rated-torque`). |
| `dinput.rs` | Windows backend: raw DirectInput (no SDK) — exclusive+background acquisition, autocenter off, one constant-force effect updated in place (`DIEP_TYPESPECIFICPARAMS | DIEP_NORESTART`). Owns the Windows half of the output-integrity policy (finite lease, never-play-at-zero, quarantine, `Stop`+`STOPALL` escalation). Re-acquire attempts after acquisition loss are throttled to ~1 Hz and re-apply DIPROP_AUTOCENTER + DIPROP_RANGE before acquiring; VID/PID via DIPROP_VIDPID drives rated-torque lookup. |
| `evdev.rs` | Linux backend: kernel evdev through `hid-universal-pidff` — `EVIOCSFF` in-place updates, same policy mirrored (`EVIOCRMFF` erase escalation, identity re-verification on re-attach). Steering velocity comes from the drained ABS_X events' kernel timestamps when available (tick finite-difference as fallback); buttons 16–31 are the OR-union of BTN_TRIGGER_HAPPY and BTN_GAMEPAD. ioctl encodings defined locally against the stable kernel ABI and pinned by unit tests; build gated to x86_64/aarch64. |

## Testing

```bash
cargo test                              # golden vectors, state machine, sim physics
../gradlew :engine:sidecarConformance   # the real Java client against this binary
```

Neither needs hardware. What *does* need hardware is enumerated in
[`../README.md`](../README.md) ("Known-unproven" + the HIL checklist).

## Editing rules

- `protocol.rs` and `BridgeProtocol.java` change **together**, with golden
  vectors updated on both sides in the same change.
- Anything touching output must preserve the invariants: the magnitude/level
  cache advances only on an accepted write; a zero is never "assumed
  delivered"; escalation paths must stay reachable.
- New CLI flags: parse strictly (reject garbage, don't default it) — this
  process commands a motor.
