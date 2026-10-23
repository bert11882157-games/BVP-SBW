#!/usr/bin/env python3
"""F8F-1 canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The F8F Bearcat canopy (side views and photos of the type): a windscreen of a flat bulletproof front pane between two
curved side panes, its two posts down to the base frame on the nose and its arch at the rear; the canopy a full
blown bubble sliding aft, with no bow across it, held by its frame (the front bow against the windscreen arch, the
sill rails, the tail frame where it meets the spine).

Run after the cockpit (f8f_1.py), on its output. Usage: python3 tools/cockpit_models/canopy_f8f_1.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'f8f_1'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.85, [(0.20, -0.23), (0.205, -0.17), (0.175, -0.11), (0.12, -0.07), (0.07, -0.05), (0.0, -0.035)]),
    (-0.60, [(0.344, -0.245), (0.334, -0.13), (0.285, -0.031), (0.229, 0.046), (0.155, 0.096), (0.079, 0.126), (0.0, 0.139)]),
    (-0.40, [(0.399, -0.235), (0.355, -0.087), (0.317, 0.021), (0.253, 0.105), (0.179, 0.169), (0.1, 0.23), (0.0, 0.263)]),
    (-0.20, [(0.421, -0.235), (0.374, -0.074), (0.329, 0.039), (0.258, 0.123), (0.187, 0.194), (0.106, 0.262), (0.0, 0.3)]),
    (0.00, [(0.429, -0.245), (0.387, -0.073), (0.332, 0.041), (0.266, 0.13), (0.196, 0.207), (0.111, 0.278), (0.0, 0.326)]),
    (0.20, [(0.43, -0.265), (0.376, -0.083), (0.334, 0.031), (0.275, 0.125), (0.205, 0.209), (0.115, 0.279), (0.0, 0.342)]),
    (0.40, [(0.418, -0.285), (0.372, -0.097), (0.331, 0.016), (0.279, 0.113), (0.214, 0.206), (0.125, 0.295), (0.0, 0.348)]),
    (0.60, [(0.398, -0.29), (0.355, -0.117), (0.317, -0.009), (0.261, 0.08), (0.191, 0.155), (0.11, 0.229), (0.0, 0.28)]),
    (0.80, [(0.33, -0.25), (0.319, -0.126), (0.272, -0.033), (0.214, 0.038), (0.133, 0.072), (0.066, 0.09), (0.0, 0.103)]),
    (0.92, [(0.27, -0.24), (0.25, -0.17), (0.20, -0.11), (0.12, -0.08), (0.0, -0.07)]),
]
SPLIT = 0.56                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.10              # the flat bulletproof pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.85, SPLIT, nz=18) + C.glass_triangles(sh, E, SPLIT + 0.045, 0.92, nz=6)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -0.83, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -0.85, SPLIT, 0.04, 0.03, out, inner, steps=18)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), 0.68, 0.92, 0.025, 0.02, out, inner, steps=6)
    C.bow(m, sh, E, 0.905, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 0.92, 0.04, 0.03, out, inner, steps=6)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], colour=(30, 40, 68))   # the gloss sea blue (the sample caught the cockpit green)
