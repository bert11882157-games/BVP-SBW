#!/usr/bin/env python3
"""Mi-24A greenhouse glass, fitted by hand to the original model's own frames (bert, 2026-10-24: keep the original
canopy, fit the glass by hand, no generator), as glass_mi_24d.py and glass_mi24v.py do. Only glass is added; this
replaces the pane_fill pass the Mi-24A had before.

The Mi-24A's greenhouse is thick flat bars on the 1/16 grid, the operator in the glazed nose and the pilot behind and
above him on the left of the wide cabin (the flight technician's side on the right). Its frames, read off main
8e04fb62 with the measuring aids in work/handbuilt (frame_ortho.py, window_cut.py, pane_hole.py, snap2d), every
window flat, each corner on its bars' inner edge at their mid-depth:

Operator (eye 0, 2.991, 6.938):
  * the front pane between the two front posts (inner x +-0.248), from the bottom cross bar (y 2.25, z 8.37) up
    under the nose arch's top (y 2.93, z 8.31), leaning back about 5 degrees;
  * each side's front quarter pane, a slanted quad from the front post (x 0.29, z 8.35) back to the next post
    (x 0.55-0.56, z 7.63-7.78), the floor's edge (y 2.19-2.25) up to the side rail (y 2.81-3.05);
  * each side's big window from that post back to the post at z 5.95-6.07 (x 0.97-1.07), the sill (y 2.32-2.57,
    rising and falling with the sill bar) up to the side rail (y 3.05 at the front, 3.62 at the back), leaning out
    about 13 degrees;
  * each side's top pane from the spine (x 0.063) out to the side rail and from the front arch (z 8.30) back to the
    cross bar over his head (z 6.83-7.0), sloping up and back at about 35 degrees;
  * each side's upper pane behind that cross bar, the spine to the side rail, back to the cross bar at z 5.875 over
    the pilot's knees, nearly level.
Pilot (eye 0.5, 3.4, 5.19, on the left):
  * the left side window between the post at z 5.6-5.75 and the cabin's frame at z 4.88, from y 2.57 up to 3.685
    (x 1.11-1.24, nearly upright); the right side's (x 1.06-1.17) runs from z 5.0;
  * each side's small roof window, a trapezoid between the cross bars at z 5.38 and 5.81, x 0.56-0.93 (y 3.87).
The left and right sides are mirror images except the pilot's side windows, which have their own tables.

The cockpit's cut also took original faces that are seen through the glass; the greenhouse's own structure among them
(is_frame) goes back after the glass (run_heli.ps1 calls --restore).

Usage: glass_mi_24a.py [--check | --restore IN.json OUT.json]
"""
import os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L            # noqa: E402

VID = 'mi_24a'

LEFT = {
    'operator front quarter': [(0.288, 2.196, 8.352), (0.553, 2.253, 7.778), (0.561, 2.999, 7.627),
                               (0.529, 3.047, 7.687), (0.295, 2.808, 8.227), (0.270, 2.746, 8.291),
                               (0.275, 2.580, 8.311)],
    'operator side window': [(0.754, 2.318, 7.552), (0.859, 2.377, 7.069), (0.881, 2.441, 6.943),
                             (0.932, 2.503, 6.694), (0.968, 2.441, 6.571), (0.995, 2.457, 6.446),
                             (1.062, 2.501, 6.133), (1.097, 2.570, 5.950), (0.987, 3.620, 5.880),
                             (0.817, 3.379, 6.735), (0.685, 3.124, 7.433), (0.679, 3.052, 7.499),
                             (0.731, 2.378, 7.620)],
    'operator top pane': [(0.063, 3.366, 7.696), (0.063, 3.748, 6.999), (0.132, 3.751, 6.937),
                          (0.326, 3.722, 6.835), (0.508, 3.646, 6.827), (0.626, 3.529, 6.945),
                          (0.617, 3.420, 7.151), (0.561, 3.281, 7.451), (0.496, 3.181, 7.686),
                          (0.254, 2.977, 8.252), (0.220, 2.959, 8.313), (0.062, 3.035, 8.302)],
    'upper pane (facets)': [(0.0625, 3.84, 6.6875), (0.0625, 3.875, 6.0625), (0.1875, 3.906, 5.875),
                            (0.8125, 3.81, 5.875), (0.875, 3.72, 5.969), (0.6875, 3.59, 6.75),
                            (0.5, 3.72, 6.6875), (0.3125, 3.78, 6.6875), (0.125, 3.84, 6.75)],
    'roof window': [(0.564, 3.877, 5.810), (0.686, 3.863, 5.380), (0.934, 3.863, 5.378), (0.876, 3.875, 5.758),
                    (0.812, 3.877, 5.810)],
}
PILOT_SIDES = {
    'pilot left side window': [(1.243, 2.500, 4.878), (1.216, 2.500, 5.810), (1.173, 2.990, 5.720),
                               (1.160, 3.150, 5.650), (1.113, 3.685, 5.590), (1.133, 3.685, 4.877),
                               (1.188, 3.094, 4.878)],
    'right side window': [(-1.171, 2.570, 5.001), (-1.151, 2.568, 5.810), (-1.088, 3.317, 5.650),
                          (-1.057, 3.685, 5.590), (-1.071, 3.686, 5.002)],
}
FRONT = {'operator front pane': [(0.248, 2.250, 8.372), (0.248, 2.810, 8.322), (0.224, 2.880, 8.316),
                                 (0.067, 2.932, 8.312), (-0.067, 2.932, 8.312), (-0.224, 2.880, 8.316),
                                 (-0.248, 2.810, 8.322), (-0.248, 2.250, 8.372)]}


# (the one place the right's frames are not the left's mirror image: the left sill carries a hump at z 6.56-7.06,
# y 2.44-2.50, that the right has not, so the right big window's foot follows the right sill's straight top there)
RIGHT_SILL = {(0.881, 2.441, 6.943): (0.887, 2.390, 6.943), (0.932, 2.503, 6.694): (0.942, 2.421, 6.694)}


def panes():
    out = dict(FRONT)
    for name, P in LEFT.items():
        out['left ' + name] = P
        R = [RIGHT_SILL.get(p, p) for p in P] if name == 'operator side window' else P
        out['right ' + name] = [(-x, y, z) for x, y, z in reversed(R)]     # (the frames are mirror images)
    out.update(PILOT_SIDES)
    return out


PANES = panes()


def is_frame(P):
    """an original face the cockpit's cut took that belongs to the greenhouse's structure, not the old interior, and is
    so goes back (--restore): the cabin's side walls and posts (|x| 0.85 and out), the upper side rails and the roof's bars and panels (y 3.6
    and up). The old seats, consoles, panels and the flight technician's station stay out."""
    c = P.mean(0)
    return bool(abs(c[0]) >= 0.95 or (abs(c[0]) >= 0.85 and c[1] >= 2.8) or (abs(c[0]) >= 0.6 and c[1] >= 3.3 and c[2] >= 5.3)
                or c[1] >= 3.6)   # (the upper side rails' undersides among them; the low bulkhead
    # and shelf of the right-hand station, inboard of the wall, stay out: the new cockpit stands at them)


def plane(P):
    P = np.asarray(P, float); c = P.mean(0)
    _, _, vt = np.linalg.svd(P - c)
    return c, vt[2], float(np.abs((P - c) @ vt[2]).max())


TUCK = 0.012    # (each flat pane's edge runs this far in under its bars, as the D's and V's panes are tucked by hand)


def tucked(P):
    """the flat pane P grown by TUCK all round in its own plane (each corner moved out along its corner's bisector)"""
    P = np.asarray(P, float); c, n, _ = plane(P)
    u = np.cross(n, [0, 1.0, 0]) if abs(n[1]) < 0.9 else np.cross(n, [1.0, 0, 0]); u /= np.linalg.norm(u)
    v = np.cross(n, u)
    Q = np.c_[(P - c) @ u, (P - c) @ v]
    if (np.roll(Q[:, 0], -1) - Q[:, 0]) @ (np.roll(Q[:, 1], -1) + Q[:, 1]) > 0:
        sgn = -1.0       # (clockwise in (u, v))
    else:
        sgn = 1.0
    out = []
    for k in range(len(Q)):
        a, b, d = Q[k - 1], Q[k], Q[(k + 1) % len(Q)]
        e1 = (b - a) / np.linalg.norm(b - a); e2 = (d - b) / np.linalg.norm(d - b)
        n1 = sgn * np.array([e1[1], -e1[0]]); n2 = sgn * np.array([e2[1], -e2[0]])     # (outward)
        m = n1 + n2
        m = m / np.linalg.norm(m) if np.linalg.norm(m) > 1e-9 else n1
        s = TUCK / max(m @ n1, 0.35)
        q = b + m * s
        out.append(c + q[0] * u + q[1] * v)
    return out


def triangles():
    out = []
    for name, P in PANES.items():
        P = [np.array(p, float) for p in P] if '(facets)' in name else tucked(P)
        for k in range(1, len(P) - 1):
            out += [P[0], P[k], P[k + 1]]
    return out


def main(argv):
    if '--restore' in argv:                 # IN.json (beside the original's orig.geo.json) -> OUT.json
        # (every original face the cut took that is greenhouse structure, seen through the glass or not: the cut
        # took the inner faces of the right-hand posts and bulkhead too, leaving them hollow to look through)
        i = argv.index('--restore')
        import json
        orig = json.load(open(os.path.join(os.path.dirname(os.path.abspath(argv[i + 1])), 'orig.geo.json'),
                              encoding='utf-8-sig'))
        ac = L.Aircraft(VID)
        key = lambda Q: tuple(sorted(tuple(q) for q in np.round(Q, 3)))
        have = set()
        for b in ac.bones:
            pm = b.get('poly_mesh')
            if pm and pm.get('polys'):
                P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
                have.update(key(P[[k[0] for k in poly]]) for poly in pm['polys'] if poly)
        keep = []
        for b in orig['minecraft:geometry'][0]['bones']:
            pm = b.get('poly_mesh')
            if not pm or not pm.get('polys') or b['name'] in ('main_rotor', 'tail_rotor'):
                continue
            P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
            for poly in pm['polys']:
                if not poly or len(poly) < 3:
                    continue
                Q = P[[k[0] for k in poly]]
                if key(Q) not in have and is_frame(Q) and np.abs(Q[:, 0]).max() <= 1.3 and 4.1 <= Q[:, 2].mean() <= 8.6:
                    keep.append(dict(bone=b['name'], P=[pm['positions'][k[0]] for k in poly],
                                     N=[pm['normals'][k[1]] for k in poly], U=[pm['uvs'][k[2]] for k in poly]))
        json.dump(keep, open(argv[i + 2], 'w'))
        print(f'{VID}: {len(keep)} greenhouse faces the cut took go back')
        return
    if '--check' in argv:
        for name, P in PANES.items():
            c, n, dev = plane(P)
            faceted = '(facets)' in name
            flag = '' if faceted or dev <= 0.002 else '   NOT FLAT'
            print(f'{name}: {len(P)} corners, normal {np.round(n, 3).tolist()}, {dev * 1000:.1f} mm off flat'
                  + (' (flat facets fanned from its first corner)' if faceted else '') + flag)
        return
    ac = L.Aircraft(VID)
    L.add_glass(ac.asset, [list(map(float, p)) for p in triangles()])
    L.write_asset(ac.asset_path, ac.asset)
    print(f'{VID}: {sum(len(P) - 2 for P in PANES.values())} glass triangles in {len(PANES)} hand-fitted panes')


if __name__ == '__main__':
    main(sys.argv[1:])
