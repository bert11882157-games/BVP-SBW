#!/usr/bin/env python3
"""J-11A canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout (the Su-27SK's, as canopy_su_27.py).

The J-11A canopy (side views and photos of the type, the Su-27's): a fixed one-piece windscreen, no posts, held by its
base frame on the nose and its arch at the rear over the HUD; the canopy one large blown transparency hinged at the
back, with one hoop across it just behind the headrest (the glass running on behind it to the spine), held by the
frame round its edge (the thick front bow against the windscreen arch, carrying three mirrors, the sill rails, the
frame at its tail on the spine).

Run after the cockpit (j_11a.py), on its output. Usage: python3 tools/cockpit_models/canopy_j_11a.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'j_11a'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-1.60, [(0.197, 0.041), (0.17, 0.099), (0.102, 0.122), (0.051, 0.12), (0.019, 0.111), (0.0, 0.106)]),
    (-1.40, [(0.321, 0.025), (0.258, 0.115), (0.171, 0.16), (0.088, 0.161), (0.04, 0.163), (0.0, 0.165)]),
    (-1.20, [(0.339, 0.019), (0.289, 0.119), (0.211, 0.182), (0.127, 0.207), (0.059, 0.215), (0.0, 0.217)]),
    (-1.00, [(0.317, -0.096), (0.308, 0.016), (0.271, 0.108), (0.217, 0.181), (0.15, 0.231), (0.073, 0.251), (0.0, 0.259)]),
    (-0.80, [(0.343, -0.114), (0.326, 0.007), (0.28, 0.104), (0.237, 0.186), (0.173, 0.253), (0.087, 0.284), (0.0, 0.295)]),
    (-0.60, [(0.387, -0.141), (0.361, -0.005), (0.315, 0.103), (0.264, 0.195), (0.193, 0.27), (0.1, 0.313), (0.0, 0.33)]),
    (-0.40, [(0.43, -0.17), (0.398, -0.017), (0.346, 0.101), (0.285, 0.199), (0.208, 0.279), (0.112, 0.337), (0.0, 0.365)]),
    (-0.20, [(0.46, -0.203), (0.405, -0.039), (0.365, 0.085), (0.3, 0.188), (0.216, 0.269), (0.117, 0.331), (0.0, 0.373)]),
    (0.00, [(0.506, -0.258), (0.47, -0.079), (0.406, 0.061), (0.322, 0.167), (0.223, 0.242), (0.119, 0.301), (0.0, 0.336)]),
    (0.20, [(0.518, -0.304), (0.473, -0.12), (0.425, 0.025), (0.321, 0.125), (0.219, 0.196), (0.115, 0.249), (0.0, 0.277)]),
    (0.40, [(0.491, -0.333), (0.45, -0.17), (0.39, -0.03), (0.3, 0.071), (0.203, 0.136), (0.104, 0.178), (0.0, 0.203)]),
    (0.60, [(0.402, -0.332), (0.392, -0.191), (0.333, -0.076), (0.266, 0.013), (0.174, 0.061), (0.088, 0.093), (0.0, 0.11)]),
    (0.80, [(0.315, -0.325), (0.319, -0.216), (0.275, -0.121), (0.204, -0.058), (0.128, -0.028), (0.06, -0.017), (0.0, -0.012)]),
    (1.00, [(0.156, -0.269), (0.17, -0.215), (0.143, -0.166), (0.102, -0.136), (0.057, -0.13), (0.024, -0.132), (0.0, -0.138)]),
]
SPLIT = 0.50                     # the canopy's front edge, against the windscreen arch over the HUD
HOOP_Z = -0.62                  # the canopy's hoop just behind the headrest (the glass runs on behind it)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    mback, mglass = atlas.swatch((34, 36, 38)), atlas.swatch((150, 170, 180))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -1.60, SPLIT, nz=24) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.00, nz=8)
    # the canopy: its tail frame, the hoop behind the headrest, the thick front bow with its three mirrors, the sill rails
    C.bow(m, sh, E, -1.58, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, HOOP_Z, 0.05, 0.035, out, inner)
    C.bow(m, sh, E, SPLIT - 0.01, 0.055, 0.04, out, inner)
    C.mirrors(m, sh, E, SPLIT - 0.01, 0.04, mback, mglass)
    C.member(m, sh, E, 0.0, -1.60, SPLIT, 0.05, 0.035, out, inner, steps=24)
    # the windscreen, one piece (no posts): the arch, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.02, out, inner)
    C.bow(m, sh, E, 0.98, 0.04, 0.025, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.00, 0.05, 0.035, out, inner)
    # one smooth shell under the windscreen ahead of the coaming, inside the glass, its top under the sight line over
    # the coaming's front edge (0.82 m ahead, 0.17 m under the eye): the nose under the glass one dark deck
    C.shell(m, sh, E, 0.80, 0.98, atlas.swatch((24, 25, 26)), sight=lambda z: -0.17 / 0.82 * z - 0.012, inset=0.045)
    return m, glass


def main(argv):
    C.run(VID, SOURCE_BONE, build, argv)


if __name__ == '__main__':
    main(sys.argv[1:])
