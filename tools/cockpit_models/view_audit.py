#!/usr/bin/env python3
"""What each crew eye sees, ray by ray, for the cockpit cleanup (bert's playtest, 2026-10-23):

  * leaks: rays that meet neither the model, the cockpit nor the canopy glass within 8 m: a gap between the cockpit
    tub or sills and the fuselage skin (or a hole in either) that shows the outside or the inside of the airframe;
  * leftovers: rays whose first hit, closer than the glass and within 1.3 m of the eye, is a face of the original
    model with canopy glass straight above it (so inside the canopy, not the skin outside or under the sill), other
    than the model's own animated parts (stick, throttle, gear): old model geometry still showing in the cockpit.

Rays leave the eye one degree apart over every direction below 10 degrees of elevation and the forward half above
it. Faces of the model the cockpit scripts keep on purpose (the skin round the cockpit, already repainted or lowered)
are told apart by colour only where --keep-uv gives their swatches; otherwise every model face near the eye counts,
so the report lists where they are for a look in the seat views. --map writes the classified view (grey cockpit,
blue glass, red leftover, magenta leak, white beyond).

Usage: view_audit.py VID [--seat N] [--map OUT.png]
"""
import os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402

LEAK = 8.0
NEAR = 1.3
SKIP = ('propeller', 'contraPropeller', 'main_rotor', 'tail_rotor', 'tailPropeller', 'front_rotor', 'rear_rotor')


def arg(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


MOVING = ('control_stick', 'throttle', 'landing_gear', 'gear_')


def load(ac, eye):
    T, own = [], []
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or b['name'] in SKIP:
            continue
        mine = b['name'].endswith('__cockpit') or b['name'].endswith('__canopy') or \
            any(k in b['name'] for k in MOVING)
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        for poly in pm['polys']:
            V = P[[v[0] for v in poly]]
            for k in range(1, len(V) - 1):
                T.append(V[[0, k, k + 1]]); own.append(mine)
    T, own = np.array(T), np.array(own)
    near = np.linalg.norm(T.mean(1) - eye, axis=1) < LEAK + 3
    G = np.array((ac.asset.get('CanopyGlass') or {}).get('Triangles', []), float).reshape(-1, 3, 3)
    return T[near], own[near], G


def first_hits(eye, dirs, T):
    """distance to the first triangle along each direction (inf if none), and its index"""
    if not len(T):
        return np.full(len(dirs), np.inf), np.full(len(dirs), -1)
    e1, e2 = T[:, 1] - T[:, 0], T[:, 2] - T[:, 0]
    s = eye - T[:, 0]
    q = np.cross(s, e1)
    dist, idx = np.full(len(dirs), np.inf), np.full(len(dirs), -1)
    for i, d in enumerate(dirs):
        h = np.cross(d, e2)
        a = (e1 * h).sum(1)
        ok = np.abs(a) > 1e-12
        f = np.where(ok, 1 / np.where(ok, a, 1), 0)
        u = f * (s * h).sum(1)
        v = f * (q @ d)
        t = f * (q * e2).sum(1)
        hit = ok & (u >= 0) & (u <= 1) & (v >= 0) & (u + v <= 1) & (t > 0.02)
        if hit.any():
            j = np.where(hit)[0][np.argmin(t[hit])]
            dist[i], idx[i] = t[j], j
    return dist, idx


def audit(vid, seat=0, map_out=None):
    ac = L.Aircraft(vid)
    eye = np.array(ac.eye(seat), float)
    T, own, G = load(ac, eye)
    az = np.arange(-180, 180)
    el = np.arange(-89, 90)
    A, Ev = np.meshgrid(np.radians(az), np.radians(el))
    keep = (Ev < np.radians(10)) | (np.abs(A) <= np.radians(90))
    dirs = np.stack([np.sin(A) * np.cos(Ev), np.sin(Ev), np.cos(A) * np.cos(Ev)], -1)[keep]
    td, ti = first_hits(eye, dirs, T)
    gd, _ = first_hits(eye, dirs, G)
    leak = np.isinf(td) & np.isinf(gd) | (td > LEAK) & (gd > LEAK)
    left = (ti >= 0) & ~own[np.maximum(ti, 0)] & (td < NEAR) & (td < gd)
    if left.any():                       # only what has the canopy glass straight above it
        idx = np.where(left)[0]
        P = eye + dirs[idx] * (td[idx, None] - 0.005)
        up = np.array([0.0, 1.0, 0.0])
        under = np.array([np.isfinite(first_hits(p, up[None], G)[0][0]) for p in P]) if len(G) else np.zeros(len(P), bool)
        left[idx[~under]] = False
    pts = lambda m: eye + dirs[m] * np.where(np.isfinite(td[m]), np.minimum(td[m], 1.0), 1.0)[:, None] - eye
    def clusters(P):
        seen = {}
        for p in np.round(P, 1):
            k = tuple(p.tolist())
            seen[k] = seen.get(k, 0) + 1
        return sorted(seen.items(), key=lambda kv: -kv[1])[:6]
    res = dict(vid=vid, seat=seat, rays=int(len(dirs)), leaks=int(leak.sum()), leftovers=int(left.sum()),
               leak_at=clusters(pts(leak)), left_at=clusters(pts(left)))
    if map_out:
        from PIL import Image
        cls = np.zeros(keep.shape, np.uint8)            # 0 not cast
        c = np.full(len(dirs), 4, np.uint8)             # 4 beyond
        c[np.isfinite(gd) & (gd < td)] = 2              # glass first
        c[(ti >= 0) & own[np.maximum(ti, 0)] & (td <= gd)] = 1
        c[left] = 3
        c[leak] = 5
        cls[keep] = c
        pal = np.array([[0, 0, 0], [120, 120, 120], [90, 140, 220], [230, 40, 40], [245, 245, 245], [230, 0, 230]],
                       np.uint8)
        img = pal[cls[::-1, ::-1]]
        Image.fromarray(img).resize((img.shape[1] * 3, img.shape[0] * 3), Image.NEAREST).save(map_out)
    return res


def main():
    vid = sys.argv[1]
    r = audit(vid, int(arg('--seat', 0)), arg('--map'))
    print(f"{vid} seat {r['seat']}: {r['leaks']} leak rays, {r['leftovers']} leftover-model rays (of {r['rays']})")
    for name, key in (('leaks', 'leak_at'), ('leftovers', 'left_at')):
        if r[key]:
            print(f'   {name} at (from the eye, m): ' + ', '.join(f'{list(k)} x{n}' for k, n in r[key]))


if __name__ == '__main__':
    main()
