#!/usr/bin/env python3
"""Saab J 21A canopy, made by hand: the canopy's shape from the model's old glass (glass_sections.py), the
windscreen's set to the model's nose deck (the old glass stops at the canopy's front), and the real frame layout.

The J 21 canopy (side views and photos of the type): a windscreen of a flat armoured front pane between two side
panes, its two posts down to the base frame on the nose deck and its arch over the sight; the canopy held by the
frame round its edge (no frame across it is certain from the references at hand: none is drawn).

Run after the cockpit (saab_j_21a_1.py), on its output. Usage: python3 tools/cockpit_models/canopy_saab_j_21a_1.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'saab_j_21a_1'
SOURCE_BONE = 'wreck_fuselage_0__hull'


def _section(xs, ys, yt, n=7, k=2.4):
    """a smooth section from the sill (xs, ys) up to the top centre (0, yt): a superellipse quadrant"""
    return [(round(xs * math.cos(t) ** (2 / k), 4), round(ys + (yt - ys) * math.sin(t) ** (2 / k), 4))
            for t in [math.pi / 2 * i / (n - 1) for i in range(n)]]


# the canopy's sections off the old glass, the windscreen's set to the nose deck (sill x, sill y, top y)
SECTIONS = [
    (-0.30, [(0.40, -0.26), (0.42, -0.12), (0.36, 0.0), (0.27, 0.08), (0.18, 0.14), (0.10, 0.20), (0.0, 0.21)]),
    (-0.20, [(0.411, -0.266), (0.433, -0.124), (0.368, 0.003), (0.282, 0.093), (0.193, 0.156), (0.107, 0.219), (0.0, 0.227)]),
    (0.00, [(0.443, -0.27), (0.455, -0.116), (0.39, 0.018), (0.304, 0.117), (0.209, 0.186), (0.111, 0.242), (0.0, 0.254)]),
    (0.20, [(0.471, -0.283), (0.471, -0.119), (0.417, 0.024), (0.33, 0.132), (0.231, 0.212), (0.12, 0.265), (0.0, 0.281)]),
    (0.40, [(0.477, -0.289), (0.477, -0.123), (0.423, 0.021), (0.34, 0.135), (0.238, 0.219), (0.125, 0.275), (0.0, 0.287)]),
] + [(z, _section(xs, ys, yt)) for z, xs, ys, yt in (
    (0.55, 0.465, -0.29, 0.25), (0.65, 0.44, -0.28, 0.17), (0.75, 0.40, -0.27, 0.07), (0.85, 0.33, -0.26, -0.04),
    (0.95, 0.26, -0.25, -0.15))]
SPLIT = 0.50                     # the canopy's front edge, against the windscreen arch over the sight
FRONT_PANE_X = 0.12              # the flat front pane's edges, out from the centre line
# the flat armoured front pane (flat_pane): from the arch to the base frame, straight-sided
PANE = (SPLIT + 0.05, 0.95, FRONT_PANE_X, 0.01, 0.03)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -0.30, SPLIT, nz=12) + C.glass_triangles(sh, E, SPLIT + 0.05, 0.95, nz=10)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -0.28, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, -0.30, SPLIT, 0.045, 0.035, out, inner, steps=12)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.022, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.05, 0.95, 0.03, 0.022, out, inner, sides=(1, -1),
             steps=12)
    C.bow(m, sh, E, 0.935, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 0.95, 0.045, 0.035, out, inner, steps=12)
    return m, glass


def main(argv):
    # the old windscreen frame posts and pieces standing in the new windscreen's place
    # (and the old canopy's frame strips left inside the new one)
    C.run(VID, SOURCE_BONE, build, argv, boxes=[((-0.30, -0.22, 0.55), (0.30, 0.30, 0.95)),
                                                ((0.15, 0.05, -0.05), (0.30, 0.17, 0.52)),
                                                ((-0.30, 0.05, -0.05), (-0.15, 0.17, 0.52))])


if __name__ == '__main__':
    main(sys.argv[1:])
