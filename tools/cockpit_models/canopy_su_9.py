#!/usr/bin/env python3
"""Su-9 canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The Su-9 canopy (side views and photos of the type, the Su-7's own layout): a windscreen of a flat armoured front
pane between two side panes, its two posts down to the base frame on the nose and its arch at the rear; the canopy a
tall narrow transparency sliding aft, held by its frame (the front bow against the windscreen arch, the tail frame
against the spine fairing, the sill rails) with the rear-view periscope housing on top.

Run after the cockpit (su_9.py), on its output. Usage: python3 tools/cockpit_models/canopy_su_9.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'su_9'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-1.00, [(0.188, -0.116), (0.175, -0.05), (0.166, 0.006), (0.148, 0.061), (0.108, 0.103), (0.056, 0.126), (0.0, 0.136)]),
    (-0.80, [(0.25, -0.23), (0.245, -0.176), (0.229, -0.088), (0.216, -0.015), (0.201, 0.062), (0.16, 0.137), (0.09, 0.192), (0.0, 0.218)]),
    (-0.60, [(0.282, -0.26), (0.27, -0.15), (0.244, -0.022), (0.227, 0.064), (0.186, 0.155), (0.104, 0.219), (0.0, 0.248)]),
    (-0.40, [(0.312, -0.27), (0.30, -0.17), (0.273, -0.019), (0.244, 0.071), (0.195, 0.162), (0.106, 0.22), (0.0, 0.245)]),
    (0.00, [(0.311, -0.25), (0.295, -0.15), (0.259, -0.014), (0.233, 0.073), (0.184, 0.157), (0.099, 0.208), (0.0, 0.229)]),
    (0.40, [(0.30, -0.25), (0.285, -0.16), (0.248, -0.027), (0.224, 0.056), (0.177, 0.137), (0.093, 0.182), (0.0, 0.195)]),
    (0.60, [(0.279, -0.259), (0.262, -0.14), (0.237, -0.059), (0.214, 0.02), (0.169, 0.097), (0.087, 0.128), (0.0, 0.132)]),
    (0.80, [(0.253, -0.268), (0.242, -0.176), (0.22, -0.101), (0.192, -0.032), (0.144, 0.027), (0.071, 0.045), (0.0, 0.045)]),
    (1.00, [(0.225, -0.27), (0.22, -0.221), (0.2, -0.152), (0.148, -0.107), (0.084, -0.096), (0.0, -0.096)]),
    (1.10, [(0.21, -0.27), (0.19, -0.24), (0.14, -0.22), (0.07, -0.215), (0.0, -0.215)]),
]
SPLIT = 0.45                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.10              # the flat front pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner, dark = atlas.swatch(skin), atlas.swatch((40, 42, 44)), atlas.swatch((26, 27, 28))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -1.00, SPLIT, nz=16) + C.glass_triangles(sh, E, SPLIT + 0.045, 1.10, nz=8)
    # the canopy: its tail frame, its front bow, the sill rails, the periscope
    C.bow(m, sh, E, -0.98, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -1.00, SPLIT, 0.04, 0.03, out, inner, steps=16)
    C.periscope(m, sh, E, 0.10, out, dark)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    # the Su-7 family's armoured front pane: tall, its top one round arch (photos of the type head-on: its round top reaches the windscreen's top frame)
    C.arched_pane(m, sh, E, 0.495, 1.1, 0.11, 0.11, 0.03, 0.02, out, inner)
    C.bow(m, sh, E, 1.085, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 1.10, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
