# Releasing

How a release of Aeronautics SimWheel is built and published. The unit of
release is a GitHub release carrying: the mod jar, the tester `.mrpack`, the
two FFB-bridge bundles (Windows zip, Linux tarball), and the shareable
schematic.

## 1. Version bump — three places, together

| File | Field | Note |
|---|---|---|
| `gradle.properties` | `mod_version` | **the source of truth** — the jar name, `make_testkit.py`, and the mrpack all read it |
| `mod/src/main/resources/META-INF/neoforge.mods.toml` | `version` | hardcoded copy, keep in sync |
| `sidecar/Cargo.toml` | `package.version` | the bridge binary's `--verbose`/HELLO identity |

## 2. Verify — the full battery, all green

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home  # macOS/brew
./gradlew :engine:test :engine:sidecarConformance :mod:build
./gradlew :mod:runGameTest        # expect "All 6 required tests passed" +
                                  # "health check: all 7 integration surfaces verified"
./gradlew :mod:runClientSelftest
cd sidecar && cargo test && cd ..
```

Don't pipe gradle output through `tail`/`grep` and trust the exit code —
grep the text for `BUILD SUCCESSFUL` / `FAILED` (see `AGENTS.md`).

## 3. Build the artifacts

```bash
./gradlew :mod:build                                   # → mod/build/libs/aeronautics-simwheel-<ver>.jar
python tools/make_test_structures.py                   # only if the NBT format/bindings changed
cd sidecar && cargo build --release && cd ..           # Windows box → simwheel-bridge.exe
# on WSL/Linux:  cd sidecar && CARGO_TARGET_DIR=target-linux cargo build --release
python tools/make_testkit.py                           # → dist/ (mrpack + bridge bundles)
```

`make_testkit.py` needs network (it pins the Create/Sable/Aeronautics
versions against the Modrinth API) and skips any bridge bundle whose binary
isn't built — see the carry-forward rule below.

### Carrying bridge bundles forward

The bridge binaries can only be built on (or for) their OS. When a release
is cut from a machine without those toolchains, **re-attach the previous
release's bridge bundles unchanged, under their original filenames** —
honest about what the binary is — *provided the wire protocol is
unchanged*. The protocol (`BridgeProtocol.java` ↔ `sidecar/src/protocol.rs`)
is golden-vector-pinned; if a release changes it, the bridges MUST be
rebuilt and the release notes must say old bridges stopped working.
Download the previous assets from the GitHub release page, verify their
checksums against the release notes, and attach.

## 4. Release notes

Write `docs/releases/v<ver>.md` (committed, not just pasted into GitHub):
what changed for testers, what changed under the hood, asset list with
which are new vs. carried forward, and any upgrade notes (config format,
schematic regeneration, bridge compatibility).

## 5. Tag and publish

```bash
git tag v<ver>
git push origin main --tags
gh release create v<ver> \
  --title "v<ver> — <one line>" \
  --notes-file docs/releases/v<ver>.md \
  mod/build/libs/aeronautics-simwheel-<ver>.jar \
  dist/simwheel-testkit-<ver>.mrpack \
  dist/simwheel-ffb-bridge-*-windows.zip \
  dist/simwheel-ffb-bridge-*-linux-x86_64.tar.gz \
  schematics/simwheel_race_car.nbt
```

(Requires the `gh` CLI authenticated against the repo. Any machine works —
the artifacts are already built by step 3.)

If `gh` is unavailable, create the release and upload the same assets through
the GitHub REST API. Read the token at runtime from `git credential fill`; do
not echo or store it. v0.2.0 and v0.3.0 were both published this way.

## 6. After publishing

Smoke-test the tester path from a clean download: import the mrpack, place
the schematic, drive with K-demo. TESTING.md is written against "the latest
release" — confirm its claims still hold.
