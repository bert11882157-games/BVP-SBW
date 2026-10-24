#!/usr/bin/env python3
"""Su-35 canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The Su-35 canopy (side views and photos of the type, the Su-27's own layout): a fixed windscreen of a flat armoured
front pane between two curved side panes, its two posts running down to the base frame on the nose, its arch at the
rear over the HUD; the canopy one large blown transparency hinged at the back, with one hoop across it just behind the headrest (the glass
running on behind it to the spine), held by the frame round its edge (the thick front bow against the windscreen arch,
carrying three mirrors, the sill rails, the frame at its tail on the spine).

Run after the cockpit (su_35.py), on its output. Usage: python3 tools/cockpit_models/canopy_su_35.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'su_35'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-1.00, [(0.338, -0.04), (0.338, 0.038), (0.3, 0.14), (0.195, 0.19), (0.109, 0.212), (0.054, 0.225), (0.0, 0.238)]),
    (-0.80, [(0.444, -0.15), (0.429, 0.006), (0.393, 0.139), (0.319, 0.247), (0.214, 0.314), (0.104, 0.34), (0.0, 0.349)]),
    (-0.60, [(0.465, -0.194), (0.44, -0.019), (0.407, 0.119), (0.353, 0.246), (0.263, 0.345), (0.128, 0.385), (0.0, 0.389)]),
    (-0.40, [(0.491, -0.225), (0.463, -0.035), (0.428, 0.11), (0.37, 0.242), (0.275, 0.35), (0.136, 0.396), (0.0, 0.403)]),
    (-0.20, [(0.53, -0.24), (0.505, -0.047), (0.465, 0.111), (0.393, 0.249), (0.284, 0.355), (0.139, 0.396), (0.0, 0.407)]),
    (0.00, [(0.526, -0.249), (0.502, -0.054), (0.459, 0.102), (0.387, 0.238), (0.281, 0.345), (0.139, 0.388), (0.0, 0.402)]),
    (0.20, [(0.502, -0.257), (0.465, -0.06), (0.432, 0.085), (0.368, 0.216), (0.271, 0.322), (0.137, 0.372), (0.0, 0.391)]),
    (0.40, [(0.487, -0.27), (0.454, -0.076), (0.416, 0.065), (0.357, 0.193), (0.264, 0.298), (0.135, 0.35), (0.0, 0.37)]),
    (0.60, [(0.47, -0.29), (0.446, -0.11), (0.408, 0.028), (0.348, 0.151), (0.256, 0.252), (0.127, 0.294), (0.0, 0.306)]),
    (0.80, [(0.448, -0.325), (0.432, -0.168), (0.393, -0.034), (0.324, 0.077), (0.224, 0.153), (0.109, 0.181), (0.0, 0.184)]),
    (1.00, [(0.424, -0.374), (0.416, -0.23),    (0.361, -0.107), (0.289, -0.011), (0.189, 0.042), (0.086, 0.05), (0.0, 0.051)]),
    (1.20, [(0.38, -0.364), (0.376, -0.291), (0.324, -0.18), (0.238, -0.108), (0.144, -0.079), (0.063, -0.081), (0.0, -0.086)]),
    (1.45, [(0.33, -0.376), (0.32, -0.33), (0.26, -0.255), (0.16, -0.225), (0.08, -0.225), (0.0, -0.23)]),
]
SPLIT = 0.66                     # the canopy's front edge, against the windscreen arch over the HUD
BOW_LEAN = 0.20                  # the front bow leans back: its foot on the sill 10 cm ahead of BOW_Z, its top 10 cm
                                 # behind (bert's War Thunder side shot, 2026-10-23)
BOW_Z, BOW_W = SPLIT + 0.02, 0.085
HOOP_Z = -0.55                  # the canopy's hoop just behind the headrest (the glass runs on behind it)
NOSE_RGB = (24, 25, 26)          # (lower_skin.py: that anti-glare is the shell's black, not the nose's turquoise IRST ball)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    mback, mglass = atlas.swatch((34, 36, 38)), atlas.swatch((150, 170, 180))
    sh = C.Shape(SECTIONS)
    glass = (C.glass_triangles(sh, E, -1.00, BOW_Z + BOW_LEAN / 2 + BOW_W / 2 + 0.01, nz=20, keep='aft') +
             C.glass_triangles(sh, E, BOW_Z - BOW_LEAN / 2 - BOW_W / 2 - 0.01, 1.45, nz=14, keep='fore'))
    # the canopy: its tail frame, the hoop behind the headrest, the thick front bow with its three mirrors, the sill rails
    C.bow(m, sh, E, -0.98, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, HOOP_Z, 0.05, 0.035, out, inner)
    # the canopy's front bow and the windscreen's arch: one thick bar (the Su-27's, bert's War Thunder reference)
    C.bow(m, sh, E, BOW_Z, BOW_W, 0.045, out, inner, lean=BOW_LEAN)
    C.mirrors(m, sh, E, BOW_Z - 0.02, 0.045, mback, mglass, lean=BOW_LEAN)
    C.member(m, sh, E, 0.0, -1.00, BOW_Z + BOW_LEAN / 2, 0.05, 0.035, out, inner, steps=20)
    # the windscreen, one piece (no posts): the base frame on the nose, the side rails
    C.bow(m, sh, E, 1.43, 0.04, 0.025, out, inner)
    C.member(m, sh, E, 0.0, BOW_Z + BOW_LEAN / 2, 1.45, 0.05, 0.035, out, inner)
    # one smooth shell under the windscreen ahead of the coaming, inside the glass, its top under the sight line over
    # the coaming's front edge (1.10 m ahead, 0.27 m under the eye): the nose under the glass one dark deck
    C.shell(m, sh, E, 1.06, 1.43, atlas.swatch((24, 25, 26)), sight=lambda z: -0.27 / 1.10 * z - 0.012, inset=0.045)
    return m, glass


def main(argv):
    C.run(VID, SOURCE_BONE, build, argv)


if __name__ == '__main__':
    main(sys.argv[1:])
