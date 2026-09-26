#!/usr/bin/env python3
"""Splits the pop-out wings of cruise missiles into their own bones so they can fold on the pylon and deploy after
launch (BvpFoldingWings).

Each missile lists its wing polygons by a box in model pixels. Every side's panel becomes a bone
(`wing_left` for +x, `wing_right` for -x) pivoting at its root; the fold angle turns the panel about the vertical
axis until it lies along the body, pointing aft. Angles are for the PolyMesh frame the renderer rotates bones in
(model x reversed), right-handed about +Y.

Usage: python3 tools/munition_wings/fold.py [--write]
"""
import json, math, os, sys
import numpy as np

GEN = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')


# model file -> wing selection box (all vertices inside), body axis (index), nose direction along it, vertical axis
MODELS = {
    'aircraft_stores/agm84k_slam_er': dict(box=dict(x=(3.9, 99), y=(-4.9, -4.45)), body=2, nose=+1, up=1),
    'aircraft_stores/kh55': dict(box=dict(x=(4.4, 99), y=(-5.0, 3.0), z=(6.8, 7.8)), body=2, nose=-1, up=1),
    'projectiles/kh55': dict(box=dict(x=(4.4, 99), y=(39.8, 41.0), z=(-5.0, 3.0)), body=1, nose=+1, up=2),
}
OUT = os.path.join(GEN, 'assets/berts_vehicle_pack/folding_wings.json')


def inside(q, box):
    ax = np.abs(q[:, 0])
    ok = (ax >= box['x'][0] - 1e-6) & (ax <= box['x'][1])
    ok &= (q[:, 1] >= box['y'][0]) & (q[:, 1] <= box['y'][1])
    if 'z' in box:
        ok &= (q[:, 2] >= box['z'][0]) & (q[:, 2] <= box['z'][1])
    return bool(ok.all()) and ((q[:, 0] > 0).all() or (q[:, 0] < 0).all())


def split(path, spec):
    doc = json.load(open(path))
    bones = doc['minecraft:geometry'][0]['bones']
    if any(b['name'].startswith('wing_') for b in bones):
        return None, 'already split'
    root = bones[0]
    pm = root['poly_mesh']
    sides = {'wing_left': [], 'wing_right': []}
    keep = []
    for poly in pm['polys']:
        q = np.array([pm['positions'][v[0]] for v in poly], float)
        if inside(q, spec['box']):
            sides['wing_left' if q[:, 0].mean() > 0 else 'wing_right'].append(poly)
        else:
            keep.append(poly)
    if not sides['wing_left'] or not sides['wing_right']:
        return None, 'no wing polygons'
    pm['polys'] = keep
    folds = {}
    for name, polys in sides.items():
        pts = np.array([pm['positions'][v[0]] for p in polys for v in p], float)
        sgn = 1 if name == 'wing_left' else -1
        body, up = spec['body'], spec['up']
        inner = pts[np.abs(pts[:, 0]) <= np.abs(pts[:, 0]).min() + 0.3]
        pivot = np.zeros(3)
        pivot[0] = sgn * np.abs(pts[:, 0]).min()
        pivot[up] = pts[:, up].mean()
        pivot[body] = inner[:, body].max() if spec['nose'] > 0 else inner[:, body].min()
        tip = pts[np.argmax(np.abs(pts[:, 0]))]
        # PolyMesh frame: x reversed. The folded panel points aft (-nose along the body axis).
        v = np.array([-(tip[0] - pivot[0]), tip[body] - pivot[body]])
        target = np.array([0.0, -spec['nose']])
        # Right-handed about the vertical axis. About +Y: (x, z) -> (x c + z s, -x s + z c);
        # about +Z: (x, y) -> (x c - y s, x s + y c).
        def turn(t):
            c, s_ = math.cos(math.radians(t)), math.sin(math.radians(t))
            return [v[0] * c + v[1] * s_, -v[0] * s_ + v[1] * c] if up == 1 else [v[0] * c - v[1] * s_, v[0] * s_ + v[1] * c]
        best = max(np.arange(-180, 180, 0.25), key=lambda t: float(np.dot(turn(t), target)))
        axis = [0, 1, 0] if up == 1 else [0, 0, 1]
        folds[name] = {'Axis': axis, 'Degrees': round(float(best), 2)}
        nb = {'name': name, 'parent': root['name'], 'pivot': [round(float(c), 5) for c in pivot],
              'poly_mesh': {'normalized_uvs': pm.get('normalized_uvs', True), 'positions': pm['positions'],
                            'normals': pm['normals'], 'uvs': pm['uvs'], 'polys': polys}}
        bones.append(nb)
    return doc, folds


def main(argv):
    write = '--write' in argv
    table = json.load(open(OUT)) if os.path.exists(OUT) else {}
    for model, spec in MODELS.items():
        path = os.path.join(GEO, model + '.geo.json')
        doc, result = split(path, spec)
        print(f'{model:36s} {result}')
        if doc is None:
            continue
        table['berts_vehicle_pack:custom_geo/' + model + '.geo.json'] = {'DeployTicks': 8, 'Bones': result}
        if write:
            with open(path, 'w') as fh:
                json.dump(doc, fh, separators=(',', ':'))
    if write:
        with open(OUT, 'w') as fh:
            json.dump(table, fh, indent=1)
            fh.write('\n')


if __name__ == '__main__':
    main(sys.argv[1:])
