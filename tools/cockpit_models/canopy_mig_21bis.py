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
import cockpit_lib as L          # noqa: E402
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'mig_21bis'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's sections, metres from the eye: z, then the right half from the sill up over the top
CANOPY = [
    (-0.30, [(0.36, -0.15), (0.34, 0.06), (0.31, 0.19), (0.22, 0.27), (0.10, 0.285), (0.0, 0.28)]),
    (-0.10, [(0.45, -0.30), (0.425, -0.05), (0.40, 0.15), (0.30, 0.255), (0.14, 0.275), (0.0, 0.27)]),
    (0.20, [(0.456, -0.28), (0.432, -0.12), (0.38, 0.13), (0.26, 0.225), (0.12, 0.25), (0.0, 0.26)]),
    (0.50, [(0.455, -0.34), (0.42, -0.18), (0.34, 0.05), (0.24, 0.15), (0.12, 0.215), (0.0, 0.25)]),
    (0.66, [(0.43, -0.32), (0.385, -0.14), (0.29, 0.02), (0.17, 0.13), (0.08, 0.19), (0.0, 0.22)]),
]
WINDSCREEN = [
    (0.70, [(0.41, -0.30), (0.37, -0.13), (0.28, 0.0), (0.16, 0.11), (0.07, 0.17), (0.0, 0.19)]),
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
    m.box(top + np.array([0, 0.012, 0]), (X, Y, Z), (0.028, 0.022, 0.06), out)        # sitting on the glass
    m.box(top + np.array([0, 0.014, -0.0615]), (X, Y, Z), (0.02, 0.014, 0.002), dark)
    return m, glass


def main(argv):
    ac = L.Aircraft(VID)
    E = ac.eye(0)
    old = C.original_glass(VID)
    atlas = C.atlas_for(VID)
    m, glass = build(atlas, E, C.frame_colour(ac))
    n_old, n_new = C.install(ac, m, SOURCE_BONE, glass, old)
    print(f'{VID} canopy: {n_old} old frame faces removed; {n_new} frame faces, {len(glass) // 3} glass triangles')
    if '--dry-run' not in argv:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
