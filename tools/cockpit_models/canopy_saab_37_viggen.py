#!/usr/bin/env python3
"""Saab AJ 37 Viggen canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into
smooth sections, and the real frame layout.

The Viggen canopy (side views and photos of the type): a windscreen of a flat front pane between two curved side
panes, its two posts down to the base frame on the nose and its arch over the head-up display; the canopy one
transparency hinged at the back, held by the frame round its edge (the front bow against the windscreen arch, the
sill rails, the frame at its tail on the spine).

Run after the cockpit (saab_37_viggen.py), on its output. Usage: python3 tools/cockpit_models/canopy_saab_37_viggen.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'saab_37_viggen'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.80, [(0.265, -0.023), (0.253, 0.071), (0.24, 0.15), (0.224, 0.237), (0.152, 0.285), (0.068, 0.287), (0.0, 0.291)]),
    (-0.60, [(0.382, -0.229), (0.362, -0.095), (0.35, 0.023), (0.331, 0.15), (0.242, 0.245), (0.118, 0.276), (0.0, 0.305)]),
    (-0.40, [(0.434, -0.287), (0.409, -0.134), (0.39, -0.002), (0.359, 0.133), (0.248, 0.217), (0.127, 0.268), (0.0, 0.311)]),
    (-0.20, [(0.456, -0.278), (0.425, -0.117), (0.399, 0.017), (0.34, 0.138), (0.22, 0.198), (0.117, 0.255), (0.0, 0.312)]),
    (0.00, [(0.451, -0.271), (0.43, -0.112), (0.383, 0.019), (0.306, 0.12), (0.202, 0.179), (0.107, 0.231), (0.0, 0.267)]),
    (0.20, [(0.44, -0.294), (0.428, -0.14), (0.368, -0.013), (0.291, 0.083), (0.19, 0.137), (0.097, 0.173), (0.0, 0.194)]),
    (0.40, [(0.43, -0.35), (0.419, -0.199), (0.363, -0.074), (0.291, 0.023), (0.19, 0.076), (0.094, 0.105), (0.0, 0.119)]),
    (0.60, [(0.412, -0.419), (0.4, -0.275), (0.356, -0.153), (0.289, -0.056), (0.195, 0.005), (0.095, 0.031), (0.0, 0.04)]),
    (0.80, [(0.374, -0.463), (0.363, -0.332), (0.331, -0.22), (0.269, -0.128), (0.183, -0.069), (0.088, -0.05), (0.0, -0.047)]),
    (0.92, [(0.33, -0.42), (0.31, -0.33), (0.27, -0.24), (0.21, -0.17), (0.14, -0.125), (0.07, -0.11), (0.0, -0.11)]),
    (1.03, [(0.27, -0.33), (0.23, -0.26), (0.18, -0.21), (0.12, -0.19), (0.06, -0.185), (0.03, -0.185), (0.0, -0.185)]),
]
SPLIT = 0.45                     # the canopy's front edge, against the windscreen arch over the HUD
FRONT_PANE_X = 0.14              # the flat front pane's edges, out from the centre line
# the flat front pane (flat_pane): from the arch to the base frame, straight-sided
PANE = (SPLIT + 0.05, 1.03, FRONT_PANE_X, 0.01, 0.028)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -0.80, SPLIT, nz=16) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.03, nz=16)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -0.78, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, -0.80, SPLIT, 0.045, 0.035, out, inner, steps=16)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.022, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.05, 1.03, 0.028, 0.022, out, inner, sides=(1, -1),
             steps=16)
    C.bow(m, sh, E, 1.015, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.03, 0.045, 0.035, out, inner, steps=28)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
