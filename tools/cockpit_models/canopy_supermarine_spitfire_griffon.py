#!/usr/bin/env python3
"""Griffon Spitfire canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into
smooth sections, and the real frame layout.

The late Griffon Spitfire canopy (side views and photos of the type): a windscreen of a flat armoured front pane
between two curved quarter panes, its two posts down to the base frame on the nose and its arch at the rear; the hood
a blown bubble sliding aft, with no bow across it, held by its frame (the front bow against the windscreen arch, the
sill rails, the tail frame where it meets the cut-down spine).

Run after the cockpit (supermarine_spitfire_griffon.py), on its output.
Usage: python3 tools/cockpit_models/canopy_supermarine_spitfire_griffon.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'supermarine_spitfire_griffon'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the hood's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-1.00, [(0.122, -0.15), (0.122, -0.107), (0.113, -0.068), (0.099, -0.033), (0.069, -0.012), (0.031, -0.006), (0.0, -0.003)]),
    (-0.80, [(0.217, -0.155), (0.213, -0.086), (0.191, -0.021), (0.148, 0.027), (0.097, 0.054), (0.047, 0.067), (0.0, 0.075)]),
    (-0.60, [(0.26, -0.155), (0.254, -0.071), (0.215, 0.003), (0.17, 0.059), (0.109, 0.092), (0.056, 0.113), (0.0, 0.125)]),
    (-0.40, [(0.285, -0.155), (0.275, -0.057), (0.233, 0.023), (0.182, 0.082), (0.121, 0.122), (0.065, 0.151), (0.0, 0.169)]),
    (-0.20, [(0.298, -0.152), (0.281, -0.046), (0.238, 0.037), (0.19, 0.099), (0.132, 0.145), (0.072, 0.186), (0.0, 0.203)]),
    (0.00, [(0.302, -0.15), (0.275, -0.038), (0.239, 0.044), (0.191, 0.108), (0.136, 0.158), (0.076, 0.204), (0.0, 0.224)]),
    (0.20, [(0.30, -0.148), (0.273, -0.032), (0.233, 0.049), (0.188, 0.112), (0.136, 0.164), (0.078, 0.217), (0.0, 0.242)]),
    (0.40, [(0.272, -0.148), (0.249, -0.048), (0.22, 0.027), (0.177, 0.086), (0.125, 0.132), (0.069, 0.171), (0.0, 0.19)]),
    (0.58, [(0.23, -0.15), (0.21, -0.09), (0.18, -0.045), (0.13, -0.015), (0.07, 0.0), (0.0, 0.005)]),
]
SPLIT = 0.26                     # the hood's front edge, against the windscreen arch
FRONT_PANE_X = 0.08              # the flat armoured pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -1.00, SPLIT, nz=16) + C.glass_triangles(sh, E, SPLIT + 0.04, 0.58, nz=6)
    # the hood: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -0.98, 0.03, 0.022, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.025, 0.016, out, inner)
    C.member(m, sh, E, 0.0, -1.00, SPLIT, 0.035, 0.028, out, inner, steps=16)
    # the windscreen: the arch, the armoured pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.024, 0.03, 0.018, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), 0.40, 0.58, 0.022, 0.018, out, inner, steps=4)
    C.bow(m, sh, E, 0.565, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.04, 0.58, 0.035, 0.028, out, inner, steps=6)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], colour=(74, 84, 62))   # the camouflage dark green (the sample caught the cockpit green)
