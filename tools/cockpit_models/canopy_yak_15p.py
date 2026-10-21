#!/usr/bin/env python3
"""Yak-15P canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The Yak-15 canopy (side views and photos of the type): a framed windscreen of a flat front pane between two side
panes, its two posts down to the base frame on the nose and its arch at the rear; the canopy a blown teardrop sliding
aft, held by its frame (the front bow against the windscreen arch, the sill rails, the tail frame on the spine).

Run after the cockpit (yak_15p.py), on its output. Usage: python3 tools/cockpit_models/canopy_yak_15p.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'yak_15p'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.45, [(0.16, -0.17), (0.185, -0.12), (0.158, -0.054), (0.138, -0.007), (0.112, 0.031), (0.075, 0.09), (0.0, 0.12)]),
    (-0.20, [(0.331, -0.225), (0.306, -0.101), (0.263, -0.011), (0.211, 0.059), (0.148, 0.115), (0.084, 0.167), (0.0, 0.2)]),
    (0.00, [(0.418, -0.305), (0.391, -0.155), (0.343, -0.037), (0.277, 0.056), (0.187, 0.12), (0.101, 0.169), (0.0, 0.2)]),
    (0.20, [(0.405, -0.32), (0.374, -0.167), (0.33, -0.054), (0.271, 0.038), (0.188, 0.108), (0.104, 0.164), (0.0, 0.2)]),
    (0.40, [(0.355, -0.285), (0.328, -0.152), (0.286, -0.054), (0.228, 0.022), (0.158, 0.08), (0.09, 0.133), (0.0, 0.166)]),
    (0.60, [(0.266, -0.243), (0.259, -0.15), (0.216, -0.075), (0.163, -0.024), (0.104, 0.003), (0.056, 0.032), (0.0, 0.054)]),
    (0.76, [(0.20, -0.22), (0.19, -0.17), (0.15, -0.13), (0.09, -0.11), (0.0, -0.10)]),
]
SPLIT = 0.30                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.09              # the flat front pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.45, SPLIT, nz=12) + C.glass_triangles(sh, E, SPLIT + 0.045, 0.76, nz=8)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -0.43, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -0.45, SPLIT, 0.04, 0.03, out, inner)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), 0.48, 0.76, 0.025, 0.02, out, inner)
    C.bow(m, sh, E, 0.745, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 0.76, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
