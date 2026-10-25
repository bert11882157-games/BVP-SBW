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


PANE_FOOT = 1.35                 # its frame's foot: under it a narrow metal band to the base frame (was 1.32)
# the flat armoured front pane (flat_pane): bert's head-on reference (War Thunder, 2026-10-23): an arch, its top
# fully round to the top frame, its sides straight, its foot flat (n_bot 8: small lower corners), on a metal band
PANE = (PANE_TOP, 1.42, PANE_W, PANE_W, 0.04, PANE_FOOT, 2.0, 8.0)
# the armoured front pane's glass, exactly (2026-10-23 trace from 71 windscreen photos and bert's War Thunder shots):
# flat, raked 65 degrees, a tall egg 245 mm wide and 600 mm up the slope with a 184 mm straight foot; seen head-on the
# rake shortens it to the round arch. mm in the pane's plane, origin the middle of its foot, +X to the pilot's right
OUTLINE = [(0, 0), (92, 0), (103, 4), (107, 15), (110, 42), (116, 90), (118, 150), (122, 210), (122, 270), (120, 324),
           (116, 372), (106, 420), (97, 462), (84, 498), (72, 528), (60, 552), (48, 570), (34, 585), (21, 594), (0, 600),
           (-21, 594), (-34, 585), (-48, 570), (-60, 552), (-72, 528), (-84, 498), (-97, 462), (-106, 420), (-116, 372),
           (-120, 324), (-122, 270), (-122, 210), (-118, 150), (-116, 90), (-110, 42), (-107, 15), (-103, 4), (-92, 0)]
SCALE = 1.0                      # (our windscreen carries it at life size: its frame's foot lands at 1.356, the old 1.35)
METAL_X = 0.19                   # the solid metal under the pane's foot: out to here, the side panes' glass beyond it


def side_floor(z):
    """the side panes' lower edge: metal under it only as a thin rail along the sill, rising 4 cm to the front pane's
    frame (bert, 2026-10-23: the filled areas cut the view; his shots 003857/003904/003912/003917 show the side
    panes' glass running nearly down to the sill)"""
    t = (z - 0.735) / (1.15 - 0.735)
    return -0.24 + 0.04 * min(1.0, max(0.0, t))


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner, dark = atlas.swatch(skin), atlas.swatch((40, 42, 44)), atlas.swatch((26, 27, 28))
    # the armoured front pane is a flat slab: the windscreen made flat round it
    can, ws = C.Shape(CANOPY), C.Shape(C.flat_pane(WINDSCREEN, *PANE, outline=OUTLINE, scale=SCALE))
    glass = C.glass_triangles(can, E, -0.30, 0.66) + C.glass_triangles(ws, E, 0.70, 1.42)
    # the canopy's frame: rear bow, front bow, sill rails
    C.bow(m, can, E, -0.28, 0.05, 0.03, out, inner)
    C.bow(m, can, E, 0.662, 0.074, 0.022, out, inner)      # the canopy's front bow, butting the windscreen's arch: one bar
    C.member(m, can, E, 0.0, -0.30, 0.66, 0.045, 0.035, out, inner)
    # (no frame along the canopy's top: its transparency is one piece, bert 2026-10-23)
    # the windscreen: its arch against the canopy, the front pane's arched frame, the base frame, the side rails
    C.bow(m, ws, E, 0.7165, 0.031, 0.022, out, inner)      # the arch, on from the canopy's bow (one bar, bert)
    ov = C.oval_pane(m, ws, E, PANE_TOP, PANE_FOOT, PANE_W, 0.04, 0.025, out, inner, n=2.0, n_bot=8.0,
                     outline=OUTLINE, scale=SCALE)
    # the solid metal: under the side panes' rising lower edge, and the whole band under the front pane's foot
    def side_top(z):                                     # (up to the front pane's frame, not over it)
        return min(ws.s_at_y(z, side_floor(z)), ws.s_at_x(z, ov.env(z, 0.04 / 2 + 0.008)) - 0.004)
    C.fill(m, ws, E, 0.735, PANE_FOOT, side_top, out, inner, steps=40)
    C.cap(m, ws, E, PANE_FOOT, 1.38, lambda z: ws.s_at_x(z, METAL_X), out, inner, steps=8)   # (centre only, the trace)
    C.bow(m, ws, E, 1.40, 0.04, 0.025, out, inner)
    C.member(m, ws, E, 0.0, 0.70, 1.42, 0.045, 0.035, out, inner)
    # the periscope housing on the canopy's top
    top = can.point(0.12, 1.0) + E
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    # its foot a short frame on the glass (the glass cut away under it), the housing on that
    C.member(m, can, E, 1.0, 0.06, 0.18, 0.05, 0.02, out, inner, sides=(1,), steps=4)
    m.box(top + np.array([0, 0.024, 0]), (X, Y, Z), (0.028, 0.022, 0.06), out)
    m.box(top + np.array([0, 0.030, -0.0615]), (X, Y, Z), (0.02, 0.014, 0.002), dark)
    return m, glass


def main(argv):
    C.run(VID, SOURCE_BONE, build, argv)


if __name__ == '__main__':
    main(sys.argv[1:])
