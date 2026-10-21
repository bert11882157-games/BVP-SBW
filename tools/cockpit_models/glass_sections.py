#!/usr/bin/env python3
"""Prints the old canopy glass's cross-sections (the right half, pilot's right = -x is mirrored to +x) at stations
along the aircraft, for measuring a hand-made canopy: per station z (metres from the eye) the outline as (x, y) points
from the sill up over the top, at 9 even angles around the section's centre. Also the glass's ends and the sill line.

Usage: glass_sections.py VID [--step 0.1] [--asset path]
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402


def arg(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


def section(G, z):
    """points where the plane z cuts the glass triangles"""
    pts = []
    for T in G:
        d = T[:, 2] - z
        if d.max() < 0 or d.min() > 0:
            continue
        for i in range(3):
            a, b = T[i], T[(i + 1) % 3]
            da, db = d[i], d[(i + 1) % 3]
            if da * db < 0:
                pts.append(a + (b - a) * (da / (da - db)))
            elif da == 0:
                pts.append(a)
    return np.array(pts)


def main():
    vid = sys.argv[1]
    step = float(arg('--step', '0.1'))
    ac = L.Aircraft(vid)
    if arg('--asset'):
        cg = json.load(open(arg('--asset'))).get('CanopyGlass')
        ac.glass = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
    E = ac.eye(0)
    G = ac.glass - E
    if not len(G):
        sys.exit('no glass')
    lo, hi = G.reshape(-1, 3).min(0), G.reshape(-1, 3).max(0)
    print(f'{vid}: eye {np.round(E, 4).tolist()}; glass z {lo[2]:+.2f}..{hi[2]:+.2f}, y {lo[1]:+.2f}..{hi[1]:+.2f}, '
          f'|x| <= {max(abs(lo[0]), abs(hi[0])):.2f}')
    z = math.ceil(lo[2] / step) * step
    while z <= hi[2]:
        S = section(G, z)
        if len(S) >= 4:
            S[:, 0] = np.abs(S[:, 0])                # fold the left half onto the right
            ymin, ymax = S[:, 1].min(), S[:, 1].max()
            cy = ymin + 0.35 * (ymax - ymin)
            out = []
            for k in range(9):                        # from the sill (angle -60) up over the top (90)
                a = math.radians(-60 + k * 150 / 8)
                d = np.array([math.cos(a), math.sin(a)])
                rel = S[:, :2] - [0, cy]
                proj = rel @ d
                perp = np.abs(rel @ np.array([-d[1], d[0]]))
                cand = proj[(perp < 0.04) & (proj > 0)]
                if len(cand):
                    r = cand.max()
                    out.append((round(float(d[0] * r), 3), round(float(cy + d[1] * r), 3)))
            print(f'  z {z:+.2f}: x<= {S[:, 0].max():.3f} y {ymin:+.3f}..{ymax:+.3f}  outline {out}')
        z += step


if __name__ == '__main__':
    main()
