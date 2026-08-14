#!/usr/bin/env python3
"""Build the tester distribution into dist/:

1. simwheel-testkit-<ver>.mrpack — a Modrinth modpack with the whole mod stack
   pinned (Create, Sable, the bundled Aeronautics/Simulated/Offroad jar) and
   our own jar + the pre-wired race car schematic riding along as overrides.
   A tester installs the Modrinth App, opens this one file, and presses Play.
2. simwheel-ffb-bridge-<ver>-windows.zip — the native FFB sidecar exe with a
   double-click launcher and a plain-text note.

Prerequisites (the script checks): `./gradlew :mod:build` has produced the mod
jar, `cargo build --release` has produced the sidecar exe, and
make_test_structures.py has produced the schematic.

Run from the repo root:  python tools/make_testkit.py
"""
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request
import zipfile

VERSION = '0.2.0'
MC = '1.21.1'
NEOFORGE = '21.1.248'  # the version the gametest suite runs on
USER_AGENT = f'aeronautics-simwheel-testkit/{VERSION} (github.com/Cmiller132/aeronautics-simwheel)'

# (modrinth slug, exact version_number) — the stack the mod is tested against.
PINNED = [
    ('create', '6.0.10+mc1.21.1'),          # Flywheel/Ponder bundled inside
    ('sable', '2.0.3+mc1.21.1'),            # physics engine; Veil embedded
    ('create-aeronautics', '1.3.0+mc1.21.1'),  # bundled Simulated+Aeronautics+Offroad
]

MOD_JAR = f'mod/build/libs/aeronautics-simwheel-{VERSION}.jar'
SCHEMATIC = 'schematics/simwheel_race_car.nbt'
SIDECAR_EXE = 'sidecar/target/release/simwheel-bridge.exe'

BRIDGE_BAT = """@echo off
title SimWheel FFB Bridge
echo ============================================================
echo  SimWheel FFB Bridge - keep this window open while playing.
echo  Closing it cuts force feedback (the game keeps working).
echo ============================================================
echo.
"%~dp0simwheel-bridge.exe" --verbose
echo.
echo Bridge exited. If that was unexpected, read the message above,
echo then check BRIDGE-README.txt.
pause
"""

BRIDGE_TXT = f"""SimWheel FFB Bridge {VERSION} (Windows)
=====================================

WHAT: the little program that sends force feedback to your wheelbase.
The game runs fine without it - you just get no forces on the rim.

BEFORE FIRST RUN (MOZA):
  1. Open Pit House.
  2. Set the base to game FFB mode; set the in-base spring and damper to 0.
  3. Set the base's own maximum torque to 50%% for your first drives.
  4. Set rotation to 1080 degrees (or start the bridge with --range <deg>).

RUN: double-click START-FFB-BRIDGE.bat (before or after starting the game -
the game finds the bridge automatically within a couple of seconds).

The bridge recognizes MOZA R-series bases automatically. A different
wheelbase needs:  simwheel-bridge.exe --rated-torque <its rated Nm>

SAFETY: torque is clamped to 2.5 Nm in the game and 5 Nm in the bridge by
default, ramps in over half a second, and cuts automatically if the game
stops responding, the bridge is closed, or anything crashes. Keep hands
clear of the rim the first time you engage anyway.

Problems? See TESTING.md in the repo, or run:  simwheel-bridge.exe --list
"""


def api(path: str):
    req = urllib.request.Request('https://api.modrinth.com/v2' + path,
                                 headers={'User-Agent': USER_AGENT})
    return json.load(urllib.request.urlopen(req, timeout=30))


def pinned_version(slug: str, version_number: str) -> dict:
    q = urllib.parse.quote
    versions = api(f'/project/{slug}/version'
                   f'?game_versions={q(json.dumps([MC]))}'
                   f'&loaders={q(json.dumps(["neoforge"]))}')
    for v in versions:
        if v['version_number'] == version_number:
            return v
    raise SystemExit(f'{slug} {version_number} not found on Modrinth '
                     f'(available: {[v["version_number"] for v in versions[:5]]})')


def build_mrpack(out_path: str) -> None:
    files = []
    for slug, ver in PINNED:
        v = pinned_version(slug, ver)
        f = next(f for f in v['files'] if f.get('primary', False)) \
            if any(f.get('primary') for f in v['files']) else v['files'][0]
        files.append({
            'path': f'mods/{f["filename"]}',
            'hashes': {'sha1': f['hashes']['sha1'], 'sha512': f['hashes']['sha512']},
            'env': {'client': 'required', 'server': 'required'},
            'downloads': [f['url']],
            'fileSize': f['size'],
        })
        print(f'  pinned {slug} {ver} -> {f["filename"]}')

    index = {
        'formatVersion': 1,
        'game': 'minecraft',
        'versionId': VERSION,
        'name': 'Aeronautics SimWheel Test Kit',
        'summary': 'Create: Simulated + Aeronautics/Offroad with the Sim Steering '
                   'Wheel mod and a ready-to-drive test car schematic.',
        'dependencies': {'minecraft': MC, 'neoforge': NEOFORGE},
        'files': files,
    }

    with zipfile.ZipFile(out_path, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('modrinth.index.json', json.dumps(index, indent=2))
        z.write(MOD_JAR, f'overrides/mods/{os.path.basename(MOD_JAR)}')
        z.write(SCHEMATIC, f'overrides/schematics/{os.path.basename(SCHEMATIC)}')
    print(f'  wrote {out_path} ({os.path.getsize(out_path)} bytes)')


def build_bridge_zip(out_path: str) -> None:
    with zipfile.ZipFile(out_path, 'w', zipfile.ZIP_DEFLATED) as z:
        z.write(SIDECAR_EXE, 'simwheel-bridge.exe')
        z.writestr('START-FFB-BRIDGE.bat', BRIDGE_BAT)
        z.writestr('BRIDGE-README.txt', BRIDGE_TXT)
    print(f'  wrote {out_path} ({os.path.getsize(out_path)} bytes)')


def main() -> None:
    for prereq, hint in [(MOD_JAR, './gradlew :mod:build'),
                         (SCHEMATIC, 'python tools/make_test_structures.py'),
                         (SIDECAR_EXE, 'cd sidecar && cargo build --release')]:
        if not os.path.exists(prereq):
            raise SystemExit(f'missing {prereq} — run: {hint}')
    os.makedirs('dist', exist_ok=True)
    print('building mrpack (querying Modrinth for pinned versions)...')
    build_mrpack(f'dist/simwheel-testkit-{VERSION}.mrpack')
    print('building FFB bridge zip...')
    build_bridge_zip(f'dist/simwheel-ffb-bridge-{VERSION}-windows.zip')
    print('done — release assets in dist/')


if __name__ == '__main__':
    sys.exit(main())
