#!/usr/bin/env python3
"""McDonnell F3H-2 Demon canopy, made by hand: this model's canopy is an old open frame with no glass, so the canopy is
made here from smooth sections set on the cockpit's sill rails (sections.py: x +-0.53, y -0.22 beside the eye), its
top at the old frame's (0.28 m over the eye), back to the fairing 1.05 m behind the eye, and the windscreen from its
arch 0.60 m ahead down to its foot on the nose 1.62 m ahead. The old frame is removed.

The F3H canopy (side views and photos of the type): a windscreen of a flat armoured front pane between two curved
side panes, its two posts down to the foot and the arch at the rear; the sliding bubble, frameless but for its edge
(the front bow against the windscreen arch, the sill rails, the tail frame on the spine).

Run after the cockpit (f3h.py), on its output. Usage: python3 tools/cockpit_models/canopy_f3h.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'f3h'
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
    (-1.05, 0.46, -0.22, 0.190), (-0.80, 0.51, -0.22, 0.250), (-0.45, 0.53, -0.22, 0.280), (-0.10, 0.53, -0.22, 0.285),
    (0.25, 0.52, -0.22, 0.275), (0.45, 0.50, -0.23, 0.255), (0.60, 0.48, -0.25, 0.235), (0.90, 0.44, -0.33, 0.090),
    (1.20, 0.42, -0.40, -0.110), (1.45, 0.40, -0.44, -0.270), (1.62, 0.38, -0.46, -0.380))]
SPLIT = 0.60                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.270             # the flat armoured pane's edges, out from the centre line
PANE = (SPLIT + 0.045, 1.62, FRONT_PANE_X, 0.01, 0.025)
# the old open canopy frame and windscreen struts over the sills (eye-relative box)
GONE = [((-0.60, -0.21, -1.02), (0.60, 0.40, 1.30))]


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -1.05, SPLIT, nz=28) + C.glass_triangles(sh, E, SPLIT + 0.045, 1.62, nz=20)
    # the canopy: its tail frame on the fairing, its front bow, the sill rails
    C.bow(m, sh, E, -1.03, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -1.05, SPLIT, 0.04, 0.03, out, inner, steps=28)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.045, 1.62, 0.025, 0.02, out, inner, steps=20)
    C.bow(m, sh, E, 1.605, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 1.62, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], boxes=GONE)
