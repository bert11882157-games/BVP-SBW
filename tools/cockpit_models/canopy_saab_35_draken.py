#!/usr/bin/env python3
"""Saab J 35 Draken canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into
smooth sections, and the real frame layout.

The Draken canopy (side views and photos of the type): a windscreen of a flat front pane between two curved side panes,
its two posts down to the base frame on the nose and its arch over the gunsight; the canopy one blown transparency
hinged at the back, held by the frame round its edge (the front bow against the windscreen arch, the sill rails, the
frame at its tail on the spine).

Run after the cockpit (saab_35_draken.py), on its output. Usage: python3 tools/cockpit_models/canopy_saab_35_draken.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'saab_35_draken'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.40, [(0.142, 0.21), (0.155, 0.258), (0.135, 0.304), (0.096, 0.333), (0.051, 0.335), (0.021, 0.329), (0.0, 0.325)]),
    (-0.20, [(0.386, -0.148), (0.36, -0.011), (0.34, 0.11), (0.317, 0.224), (0.215, 0.293), (0.107, 0.328), (0.0, 0.357)]),
    (0.00, [(0.411, -0.261), (0.391, -0.116), (0.37, 0.02), (0.351, 0.144), (0.256, 0.243), (0.132, 0.298), (0.0, 0.347)]),
    (0.20, [(0.419, -0.285), (0.388, -0.137), (0.36, -0.01), (0.333, 0.111), (0.236, 0.196), (0.128, 0.265), (0.0, 0.319)]),
    (0.40, [(0.416, -0.286), (0.385, -0.139), (0.355, -0.018), (0.299, 0.086), (0.204, 0.152), (0.109, 0.209), (0.0, 0.268)]),
    (0.60, [(0.395, -0.299), (0.365, -0.159), (0.334, -0.046), (0.264, 0.041), (0.174, 0.092), (0.095, 0.145), (0.0, 0.181)]),
    (0.80, [(0.37, -0.341), (0.36, -0.211), (0.318, -0.103), (0.237, -0.03), (0.15, 0.007), (0.077, 0.04), (0.0, 0.067)]),
    (1.00, [(0.338, -0.397), (0.334, -0.279), (0.301, -0.176), (0.203, -0.122), (0.131, -0.087), (0.064, -0.07), (0.0, -0.049)]),
    (1.15, [(0.30, -0.37), (0.29, -0.28), (0.25, -0.215), (0.17, -0.17), (0.105, -0.15), (0.05, -0.14), (0.0, -0.13)]),
    (1.30, [(0.25, -0.33), (0.23, -0.27), (0.16, -0.235), (0.09, -0.225), (0.045, -0.215), (0.0, -0.205)]),
]
SPLIT = 0.42                     # the canopy's front edge, against the windscreen arch over the gunsight
FRONT_PANE_X = 0.13              # the flat front pane's edges, out from the centre line
# the flat front pane (flat_pane): from the arch to the base frame, straight-sided
PANE = (SPLIT + 0.05, 1.30, FRONT_PANE_X, 0.01, 0.028)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -0.40, SPLIT, nz=14) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.30, nz=18)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -0.38, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, -0.40, SPLIT, 0.045, 0.035, out, inner, steps=14)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.022, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.05, 1.30, 0.028, 0.022, out, inner, sides=(1, -1),
             steps=16)
    C.bow(m, sh, E, 1.285, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.30, 0.045, 0.035, out, inner, steps=28)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
