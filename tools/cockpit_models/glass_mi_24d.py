#!/usr/bin/env python3
"""Mi-24D canopy glass, fitted by hand to the original model's own canopy frames (bert, 2026-10-23/24: "the original
canopy is better than anything you could make ... fitting the glass will be difficult, and will require handmade,
not a generator"). The canopy itself is the original modeller's, untouched; only glass is added.

Every pane below is written out corner by corner, read off the original frame (main 8e04fb62) with the measuring
aids in work/handbuilt (frame_ortho.py views, snap_vertex.py, ring_inner.py). The frames are box-section bars on the
model's 1/16-block grid; each pane lies at the middle of its bars' depth and runs about 1 cm in under them, so its
edge is never seen and it never stands proud of a frame's outer face. Flat panes are flat to the millimetre; where an
opening is curved (the blisters) it gets a few flat facets meeting along a seam, with no bar between them.

The original's frame layout (vehicle-local blocks, the model about 1.3 times life size), and where the glass goes:

Pilot's canopy (eye at 0, 3.56, 5.75):
  * the windscreen ring, a closed box-section ring from z 7.0 (its bottom bar) back to z 6.44 (its top bar), outer
    x +-0.5: one flat armoured pane in it (inner edge x +-0.375 at y 3.44-3.69, +-0.3125 at 3.25 and 3.75, +-0.1875 at
    3.875, the top bar's underside at y 3.95, the bottom bar's top at y 3.125), on the ring's mid-depth plane
    z = 6.7865 - 0.6413 (y - 3.5208);
  * the rear arch at z 5.12-5.7 (front edge (0.5625, 3.5, 5.19), (0.5, 3.69, 5.25), (0.4375, 3.81, 5.5),
    (0.3125, 3.94, 5.63), (0, 4.0, 5.69), mirrored);
  * on the right: the door's top/hinge rail (x -0.25..-0.5, y 3.875-4.06, z 5.6-6.31), a diagonal roof bar from its
    front end to the ring's top, and the door's front post (z 6.125-6.3125, mid-depth x -0.47 at y 3.94 to -0.69 at
    3.19); glass: the door window behind the post (two facets on a seam at y 3.44, the blister), the front quarter
    between the post and the ring (two facets), and a small corner window under the diagonal bar;
  * on the left and over the top there is no bar at all: one opening from the ring back to the arch, from the sill
    (y 3.52 at z 5.15 falling to 2.88 at z 6.80) over the top to the right rail; glass: an upright side at x 0.46, a
    shoulder from (0.46, 3.69) to (0.27, 3.95), and a roof on y = 3.995 - 0.16 x.
Operator's canopy (eye at 0, 2.678, 7.75):
  * the tombstone ring at z 8.44-8.88 (outer x +-0.5, y 1.87-2.78): one flat armoured pane in it (inner edge y 2.0
    at x +-0.3125, x +-0.375 from y 2.125 to 2.3125, (+-0.3125, 2.5625), (+-0.25, 2.625), the top bar's underside flat
    at y 2.6875), on z = 8.594 - 0.362 (y - 2.325);
  * the "lambda" posts each side of the ring (apex (+-0.28, 2.72, 8.42), rear post down to (+-0.6, 2.2, 8.22), front
    post to (+-0.44, 2.12, 8.6)): a cheek pane inside each;
  * the hood's hinge rail off centre on the right (x -0.25..-0.375, y 3.125 at z 7.28 falling to 2.9 at z 8.19), the
    left side rail (x 0.625-0.69, top y 2.69 at z 7.19 falling to 2.31 at z 8.1), the right side bar (x -0.66,
    y 2.375 at z 7.75 to 2.15 at z 8.19) and the rear fairing (z 7.125-7.31, top y 3.0-3.25); glass: the hood's left
    side and top as two faceted panes on a seam from the fairing's shoulder to the left rear post's top, and the right
    side from under the hinge rail down to the side bar.

Usage: glass_mi_24d.py [--check]   (--check prints each pane's flatness and the corners; nothing is written)
"""
import os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L            # noqa: E402

VID = 'mi_24d'


def ws(y):
    """the pilot's windscreen plane: z at height y"""
    return round(6.7865 - 0.6413 * (y - 3.5208), 4)


def ts(y):
    """the operator's armoured front pane ("tombstone") plane: z at height y"""
    return round(8.594 - 0.362 * (y - 2.325), 4)


PANES = {
    # the pilot's armoured windscreen, counter-clockwise seen from ahead
    'pilot windscreen': [(0.26, 3.115, ws(3.115)), (0.322, 3.245, ws(3.245)), (0.385, 3.4375, ws(3.4375)),
                         (0.385, 3.6875, ws(3.6875)), (0.32, 3.757, ws(3.757)), (0.19, 3.885, ws(3.885)),
                         (0.07, 3.95, ws(3.95)), (-0.07, 3.95, ws(3.95)), (-0.19, 3.885, ws(3.885)), (-0.32, 3.757, ws(3.757)), (-0.385, 3.6875, ws(3.6875)),
                         (-0.385, 3.4375, ws(3.4375)), (-0.322, 3.245, ws(3.245)), (-0.26, 3.115, ws(3.115))],
    # the pilot's left side and roof: one opening in the original from the windscreen ring back to the rear arch, from
    # the sill up over the top to the right roof rail, no bar in it; three flat facets meeting along two seams.
    # The lower side, upright at x 0.46 (the ring's outer face is at 0.5, the arch's at 0.5-0.56, the skin's top edge
    # at 0.47-0.56): its front edge 2-3 cm into the ring behind its rear face, its rear 3 cm into the arch, its foot
    # 3 cm under the sill line (y = 3.52 at z 5.15 falling to 2.88 at z 6.80)
    'pilot left side': [(0.46, 3.6875, 6.72), (0.46, 3.6875, 5.22), (0.46, 3.482, 5.17), (0.46, 2.80, 6.92),
                        (0.46, 3.06, 6.975), (0.46, 3.4375, 6.85)],
    # the shoulder: from the side's top seam (x 0.46, y 3.6875) up to the roof seam (x 0.27, y 3.952), following the
    # ring's upper-left bend ((0.5, 3.6875) - (0.4375, 3.8125) - (0.25, 3.9375)) and the arch's ((0.5, 3.6875, 5.25) -
    # (0.4375, 3.8125, 5.5) - (0.3125, 3.9375, 5.625))
    'pilot left shoulder': [(0.46, 3.6875, 6.72), (0.27, 3.952, 6.56), (0.27, 3.952, 5.60), (0.46, 3.6875, 5.22)],
    # the roof, on y = 3.995 - 0.16 x: from the shoulder seam across to 1 cm under the right roof rail (its inner edge at
    # x -0.25, y 4.0-4.0625, z 5.875-6.3125, and its diagonal to the ring's top at (-0.0625, 4.0, 6.4375)); its front
    # 3 cm into the ring's top, its rear into the arch's top ((0, 4.0, 5.6875), (-0.25, 3.9375, 5.625))
    'pilot roof': [(0.27, 3.952, 6.56), (0.0625, 3.985, 6.50), (-0.07, 4.006, 6.45), (-0.26, 4.037, 6.30),
                   (-0.26, 4.037, 5.64), (0.0, 3.995, 5.665), (0.27, 3.952, 5.60)],
    # the pilot's door window on the right, between the rear arch and the door's front post (z 6.125-6.3125), under
    # the top rail (its lower edge x -0.44..-0.5 at y 3.875-3.94): the door's blister, two facets on a seam from the
    # post's middle at y 3.4375 back to the arch's foot. The post's depth mid-line: x -0.47 at y 3.94, -0.594 at 3.75,
    # -0.656 at 3.4375, -0.687 at 3.19; the arch's front edge (-0.4375, 3.8125, 5.5), (-0.5625, 3.6875, 5.25),
    # (-0.625, 3.5, 5.1875)
    'pilot door upper (facets)': [(-0.47, 3.90, 6.15), (-0.44, 3.87, 5.50), (-0.53, 3.69, 5.24), (-0.61, 3.43, 5.18),
                         (-0.656, 3.4375, 6.15), (-0.594, 3.75, 6.15)],
    'pilot door lower (facets)': [(-0.656, 3.4375, 6.15), (-0.61, 3.43, 5.18), (-0.69, 3.09, 6.10), (-0.687, 3.19, 6.15)],
    # the right front quarter, between the door post's front face (z 6.3125) and the windscreen ring's right side
    # (mid-depth x -0.44, rear face z 6.69-6.94), under the roof rail's diagonal ((-0.4375, 4.0, 6.3125) to
    # (-0.4375, 3.8125, 6.5938)); two facets on a seam at y 3.4375
    'pilot right quarter upper (facets)': [(-0.47, 3.94, 6.29), (-0.594, 3.75, 6.29), (-0.656, 3.4375, 6.29),
                                  (-0.44, 3.4375, 6.84), (-0.44, 3.6875, 6.72), (-0.44, 3.80, 6.62)],
    # the small top-right corner window: between the ring's top-right bend ((-0.4375, 3.8125, 6.5938) to
    # (-0.25, 3.9375, 6.5)), the diagonal roof bar ((-0.25, 4.0, 6.3125) to (-0.0625, 4.0, 6.4375)) and the door
    # post's top ((-0.4375..-0.5, 3.94-4.0, 6.3125))
    'pilot right top corner (facets)': [(-0.47, 3.94, 6.29), (-0.44, 3.80, 6.62), (-0.25, 3.93, 6.52),
                                        (-0.26, 4.02, 6.33)],
    # the operator's armoured front pane in its tombstone ring: inner edge at y 2.0 (x +-0.3125, the bottom bar's top),
    # x +-0.375 from y 2.125 to 2.3125, (+-0.3125, 2.5625), (+-0.25, 2.625), its round top at y 2.69; flat, on the ring's
    # mid-depth plane z = 8.594 - 0.362 (y - 2.325) (corners within 22 mm of it, inside the ring's 62 mm depth)
    'operator front pane': [(0.32, 1.99, ts(1.99)), (0.385, 2.115, ts(2.115)), (0.385, 2.3125, ts(2.3125)),
                            (0.32, 2.57, ts(2.57)), (0.26, 2.70, ts(2.70)),
                            (-0.26, 2.70, ts(2.70)), (-0.32, 2.57, ts(2.57)), (-0.385, 2.3125, ts(2.3125)),
                            (-0.385, 2.115, ts(2.115)), (-0.32, 1.99, ts(1.99))],
    # the operator's hood, left and top: one opening in the original from the "lambda" posts and the front ring's top
    # back to the rear fairing (z 7.125-7.31), from the left side rail (x 0.625-0.69, its top at y 2.69 at z 7.19
    # falling to 2.31 at z 8.1) up over the top to the hood's hinge rail (x -0.25..-0.375, y 3.125 at z 7.28, 3.06-3.125
    # at 7.69, 2.875-2.94 at 8.19); two faceted panes on a seam from the fairing's shoulder (0.48, 2.98) to the left
    # lambda's rear post top (0.47, 2.60, 8.34)
    'operator top (facets)': [(0.40, 3.10, 7.30), (0.0, 3.13, 7.30), (-0.26, 3.14, 7.30), (-0.26, 3.094, 7.69),
                              (-0.26, 2.906, 8.19), (-0.30, 2.77, 8.42), (0.0, 2.76, 8.43), (0.29, 2.74, 8.42),
                              (0.47, 2.60, 8.34), (0.49, 2.99, 7.27)],
    'operator left side (facets)': [(0.49, 2.99, 7.27), (0.47, 2.60, 8.34), (0.62, 2.20, 8.24), (0.635, 2.05, 8.24), (0.656, 2.30, 8.10),
                                    (0.656, 2.49, 7.81), (0.656, 2.61, 7.50), (0.656, 2.67, 7.19)],
    # the right side: from under the hinge rail down to the right side bar (x -0.625..-0.69, y 2.375 at z 7.75 to 2.15
    # at 8.19) and, aft of it, the sill; between the fairing and the right lambda
    'operator right side (facets)': [(-0.36, 3.14, 7.30), (-0.36, 3.09, 7.69), (-0.30, 2.90, 8.19),
                                     (-0.29, 2.76, 8.43), (-0.47, 2.60, 8.34), (-0.62, 2.20, 8.24), (-0.635, 2.05, 8.24),
                                     (-0.656, 2.14, 8.19), (-0.656, 2.355, 7.75), (-0.656, 2.66, 7.19),
                                     (-0.49, 2.99, 7.27)],
    # the nose cheek panes inside each lambda: between its rear post (from (+-0.47, 2.6, 8.34) down to
    # (+-0.6, 2.2, 8.22)) and its front post (down to (+-0.44, 2.12, 8.6)), under the apex (+-0.28, 2.72, 8.42)
    'operator left cheek (facets)': [(0.30, 2.70, 8.43), (0.47, 2.60, 8.34), (0.62, 2.20, 8.24),
                                     (0.635, 2.05, 8.24), (0.53, 2.00, 8.46), (0.47, 2.00, 8.63), (0.45, 2.11, 8.62)],
    'operator right cheek (facets)': [(-0.30, 2.70, 8.43), (-0.45, 2.11, 8.62), (-0.47, 2.00, 8.63), (-0.53, 2.00, 8.46),
                                      (-0.635, 2.05, 8.24), (-0.62, 2.20, 8.24), (-0.47, 2.60, 8.34)],
    'pilot right quarter lower (facets)': [(-0.656, 3.4375, 6.29), (-0.687, 3.19, 6.29), (-0.66, 3.04, 6.30),
                                  (-0.44, 2.80, 6.92), (-0.44, 3.06, 6.965), (-0.44, 3.4375, 6.84)],
}


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
