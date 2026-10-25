#!/usr/bin/env python3
"""Su-57 canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The Su-57 canopy (side views and photos of the type, the T-50 prototypes): a long one-piece windscreen down to the
nose, one slanted bow at its joint with the canopy just behind the HUD; the canopy one large blown transparency with
no bow or hoop across it, held by the frame round its edge (the front bow, the sill rails, the frame at its tail on the
spine).

Run after the cockpit (su_57.py), on its output. Usage: python3 tools/cockpit_models/canopy_su_57.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'su_57'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.88, [(0.30, -0.02), (0.30, 0.05), (0.26, 0.14), (0.18, 0.20), (0.10, 0.23), (0.05, 0.255), (0.0, 0.275)]),
    (-0.80, [(0.377, -0.092), (0.373, 0.039), (0.315, 0.148), (0.215, 0.207), (0.128, 0.23), (0.068, 0.265), (0.0, 0.297)]),
    (-0.60, [(0.484, -0.221), (0.479, -0.052), (0.432, 0.095), (0.296, 0.177), (0.183, 0.218), (0.094, 0.256), (0.0, 0.295)]),
    (-0.40, [(0.54, -0.304), (0.528, -0.115), (0.477, 0.047), (0.33, 0.14), (0.214, 0.199), (0.11, 0.245), (0.0, 0.29)]),
    (-0.20, [(0.568, -0.362), (0.548, -0.163), (0.478, 0.001), (0.352, 0.109), (0.234, 0.178), (0.122, 0.234), (0.0, 0.283)]),
    (0.00, [(0.579, -0.408), (0.537, -0.203), (0.488, -0.037), (0.371, 0.08), (0.246, 0.153), (0.13, 0.216), (0.0, 0.272)]),
    (0.20, [(0.578, -0.446), (0.533, -0.242), (0.473, -0.080), (0.370, 0.040), (0.255, 0.126), (0.138, 0.200), (0.0, 0.256)]),
    (0.40, [(0.577, -0.484), (0.529, -0.28), (0.458, -0.123), (0.368, 0.001), (0.264, 0.098), (0.146, 0.185), (0.0, 0.239)]),
    (0.60, [(0.56, -0.511), (0.5, -0.312), (0.439, -0.162), (0.358, -0.04), (0.262, 0.062), (0.148, 0.156), (0.0, 0.206)]),
    (0.80, [(0.511, -0.548), (0.463, -0.367), (0.416, -0.225), (0.327, -0.118), (0.227, -0.041), (0.121, 0.021), (0.0, 0.052)]),
    (1.00, [(0.44, -0.50), (0.40, -0.36), (0.356, -0.289), (0.259, -0.211), (0.162, -0.172), (0.072, -0.172), (0.0, -0.168)]),
    (1.12, [(0.33, -0.45), (0.30, -0.37), (0.25, -0.32), (0.18, -0.29), (0.12, -0.28), (0.06, -0.275), (0.0, -0.275)]),
]
SPLIT = 0.58                     # the canopy's front edge, against the windscreen's bow just behind the HUD


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.88, SPLIT, nz=22) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.12, nz=10)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -0.86, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.01, 0.045, 0.035, out, inner)
    C.member(m, sh, E, 0.0, -0.88, SPLIT, 0.05, 0.035, out, inner, steps=22)
    # the windscreen, one piece: the bow at the joint, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.025, 0.035, 0.02, out, inner)
    C.bow(m, sh, E, 1.10, 0.04, 0.025, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.12, 0.05, 0.035, out, inner)
    # one smooth dark shell under the windscreen ahead of the coaming, its top under the sight line over the
    # coaming's front edge (0.98 m ahead, 0.30 m under the eye)
    C.shell(m, sh, E, 0.95, 1.10, atlas.swatch((24, 25, 26)), sight=lambda z: -0.30 / 0.98 * z - 0.012, inset=0.045)
    return m, glass


def main(argv):
    # the old canopy's opening strut standing inside the cockpit on the left (the real aircraft has none)
    C.run(VID, SOURCE_BONE, build, argv, boxes=[((0.38, -0.44, 0.10), (0.50, 0.30, 0.65))])


if __name__ == '__main__':
    main(sys.argv[1:])
