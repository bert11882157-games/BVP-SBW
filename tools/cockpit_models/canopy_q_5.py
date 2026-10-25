#!/usr/bin/env python3
"""Nanchang Q-5 canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The Q-5 canopy (side views and photos of the type): a three-piece windscreen, a flat armoured front pane between two
curved side panes, its two straight posts from the arch down to the base frame on the nose; the canopy one blown
transparency hinged at the back, held by its frame (the front bow against the windscreen arch, the rear bow on the
spine, the sill rails).

Run after the cockpit (q_5.py), on its output. Usage: python3 tools/cockpit_models/canopy_q_5.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'q_5'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.50, [(0.20, -0.06), (0.195, 0.02), (0.18, 0.08), (0.15, 0.13), (0.10, 0.16), (0.05, 0.175), (0.0, 0.18)]),
    (-0.40, [(0.269, -0.136), (0.259, -0.042), (0.236, 0.038), (0.2, 0.109), (0.142, 0.16), (0.071, 0.184), (0.0, 0.195)]),
    (-0.20, [(0.358, -0.224), (0.34, -0.098), (0.313, 0.008), (0.264, 0.101), (0.181, 0.161), (0.093, 0.199), (0.0, 0.222)]),
    (0.00, [(0.375, -0.253), (0.354, -0.12), (0.326, -0.01), (0.271, 0.084), (0.192, 0.153), (0.098, 0.192), (0.0, 0.216)]),
    (0.20, [(0.373, -0.256), (0.353, -0.125), (0.321, -0.016), (0.267, 0.076), (0.189, 0.145), (0.097, 0.183), (0.0, 0.203)]),
    (0.40, [(0.367, -0.256), (0.343, -0.126), (0.31, -0.021), (0.256, 0.067), (0.183, 0.134), (0.094, 0.172), (0.0, 0.191)]),
    (0.60, [(0.358, -0.259), (0.336, -0.133), (0.292, -0.032), (0.244, 0.053), (0.175, 0.118), (0.091, 0.158), (0.0, 0.178)]),
    (0.80, [(0.339, -0.272), (0.312, -0.152), (0.281, -0.056), (0.229, 0.022), (0.162, 0.08), (0.084, 0.117), (0.0, 0.135)]),
    (1.00, [(0.301, -0.296), (0.295, -0.191), (0.259, -0.102), (0.205, -0.035), (0.135, 0.005), (0.065, 0.02), (0.0, 0.026)]),
    (1.20, [(0.249, -0.24), (0.23, -0.18), (0.20, -0.14), (0.136, -0.106), (0.072, -0.102), (0.033, -0.101), (0.0, -0.095)]),
    (1.25, [(0.23, -0.26), (0.21, -0.20), (0.18, -0.16), (0.12, -0.13), (0.06, -0.125), (0.0, -0.12)]),
]
SPLIT = 0.60                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.12              # the flat front pane's edges, out from the centre line
# the flat armoured front pane (flat_pane): from the arch to the base frame, straight-sided
PANE = (SPLIT + 0.05, 1.25, FRONT_PANE_X, 0.01, 0.028)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -0.50, SPLIT, nz=14) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.25, nz=10)
    # the canopy: its rear bow, its front bow, the sill rails
    C.bow(m, sh, E, -0.48, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, -0.50, SPLIT, 0.045, 0.035, out, inner, steps=14)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.022, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.05, 1.25, 0.028, 0.022, out, inner, sides=(1, -1),
             steps=14)
    C.bow(m, sh, E, 1.235, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.25, 0.045, 0.035, out, inner, steps=14)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
