#!/usr/bin/env python3
"""Blockbench authoring files with the vehicle's armor mesh in them, for tuning plate thickness by hand.

    python3 tools/armor_authoring/with_armor.py OUT_DIR [id...]    (default: every vehicle with an armor mesh)

Writes <OUT_DIR>/<category>/<id>.armor.geo.json: the solid outline (outline_hull / outline_turret / outline_barrel,
from outline.py) plus the armor mesh bones exactly as the game loads them (armor_hull / armor_turret / armor_barrel
with plate__<mm>mm__<name>, engine____*, ammo__*__* volumes). The armor frame bones get the turret and barrel pivots
so turning them in Blockbench turns the armor with the outline; the game ignores a frame bone's pivot while it has
no rotation, so leave the frame bones unrotated before saving.

To tune: rename a plate bone's thickness (plate__110mm__hull_lfp -> plate__130mm__hull_lfp), hide or delete the
outline bones, save, and put the file back as bvp/src/main/resources/data/berts_vehicle_pack/armor_mesh/<id>.geo.json
(and add the id to auto_mesh.HAND_TUNED so a regeneration keeps it).
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
MESH = os.path.join(REPO, 'bvp', 'src', 'main', 'resources', 'data', 'berts_vehicle_pack', 'armor_mesh')


def main(argv):
    out = argv[0]
    ids = argv[1:] or sorted(f[:-9] for f in os.listdir(MESH) if f.endswith('.geo.json'))
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run([sys.executable, os.path.join(HERE, 'outline.py'), tmp] + ids, check=True,
                       stdout=subprocess.DEVNULL)
        n = 0
        for cat in sorted(os.listdir(tmp)):
            for f in sorted(os.listdir(os.path.join(tmp, cat))):
                vid = f[:-len('.outline.geo.json')]
                mesh_path = os.path.join(MESH, f'{vid}.geo.json')
                if not os.path.exists(mesh_path):
                    continue
                outline = json.load(open(os.path.join(tmp, cat, f)))
                geo = outline['minecraft:geometry'][0]
                pivots = {b['name']: b.get('pivot', [0, 0, 0]) for b in geo['bones']}
                armor = json.load(open(mesh_path))['minecraft:geometry'][0]['bones']
                for b in armor:
                    if b['name'] == 'armor_turret':
                        b['pivot'] = pivots.get('outline_turret', b.get('pivot', [0, 0, 0]))
                    elif b['name'] == 'armor_barrel':
                        b['pivot'] = pivots.get('outline_barrel', b.get('pivot', [0, 0, 0]))
                geo['bones'] += armor
                geo['description']['identifier'] = f'geometry.{vid}_armor_authoring'
                os.makedirs(os.path.join(out, cat), exist_ok=True)
                with open(os.path.join(out, cat, f'{vid}.armor.geo.json'), 'w') as fh:
                    json.dump(outline, fh, separators=(',', ':'))
                    fh.write('\n')
                n += 1
    print(f'{n} authoring files with armor in {out}')


if __name__ == '__main__':
    main(sys.argv[1:])
