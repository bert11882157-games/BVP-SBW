#!/usr/bin/env python3
"""MiG-29 canopy, made by hand: the model had no canopy glass, only a flat roof strip and a windscreen arch piece,
so the shape is set against its sill rails, that roof line and the nose deck (sections.py), with the proportions and
frame layout taken from reference views of the type (work/reference/mig_29: two dozen angles, outside and from the
seat).

The MiG-29 canopy (side views, photos and the reference views): a long windscreen down to the nose deck, a flat front
pane between two curved side panes with two thin posts; the canopy one blown transparency hinged at the back, low
(its top about 20 cm over the eye), with no bow across it, held by its frame: the thick front bow against the
windscreen arch, the sill rails, the rear bow over the headrest; a red-brown seal round the frame's inner edge.

Run after the cockpit (mig_29.py), on its output. Usage: python3 tools/cockpit_models/canopy_mig_29.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'mig_29'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.92, [(0.335, -0.20), (0.335, -0.12), (0.27, -0.035), (0.18, 0.015), (0.08, 0.045), (0.0, 0.055)]),   # 2 cm clear of the spine fairing's front
    (-0.80, [(0.34, -0.226), (0.34, -0.10), (0.30, 0.0), (0.22, 0.07), (0.12, 0.105), (0.0, 0.115)]),
    (-0.60, [(0.37, -0.226), (0.365, -0.08),    (0.335, 0.03), (0.27, 0.11), (0.16, 0.16), (0.0, 0.17)]),
    (-0.40, [(0.38, -0.235), (0.375, -0.06), (0.345, 0.05), (0.28, 0.14), (0.17, 0.19), (0.0, 0.20)]),
    (-0.20, [(0.385, -0.25), (0.38, -0.05), (0.35, 0.06), (0.285, 0.15), (0.175, 0.20), (0.0, 0.21)]),
    (0.20, [(0.385, -0.25), (0.38, -0.05), (0.35, 0.06), (0.285, 0.15), (0.175, 0.20), (0.0, 0.21)]),
    (0.40, [(0.38, -0.25), (0.375, -0.06), (0.345, 0.05), (0.28, 0.13), (0.17, 0.18), (0.0, 0.19)]),
    (0.60, [(0.375, -0.26), (0.365, -0.08), (0.33, 0.02), (0.26, 0.09), (0.15, 0.13), (0.0, 0.14)]),
    (0.76, [(0.37, -0.28), (0.35, -0.12), (0.31, -0.03), (0.23, 0.03), (0.13, 0.065), (0.0, 0.075)]),
    (0.80, [(0.365, -0.30), (0.345, -0.14), (0.30, -0.05), (0.22, 0.01), (0.12, 0.045), (0.0, 0.055)]),
    (1.00, [(0.36, -0.38), (0.34, -0.25), (0.29, -0.17), (0.20, -0.12), (0.10, -0.10), (0.0, -0.095)]),
    (1.24, [(0.36, -0.41), (0.33, -0.40), (0.26, -0.39), (0.14, -0.385), (0.0, -0.38)]),   # its foot on the nose deck
]
SPLIT = 0.76                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.12              # the flat front pane's edges (the windscreen's two thin posts), out from the centre line
SEAL = (122, 66, 52)             # the red-brown seal round the canopy frame's inner edge

def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner, seal = atlas.swatch(skin), atlas.swatch((58, 60, 62)), atlas.swatch(SEAL)
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.92, SPLIT, nz=20) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.24, nz=10)
    # the canopy: the rear bow over the headrest, the thick front bow, the sill rails; the seal a thin line along them
    C.bow(m, sh, E, -0.90, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.01, 0.045, 0.035, out, inner)
    C.member(m, sh, E, 0.03, -0.92, SPLIT, 0.044, 0.035, out, inner, steps=20)     # just up the glass: off the model's sill
    C.member(m, sh, E, lambda z: 0.03 + 0.042 / sh.arclen(z), -0.88, SPLIT - 0.035, 0.012, 0.008, seal, seal, steps=20,
             proud=0.002)                                                            # the seal, beside the rail
    # the windscreen: its arch, the front pane's two thin posts, the base frame on the nose deck, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.03, 0.022, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.05, 1.24, 0.016, 0.014, out, inner, steps=8)
    C.bow(m, sh, E, 1.225, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.24, 0.045, 0.035, out, inner, steps=8)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], colour=(128, 134, 140),   # the airframe grey
          clear=(C.Shape(SECTIONS), 0.30, 1.20, 0.30))                       # the old arch and side plates (not the spine fairing)
