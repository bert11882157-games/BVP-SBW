#!/usr/bin/env python3
"""Su-27 canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The Su-27 canopy (side views and photos of the type): a fixed one-piece windscreen, no posts (reference views of the
type, work/reference/su_27), held by its base frame on the nose and its arch at the rear over the HUD; the
canopy one large blown transparency hinged at the back, with one hoop across it just behind the headrest (the glass
running on behind it to the spine), held by the frame round its edge (the thick front bow against the windscreen arch,
carrying three mirrors, the sill rails, the frame at its tail on the spine).

Run after the cockpit (su_27.py), on its output. Usage: python3 tools/cockpit_models/canopy_su_27.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'su_27'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-1.20, [(0.215, -0.06), (0.227, 0.011), (0.191, 0.077), (0.122, 0.107), (0.072, 0.119), (0.032, 0.124), (0.0, 0.128)]),
    (-1.00, [(0.401, -0.221), (0.399, -0.081), (0.36, 0.042), (0.282, 0.134), (0.177, 0.176), (0.084, 0.192), (0.0, 0.199)]),
    (-0.70, [(0.468, -0.305), (0.441, -0.14), (0.407, -0.002), (0.349, 0.122), (0.257, 0.222), (0.128, 0.266), (0.0, 0.279)]),
    (-0.40, [(0.513, -0.345), (0.483, -0.164), (0.444, -0.014), (0.383, 0.123), (0.285, 0.236), (0.142, 0.286), (0.0, 0.296)]),
    (-0.10, [(0.522, -0.352), (0.489, -0.166), (0.452, -0.013), (0.385, 0.123), (0.282, 0.233), (0.142, 0.283), (0.0, 0.301)]),
    (0.20, [(0.496, -0.341), (0.45, -0.165), (0.416, -0.025), (0.359, 0.104), (0.264, 0.208), (0.137, 0.267), (0.0, 0.294)]),
    (0.50, [(0.459, -0.363), (0.433, -0.202), (0.397, -0.067), (0.34, 0.054), (0.251, 0.154), (0.125, 0.196), (0.0, 0.208)]),
    (0.80, [(0.423, -0.415), (0.408, -0.267), (0.362, -0.143), (0.3, -0.04), (0.211, 0.035), (0.104, 0.065), (0.0, 0.072)]),
    (1.10, [(0.362, -0.469), (0.354, -0.342), (0.303, -0.238), (0.24, -0.158), (0.158, -0.112), (0.078, -0.088), (0.0, -0.078)]),
    (1.30, [(0.318, -0.504), (0.317, -0.394), (0.261, -0.303), (0.19, -0.246), (0.121, -0.215), (0.059, -0.199), (0.0, -0.189)]),
    (1.50, [(0.288, -0.48), (0.27, -0.43), (0.199, -0.379), (0.121, -0.352), (0.069, -0.342), (0.0, -0.342)]),
]
SPLIT = 0.60                     # the canopy's front edge, against the windscreen arch over the HUD
HOOP_Z = -0.62                  # the canopy's hoop just behind the headrest (the glass runs on behind it)
NOSE_RGB = (24, 25, 26)          # (lower_skin.py: that anti-glare is the shell's black; the nose's darkest texel is the IRST ball)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    mback, mglass = atlas.swatch((34, 36, 38)), atlas.swatch((150, 170, 180))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -1.20, SPLIT, nz=20) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.50, nz=10)
    # the canopy: its tail frame, the hoop behind the headrest, the thick front bow with its three mirrors, the sill rails
    C.bow(m, sh, E, -1.18, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, HOOP_Z, 0.05, 0.035, out, inner)
    C.bow(m, sh, E, SPLIT - 0.01, 0.055, 0.04, out, inner)
    C.mirrors(m, sh, E, SPLIT - 0.01, 0.04, mback, mglass)
    C.member(m, sh, E, 0.0, -1.20, SPLIT, 0.05, 0.035, out, inner, steps=20)
    # the windscreen, one piece (no posts): the arch, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.02, out, inner)
    C.bow(m, sh, E, 1.48, 0.04, 0.025, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.50, 0.05, 0.035, out, inner)
    # one smooth shell under the windscreen ahead of the coaming, 3 cm inside the glass, its top under the sight line
    # over the coaming's front edge (1.05 m ahead, 0.32 m under the eye): the nose under the glass one dark deck
    C.shell(m, sh, E, 1.02, 1.48, atlas.swatch((24, 25, 26)), sight=lambda z: -0.32 / 1.05 * z - 0.012,
            inset=0.045)
    return m, glass


def main(argv):
    C.run(VID, SOURCE_BONE, build, argv)


if __name__ == '__main__':
    main(sys.argv[1:])
