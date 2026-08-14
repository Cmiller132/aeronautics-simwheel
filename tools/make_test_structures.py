#!/usr/bin/env python3
"""Generate the mod's gametest structure templates.

1. race_car.nbt — testdata/tones_template_race_car.nbt with the single
   dndecor:lime_large_cogwheel swapped for create:large_cogwheel. That cog sits
   on top of the rotation_speed_controller and feeds both directional_gearshifts,
   so it must stay a real large cogwheel (DnDecor cogs are functional reskins);
   swapping lets the test env drop the DnDecor dependency.
   Phase 2a: a sim_steering_wheel is added at (3,2,2) (free, chassis below) so
   the assembled craft carries the FFB rig root — the ground-telemetry gametest
   binds its STEER channels to the car's own link frequencies and drives the
   whole chain: wheel BE → link net → mount side faces → tire forces → sampler.
2. control_rig.nbt — 5x5x5 rig: smooth stone floor, our sim_steering_wheel and
   a create:redstone_link receiver (red wool pair) for the analog-transmission
   gametest.
3. schematics/simwheel_race_car.nbt — the SHAREABLE test car: same race car,
   sim wheel aboard and PRE-BOUND to the car's own link frequencies, the four
   links above the wheel mounts flipped into BRAKE receivers (receiver above a
   mount is Offroad's native per-wheel brake input), failsafe brake preset.
   Testers drop it in their Create schematics folder and drive.

Run from the repo root:  <venv>/bin/python tools/make_test_structures.py
"""
import nbtlib
from nbtlib import tag

OUT = 'mod/src/main/resources/data/aeronautics_simwheel/structure'

import os
os.makedirs(OUT, exist_ok=True)

# --- 1. race car with dndecor swap -----------------------------------------
car = nbtlib.load('testdata/tones_template_race_car.nbt')
swapped = 0
for entry in car['palette']:
    if str(entry['Name']).startswith('dndecor:'):
        entry['Name'] = tag.String('create:large_cogwheel')
        swapped += 1
assert swapped == 1, f'expected exactly 1 dndecor palette entry, got {swapped}'

# Phase 2a: mount the sim wheel on the chassis so the craft carries the rig.
wheel_pos = (3, 2, 2)
occupied = {tuple(int(p) for p in b['pos']) for b in car['blocks']}
assert wheel_pos not in occupied, f'{wheel_pos} unexpectedly occupied'
assert (wheel_pos[0], wheel_pos[1] - 1, wheel_pos[2]) in occupied, 'wheel needs chassis below'
car['palette'].append(tag.Compound({
    'Name': tag.String('aeronautics_simwheel:sim_steering_wheel'),
    'Properties': tag.Compound({'facing': tag.String('north')}),
}))
car['blocks'].append(tag.Compound({
    'pos': nbtlib.List[tag.Int]([tag.Int(wheel_pos[0]), tag.Int(wheel_pos[1]), tag.Int(wheel_pos[2])]),
    'state': tag.Int(len(car['palette']) - 1),
}))
car.save(f'{OUT}/race_car.nbt', gzipped=True)

data_version = car['DataVersion']

# --- 1b. shareable test-car schematic ---------------------------------------
# Start from the gametest car (cog swap + wheel already applied above) and make
# it drive-ready out of the box. The car's existing frequencies (probed from
# tones' template): steering receivers use the order-swapped pair
# (lime_glazed_terracotta, lime_wool) / (lime_wool, lime_glazed_terracotta);
# the drivetrain gearshift receivers use (lime_terracotta, lime_wool) /
# (lime_wool, lime_terracotta). BRAKE gets a fresh pair (lime_dye x2) into the
# four above-mount links, flipped from unused transmitters into receivers.
sch = nbtlib.load(f'{OUT}/race_car.nbt')

def item(name):
    return tag.Compound({'id': tag.String(name), 'count': tag.Int(1)})

def binding(first, second):
    return tag.Compound({'First': tag.String(first), 'Second': tag.String(second)})

GLAZED, WOOL, TERRA, DYE = ('minecraft:lime_glazed_terracotta', 'minecraft:lime_wool',
                            'minecraft:lime_terracotta', 'minecraft:lime_dye')

# The wheel: pre-bound channels + a gentle failsafe brake. If steering or
# throttle feel swapped on a given build, re-bind in game (sneak-right-click
# cycles the target) — the frequencies themselves are the car's own.
wheel_nbt = tag.Compound({
    'id': tag.String('aeronautics_simwheel:sim_steering_wheel'),
    'Bindings': tag.Compound({
        'STEER_RIGHT': binding(GLAZED, WOOL),
        'STEER_LEFT': binding(WOOL, GLAZED),
        'THROTTLE': binding(TERRA, WOOL),
        'BTN_1': binding(WOOL, TERRA),   # reverse (the car's other gearshift pair)
        'BRAKE': binding(DYE, DYE),
    }),
    'LockDeg': tag.Float(450.0),
    'FailsafeBrake': tag.Int(6),
})

brake_link_nbt = tag.Compound({
    'id': tag.String('create:redstone_link'),
    'FrequencyFirst': item(DYE),
    'FrequencyLast': item(DYE),
    'Transmit': tag.Int(0),
    'Transmitter': tag.Byte(0),
    'Receive': tag.Int(0),
    'ReceivedChanged': tag.Byte(0),
})

sch['palette'].append(tag.Compound({
    'Name': tag.String('create:redstone_link'),
    'Properties': tag.Compound({
        'powered': tag.String('false'),
        'receiver': tag.String('true'),
        'facing': tag.String('up'),
    }),
}))
brake_receiver_state = len(sch['palette']) - 1

ABOVE_MOUNTS = {(1, 2, 1), (1, 2, 3), (7, 2, 1), (7, 2, 3)}
flipped = 0
for b in sch['blocks']:
    pos = tuple(int(p) for p in b['pos'])
    name = str(sch['palette'][int(b['state'])]['Name'])
    if pos in ABOVE_MOUNTS and name == 'create:redstone_link':
        b['state'] = tag.Int(brake_receiver_state)
        b['nbt'] = brake_link_nbt
        flipped += 1
    if pos == wheel_pos:
        b['nbt'] = wheel_nbt
assert flipped == 4, f'expected 4 above-mount links, flipped {flipped}'

os.makedirs('schematics', exist_ok=True)
sch.save('schematics/simwheel_race_car.nbt', gzipped=True)

# --- 2. sim control rig ------------------------------------------------------
# Our control block + a real create:redstone_link receiver on red wool
# (mirrors the state/NBT of a receiver link from the user's example car).
control_palette = nbtlib.List[tag.Compound]([
    tag.Compound({'Name': tag.String('minecraft:smooth_stone')}),
    tag.Compound({'Name': tag.String('aeronautics_simwheel:sim_steering_wheel'),
                  'Properties': tag.Compound({'facing': tag.String('north')})}),
    # facing=up → floor-mounted (FACING points away from the supporting block);
    # facing=down would hang from the block above and pop off in this rig.
    tag.Compound({'Name': tag.String('create:redstone_link'),
                  'Properties': tag.Compound({
                      'powered': tag.String('false'),
                      'receiver': tag.String('true'),
                      'facing': tag.String('up'),
                  })}),
])
red_wool = tag.Compound({'id': tag.String('minecraft:red_wool'), 'count': tag.Int(1)})
link_nbt = tag.Compound({
    'id': tag.String('create:redstone_link'),
    'FrequencyFirst': red_wool,
    'FrequencyLast': tag.Compound({'id': tag.String('minecraft:red_wool'), 'count': tag.Int(1)}),
    'Transmit': tag.Int(0),
    'Transmitter': tag.Byte(0),
    'Receive': tag.Int(0),
    'ReceivedChanged': tag.Byte(0),
})
control_blocks = []
for x in range(5):
    for z in range(5):
        control_blocks.append(tag.Compound({
            'pos': nbtlib.List[tag.Int]([tag.Int(x), tag.Int(0), tag.Int(z)]),
            'state': tag.Int(0),
        }))
control_blocks.append(tag.Compound({
    'pos': nbtlib.List[tag.Int]([tag.Int(1), tag.Int(1), tag.Int(1)]),
    'state': tag.Int(1),
}))
control_blocks.append(tag.Compound({
    'pos': nbtlib.List[tag.Int]([tag.Int(3), tag.Int(1), tag.Int(3)]),
    'state': tag.Int(2),
    'nbt': link_nbt,
}))
control_rig = nbtlib.File({
    'size': nbtlib.List[tag.Int]([tag.Int(5), tag.Int(5), tag.Int(5)]),
    'entities': nbtlib.List[tag.Compound]([]),
    'blocks': nbtlib.List[tag.Compound](control_blocks),
    'palette': control_palette,
    'DataVersion': data_version,
})
control_rig.save(f'{OUT}/control_rig.nbt', gzipped=True)

print(f'wrote {OUT}/race_car.nbt (palette swap: {swapped}), {OUT}/control_rig.nbt, '
      f'schematics/simwheel_race_car.nbt (brake receivers: {flipped})')
