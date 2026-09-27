#!/usr/bin/env python3
"""Shifts (and optionally re-parents) polygons of one bone that the source placement put in the wrong spot, and moves
that bone's pivot, in the custom geometry, the turret wreck model and the native fallback skeleton.

Polygons are picked by the centre of each polygon inside a box in model pixels, so a fix is reproducible from this
file alone and a second run finds nothing to do (the picked polygons have left the box).

Usage: python3 tools/model_fix/shift_polys.py [--write]
"""
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'assets', 'berts_vehicle_pack')
GEO = os.path.join(ASSETS, 'custom_geo')
FALLBACK = os.path.join(ASSETS, 'geo', 'native_fallback')

FIXES = [
    {'vid': 'm1a1_abrams', 'bone': 'barell', 'box': ((-4, 8, -80), (4, 16, -5)), 'shift': (0, 29.4, 0),
     'pivot': [0, 40.75, -34.0],
     'note': 'the 120 mm gun sat 29.4 px low, sticking out of the lower glacis; its axis now runs through the mantlet '
             'collar opening (x 0.55, y 40.75) and the gun pitches about the trunnion inside the mantlet'},
    {'vid': 'm1a1_abrams', 'bone': 'barell', 'to': 'turret', 'box': ((-14, 50, -12), (0, 62, 14)), 'shift': (0, 0, 0),
     'note': "the commander's M2 on the cupola was parented to the gun"},
    {'vid': 'type_90', 'bone': 'barell', 'to': 'turret', 'box': ((-3, 49, -40), (10, 59, 5)), 'shift': (0, 0, 0),
     'note': "the commander's M2 on the turret roof was parented to the gun"},
]


def bones_of(geo):
    return {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}


def pick(pm, box):
    lo, hi = np.array(box[0], float), np.array(box[1], float)
    P = np.array(pm['positions'], float)
    return [i for i, poly in enumerate(pm['polys'])
            if np.all((P[[v[0] for v in poly]].mean(0) >= lo) & (P[[v[0] for v in poly]].mean(0) <= hi))]


def move(src_pm, dst_bone, polys, shift):
    pm = dst_bone.setdefault('poly_mesh', {'normalized_uvs': src_pm.get('normalized_uvs', True),
                                           'positions': [], 'normals': [], 'uvs': [], 'polys': []})
    for poly in polys:
        out = []
        for p, n, u in poly:
            q = src_pm['positions'][p]
            pm['positions'].append([round(q[k] + shift[k], 5) for k in range(3)])
            pm['normals'].append(src_pm['normals'][n])
            pm['uvs'].append(src_pm['uvs'][u])
            out.append([len(pm['positions']) - 1, len(pm['normals']) - 1, len(pm['uvs']) - 1])
        pm['polys'].append(out)


def shift_in_place(pm, idx, shift):
    verts = sorted({v[0] for i in idx for v in pm['polys'][i]})
    shared = {v[0] for i, poly in enumerate(pm['polys']) if i not in set(idx) for v in poly} & set(verts)
    if shared:
        raise SystemExit(f'{len(shared)} vertices are shared with polygons outside the box')
    for v in verts:
        pm['positions'][v] = [round(pm['positions'][v][k] + shift[k], 5) for k in range(3)]


def compact(pm):
    """Drops positions, normals and uvs no polygon uses any more."""
    for slot, key in enumerate(('positions', 'normals', 'uvs')):
        used = sorted({v[slot] for poly in pm['polys'] for v in poly})
        remap = {old: new for new, old in enumerate(used)}
        pm[key] = [pm[key][i] for i in used]
        for poly in pm['polys']:
            for v in poly:
                v[slot] = remap[v[slot]]


def fix_geo(geo, fix, offset):
    """offset: where this model puts the turret relative to the main model (the wreck is re-centred)."""
    bones = bones_of(geo)
    pm = bones[fix['bone']]['poly_mesh']
    box = [[fix['box'][j][k] + offset[k] for k in range(3)] for j in range(2)]
    idx = pick(pm, box)
    if not idx:
        return 0
    if fix.get('to'):
        move(pm, bones[fix['to']], [pm['polys'][i] for i in idx], fix['shift'])
        keep = set(range(len(pm['polys']))) - set(idx)
        pm['polys'] = [p for i, p in enumerate(pm['polys']) if i in keep]
        compact(pm)
    else:
        shift_in_place(pm, idx, fix['shift'])
    if fix.get('pivot'):
        bones[fix['bone']]['pivot'] = [round(fix['pivot'][k] + offset[k], 5) for k in range(3)]
    return len(idx)


def main(argv):
    write = '--write' in argv
    for fix in FIXES:
        vid = fix['vid']
        path = os.path.join(GEO, vid + '.geo.json')
        geo = json.load(open(path))
        n = fix_geo(geo, fix, (0, 0, 0))
        if not n:
            print(f'{vid}: nothing to do for {fix["bone"]} ({fix["note"][:60]}...) - already fixed?')
            continue
        msg = f'{vid}: {n} polys of {fix["bone"]} ' + (f'-> {fix["to"]}' if fix.get('to') else f'shifted {fix["shift"]}')
        outputs = [(path, geo)]
        wreck_path = os.path.join(GEO, vid + '_turret_wreck.geo.json')
        if os.path.exists(wreck_path):
            wreck = json.load(open(wreck_path))
            t_main = bones_of(geo)['turret']['pivot']
            t_wreck = bones_of(wreck)['turret']['pivot']
            offset = [t_wreck[k] - t_main[k] for k in range(3)]
            msg += f'; turret wreck {fix_geo(wreck, fix, offset)} polys (offset {offset})'
            outputs.append((wreck_path, wreck))
        fb_path = os.path.join(FALLBACK, vid + '.geo.json')
        if fix.get('pivot') and os.path.exists(fb_path):
            fb = json.load(open(fb_path))
            bones_of(fb)[fix['bone']]['pivot'] = list(fix['pivot'])
            outputs.append((fb_path, fb))
            msg += '; fallback pivot'
        print(msg)
        if write:
            for p, g in outputs:
                newline = open(p).read().endswith('\n')
                with open(p, 'w') as f:
                    json.dump(g, f, separators=(',', ':'))
                    if newline:
                        f.write('\n')


if __name__ == '__main__':
    main(sys.argv[1:])
