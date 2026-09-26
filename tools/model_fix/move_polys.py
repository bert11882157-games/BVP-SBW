#!/usr/bin/env python3
"""Moves a set of polygons from one bone to another in a vehicle's custom geometry.

For parts the source model parented to the wrong bone (a turret-side grate left on the hull, an observation block
left on the cupola ring instead of the gun mount). Polygons are picked by index range in the source bone and
optionally filtered by a box in model pixels, so a fix is reproducible from this file alone.

When polygons move from outside the turret onto it (or one of its children), they are also added to the separate
turret wreck model (<id>_turret_wreck.geo.json), shifted by the offset that model applies to the turret.

Usage: python3 tools/model_fix/move_polys.py [--write]
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')

# vid, source bone, target bone, poly index range [a, b), box (min xyz, max xyz) in model px, note
FIXES = [
    ('m1a2_abrams_sep_v2', 'hull', 'turret', (940, 1014), ((-29.5, 29.0, 2.0), (-25.5, 40.0, 17.0)),
     'right turret-side grate left on the hull'),
    ('t72b', 'turret', 'passengerWeaponStationPitch', (1182, 1194), ((-12.5, 37.9, 3.9), (-6.5, 39.8, 7.4)),
     'block under the cupola DShK left on the turret roof'),
]


def bones_of(geo):
    return {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}


def poly_points(pm, poly):
    return np.array([pm['positions'][v[0]] for v in poly], float)


def append_polys(dst, src_pm, polys, shift):
    pm = dst.setdefault('poly_mesh', {'normalized_uvs': src_pm.get('normalized_uvs', True),
                                      'positions': [], 'normals': [], 'uvs': [], 'polys': []})
    for key in ('positions', 'normals', 'uvs', 'polys'):
        pm.setdefault(key, [])
    for poly in polys:
        out = []
        for p, n, u in poly:
            q = src_pm['positions'][p]
            pm['positions'].append([round(q[0] + shift[0], 5), round(q[1] + shift[1], 5), round(q[2] + shift[2], 5)])
            pm['normals'].append(src_pm['normals'][n])
            pm['uvs'].append(src_pm['uvs'][u])
            out.append([len(pm['positions']) - 1, len(pm['normals']) - 1, len(pm['uvs']) - 1])
        pm['polys'].append(out)


def turret_family(bones, name):
    while name:
        if name == 'turret':
            return True
        name = bones[name].get('parent') if name in bones else None
    return False


def apply(fix, write):
    vid, src, dst, (a, b), box, note = fix
    path = os.path.join(GEO, vid + '.geo.json')
    geo = json.load(open(path)); bones = bones_of(geo)
    pm = bones[src]['poly_mesh']
    lo, hi = np.array(box[0]), np.array(box[1])
    pick = [i for i in range(a, min(b, len(pm['polys'])))
            if np.all((poly_points(pm, pm['polys'][i]).mean(0) >= lo) & (poly_points(pm, pm['polys'][i]).mean(0) <= hi))]
    if not pick:
        return f'{vid}: nothing to move ({note}) - already fixed?'
    moving = [pm['polys'][i] for i in pick]
    append_polys(bones[dst], pm, moving, (0.0, 0.0, 0.0))
    keep = set(range(len(pm['polys']))) - set(pick)
    pm['polys'] = [p for i, p in enumerate(pm['polys']) if i in keep]
    msg = f'{vid}: {len(pick)} polys {src} -> {dst} ({note})'
    wreck_path = os.path.join(GEO, vid + '_turret_wreck.geo.json')
    wreck = None
    if turret_family(bones, dst) and not turret_family(bones, src) and os.path.exists(wreck_path):
        wreck = json.load(open(wreck_path)); wb = bones_of(wreck)
        # offset the wreck model applies: compare the first turret vertex in both models
        t_main = bones['turret']['poly_mesh']['positions'][0]
        t_wreck = wb['turret']['poly_mesh']['positions'][0]
        shift = [t_wreck[k] - t_main[k] for k in range(3)]
        append_polys(wb[dst], pm, moving, shift)
        msg += f'; turret wreck too (shift {np.round(shift, 3).tolist()})'
    if write:
        json.dump(geo, open(path, 'w'), separators=(',', ':'))
        if wreck is not None:
            json.dump(wreck, open(wreck_path, 'w'), separators=(',', ':'))
    return msg


def main(argv):
    write = '--write' in argv
    for fix in FIXES:
        print(apply(fix, write))


if __name__ == '__main__':
    main(sys.argv[1:])
