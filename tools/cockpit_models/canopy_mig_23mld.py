#!/usr/bin/env python3
"""MiG-23MLD canopy, made by hand: the shape set against the model's cockpit sills and nose deck (sections.py; the
old glass stops short of both), and the real frame layout.

The MiG-23 canopy (side views and photos of the type): a long sloping windscreen of a flat armoured front pane between
two side panes (the pane a flat rectangle, not the earlier MiGs' oval), its two straight posts from the arch down to the
base frame on the nose; the canopy hinged at the
back and lifting up, one blown transparency held by its frame (the front bow against the windscreen arch, the rear
bow over the bulkhead behind the seat, the sill rails) with the rear-view periscope housing on top.

Run after the cockpit (mig_23mld.py), on its output. Usage: python3 tools/cockpit_models/canopy_mig_23mld.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'mig_23mld'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.60, [(0.42, -0.25), (0.40, -0.05), (0.36, 0.10), (0.28, 0.20), (0.15, 0.255), (0.0, 0.27)]),
    (-0.20, [(0.44, -0.25), (0.43, -0.08), (0.40, 0.08), (0.33, 0.19), (0.18, 0.26), (0.0, 0.285)]),
    (0.00, [(0.45, -0.25), (0.44, -0.10), (0.40, 0.06), (0.33, 0.18), (0.20, 0.26), (0.0, 0.30)]),
    (0.20, [(0.44, -0.25), (0.42, -0.10), (0.38, 0.05), (0.30, 0.18), (0.17, 0.265), (0.0, 0.30)]),
    (0.40, [(0.42, -0.25), (0.40, -0.10), (0.35, 0.06), (0.27, 0.17), (0.15, 0.245), (0.0, 0.27)]),
    (0.60, [(0.40, -0.25), (0.37, -0.12), (0.31, 0.0), (0.23, 0.09), (0.12, 0.14), (0.0, 0.15)]),
    (0.80, [(0.36, -0.27), (0.32, -0.16), (0.26, -0.08), (0.17, -0.02), (0.09, 0.0), (0.0, 0.0)]),
    (1.00, [(0.32, -0.29), (0.29, -0.28), (0.20, -0.275), (0.0, -0.27)]),
]
SPLIT = 0.40                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.13              # the flat front pane's edges, out from the centre line
# the flat armoured front pane (flat_pane): from the arch to the base frame, straight-sided, its corners barely rounded
PANE = (SPLIT + 0.05, 1.00, FRONT_PANE_X, 0.01, 0.028)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner, dark = atlas.swatch(skin), atlas.swatch((40, 42, 44)), atlas.swatch((26, 27, 28))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -0.60, SPLIT, nz=12) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.00, nz=8)
    # the canopy: its rear bow, its front bow, the sill rails, the periscope
    C.bow(m, sh, E, -0.58, 0.045, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, -0.60, SPLIT, 0.045, 0.035, out, inner)
    C.periscope(m, sh, E, 0.05, out, dark)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.022, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.05, 1.00, 0.028, 0.022, out, inner, sides=(1, -1))
    C.bow(m, sh, E, 0.985, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.00, 0.045, 0.035, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
