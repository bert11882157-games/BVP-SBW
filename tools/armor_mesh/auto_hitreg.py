#!/usr/bin/env python3
"""Ground-truth shot rays for a generated mesh armor file (auto_mesh.py), plus a phantom-armor count.

    python3 tools/armor_mesh/auto_hitreg.py <id> <rays.jsonl> [--mesh FILE] [--step PX] [--az DEG] [--el LIST]

Like hitreg_rays.py (same record format, read by ArmorMeshHitregHarness), but the categories come from auto_mesh's
own solids instead of a hand-written spec. For each parallel ray that meets the visual model, the first model
triangle is:

* ``core``    - part of the structure the armor covers (a kept component lying within 0.5 px of an armor solid of
                its frame);
* ``track``   - wheels and tracks (no hitbox; a shot through them should reach the hull plates behind);
* ``era``     - ERA (kept as boxes);
* ``barrel``  - gun tube and other barrel-frame parts outside the mantlet;
* ``fitting`` - everything else.

``core_t`` is where the ray meets core structure, if at all. The script also prints how many rays miss the model
entirely but would still enter the armor mesh ("phantom" armor, where the convex solids bridge a concave outline).
Coordinates are written in the armor-profile frame (blocks): (-x, y, z) / 16 of the geo frame, or (x, y, z) / 16
for the X-mirrored profiles.
"""
import argparse
import collections
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import auto_mesh as A  # noqa: E402
import build_mesh as B  # noqa: E402
import hitreg_rays as H  # noqa: E402

MIRRORED = {'t72a', 't72b'}


def solid_planes(pts):
    return np.array([np.r_[n, d] for n, d, _ in B.hull_faces(pts)])


def inside(planes, q, tol=0.5):
    return (q @ planes[:, :3].T - planes[:, 3]).max() <= tol


def categorize(vid, solids):
    bones = A.load_model(vid)
    frame, running = A.classify_bones(bones)
    planes = collections.defaultdict(list)
    for f, pts in solids:
        planes[f].append(solid_planes(pts))
    tris, cats, frames = [], [], []
    for name, b in bones.items():
        pm = b.get('poly_mesh')
        if not pm or name.lower().startswith(('brokentrack', 'crudetrack')):
            continue
        ch = A.chain(bones, name)
        fr = 'barrel' if ('barell' in ch or 'barrel' in ch) else 'turret' if 'turret' in ch else 'hull'
        P = np.array(pm['positions'], float)
        for poly in pm['polys']:
            q = P[[v[0] for v in poly]]
            if running.get(name):
                cat = 'track'
            elif any(A.ERA.search(x) for x in ch):
                cat = 'era'
            elif frame.get(name) is not None and planes.get(frame[name]) and \
                    all(any(inside(pl, p[None]) for pl in planes[frame[name]]) for p in q):
                cat = 'core'
            elif fr == 'barrel':
                cat = 'barrel'
            else:
                cat = 'fitting'
            for i in range(1, len(q) - 1):
                tris.append((q[0], q[i], q[i + 1]))
                cats.append(cat)
                frames.append(fr)
    return np.array(tris), np.array(cats), np.array(frames)


def armor_triangles(path):
    g = json.load(open(path))['minecraft:geometry'][0]
    out = []
    for b in g['bones']:
        pm = b.get('poly_mesh')
        if not pm:
            continue
        P = np.array(pm['positions'], float)
        for poly in pm['polys']:
            q = P[[v[0] for v in poly]]
            for i in range(1, len(q) - 1):
                out.append((q[0], q[i], q[i + 1]))
    return np.array(out)


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument('vid')
    ap.add_argument('out')
    ap.add_argument('--mesh')
    ap.add_argument('--step', type=float, default=3.0)
    ap.add_argument('--az', type=float, default=30.0)
    ap.add_argument('--el', default='0,20,45,-10')
    a = ap.parse_args(argv)
    _, info = A.build(a.vid, verbose=False)
    T, cats, frames = categorize(a.vid, info['solids'])
    core = cats == 'core'
    armor = armor_triangles(a.mesh or os.path.join(B.OUT, f'{a.vid}.geo.json'))
    sx = 1.0 if a.vid in MIRRORED else -1.0
    allpts = T.reshape(-1, 3)
    centre = (allpts.min(0) + allpts.max(0)) / 2
    radius = np.linalg.norm(allpts.max(0) - allpts.min(0)) / 2 + 4
    n = 0
    phantom = collections.Counter()
    counts = collections.Counter()
    with open(a.out, 'w') as f:
        for el in [float(x) for x in a.el.split(',')]:
            for az in np.arange(0, 360, a.az):
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
                model_hit = np.zeros(len(origins), bool)
                for ks, idx, t in H.cast_dir(T, origins, d, u, v):
                    tmin = t.min(1)
                    arg = t.argmin(1)
                    tcore = np.where(core[idx][None], t, np.inf).min(1)
                    for j, k in enumerate(ks):
                        if not np.isfinite(tmin[j]):
                            continue
                        model_hit[k] = True
                        ti = idx[arg[j]]
                        hit = origins[k] + d * tmin[j]
                        O = origins[k]
                        rec = {'az': float(az), 'el': el, 'cat': str(cats[ti]), 'frame': str(frames[ti]),
                               'o': [round(sx * O[0] / 16, 5), round(O[1] / 16, 5), round(O[2] / 16, 5)],
                               'd': [round(sx * d[0], 6), round(d[1], 6), round(d[2], 6)],
                               'hit': [round(sx * hit[0] / 16, 5), round(hit[1] / 16, 5), round(hit[2] / 16, 5)],
                               't': round(float(tmin[j]) / 16, 5),
                               'core_t': round(float(tcore[j]) / 16, 5) if np.isfinite(tcore[j]) else -1,
                               'solid_t': -1}
                        f.write(json.dumps(rec) + '\n')
                        counts[str(cats[ti])] += 1
                        n += 1
                # phantom armor: rays that miss the whole model but enter the armor mesh
                miss = np.where(~model_hit)[0]
                if len(miss):
                    for ks, idx, t in H.cast_dir(armor, origins[miss], d, u, v):
                        tmin = t.min(1)
                        phantom[f'az{int(az)}_el{int(el)}'] += int(np.isfinite(tmin).sum())
    total_phantom = sum(phantom.values())
    print(json.dumps({'vid': a.vid, 'rays': n, 'first_contact': dict(counts), 'phantom_rays': total_phantom,
                      'phantom_worst': phantom.most_common(3), 'step_px': a.step}))


if __name__ == '__main__':
    main(sys.argv[1:])
