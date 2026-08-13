#!/usr/bin/env python3
"""Generate the mod's gametest structure templates.

1. race_car.nbt — testdata/tones_template_race_car.nbt with the single
   dndecor:lime_large_cogwheel swapped for create:large_cogwheel. That cog sits
   on top of the rotation_speed_controller and feeds both directional_gearshifts,
   so it must stay a real large cogwheel (DnDecor cogs are functional reskins);
   swapping lets the test env drop the DnDecor dependency.
2. steering_rig.nbt — 5x5x5 rig: smooth stone floor, spruce planks pillar,
   simulated:steering_wheel mounted on top at (2,2,2).

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

# --- 2. steering wheel rig ---------------------------------------------------
data_version = car['DataVersion']

palette = nbtlib.List[tag.Compound]([
    tag.Compound({'Name': tag.String('minecraft:smooth_stone')}),
    tag.Compound({'Name': tag.String('minecraft:spruce_planks')}),
    tag.Compound({'Name': tag.String('simulated:steering_wheel'),
                  'Properties': tag.Compound({
                      'facing': tag.String('north'),
                      'on_floor': tag.String('true'),
                      'waterlogged': tag.String('false'),
                  })}),
])
blocks = []
for x in range(5):
    for z in range(5):
        blocks.append(tag.Compound({
            'pos': nbtlib.List[tag.Int]([tag.Int(x), tag.Int(0), tag.Int(z)]),
            'state': tag.Int(0),
        }))
blocks.append(tag.Compound({
    'pos': nbtlib.List[tag.Int]([tag.Int(2), tag.Int(1), tag.Int(2)]),
    'state': tag.Int(1),
}))
blocks.append(tag.Compound({
    'pos': nbtlib.List[tag.Int]([tag.Int(2), tag.Int(2), tag.Int(2)]),
    'state': tag.Int(2),
}))

rig = nbtlib.File({
    'size': nbtlib.List[tag.Int]([tag.Int(5), tag.Int(5), tag.Int(5)]),
    'entities': nbtlib.List[tag.Compound]([]),
    'blocks': nbtlib.List[tag.Compound](blocks),
    'palette': palette,
    'DataVersion': data_version,
})
rig.save(f'{OUT}/steering_rig.nbt', gzipped=True)

print(f'wrote {OUT}/race_car.nbt (palette swap: {swapped}) and {OUT}/steering_rig.nbt')
