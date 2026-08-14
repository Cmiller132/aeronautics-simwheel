# Agent guide — Aeronautics SimWheel

Orientation for coding agents (and humans in a hurry). Deep dives live in
the per-directory READMEs; this file is the things you need before your
first edit.

## What this is

A NeoForge 1.21.1 mod that drives Create: Simulated/Aeronautics/Offroad
vehicles from a real sim-racing wheel with true force feedback, plus a Rust
sidecar that talks to the wheelbase. Three parts:

- `engine/` — pure-JVM input + FFB engine, **zero Minecraft imports**
  (enforced by the module boundary). All the math lives here.
- `mod/` — thin NeoForge adapter: one block, 3 packets, 1 mixin.
- `sidecar/` — Rust process speaking a localhost-UDP protocol; DirectInput
  (Windows) / evdev (Linux).

Architecture + decision record: `docs/DESIGN.md` (start §3, then §2's
integration-surface table). Every major directory has a `README.md`
file-by-file map — read the local one before editing a package.

## Build & test (macOS dev machine)

JDK 21 required. On this machine Homebrew's JDK is not on the default path:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

Always use the wrapper (`./gradlew`), never a system gradle.

| Command | What | Time |
|---|---|---|
| `./gradlew :engine:test` | engine unit suite (~86 tests) | fast |
| `./gradlew :engine:sidecarConformance` | builds the Rust sidecar + cross-language conformance over live UDP | needs cargo |
| `./gradlew :mod:build` | the mod jar | fast |
| `./gradlew :mod:runGameTest` | headless server, full Create/Simulated/Offroad/Sable stack (6 tests + the startup health check) | minutes, downloads on first run |
| `./gradlew :mod:runClientSelftest` | boots a real client, exercises input/FFB/config, quits | minutes |
| `cd sidecar && cargo test` | protocol golden vectors, state machine, sim physics | fast |

The full pre-commit battery is all of the above. A change that touches only
one layer still runs at least `:engine:test` + `:mod:build`.

## Gotchas that have actually bitten

- **Piping gradle output masks failures**: `./gradlew test | tail` exits 0
  even when tests fail (zsh pipe status). Grep the output for
  `BUILD SUCCESSFUL` / `FAILED` instead of trusting the exit code, or don't
  pipe.
- **Gametests share one level** — any test using Create link frequencies
  must use a frequency no other test uses.
- **Gametest structures are generated** by
  `tools/make_test_structures.py` → `mod/src/main/resources/data/.../structure/`.
  Never hand-edit the `.nbt` output; edit the generator and rerun (needs
  `pip install nbtlib`).
- **Version lives in three places**: `gradle.properties` (`mod_version`, the
  source of truth), `mod/src/main/resources/META-INF/neoforge.mods.toml`
  (hardcoded `version`), and `sidecar/Cargo.toml`. Bump together.
- **Torque is Nm end-to-end** through the HAL. If you add a device backend,
  it owns its own unit conversion and clamps — never renormalize upstream.
- `engine/` must never import Minecraft/NeoForge. If a change seems to need
  it, pass values in from `mod/` instead.
- `BridgeProtocol.java` and `sidecar/src/protocol.rs` change **together**,
  golden vectors on both sides in the same change.
- The mixin (`mod/.../mixin/`) is deliberately the only one, with
  `defaultRequire: 0` (degrade-to-stock). Don't add mixins casually — the
  design's compatibility story depends on the §2 surface staying small and
  health-checked (`HealthCheck.java` must learn any new reach).

## Safety context (read before touching output paths)

This project commands a direct-drive motor that can hurt someone. The
SafetyChain (`engine/.../ffb/SafetyChain.java`) is the last stage before the
device and nothing may be added downstream of it; the sidecar has its own
independent layers (`sidecar/README.md`). Changes that weaken a clamp,
bypass a watchdog, or "assume the write landed" are wrong even if every
test passes — the invariants are documented in the two files above.

## Verification state

Everything above was green on 2026-08-14 on macOS (sim mode — no wheel
hardware). Hardware-in-the-loop (a real MOZA R9) has never run; the
checklist is in `sidecar/README.md` and is the known frontier.
