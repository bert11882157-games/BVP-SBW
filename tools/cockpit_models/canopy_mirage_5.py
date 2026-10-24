#!/usr/bin/env python3
"""Mirage 5 canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout (the Mirage III's).

The Mirage III / 5 canopy (side views and photos of the type): a windscreen of a flat front pane between two curved
side panes, its two posts down to the base frame on the nose and its arch over the gunsight; the canopy one
transparency hinged at the back, held by the frame round its edge (the front bow against the windscreen arch, the
sill rails, the frame at its tail on the spine).

Run after the cockpit (mirage_5.py), on its output. Usage: python3 tools/cockpit_models/canopy_mirage_5.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'mirage_5'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.60, [(0.217, 0.102), (0.223, 0.177), (0.202, 0.246), (0.167, 0.303), (0.1, 0.322), (0.044, 0.321), (0.0, 0.323)]),
    (-0.40, [(0.383, -0.144), (0.36, -0.01), (0.34, 0.11), (0.322, 0.229), (0.217, 0.297), (0.104, 0.319), (0.0, 0.326)]),
    (-0.20, [(0.437, -0.232), (0.408, -0.078), (0.385, 0.06), (0.365, 0.193), (0.255, 0.281), (0.123, 0.313), (0.0, 0.321)]),
    (0.00, [(0.446, -0.241), (0.422, -0.084), (0.4, 0.051), (0.357, 0.182), (0.252, 0.273), (0.123, 0.305), (0.0, 0.313)]),
    (0.20, [(0.44, -0.226), (0.419, -0.071), (0.39, 0.061), (0.331, 0.178), (0.235, 0.263), (0.114, 0.293), (0.0, 0.301)]),
    (0.40, [(0.433, -0.217), (0.416, -0.065), (0.375, 0.063), (0.308, 0.168), (0.212, 0.239), (0.107, 0.277), (0.0, 0.286)]),
    (0.60, [(0.425, -0.23), (0.398, -0.08), (0.354, 0.041), (0.294, 0.143), (0.21, 0.22), (0.105, 0.255), (0.0, 0.267)]),
    (0.80, [(0.413, -0.271), (0.386, -0.125), (0.342, -0.008), (0.285, 0.09), (0.204, 0.166), (0.103, 0.203), (0.0, 0.216)]),
    (1.00, [(0.387, -0.334), (0.376, -0.199), (0.335, -0.084), (0.271, 0.007), (0.183, 0.065), (0.092, 0.096), (0.0, 0.104)]),
    (1.20, [(0.341, -0.379), (0.342, -0.26), (0.309, -0.155), (0.241, -0.077), (0.147, -0.046), (0.067, -0.039), (0.0, -0.038)]),
    (1.30, [(0.31, -0.32), (0.30, -0.24), (0.27, -0.17), (0.18, -0.115), (0.105, -0.10), (0.05, -0.10), (0.0, -0.103)]),
    (1.40, [(0.276, -0.256), (0.25, -0.21), (0.219, -0.179), (0.118, -0.159), (0.064, -0.154), (0.025, -0.163), (0.0, -0.169)]),
]
SPLIT = 0.60                     # the canopy's front edge, against the windscreen arch over the gunsight
FRONT_PANE_X = 0.14              # the flat front pane's edges, out from the centre line
# the flat front pane (flat_pane): from the arch to the base frame, straight-sided
PANE = (SPLIT + 0.05, 1.40, FRONT_PANE_X, 0.01, 0.03)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -0.60, SPLIT, nz=14) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.40, nz=20)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -0.58, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, -0.60, SPLIT, 0.045, 0.035, out, inner, steps=14)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.022, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.05, 1.40, 0.03, 0.022, out, inner, sides=(1, -1),
             steps=14)
    C.bow(m, sh, E, 1.385, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.40, 0.045, 0.035, out, inner, steps=32)   # (fine: the sill rises to the nose)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
