#!/usr/bin/env python3
"""Republic F-84F Thunderstreak canopy, made by hand: this model has no canopy glass of its own, only an old frame,
and its canopy stands open; so the canopy is made here, closed, from smooth sections set on the cockpit's sills
(sections.py: x +-0.31, y -0.30 beside the eye), its top 0.27 m over the eye, tapering down onto the spine deck 1.55 m behind
the eye, and the windscreen from its arch 0.38 m ahead down to its foot on the nose deck 1.25 m ahead, over the old
frame's posts. The model's open hood and the two old plates beside the windscreen are removed.

The F-84F canopy (side views and photos of the type): a windscreen of a flat armoured front pane between two curved
side panes, its two posts down to the foot and the arch at the rear; the clamshell hinged at its back, frameless but
for its edge (the front bow against the windscreen arch, the sill rails, the tail frame on the spine).

Run after the cockpit (f_84f.py), on its output. Usage: python3 tools/cockpit_models/canopy_f_84f.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'f_84f'
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
    (-1.55, 0.12, -0.27, -0.215), (-1.40, 0.18, -0.27, -0.110), (-1.15, 0.24, -0.27, 0.060), (-0.90, 0.29, -0.29, 0.200), (-0.55, 0.31, -0.30, 0.260), (-0.20, 0.31, -0.30, 0.270),
    (0.10, 0.31, -0.30, 0.250), (0.30, 0.31, -0.30, 0.180), (0.45, 0.30, -0.30, 0.120), (0.65, 0.28, -0.30, 0.040),
    (0.85, 0.26, -0.30, -0.070), (1.05, 0.24, -0.30, -0.180), (1.25, 0.22, -0.30, -0.285))]
SPLIT = 0.38                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.110             # the flat armoured pane's edges, out from the centre line
PANE = (SPLIT + 0.045, 1.25, FRONT_PANE_X, 0.01, 0.025)
# the model's open hood standing up over the spine, and the two old plates beside the windscreen: removed (eye-relative
# boxes; the exterior restore leaves them gone too)
GONE = [((-0.42, -0.24, -1.95), (0.42, 0.45, -0.50)),
        ((0.25, -0.26, 0.10), (0.55, 0.30, 0.50)), ((-0.55, -0.26, 0.10), (-0.25, 0.30, 0.50))]


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -1.55, SPLIT, nz=32) + C.glass_triangles(sh, E, SPLIT + 0.045, 1.25, nz=18)
    # the canopy: its tail frame on the fairing, its front bow, the sill rails
    C.bow(m, sh, E, -1.53, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -1.55, SPLIT, 0.04, 0.03, out, inner, steps=32)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.045, 1.25, 0.025, 0.02, out, inner, steps=18)
    C.bow(m, sh, E, 1.235, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 1.25, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], boxes=GONE)
