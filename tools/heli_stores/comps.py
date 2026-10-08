"""Connected parts of a geo bone's poly_mesh (polys sharing a position, or positions within EPS), with their bounding
boxes in vehicle-local blocks (+X left, +Y up, +Z forward). Usage: comps.py <geo.json> <bone> [min_x_abs]"""
import json, sys
import numpy as np

EPS = 1e-4


def to_local(p):
    return np.asarray(p, float) * np.array([1.0, 1.0, -1.0]) / 16.0


def bone_parts(geo, bone_name):
    bone = next(b for b in geo['minecraft:geometry'][0]['bones'] if b['name'] == bone_name)
    pm = bone['poly_mesh']
    pos = np.array(pm['positions'], float)
    # positions that coincide count as one (exports often duplicate vertices per face)
    key = {}
    canon = np.empty(len(pos), int)
    for i, p in enumerate(np.round(pos / EPS).astype(np.int64)):
        canon[i] = key.setdefault(tuple(p), i)
    parent = list(range(len(pm['polys'])))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]; x = parent[x]
        return x
    owner = {}
    for pi, poly in enumerate(pm['polys']):
        for v in poly:
            c = canon[v[0]]
            if c in owner:
                a, b = find(pi), find(owner[c])
                if a != b: parent[a] = b
            else:
                owner[c] = pi
    groups = {}
    for pi in range(len(pm['polys'])):
        groups.setdefault(find(pi), []).append(pi)
    parts = []
    for polys in groups.values():
        idx = sorted({v[0] for p in polys for v in pm['polys'][p]})
        P = to_local(pos[idx])
        parts.append({'polys': polys, 'lo': P.min(0), 'hi': P.max(0)})
    parts.sort(key=lambda q: (round(-abs((q['lo'][0] + q['hi'][0]) / 2), 2), q['lo'][2]))
    return bone, parts


if __name__ == '__main__':
    geo = json.load(open(sys.argv[1]))
    min_x = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0
    _, parts = bone_parts(geo, sys.argv[2])
    print(len(parts), 'parts')
    for i, q in enumerate(parts):
        c = (q['lo'] + q['hi']) / 2
        if abs(c[0]) < min_x:
            continue
        s = q['hi'] - q['lo']
        print(f"{i:4d} n{len(q['polys']):4d} c({c[0]:6.2f},{c[1]:5.2f},{c[2]:6.2f}) size({s[0]:4.2f},{s[1]:4.2f},{s[2]:4.2f})")
