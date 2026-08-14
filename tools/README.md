# tools/ — build-time generators and packaging

Python utilities run from the repo root. None run automatically — each is
invoked by hand at the step that needs it.

| File | Role | Needs |
|---|---|---|
| `make_test_structures.py` | Generates the gametest structure templates (`race_car.nbt` — the tones testdata car with the DnDecor cog swapped and the sim wheel placed aboard — and `control_rig.nbt`) into `mod/src/main/resources/data/aeronautics_simwheel/structure/`, **and** the shareable drive-ready schematic `schematics/simwheel_race_car.nbt` (sim wheel pre-bound to the car's own link frequencies, four above-mount links flipped into per-wheel brake receivers, failsafe brake preset). Rerun after changing the block's NBT format, the bindings, or the testdata template — never edit the generated NBT by hand. | `pip install nbtlib` |
| `make_testkit.py` | Packages the release assets into `dist/`: the tester `.mrpack` (queries Modrinth for the pinned Create/Sable/Aeronautics versions, bundles our jar + schematic as overrides) and, when the binaries exist, the Windows/Linux FFB-bridge bundles with their launchers and README texts. Reads the version from `gradle.properties`. See [`docs/RELEASING.md`](../docs/RELEASING.md). | network (Modrinth API), a built mod jar |
| `render_sim_report.py` | Renders the offline driving harness's trace into a self-contained HTML report — per-phase cards (corners, curb strike + impulse series, ice, soft lock) with raw vs. telemetry vs. output torque plots. The eyeball complement to `DrivingScenarioTest`'s asserts. Flow: `./gradlew :engine:drivingDemo` writes `engine/build/driving-sim.csv`, then `python tools/render_sim_report.py` writes the HTML next to it. | stdlib only |
