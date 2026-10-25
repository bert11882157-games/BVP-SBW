#!/usr/bin/env python3
"""Vought A-7D Corsair II canopy, made by hand: this model's canopy stands open over the fairing with no glass, so the
canopy is made here, closed, from smooth sections set on the cockpit's sills (sections.py: x +-0.39, y -0.40 beside
the eye), its top 0.29 m over the eye, back to the fairing's front face 0.60 m behind the eye, and the windscreen from
its arch 0.55 m ahead down to its foot on the nose 1.30 m ahead. The open canopy and the old windscreen frame are
removed.

The A-7 canopy (side views and photos of the type): a windscreen of a flat armoured front pane between two curved
side panes, its two posts down to the foot and the arch at the rear; the clamshell hinged at its back, frameless but for its edge
(the front bow against the windscreen arch, the sill rails, the tail frame on the fairing).

Run after the cockpit (a_7d.py), on its output. Usage: python3 tools/cockpit_models/canopy_a_7d.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'a_7d'
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
    (-0.60, 0.34, -0.38, 0.270), (-0.40, 0.37, -0.39, 0.285), (-0.10, 0.39, -0.40, 0.290), (0.20, 0.39, -0.40, 0.275),
    (0.40, 0.37, -0.40, 0.245), (0.55, 0.35, -0.40, 0.215), (0.75, 0.32, -0.38, 0.120), (0.95, 0.30, -0.35, 0.010),
    (1.15, 0.28, -0.32, -0.130), (1.30, 0.26, -0.30, -0.240))]
SPLIT = 0.55                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.130             # the flat armoured pane's edges, out from the centre line
PANE = (SPLIT + 0.045, 1.30, FRONT_PANE_X, 0.01, 0.025)
# the open canopy over the fairing and the old windscreen frame (eye-relative boxes)
GONE = [((-0.60, 0.0, -0.56), (0.60, 0.55, -0.05)), ((-0.45, -0.22, 0.35), (0.45, 0.45, 1.45))]


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -0.60, SPLIT, nz=22) + C.glass_triangles(sh, E, SPLIT + 0.045, 1.30, nz=16)
    # the canopy: its tail frame on the fairing, its front bow, the sill rails
    C.bow(m, sh, E, -0.58, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -0.60, SPLIT, 0.04, 0.03, out, inner, steps=22)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.045, 1.30, 0.025, 0.02, out, inner, steps=16)
    C.bow(m, sh, E, 1.285, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 1.30, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], boxes=GONE)
