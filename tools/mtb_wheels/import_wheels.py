#!/usr/bin/env python3
"""Rebuilds a tracked vehicle's road wheels, idlers and sprockets from its SMP Toolbox model (.mtb).

The toolbox model keeps the running gear in its own parts: part 9 holds the left track wheels, part 10 the right
ones, part 12 the rear sprockets of both sides. Each wheel is the group of elements sharing one rotation point. Its
elements (Shapeboxes, and Shape elements: extruded outlines such as octagonal wheel discs) are rebuilt as textured
triangles with tools/mtb_wheels/mtb.py and replace the geometry of the matching wheel bone (the bone whose pivot is
nearest the wheel's rotation point), so the wheels in game are the wheels in the toolbox.

Toolbox frame (+x forward, +y down, +z left) -> model pixels: model = s * (z, -y, -x) + (0, ty, 0), with s and ty
found from the wheel bones' pivots (every vehicle was imported at its own scale). Texture coordinates are texel
positions on the toolbox skin; the vehicle texture keeps that skin in its top rows (later tools only add space
below it), so they are normalised by the vehicle texture's size.

Usage: python3 tools/mtb_wheels/import_wheels.py [--write] <vehicle_id>=<model.mtb> ...
"""
import json, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mtb  # noqa: E402

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'assets', 'berts_vehicle_pack')
GEO = os.path.join(GEN, 'custom_geo')
TEX = os.path.join(GEN, 'textures', 'entity')
WHEEL_PARTS = {'9': 'L', '10': 'R', '12': None}   # 12: rear sprockets, side from the rotation point's z


def wheels(elements):
    """{(side, rotation point): [element fields]} for the running-gear parts."""
    out = {}
    for f in elements:
        if f[4] not in WHEEL_PARTS:
            continue
        rp = (mtb.num(f[6]), mtb.num(f[7]), mtb.num(f[8]))
        side = WHEEL_PARTS[f[4]] or ('L' if rp[2] > 0 else 'R')
        out.setdefault((side, rp), []).append(f)
    return out


def wheel_bones(bones):
    """side -> {bone name: pivot} for the wheel bones (wheelL*, wheelR*)."""
    out = {'L': {}, 'R': {}}
    for b in bones:
        n = b['name']
        if n.startswith('wheel') and len(n) > 5 and n[5] in 'LR' and 'pivot' in b:
            out[n[5]][n] = np.array(b['pivot'], float)
    return out


def fit(pairs):
    """Least-squares s, ty for model = s*(z, -y, -x) + (0, ty, 0) from (rotation point, pivot) pairs. Only height
    and fore-aft position are used: a bone pivot sits at the centre of the old wheel's width, which need not be the
    rotation point's side position."""
    a, b = [], []
    for rp, pv in pairs:
        x, y, z = rp
        a += [[-y, 1], [-x, 0]]
        b += [pv[1], pv[2]]
    (s, ty), res, *_ = np.linalg.lstsq(np.array(a), np.array(b), rcond=None)
    err = np.abs(np.array(a) @ np.array([s, ty]) - np.array(b)).max()
    return s, ty, err


def to_model(p, s, ty):
    return [s * p[2], -s * p[1] + ty, -s * p[0]]


def build(vid, mtb_path):
    (tw, th), elements, _ = mtb.read(mtb_path)
    path = os.path.join(GEO, vid + '.geo.json')
    geo = json.load(open(path))
    bones = geo['minecraft:geometry'][0]['bones']
    by_name = {b['name']: b for b in bones}
    tex = Image.open(os.path.join(TEX, vid + '.png'))
    TW, TH = tex.size
    found = wheels(elements)
    targets = wheel_bones(bones)
    order = {}
    for side in 'LR':
        mine = sorted([rp for sd, rp in found if sd == side], key=lambda r: -r[0])       # front first
        bones_s = sorted(targets[side].items(), key=lambda kv: kv[1][2])                  # model -z is front
        if len(mine) != len(bones_s):
            raise SystemExit(f'{vid}: {len(mine)} toolbox wheels on side {side}, {len(bones_s)} wheel bones')
        for rp, (name, pv) in zip(mine, bones_s):
            order[(side, rp)] = name
    s, ty, err = fit([(rp, targets[side][order[(side, rp)]]) for side, rp in order])
    if err > 0.05:
        raise SystemExit(f'{vid}: toolbox wheels do not line up with the wheel bones (max error {err:.3f} px)')
    notes = []
    for (side, rp), els in sorted(found.items(), key=lambda kv: (kv[0][0], -kv[0][1][0])):
        name = order[(side, rp)]
        positions, normals, uvs, polys = [], [], [], []
        for f in els:
            polys_f = mtb.element_polys(f, tw, th)
            centre = np.mean([p for pos, _ in polys_f for p in pos], axis=0)
            for pos, uv in polys_f:
                P = [to_model(p, s, ty) for p in pos]
                # the toolbox winds faces counter-clockwise seen from outside; the axis swap mirrors, so reverse.
                # Shape outlines may be wound either way: orient those away from the element's centre instead.
                P, uv = P[::-1], uv[::-1]
                if f[5] == 'Shape':
                    n = np.cross(np.subtract(P[1], P[0]), np.subtract(P[2], P[1]))
                    if np.linalg.norm(n) > 1e-9 and np.dot(n, np.mean(P, 0) - np.array(to_model(centre, s, ty))) < 0:
                        P, uv = P[::-1], uv[::-1]
                # quads stay quads (the loader draws quads; a triangle costs a degenerate quad), longer outlines
                # are split into a fan of quads and at most one triangle
                pieces = [list(range(len(P)))] if len(P) <= 4 else \
                    [[0, k, k + 1, k + 2] if k + 2 < len(P) else [0, k, k + 1] for k in range(1, len(P) - 1, 2)]
                for idx in pieces:
                    q = np.array([P[i] for i in idx])
                    n = np.cross(q[1] - q[0], q[2] - q[1])
                    if len(idx) == 4 and np.linalg.norm(n) < 1e-7:
                        n = np.cross(q[2] - q[0], q[3] - q[2])
                    ln = np.linalg.norm(n)
                    if ln < 1e-7:
                        continue
                    normals.append([round(float(c), 5) for c in n / ln])
                    poly = []
                    for i in idx:
                        positions.append([round(float(c), 5) for c in P[i]])
                        uvs.append([round(uv[i][0] / TW, 6), round(1 - uv[i][1] / TH, 6)])
                        poly.append([len(positions) - 1, len(normals) - 1, len(uvs) - 1])
                    polys.append(poly)
        bone = by_name[name]
        old = len((bone.get('poly_mesh') or {}).get('polys', []))
        bone['poly_mesh'] = {'normalized_uvs': True, 'positions': positions, 'normals': normals, 'uvs': uvs,
                             'polys': polys}
        notes.append(f'{name}: {len(els)} elements, {old} -> {len(polys)} polygons')
    return path, geo, s, ty, err, notes


def main(argv):
    write = '--write' in argv
    for arg in argv:
        if arg.startswith('--'):
            continue
        vid, mtb_path = arg.split('=', 1)
        path, geo, s, ty, err, notes = build(vid, mtb_path)
        print(f'{vid}: scale {s:.6f}, y offset {ty:.4f} px, pivot error {err:.4f} px')
        for n in notes:
            print('  ' + n)
        if write:
            raw = open(path).read()
            with open(path, 'w') as fh:
                json.dump(geo, fh, separators=(',', ':'))
                if raw.endswith('\n'):
                    fh.write('\n')


if __name__ == '__main__':
    main(sys.argv[1:])
