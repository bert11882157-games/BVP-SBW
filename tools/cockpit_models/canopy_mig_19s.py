#!/usr/bin/env python3
"""MiG-19S canopy, made by hand: the shape from the model's old glass (glass_sections.py; it sits on the cockpit
sills), cleaned up into smooth sections, and the real frame layout.

The MiG-19 canopy (side views and photos of the type): a windscreen of a narrow flat armoured front pane between two
curved side panes, its two posts down to the base frame on the nose and its arch at the rear; the canopy a long
blown teardrop sliding aft, with no bow across it, held by its frame (the front bow against the windscreen arch, the
sill rails, the tail frame where it runs out onto the spine).

Run after the cockpit (mig_19s.py), on its output. Usage: python3 tools/cockpit_models/canopy_mig_19s.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'mig_19s'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [   # the DCS MiG-19P reference's profile (highest over the pilot, the joint at 0.40, a steady windscreen), between this model's spine and nose
    (-1.05, [(0.17, -0.06), (0.174, 0.0), (0.148, 0.053), (0.113, 0.089), (0.068, 0.101), (0.0, 0.108)]),
    (-0.80, [(0.252, -0.142), (0.248, -0.045), (0.224, 0.039), (0.185, 0.109), (0.121, 0.147), (0.058, 0.161), (0.0, 0.17)]),
    (-0.60, [(0.301, -0.193), (0.292, -0.073), (0.266, 0.028), (0.224, 0.116), (0.153, 0.173), (0.075, 0.198), (0.0, 0.21)]),
    (-0.40, [(0.335, -0.222), (0.318, -0.087), (0.293, 0.025), (0.245, 0.12), (0.171, 0.188), (0.085, 0.219), (0.0, 0.235)]),
    (-0.20, [(0.369, -0.239), (0.354, -0.094), (0.321, 0.027), (0.263, 0.127), (0.185, 0.2), (0.092, 0.233), (0.0, 0.25)]),
    (0.00, [(0.376, -0.242), (0.358, -0.094), (0.319, 0.027), (0.265, 0.127), (0.186, 0.199), (0.094, 0.24), (0.0, 0.258)]),
    (0.20, [(0.35, -0.238), (0.324, -0.092), (0.292, 0.015), (0.246, 0.108), (0.181, 0.185), (0.095, 0.231), (0.0, 0.253)]),
    (0.40, [(0.328, -0.234), (0.3, -0.091), (0.263, 0.002), (0.229, 0.088), (0.175, 0.164), (0.095, 0.217), (0.0, 0.24)]),
    (0.60, [(0.304, -0.23), (0.269, -0.107), (0.239, -0.036), (0.21, 0.032), (0.168, 0.1), (0.093, 0.149), (0.0, 0.17)]),
    (0.80, [(0.263, -0.226), (0.232, -0.128), (0.204, -0.075), (0.178, -0.027), (0.139, 0.02), (0.08, 0.062), (0.0, 0.085)]),
    (1.00, [(0.208, -0.213), (0.201, -0.15), (0.175, -0.099), (0.136, -0.062), (0.092, -0.037), (0.05, -0.014), (0.0, 0.0)]),
    (1.15, [(0.17, -0.215), (0.16, -0.19), (0.12, -0.17), (0.06, -0.16), (0.0, -0.16)]),
]
SPLIT = 0.40                     # the canopy's front edge, against the windscreen arch (the reference's joint)
FRONT_PANE_X = 0.10              # the flat front pane's edges, out from the centre line (the reference's)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -1.05, SPLIT, nz=20) + C.glass_triangles(sh, E, SPLIT + 0.045, 1.15, nz=8)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -1.03, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -1.05, SPLIT, 0.04, 0.03, out, inner, steps=20)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.045, 1.15, 0.025, 0.02, out, inner)
    C.bow(m, sh, E, 1.135, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 1.15, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
