#!/usr/bin/env python3
"""Su-17 canopy, made by hand: the shape set against the model's cockpit wall tops and nose deck (sections.py; the
old glass came down inside the walls), and the real frame layout.

The Su-17 canopy (side views and photos of the type, the Su-7's own layout): a windscreen of a flat armoured front
pane between two side panes, its two posts down to the base frame on the nose and its arch at the rear; the canopy
sliding aft, a tall narrow transparency held by its frame (the front bow against the windscreen arch, the rear bow
against the spine fairing, the sill rails) with the rear-view periscope housing on top.

Run after the cockpit (su_17.py), on its output. Usage: python3 tools/cockpit_models/canopy_su_17.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'su_17'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.45, [(0.37, -0.50), (0.30, -0.30), (0.22, -0.05), (0.16, 0.15), (0.09, 0.27), (0.0, 0.28)]),
    (-0.20, [(0.43, -0.50), (0.36, -0.35), (0.30, -0.15), (0.285, 0.05), (0.25, 0.18), (0.14, 0.28), (0.0, 0.30)]),
    (0.00, [(0.43, -0.50), (0.36, -0.35), (0.30, -0.15), (0.285, 0.05), (0.25, 0.18), (0.14, 0.28), (0.0, 0.30)]),
    (0.20, [(0.43, -0.50), (0.365, -0.35), (0.30, -0.15), (0.28, 0.04), (0.24, 0.16), (0.13, 0.26), (0.0, 0.29)]),
    (0.40, [(0.43, -0.50), (0.37, -0.35), (0.30, -0.15), (0.27, 0.03), (0.22, 0.14), (0.12, 0.22), (0.0, 0.27)]),
    (0.60, [(0.44, -0.50), (0.38, -0.36), (0.32, -0.18), (0.27, -0.02), (0.19, 0.10), (0.10, 0.155), (0.0, 0.17)]),
    (0.80, [(0.42, -0.50), (0.36, -0.40), (0.30, -0.27), (0.25, -0.12), (0.17, 0.01), (0.09, 0.08), (0.0, 0.10)]),
    (1.00, [(0.33, -0.45), (0.29, -0.36), (0.25, -0.24), (0.18, -0.12), (0.09, -0.04), (0.0, -0.02)]),
    (1.20, [(0.27, -0.42), (0.23, -0.33), (0.18, -0.25), (0.10, -0.18), (0.0, -0.15)]),
    (1.32, [(0.22, -0.40), (0.18, -0.36), (0.12, -0.33), (0.06, -0.32), (0.0, -0.32)]),
]
SPLIT = 0.44                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.12              # the flat front pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner, dark = atlas.swatch(skin), atlas.swatch((40, 42, 44)), atlas.swatch((26, 27, 28))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.45, SPLIT, nz=12) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.32, nz=10)
    # the canopy: its rear bow, its front bow, the sill rails, the periscope
    C.bow(m, sh, E, -0.43, 0.045, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, -0.45, SPLIT, 0.045, 0.035, out, inner)
    C.periscope(m, sh, E, 0.10, out, dark)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.022, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), 0.70, 1.32, 0.028, 0.022, out, inner)
    C.bow(m, sh, E, 1.30, 0.035, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.32, 0.045, 0.035, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
