#!/usr/bin/env python3
"""9P149 Shturm-S launcher rig: pivots on the launcher arm and the bones the reload animation drives.

    python3 tools/vehgen/shturm_reload_rig.py [--check]

Model (geo px; data frame = (x, y, -z) / 16):

* ``turret``  - the launcher pole and its top mount: yaws about the pole's own axis (x -12.75, z 30.57), rolls
  about the pole foot at roof height (y 33.0) when it swings into the hull for reloading.
* ``barell``  - the 9M114 launch tube and rail: elevates about the mount at the top of the pole (y 45.7).
* ``launcher_plate``  - the angled plate between the pole and the roof (hull child, hinged at its roof edge): follows
  the pole's yaw and folds flat onto the roof while the pole is down.
* ``launcher_tube_spent`` - a copy of the tube (hull child, pivot = the barrel's, on the pole axis), shown while the
  fired tube is thrown off, falls and lies on the ground; the barrel itself is hidden from the shot until the new tube appears.
* ``reload_hatch`` - the roof hatch beside the pole (hull child, hinged along its outer edge, x 10.2).
* ``reload_hatch_well`` - a black quad just above the roof under the closed hatch (inside the hatch slab, so it
  cannot be seen while the hatch is closed): the open hatch shows a black opening (there is no interior).

Moves hull components between bones by their geometry (pole, plate, hatch), so run it on the fitted model; a second
run finds the rig in place and changes nothing. Updates TurretPos / BarrelPos and every Turret- or Barrel-frame
position in the vehicle data so nothing moves in the world.
"""
import copy
import json
import os
import sys

import numpy as np
from PIL import Image

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
GEO = os.path.join(REPO, 'bvp/src/generated/resources/assets/berts_vehicle_pack/custom_geo/9p149_shturm.geo.json')
TEX = os.path.join(REPO, 'bvp/src/generated/resources/assets/berts_vehicle_pack/textures/entity/9p149_shturm.png')
DATA = os.path.join(REPO, 'bvp/src/generated/resources/data/berts_vehicle_pack/sbw/vehicles/9p149_shturm.json')

YAW_AXIS = (-12.75, 30.57)          # x, z of the pole axis
ROLL_Y = 33.0                        # pole foot at roof height
PITCH_Y = 45.7                       # mount at the top of the pole
POLE_BOX = ((-14.2, 31.8, 28.9), (-11.2, 46.1, 32.2))
PLATE_BOX = ((-21.8, 32.0, 27.5), (-14.6, 41.8, 33.6))
HATCH_BOX = ((-0.1, 32.7, 8.8), (10.3, 34.2, 47.2))
PLATE_HINGE = (-21.7, 32.1, 30.55)   # roof edge of the plate
HATCH_HINGE = (10.2, 34.1, 28.0)     # outer edge of the hatch
BLACK = (1018, 634, 6)               # texel block painted black for the well


def data_pos(p):
    return [round(p[0] / 16, 5), round(p[1] / 16, 5), round(-p[2] / 16, 5)]


def components(pm):
    P = np.array(pm['positions'], float)
    key, parent = {}, list(range(len(pm['polys'])))

    def f(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    for pi, poly in enumerate(pm['polys']):
        for v in poly:
            k = tuple(np.round(P[v[0]], 3))
            if k in key:
                parent[f(pi)] = f(key[k])
            else:
                key[k] = pi
    out = {}
    for pi in range(len(pm['polys'])):
        out.setdefault(f(pi), []).append(pi)
    return P, list(out.values())


def inside(Q, box):
    lo, hi = np.array(box[0]), np.array(box[1])
    return bool(np.all(Q.min(0) >= lo) and np.all(Q.max(0) <= hi))


def split(pm, polys):
    """(poly_mesh with only `polys`, poly_mesh without them); vertex/normal/uv arrays are kept whole."""
    keep = [p for i, p in enumerate(pm['polys']) if i not in polys]
    take = [p for i, p in enumerate(pm['polys']) if i in polys]
    a = dict(pm); a['polys'] = take
    b = dict(pm); b['polys'] = keep
    return compact(a), compact(b)


def compact(pm):
    """Drops unreferenced positions/normals/uvs and renumbers."""
    maps = [{}, {}, {}]
    arrs = [pm['positions'], pm['normals'], pm['uvs']]
    out = [[], [], []]
    polys = []
    for poly in pm['polys']:
        np_ = []
        for v in poly:
            nv = []
            for k in range(3):
                i = v[k]
                if i not in maps[k]:
                    maps[k][i] = len(out[k])
                    out[k].append(arrs[k][i])
                nv.append(maps[k][i])
            np_.append(nv)
        polys.append(np_)
    r = {kk: vv for kk, vv in pm.items() if kk not in ('positions', 'normals', 'uvs', 'polys')}
    r.update({'positions': out[0], 'normals': out[1], 'uvs': out[2], 'polys': polys})
    return r


def merge(a, b):
    if not a or not a.get('polys'):
        return copy.deepcopy(b)
    r = copy.deepcopy(a)
    n0, n1, n2 = len(r['positions']), len(r['normals']), len(r['uvs'])
    r['positions'] += b['positions']; r['normals'] += b['normals']; r['uvs'] += b['uvs']
    r['polys'] += [[[v[0] + n0, v[1] + n1, v[2] + n2] for v in poly] for poly in b['polys']]
    return r


def well_quad(tw, th):
    x0, y0, s = BLACK
    u = (x0 + s / 2) / tw
    v = 1 - (y0 + s / 2) / th
    y = 33.8   # roof 33.6, hatch slab 32.8-34.1: inside the slab, above the roof
    # the hatch is a trapezoid (its ends are angled: z 12.74-43.32 at x 0, 8.92-47.14 at x 10.19); inset 0.35 px
    zlo = lambda x: 12.74 - 3.82 * x / 10.19
    zhi = lambda x: 43.32 + 3.82 * x / 10.19
    a, b = 0.35, 9.84
    pos = [[a, y, round(zlo(a) + 0.35, 3)], [b, y, round(zlo(b) + 0.35, 3)],
           [b, y, round(zhi(b) - 0.35, 3)], [a, y, round(zhi(a) - 0.35, 3)]]
    # counter-clockwise seen from above (+y); the loader draws both sides of cutout geometry anyway
    return {'normalized_uvs': True, 'positions': pos, 'normals': [[0, 1, 0]], 'uvs': [[u, v]],
            'polys': [[[0, 0, 0], [3, 0, 0], [2, 0, 0], [1, 0, 0]]]}


def main(argv):
    check = '--check' in argv
    text = open(GEO).read()
    geo = json.loads(text)
    g = geo['minecraft:geometry'][0]
    bones = g['bones']
    by = {b['name']: b for b in bones}
    if 'reload_hatch' in by:
        print('rig already in place')
        return 0
    tw, th = g['description']['texture_width'], g['description']['texture_height']
    hull = by['hull']
    P, comps = components(hull['poly_mesh'])
    found = {}
    for c in comps:
        Q = np.array([P[v[0]] for pi in c for v in hull['poly_mesh']['polys'][pi]])
        for name, box in (('pole', POLE_BOX), ('plate', PLATE_BOX), ('hatch', HATCH_BOX)):
            if inside(Q, box):
                found.setdefault(name, []).extend(c)
    for name in ('pole', 'plate', 'hatch'):
        if name not in found:
            raise SystemExit(f'no {name} component in the hull')
        print(f'{name}: {len(found[name])} polygons')
    pm = hull['poly_mesh']
    parts = {}
    for name in ('pole', 'plate', 'hatch'):
        idx = set(found[name])
        take = [p for i, p in enumerate(pm['polys']) if i in idx]
        parts[name] = compact(dict(pm, polys=take))
    rest = set(i for n in found.values() for i in n)
    hull['poly_mesh'] = compact(dict(pm, polys=[p for i, p in enumerate(pm['polys']) if i not in rest]))

    turret, barrel = by['turret'], by['barell']
    turret['pivot'] = [YAW_AXIS[0], ROLL_Y, YAW_AXIS[1]]
    turret['poly_mesh'] = merge(turret.get('poly_mesh'), parts['pole'])
    barrel['pivot'] = [YAW_AXIS[0], PITCH_Y, YAW_AXIS[1]]
    spent = {'name': 'launcher_tube_spent', 'parent': 'hull', 'pivot': list(barrel['pivot']),
             'poly_mesh': copy.deepcopy(barrel['poly_mesh'])}
    plate = {'name': 'launcher_plate', 'parent': 'hull', 'pivot': list(PLATE_HINGE), 'poly_mesh': parts['plate']}
    hatch = {'name': 'reload_hatch', 'parent': 'hull', 'pivot': list(HATCH_HINGE), 'poly_mesh': parts['hatch']}
    well = {'name': 'reload_hatch_well', 'parent': 'hull', 'pivot': [5.0, 33.8, 28.0], 'poly_mesh': well_quad(tw, th)}
    i = bones.index(barrel)
    bones[i + 1:i + 1] = [spent]
    bones += [plate, hatch, well]

    # vehicle data: new pivots, and every Turret/Barrel-frame position kept in place in the world
    d = json.load(open(DATA))
    old_t = np.array(d['TurretPos'], float)
    old_b = old_t + np.array(d['BarrelPos'], float)
    new_t = np.array(data_pos(turret['pivot']), float)
    new_b = np.array(data_pos(barrel['pivot']), float)
    d['TurretPos'] = [round(float(v), 5) for v in new_t]
    d['BarrelPos'] = [round(float(v), 5) for v in new_b - new_t]
    moved = []
    for name, a in (d.get('Attachments') or {}).items():
        if a.get('Parent') == 'Turret':
            a['Position'] = [round(float(v), 5) for v in np.array(a['Position']) + old_t - new_t]; moved.append(name)
        elif a.get('Parent') == 'Barrel':
            a['Position'] = [round(float(v), 5) for v in np.array(a['Position']) + old_b - new_b]; moved.append(name)
    if 'turretPivot' in d.get('Attachments', {}):
        d['Attachments']['turretPivot']['Position'] = list(d['TurretPos'])
    if 'barrelPivot' in d.get('Attachments', {}):
        d['Attachments']['barrelPivot']['Position'] = list(d['BarrelPos'])
    for s in d.get('Seats', []):
        if s.get('Transform') == 'Turret':
            s['Position'] = [round(float(v), 5) for v in np.array(s['Position']) + old_t - new_t]; moved.append('seat')
    for o in d.get('OBB', []):
        if o.get('Transform') == 'Barrel':
            o['Position'] = [round(float(v), 5) for v in np.array(o['Position']) + old_b - new_b]; moved.append('obb')
        elif o.get('Transform') == 'Turret':
            o['Position'] = [round(float(v), 5) for v in np.array(o['Position']) + old_t - new_t]; moved.append('obb')
    print('TurretPos', d['TurretPos'], 'BarrelPos', d['BarrelPos'], 'moved', moved)
    if check:
        return 0
    img = Image.open(TEX).convert('RGBA')
    a = np.array(img)
    x0, y0, s = BLACK
    a[y0:y0 + s, x0:x0 + s] = [0, 0, 0, 255]
    Image.fromarray(a).save(TEX)
    open(GEO, 'w').write(json.dumps(geo) + ('\n' if text.endswith('\n') else ''))
    dt = open(DATA).read()
    open(DATA, 'w').write(json.dumps(d, indent=2, ensure_ascii=False) + ('\n' if dt.endswith('\n') else ''))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
