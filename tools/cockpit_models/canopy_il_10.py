#!/usr/bin/env python3
"""Il-10 canopy, made by hand: the shape from the model's old glass (glass_sections.py), its windscreen's foot set on
the armoured nose (sections.py), and the real frame layout.

The Il-10 canopy (side views and photos of the type): a long glazed hood over both crew on the armoured tub. A
windscreen of a thick flat armoured front pane between two side panes, its posts down to the base frame on the nose
and its arch at the rear; the pilot's hood, with a bow across its middle; the gunner's glazing behind the pilot's
armoured back, with its own bow, tapering to its tail frame over the gunner's position. Along both sides the frame
runs at the sill and at mid-height, as the panes are set.

Run after the cockpit (il_10.py), on its output. Usage: python3 tools/cockpit_models/canopy_il_10.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'il_10'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the hood's sections, metres from the pilot's eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-1.95, [(0.19, -0.44), (0.20, -0.30), (0.20, -0.05), (0.18, 0.08), (0.12, 0.18), (0.0, 0.23)]),
    (-1.80, [(0.223, -0.44), (0.214, -0.265), (0.205, 0.016), (0.17, 0.10), (0.10, 0.18), (0.0, 0.227)]),
    (-1.40, [(0.36, -0.44), (0.344, -0.266), (0.31, -0.036), (0.263, 0.099), (0.149, 0.194), (0.0, 0.248)]),
    (-1.00, [(0.40, -0.44), (0.389, -0.265), (0.37, -0.14), (0.351, -0.004), (0.281, 0.127), (0.155, 0.217), (0.0, 0.261)]),
    (-0.60, [(0.41, -0.44), (0.401, -0.263), (0.379, -0.135), (0.358, 0.002), (0.289, 0.14), (0.158, 0.229), (0.0, 0.269)]),
    (-0.20, [(0.415, -0.44), (0.397, -0.262), (0.383, -0.133), (0.364, 0.007), (0.289, 0.142), (0.16, 0.236), (0.0, 0.272)]),
    (0.20, [(0.39, -0.44), (0.372, -0.26), (0.352, -0.141), (0.34, -0.008), (0.286, 0.138), (0.161, 0.239), (0.0, 0.272)]),
    (0.40, [(0.37, -0.45), (0.345, -0.28), (0.323, -0.15), (0.30, -0.03), (0.25, 0.08), (0.15, 0.15), (0.0, 0.17)]),
    (0.60, [(0.33, -0.28), (0.30, -0.20), (0.274, -0.151), (0.218, -0.05), (0.111, -0.006), (0.0, -0.009)]),
    (0.70, [(0.29, -0.27), (0.26, -0.25), (0.22, -0.23), (0.12, -0.22), (0.0, -0.22)]),
]
SPLIT = 0.25                                 # the pilot's hood front, against the windscreen arch
BOWS = (-1.40, -0.85, -0.25)                 # the gunner's glazing's bow, its front at the armoured back, the pilot's middle
FRONT_PANE_X = 0.12                          # the armoured pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -1.95, SPLIT, nz=22) + C.glass_triangles(sh, E, SPLIT + 0.04, 0.70, nz=8)
    # the hood: its tail frame, its bows, the front bow; the sill and mid-height frames
    C.bow(m, sh, E, -1.93, 0.035, 0.025, out, inner)
    for z in BOWS:
        C.bow(m, sh, E, z, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, -1.95, SPLIT, 0.045, 0.035, out, inner, steps=22)
    C.member(m, sh, E, 0.45, -1.95, SPLIT, 0.03, 0.025, out, inner, steps=22)
    # the windscreen: the arch, the armoured pane's two heavy posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.025, 0.04, 0.025, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.06, 0.70, 0.035, 0.025, out, inner, steps=6)
    C.bow(m, sh, E, 0.685, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.04, 0.70, 0.045, 0.035, out, inner, steps=6)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
