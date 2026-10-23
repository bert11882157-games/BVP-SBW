#!/usr/bin/env python3
"""Saab J 29 Tunnan canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into
smooth sections, and the real frame layout.

The Tunnan canopy (side views and photos of the type): a windscreen of a flat armoured front pane between two curved
side panes, its two posts down to the base frame on the nose and its arch over the gunsight; the canopy one blown
bubble sliding back, held by the frame round its edge (the front bow against the windscreen arch, the sill rails, the
frame at its tail).

Run after the cockpit (saab_29_tunnan.py), on its output. Usage: python3 tools/cockpit_models/canopy_saab_29_tunnan.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'saab_29_tunnan'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-1.00, [(0.204, -0.031), (0.19, 0.0), (0.173, 0.029), (0.104, 0.052), (0.055, 0.054), (0.022, 0.046), (0.0, 0.038)]),
    (-0.80, [(0.247, -0.109), (0.248, -0.022), (0.204, 0.048), (0.141, 0.088), (0.089, 0.11), (0.043, 0.12), (0.0, 0.131)]),
    (-0.60, [(0.309, -0.135), (0.298, -0.027), (0.248, 0.059), (0.185, 0.116), (0.128, 0.159), (0.069, 0.195), (0.0, 0.216)]),
    (-0.40, [(0.367, -0.168), (0.327, -0.038), (0.293, 0.062), (0.233, 0.139), (0.16, 0.193), (0.09, 0.249), (0.0, 0.281)]),
    (-0.20, [(0.411, -0.203), (0.375, -0.057), (0.335, 0.057), (0.266, 0.145), (0.182, 0.205), (0.1, 0.263), (0.0, 0.308)]),
    (0.00, [(0.419, -0.226), (0.38, -0.09), (0.341, 0.039), (0.283, 0.136), (0.198, 0.205), (0.109, 0.268), (0.0, 0.3)]),
    (0.20, [(0.394, -0.229), (0.358, -0.089), (0.327, 0.022), (0.278, 0.12), (0.203, 0.199), (0.109, 0.256), (0.0, 0.284)]),
    (0.40, [(0.365, -0.227), (0.33, -0.097), (0.307, 0.006), (0.271, 0.105), (0.206, 0.192), (0.108, 0.241), (0.0, 0.259)]),
    (0.60, [(0.338, -0.236), (0.317, -0.117), (0.286, -0.02), (0.247, 0.069), (0.187, 0.147), (0.095, 0.185), (0.0, 0.198)]),
    (0.80, [(0.309, -0.276), (0.301, -0.168), (0.258, -0.079), (0.214, -0.005), (0.15, 0.048), (0.072, 0.064), (0.0, 0.068)]),
    (1.00, [(0.30, -0.28), (0.28, -0.20), (0.255, -0.158), (0.186, -0.102), (0.112, -0.081), (0.049, -0.083), (0.0, -0.089)]),
    (1.20, [(0.259, -0.357), (0.24, -0.31), (0.212, -0.283), (0.144, -0.244), (0.079, -0.237), (0.033, -0.244), (0.0, -0.255)]),
]
SPLIT = 0.50                     # the canopy's front edge, against the windscreen arch over the gunsight
FRONT_PANE_X = 0.12              # the flat front pane's edges, out from the centre line
# the flat armoured front pane (flat_pane): from the arch to the base frame, straight-sided
PANE = (SPLIT + 0.05, 1.20, FRONT_PANE_X, 0.01, 0.03)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -1.00, SPLIT, nz=18) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.20, nz=14)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -0.98, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, -1.00, SPLIT, 0.045, 0.035, out, inner, steps=18)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.022, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.05, 1.20, 0.03, 0.022, out, inner, sides=(1, -1),
             steps=14)
    C.bow(m, sh, E, 1.185, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.20, 0.045, 0.035, out, inner, steps=24)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
