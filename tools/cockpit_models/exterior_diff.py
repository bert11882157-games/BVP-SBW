#!/usr/bin/env python3
"""Exterior-diff gate: faces of the untouched model that a cockpit build removed although they are the outside skin.

A face of the original model is exterior when rays leaving its centre round its outward normal (the normal the model
stores for it, and eight directions 45 degrees off it) escape the original model: at least two of the nine meet none
of the model's other faces within 30 m (a wing or a pylon may stand in front of one of them). Every such
face missing from the current geo is reported (bert's rule: the exterior never changes outside the canopy area).

Faces inside the new canopy (a fighter's old canopy frames and glass, removed on purpose) are exempt: a removed face
whose centre is within --exempt metres of the new canopy glass, or under it, counts as the canopy area.

Usage: exterior_diff.py VID ORIG.geo.json [--write LIST.json] [--exempt 0.06]
       (LIST.json is restore_exterior.py's restore list: the faces with their own positions, normals and uvs)
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L      # noqa: E402
import view_audit as VA      # noqa: E402


def arg(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


def faces(bones):
    out = {}
    for b in bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or b['name'].endswith('__cockpit') or b['name'].endswith('__canopy'):
            continue
        P = np.array(pm['positions'], float)
        N = np.array(pm.get('normals') or [[0, 1, 0]], float)
        U = np.array(pm['uvs'], float)
        for poly in pm['polys']:
            if not poly or len(poly) < 3:
                continue
            key = tuple(sorted(tuple(np.round(P[v[0]], 2)) for v in poly))
            out[key] = (b['name'], P[[v[0] for v in poly]], N[[min(v[1], len(N) - 1) for v in poly]],
                        U[[v[2] for v in poly]])
    return out


def escapes(T, p, d, reach=30.0):
    e1, e2 = T[:, 1] - T[:, 0], T[:, 2] - T[:, 0]
    h = np.cross(d, e2); a = (e1 * h).sum(1); ok = np.abs(a) > 1e-12
    f = np.where(ok, 1 / np.where(ok, a, 1), 0); s = p - T[:, 0]; u = f * (s * h).sum(1)
    q = np.cross(s, e1); v = f * (q @ d); t = f * (q * e2).sum(1)
    return not bool((ok & (u >= 0) & (u <= 1) & (v >= 0) & (u + v <= 1) & (t > 1e-3) & (t < reach)).any())


def main():
    vid, orig = sys.argv[1], sys.argv[2]
    exempt = float(arg('--exempt', 0.06))
    ac = L.Aircraft(vid)
    og = json.load(open(orig, encoding='utf-8-sig'))['minecraft:geometry'][0]['bones']
    cur, org = faces(ac.bones), faces(og)
    gone = [(k, v) for k, v in org.items() if k not in cur]
    T = []
    for name, P, N, U in org.values():
        V = P * np.array([1, 1, -1]) / 16.0
        for k in range(1, len(V) - 1):
            T.append(V[[0, k, k + 1]])
    T = np.array(T)
    G = np.array((ac.asset.get('CanopyGlass') or {}).get('Triangles', []), float).reshape(-1, 3, 3)
    Gp = np.concatenate([G.reshape(-1, 3), G.mean(1)]) if len(G) else np.zeros((0, 3))
    ext = []
    for k, (name, P, N, U) in gone:
        V = P * np.array([1, 1, -1]) / 16.0
        c = V.mean(0)
        n = np.cross(V[1] - V[0], V[2] - V[0]); ln = np.linalg.norm(n)
        if ln < 1e-12:
            continue
        n /= ln
        ns = N.mean(0) * np.array([1, 1, -1])
        if ns @ n < 0:
            n = -n
        # (a fighter's canopy area: old frames and glass removed on purpose)
        if len(Gp) and np.min(np.linalg.norm(Gp - c, axis=1)) < exempt:
            continue
        a = np.cross(n, [0.0, 1.0, 0.0] if abs(n[1]) < 0.9 else [1.0, 0.0, 0.0]); a /= np.linalg.norm(a)
        b = np.cross(n, a)
        # (either side: the model draws both, and its stored normals do not always face out)
        for m in (n, -n):
            dirs = [m] + [(m + np.cos(t) * a + np.sin(t) * b) / np.sqrt(2) for t in np.radians(np.arange(0, 360, 45))]
            if sum(escapes(T, c + m * 0.01, d) for d in dirs) >= 2:
                ext.append((name, P, N, U, c))
                break
    print(f'{vid}: {len(gone)} model faces removed, {len(ext)} of them exterior skin')
    from collections import Counter
    E = np.array(ac.eye(0), float)
    cnt = Counter(tuple(np.round(c - E, 1).tolist()) for *_, c in ext)
    for k, v in sorted(cnt.items(), key=lambda kv: -kv[1])[:10]:
        print(f'   at {list(k)} (from the eye): {v}')
    out = arg('--write')
    if out:
        json.dump([{'bone': name, 'P': P.tolist(), 'N': N.tolist(), 'U': U.tolist()} for name, P, N, U, _ in ext],
                  open(out, 'w'))
        print('wrote', out)


if __name__ == '__main__':
    main()
