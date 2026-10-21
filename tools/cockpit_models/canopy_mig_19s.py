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
SECTIONS = [
    (-1.05, [(0.17, -0.06), (0.174, 0.0), (0.148, 0.053), (0.113, 0.089), (0.068, 0.101), (0.0, 0.108)]),
    (-0.80, [(0.252, -0.142), (0.248, -0.054), (0.224, 0.022), (0.185, 0.086), (0.121, 0.12), (0.058, 0.133), (0.0, 0.141)]),
    (-0.60, [(0.301, -0.193), (0.292, -0.087), (0.266, 0.003), (0.224, 0.081), (0.153, 0.131), (0.075, 0.153), (0.0, 0.164)]),
    (-0.40, [(0.335, -0.222), (0.318, -0.102), (0.293, -0.003), (0.245, 0.082), (0.171, 0.142), (0.085, 0.17), (0.0, 0.184)]),
    (-0.20, [(0.369, -0.239), (0.354, -0.109), (0.321, 0.0), (0.263, 0.09), (0.185, 0.155), (0.092, 0.185), (0.0, 0.2)]),
    (0.00, [(0.376, -0.242), (0.358, -0.108), (0.319, 0.001), (0.265, 0.092), (0.186, 0.157), (0.094, 0.194), (0.0, 0.21)]),
    (0.20, [(0.35, -0.238), (0.324, -0.102), (0.292, -0.003), (0.246, 0.083), (0.181, 0.155), (0.095, 0.198), (0.0, 0.218)]),
    (0.40, [(0.328, -0.234), (0.3, -0.096), (0.263, -0.006), (0.229, 0.077), (0.175, 0.151), (0.095, 0.202), (0.0, 0.224)]),
    (0.60, [(0.304, -0.23), (0.269, -0.09), (0.239, -0.008), (0.21, 0.069), (0.168, 0.147), (0.093, 0.203), (0.0, 0.227)]),
    (0.80, [(0.263, -0.226), (0.232, -0.097), (0.204, -0.027), (0.178, 0.037), (0.139, 0.099), (0.08, 0.154), (0.0, 0.185)]),
    (1.00, [(0.208, -0.213), (0.201, -0.138), (0.175, -0.078), (0.136, -0.034), (0.092, -0.005), (0.05, 0.022), (0.0, 0.039)]),
    (1.15, [(0.17, -0.215), (0.16, -0.19), (0.12, -0.17), (0.06, -0.16), (0.0, -0.16)]),
]
SPLIT = 0.56                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.09              # the flat front pane's edges, out from the centre line


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
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), 0.82, 1.15, 0.025, 0.02, out, inner)
    C.bow(m, sh, E, 1.135, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 1.15, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
