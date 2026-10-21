#!/usr/bin/env python3
"""Su-39 (Su-25TM) canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into
smooth sections; it already follows the DCS Su-25T reference model's measured outline (the hood falling about 0.32 m
per metre from the big dorsal hump to the joint, the Su-25's long windscreen), and the frame layout is the reference's.

The Su-25T canopy (the reference views): the hood one transparency from the hump behind the seat, falling to the joint
with the windscreen, held by its frame (the rear bow on the hump, the front bow at the joint, the sill rails), the
periscope housing on top; the windscreen the Su-25's, a flat armoured front pane between two side panes running its
whole length, its heavy posts down to the base frame on the nose.

Run after the cockpit (su_39.py), on its output. Usage: python3 tools/cockpit_models/canopy_su_39.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'su_39'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.40, [(0.393, 0.05), (0.38, 0.20), (0.34, 0.40), (0.25, 0.455), (0.12, 0.468), (0.0, 0.468)]),
    (-0.20, [(0.504, -0.204), (0.47, 0.10), (0.44, 0.30), (0.33, 0.39), (0.16, 0.41), (0.0, 0.412)]),
    (0.00, [(0.527, -0.309), (0.506, -0.12), (0.48, 0.20), (0.40, 0.31), (0.20, 0.35), (0.0, 0.352)]),
    (0.20, [(0.524, -0.352), (0.50, -0.17), (0.475, 0.15), (0.38, 0.25), (0.18, 0.285), (0.0, 0.289)]),
    (0.40, [(0.515, -0.379), (0.50, -0.20), (0.48, -0.03), (0.45, 0.12), (0.32, 0.21), (0.15, 0.225), (0.0, 0.225)]),
    (0.60, [(0.505, -0.411), (0.49, -0.234), (0.47, -0.076), (0.41, 0.072), (0.271, 0.152), (0.122, 0.159), (0.0, 0.155)]),
    (0.80, [(0.489, -0.448), (0.484, -0.277), (0.431, -0.13), (0.352, -0.01), (0.235, 0.061), (0.11, 0.078), (0.0, 0.076)]),
    (0.90, [(0.48, -0.45), (0.46, -0.30), (0.399, -0.163), (0.325, -0.053), (0.221, 0.018), (0.10, 0.023), (0.0, 0.023)]),
    (1.10, [(0.47, -0.48), (0.42, -0.36), (0.327, -0.205), (0.195, -0.163), (0.0, -0.160)]),
    (1.30, [(0.50, -0.47), (0.286, -0.353), (0.22, -0.282), (0.0, -0.28)]),
    (1.50, [(0.476, -0.547), (0.301, -0.435), (0.0, -0.43)]),   # its foot on the nose deck
]
SPLIT = 0.75                     # the hood's front edge, against the windscreen arch (where the reference's joint falls)
FRONT_PANE_X = 0.15              # the flat armoured front pane's edges, out from the centre line (the reference's)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner, dark = atlas.swatch(skin), atlas.swatch((40, 42, 44)), atlas.swatch((26, 27, 28))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.25, SPLIT, nz=14) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.50, nz=10)
    # the hood: the metal fairing on the hump (over the model's old rear glazing bay), its rear bow, its front bow, the
    # sill rails, the periscope
    C.skin(m, sh, E, -0.40, -0.25, out)
    # the bulkhead closing the hood's rear under the fairing (the model's old rear glazing bay behind it)
    sec = sh.section(-0.26)
    for (xa, ya), (xb, yb) in zip(sec[:-1], sec[1:]):
        for sd in (1, -1):
            m.flat([E + [sd * xa, ya, -0.26], E + [sd * xb, yb, -0.26], E + [0.0, yb, -0.26], E + [0.0, ya, -0.26]], inner,
                   normal=[0, 0, 1])
    C.bow(m, sh, E, -0.395, 0.03, 0.03, out, inner)
    C.bow(m, sh, E, -0.24, 0.045, 0.035, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.035, 0.022, out, inner)
    C.member(m, sh, E, 0.0, -0.40, SPLIT, 0.05, 0.04, out, inner)
    C.periscope(m, sh, E, 0.10, out, dark)
    # the windscreen: the arch, the armoured front pane's two heavy posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.035, 0.045, 0.025, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.05, 1.50, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, 1.48, 0.04, 0.025, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.50, 0.05, 0.04, out, inner)
    # the deck under the windscreen ahead of the coaming, closing off the nose bay seen through the glass
    C.deck(m, sh, E, 1.15, 1.50, atlas.swatch((24, 25, 26)))
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], clear=(C.Shape(SECTIONS), -0.40, 1.50),     # the old cage's plates
          boxes=[((-0.56, -0.30, 0.55), (0.56, 0.10, 0.95))])                  # the old windscreen's side plates, inside the new glass