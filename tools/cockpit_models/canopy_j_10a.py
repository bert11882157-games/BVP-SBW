#!/usr/bin/env python3
"""J-10A canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout; the model's dark windscreen fairing under the glass cleared.

The J-10A canopy (side views and photos of the type): a one-piece windscreen falling to the nose with no frame across
it, joined to the canopy by one arch; the canopy one large blown transparency, held by the frame round its edge (the
arch at the joint, the sill rails, the frame at its tail on the spine).

Run after the cockpit (j_10a.py), on its output. Usage: python3 tools/cockpit_models/canopy_j_10a.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'j_10a'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-1.20, [(0.149, -0.064), (0.161, -0.012), (0.127, 0.032), (0.083, 0.054), (0.046, 0.058), (0.022, 0.064), (0.0, 0.072)]),
    (-1.00, [(0.356, -0.227), (0.351, -0.103), (0.309, 0.003), (0.236, 0.078), (0.146, 0.111), (0.074, 0.137), (0.0, 0.16)]),
    (-0.80, [(0.399, -0.283), (0.361, -0.141), (0.339, -0.027), (0.278, 0.068), (0.189, 0.128), (0.102, 0.184), (0.0, 0.225)]),
    (-0.60, [(0.418, -0.3), (0.376, -0.152), (0.348, -0.034), (0.294, 0.069), (0.212, 0.149), (0.12, 0.226), (0.0, 0.278)]),
    (-0.40, [(0.426, -0.304), (0.393, -0.153), (0.355, -0.032), (0.301, 0.073), (0.222, 0.163), (0.126, 0.244), (0.0, 0.301)]),
    (-0.20, [(0.422, -0.301), (0.37, -0.15), (0.345, -0.034), (0.298, 0.073), (0.224, 0.166), (0.128, 0.252), (0.0, 0.303)]),
    (0.00, [(0.434, -0.305), (0.391, -0.151), (0.35, -0.032), (0.3, 0.075), (0.231, 0.175), (0.131, 0.261), (0.0, 0.301)]),
    (0.20, [(0.433, -0.308), (0.381, -0.154), (0.35, -0.035), (0.304, 0.074), (0.234, 0.176), (0.131, 0.256), (0.0, 0.297)]),
    (0.40, [(0.441, -0.323), (0.395, -0.167), (0.352, -0.046), (0.303, 0.062), (0.238, 0.169), (0.133, 0.25), (0.0, 0.29)]),
    (0.60, [(0.441, -0.356), (0.388, -0.199), (0.352, -0.079), (0.305, 0.03), (0.235, 0.133), (0.128, 0.203), (0.0, 0.24)]),
    (0.80, [(0.427, -0.406), (0.37, -0.25), (0.348, -0.136), (0.302, -0.027), (0.227, 0.066), (0.117, 0.117), (0.0, 0.136)]),
    (1.00, [(0.402, -0.453), (0.36, -0.30), (0.331, -0.198), (0.278, -0.101), (0.194, -0.034), (0.099, 0.005), (0.0, 0.019)]),
    (1.20, [(0.367, -0.42), (0.34, -0.36), (0.308, -0.274), (0.23, -0.203), (0.142, -0.171), (0.065, -0.164), (0.0, -0.163)]),
    (1.36, [(0.31, -0.43), (0.29, -0.38), (0.25, -0.33), (0.18, -0.29), (0.11, -0.275), (0.05, -0.27), (0.0, -0.27)]),
]
SPLIT = 0.66                     # the canopy's front edge, the arch at the joint with the windscreen


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -1.20, SPLIT, nz=26) + C.glass_triangles(sh, E, SPLIT + 0.04, 1.36, nz=12)
    # the canopy: its tail frame, its front bow at the joint, the sill rails
    C.bow(m, sh, E, -1.18, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.025, out, inner)
    C.member(m, sh, E, 0.0, -1.20, SPLIT, 0.045, 0.03, out, inner, steps=26)
    # the windscreen, one piece: its arch at the joint, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.022, 0.03, 0.025, out, inner)
    C.bow(m, sh, E, 1.345, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.04, 1.36, 0.04, 0.03, out, inner, steps=12)
    return m, glass


def main(argv):
    # the old dark windscreen fairing under the windscreen glass, its stepped sides included
    sh = C.Shape(SECTIONS)
    C.run(VID, SOURCE_BONE, build, argv, clear=(sh, 0.70, 1.36), boxes=[((-0.34, -0.38, 0.70), (0.34, 0.25, 1.34))])


if __name__ == '__main__':
    main(sys.argv[1:])
