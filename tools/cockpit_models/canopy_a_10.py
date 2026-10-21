#!/usr/bin/env python3
"""A-10 canopy, made by hand: the canopy's shape from the model's old glass (glass_sections.py), the windscreen's
(which the model had no glass for, only its long frame posts) set along those posts down to the nose (sections.py),
and the real frame layout.

The A-10 canopy (side views and photos of the type): a heavy windscreen of a wide flat bulletproof front pane between
two side panes, its two thick posts down to the base frame on the nose and its arch at the rear; the canopy a single
blown bubble hinged at the back, with no bow across it, held by its frame (the front bow against the windscreen arch,
the rear bow over the bulkhead behind the seat, the sill rails).

Run after the cockpit (a_10.py), on its output. Usage: python3 tools/cockpit_models/canopy_a_10.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'a_10'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.38, [(0.33, -0.15), (0.33, -0.05), (0.30, 0.05), (0.22, 0.10), (0.12, 0.12), (0.0, 0.13)]),
    (-0.20, [(0.422, -0.24), (0.418, -0.093), (0.384, 0.037), (0.303, 0.137), (0.188, 0.179), (0.09, 0.199), (0.0, 0.217)]),
    (0.00, [(0.481, -0.264), (0.457, -0.095), (0.43, 0.05), (0.369, 0.182), (0.259, 0.273), (0.125, 0.303), (0.0, 0.312)]),
    (0.20, [(0.485, -0.26), (0.456, -0.074), (0.426, 0.07), (0.376, 0.207), (0.282, 0.323), (0.142, 0.372), (0.0, 0.381)]),
    (0.40, [(0.475, -0.23), (0.43, -0.039), (0.41, 0.1), (0.358, 0.229), (0.269, 0.34), (0.138, 0.396), (0.0, 0.418)]),
    (0.45, [(0.47, -0.25), (0.43, -0.05), (0.405, 0.09), (0.35, 0.22), (0.26, 0.32), (0.17, 0.36), (0.0, 0.38)]),
    (0.60, [(0.45, -0.33), (0.42, -0.15), (0.38, 0.0), (0.31, 0.13), (0.23, 0.21), (0.17, 0.245), (0.0, 0.25)]),
    (0.80, [(0.43, -0.42), (0.40, -0.25), (0.35, -0.10), (0.28, 0.0), (0.21, 0.07), (0.17, 0.09), (0.0, 0.095)]),
    (1.00, [(0.41, -0.45), (0.38, -0.33), (0.33, -0.21), (0.26, -0.12), (0.20, -0.07), (0.17, -0.06), (0.0, -0.06)]),
    (1.20, [(0.37, -0.50), (0.34, -0.42), (0.29, -0.33), (0.23, -0.26), (0.17, -0.21), (0.0, -0.21)]),
    (1.38, [(0.32, -0.52), (0.28, -0.46), (0.23, -0.40), (0.17, -0.36), (0.0, -0.355)]),
]
SPLIT = 0.42                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.17              # the wide flat front pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.38, SPLIT, nz=12) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.38, nz=12)
    # the canopy: its rear bow, its front bow, the sill rails
    C.bow(m, sh, E, -0.36, 0.045, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, -0.38, SPLIT, 0.05, 0.035, out, inner)
    # the windscreen: the arch, the front pane's two thick posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.045, 0.025, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), 0.47, 1.38, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, 1.365, 0.035, 0.025, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.38, 0.05, 0.035, out, inner, steps=12)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], colour=(120, 124, 128),       # the frames in the airframe grey
          clear=(C.Shape(SECTIONS), 0.42, 1.45),                                # the old open windscreen's posts
          boxes=[((-0.40, 0.12, -1.30), (0.40, 0.50, -0.36))])                  # its old frame blocks over the bulkhead
