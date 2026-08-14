# mod/ — the NeoForge adapter

The Minecraft-facing half: one block, its server logic, the packets, the
client wiring, and one mixin. Deliberately thin — every Newton-meter and
every device decision lives in [`engine/`](../engine/README.md); this module
translates game state into numbers for the engine and engine callbacks into
game actions.

The design's governing rule (DESIGN.md §5): **the Sim Steering Wheel block
is the only control surface.** Sim hardware talks to it and to nothing else.

## Packages

| Package | Role | Map |
|---|---|---|
| `content/` | The block + BE (server-authoritative everything), the telemetry rig, mount linking | [README](src/main/java/dev/aeronauticssimwheel/content/README.md) |
| `client/` | Input devices → packets, the FFB adapter, hot-reload feel config, HUD | [README](src/main/java/dev/aeronauticssimwheel/client/README.md) |
| `network/` | 3 packets + the per-sender rate gate | [README](src/main/java/dev/aeronauticssimwheel/network/README.md) |
| `mixin/` | `WheelMountBlockEntityMixin` — the mod's one and only mixin (float steering/brake for linked mounts) | [README](src/main/java/dev/aeronauticssimwheel/mixin/README.md) |
| `registry/` | Block/item/BE registration | [README](src/main/java/dev/aeronauticssimwheel/registry/README.md) |
| `gametest/` | Headless server tests against the full Create/Simulated/Offroad/Sable stack | [README](src/main/java/dev/aeronauticssimwheel/gametest/README.md) |

Root-level classes:

| File | Role |
|---|---|
| `AeronauticsSimwheel.java` | Mod entry point: registries, network registration, client bootstrap dispatch, health check at common setup. |
| `HealthCheck.java` | Startup verification of every DESIGN.md §2 integration surface that can silently drift when Simulated/Offroad/Sable update (7 checks). One loud line per failure; features degrade instead of crashing. |

## Resources worth knowing

- `src/main/resources/META-INF/neoforge.mods.toml` — dependency pins
  (Offroad and Sable are `required`) **and a hardcoded `version` — keep it
  in sync with `gradle.properties`**.
- `src/main/resources/aeronautics_simwheel.mixins.json` — `required: false`,
  `defaultRequire: 0`: if Offroad drifts, the mixin silently doesn't apply
  and mounts behave stock (the health check reports it).
- `src/main/resources/data/aeronautics_simwheel/structure/` — gametest
  structures, **generated** by `tools/make_test_structures.py`; don't edit
  by hand.

## Commands

```bash
./gradlew :mod:build              # the jar → mod/build/libs/
./gradlew :mod:runGameTest        # headless server, full mod stack
./gradlew :mod:runClient          # dev client
./gradlew :mod:runClientSelftest  # boots, exercises input/FFB/config, quits
```
