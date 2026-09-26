#!/usr/bin/env python3
"""Finds the wheels inside each aircraft's landing gear bones.

A wheel is a group of polygons (connected by shared vertices, merged when coaxial) that is round in the side view
(height ~ length), narrower across (the axle runs along model x), and hangs at the bottom of its gear leg.
Returns, per gear bone, the wheels: polygon indices, axle centre (model px) and radius.
"""
import json, os
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
VEH = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')


def components(pm):
    polys = pm['polys']
    key = lambda p: tuple(np.round(pm['positions'][p], 3))
    parent = list(range(len(polys)))
    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]; i = parent[i]
        return i
    owner = {}
    for i, poly in enumerate(polys):
        for v in poly:
            k = key(v[0])
            if k in owner:
                a, b = find(i), find(owner[k])
                if a != b: parent[a] = b
            else:
                owner[k] = i
    groups = {}
    for i in range(len(polys)):
        groups.setdefault(find(i), []).append(i)
    return list(groups.values())


def points(pm, idx):
    return np.array([pm['positions'][v[0]] for i in idx for v in pm['polys'][i]], float)


def wheels_in(pm):
    comps = []
    for idx in components(pm):
        P = points(pm, idx)
        lo, hi = P.min(0), P.max(0)
        comps.append(dict(idx=idx, lo=lo, hi=hi, c=(lo + hi) / 2, size=hi - lo))
    # Tyres built from separate blocks around the rim (same x span, centres on a circle) form one group.
    rings = {}
    for c in comps:
        rings.setdefault((round(float(c['lo'][0]), 1), round(float(c['hi'][0]), 1)), []).append(c)
    for members in rings.values():
        if len(members) < 6:
            continue
        lo = np.min([m['lo'] for m in members], 0); hi = np.max([m['hi'] for m in members], 0)
        centre = (lo + hi) / 2
        d = np.array([np.hypot(m['c'][1] - centre[1], m['c'][2] - centre[2]) for m in members])
        if d.mean() < 0.25 * (hi - lo)[1] or d.std() > 0.15 * d.mean():
            continue
        ring = dict(idx=[i for m in members for i in m['idx']], lo=lo, hi=hi, c=centre, size=hi - lo)
        comps = [c for c in comps if not any(c is m for m in members)] + [ring]
    # Coaxial pieces (tyre halves, rims, hub) share the axle centre exactly: join them first.
    joined = []
    for c in sorted(comps, key=lambda c: -max(c['size'][1], c['size'][2])):
        for m in joined:
            if (abs(c['c'][1] - m['c'][1]) < 0.35 and abs(c['c'][2] - m['c'][2]) < 0.35
                    and c['lo'][1] >= m['lo'][1] - 0.1 and c['hi'][1] <= m['hi'][1] + 0.1
                    and c['lo'][2] >= m['lo'][2] - 0.1 and c['hi'][2] <= m['hi'][2] + 0.1
                    and c['lo'][0] <= m['hi'][0] + 0.3 and c['hi'][0] >= m['lo'][0] - 0.3):
                m['idx'] = m['idx'] + c['idx']; m['lo'] = np.minimum(m['lo'], c['lo']); m['hi'] = np.maximum(m['hi'], c['hi'])
                m['c'] = (m['lo'] + m['hi']) / 2; m['size'] = m['hi'] - m['lo']
                break
        else:
            joined.append(dict(c, idx=list(c['idx'])))
    comps = joined
    # Round groups are wheel candidates; smaller groups inside one of them in the side view (hub, rim) join it.
    round_ = lambda c: min(c['size'][1], c['size'][2]) >= 0.6 and \
        abs(c['size'][1] - c['size'][2]) <= 0.22 * max(c['size'][1], c['size'][2])
    merged = [dict(c, idx=list(c['idx'])) for c in comps if round_(c)]
    merged.sort(key=lambda c: -c['size'][1])
    for c in comps:
        if round_(c) and any(c is not m and False for m in merged):
            continue
        for m in merged:
            if m['idx'] == c['idx'] or set(c['idx']) <= set(m['idx']):
                break
            inside = (c['lo'][1] >= m['lo'][1] - 0.05 and c['hi'][1] <= m['hi'][1] + 0.05
                      and c['lo'][2] >= m['lo'][2] - 0.05 and c['hi'][2] <= m['hi'][2] + 0.05
                      and c['lo'][0] <= m['hi'][0] + 0.3 and c['hi'][0] >= m['lo'][0] - 0.3
                      and abs(c['c'][1] - m['c'][1]) < 0.12 * m['size'][1]
                      and abs(c['c'][2] - m['c'][2]) < 0.12 * m['size'][2])
            if inside:
                m['idx'] += c['idx']; m['lo'] = np.minimum(m['lo'], c['lo']); m['hi'] = np.maximum(m['hi'], c['hi'])
                m['c'] = (m['lo'] + m['hi']) / 2; m['size'] = m['hi'] - m['lo']
                break
    # a smaller round group swallowed by a bigger one is not a wheel of its own
    seen, unique = set(), []
    for m in merged:
        key = frozenset(m['idx'])
        if any(key <= u for u in seen):
            continue
        seen.add(key); unique.append(m)
    merged = unique
    out = []
    for m in merged:
        sx, sy, sz = m['size']
        if sy < 0.6 or sz < 0.6:
            continue
        if abs(sy - sz) > 0.22 * max(sy, sz):          # round in the side view
            continue
        if sx > 0.9 * max(sy, sz) or sx < 0.12 * max(sy, sz):   # a tyre: thinner than tall, thicker than a door
            continue
        P = points(pm, m['idx'])
        # a real wheel is a ring: its vertices lie away from the axle, not along a line (struts are long and thin)
        c = m['c']
        rr = np.hypot(P[:, 1] - c[1], P[:, 2] - c[2])
        if np.percentile(rr, 90) < 0.3 * max(sy, sz) / 2:
            continue
        if rr.max() > 1.12 * max(sy, sz) / 2:               # corners filled: a box or a slanted strut, not a disc
            continue
        out.append(dict(idx=sorted(m['idx']), centre=c.tolist(), radius=float(max(sy, sz) / 2), width=float(sx)))
    return out


def find(vid):
    d = json.load(open(os.path.join(VEH, vid + '.json')))
    rig = d.get('AircraftRig') or {}
    geo = json.load(open(os.path.join(GEO, vid + '.geo.json')))
    bones = {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}
    res = {}
    kids = {}
    for b in bones.values():
        kids.setdefault(b.get('parent'), []).append(b['name'])
    for g in rig.get('Gear') or []:
        if g.get('VisibleWhen') == 'RETRACTED':
            continue
        stack, found = [g['Bone']], []
        while stack:
            name = stack.pop()
            stack += kids.get(name, [])
            pm = (bones.get(name) or {}).get('poly_mesh')
            if pm and pm.get('polys'):
                for w in wheels_in(pm):
                    found.append(dict(w, bone=name))
        res[g['Bone']] = found
    return res


if __name__ == '__main__':
    import sys
    for vid in sys.argv[1:]:
        r = find(vid)
        print(vid, {k: [(w['bone'][-22:], len(w['idx']), [round(x, 1) for x in w['centre']], round(w['radius'], 2), round(w['width'], 2)) for w in v] for k, v in r.items()})
