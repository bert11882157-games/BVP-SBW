#!/usr/bin/env python3
"""Ju 87 B-2 canopy, made by hand: the greenhouse's shape from the model's old glass and its cockpit walls
(glass_sections.py, sections.py), the windscreen (which the model had no glass for) down to the cowling, and the real
frame layout.

The Ju 87 B canopy (side views and photos of the type): a long flat-topped greenhouse over both crew. A windscreen of
a flat armoured front pane between two side panes, its posts down to the base frame on the cowling and its arch at
the rear; the pilot's hood sliding aft, with a bow across its middle; the fixed centre section; the gunner's hood,
with its own bows, open at its tail for the rear gun. Along both sides the frame runs at the sill, at mid-height and
at the edge of the flat top, as the greenhouse's panes are set.

Run after the cockpit (ju_87_b2.py), on its output. Usage: python3 tools/cockpit_models/canopy_ju_87_b2.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'ju_87_b2'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the greenhouse's sections, metres from the pilot's eye: z, then the right half from the sill up over the flat top
SECTIONS = [
    (-2.00, [(0.31, -0.31), (0.25, -0.12), (0.18, 0.03), (0.10, 0.06), (0.0, 0.06)]),
    (-1.80, [(0.36, -0.32), (0.30, -0.14), (0.24, 0.06), (0.19, 0.14), (0.0, 0.15)]),
    (-1.40, [(0.39, -0.29), (0.33, -0.10), (0.26, 0.14), (0.20, 0.28), (0.0, 0.30)]),
    (-1.00, [(0.40, -0.25), (0.36, -0.05), (0.30, 0.15), (0.24, 0.28), (0.20, 0.31), (0.0, 0.31)]),
    (0.20, [(0.40, -0.25), (0.36, -0.05), (0.30, 0.15), (0.24, 0.28), (0.20, 0.31), (0.0, 0.31)]),
    (0.41, [(0.39, -0.24), (0.355, -0.05), (0.295, 0.14), (0.235, 0.27), (0.20, 0.30), (0.0, 0.30)]),
    (0.60, [(0.39, -0.22), (0.35, -0.05), (0.28, 0.10), (0.21, 0.16), (0.0, 0.17)]),
    (0.83, [(0.30, -0.15), (0.24, -0.08), (0.17, -0.05), (0.0, -0.05)]),
]
SPLIT = 0.41                                 # the pilot's hood front, against the windscreen arch
BOWS = (-1.60, -1.00, -0.45, -0.05)          # the gunner's hood bow, gunner's / centre, centre / pilot's, pilot's middle
FRONT_PANE_X = 0.20                          # the flat armoured pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -2.00, SPLIT, nz=24) + C.glass_triangles(sh, E, SPLIT + 0.04, 0.83, nz=6)
    # the greenhouse: its tail frame at the gunner's opening, its bows, the front bow; the sill, mid and top-edge frames
    C.bow(m, sh, E, -1.98, 0.035, 0.025, out, inner)
    for z in BOWS:
        C.bow(m, sh, E, z, 0.03, 0.022, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    for s in (0.0, 0.42, 0.80):
        C.member(m, sh, E, s, -2.00, SPLIT, 0.03 if s else 0.04, 0.025, out, inner, steps=24)
    # the windscreen: the arch, the armoured pane's two posts, the base frame on the cowling, the side rails
    C.bow(m, sh, E, SPLIT + 0.025, 0.035, 0.022, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.04, 0.83, 0.028, 0.022, out, inner, steps=6)
    C.bow(m, sh, E, 0.815, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.04, 0.83, 0.04, 0.03, out, inner, steps=6)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], clear=(C.Shape(SECTIONS), 0.30, 0.90))   # the old windscreen frames
