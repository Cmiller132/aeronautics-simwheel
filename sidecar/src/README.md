# sidecar/src/ — source map

The native FFB bridge, ~6 files of Rust. Read the safety model in
[`../README.md`](../README.md) first — every odd-looking property in this
code (finite effect leases, never-play-at-zero, parameter quarantine,
connection epochs) is a deliberate answer to a failure mode documented
there.

| File | Role |
|---|---|
| `main.rs` | Entry point: strict argument parsing (`--list`, `--sim`, `--device N`, `--port`, `--max-torque`, `--range`, `--rated-torque`, `--ack-autocenter`, `--invert-ffb`, `--verbose`), backend selection per platform, the run loop, and SIGINT/SIGTERM (+ Windows console-ctrl) handlers that break the loop so `Drop` runs — device stopped, effect erased, on every exit path. |
| `protocol.rs` | The AWFB wire protocol — byte-for-byte mirror of the Java `BridgeProtocol.java`, pinned by golden byte vectors in both languages. Decoding is total: malformed input yields `None`, never a panic. |
| `bridge.rs` | The session state machine: one UDP socket (localhost, port singleton), one device, START/HELLO/TORQUE/STATE/STOP/PANIC semantics, the double clamp (frame cap + `--max-torque`), the per-frame watchdog with a bounded socket-drain budget, wrap-aware sequence gating, client-change/silence zeroing, and the connection-epoch disarm on device loss *and* return. |
| `device.rs` | The `FfbDevice` trait behind the bridge, the `--sim` conformance device (a critically damped synthetic wheel the Java `SidecarConformanceTest` physically deflects), and rated-torque resolution (known MOZA R-series by name/USB id; anything else requires `--rated-torque`). |
| `dinput.rs` | Windows backend: raw DirectInput (no SDK) — exclusive+background acquisition, autocenter off, one constant-force effect updated in place (`DIEP_TYPESPECIFICPARAMS | DIEP_NORESTART`). Owns the Windows half of the output-integrity policy (finite lease, never-play-at-zero, quarantine, `Stop`+`STOPALL` escalation). |
| `evdev.rs` | Linux backend: kernel evdev through `hid-universal-pidff` — `EVIOCSFF` in-place updates, same policy mirrored (`EVIOCRMFF` erase escalation, identity re-verification on re-attach). ioctl encodings defined locally against the stable kernel ABI and pinned by unit tests; build gated to x86_64/aarch64. |

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
