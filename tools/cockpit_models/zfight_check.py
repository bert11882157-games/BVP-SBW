#!/usr/bin/env python3
"""Finds z-fighting in an aircraft's cockpit and canopy: pairs of faces (cockpit / canopy bones against each other
and against the model's own faces round the cockpit) that lie in nearly the same plane (normals within 2.5 degrees,
planes within GAP) and overlap. Prints each clash's position from the eye, so it can be fixed in the aircraft's file.

Faces back to back are checked as well as face to face: the vehicle model is drawn without back-face culling.

--strict (bert, 2026-10-23: z-fighting still shows in game): what flickers at the distances the game is seen from
(third person, 10-20 m) rather than from the seat: planes within 4 mm, normals within 6 degrees, everything within
4 m of the eye (the whole canopy and the skin round it).

Usage: zfight_check.py VID [VID...] [--gap 0.0015] [--angle 2.5] [--reach 2.5] [--strict]
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402


def arg(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


STRICT = '--strict' in sys.argv
ANGLE = float(arg('--angle', 6.0 if STRICT else 2.5))     # normals within this many degrees count as one plane
REACH = float(arg('--reach', 4.0 if STRICT else 2.5))     # faces this far from the eye are checked


def tris_of(ac, E, mine, uvs_out):
    out = []
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        own = b['name'].endswith('__cockpit') or b['name'].endswith('__canopy')
        if own != mine:
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0 - E
        U = np.array(pm['uvs'], float)
        for poly in pm['polys']:
            if poly:
                V = P[[v[0] for v in poly]]
                if np.abs(V.mean(0)).max() > REACH:
                    continue
                uv = U[[v[2] for v in poly]]
                for k in range(1, len(V) - 1):
                    out.append(V[[0, k, k + 1]])
                    uvs_out.append(uv[[0, k, k + 1]])
    return np.array(out).reshape(-1, 3, 3)


def overlap2d(a, b):
    """separating-axis test of two triangles in 2-D (shrunk a little so touching edges do not count)"""
    def shrink(t):
        c = t.mean(0)
        return c + (t - c) * 0.96
    a, b = shrink(a), shrink(b)
    for t in (a, b):
        for i in range(3):
            e = t[(i + 1) % 3] - t[i]
            n = np.array([-e[1], e[0]])
            pa, pb = a @ n, b @ n
            if pa.max() <= pb.min() or pb.max() <= pa.min():
                return False
    return True


def glass_clashes(ac, E, B, gap):
    """faces (B, eye-relative) lying within [gap] of the canopy glass and overlapping it"""
    G = np.array((ac.asset.get('CanopyGlass') or {}).get('Triangles', []), float).reshape(-1, 3, 3)
    if not len(G):
        return []
    G = G - E
    n = np.cross(G[:, 1] - G[:, 0], G[:, 2] - G[:, 0])
    ln = np.linalg.norm(n, axis=1)
    ok = ln > 1e-10
    G, n = G[ok], n[ok] / ln[ok, None]
    nb = np.cross(B[:, 1] - B[:, 0], B[:, 2] - B[:, 0])
    lb = np.linalg.norm(nb, axis=1)
    okb = lb > 1e-10
    nb[okb] /= lb[okb, None]
    lo_b, hi_b = B.min(1), B.max(1)
    out = []
    for i in range(len(G)):
        lo, hi = G[i].min(0) - gap, G[i].max(0) + gap
        cand = np.where(okb & np.all(lo_b <= hi, axis=1) & np.all(hi_b >= lo, axis=1))[0]
        for j in cand:
            if abs(n[i] @ nb[j]) < math.cos(math.radians(8)):
                continue
            if np.abs((B[j] - G[i][0]) @ n[i]).max() > gap:
                continue
            ax = np.argsort(np.abs(n[i]))[:2]
            if overlap2d(G[i][:, ax], B[j][:, ax]):
                out.append((B[j].mean(0), j))
    return out


def check(vid, gap):
    ac = L.Aircraft(vid)
    E = ac.eye(0)
    ua, ub = [], []
    A = tris_of(ac, E, True, ua)
    B = np.concatenate([A, tris_of(ac, E, False, ub)])
    UV = np.array(ua + ub).reshape(-1, 3, 2)
    flat = np.ptp(UV, axis=1).max(1) < 1e-6           # one swatch colour
    key = np.round(UV[:, 0] * 4096).astype(int)
    def planes(T):
        n = np.cross(T[:, 1] - T[:, 0], T[:, 2] - T[:, 0])
        ln = np.linalg.norm(n, axis=1)
        ok = ln > 1e-10
        n[ok] /= ln[ok, None]
        return n, (n * T[:, 0]).sum(1), ok
    na, da, oka = planes(A)
    nb, db, okb = planes(B)
    lo_b, hi_b = B.min(1), B.max(1)
    clashes = []
    for i in range(len(A)):
        if not oka[i]:
            continue
        lo, hi = A[i].min(0), A[i].max(0)
        cand = np.where(okb & np.all(lo_b <= hi + 1e-4, axis=1) & np.all(hi_b >= lo - 1e-4, axis=1))[0]
        cand = cand[cand > i] if True else cand
        for j in cand:
            if j < len(A) and j <= i:
                continue
            if np.ptp(UV[i], 0).max() < 0.004 and np.ptp(UV[j], 0).max() < 0.004 and \
                    np.abs(UV[i].mean(0) - UV[j].mean(0)).max() < 0.004:
                continue                         # both within one swatch, the same colour: no visible fighting
            c = abs(na[i] @ nb[j])
            if c < math.cos(math.radians(ANGLE)):
                continue
            # back to back counts too: the vehicle model is drawn without back-face culling (GeckoLib's default
            # entityCutoutNoCull), so each face also shows from behind and fights the other (bert, 2026-10-23)
            # the planes' distance measured at the triangles themselves (not from the origin)
            if abs((B[j].mean(0) - A[i].mean(0)) @ na[i]) > gap:
                continue
            # project both on the plane's two best axes
            ax = np.argsort(np.abs(na[i]))[:2]
            if overlap2d(A[i][:, ax], B[j][:, ax]):
                clashes.append((A[i].mean(0), j >= len(A), UV[i].mean(0), UV[j].mean(0)))
                if os.environ.get('ZDBG') and len(clashes) < 3:
                    print('A', np.round(A[i], 3).tolist(), np.round(UV[i], 4).tolist()); print('B', np.round(B[j], 3).tolist(), np.round(UV[j], 4).tolist())
    return clashes


def main():
    gap = float(arg('--gap', '0.004' if STRICT else '0.0015'))
    gg = arg('--glass')
    for vid in [a for a in sys.argv[1:] if not a.startswith('--') and a not in (arg('--gap'), gg, arg('--angle'), arg('--reach'))]:
        if gg:
            ac = L.Aircraft(vid)
            E = ac.eye(0)
            na = len(tris_of(ac, E, True, []))
            B = np.concatenate([tris_of(ac, E, True, []), tris_of(ac, E, False, [])])
            gc = glass_clashes(ac, E, B, float(gg))
            own = sum(1 for _, j in gc if j < na)
            print(f'{vid}: {len(gc)} faces under the glass ({own} new frames / cockpit, {len(gc) - own} model)')
            seen = set()
            for c, j in gc:
                k = tuple(np.round(c, 1))
                if k not in seen and len(seen) < 8:
                    seen.add(k)
                    print(f'   {"own  " if j < na else "model"} at {np.round(c, 3).tolist()}')
        cl = check(vid, gap)
        own = sum(1 for c in cl if not c[1]); model = sum(1 for c in cl if c[1])
        print(f'{vid}: {len(cl)} clashes ({own} within the new cockpit / canopy, {model} against the model)')
        seen = set()
        for c, m, u1, u2 in cl:
            k = tuple(np.round(c, 2))
            if k in seen:
                continue
            seen.add(k)
            print(f'   {"model" if m else "own  "} at {np.round(c, 3).tolist()} uv {np.round(u1, 3).tolist()} / {np.round(u2, 3).tolist()}')
            if len(seen) >= 10:
                break


if __name__ == '__main__':
    main()
