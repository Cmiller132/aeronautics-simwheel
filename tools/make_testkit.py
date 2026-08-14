#!/usr/bin/env python3
"""Build the tester distribution into dist/:

1. simwheel-testkit-<ver>.mrpack — a Modrinth modpack with the whole mod stack
   pinned (Create, Sable, the bundled Aeronautics/Simulated/Offroad jar) and
   our own jar + the pre-wired race car schematic riding along as overrides.
   A tester installs the Modrinth App, opens this one file, and presses Play.
2. simwheel-ffb-bridge-<ver>-windows.zip — the native FFB sidecar exe with a
   double-click launcher and a plain-text note.
3. simwheel-ffb-bridge-<ver>-linux-x86_64.tar.gz — the same sidecar built for
   Linux (evdev backend) with a launcher script; exec bits preserved by tar.

Prerequisites (the script checks): `./gradlew :mod:build` has produced the mod
jar, `cargo build --release` has produced the sidecar exe (plus, in WSL/Linux,
`CARGO_TARGET_DIR=target-linux cargo build --release` for the Linux binary),
and make_test_structures.py has produced the schematic.

Run from the repo root:  python tools/make_testkit.py
"""
import hashlib
import io
import json
import os
import sys
import tarfile
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
SIDECAR_LINUX = 'sidecar/target-linux/release/simwheel-bridge'

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
  3. Set the base's own maximum torque to 50% for your first drives.
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

BRIDGE_SH = """#!/usr/bin/env bash
# SimWheel FFB Bridge — run from a terminal, keep it open while playing.
cd "$(dirname "$0")"
echo "============================================================"
echo " SimWheel FFB Bridge - keep this terminal open while playing."
echo " Closing it cuts force feedback (the game keeps working)."
echo "============================================================"
echo
[ -x ./simwheel-bridge ] || chmod +x ./simwheel-bridge
./simwheel-bridge --verbose
status=$?
echo
if [ $status -ne 0 ]; then
  echo "Bridge exited with an error (code $status)."
  echo "If it mentioned permissions:  sudo usermod -aG input $USER"
  echo "then log out and back in. More help: BRIDGE-README.txt"
fi
"""

BRIDGE_TXT_LINUX = f"""SimWheel FFB Bridge {VERSION} (Linux x86_64)
==========================================

WHAT: the little program that sends force feedback to your wheelbase.
The game runs fine without it - you just get no forces on the rim.

KERNEL: force feedback for MOZA bases comes from the kernel's
hid-universal-pidff driver - mainline since 6.15, backported to 6.12.24,
6.13.12 and 6.14.3. Check yours with:  uname -r
On an older kernel, install the DKMS driver:
  https://github.com/JacKeTUs/universal-pidff

PERMISSIONS: the bridge opens /dev/input/event* read-write. If it reports
a permission error:  sudo usermod -aG input $USER   then log out/in.

BEFORE FIRST RUN (MOZA Pit House):
  1. Set the base to game FFB mode; set the in-base spring and damper to 0.
  2. Set the base's own maximum torque to 50% for your first drives.
  3. Set rotation to 1080 degrees (or start the bridge with --range <deg>).

RUN, from a terminal in this folder:  ./start-ffb-bridge.sh
(before or after starting the game - the game finds the bridge
automatically within a couple of seconds).

The bridge recognizes MOZA R-series bases automatically. A different
wheelbase needs:  ./simwheel-bridge --rated-torque <its rated Nm>

SAFETY: torque is clamped to 2.5 Nm in the game and 5 Nm in the bridge by
default, ramps in over half a second, and cuts automatically if the game
stops responding, the bridge is closed, or anything crashes. Keep hands
clear of the rim the first time you engage anyway.

NOTE: the Linux backend has passed protocol conformance but has not yet
been felt on real hardware. First engage: hands OFF the rim. If the wheel
ever pulls HARDER into a corner instead of back toward center, let go,
close the bridge, and report it - that is a sign flip we fix in one line.

Problems? See TESTING.md in the repo, or run:  ./simwheel-bridge --list
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


def build_bridge_tarball(out_path: str) -> None:
    """tar.gz (not zip) so the exec bits survive the trip to a Linux tester."""
    # One timestamp for every entry, taken from the binary being shipped:
    # deterministic across rebuilds, and not the 1970 epoch that makes some
    # extractors warn.
    mtime = int(os.path.getmtime(SIDECAR_LINUX))

    def add_bytes(tar: tarfile.TarFile, name: str, data: bytes, mode: int) -> None:
        info = tarfile.TarInfo(name)
        info.size = len(data)
        info.mode = mode
        info.mtime = mtime
        tar.addfile(info, io.BytesIO(data))

    root = 'simwheel-ffb-bridge'  # extract into a folder, not a tarbomb
    with tarfile.open(out_path, 'w:gz') as tar:
        with open(SIDECAR_LINUX, 'rb') as f:
            add_bytes(tar, f'{root}/simwheel-bridge', f.read(), 0o755)
        add_bytes(tar, f'{root}/start-ffb-bridge.sh', BRIDGE_SH.encode(), 0o755)
        add_bytes(tar, f'{root}/BRIDGE-README.txt', BRIDGE_TXT_LINUX.encode(), 0o644)
    print(f'  wrote {out_path} ({os.path.getsize(out_path)} bytes)')


def main() -> None:
    for prereq, hint in [(MOD_JAR, './gradlew :mod:build'),
                         (SCHEMATIC, 'python tools/make_test_structures.py'),
                         (SIDECAR_EXE, 'cd sidecar && cargo build --release'),
                         (SIDECAR_LINUX,
                          'wsl/linux: cd sidecar && CARGO_TARGET_DIR=target-linux '
                          'cargo build --release')]:
        if not os.path.exists(prereq):
            raise SystemExit(f'missing {prereq} — run: {hint}')
    os.makedirs('dist', exist_ok=True)
    print('building mrpack (querying Modrinth for pinned versions)...')
    build_mrpack(f'dist/simwheel-testkit-{VERSION}.mrpack')
    print('building FFB bridge zip...')
    build_bridge_zip(f'dist/simwheel-ffb-bridge-{VERSION}-windows.zip')
    print('building FFB bridge Linux tarball...')
    build_bridge_tarball(f'dist/simwheel-ffb-bridge-{VERSION}-linux-x86_64.tar.gz')
    print('done — release assets in dist/')


if __name__ == '__main__':
    sys.exit(main())
