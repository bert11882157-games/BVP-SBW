#!/usr/bin/env python3
"""MiG-21bis canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The MiG-21bis canopy (side views and photos of the type): a fixed windscreen with a flat armoured front pane between
two curved side panes, its posts running down to the base frame on the nose; the canopy hinged at its front, one
large blown transparency held by a frame round its edge (the front bow against the windscreen arch, the rear bow
where it meets the spine, the sill rails along both sides) with the rear-view periscope housing on top.

Run after the cockpit (mig_21bis.py), on its output. Usage: python3 tools/cockpit_models/canopy_mig_21bis.py
"""
import math, os, sys
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
# the flat armoured front pane (photos of the type head-on): a tall window with straight sides and a round arched top,
# its apex just ahead of the windscreen arch, its sides running down to the nose, in a thick frame
PANE_W = 0.17                    # its half width (bert's head-on reference: the pane nearly as tall as wide, 0.8)
PANE_TOP = 0.733                 # the apex of its arch: it reaches the windscreen's top frame (the arch at 0.715)


PANE_FOOT = 1.32                 # its frame's foot: under it the windscreen is solid metal to the base frame
# the flat armoured front pane (flat_pane): bert's head-on reference (War Thunder, 2026-10-23): an arch, its top
# fully round to the top frame, its sides straight, its foot flat (n_bot 8: small lower corners), on a metal band
PANE = (PANE_TOP, 1.42, PANE_W, PANE_W, 0.04, PANE_FOOT, 2.0, 8.0)


def side_floor(z):
    """the side panes' lower edge (the reference's side views: each side pane a triangle): metal under it, rising
    from just above the sill at the arch to the front pane's frame 0.42 m further forward"""
    t = (z - 0.735) / (1.15 - 0.735)
    return -0.24 + 0.20 * min(1.0, max(0.0, t))


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner, dark = atlas.swatch(skin), atlas.swatch((40, 42, 44)), atlas.swatch((26, 27, 28))
    # the armoured front pane is a flat slab: the windscreen made flat round it
    can, ws = C.Shape(CANOPY), C.Shape(C.flat_pane(WINDSCREEN, *PANE))
    glass = C.glass_triangles(can, E, -0.30, 0.66) + C.glass_triangles(ws, E, 0.70, 1.42)
    # the canopy's frame: rear bow, front bow, sill rails
    C.bow(m, can, E, -0.28, 0.05, 0.03, out, inner)
    C.bow(m, can, E, 0.645, 0.04, 0.022, out, inner)
    C.member(m, can, E, 0.0, -0.30, 0.66, 0.045, 0.035, out, inner)
    # the frame along the canopy's top centre line, bow to bow: the two halves of the transparency meet on it, the
    # periscope stands on it
    C.member(m, can, E, 1.0, -0.28, 0.645, 0.035, 0.022, out, inner, sides=(1,), steps=14)
    # the windscreen: its arch against the canopy, the front pane's arched frame, the base frame, the side rails
    C.bow(m, ws, E, 0.715, 0.035, 0.022, out, inner)
    ov = C.oval_pane(m, ws, E, PANE_TOP, PANE_FOOT, PANE_W, 0.04, 0.025, out, inner, n=2.0, n_bot=8.0)
    # the solid metal: under the side panes' rising lower edge, and the whole band under the front pane's foot
    def side_top(z):                                     # (up to the front pane's frame, not over it)
        return min(ws.s_at_y(z, side_floor(z)), ws.s_at_x(z, ov.env(z, 0.04 / 2 + 0.008)) - 0.004)
    C.fill(m, ws, E, 0.735, PANE_FOOT, side_top, out, inner, steps=40)
    C.fill(m, ws, E, PANE_FOOT, 1.38, lambda z: 1.0, out, inner, steps=8)
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
