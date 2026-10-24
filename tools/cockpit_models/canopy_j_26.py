#!/usr/bin/env python3
"""Saab J 26 (P-51D) canopy, made by hand: this model has no canopy glass of its own, only the windscreen's frame and
the rails along the cockpit's sides, so the bubble is made here from smooth sections set on the rails' inner edge
(sections.py: (0.30, -0.05) beside the seat to (0.35, -0.15) at the windscreen), its top 0.30 m over the eye as on the
P-51D, falling to the spine 1.3 m behind and to the windscreen's arch (its top 0.20 m over the eye, 0.45 m ahead).

The P-51D canopy (side views and photos of the type): a windscreen of a flat bulletproof front pane between two curved
side panes, its two posts down to the base frame on the nose and its arch at the rear; the sliding bubble canopy,
frameless but for its edge (the front bow against the windscreen arch, the sill rails, the frame at its tail).

Run after the cockpit (j_26.py), on its output. Usage: python3 tools/cockpit_models/canopy_j_26.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'j_26'
SOURCE_BONE = 'wreck_fuselage_1__hull'


def _section(xs, ys, yt, n=7, k=2.4):
    """a smooth section from the sill (xs, ys) up to the top centre (0, yt): a superellipse quadrant (k 2 an ellipse)"""
    out = []
    for i in range(n):
        t = math.pi / 2 * i / (n - 1)
        out.append((round(xs * math.cos(t) ** (2 / k), 4), round(ys + (yt - ys) * math.sin(t) ** (2 / k), 4)))
    return out


# the sill (x, y) and the top's height at each station, metres from the eye: on the rails' inner edge
SECTIONS = [(z, _section(xs, ys, yt)) for z, xs, ys, yt in (
    (-1.30, 0.20, -0.08, 0.020), (-1.10, 0.26, -0.05, 0.140), (-0.90, 0.28, -0.045, 0.215), (-0.63, 0.29, -0.042, 0.265),
    (-0.40, 0.305, -0.062, 0.290), (-0.10, 0.320, -0.090, 0.295), (0.20, 0.335, -0.118, 0.275), (0.42, 0.345, -0.140, 0.225),
    (0.50, 0.345, -0.150, 0.205), (0.65, 0.320, -0.180, 0.120), (0.80, 0.280, -0.215, 0.000), (0.91, 0.240, -0.240, -0.150))]
SPLIT = 0.44                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.12              # the flat bulletproof pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -1.30, SPLIT, nz=22) + C.glass_triangles(sh, E, SPLIT + 0.045, 0.91, nz=10)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -1.28, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -1.30, SPLIT, 0.04, 0.03, out, inner, steps=22)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.045, 0.91, 0.025, 0.02, out, inner, steps=10)
    C.bow(m, sh, E, 0.895, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 0.91, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
