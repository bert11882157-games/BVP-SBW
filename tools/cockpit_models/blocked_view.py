#!/usr/bin/env python3
"""Eye-ray blocked-view check: how much of the pilot's view the solid geometry (frames, panel, coaming, skin; not the
canopy glass) blocks within a reach of the eye.

Rays leave the seat's eye over a grid of directions, one degree apart; a ray is blocked when it meets any opaque
triangle of the model (every bone, the cockpit included) closer than REACH. Reported for the whole forward view
(azimuth -90..90, elevation -30..+60) and for the forward-low band over the nose where windscreen fills and frames
sit (azimuth -60..60, elevation -25..+5); --map writes a picture of the grid (blocked dark).

Usage: blocked_view.py VID [--seat N] [--reach 3.0] [--map OUT.png]
"""
import os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402

FORWARD = ((-90, 90), (-30, 60))
LOW = ((-60, 60), (-25, 5))


def arg(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


def triangles(ac, eye, reach):
    T = []
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        for poly in pm['polys']:
            V = P[[v[0] for v in poly]]
            for k in range(1, len(V) - 1):
                T.append(V[[0, k, k + 1]])
    T = np.array(T)
    near = np.linalg.norm(T.mean(1) - eye, axis=1) < reach + 2.0
    return T[near]


def blocked(eye, dirs, T, reach):
    """True per direction when an opaque triangle lies within reach along it (Moller-Trumbore, all triangles at once)"""
    e1, e2 = T[:, 1] - T[:, 0], T[:, 2] - T[:, 0]
    s = eye - T[:, 0]
    q = np.cross(s, e1)
    out = np.zeros(len(dirs), bool)
    for i, d in enumerate(dirs):
        h = np.cross(d, e2)
        a = (e1 * h).sum(1)
        ok = np.abs(a) > 1e-12
        f = np.where(ok, 1 / np.where(ok, a, 1), 0)
        u = f * (s * h).sum(1)
        v = f * (q @ d)
        t = f * (q * e2).sum(1)
        out[i] = bool((ok & (u >= 0) & (u <= 1) & (v >= 0) & (u + v <= 1) & (t > 0.02) & (t < reach)).any())
    return out


def main():
    vid = sys.argv[1]
    seat, reach = int(arg('--seat', 0)), float(arg('--reach', 3.0))
    ac = L.Aircraft(vid)
    eye = np.array(ac.eye(seat), float)
    T = triangles(ac, eye, reach)
    az = np.arange(FORWARD[0][0], FORWARD[0][1] + 1)
    el = np.arange(FORWARD[1][0], FORWARD[1][1] + 1)
    A, Ev = np.meshgrid(np.radians(az), np.radians(el))
    # forward is +z, left is +x (the model's frame), up is +y
    dirs = np.stack([np.sin(A) * np.cos(Ev), np.sin(Ev), np.cos(A) * np.cos(Ev)], -1).reshape(-1, 3)
    hit = blocked(eye, dirs, T, reach).reshape(len(el), len(az))
    w = np.cos(np.radians(el))[:, None] * np.ones_like(hit, float)     # solid-angle weight per cell
    def share(box):
        (a0, a1), (e0, e1) = box
        m = (az[None, :] >= a0) & (az[None, :] <= a1) & (el[:, None] >= e0) & (el[:, None] <= e1)
        return float((hit * w * m).sum() / (w * m).sum())
    print(f'{vid} seat {seat}: forward view {100 * share(FORWARD):.1f}% blocked, forward-low band '
          f'{100 * share(LOW):.1f}% blocked (within {reach} m)')
    out = arg('--map')
    if out:
        from PIL import Image
        img = np.where(hit[::-1, ::-1], 40, 220).astype(np.uint8)     # up at the top, left on the left
        Image.fromarray(img).resize((len(az) * 4, len(el) * 4), Image.NEAREST).save(out)


if __name__ == '__main__':
    main()
