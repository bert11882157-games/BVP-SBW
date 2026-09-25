#!/usr/bin/env python3
"""Handedness checks for aircraft models (geo +X = the aircraft's LEFT, nose toward -Z).

Witnesses are features whose side on the real aircraft is documented; every aircraft is also checked for nose
-Z (fin top and wide end at +Z). Exit code 1 on any failure.
"""
import json, os, sys
import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
GEO = os.path.join(ROOT, 'bvp/src/generated/resources/assets/berts_vehicle_pack/custom_geo')
# (aircraft, bone name, expected side) - tail rotors: AH-64 left, Mi-24D/V left, UH-1D (military) left,
# AH-6 left, AH-1F right, AH-1W right.
BONE_WITNESSES = [
    ('ah_64d', 'tail_rotor', 'left'), ('mi_24d', 'tail_rotor', 'left'), ('mi24v', 'tailPropeller', 'left'),
    ('uh1d_huey', 'tailPropeller', 'left'), ('ah_6j', 'tailPropeller', 'left'), ('ah_1f', 'tail_rotor', 'right'),
    ('ah1w_super_cobra', 'tailPropeller', 'right'), ('ka50', 'barell', 'right'),
]


def bones(vid):
    return json.load(open(os.path.join(GEO, vid + '.geo.json')))['minecraft:geometry'][0]['bones']


def side(x):
    return 'left' if x > 0 else 'right'


def centre(bone):
    pm = bone.get('poly_mesh') or {}
    if pm.get('positions'):
        return np.array(pm['positions']).mean(0)
    return np.array(bone.get('pivot', [0, 0, 0]), float)


def main():
    failures = []
    for vid, name, expected in BONE_WITNESSES:
        b = next((b for b in bones(vid) if b['name'] == name), None)
        got = side(centre(b)[0]) if b else 'missing'
        (failures.append if got != expected else print)(f'{vid} {name}: {got} (expected {expected})')
    # A-10: GAU-8 muzzle left of centre, nose wheel right.
    hull = np.array(next(b for b in bones('a_10') if b['name'] == 'hull')['poly_mesh']['positions'])
    muzzle = hull[(hull[:, 2] < -122) & (hull[:, 1] < 34)]
    g12 = np.array(next(b for b in bones('a_10') if b['name'] == 'landing_gear_12')['poly_mesh']['positions'])
    wheel = g12[g12[:, 1] < g12[:, 1].min() + 4]
    for label, x, expected in [('a_10 gun muzzle', muzzle[:, 0].mean(), 'left'), ('a_10 nose wheel', wheel[:, 0].mean(), 'right')]:
        (failures.append if side(x) != expected else print)(f'{label}: {side(x)} (expected {expected})')
    data = os.path.join(ROOT, 'bvp/src/generated/resources/data/berts_vehicle_pack/sbw/vehicles')
    for f in sorted(os.listdir(data)):
        d = json.load(open(os.path.join(data, f)))
        if d.get('Type') not in ('Airplane', 'Helicopter'):
            continue
        vid = f[:-5]
        path = os.path.join(GEO, vid + '.geo.json')
        if not os.path.exists(path):
            continue
        if d['Type'] != 'Airplane':
            continue  # helicopter seats use a cabin-relative frame
        # Facing: the pilot sits forward of the model's middle, i.e. toward the nose (-Z in geo, +Z in data).
        pts = np.vstack([np.array(b['poly_mesh']['positions']) for b in bones(vid)
                         if not b['name'].startswith('wreck') and (b.get('poly_mesh') or {}).get('positions')])
        mid_geo_z = (pts[:, 2].min() + pts[:, 2].max()) / 2
        seat_z = -16 * d['Seats'][0]['Position'][2]
        if seat_z > mid_geo_z:
            failures.append(f'{vid}: pilot seat behind the middle (nose not at -Z)')
    print('failures:', len(failures))
    for f in failures:
        print('  FAIL', f)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
