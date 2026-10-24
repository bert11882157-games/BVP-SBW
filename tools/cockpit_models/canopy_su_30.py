#!/usr/bin/env python3
"""Su-30 canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The Su-30 canopy (side views and photos of the type): the Su-27's windscreen (one piece, no posts, down to the base
frame on the nose), the canopy's front bow and the windscreen's arch one thick bar leaning back with the three mirrors
on it (bert's War Thunder Su-27 reference), and one long canopy over both seats, hinged at the back, with no bow
across it: only the frame round its edge (the sill rails, the frame at its tail on the spine).

Run after the cockpits (su_30.py), on their output. Usage: python3 tools/cockpit_models/canopy_su_30.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'su_30'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the front seat's eye: z, then the right half from the sill up
SECTIONS = [
    (-2.75, [(0.15, 0.08), (0.16, 0.14), (0.13, 0.20), (0.095, 0.25), (0.05, 0.30), (0.0, 0.345)]),
    (-2.60, [(0.27, 0.03), (0.246, 0.136), (0.212, 0.209), (0.166, 0.263), (0.115, 0.31), (0.06, 0.36), (0.0, 0.40)]),
    (-2.40, [(0.339, -0.03), (0.303, 0.106), (0.274, 0.199), (0.217, 0.271), (0.149, 0.33), (0.08, 0.39), (0.0, 0.437)]),
    (-2.20, [(0.385, -0.06), (0.354, 0.088), (0.309, 0.194), (0.253, 0.28), (0.173, 0.345), (0.095, 0.41), (0.0, 0.463)]),
    (-2.00, [(0.417, -0.08), (0.38, 0.08), (0.336, 0.194), (0.277, 0.29), (0.193, 0.36), (0.105, 0.43), (0.0, 0.479)]),
    (-1.80, [(0.43, -0.09), (0.398, 0.075), (0.353, 0.196), (0.292, 0.297), (0.207, 0.37), (0.112, 0.442), (0.0, 0.487)]),
    (-1.60, [(0.393, -0.10), (0.355, 0.074), (0.321, 0.184), (0.274, 0.281), (0.207, 0.368), (0.114, 0.446), (0.0, 0.484)]),
    (-1.40, [(0.321, -0.11), (0.29, 0.07), (0.268, 0.158), (0.24, 0.249), (0.195, 0.342), (0.112, 0.424), (0.0, 0.465)]),
    (-1.20, [(0.302, -0.122), (0.278, 0.057), (0.255, 0.141), (0.228, 0.227), (0.187, 0.317), (0.11, 0.399), (0.0, 0.438)]),
    (-1.00, [(0.335, -0.135), (0.299, 0.035), (0.272, 0.128), (0.239, 0.215), (0.189, 0.302), (0.107, 0.371), (0.0, 0.408)]),
    (-0.80, [(0.385, -0.155), (0.336, 0.009), (0.308, 0.113), (0.263, 0.206), (0.197, 0.287), (0.106, 0.344), (0.0, 0.375)]),
    (-0.60, [(0.428, -0.182), (0.394, -0.024), (0.35, 0.096), (0.287, 0.194), (0.206, 0.27), (0.108, 0.32), (0.0, 0.344)]),
    (-0.40, [(0.456, -0.219), (0.411, -0.057), (0.372, 0.07), (0.304, 0.173), (0.212, 0.246), (0.109, 0.292), (0.0, 0.314)]),
    (-0.20, [(0.487, -0.26), (0.441, -0.093), (0.398, 0.042), (0.32, 0.149), (0.219, 0.221), (0.112, 0.265), (0.0, 0.287)]),
    (0.00, [(0.52, -0.30), (0.48, -0.132), (0.422, 0.014), (0.332, 0.123), (0.225, 0.193), (0.114, 0.237), (0.0, 0.259)]),
    (0.20, [(0.525, -0.34), (0.488, -0.168), (0.425, -0.021), (0.329, 0.085), (0.222, 0.154), (0.114, 0.2), (0.0, 0.223)]),
    (0.40, [(0.476, -0.362), (0.448, -0.204), (0.383, -0.072), (0.3, 0.026), (0.206, 0.093), (0.107, 0.139), (0.0, 0.162)]),
    (0.60, [(0.378, -0.37), (0.341, -0.248), (0.307, -0.144), (0.237, -0.068), (0.165, -0.01), (0.085, 0.024), (0.0, 0.041)]),
    (0.80, [(0.302, -0.36), (0.29, -0.296), (0.252, -0.209), (0.18, -0.156), (0.115, -0.13), (0.052, -0.122), (0.0, -0.118)]),
    (0.92, [(0.22, -0.315), (0.20, -0.28), (0.15, -0.26), (0.09, -0.235), (0.04, -0.228), (0.0, -0.226)]),
]
SPLIT = 0.46                     # the canopy's front edge, against the windscreen arch over the HUD
BOW_LEAN = 0.20                  # the front bow leans back: its foot on the sill 10 cm ahead of BOW_Z, its top 10 cm
                                 # behind (bert's War Thunder side shot, 2026-10-23)
BOW_Z, BOW_W = SPLIT + 0.02, 0.085
NOSE_RGB = (24, 25, 26)          # (lower_skin.py: that anti-glare is the shell's black)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    mback, mglass = atlas.swatch((34, 36, 38)), atlas.swatch((150, 170, 180))
    sh = C.Shape(SECTIONS)
    glass = (C.glass_triangles(sh, E, -2.75, BOW_Z + BOW_LEAN / 2 + BOW_W / 2 + 0.01, nz=34, keep='aft') +
             C.glass_triangles(sh, E, BOW_Z - BOW_LEAN / 2 - BOW_W / 2 - 0.01, 0.90, nz=12, keep='fore'))     # (ends under the base frame: the nose tip's skin lies at its edge)
    # the canopy: its tail frame, the sill rails (no frame between the seats: one transparency over both)
    C.bow(m, sh, E, -2.73, 0.04, 0.03, out, inner)
    C.member(m, sh, E, 0.0, -2.75, BOW_Z + BOW_LEAN / 2, 0.05, 0.035, out, inner, steps=34)
    # the canopy's front bow and the windscreen's arch: one thick bar leaning back, the three mirrors on it (the
    # Su-27's, bert's War Thunder reference); the windscreen one piece, no posts: the base frame, the side rails
    C.bow(m, sh, E, BOW_Z, BOW_W, 0.045, out, inner, lean=BOW_LEAN)
    C.mirrors(m, sh, E, BOW_Z - 0.02, 0.045, mback, mglass, lean=BOW_LEAN)
    C.bow(m, sh, E, 0.905, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, BOW_Z + BOW_LEAN / 2, 0.92, 0.05, 0.035, out, inner)
    # the windscreen's lower edge where the nose skin rises up to it (the skin lies just under the glass there): metal
    C.fill(m, sh, E, 0.72, 0.905, lambda z: min(0.32, max(0.06, sh.s_at_y(z, -0.25))), out, inner, steps=14)
    # one smooth shell under the windscreen ahead of the coaming, inside the glass, its top under the sight line over
    # the coaming's front edge (0.80 m ahead, 0.30 m under the eye): the nose under the glass one dark deck
    C.shell(m, sh, E, 0.80, 0.905, atlas.swatch((24, 25, 26)), sight=lambda z: -0.30 / 0.80 * z - 0.012, inset=0.045, nz=4)
    return m, glass


def main(argv):
    C.run(VID, SOURCE_BONE, build, argv)


if __name__ == '__main__':
    main(sys.argv[1:])
