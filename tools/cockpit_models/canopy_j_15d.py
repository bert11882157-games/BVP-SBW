#!/usr/bin/env python3
"""J-15D canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The J-15D canopy (side views and photos of the two-seat J-15, the Su-30 family's): the Su-27's one-piece windscreen
(no posts), held by its base frame on the nose and its arch over the HUD; one long canopy over both seats, hinged at
the back, with no bow across it: only the frame round its edge (the front bow against the windscreen arch, carrying
three mirrors, the sill rails, the frame at its tail on the spine).

Run after the cockpits (j_15d.py), on their output. Usage: python3 tools/cockpit_models/canopy_j_15d.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'j_15d'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the front seat's eye: z, then the right half from the sill up
SECTIONS = [
    (-2.80, [(0.083, -0.058), (0.106, -0.03), (0.107, 0.007), (0.086, 0.038), (0.065, 0.058), (0.042, 0.069), (0.0, 0.102)]),
    (-2.60, [(0.251, -0.096), (0.237, -0.008), (0.205, 0.062), (0.16, 0.114), (0.106, 0.145), (0.061, 0.188), (0.0, 0.222)]),
    (-2.40, [(0.318, -0.143), (0.299, -0.031), (0.271, 0.061), (0.223, 0.137), (0.153, 0.188), (0.083, 0.233), (0.0, 0.26)]),
    (-2.20, [(0.364, -0.17), (0.34, -0.042), (0.311, 0.064), (0.263, 0.156), (0.189, 0.227), (0.099, 0.272), (0.0, 0.295)]),
    (-2.00, [(0.397, -0.185), (0.371, -0.045), (0.339, 0.07), (0.287, 0.171), (0.208, 0.25), (0.11, 0.302), (0.0, 0.328)]),
    (-1.80, [(0.421, -0.193), (0.378, -0.044), (0.359, 0.077), (0.302, 0.183), (0.217, 0.264), (0.116, 0.322), (0.0, 0.353)]),
    (-1.60, [(0.427, -0.192), (0.388, -0.041), (0.358, 0.081), (0.303, 0.187), (0.221, 0.272), (0.12, 0.338), (0.0, 0.374)]),
    (-1.40, [(0.36, -0.163), (0.33, -0.04), (0.302, 0.067), (0.271, 0.167), (0.21, 0.26), (0.121, 0.342), (0.0, 0.388)]),
    (-1.20, [(0.299, -0.143), (0.277, -0.037), (0.259, 0.05), (0.241, 0.142), (0.205, 0.248), (0.122, 0.341), (0.0, 0.389)]),
    (-1.00, [(0.294, -0.148), (0.278, -0.045), (0.265, 0.045), (0.236, 0.132), (0.211, 0.249), (0.126, 0.344), (0.0, 0.389)]),
    (-0.80, [(0.36, -0.189), (0.326, -0.061), (0.305, 0.042), (0.274, 0.144), (0.218, 0.245), (0.127, 0.335), (0.0, 0.382)]),
    (-0.60, [(0.412, -0.235), (0.365, -0.088), (0.338, 0.026), (0.293, 0.131), (0.224, 0.228), (0.126, 0.307), (0.0, 0.352)]),
    (-0.40, [(0.448, -0.278), (0.386, -0.118), (0.358, 0.003), (0.306, 0.112), (0.227, 0.204), (0.125, 0.275), (0.0, 0.319)]),
    (-0.20, [(0.472, -0.321), (0.429, -0.154), (0.386, -0.022), (0.32, 0.088), (0.231, 0.176), (0.126, 0.246), (0.0, 0.283)]),
    (0.00, [(0.496, -0.365), (0.461, -0.19), (0.404, -0.052), (0.332, 0.062), (0.234, 0.145), (0.126, 0.211), (0.0, 0.244)]),
    (0.20, [(0.512, -0.41), (0.457, -0.228), (0.415, -0.087), (0.333, 0.025), (0.228, 0.1), (0.122, 0.163), (0.0, 0.194)]),
    (0.40, [(0.474, -0.451), (0.428, -0.282), (0.388, -0.15), (0.294, -0.058), (0.203, 0.01), (0.106, 0.059), (0.0, 0.085)]),
    (0.60, [(0.384, -0.467), (0.361, -0.332), (0.318, -0.223), (0.245, -0.144), (0.165, -0.092), (0.086, -0.054), (0.0, -0.032)]),
    (0.80, [(0.305, -0.489), (0.304, -0.382), (0.257, -0.294), (0.202, -0.228), (0.128, -0.196), (0.062, -0.181), (0.0, -0.175)]),
    (1.00, [(0.214, -0.429), (0.18, -0.367), (0.119, -0.336), (0.065, -0.33), (0.025, -0.341), (0.0, -0.347)]),
]
SPLIT = 0.45                     # the canopy's front edge, against the windscreen arch over the HUD


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    mback, mglass = atlas.swatch((34, 36, 38)), atlas.swatch((150, 170, 180))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -2.80, SPLIT, nz=36) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.00, nz=8)
    # the canopy: its tail frame, its front bow with the three mirrors, the sill rails
    C.bow(m, sh, E, -2.78, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.01, 0.045, 0.03, out, inner)
    C.mirrors(m, sh, E, SPLIT - 0.01, 0.04, mback, mglass, at=(1.0, 0.68))   # (the side pair higher: inside the glass)
    C.member(m, sh, E, 0.0, -2.80, SPLIT, 0.05, 0.035, out, inner, steps=36)
    # the windscreen, one piece (no posts): the arch, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.02, out, inner)
    C.bow(m, sh, E, 0.98, 0.04, 0.025, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.00, 0.05, 0.035, out, inner)
    # one smooth shell under the windscreen ahead of the coaming, inside the glass, its top under the sight line over
    # the coaming's front edge (0.76 m ahead, 0.32 m under the eye): the nose under the glass one dark deck
    C.shell(m, sh, E, 0.74, 0.96, atlas.swatch((24, 25, 26)), sight=lambda z: -0.32 / 0.76 * z - 0.012, inset=0.045, nz=4)
    return m, glass


def main(argv):
    C.run(VID, SOURCE_BONE, build, argv)


if __name__ == '__main__':
    main(sys.argv[1:])
