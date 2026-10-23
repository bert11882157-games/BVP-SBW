#!/usr/bin/env python3
"""Yak-3 canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The Yak-3 canopy (side views and photos of the type): a windscreen of a flat armoured front pane between two curved
side panes, its two posts down to the base frame on the nose and its arch at the rear; the hood a blown transparency
sliding aft, held by its frame (the front bow against the windscreen arch, the rear bow, the sill rails); behind it
the fixed rear glazing running down onto the spine, with its own tail frame.

Run after the cockpit (yak_3.py), on its output. Usage: python3 tools/cockpit_models/canopy_yak_3.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'yak_3'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the glazing's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-1.15, [(0.20, -0.15), (0.205, -0.08), (0.18, -0.02), (0.14, 0.03), (0.08, 0.06), (0.0, 0.07)]),
    (-1.00, [(0.265, -0.16), (0.268, -0.071), (0.237, 0.01), (0.19, 0.07), (0.131, 0.1), (0.06, 0.105), (0.0, 0.106)]),
    (-0.80, [(0.35, -0.215), (0.34, -0.096), (0.303, 0.007), (0.249, 0.093), (0.174, 0.15), (0.083, 0.168), (0.0, 0.17)]),
    (-0.60, [(0.395, -0.222), (0.372, -0.085), (0.334, 0.029), (0.272, 0.121), (0.191, 0.189), (0.093, 0.215), (0.0, 0.22)]),
    (-0.40, [(0.419, -0.216), (0.384, -0.067), (0.342, 0.05), (0.285, 0.149), (0.202, 0.222), (0.099, 0.25), (0.0, 0.251)]),
    (-0.20, [(0.429, -0.213), (0.40, -0.062), (0.348, 0.058), (0.285, 0.155), (0.204, 0.231), (0.099, 0.257), (0.0, 0.258)]),
    (0.00, [(0.433, -0.217), (0.394, -0.064), (0.351, 0.056), (0.287, 0.154), (0.203, 0.227), (0.101, 0.259), (0.0, 0.263)]),
    (0.20, [(0.429, -0.223), (0.391, -0.071), (0.342, 0.047), (0.276, 0.139), (0.194, 0.208), (0.099, 0.248), (0.0, 0.265)]),
    (0.40, [(0.391, -0.234), (0.38, -0.097), (0.318, 0.014), (0.245, 0.092), (0.167, 0.145), (0.082, 0.169), (0.0, 0.175)]),
    (0.60, [(0.31, -0.20), (0.30, -0.11), (0.21, -0.034), (0.127, -0.005), (0.06, 0.003), (0.0, 0.0)]),
]
SPLIT = 0.28                     # the hood's front edge, against the windscreen arch
HOOD_REAR = -0.70                # the hood's rear bow, where the fixed rear glazing begins
FRONT_PANE_X = 0.09              # the flat armoured pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -1.15, SPLIT, nz=18) + C.glass_triangles(sh, E, SPLIT + 0.04, 0.60, nz=6)
    # the rear glazing's tail frame, the hood's rear and front bows, the sill rails
    C.bow(m, sh, E, -1.13, 0.03, 0.022, out, inner)
    C.bow(m, sh, E, HOOD_REAR, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.025, 0.016, out, inner)
    C.member(m, sh, E, 0.0, -1.15, SPLIT, 0.035, 0.028, out, inner, steps=18)
    # the windscreen: the arch, the armoured pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.024, 0.03, 0.018, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), 0.42, 0.60, 0.022, 0.018, out, inner, steps=4)
    C.bow(m, sh, E, 0.585, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.04, 0.60, 0.035, 0.028, out, inner, steps=6)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], colour=(122, 128, 136))   # the airframe grey (the sample caught the black spine)
