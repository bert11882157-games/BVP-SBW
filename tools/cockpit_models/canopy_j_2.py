#!/usr/bin/env python3
"""J-2 canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The J-2 canopy (side views and photos of the type, the MiG-15's own layout): a short windscreen of a flat armoured
front pane between two curved side panes, its two posts down to the base frame on the nose and its arch at the rear;
the canopy a blown teardrop sliding aft, with no bow across it, held by its frame (the front bow against the
windscreen arch, the sill rails, the tail frame where it runs out onto the spine).

Run after the cockpit (j_2.py), on its output. Usage: python3 tools/cockpit_models/canopy_j_2.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'j_2'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.85, [(0.13, -0.10), (0.14, -0.06), (0.12, -0.01), (0.085, 0.025), (0.045, 0.035), (0.0, 0.04)]),
    (-0.60, [(0.228, -0.097), (0.222, -0.017), (0.185, 0.047), (0.143, 0.093), (0.092, 0.125), (0.051, 0.15), (0.0, 0.165)]),
    (-0.40, [(0.31, -0.137), (0.278, -0.027), (0.249, 0.058), (0.184, 0.12), (0.126, 0.16), (0.069, 0.195), (0.0, 0.218)]),
    (-0.20, [(0.364, -0.186), (0.346, -0.058), (0.299, 0.044), (0.23, 0.118), (0.153, 0.168), (0.08, 0.203), (0.0, 0.223)]),
    (0.00, [(0.42, -0.232), (0.401, -0.084), (0.349, 0.036), (0.266, 0.12), (0.174, 0.172), (0.089, 0.205), (0.0, 0.224)]),
    (0.20, [(0.455, -0.255), (0.425, -0.097), (0.38, 0.034), (0.282, 0.119), (0.187, 0.176), (0.093, 0.206), (0.0, 0.223)]),
    (0.40, [(0.411, -0.242), (0.393, -0.097), (0.333, 0.017), (0.252, 0.097), (0.168, 0.147), (0.086, 0.181), (0.0, 0.199)]),
    (0.60, [(0.322, -0.213), (0.32, -0.101), (0.26, -0.01), (0.193, 0.049), (0.126, 0.085), (0.062, 0.103), (0.0, 0.113)]),
    (0.80, [(0.258, -0.13), (0.24, -0.09), (0.174, -0.038), (0.1, -0.018), (0.0, -0.01)]),
]
SPLIT = 0.38                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.10              # the flat front pane's edges, out from the centre line (the DCS MiG-15bis reference's)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.85, SPLIT, nz=16) + C.glass_triangles(sh, E, SPLIT + 0.045, 0.80, nz=8)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -0.83, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -0.85, SPLIT, 0.04, 0.03, out, inner, steps=16)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.045, 0.80, 0.025, 0.02, out, inner)
    C.bow(m, sh, E, 0.785, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 0.80, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
