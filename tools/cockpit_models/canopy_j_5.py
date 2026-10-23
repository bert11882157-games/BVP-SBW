#!/usr/bin/env python3
"""J-5 canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The J-5 canopy (side views and photos of the type, the MiG-17's own layout): a windscreen of a flat armoured front
pane between two curved side panes, its two posts down to the base frame on the nose and its arch at the rear; the
canopy a blown teardrop sliding aft, with no bow across it, held by its frame (the front bow against the windscreen
arch, the sill rails, the tail frame where it runs out onto the spine).

Run after the cockpit (j_5.py), on its output. Usage: python3 tools/cockpit_models/canopy_j_5.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'j_5'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.90, [(0.20, -0.24), (0.195, -0.17), (0.165, -0.11), (0.12, -0.075), (0.075, -0.06), (0.0, -0.05)]),
    (-0.80, [(0.28, -0.255), (0.278, -0.163), (0.235, -0.082), (0.168, -0.033), (0.109, -0.003), (0.053, 0.01), (0.0, 0.02)]),
    (-0.60, [(0.361, -0.271), (0.343, -0.144), (0.296, -0.042), (0.232, 0.034), (0.153, 0.085), (0.082, 0.124), (0.0, 0.148)]),
    (-0.40, [(0.40, -0.292), (0.36, -0.149), (0.325, -0.039), (0.264, 0.051), (0.18, 0.112), (0.098, 0.162), (0.0, 0.191)]),
    (-0.20, [(0.418, -0.311), (0.386, -0.163), (0.344, -0.045), (0.28, 0.05), (0.197, 0.119), (0.106, 0.174), (0.0, 0.204)]),
    (0.00, [(0.422, -0.323), (0.38, -0.172), (0.345, -0.055), (0.286, 0.044), (0.204, 0.119), (0.111, 0.18), (0.0, 0.209)]),
    (0.20, [(0.42, -0.327), (0.39, -0.177), (0.343, -0.059), (0.283, 0.038), (0.203, 0.114), (0.112, 0.18), (0.0, 0.212)]),
    (0.40, [(0.412, -0.321), (0.38, -0.174), (0.333, -0.059), (0.27, 0.032), (0.194, 0.104), (0.109, 0.172), (0.0, 0.214)]),
    (0.60, [(0.386, -0.328), (0.363, -0.192), (0.315, -0.084), (0.259, 0.005), (0.183, 0.071), (0.096, 0.115), (0.0, 0.134)]),
    (0.80, [(0.33, -0.33), (0.325, -0.231), (0.285, -0.133), (0.226, -0.059), (0.149, -0.022), (0.063, -0.026), (0.0, -0.029)]),
    (0.94, [(0.27, -0.33), (0.26, -0.27), (0.21, -0.22), (0.13, -0.195), (0.0, -0.19)]),
]
SPLIT = 0.46                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.10              # the flat front pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.90, SPLIT, nz=16) + C.glass_triangles(sh, E, SPLIT + 0.045, 0.94, nz=8)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -0.88, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.012, -0.90, SPLIT, 0.04, 0.03, out, inner, steps=16)   # just up the glass: off the fuselage side
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    # the MiG-17's armoured front pane: wide, its top corners well rounded (photos of the type head-on: its round top reaches the windscreen's top frame)
    C.arched_pane(m, sh, E, 0.505, 0.94, 0.15, 0.06, 0.03, 0.02, out, inner)
    C.bow(m, sh, E, 0.925, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 0.94, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
