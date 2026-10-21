#!/usr/bin/env python3
"""MiG-19 (mig19) canopy, made by hand: the shape from the model's old glass (glass_sections.py; it sits on the
cockpit sills), cleaned up into smooth sections, and the real frame layout.

The MiG-19 canopy (side views and photos of the type): a short windscreen of a narrow flat armoured front pane
between two curved side panes, its two posts down to the base frame on the coaming and its arch at the rear; the
canopy a long blown teardrop sliding aft, with no bow across it, held by its frame (the front bow against the
windscreen arch, the sill rails, the tail frame where it runs out onto the spine).

Run after the cockpit (mig19.py), on its output. Usage: python3 tools/cockpit_models/canopy_mig19.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'mig19'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-1.25, [(0.30, -0.10), (0.32, -0.04), (0.29, 0.04), (0.20, 0.09), (0.10, 0.10), (0.0, 0.095)]),
    (-1.00, [(0.40, -0.24), (0.432, -0.136), (0.404, 0.001), (0.285, 0.083), (0.168, 0.111), (0.08, 0.12), (0.0, 0.12)]),
    (-0.80, [(0.47, -0.32), (0.489, -0.188), (0.464, -0.031), (0.363, 0.087), (0.22, 0.13), (0.101, 0.142), (0.0, 0.145)]),
    (-0.60, [(0.537, -0.40), (0.527, -0.219), (0.499, -0.051), (0.398, 0.082), (0.248, 0.139), (0.117, 0.16), (0.0, 0.165)]),
    (-0.40, [(0.561, -0.42), (0.546, -0.232), (0.516, -0.058), (0.421, 0.085), (0.263, 0.146), (0.128, 0.179), (0.0, 0.185)]),
    (0.00, [(0.577, -0.43), (0.563, -0.235), (0.522, -0.058), (0.423, 0.084), (0.274, 0.16), (0.134, 0.198), (0.0, 0.205)]),
    (0.40, [(0.572, -0.418), (0.556, -0.218), (0.51, -0.05), (0.406, 0.09), (0.265, 0.164), (0.133, 0.209), (0.0, 0.215)]),
    (0.80, [(0.548, -0.388), (0.517, -0.194), (0.472, -0.034), (0.372, 0.088), (0.251, 0.167), (0.128, 0.216), (0.0, 0.224)]),
    (1.20, [(0.491, -0.344), (0.454, -0.171), (0.412, -0.031), (0.328, 0.078), (0.229, 0.157), (0.12, 0.211), (0.0, 0.217)]),
    (1.40, [(0.42, -0.32), (0.409, -0.213), (0.374, -0.086), (0.3, 0.015), (0.192, 0.06), (0.085, 0.064), (0.0, 0.06)]),
    (1.50, [(0.36, -0.33), (0.30, -0.31), (0.20, -0.30), (0.0, -0.30)]),
]
SPLIT = 1.18                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.11              # the flat front pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -1.25, SPLIT, nz=24) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.50, nz=6)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -1.23, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, -1.25, SPLIT, 0.05, 0.035, out, inner, steps=24)
    # the windscreen: the arch, the front pane's two posts, the base frame on the coaming, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.022, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), 1.30, 1.50, 0.03, 0.022, out, inner, steps=4)
    C.bow(m, sh, E, 1.485, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.50, 0.05, 0.035, out, inner, steps=4)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
