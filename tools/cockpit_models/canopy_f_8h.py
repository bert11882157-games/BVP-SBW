#!/usr/bin/env python3
"""Vought F-8H Crusader canopy, made by hand: the shape after the model's old glass (glass_sections.py on the
be357476 asset), as smooth sections on the cockpit's sills (x +-0.37, y -0.37 beside the eye, falling to -0.58 at the
windscreen's foot 1.8 m ahead), its top 0.30 m over the eye, its tail on the fairing 0.52 m behind the eye.

The F-8 canopy (side views and photos of the type): a long windscreen of a flat armoured front pane between two
curved side panes, its two posts down to the foot and the arch at the rear; the canopy hinged at the back, frameless
but for its edge (the front bow against the windscreen arch, the sill rails, the tail frame on the fairing).

Run after the cockpit (f_8h.py), on its output. Usage: python3 tools/cockpit_models/canopy_f_8h.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'f_8h'
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
    (-0.52, 0.22, 0.00, 0.210), (-0.35, 0.35, -0.22, 0.280), (-0.15, 0.37, -0.32, 0.300), (0.00, 0.37, -0.37, 0.298),
    (0.25, 0.38, -0.44, 0.228), (0.50, 0.38, -0.46, 0.135), (0.75, 0.37, -0.49, 0.037), (1.00, 0.36, -0.525, -0.071),
    (1.25, 0.35, -0.565, -0.191), (1.50, 0.32, -0.59, -0.318), (1.78, 0.27, -0.58, -0.455))]
SPLIT = 0.85                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.120             # the flat armoured pane's edges, out from the centre line
PANE = (SPLIT + 0.045, 1.78, FRONT_PANE_X, 0.01, 0.025)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -0.52, SPLIT, nz=24) + C.glass_triangles(sh, E, SPLIT + 0.045, 1.78, nz=20)
    # the canopy: its tail frame on the fairing, its front bow, the sill rails
    C.bow(m, sh, E, -0.50, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -0.52, SPLIT, 0.04, 0.03, out, inner, steps=24)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.045, 1.78, 0.025, 0.02, out, inner, steps=20)
    C.bow(m, sh, E, 1.765, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 1.78, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
