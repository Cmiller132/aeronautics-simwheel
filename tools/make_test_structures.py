#!/usr/bin/env python3
"""Generate the mod's gametest structure templates.

1. race_car.nbt — testdata/tones_template_race_car.nbt with the single
   dndecor:lime_large_cogwheel swapped for create:large_cogwheel. That cog sits
   on top of the rotation_speed_controller and feeds both directional_gearshifts,
   so it must stay a real large cogwheel (DnDecor cogs are functional reskins);
   swapping lets the test env drop the DnDecor dependency.
2. control_rig.nbt — 5x5x5 rig: smooth stone floor, our sim_steering_wheel and
   a create:redstone_link receiver (red wool pair) for the analog-transmission
   gametest.

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
car.save(f'{OUT}/race_car.nbt', gzipped=True)

data_version = car['DataVersion']

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

print(f'wrote {OUT}/race_car.nbt (palette swap: {swapped}), {OUT}/control_rig.nbt')
