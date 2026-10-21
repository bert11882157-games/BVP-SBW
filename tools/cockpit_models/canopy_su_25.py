#!/usr/bin/env python3
"""Su-25 canopy, made by hand: the shape set against the model's cockpit walls and nose deck (sections.py) and the
old glass, and the real frame layout.

The Su-25 canopy (side views and photos of the type): a windscreen of a thick flat armoured front pane between two
side panes, its two heavy posts down to the base frame on the nose and its arch at the rear; the canopy hinged on the
right and opening sideways, one transparency over the armoured tub held by its frame (the front bow against the
windscreen arch, the rear bow where it meets the metal hump behind the seat, the sill rails) with the periscope housing on top.

Run after the cockpit (su_25.py), on its output. Usage: python3 tools/cockpit_models/canopy_su_25.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'su_25'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.45, [(0.45, -0.15), (0.40, -0.03), (0.33, 0.12), (0.24, 0.22), (0.12, 0.265), (0.0, 0.27)]),
    (-0.30, [(0.45, -0.18), (0.44, -0.02), (0.41, 0.13), (0.34, 0.24), (0.19, 0.29), (0.0, 0.305)]),
    (-0.15, [(0.45, -0.20), (0.45, 0.0), (0.43, 0.15), (0.36, 0.26), (0.20, 0.30), (0.0, 0.315)]),
    (0.10, [(0.45, -0.22), (0.45, -0.02), (0.43, 0.14), (0.37, 0.25), (0.22, 0.305), (0.0, 0.325)]),
    (0.40, [(0.45, -0.24), (0.44, -0.05), (0.41, 0.10), (0.33, 0.22), (0.20, 0.29), (0.0, 0.31)]),
    (0.65, [(0.45, -0.24), (0.42, -0.07), (0.37, 0.07), (0.28, 0.18), (0.16, 0.25), (0.0, 0.27)]),
    (0.70, [(0.45, -0.25), (0.415, -0.08), (0.36, 0.06), (0.27, 0.17), (0.15, 0.235), (0.0, 0.255)]),
    (0.85, [(0.45, -0.30), (0.40, -0.15), (0.33, -0.04), (0.24, 0.04), (0.13, 0.08), (0.0, 0.09)]),
    (1.00, [(0.45, -0.36), (0.38, -0.24), (0.30, -0.17), (0.20, -0.13), (0.10, -0.12), (0.0, -0.12)]),
    (1.20, [(0.44, -0.38), (0.36, -0.32), (0.26, -0.29), (0.14, -0.285), (0.0, -0.285)]),
]
SPLIT = 0.66                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.17              # the armoured front pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner, dark = atlas.swatch(skin), atlas.swatch((40, 42, 44)), atlas.swatch((26, 27, 28))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.15, SPLIT, nz=12) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.20, nz=8)
    # the canopy: the metal hump behind the seat, its rear bow, its front bow, the sill rails, the periscope
    C.skin(m, sh, E, -0.45, -0.15, out)
    C.bow(m, sh, E, -0.44, 0.03, 0.03, out, inner)
    C.bow(m, sh, E, -0.13, 0.045, 0.035, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.035, 0.022, out, inner)
    C.member(m, sh, E, 0.0, -0.45, SPLIT, 0.05, 0.04, out, inner)
    C.periscope(m, sh, E, 0.15, out, dark)
    # the windscreen: the arch, the armoured front pane's two heavy posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.035, 0.045, 0.025, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), 0.80, 1.20, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, 1.18, 0.04, 0.025, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.20, 0.05, 0.04, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], clear=(C.Shape(SECTIONS), -0.45, 1.20),     # the old cage's plates
          boxes=[((-0.20, -0.20, 0.72), (0.20, 0.15, 1.25))])                   # the old armoured-glass posts
