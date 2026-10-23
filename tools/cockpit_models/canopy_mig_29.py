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
SECTIONS = [   # measured from the reference model (its eye at the origin), widths x0.96 to our fuselage, down to our sill
    (-0.74, [(0.345, -0.226), (0.342, -0.17), (0.304, -0.093), (0.264, -0.031), (0.225, -0.002), (0.164, 0.041), (0.082, 0.073), (0.0, 0.074)]),   # ends on the spine fairing's front, its own outline 5 mm over it (no gap above it)
    (-0.42, [(0.368, -0.235), (0.365, -0.15), (0.356, -0.11), (0.342, -0.07), (0.324, -0.03), (0.30, 0.01), (0.268, 0.05), (0.226, 0.09), (0.164, 0.13), (0.0, 0.166)]),
    (-0.12, [(0.384, -0.25), (0.382, -0.19), (0.377, -0.15), (0.37, -0.11), (0.357, -0.07), (0.34, -0.03), (0.316, 0.01), (0.286, 0.05), (0.246, 0.09), (0.191, 0.13), (0.099, 0.17), (0.0, 0.18)]),
    (0.18, [(0.386, -0.25), (0.384, -0.19), (0.378, -0.15), (0.369, -0.11), (0.353, -0.07), (0.333, -0.03), (0.308, 0.01), (0.274, 0.05), (0.228, 0.09), (0.163, 0.13), (0.0, 0.163)]),
    (0.38, [(0.38, -0.25), (0.378, -0.19), (0.369, -0.15), (0.355, -0.11), (0.338, -0.07), (0.313, -0.03), (0.282, 0.01), (0.242, 0.05), (0.185, 0.09), (0.084, 0.13), (0.0, 0.136)]),
    (0.56, [(0.378, -0.27), (0.372, -0.19), (0.362, -0.15), (0.35, -0.11), (0.33, -0.07), (0.305, -0.03), (0.27, 0.01), (0.225, 0.05), (0.16, 0.085), (0.0, 0.10)]),
    (0.68, [(0.378, -0.33), (0.372, -0.25), (0.362, -0.17), (0.342, -0.09), (0.30, -0.02), (0.25, 0.04), (0.18, 0.085), (0.09, 0.105), (0.0, 0.11)]),   # raised over the HUD: its glass centred on the eye
    (0.88, [(0.36, -0.37), (0.356, -0.30), (0.346, -0.22), (0.318, -0.14), (0.255, -0.06), (0.20, 0.0), (0.13, 0.04), (0.06, 0.055), (0.0, 0.06)]),
    (1.08, [(0.31, -0.40), (0.30, -0.35), (0.27, -0.28), (0.23, -0.21), (0.175, -0.15), (0.10, -0.115), (0.0, -0.10)]),
    (1.24, [(0.24, -0.40), (0.20, -0.36), (0.13, -0.32), (0.0, -0.28)]),   # its foot on the nose deck
]
SPLIT = 0.56                     # the canopy's front edge, against the windscreen arch (the reference's joint)
SEAL = (122, 66, 52)             # the red-brown seal round the canopy frame's inner edge
PAINT_FOOTPRINT = True           # (work/handbuilt lower_skin.py: the model's skin round the windscreen in the nose's anti-glare)

def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner, seal = atlas.swatch(skin), atlas.swatch((58, 60, 62)), atlas.swatch(SEAL)
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.74, SPLIT, nz=18) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.24, nz=10)
    # the canopy: the rear bow over the headrest, the thick front bow, the sill rails; the seal a thin line along them
    C.bow(m, sh, E, -0.725, 0.035, 0.03, out, inner, proud=0.002)                              # the rear bow, on the fairing's front edge
    C.bow(m, sh, E, SPLIT + 0.015, 0.07, 0.04, out, inner, proud=0.002)       # one thick frame at the joint (canopy bow and windscreen arch together)
    C.member(m, sh, E, 0.03, -0.74, SPLIT, 0.044, 0.035, out, inner, steps=20)     # just up the glass: off the model's sill
    C.member(m, sh, E, lambda z: 0.03 + 0.042 / sh.arclen(z), -0.71, SPLIT - 0.035, 0.012, 0.008, seal, seal, steps=20,
             proud=0.002)                                                            # the seal, beside the rail
    # the windscreen, one piece: the base frame on the nose deck, the side rails, in the nose's dark anti-glare (in the
    # airframe grey they stood out as light strips on it)
    ag = atlas.swatch((30, 31, 33))
    C.bow(m, sh, E, 1.225, 0.03, 0.02, ag, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.24, 0.03, 0.003, ag, inner, steps=8, proud=0.008)
    # the windscreen's two thin posts from the arch down to the base frame, a little closer together ahead
    # (bert's War Thunder reference, 2026-10-23: head-on and from above)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, 0.17 - 0.04 * (z - SPLIT) / (1.22 - SPLIT)), SPLIT + 0.05, 1.21, 0.016, 0.01,
             out, inner, steps=12, proud=0.002)   # (on the outside: deeper, it stood as a block inside beside the HUD)
    # the deck under the windscreen ahead of the coaming, closing off the nose bay seen through the glass
    # one smooth shell under the windscreen, 3 cm inside the glass, its top under the sight line over the coaming
    # (the coaming's front edge 1.15 m ahead, 0.26 m under the eye): the nose under the glass one homogeneous deck
    C.shell(m, sh, E, 0.86, 1.24, atlas.swatch((24, 25, 26)), sight=lambda z: -0.26 / 1.15 * z - 0.012)
    C.deck(m, sh, E, SPLIT + 0.03, 0.86, atlas.swatch((24, 25, 26)), x_in=0.40, front=0.0, nz=4)     # beside the panel by the walls, back to the joint (over the wing tops)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], colour=(128, 134, 140),   # the airframe grey
          clear=(C.Shape(SECTIONS), 0.30, 1.20, 0.12))                       # the old arch and side plates (not the spine fairing)
