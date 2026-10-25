#!/usr/bin/env python3
"""Dassault M.D.450 Ouragan canopy, made by hand: this model has no canopy glass of its own, only an old frame, so the
canopy is made here from smooth sections set on the cockpit's sills (sections.py: x +-0.29, y -0.35 beside the eye),
its top 0.20 m over the eye, its tail sealed on the spine fairing 0.70 m behind the eye, and the windscreen
from its arch 0.55 m ahead down to the base frame on the nose 1.10 m ahead.

The Ouragan canopy (side views and photos of the type): a windscreen of a flat armoured front pane between two curved
side panes, its two posts down to the base frame and its arch at the rear; the bubble canopy sliding aft, frameless
but for its edge (the front bow against the windscreen arch, the sill rails, the tail frame on the fairing).

Run after the cockpit (md_450_ouragan.py), on its output. Usage: python3 tools/cockpit_models/canopy_md_450_ouragan.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'md_450_ouragan'
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
    (-0.70, 0.22, -0.30, -0.050), (-0.55, 0.26, -0.32, 0.070), (-0.38, 0.285, -0.34, 0.165), (-0.05, 0.30, -0.35, 0.200), (0.20, 0.30, -0.36, 0.190),
    (0.40, 0.29, -0.37, 0.130), (0.55, 0.28, -0.38, 0.060), (0.70, 0.26, -0.39, -0.020), (0.85, 0.22, -0.38, -0.100),
    (1.00, 0.17, -0.35, -0.220), (1.10, 0.14, -0.34, -0.300))]
SPLIT = 0.52                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.090             # the flat armoured pane's edges, out from the centre line
PANE = (SPLIT + 0.045, 1.10, FRONT_PANE_X, 0.01, 0.025)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -0.70, SPLIT, nz=20) + C.glass_triangles(sh, E, SPLIT + 0.045, 1.10, nz=12)
    # the canopy: its tail frame on the fairing, its front bow, the sill rails
    C.bow(m, sh, E, -0.68, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -0.70, SPLIT, 0.04, 0.03, out, inner, steps=20)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.045, 1.10, 0.025, 0.02, out, inner, steps=12)
    C.bow(m, sh, E, 1.085, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 1.10, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
