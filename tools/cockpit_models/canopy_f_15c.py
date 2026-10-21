#!/usr/bin/env python3
"""F-15C canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The F-15C canopy (side views and photos of the type): a fixed one-piece windscreen, no centre post, held by its base
frame on the nose and the heavy arch at its rear just behind the HUD; the canopy one long blown transparency with no
bow across it, hinged at the back, held by the frame round its edge (the front bow against the windscreen arch, the
sill rails along both sides, the frame at its tail on the spine).

Run after the cockpit (f_15c.py), on its output. Usage: python3 tools/cockpit_models/canopy_f_15c.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L          # noqa: E402
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'f_15c'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-2.85, [(0.09, 0.03), (0.105, 0.06), (0.098, 0.10), (0.075, 0.135), (0.04, 0.148), (0.0, 0.15)]),
    (-2.60, [(0.20, -0.03), (0.194, 0.049), (0.183, 0.111), (0.162, 0.171), (0.113, 0.209), (0.053, 0.222), (0.0, 0.229)]),
    (-2.30, [(0.316, -0.09), (0.302, 0.024), (0.286, 0.121), (0.26, 0.218), (0.176, 0.273), (0.083, 0.296), (0.0, 0.308)]),
    (-2.00, [(0.335, -0.12), (0.313, 0.019), (0.305, 0.121), (0.289, 0.232), (0.214, 0.318), (0.105, 0.358), (0.0, 0.375)]),
    (-1.60, [(0.335, -0.17), (0.316, 0.031), (0.302, 0.14), (0.291, 0.246), (0.245, 0.371), (0.132, 0.448), (0.0, 0.474)]),
    (-1.20, [(0.318, -0.216), (0.322, -0.076), (0.315, 0.041), (0.305, 0.15), (0.299, 0.263), (0.26, 0.401), (0.148, 0.499), (0.0, 0.548)]),
    (-0.80, [(0.325, -0.238), (0.327, -0.091), (0.318, 0.024), (0.305, 0.125), (0.296, 0.245), (0.255, 0.378), (0.151, 0.488), (0.0, 0.554)]),
    (-0.40, [(0.38, -0.268), (0.372, -0.164), (0.358, -0.032), (0.348, 0.086), (0.326, 0.212), (0.265, 0.336), (0.149, 0.431), (0.0, 0.473)]),
    (0.00, [(0.435, -0.30), (0.411, -0.112), (0.384, 0.018), (0.339, 0.142), (0.266, 0.262), (0.139, 0.323), (0.0, 0.345)]),
    (0.40, [(0.43, -0.344), (0.386, -0.192), (0.359, -0.07), (0.315, 0.045), (0.239, 0.145), (0.123, 0.195), (0.0, 0.213)]),
    (0.70, [(0.355, -0.38), (0.318, -0.253), (0.292, -0.154), (0.256, -0.061), (0.195, 0.022), (0.103, 0.071), (0.0, 0.09)]),
    (1.00, [(0.261, -0.43), (0.253, -0.34), (0.226, -0.263), (0.186, -0.199), (0.134, -0.149), (0.065, -0.132), (0.0, -0.127)]),
    (1.20, [(0.143, -0.435), (0.145, -0.385), (0.125, -0.342), (0.103, -0.307), (0.072, -0.282), (0.035, -0.272), (0.0, -0.269)]),
    (1.26, [(0.11, -0.43), (0.105, -0.39), (0.09, -0.35), (0.06, -0.322), (0.03, -0.31), (0.0, -0.306)]),
]
SPLIT = 0.46                     # the canopy's front edge, against the windscreen arch behind the HUD


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -2.85, SPLIT, nz=26) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.26, nz=10)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -2.83, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -2.85, SPLIT, 0.05, 0.035, out, inner, steps=26)
    # the windscreen: the heavy arch, the base frame on the nose and its side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.02, out, inner)
    C.bow(m, sh, E, 1.245, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.26, 0.05, 0.035, out, inner)
    return m, glass


def main(argv):
    ac = L.Aircraft(VID)
    E = ac.eye(0)
    old = C.original_glass(VID)
    atlas = C.atlas_for(VID)
    m, glass = build(atlas, E, C.frame_colour(ac))
    n_old, n_new = C.install(ac, m, SOURCE_BONE, glass, old)
    print(f'{VID} canopy: {n_old} old frame faces removed; {n_new} frame faces, {len(glass) // 3} glass triangles')
    if '--dry-run' not in argv:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
