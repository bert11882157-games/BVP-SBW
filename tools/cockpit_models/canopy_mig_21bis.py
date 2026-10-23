#!/usr/bin/env python3
"""MiG-21bis canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The MiG-21bis canopy (side views and photos of the type): a fixed windscreen with a flat armoured front pane between
two curved side panes, its posts running down to the base frame on the nose; the canopy hinged at its front, one
large blown transparency held by a frame round its edge (the front bow against the windscreen arch, the rear bow
where it meets the spine, the sill rails along both sides) with the rear-view periscope housing on top.

Run after the cockpit (mig_21bis.py), on its output. Usage: python3 tools/cockpit_models/canopy_mig_21bis.py
"""
import os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'mig_21bis'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's sections, metres from the eye: z, then the right half from the sill up over the top
CANOPY = [   # lowered to the DCS MiG-21bis reference's line: flush with the spine behind it, falling gently to the joint
    (-0.30, [(0.36, -0.15), (0.34, 0.034), (0.31, 0.149), (0.22, 0.219), (0.1, 0.23), (0.0, 0.232)]),     # (the spine's top: 0.226)
    (-0.10, [(0.45, -0.3), (0.425, -0.067), (0.4, 0.119), (0.3, 0.217), (0.14, 0.234), (0.0, 0.236)]),
    (0.20, [(0.456, -0.28), (0.432, -0.129), (0.38, 0.106), (0.26, 0.195), (0.12, 0.219), (0.0, 0.228)]),
    (0.50, [(0.455, -0.3), (0.42, -0.191), (0.34, 0.018), (0.24, 0.109), (0.12, 0.168), (0.0, 0.2)]),
    (0.66, [(0.43, -0.29), (0.385, -0.155), (0.29, -0.01), (0.17, 0.089), (0.08, 0.143), (0.0, 0.17)]),
]
WINDSCREEN = [
    (0.70, [(0.41, -0.3), (0.37, -0.142), (0.28, -0.021), (0.16, 0.081), (0.07, 0.136), (0.0, 0.155)]),
    (1.00, [(0.34, -0.24), (0.30, -0.10), (0.23, -0.02), (0.12, 0.00), (0.0, 0.00)]),
    (1.25, [(0.30, -0.25), (0.27, -0.15), (0.20, -0.12), (0.10, -0.12), (0.0, -0.12)]),
    (1.42, [(0.26, -0.26), (0.24, -0.19), (0.17, -0.18), (0.10, -0.18), (0.0, -0.18)]),
]
FRONT_PANE_S = 0.80              # the flat front pane's edges (x about 0.10) across the windscreen's sections


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner, dark = atlas.swatch(skin), atlas.swatch((40, 42, 44)), atlas.swatch((26, 27, 28))
    can, ws = C.Shape(CANOPY), C.Shape(WINDSCREEN)
    glass = C.glass_triangles(can, E, -0.30, 0.66) + C.glass_triangles(ws, E, 0.70, 1.42)
    # the canopy's frame: rear bow, front bow, sill rails
    C.bow(m, can, E, -0.28, 0.05, 0.03, out, inner)
    C.bow(m, can, E, 0.645, 0.04, 0.022, out, inner)
    C.member(m, can, E, 0.0, -0.30, 0.66, 0.045, 0.035, out, inner)
    # the windscreen: its arch against the canopy, the front pane's two posts, the base frame, the side rails
    C.bow(m, ws, E, 0.715, 0.035, 0.022, out, inner)
    C.member(m, ws, E, FRONT_PANE_S, 0.95, 1.42, 0.03, 0.025, out, inner)
    C.bow(m, ws, E, 1.40, 0.04, 0.025, out, inner)
    C.member(m, ws, E, 0.0, 0.70, 1.42, 0.045, 0.035, out, inner)
    # the periscope housing on the canopy's top
    top = can.point(0.12, 1.0) + E
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    m.box(top + np.array([0, 0.0, 0]), (X, Y, Z), (0.028, 0.022, 0.06), out)          # its foot sunk 22 mm through the glass
    m.box(top + np.array([0, 0.006, -0.0615]), (X, Y, Z), (0.02, 0.014, 0.002), dark)
    return m, glass


def main(argv):
    C.run(VID, SOURCE_BONE, build, argv)


if __name__ == '__main__':
    main(sys.argv[1:])
