#!/usr/bin/env python3
"""McDonnell F2H-2 Banshee canopy, made by hand: this model has no canopy of its own, only an old windscreen frame
(thin yellow struts) and a tall rear frame plate, so the canopy is made here from smooth sections set on the cockpit's
sills (sections.py: x +-0.28, y -0.23 beside the eye), its top at the old frame's (0.39 m over the eye), down onto the
spine 1.15 m behind the eye, and the long windscreen from its arch 0.55 m ahead down to its foot on the nose 1.62 m
ahead. The old struts and the rear plate are removed.

The F2H-2 canopy (side views and photos of the type): a windscreen of a flat armoured front pane between two curved
side panes, its two posts down to the foot and the arch at the rear; the sliding bubble, frameless but for its edge
(the front bow against the windscreen arch, the sill rails, the tail frame on the spine).

Run after the cockpit (f2h_2.py), on its output. Usage: python3 tools/cockpit_models/canopy_f2h_2.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'f2h_2'
SOURCE_BONE = 'wreck_fuselage_0__hull'


def _section(xs, ys, yt, n=7, k=2.6):
    """a smooth section from the sill (xs, ys) up to the top centre (0, yt): a superellipse quadrant (k 2 an ellipse)"""
    out = []
    for i in range(n):
        t = math.pi / 2 * i / (n - 1)
        out.append((round(xs * math.cos(t) ** (2 / k), 4), round(ys + (yt - ys) * math.sin(t) ** (2 / k), 4)))
    return out


# the sill (x, y) and the top's height at each station, metres from the eye
SECTIONS = [(z, _section(xs, ys, yt)) for z, xs, ys, yt in (
    (-1.15, 0.28, -0.25, -0.050), (-0.85, 0.30, -0.24, 0.240), (-0.50, 0.30, -0.23, 0.370), (-0.15, 0.30, -0.23, 0.390),
    (0.20, 0.30, -0.23, 0.380), (0.45, 0.29, -0.23, 0.340), (0.60, 0.28, -0.23, 0.310), (0.90, 0.26, -0.22, 0.180),
    (1.20, 0.23, -0.21, 0.010), (1.45, 0.20, -0.21, -0.120), (1.62, 0.17, -0.22, -0.210))]
SPLIT = 0.55                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.100             # the flat armoured pane's edges, out from the centre line
PANE = (SPLIT + 0.045, 1.62, FRONT_PANE_X, 0.01, 0.025)
# the old windscreen struts either side and the rear frame plate standing behind the seat (eye-relative boxes)
GONE = [((0.12, -0.22, 0.45), (0.38, 0.55, 1.45)), ((-0.38, -0.22, 0.45), (-0.12, 0.55, 1.45)),
        ((-0.30, -0.22, -0.62), (0.30, 0.65, -0.28))]


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -1.15, SPLIT, nz=28) + C.glass_triangles(sh, E, SPLIT + 0.045, 1.62, nz=20)
    # the canopy: its tail frame on the fairing, its front bow, the sill rails
    C.bow(m, sh, E, -1.13, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -1.15, SPLIT, 0.04, 0.03, out, inner, steps=28)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.045, 1.62, 0.025, 0.02, out, inner, steps=20)
    C.bow(m, sh, E, 1.605, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 1.62, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], boxes=GONE)
