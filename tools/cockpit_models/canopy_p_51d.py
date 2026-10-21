#!/usr/bin/env python3
"""P-51D canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The P-51D canopy (side views and photos of the type): a windscreen of a flat bulletproof front pane between two side
panes, its two posts down to the base frame on the nose and its arch at the rear; the canopy a blown bubble sliding
aft on rails, with no bow across it, held by its frame (the front bow against the windscreen arch, the sill rails,
the tail frame where it meets the spine).

Run after the cockpit (p_51d.py), on its output. Usage: python3 tools/cockpit_models/canopy_p_51d.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'p_51d'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-1.25, [(0.18, -0.19), (0.19, -0.13), (0.165, -0.07), (0.12, -0.035), (0.07, -0.02), (0.0, -0.02)]),
    (-1.00, [(0.291, -0.19), (0.289, -0.099), (0.239, -0.016), (0.175, 0.037), (0.117, 0.072), (0.058, 0.091), (0.0, 0.101)]),
    (-0.80, [(0.343, -0.191), (0.311, -0.069), (0.25, 0.018), (0.197, 0.083), (0.135, 0.13), (0.075, 0.172), (0.0, 0.2)]),
    (-0.60, [(0.377, -0.187), (0.335, -0.045), (0.279, 0.052), (0.218, 0.122), (0.156, 0.18), (0.09, 0.243), (0.0, 0.28)]),
    (-0.40, [(0.397, -0.193), (0.35, -0.036), (0.285, 0.064), (0.233, 0.143), (0.171, 0.21), (0.099, 0.28), (0.0, 0.323)]),
    (-0.20, [(0.405, -0.205), (0.361, -0.045), (0.307, 0.061), (0.247, 0.143), (0.181, 0.215), (0.103, 0.283), (0.0, 0.319)]),
    (0.00, [(0.407, -0.221), (0.368, -0.059), (0.317, 0.05), (0.257, 0.137), (0.189, 0.211), (0.106, 0.277), (0.0, 0.311)]),
    (0.20, [(0.404, -0.238), (0.358, -0.078), (0.319, 0.03), (0.263, 0.121), (0.191, 0.194), (0.103, 0.249), (0.0, 0.285)]),
    (0.40, [(0.387, -0.251), (0.364, -0.109), (0.316, -0.0), (0.259, 0.088), (0.18, 0.15), (0.097, 0.201), (0.0, 0.224)]),
    (0.60, [(0.351, -0.262), (0.34, -0.139), (0.306, -0.035), (0.234, 0.04), (0.147, 0.08), (0.076, 0.11), (0.0, 0.135)]),
    (0.80, [(0.284, -0.23), (0.27, -0.16), (0.249, -0.106), (0.173, -0.065), (0.081, -0.068), (0.0, -0.066)]),
    (0.86, [(0.25, -0.23), (0.23, -0.18), (0.19, -0.14), (0.12, -0.11), (0.0, -0.105)]),
]
SPLIT = 0.36                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.09              # the flat bulletproof pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -1.25, SPLIT, nz=20) + C.glass_triangles(sh, E, SPLIT + 0.045, 0.86, nz=8)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -1.23, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -1.25, SPLIT, 0.04, 0.03, out, inner, steps=20)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), 0.55, 0.86, 0.025, 0.02, out, inner)
    C.bow(m, sh, E, 0.845, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 0.86, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], colour=(150, 153, 156))   # natural aluminium (the spine sample caught a white stripe)
