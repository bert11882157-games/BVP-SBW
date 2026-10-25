#!/usr/bin/env python3
"""Mi-24V canopy glass, fitted by hand to the original model's own canopy frames (bert, 2026-10-24: keep the original
canopy, fit the glass by hand, no generator), as glass_mi_24d.py does for the Mi-24D. Only glass is added.

The Mi-24V model is not the Mi-24D's: thin round-section tubes, on its own scale (not the 1/16 grid). Its frames
(vehicle-local blocks, read off main 8e04fb62 with the measuring aids in work/handbuilt):

Pilot's canopy (eye at 0, 2.589, 3.647):
  * the windscreen ring, a flat 12-sided ring raked on z = 4.345 - 0.7266 (y - 2.5752), inner edge (+-0.108, 2.284),
    (+-0.252 and +-0.324, 2.400), x +-0.324 up to y 2.75, (+-0.252, 2.75), (+-0.216, 2.808), (+-0.036, 2.867);
  * on the right: the top rail (x -0.18..-0.29, y 2.84-2.87) from the ring's top back to the intake fairing at z 3.35,
    and the door post running from the rail at z 3.80 diagonally down and forward to (-0.70, 2.27, 3.90);
  * the intake fairing's front face at z 3.31-3.39 (y 2.66 at x +-0.61, 2.98 at +-0.54, 3.09 at +-0.29..0.43);
  * each side's pale blister strip (the original's own, opaque, kept) from (+-0.61, 2.66, 3.35) down to
    (+-0.70, 2.27, 3.90), on the sill;
  * the step between the canopies at z 4.50-4.72 (y 2.08 at its sides, 2.30 in the middle).
  Glass: the flat windscreen; a roof from the right rail across to the left; the left side from the roof down to the
  sill and the blister strip, between the ring and the fairing; the right door window between the rail, the post, the
  strip and the fairing; the right front quarter between the post and the ring.
Operator's canopy (eye at 0, 1.85, 5.15):
  * the front arch at z 5.77-5.98 (legs at x +-0.25..0.32 from y 1.58, its top bar y 2.0-2.08 at x +-0.14);
  * the hood's top rail on the right only: (-0.29, 2.20, 4.71), (-0.29, 2.16, 5.29), (-0.20, 2.00, 5.81);
  * the sill: y 2.08 at z 4.72, 1.94 at 4.79, 1.72 at 5.15, 1.54 at 5.51, 1.50 at 5.58.
  Glass: the flat front pane in the arch; the hood's left side and top from the step to the arch, the sill up to the
  rail; the right side under the rail.

The cockpit's cut also took original faces that are seen through the glass; the canopy's own structure among them
(is_frame) goes back after the glass (run_heli.ps1 calls --restore).

Usage: glass_mi24v.py [--check | --restore IN.json OUT.json]
"""
import os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L            # noqa: E402

VID = 'mi24v'


def wv(y):
    """the pilot's windscreen plane (the ring's mid-depth): z at height y"""
    return round(4.345 - 0.7266 * (y - 2.5752), 4)


PANES = {
    'pilot windscreen': [(0.112, 2.274, wv(2.274)), (0.255, 2.392, wv(2.392)), (0.334, 2.392, wv(2.392)),
                         (0.334, 2.758, wv(2.758)), (0.258, 2.758, wv(2.758)), (0.222, 2.817, wv(2.817)),
                         (0.036, 2.876, wv(2.876)), (-0.036, 2.876, wv(2.876)), (-0.222, 2.817, wv(2.817)),
                         (-0.258, 2.758, wv(2.758)), (-0.334, 2.758, wv(2.758)), (-0.334, 2.392, wv(2.392)),
                         (-0.255, 2.392, wv(2.392)), (-0.112, 2.274, wv(2.274))],
    # the roof: under the right rail (x -0.26) across to the left shoulder seam, from the ring's top back to the
    # fairing's face
    'pilot roof (facets)': [(-0.245, 2.86, 4.14), (-0.26, 2.865, 3.35), (0.39, 2.865, 3.35), (0.245, 2.86, 4.14),
                            (0.04, 2.90, 4.13)],
    # the left side: the shoulder seam down to the sill and the blister strip's top, the ring's left side to the fairing
    'pilot left side (facets)': [(0.245, 2.86, 4.14), (0.39, 2.865, 3.35), (0.60, 2.655, 3.35),
                                 (0.615, 2.37, 3.67), (0.595, 2.26, 3.92), (0.57, 2.13, 4.14), (0.45, 2.10, 4.48),
                                 (0.33, 2.40, 4.475), (0.385, 2.575, 4.345), (0.315, 2.75, 4.215)],
    # under the ring's foot, each side: the ring's lower corner bar down to the step's skin
    'pilot left ring foot (facets)': [(0.33, 2.40, 4.475), (0.45, 2.10, 4.48), (0.40, 2.18, 4.545), (0.22, 2.29, 4.565)],
    'pilot right ring foot (facets)': [(-0.33, 2.40, 4.475), (-0.45, 2.10, 4.48), (-0.40, 2.18, 4.545),
                                       (-0.22, 2.29, 4.565)],
    # the right door window: under the rail, behind the door post, above the blister strip, to the fairing
    'pilot door (facets)': [(-0.26, 2.86, 3.80), (-0.26, 2.865, 3.35), (-0.39, 2.865, 3.35), (-0.60, 2.655, 3.35),
                            (-0.615, 2.37, 3.67), (-0.62, 2.28, 3.89), (-0.29, 2.85, 3.80)],
    # the right front quarter: between the door post and the ring, down to the sill
    'pilot right quarter (facets)': [(-0.29, 2.85, 3.96), (-0.245, 2.86, 4.14), (-0.315, 2.75, 4.215),
                                     (-0.385, 2.575, 4.345), (-0.33, 2.40, 4.475), (-0.45, 2.10, 4.48), (-0.57, 2.13, 4.14),
                                     (-0.62, 2.26, 3.92), (-0.53, 2.60, 4.02)],
    # the operator's front pane in the arch: under the legs (x +-0.29) from the deck's front edge up to the top bar's underside
    'operator front pane (facets)': [(0.29, 1.42, 5.99), (0.29, 1.93, 5.90), (0.15, 1.99, 5.87), (-0.15, 1.99, 5.87),
                                     (-0.29, 1.93, 5.90), (-0.29, 1.42, 5.99)],
    # the operator's hood, left and top: from the step's back (z 4.72) to the arch, the left sill up and over to the
    # right rail
    'operator top (facets)': [(-0.27, 2.21, 4.72), (-0.27, 2.17, 5.29), (-0.19, 2.01, 5.81), (0.0, 2.08, 5.83),
                              (0.19, 2.01, 5.81), (0.31, 1.96, 5.80), (0.40, 2.08, 5.25), (0.40, 2.22, 4.72),
                              (0.0, 2.30, 4.72)],
    'operator left side (facets)': [(0.40, 2.22, 4.72), (0.40, 2.08, 5.25), (0.31, 1.96, 5.80), (0.29, 1.93, 5.90),
                                    (0.29, 1.42, 5.99), (0.26, 1.40, 5.88), (0.47, 1.465, 5.51), (0.48, 1.71, 5.15), (0.50, 1.93, 4.79),
                                    (0.50, 2.07, 4.72)],
    'operator right side (facets)': [(-0.30, 2.20, 4.72), (-0.50, 2.07, 4.72), (-0.50, 1.93, 4.79),
                                     (-0.48, 1.71, 5.15), (-0.47, 1.465, 5.51), (-0.26, 1.40, 5.88),
                                     (-0.29, 1.42, 5.99), (-0.29, 1.93, 5.90), (-0.30, 1.95, 5.79), (-0.22, 2.01, 5.81),
                                     (-0.29, 2.16, 5.29)],
}


def is_frame(P):
    """an original face the cockpit's cut took that belongs to the canopy's structure, not the old interior, and is
    seen through the glass, so goes back (--restore): the fairing's front face, the blister walls, the step, the
    operator's side walls and the arch's foot. The old seat bulkhead, shelf, panel coaming and sight box stay out."""
    c = P.mean(0)
    return bool(abs(c[0]) >= 0.4 or c[1] >= 2.6 or (4.5 <= c[2] <= 4.8 and c[1] >= 1.85 and np.ptp(P[:, 0]) > 0.3)
                or (c[2] > 5.5 and c[1] > 1.4 and abs(c[0]) > 0.15))


def plane(P):
    P = np.asarray(P, float); c = P.mean(0)
    _, _, vt = np.linalg.svd(P - c)
    return c, vt[2], float(np.abs((P - c) @ vt[2]).max())


def triangles():
    out = []
    for name, P in PANES.items():
        P = [np.array(p, float) for p in P]
        for k in range(1, len(P) - 1):
            out += [P[0], P[k], P[k + 1]]
    return out


def main(argv):
    if '--restore' in argv:                 # IN.json (exterior_diff --write, no --glass-blocks) -> OUT.json
        i = argv.index('--restore')
        import json
        faces = json.load(open(argv[i + 1]))
        keep = [f for f in faces if is_frame(np.array(f['P'], float) * np.array([1, 1, -1]) / 16.0)]
        json.dump(keep, open(argv[i + 2], 'w'))
        print(f'{VID}: {len(keep)} of the cut\'s {len(faces)} seen-through faces are canopy structure, going back')
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
