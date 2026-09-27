#!/usr/bin/env python3
"""Ground-truth shot rays for testing a mesh armor file against the vehicle's visual model.

    python3 tools/armor_mesh/hitreg_rays.py t90a OUT.jsonl [--step PX] [--az 15] [--el 0,15,35,60]

Fires parallel rays from every direction (azimuth step, elevation list) on a grid over the vehicle and records, per
ray, the first visual-model triangle it meets and what it is:

* ``core``    - the structure the armor file is meant to cover (the hull core solid and the turret/mantlet components
                selected in tools/armor_mesh/specs/<id>.json);
* ``track``   - wheels and tracks;
* ``era``     - ERA bricks (kept as boxes in the armor profile);
* ``barrel``  - the gun tube;
* ``fitting`` - everything else (fenders, skirts, stowage, sights, cupola, lights, antennas...).

It also records whether any ``core`` triangle lies further along the same ray (a shot through a fitting into the
hull). Coordinates are written in the armor-profile frame (blocks; profile = (-x, y, z) / 16 of the geo frame) with
the turret at rest. ArmorMeshHitregHarness (bvp/src/test) resolves these rays with the game's own armor code.
"""
import argparse
import collections
import json
import math
import os
import re
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_mesh as B  # noqa: E402

TRACK = re.compile(r'^(Wheel|WheelL|WheelR|wheel|Track|TrackL|TrackR|crudeTrack|brokenTrack|track)', re.I)


def model_triangles(vid, spec):
    g = json.load(open(os.path.join(B.GEO, f'{vid}.geo.json')))['minecraft:geometry'][0]
    by = {b['name']: b for b in g['bones']}

    def chain(n):
        out = []
        while n:
            out.append(n)
            n = by[n].get('parent')
        return out
    # hull half-spaces: a hull triangle is core when all its corners lie within 0.5 px of the solid
    h = spec['hull']
    hull_pts = B.hull_points(spec)
    planes = [(n, d) for n, d, _ in B.hull_faces(hull_pts)]
    ring = spec['ring']
    # selected component points per bone, to mark their polygons as core
    core_points = collections.defaultdict(set)
    for part in spec['parts']:
        comps = B.model_components(vid, part['bone'])
        for s in part['select']:
            lo, hi = np.array(s['within'][0]), np.array(s['within'][1])
            for c in comps:
                mn, mx = c.min(0), c.max(0)
                if np.all(mn >= lo - 1e-6) and np.all(mx <= hi + 1e-6) and \
                        np.prod(np.maximum(mx - mn, 0.5)) >= s.get('minVolume', 0):
                    core_points[part['bone']].update(tuple(np.round(p, 3)) for p in c)
    # convex hulls of the selected core components: where a model component is open (faces the modeller left
    # out because they are hidden), a shot can pass into the component without meeting a model face
    hulls = []
    for part in spec['parts']:
        comps = B.model_components(vid, part['bone'])
        for s in part['select']:
            for c in B.components_in(comps, s):
                hulls.append(np.array([np.r_[n, d] for n, d, _ in B.hull_faces(c)]))
    model_triangles.hulls = hulls
    tris, cats, frames = [], [], []
    for b in g['bones']:
        pm = b.get('poly_mesh')
        if not pm:
            continue
        ch = chain(b['name'])
        if any(x.startswith(('trackMov', 'trackRot', 'crudeTrack', 'brokenTrack')) for x in ch):
            continue  # track links: replaced by the track envelope boxes below
        frame = 'barrel' if 'barell' in ch else 'turret' if 'turret' in ch else 'hull'
        P = np.array(pm['positions'], float)
        for poly in pm['polys']:
            q = P[[v[0] for v in poly]]
            c = q.mean(0)
            if any(TRACK.match(x) for x in ch):
                cat = 'track'
            elif any(x.startswith('bvpEraSpent') for x in ch):
                cat = 'era'
            elif b['name'] == 'barell' and c[2] < -29.4:
                cat = 'barrel'
            elif b['name'] in core_points and all(tuple(np.round(p, 3)) in core_points[b['name']] for p in q):
                cat = 'core'
            elif b['name'] == 'hull' and all((q @ n - d).max() <= 0.5 for n, d in planes):
                cat = 'core'
            elif b['name'] == 'hull' and ring and \
                    math.hypot(c[0] - ring['center'][0], c[2] - ring['center'][2]) <= ring['rOuter'] + 1.0 and \
                    ring['y'][0] - 0.5 <= c[1] <= 26.5:
                cat = 'core'
            else:
                cat = 'fitting'
            for i in range(1, len(q) - 1):
                tris.append((q[0], q[i], q[i + 1]))
                cats.append(cat)
                frames.append(frame)
    env = spec.get('trackEnvelope')
    if env:
        for sx in (1, -1):
            xs = sorted([sx * env['x'][0], sx * env['x'][1]])
            for face in B.box_faces([xs[0], env['y'][0], env['z'][0]], [xs[1], env['y'][1], env['z'][1]]):
                for i in (1, 2):
                    tris.append((face[0], face[i], face[i + 1]))
                    cats.append('track')
                    frames.append('hull')
    return np.array(tris), np.array(cats), np.array(frames)


def cast_dir(T, origins, d, u, v, cell=4.0):
    """Hit parameters of parallel rays (direction d) against every triangle, via a 2D bin grid in the (u, v) plane.
    Returns, per ray, the sorted-free arrays (t_first, index_first, t_first_core) computed by the caller's mask."""
    tu = T @ u
    tv = T @ v
    umin, umax = tu.min(1), tu.max(1)
    vmin, vmax = tv.min(1), tv.max(1)
    ou, ov = origins @ u, origins @ v
    bins = collections.defaultdict(list)
    for i in range(len(T)):
        for cu in range(int(math.floor(umin[i] / cell)), int(math.floor(umax[i] / cell)) + 1):
            for cv in range(int(math.floor(vmin[i] / cell)), int(math.floor(vmax[i] / cell)) + 1):
                bins[(cu, cv)].append(i)
    rays = collections.defaultdict(list)
    for k in range(len(origins)):
        key = (int(math.floor(ou[k] / cell)), int(math.floor(ov[k] / cell)))
        if key in bins:
            rays[key].append(k)
    v0, v1, v2 = T[:, 0], T[:, 1], T[:, 2]
    e1, e2 = v1 - v0, v2 - v0
    p = np.cross(d, e2)
    det = (e1 * p).sum(1)
    ok = np.abs(det) > 1e-12
    inv = np.where(ok, 1 / np.where(ok, det, 1), 0)
    for key, ks in rays.items():
        idx = np.array(bins[key])
        O = origins[ks]
        S = O[:, None, :] - v0[idx][None]
        uu = (S * p[idx][None]).sum(2) * inv[idx][None]
        q = np.cross(S, e1[idx][None])
        vv = (q * d).sum(2) * inv[idx][None]
        t = (q * e2[idx][None]).sum(2) * inv[idx][None]
        hit = ok[idx][None] & (uu >= -1e-9) & (vv >= -1e-9) & (uu + vv <= 1 + 1e-9) & (t > 1e-6)
        t = np.where(hit, t, np.inf)
        yield ks, idx, t


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument('vid')
    ap.add_argument('out')
    ap.add_argument('--step', type=float, default=2.0)
    ap.add_argument('--az', type=float, default=15.0)
    ap.add_argument('--el', default='0,15,35,60,-8')
    a = ap.parse_args(argv)
    spec = json.load(open(os.path.join(B.SPECS, f'{a.vid}.json')))
    T, cats, frames = model_triangles(a.vid, spec)
    core = cats == 'core'
    print('triangles', len(T), dict(collections.Counter(cats.tolist())))
    allpts = T.reshape(-1, 3)
    centre = (allpts.min(0) + allpts.max(0)) / 2
    radius = np.linalg.norm(allpts.max(0) - allpts.min(0)) / 2 + 4
    n = 0
    with open(a.out, 'w') as f:
        for el in [float(x) for x in a.el.split(',')]:
            for az in np.arange(0, 360, a.az):
                # geo frame: nose -Z; azimuth 0 = shot travelling +Z (from the front), 90 = from the vehicle's left (+X)
                ca, sa, ce, se = math.cos(math.radians(az)), math.sin(math.radians(az)), \
                    math.cos(math.radians(el)), math.sin(math.radians(el))
                d = np.array([-sa * ce, -se, ca * ce])
                d /= np.linalg.norm(d)
                u = np.cross(d, [0, 1, 0] if abs(d[1]) < 0.95 else [1, 0, 0])
                u /= np.linalg.norm(u)
                v = np.cross(u, d)
                grid = np.arange(-radius, radius + 1e-9, a.step)
                gu, gv = np.meshgrid(grid, grid)
                origins = centre - d * (radius + 2) + gu.reshape(-1, 1) * u + gv.reshape(-1, 1) * v
                # entry into any core component hull, per ray (inf when none)
                solid_t = np.full(len(origins), np.inf)
                for H in model_triangles.hulls:
                    den = H[:, :3] @ d
                    out = origins @ H[:, :3].T - H[:, 3]
                    with np.errstate(divide='ignore', invalid='ignore'):
                        tt = -out / den
                    enter = np.where(den < -1e-12, tt, -np.inf).max(1)
                    leave = np.where(den > 1e-12, tt, np.inf).min(1)
                    blocked = ((np.abs(den) <= 1e-12) & (out > 0)).any(1)
                    ok = (enter < leave) & ~blocked & (leave > 0)
                    solid_t = np.where(ok, np.minimum(solid_t, np.maximum(enter, 0)), solid_t)
                for ks, idx, t in cast_dir(T, origins, d, u, v):
                    tmin = t.min(1)
                    arg = t.argmin(1)
                    tcore = np.where(core[idx][None], t, np.inf).min(1)
                    for j, k in enumerate(ks):
                        if not np.isfinite(tmin[j]):
                            continue
                        ti = idx[arg[j]]
                        cat = cats[ti]
                        hit = origins[k] + d * tmin[j]
                        core_t = tcore[j] if np.isfinite(tcore[j]) else -1
                        O = origins[k]
                        rec = {'az': float(az), 'el': el, 'cat': str(cat), 'frame': str(frames[ti]),
                               'o': [round(-O[0] / 16, 5), round(O[1] / 16, 5), round(O[2] / 16, 5)],
                               'd': [round(-d[0], 6), round(d[1], 6), round(d[2], 6)],
                               'hit': [round(-hit[0] / 16, 5), round(hit[1] / 16, 5), round(hit[2] / 16, 5)],
                               't': round(float(tmin[j]) / 16, 5),
                               'core_t': round(float(core_t) / 16, 5) if core_t >= 0 else -1,
                               'solid_t': round(float(solid_t[k]) / 16, 5) if np.isfinite(solid_t[k]) else -1}
                        f.write(json.dumps(rec) + '\n')
                        n += 1
    print('rays', n)


if __name__ == '__main__':
    main(sys.argv[1:])
