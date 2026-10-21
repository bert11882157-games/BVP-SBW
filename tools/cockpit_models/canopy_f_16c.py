#!/usr/bin/env python3
"""F-16C canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The F-16 canopy (side views and photos of the type): a one-piece bubble with no windscreen arch or front bow at all,
the forward transparency running clear from the nose to the single transverse bow just behind the seat's headrest,
where the short aft transparency joins it; the canopy's frame round its edge (the sill rails along both sides, the
rear frame where it meets the spine) and a slim edge at the nose where the glass seals against the skin.

Run after the cockpit (f_16c.py), on its output. Usage: python3 tools/cockpit_models/canopy_f_16c.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'f_16c'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's sections, metres from the eye: z, then the right half from the sill up over the top
CANOPY = [
    (-1.50, [(0.14, -0.19), (0.145, -0.15), (0.124, -0.11), (0.087, -0.09), (0.045, -0.086), (0.0, -0.085)]),
    (-1.20, [(0.28, -0.17), (0.281, -0.09), (0.236, -0.008), (0.178, 0.047), (0.112, 0.072), (0.05, 0.077), (0.0, 0.077)]),
    (-0.90, [(0.348, -0.198), (0.336, -0.075), (0.297, 0.026), (0.246, 0.111), (0.177, 0.172), (0.086, 0.199), (0.0, 0.203)]),
    (-0.60, [(0.39, -0.234), (0.36, -0.095), (0.327, 0.016), (0.291, 0.123), (0.227, 0.218), (0.114, 0.262), (0.0, 0.269)]),
    (-0.30, [(0.404, -0.277), (0.37, -0.133), (0.335, -0.019), (0.295, 0.088), (0.241, 0.198), (0.128, 0.265), (0.0, 0.283)]),
    (0.00, [(0.404, -0.319), (0.362, -0.17), (0.334, -0.062), (0.296, 0.046), (0.228, 0.145), (0.125, 0.216), (0.0, 0.25)]),
    (0.30, [(0.405, -0.358), (0.368, -0.213), (0.329, -0.1), (0.276, -0.004), (0.203, 0.076), (0.113, 0.145), (0.0, 0.182)]),
    (0.60, [(0.371, -0.389), (0.356, -0.259), (0.308, -0.153), (0.244, -0.072), (0.16, -0.027), (0.083, 0.01), (0.0, 0.034)]),
    (0.85, [(0.325, -0.39), (0.31, -0.29), (0.265, -0.195), (0.18, -0.148), (0.10, -0.128), (0.05, -0.115), (0.0, -0.10)]),
    (1.03, [(0.262, -0.32), (0.21, -0.24), (0.11, -0.214), (0.05, -0.212), (0.0, -0.212)]),
]
BOW_Z = -0.52                    # the one transverse bow, just behind the headrest


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    can = C.Shape(CANOPY)
    glass = C.glass_triangles(can, E, -1.50, 1.03, nz=24)
    C.bow(m, can, E, -1.48, 0.04, 0.03, out, inner)              # the rear frame against the spine
    C.bow(m, can, E, BOW_Z, 0.045, 0.035, out, inner)            # the bow behind the headrest
    C.bow(m, can, E, 1.015, 0.03, 0.015, out, inner, s0=0.0, s1=1.0)   # the slim edge at the nose
    C.member(m, can, E, 0.0, -1.50, 1.03, 0.05, 0.035, out, inner, steps=24)   # the sill rails
    return m, glass


def main(argv):
    C.run(VID, SOURCE_BONE, build, argv)


if __name__ == '__main__':
    main(sys.argv[1:])
