#!/usr/bin/env python3
"""F/A-18E canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The F/A-18E canopy (side views and photos of the type): a fixed one-piece windscreen with no posts, held by its base
frame on the nose and the arch at its rear over the HUD; the canopy one blown transparency with no bow across it,
hinged at the back, held by the frame round its edge (the front bow against the windscreen arch, the sill rails along
both sides, the frame at its tail on the spine).

Run after the cockpit (fa_18e.py), on its output. Usage: python3 tools/cockpit_models/canopy_fa_18e.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'fa_18e'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-1.20, [(0.20, -0.24), (0.214, -0.209), (0.18, -0.146), (0.129, -0.109), (0.072, -0.101), (0.031, -0.104), (0.0, -0.108)]),
    (-1.00, [(0.306, -0.285), (0.297, -0.182), (0.261, -0.093), (0.217, -0.018), (0.159, 0.04), (0.076, 0.058), (0.0, 0.06)]),
    (-0.80, [(0.315, -0.32), (0.289, -0.148), (0.262, -0.059), (0.24, 0.031), (0.206, 0.139), (0.113, 0.204), (0.0, 0.225)]),
    (-0.60, [(0.30, -0.358), (0.302, -0.26), (0.28, -0.153), (0.264, -0.064), (0.24, 0.026), (0.217, 0.148), (0.131, 0.25), (0.0, 0.28)]),
    (-0.40, [(0.29, -0.393), (0.288, -0.28), (0.272, -0.178), (0.263, -0.09), (0.249, 0.006), (0.217, 0.122), (0.137, 0.242), (0.0, 0.271)]),
    (-0.20, [(0.31, -0.426), (0.313, -0.318), (0.298, -0.208), (0.288, -0.112), (0.27, -0.008), (0.231, 0.112), (0.142, 0.229), (0.0, 0.253)]),
    (0.00, [(0.37, -0.456), (0.369, -0.369), (0.349, -0.239), (0.324, -0.129), (0.299, -0.016), (0.256, 0.118), (0.144, 0.209), (0.0, 0.228)]),
    (0.20, [(0.40, -0.48), (0.405, -0.411), (0.375, -0.27), (0.347, -0.148), (0.306, -0.037), (0.262, 0.096), (0.143, 0.18), (0.0, 0.201)]),
    (0.40, [(0.406, -0.495), (0.41, -0.438), (0.377, -0.29), (0.35, -0.171), (0.315, -0.055), (0.258, 0.071), (0.137, 0.139), (0.0, 0.162)]),
    (0.60, [(0.392, -0.501), (0.4, -0.453), (0.365, -0.309), (0.335, -0.196), (0.299, -0.086), (0.24, 0.027), (0.128, 0.091), (0.0, 0.114)]),
    (0.80, [(0.345, -0.48), (0.31, -0.325), (0.283, -0.229), (0.257, -0.133), (0.204, -0.039), (0.111, 0.022), (0.0, 0.045)]),
    (1.00, [(0.227, -0.451), (0.212, -0.371), (0.187, -0.307), (0.169, -0.244), (0.134, -0.183), (0.07, -0.152), (0.0, -0.15)]),
    (1.12, [(0.15, -0.44), (0.13, -0.36), (0.10, -0.28), (0.05, -0.24), (0.0, -0.235)]),
]
SPLIT = 0.40                     # the canopy's front edge, against the windscreen arch over the HUD


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -1.20, SPLIT, nz=18) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.12, nz=8)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -1.18, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -1.20, SPLIT, 0.05, 0.035, out, inner, steps=18)
    # the windscreen: the arch, the base frame on the nose and its side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.02, out, inner)
    C.bow(m, sh, E, 1.105, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.12, 0.05, 0.035, out, inner)
    return m, glass


def main(argv):
    C.run(VID, SOURCE_BONE, build, argv, colour=(150, 154, 158))      # the fuselage grey (the spine behind is the black anti-glare)


if __name__ == '__main__':
    main(sys.argv[1:])
