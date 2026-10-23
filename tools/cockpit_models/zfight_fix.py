#!/usr/bin/env python3
"""Clears z-fighting in an aircraft's hand-built cockpit and canopy (the __cockpit / __canopy bones), the last geometry
step of a build (bert, 2026-10-23: z-fighting still shows in game).

The vehicle model is drawn without back-face culling, and seen from outside at 10-20 m the depth buffer only
resolves about half a millimetre, so two faces of different colours closer than 2 mm in one plane flicker even when
the seat view is clean. For each such overlapping pair (zfight_check.py's test, faces back to back included):

  * back to back: the smaller face goes (it is the hidden side of a thin part; the larger face shows from both
    sides anyway);
  * face to face: the smaller face is lifted 3 mm off the larger along its normal, on its own copies of its corners
    (its neighbours stay where they are).

Repeated until the bones are clean (or MAX_PASSES). Faces of the original model are never moved.

Usage: zfight_fix.py VID [--gap 0.002] [--dry-run]
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402
from zfight_check import overlap2d  # noqa: E402

LIFT = 0.003
ANGLE = 2.5
MAX_PASSES = 4


def arg(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


def faces(ac):
    """every polygon of every bone: (bone index, poly index, corners in metres, uv corners, own)"""
    out = []
    for bi, b in enumerate(ac.bones):
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        own = b['name'].endswith('__cockpit') or b['name'].endswith('__canopy')
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        U = np.array(pm['uvs'], float)
        for pi, poly in enumerate(pm['polys']):
            if poly and len(poly) >= 3:
                out.append((bi, pi, P[[v[0] for v in poly]], U[[v[2] for v in poly]], own))
    return out


def plane(V):
    n = np.zeros(3)
    for i in range(len(V)):                                   # Newell normal
        a, b = V[i], V[(i + 1) % len(V)]
        n += np.cross(a, b)
    ln = np.linalg.norm(n)
    return (n / ln, ln / 2) if ln > 1e-12 else (None, 0.0)


def one_colour(U):
    return np.ptp(U, 0).max() < 0.004


def pass_once(ac, eye, gap, reach):
    F = [f for f in faces(ac) if np.abs(f[2].mean(0) - eye).max() < reach]
    info = [plane(f[2]) for f in F]
    lo = np.array([f[2].min(0) for f in F]); hi = np.array([f[2].max(0) for f in F])
    drop, lift = set(), {}
    for i, (bi, pi, V, U, own) in enumerate(F):
        if not own or info[i][0] is None or (bi, pi) in drop:
            continue
        n, area = info[i]
        cand = np.where(np.all(lo <= V.max(0) + gap, axis=1) & np.all(hi >= V.min(0) - gap, axis=1))[0]
        for j in cand:
            if j == i or info[j][0] is None:
                continue
            bj, pj, W, UW, own_j = F[j]
            if (bj, pj) in drop:
                continue
            if one_colour(U) and one_colour(UW) and np.abs(U.mean(0) - UW.mean(0)).max() < 0.004:
                continue                                          # one colour: nothing to see
            nj, area_j = info[j]
            c = n @ nj
            if abs(c) < math.cos(math.radians(ANGLE)):
                continue
            if abs((W.mean(0) - V.mean(0)) @ n) > gap:
                continue
            ax = np.argsort(np.abs(n))[:2]
            ti = [V[[0, k, k + 1]] for k in range(1, len(V) - 1)]
            tj = [W[[0, k, k + 1]] for k in range(1, len(W) - 1)]
            if not any(overlap2d(a[:, ax], b[:, ax]) for a in ti for b in tj):
                continue
            # the one to change: the smaller of the two, never a face of the original model
            mine_small = area <= area_j or not own_j
            k_bone, k_poly, k_n = (bi, pi, n) if mine_small else (bj, pj, nj)
            if c < 0:
                drop.add((k_bone, k_poly))
            else:
                other = W if mine_small else V
                side = 1.0 if ((V if mine_small else W).mean(0) - other.mean(0)) @ k_n >= 0 else -1.0
                lift[(k_bone, k_poly)] = k_n * side * LIFT
            if (bi, pi) in drop:
                break
    return drop, lift


def apply(ac, drop, lift):
    for bi, b in enumerate(ac.bones):
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        polys = []
        for pi, poly in enumerate(pm['polys']):
            if (bi, pi) in drop:
                continue
            d = lift.get((bi, pi))
            if d is not None:
                new = []
                for v in poly:                                  # its own copies of its corners, moved
                    p = np.array(pm['positions'][v[0]], float) + d * np.array([16, 16, -16])
                    pm['positions'].append([round(float(x), 5) for x in p])
                    new.append([len(pm['positions']) - 1] + list(v[1:]))
                poly = new
            polys.append(poly)
        pm['polys'] = polys


def main():
    vid = sys.argv[1]
    gap, dry = float(arg('--gap', 0.002)), '--dry-run' in sys.argv
    ac = L.Aircraft(vid)
    eye = np.array(ac.eye(0), float)
    total_d = total_l = 0
    for k in range(MAX_PASSES):
        drop, lift = pass_once(ac, eye, gap, 4.0)
        if not drop and not lift:
            break
        total_d += len(drop); total_l += len(lift)
        apply(ac, drop, lift)
    print(f'{vid}: z-fight fix: {total_d} hidden back faces removed, {total_l} faces lifted {LIFT * 1000:.0f} mm'
          f' ({k + 1} pass{"es" if k else ""})')
    if not dry:
        with open(ac.geo_path, 'w') as fh:
            json.dump(ac.geo, fh, separators=(',', ':'))


if __name__ == '__main__':
    main()
