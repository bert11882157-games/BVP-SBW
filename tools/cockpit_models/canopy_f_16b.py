#!/usr/bin/env python3
"""F-16B canopy, made by hand: the shape from the model's old glass (glass_sections.py, on the be357476 asset),
cleaned up into smooth sections, and the real frame layout (metres from the pilot's eye; the rear seat's eye is 1.44 m
behind it).

The F-16B canopy (side views and photos of the type): a one-piece bubble over both seats with no windscreen arch or
front bow at all, the forward transparency running clear from the nose to the single transverse bow behind the rear
seat's headrest,
where the short aft transparency joins it; the canopy's frame round its edge (the sill rails along both sides, the
rear frame where it meets the spine) and a slim edge at the nose where the glass seals against the skin.

Run after the cockpit (f_16b.py), on its output. Usage: python3 tools/cockpit_models/canopy_f_16b.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'f_16b'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's sections, metres from the eye: z, then the right half from the sill up over the top
CANOPY = [
    (-2.25, [(0.166, -0.095), (0.196, -0.034), (0.186, 0.038), (0.178, 0.098), (0.165, 0.161), (0.124, 0.212), (0.067, 0.249), (0.0, 0.267)]),
    (-2.00, [(0.243, -0.206), (0.262, -0.106), (0.252, -0.009), (0.245, 0.07), (0.235, 0.169), (0.206, 0.275), (0.113, 0.34), (0.0, 0.366)]),
    (-1.75, [(0.28, -0.275), (0.305, -0.159), (0.298, -0.048), (0.281, 0.159), (0.242, 0.286), (0.13, 0.354), (0.0, 0.378)]),
    (-1.50, [(0.409, -0.213), (0.378, -0.069), (0.366, 0.054), (0.347, 0.188), (0.269, 0.306), (0.137, 0.36), (0.0, 0.374)]),
    (-1.25, [(0.425, -0.225), (0.404, -0.072), (0.384, 0.057), (0.359, 0.194), (0.269, 0.305), (0.137, 0.357), (0.0, 0.367)]),
    (-1.00, [(0.42, -0.221), (0.396, -0.069), (0.374, 0.058), (0.335, 0.181), (0.259, 0.294), (0.131, 0.344), (0.0, 0.356)]),
    (-0.75, [(0.405, -0.215), (0.37, -0.067), (0.345, 0.05), (0.305, 0.16), (0.243, 0.275), (0.126, 0.33), (0.0, 0.342)]),
    (-0.50, [(0.41, -0.222), (0.375, -0.072), (0.345, 0.046), (0.303, 0.156), (0.238, 0.264), (0.122, 0.314), (0.0, 0.325)]),
    (-0.25, [(0.425, -0.239), (0.395, -0.084), (0.36, 0.04), (0.313, 0.154), (0.241, 0.257), (0.121, 0.3), (0.0, 0.306)]),
    (0.00, [(0.414, -0.252), (0.382, -0.105), (0.337, 0.01), (0.296, 0.117), (0.23, 0.219), (0.117, 0.265), (0.0, 0.273)]),
    (0.25, [(0.364, -0.268), (0.315, -0.137), (0.293, -0.038), (0.263, 0.059), (0.208, 0.155), (0.108, 0.203), (0.0, 0.215)]),
    (0.50, [(0.345, -0.303), (0.32, -0.181), (0.281, -0.085), (0.247, 0.005), (0.19, 0.087), (0.097, 0.127), (0.0, 0.137)]),
    (0.75, [(0.333, -0.342), (0.322, -0.226), (0.277, -0.13), (0.231, -0.05), (0.165, 0.01), (0.08, 0.032), (0.0, 0.037)]),
    (1.00, [(0.298, -0.30), (0.27, -0.20), (0.199, -0.13), (0.12, -0.105), (0.053, -0.10), (0.0, -0.098)]),
    (1.20, [(0.20, -0.29), (0.16, -0.25), (0.10, -0.232), (0.05, -0.228), (0.0, -0.226)]),
]
BOW_Z = -1.96                    # the one transverse bow, just behind the rear seat's headrest
# the old canopy's two small fittings left standing over the rear panel (eye-relative boxes)
GONE = [((-0.32, 0.05, -0.76), (0.32, 0.18, -0.64))]


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    can = C.Shape(CANOPY)
    glass = C.glass_triangles(can, E, -2.25, 1.20, nz=40)
    C.bow(m, can, E, -2.23, 0.04, 0.03, out, inner)              # the rear frame against the spine
    C.bow(m, can, E, BOW_Z, 0.045, 0.035, out, inner)            # the bow behind the headrest
    C.bow(m, can, E, 1.185, 0.03, 0.015, out, inner, s0=0.0, s1=1.0)   # the slim edge at the nose
    C.member(m, can, E, 0.0, -2.25, 1.20, 0.05, 0.035, out, inner, steps=40)   # the sill rails
    return m, glass


def main(argv):
    C.run(VID, SOURCE_BONE, build, argv, colour=(46, 48, 50), boxes=GONE)   # the frames black, as on the type (reference views)


if __name__ == '__main__':
    main(sys.argv[1:])
