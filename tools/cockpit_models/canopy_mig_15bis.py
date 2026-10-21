#!/usr/bin/env python3
"""MiG-15bis canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The MiG-15 canopy (side views and photos of the type): a short windscreen of a flat armoured front pane between two
curved side panes, its two posts down to the base frame on the nose and its arch at the rear; the canopy a blown
teardrop sliding aft, with no bow across it, held by its frame (the front bow against the windscreen arch, the sill
rails, the tail frame where it runs out onto the spine).

Run after the cockpit (mig_15bis.py), on its output. Usage: python3 tools/cockpit_models/canopy_mig_15bis.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'mig_15bis'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.95, [(0.13, -0.16), (0.13, -0.12), (0.115, -0.08), (0.085, -0.05), (0.045, -0.035), (0.0, -0.03)]),
    (-0.80, [(0.175, -0.153), (0.169, -0.093), (0.15, -0.041), (0.118, -0.003), (0.081, 0.024), (0.039, 0.033), (0.0, 0.036)]),
    (-0.60, [(0.242, -0.165), (0.231, -0.074), (0.203, -0.004), (0.165, 0.051), (0.117, 0.094), (0.062, 0.124), (0.0, 0.139)]),
    (-0.40, [(0.311, -0.196), (0.293, -0.081), (0.251, 0.005), (0.202, 0.073), (0.138, 0.122), (0.076, 0.162), (0.0, 0.187)]),
    (-0.20, [(0.363, -0.227), (0.336, -0.098), (0.298, 0.004), (0.235, 0.081), (0.158, 0.135), (0.085, 0.175), (0.0, 0.2)]),
    (0.00, [(0.38, -0.248), (0.345, -0.112), (0.309, -0.007), (0.25, 0.077), (0.171, 0.135), (0.091, 0.179), (0.0, 0.2)]),
    (0.20, [(0.376, -0.25), (0.352, -0.116), (0.304, -0.011), (0.24, 0.068), (0.169, 0.127), (0.092, 0.177), (0.0, 0.2)]),
    (0.40, [(0.34, -0.249), (0.319, -0.129), (0.274, -0.034), (0.21, 0.032), (0.14, 0.075), (0.075, 0.113), (0.0, 0.135)]),
    (0.60, [(0.265, -0.247), (0.262, -0.158), (0.228, -0.079), (0.154, -0.038), (0.094, -0.018), (0.043, -0.013), (0.0, -0.009)]),
    (0.74, [(0.20, -0.245), (0.19, -0.19), (0.15, -0.15), (0.09, -0.13), (0.0, -0.12)]),
]
SPLIT = 0.30                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.09              # the flat front pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.95, SPLIT, nz=16) + C.glass_triangles(sh, E, SPLIT + 0.045, 0.74, nz=8)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -0.93, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -0.95, SPLIT, 0.04, 0.03, out, inner, steps=16)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), 0.45, 0.74, 0.025, 0.02, out, inner)
    C.bow(m, sh, E, 0.725, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 0.74, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
