#!/usr/bin/env python3
"""Yak-9U canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The Yak-9U canopy (side views and photos of the type): a windscreen of a flat armoured front pane between two curved
side panes, its two posts down to the base frame on the nose and its arch at the rear; the hood sliding aft, held by
its frame (the front bow against the windscreen arch, the rear bow, the sill rails); behind it the fixed rear glazing
running down onto the spine, with its own tail frame.

Run after the cockpit (yak_9u.py), on its output. Usage: python3 tools/cockpit_models/canopy_yak_9u.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'yak_9u'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the glazing's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.72, [(0.20, -0.16), (0.20, -0.10), (0.18, -0.04), (0.13, 0.0), (0.07, 0.02), (0.0, 0.03)]),
    (-0.60, [(0.247, -0.165), (0.243, -0.083), (0.219, -0.008), (0.165, 0.043), (0.099, 0.065), (0.049, 0.08), (0.0, 0.096)]),
    (-0.40, [(0.329, -0.17), (0.312, -0.051), (0.28, 0.044), (0.22, 0.116), (0.145, 0.165), (0.08, 0.205), (0.0, 0.237)]),
    (-0.20, [(0.356, -0.166), (0.338, -0.039), (0.299, 0.064), (0.241, 0.145), (0.163, 0.2), (0.088, 0.242), (0.0, 0.271)]),
    (0.00, [(0.361, -0.163), (0.345, -0.035), (0.306, 0.069), (0.247, 0.152), (0.169, 0.208), (0.088, 0.247), (0.0, 0.268)]),
    (0.20, [(0.356, -0.165), (0.34, -0.037), (0.304, 0.067), (0.248, 0.151), (0.173, 0.211), (0.088, 0.244), (0.0, 0.262)]),
    (0.40, [(0.331, -0.175), (0.32, -0.063), (0.294, 0.037), (0.243, 0.121), (0.169, 0.175), (0.079, 0.19), (0.0, 0.192)]),
    (0.60, [(0.275, -0.19), (0.282, -0.107), (0.254, -0.021), (0.208, 0.04), (0.115, 0.06), (0.052, 0.062), (0.0, 0.056)]),
    (0.68, [(0.24, -0.19), (0.24, -0.13), (0.21, -0.07), (0.15, -0.03), (0.07, -0.02), (0.0, -0.02)]),
]
SPLIT = 0.30                     # the hood's front edge, against the windscreen arch
HOOD_REAR = -0.30                # the hood's rear bow, where the fixed rear glazing begins
FRONT_PANE_X = 0.09              # the flat armoured pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.72, SPLIT, nz=14) + C.glass_triangles(sh, E, SPLIT + 0.04, 0.68, nz=6)
    # the rear glazing's tail frame, the hood's rear and front bows, the sill rails
    C.bow(m, sh, E, -0.70, 0.03, 0.022, out, inner)
    C.bow(m, sh, E, HOOD_REAR, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.025, 0.016, out, inner)
    C.member(m, sh, E, 0.0, -0.72, SPLIT, 0.035, 0.028, out, inner, steps=14)
    # the windscreen: the arch, the armoured pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.024, 0.03, 0.018, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), 0.45, 0.68, 0.022, 0.018, out, inner, steps=4)
    C.bow(m, sh, E, 0.665, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.04, 0.68, 0.035, 0.028, out, inner, steps=6)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
