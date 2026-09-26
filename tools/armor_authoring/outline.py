#!/usr/bin/env python3
"""Crude, untextured, true-size outlines of every vehicle, to build armour volumes around in Blockbench.

Each vehicle's visual model (custom_geo/<id>.geo.json, current size) is voxelised: the surface is rasterised into a
grid and every enclosed space filled, so the outline is a solid silhouette. The voxels are merged into boxes and
written as a Bedrock geometry file of plain poly-mesh boxes (no texture), in the same model-pixel frame as the
visual model and the armour mesh files, split into outline_hull / outline_turret / outline_barrel bones pivoting
where the model's turret and barrel pivot. Voxels are 2 px (1/8 block) for ground vehicles and helicopters and
coarser for large aircraft (at most ~200 voxels along the longest side).

Output: <out>/<category>/<id>.outline.geo.json with categories planes, helicopters, tanks, IFV, other
(vehicle Type Airplane, Helicopter, Tank, APC, anything else).
Usage: python3 tools/armor_authoring/outline.py OUT_DIR [id...]
"""
import json, math, os, sys
import numpy as np
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles')
CATEGORY = {'Airplane': 'planes', 'Helicopter': 'helicopters', 'Tank': 'tanks', 'APC': 'IFV'}
BARREL = ('barell', 'barrel')
MAX_CELLS = 200


def groups(bones):
    """bone name -> 'hull' | 'turret' | 'barrel' by ancestry."""
    parent = {b['name']: b.get('parent') for b in bones}
    out = {}
    for name in parent:
        n, g = name, 'hull'
        while n:
            if n.lower() in BARREL:
                g = 'barrel'; break
            if n.lower() == 'turret':
                g = 'turret'; break
            n = parent.get(n)
        out[name] = g
    return out


def triangles(bones, grouping):
    tris = {'hull': [], 'turret': [], 'barrel': []}
    for b in bones:
        pm = b.get('poly_mesh') or {}
        pos = pm.get('positions') or []
        for poly in pm.get('polys') or []:
            q = [pos[v[0]] for v in poly]
            for k in range(1, len(q) - 1):
                tris[grouping[b['name']]].append((q[0], q[k], q[k + 1]))
        for c in b.get('cubes') or []:
            o, s = c['origin'], c['size']
            x0, y0, z0 = o; x1, y1, z1 = o[0] + s[0], o[1] + s[1], o[2] + s[2]
            corners = [(x, y, z) for x in (x0, x1) for y in (y0, y1) for z in (z0, z1)]
            for a, b_, c_, d in ((0, 1, 3, 2), (4, 5, 7, 6), (0, 1, 5, 4), (2, 3, 7, 6), (0, 2, 6, 4), (1, 3, 7, 5)):
                tris[grouping[b['name']]] += [(corners[a], corners[b_], corners[c_]), (corners[a], corners[c_], corners[d])]
    return {g: np.array(t, float).reshape(-1, 3, 3) for g, t in tris.items() if t}


def rasterise(tris, lo, cell, shape):
    """Marks every cell a triangle passes through (dense barycentric sampling at half-cell spacing)."""
    grid = np.zeros(shape, bool)
    for tri in tris:
        a, b, c = tri
        span = max(np.linalg.norm(b - a), np.linalg.norm(c - a), np.linalg.norm(c - b))
        n = max(1, int(math.ceil(span / (cell * 0.5))))
        i, j = np.meshgrid(np.arange(n + 1), np.arange(n + 1), indexing='ij')
        keep = i + j <= n
        u, v = i[keep] / n, j[keep] / n
        pts = a + np.outer(u, b - a) + np.outer(v, c - a)
        idx = np.floor((pts - lo) / cell).astype(int)
        idx = np.clip(idx, 0, np.array(shape) - 1)
        grid[idx[:, 0], idx[:, 1], idx[:, 2]] = True
    return grid


def boxes(solid):
    """Greedy merge of solid cells into boxes: (i0, j0, k0, i1, j1, k1), exclusive ends."""
    left = solid.copy()
    out = []
    X, Y, Z = left.shape
    for i in range(X):
        for j in range(Y):
            for k in range(Z):
                if not left[i, j, k]:
                    continue
                k1 = k + 1
                while k1 < Z and left[i, j, k1]:
                    k1 += 1
                j1 = j + 1
                while j1 < Y and left[i, j1, k:k1].all():
                    j1 += 1
                i1 = i + 1
                while i1 < X and left[i1, j:j1, k:k1].all():
                    i1 += 1
                left[i:i1, j:j1, k:k1] = False
                out.append((i, j, k, i1, j1, k1))
    return out


def box_mesh(boxes_, lo, cell):
    positions, normals, polys = [], [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]], []
    faces = [((1, 0, 0), 0, [(1, 0, 0), (1, 1, 0), (1, 1, 1), (1, 0, 1)]),
             ((-1, 0, 0), 1, [(0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)]),
             ((0, 1, 0), 2, [(0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)]),
             ((0, -1, 0), 3, [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)]),
             ((0, 0, 1), 4, [(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)]),
             ((0, 0, -1), 5, [(0, 0, 0), (0, 1, 0), (1, 1, 0), (1, 0, 0)])]
    for i0, j0, k0, i1, j1, k1 in boxes_:
        a = lo + np.array([i0, j0, k0]) * cell
        b = lo + np.array([i1, j1, k1]) * cell
        for _, ni, quad in faces:
            poly = []
            for fx, fy, fz in quad:
                p = [a[0] if fx == 0 else b[0], a[1] if fy == 0 else b[1], a[2] if fz == 0 else b[2]]
                positions.append([round(float(x), 4) for x in p])
                poly.append([len(positions) - 1, ni, 0])
            polys.append(poly)
    return {'normalized_uvs': True, 'positions': positions, 'normals': normals, 'uvs': [[0, 0]], 'polys': polys}


def outline(vid):
    path = os.path.join(GEO, vid + '.geo.json')
    geo = json.load(open(path))
    bones = geo['minecraft:geometry'][0]['bones']
    grouping = groups(bones)
    tris = triangles(bones, grouping)
    if not tris:
        return None, 'no geometry'
    allp = np.concatenate([t.reshape(-1, 3) for t in tris.values()])
    lo_all, hi_all = allp.min(0), allp.max(0)
    cell = max(2.0, float((hi_all - lo_all).max()) / MAX_CELLS)
    lo = np.floor(lo_all / cell) * cell - cell
    shape = tuple(int(x) for x in np.ceil((hi_all - lo) / cell) + 2)
    pivots = {b['name']: b.get('pivot', [0, 0, 0]) for b in bones}
    out_bones, count = [], 0
    for g, parent in (('hull', None), ('turret', 'outline_hull'), ('barrel', 'outline_turret')):
        if g not in tris:
            continue
        shell = rasterise(tris[g], lo, cell, shape)
        solid = ndimage.binary_fill_holes(shell)
        bx = boxes(solid)
        count += len(bx)
        src = next((n for n in pivots if (n.lower() == 'turret' if g == 'turret' else n.lower() in BARREL)), None)
        bone = {'name': 'outline_' + g, 'pivot': [round(float(x), 4) for x in (pivots.get(src) if src else [0, 0, 0])]}
        if parent and any(b['name'] == parent for b in out_bones):
            bone['parent'] = parent
        elif g != 'hull' and out_bones:
            bone['parent'] = 'outline_hull'
        bone['poly_mesh'] = box_mesh(bx, lo, cell)
        out_bones.append(bone)
    size = hi_all - lo_all
    doc = {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': f'geometry.{vid}_outline', 'texture_width': 16, 'texture_height': 16,
                        'visible_bounds_width': round(float(max(size[0], size[2]) / 16 + 2), 1),
                        'visible_bounds_height': round(float(size[1] / 16 + 2), 1),
                        'visible_bounds_offset': [0, round(float(size[1] / 32), 2), 0]},
        'bones': out_bones}]}
    note = (f'{count} boxes, {cell:.2f} px cells, {size[0] / 16:.2f} x {size[1] / 16:.2f} x {size[2] / 16:.2f} blocks'
            f' ({", ".join(b["name"][8:] for b in out_bones)})')
    return doc, note


def category(vid):
    p = os.path.join(DATA, vid + '.json')
    t = json.load(open(p)).get('Type') if os.path.exists(p) else None
    return CATEGORY.get(t, 'other')


def main(argv):
    out = argv[0]
    ids = argv[1:] or sorted(f[:-5] for f in os.listdir(DATA)
                             if os.path.exists(os.path.join(GEO, f[:-5] + '.geo.json')))
    for vid in ids:
        doc, note = outline(vid)
        cat = category(vid)
        print(f'{cat:12s} {vid:34s} {note}', flush=True)
        if doc is None:
            continue
        os.makedirs(os.path.join(out, cat), exist_ok=True)
        with open(os.path.join(out, cat, vid + '.outline.geo.json'), 'w') as fh:
            json.dump(doc, fh, separators=(',', ':'))


if __name__ == '__main__':
    main(sys.argv[1:])
