#!/usr/bin/env python3
"""MiG-9 canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the type's frame layout (no reference model of the MiG-9 is open to us; photos and side views).

The MiG-9 canopy: a windscreen of a flat armoured front pane between two curved side panes, its two posts down to the
base frame on the nose and its arch at the rear; the hood one blown transparency sliding aft, flat-topped over the
pilot, held by its frame (the front bow against the windscreen arch, the rear bow, the sill rails).

Run after the cockpit (mig_9.py), on its output. Usage: python3 tools/cockpit_models/canopy_mig_9.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'mig_9'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.84, [(0.20, -0.12), (0.18, -0.05), (0.13, -0.01), (0.07, 0.005), (0.0, 0.01)]),   # the fairing's end on the turtle deck
    (-0.66, [(0.24, -0.14), (0.22, -0.05), (0.17, 0.0), (0.09, 0.02), (0.0, 0.025)]),
    (-0.48, [(0.27, -0.11), (0.26, 0.0), (0.235, 0.07), (0.19, 0.13), (0.12, 0.165), (0.055, 0.175), (0.0, 0.175)]),   # over the model's opening's rear edge
    (-0.40, [(0.29, -0.11), (0.284, -0.005), (0.26, 0.083), (0.218, 0.159), (0.145, 0.202), (0.068, 0.214), (0.0, 0.214)]),
    (-0.30, [(0.38, -0.15), (0.372, -0.022), (0.342, 0.095), (0.262, 0.178), (0.16, 0.211), (0.075, 0.224), (0.0, 0.234)]),
    (-0.20, [(0.435, -0.20), (0.415, -0.037), (0.38, 0.092), (0.287, 0.182), (0.171, 0.213), (0.08, 0.225), (0.0, 0.238)]),
    (0.00, [(0.455, -0.21), (0.443, -0.053), (0.391, 0.081), (0.273, 0.158), (0.17, 0.197), (0.083, 0.221), (0.0, 0.242)]),
    (0.20, [(0.452, -0.215), (0.437, -0.06), (0.374, 0.069), (0.256, 0.14), (0.163, 0.181), (0.084, 0.215), (0.0, 0.244)]),
    (0.40, [(0.44, -0.215), (0.40, -0.061), (0.346, 0.058), (0.24, 0.126), (0.151, 0.162), (0.082, 0.206), (0.0, 0.244)]),
    (0.50, [(0.425, -0.22), (0.406, -0.073), (0.33, 0.042), (0.221, 0.102), (0.14, 0.136), (0.076, 0.176), (0.0, 0.211)]),
    (0.60, [(0.41, -0.20), (0.398, -0.09), (0.313, 0.02), (0.204, 0.072), (0.125, 0.099), (0.066, 0.13), (0.0, 0.158)]),
    (0.70, [(0.39, -0.19), (0.384, -0.112), (0.294, -0.008), (0.179, 0.033), (0.105, 0.05), (0.053, 0.069), (0.0, 0.088)]),
    (0.80, [(0.357, -0.175), (0.357, -0.134), (0.278, -0.036), (0.15, -0.01), (0.083, -0.002), (0.0, 0.01)]),
    (0.90, [(0.278, -0.166), (0.278, -0.149), (0.162, -0.087), (0.082, -0.076), (0.0, -0.066)]),   # its foot on the nose
]
SPLIT = 0.45                     # the hood's front edge, against the windscreen arch (where the flat top ends)
FRONT_PANE_X = 0.09              # the flat front pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.48, SPLIT, nz=15) + C.glass_triangles(sh, E, SPLIT + 0.045, 0.90, nz=8)
    # the hood: its rear bow, its front bow, the sill rails
    C.skin(m, sh, E, -0.84, -0.48, out)             # the metal fairing over the model's opening's rear notch, down onto the deck
    C.bow(m, sh, E, -0.465, 0.035, 0.025, out, inner)
    sec = sh.section(-0.47)                         # the bulkhead closing the hood's rear (the turtle deck behind it)
    for (xa, ya), (xb, yb) in zip(sec[:-1], sec[1:]):
        for sd in (1, -1):
            m.flat([E + [sd * xa, ya, -0.47], E + [sd * xb, yb, -0.47], E + [0.0, yb, -0.47], E + [0.0, ya, -0.47]], inner,
                   normal=[0, 0, 1])
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -0.48, SPLIT, 0.04, 0.03, out, inner, steps=15)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.045, 0.90, 0.025, 0.02, out, inner)
    C.bow(m, sh, E, 0.885, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 0.90, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], clear=(C.Shape(SECTIONS), -0.48, 0.90))     # the old cage's plates