#!/usr/bin/env python3
"""Flat glass panes in the window openings the crew sees out of with no glass in them (view_audit.py's leaks).

From each crew eye, rays one degree apart that meet neither the model, the cockpit nor the canopy glass within 8 m are
grouped into connected patches (one patch per open window). For each patch, the points where the rays round its edge
meet the window's frame are the opening's outline: a plane is fitted to them, and if they lie within FLAT_TOL of it
the opening gets one flat pane, the outline's convex hull in that plane (bert's rule: flat panes, the frames shaped
round them). Openings whose edge is not flat enough are listed for a hand-built pane instead.

The panes are added to the vehicle file's CanopyGlass; the geometry is not touched.

Usage: pane_fill.py VID SEATS [--dry-run] [--min 25]    (SEATS: 0,1,...)
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L            # noqa: E402
import view_audit as VA            # noqa: E402

FLAT_TOL = 0.035                  # m: the outline's largest distance from its plane
REACH = 8.0


def arg(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


def grid(eye, T, G):
    az = np.arange(-180, 180)
    el = np.arange(-89, 90)
    A, Ev = np.meshgrid(np.radians(az), np.radians(el))
    D = np.stack([np.sin(A) * np.cos(Ev), np.sin(Ev), np.cos(A) * np.cos(Ev)], -1)
    flat = D.reshape(-1, 3)
    td, _ = VA.first_hits(eye, flat, T)
    gd, _ = VA.first_hits(eye, flat, G) if len(G) else (np.full(len(flat), np.inf), None)
    d = np.minimum(td, gd)
    leak = (d > REACH).reshape(D.shape[:2])
    return D, d.reshape(D.shape[:2]), leak


def patches(leak):
    """connected leak patches on the (elevation, azimuth) grid, azimuth wrapping round"""
    H, W = leak.shape
    lab = -np.ones(leak.shape, int)
    out = []
    for i in range(H):
        for j in range(W):
            if not leak[i, j] or lab[i, j] >= 0:
                continue
            k = len(out); stack = [(i, j)]; lab[i, j] = k; cells = []
            while stack:
                a, b = stack.pop(); cells.append((a, b))
                for da in (-1, 0, 1):
                    for db in (-1, 0, 1):
                        x, y = a + da, (b + db) % W
                        if 0 <= x < H and leak[x, y] and lab[x, y] < 0:
                            lab[x, y] = k; stack.append((x, y))
            out.append(cells)
    return out, lab


def hull2d(P):
    P = sorted(map(tuple, P))
    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lo, hi = [], []
    for p in P:
        while len(lo) >= 2 and cross(lo[-2], lo[-1], p) <= 0:
            lo.pop()
        lo.append(p)
    for p in reversed(P):
        while len(hi) >= 2 and cross(hi[-2], hi[-1], p) <= 0:
            hi.pop()
        hi.append(p)
    return np.array(lo[:-1] + hi[:-1])


def panes_for(eye, D, d, leak, minimum):
    H, W = leak.shape
    groups, lab = patches(leak)
    made, skipped = [], []
    for k, cells in enumerate(groups):
        if len(cells) < minimum:
            continue
        edge = set()
        for a, b in cells:
            for da in (-1, 0, 1):
                for db in (-1, 0, 1):
                    x, y = a + da, (b + db) % W
                    if 0 <= x < H and not leak[x, y] and d[x, y] < REACH:
                        edge.add((x, y))
        if len(edge) < 6:
            continue
        # the frame round the opening: edge hits near the eye (beyond it is what shows past the window's edge: a
        # wing, the tail), the worst-fitting 15 % trimmed twice (a post or a box poking into the edge)
        P = np.array([eye + D[x, y] * d[x, y] for x, y in edge if d[x, y] < 2.5])
        if len(P) < 6:
            continue
        for it in range(3):
            c = P.mean(0)
            _, s, vt = np.linalg.svd(P - c)
            n = vt[2]
            r = np.abs((P - c) @ n)
            if it < 2 and len(P) > 12:
                P = P[r <= np.quantile(r, 0.85)]
        dev = r.max()
        el = np.degrees(np.arcsin(D[cells[0]][1]))
        info = dict(rays=len(cells), at=np.round(c - eye, 2).tolist(), flat_dev=round(float(dev), 3))
        if dev > FLAT_TOL:
            skipped.append(info)
            continue
        u, v = vt[0], vt[1]
        Q = np.c_[(P - c) @ u, (P - c) @ v]
        hull = hull2d(Q)
        poly = [c + p[0] * u + p[1] * v for p in hull]
        tris = [np.array([poly[0], poly[i], poly[i + 1]]) for i in range(1, len(poly) - 1)]
        made.append((info, tris))
    return made, skipped


def main():
    vid = sys.argv[1]
    seats = [int(s) for s in sys.argv[2].split(',')]
    minimum = int(arg('--min', 25))
    ac = L.Aircraft(vid)
    added = []
    for s in seats:
        eye = np.array(ac.eye(s), float)
        T, own, G = VA.load(ac, eye)
        if added:                                       # (the panes made from the seats before this one)
            G = np.concatenate([G, np.array(added)]) if len(G) else np.array(added)
        D, d, leak = grid(eye, T, G)
        made, skipped = panes_for(eye, D, d, leak, minimum)
        for info, tris in made:
            print(f'{vid} seat {s}: pane over {info["rays"]} open rays at {info["at"]} (outline within '
                  f'{info["flat_dev"] * 1000:.0f} mm of flat), {len(tris)} triangles')
            added += tris
        for info in skipped:
            print(f'{vid} seat {s}: NOT FLAT ({info["flat_dev"] * 1000:.0f} mm): {info["rays"]} open rays at '
                  f'{info["at"]}: needs a hand-built pane')

    if added and '--dry-run' not in sys.argv:
        cg = ac.asset.setdefault('CanopyGlass', {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': []})
        for t in added:
            cg['Triangles'] += [round(float(x), 4) for x in t.reshape(-1)]
        L.write_asset(ac.asset_path, ac.asset)
        print(f'{vid}: {len(added)} glass triangles added')


if __name__ == '__main__':
    main()
