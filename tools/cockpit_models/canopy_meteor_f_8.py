#!/usr/bin/env python3
"""Gloster Meteor F.8 canopy, made by hand: this model has no canopy glass of its own, only an old frame, so the
canopy is made here from smooth sections set on the cockpit's sills (sections.py: x +-0.31, y -0.27 beside the eye),
its top 0.20 m over the eye, its tail sealed on the spine fairing that rises behind the head (0.32 m behind the eye),
and the windscreen from its arch 0.52 m ahead down to the base frame on the nose 1.25 m ahead.

The Meteor F.8 canopy (side views and photos of the type): a windscreen of a flat armoured front pane between two
curved side panes, its two posts down to the base frame and its arch at the rear; the blown bubble canopy sliding
aft over the metal fairing, frameless but for its edge (the front bow against the windscreen arch, the sill rails,
the tail frame on the fairing).

Run after the cockpit (meteor_f_8.py), on its output. Usage: python3 tools/cockpit_models/canopy_meteor_f_8.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'meteor_f_8'
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
    (-0.32, 0.28, -0.20, 0.050), (-0.15, 0.31, -0.26, 0.170), (0.10, 0.32, -0.27, 0.200), (0.35, 0.32, -0.28, 0.170),
    (0.52, 0.31, -0.29, 0.090), (0.70, 0.29, -0.31, 0.020), (0.90, 0.25, -0.32, -0.060), (1.10, 0.20, -0.33, -0.150),
    (1.25, 0.16, -0.34, -0.240))]
SPLIT = 0.50                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.075             # the flat armoured pane's edges, out from the centre line
PANE = (SPLIT + 0.045, 1.25, FRONT_PANE_X, 0.01, 0.025)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -0.32, SPLIT, nz=14) + C.glass_triangles(sh, E, SPLIT + 0.045, 1.25, nz=14)
    # the canopy: its tail frame on the fairing, its front bow, the sill rails
    C.bow(m, sh, E, -0.30, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -0.32, SPLIT, 0.04, 0.03, out, inner, steps=14)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.045, 1.25, 0.025, 0.02, out, inner, steps=14)
    C.bow(m, sh, E, 1.235, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 1.25, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
