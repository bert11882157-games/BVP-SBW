#!/usr/bin/env python3
"""Checks that the canopy panes which are flat on the real aircraft are flat in ours: every vertex of such a pane within
--tol (default 2 mm) of the pane's best-fit plane.

The flat panes of each aircraft are listed in flat_panes/<vid>.json beside this script:
  {"why": "<the reference the layout comes from>",
   "panes": [{"name": "centre windscreen", "box": [[x0, y0, z0], [x1, y1, z1]], "mirror": true}]}
A pane is the CanopyGlass triangles whose centroid falls inside its box (vehicle-local blocks, the frame CanopyGlass is in);
with "mirror" the box is read as |x| and the right and left panes are checked each on their own. An aircraft with no
flat panes (the F-16's one-piece bubble) has "panes": [] so it counts as audited.

--pieces lists the glass's larger pieces (split at creases over 10 degrees) with their size and flatness, which is how
the boxes are found.

Usage: planarity_check.py VID [VID...] [--tol 0.002] [--pieces]
Exit status 1 if any listed pane fails, or a listed pane selects no glass, or an aircraft is not listed.
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402

CONFIG = os.path.join(HERE, 'flat_panes')


def load_cfg(vids):
    cfg = {}
    for v in vids:
        p = os.path.join(CONFIG, v + '.json')
        if os.path.exists(p):
            cfg[v] = json.load(open(p, encoding='utf-8'))['panes']
    return cfg


def arg(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


def unique_tris(G):
    """the glass is written with both windings: keep one copy of each triangle"""
    seen, out = set(), []
    for t in G:
        k = tuple(sorted(tuple(np.round(p, 4)) for p in t))
        if k in seen:
            continue
        seen.add(k)
        out.append(t)
    return np.array(out).reshape(-1, 3, 3)


def deviation(P):
    """max distance (blocks) of the points P from their best-fit plane, and the plane's normal"""
    c = P.mean(0)
    _, s, vt = np.linalg.svd(P - c)
    n = vt[-1]
    return float(np.abs((P - c) @ n).max()), n


def pieces(T, crease=10.0):
    """the glass split into pieces: triangles joined across a shared edge whose normals differ by less than [crease]"""
    n = np.cross(T[:, 1] - T[:, 0], T[:, 2] - T[:, 0])
    n /= np.maximum(np.linalg.norm(n, axis=1), 1e-12)[:, None]
    parent = list(range(len(T)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    edges = {}
    for i, t in enumerate(T):
        ks = [tuple(np.round(p, 4)) for p in t]
        for a, b in ((0, 1), (1, 2), (2, 0)):
            edges.setdefault(tuple(sorted((ks[a], ks[b]))), []).append(i)
    c = np.cos(np.radians(crease))
    for tris in edges.values():
        for a in tris:
            for b in tris:
                if a < b and abs(n[a] @ n[b]) >= c:
                    parent[find(a)] = find(b)
    groups = {}
    for i in range(len(T)):
        groups.setdefault(find(i), []).append(i)
    return sorted(groups.values(), key=lambda g: -len(g))


def check(vid, cfg, tol, show_pieces):
    ac = L.Aircraft(vid)
    T = unique_tris(ac.glass)
    C = T.mean(1)
    bad = 0
    if vid not in cfg:
        print(f'{vid}: NOT AUDITED (no flat_panes/{vid}.json)')
        bad += 1
    for p in cfg.get(vid, []):
        lo, hi = np.array(p['box'][0], float), np.array(p['box'][1], float)
        sides = (1, -1) if p.get('mirror') else (0,)
        for s in sides:
            Q = C.copy()
            if s:
                Q[:, 0] *= s
            sel = np.all((Q >= lo) & (Q <= hi), axis=1)
            name = p['name'] + ({1: ' (right)', -1: ' (left)'}.get(s, ''))
            if not sel.any():
                print(f'{vid}: FAIL {name}: no glass in its box')
                bad += 1
                continue
            d, nrm = deviation(T[sel].reshape(-1, 3))
            ok = d <= tol
            bad += not ok
            print(f'{vid}: {"ok  " if ok else "FAIL"} {name}: {sel.sum()} triangles, {d * 1000:.2f} mm from flat'
                  f' (normal {np.round(nrm, 3).tolist()})')
    if vid in cfg and not cfg[vid]:
        print(f'{vid}: no flat panes on the real aircraft (audited)')
    if show_pieces:
        for g in [g for g in pieces(T) if len(g) >= 6][:16]:
            P = T[g].reshape(-1, 3)
            d, _ = deviation(P)
            print(f'   piece {len(g):4d} tris  x {P[:, 0].min():+.3f}..{P[:, 0].max():+.3f}  y {P[:, 1].min():.3f}..'
                  f'{P[:, 1].max():.3f}  z {P[:, 2].min():.3f}..{P[:, 2].max():.3f}  {d * 1000:7.2f} mm from flat')
    return bad


def main():
    tol = float(arg('--tol', '0.002'))
    vids = [a for a in sys.argv[1:] if not a.startswith('--') and a != arg('--tol')]
    cfg = load_cfg(vids)
    bad = sum(check(v, cfg, tol, '--pieces' in sys.argv) for v in vids)
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
