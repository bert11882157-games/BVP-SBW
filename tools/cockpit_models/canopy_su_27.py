#!/usr/bin/env python3
"""Su-27 canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The Su-27 canopy (side views and photos of the type): a fixed windscreen of a flat armoured front pane between two
curved side panes, its two posts running down to the base frame on the nose, its arch at the rear over the HUD; the
canopy one large blown transparency with no bow across it, hinged at the back and lifting up and aft, held by the
frame round its edge (the front bow against the windscreen arch, the sill rails, the frame at its tail on the spine).

Run after the cockpit (su_27.py), on its output. Usage: python3 tools/cockpit_models/canopy_su_27.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L          # noqa: E402
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'su_27'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-1.20, [(0.215, -0.06), (0.227, 0.011), (0.191, 0.077), (0.122, 0.107), (0.072, 0.119), (0.032, 0.124), (0.0, 0.128)]),
    (-1.00, [(0.401, -0.221), (0.399, -0.081), (0.36, 0.042), (0.282, 0.134), (0.177, 0.176), (0.084, 0.192), (0.0, 0.199)]),
    (-0.70, [(0.468, -0.305), (0.441, -0.14), (0.407, -0.002), (0.349, 0.122), (0.257, 0.222), (0.128, 0.266), (0.0, 0.279)]),
    (-0.40, [(0.513, -0.345), (0.483, -0.164), (0.444, -0.014), (0.383, 0.123), (0.285, 0.236), (0.142, 0.286), (0.0, 0.296)]),
    (-0.10, [(0.522, -0.352), (0.489, -0.166), (0.452, -0.013), (0.385, 0.123), (0.282, 0.233), (0.142, 0.283), (0.0, 0.301)]),
    (0.20, [(0.496, -0.341), (0.45, -0.165), (0.416, -0.025), (0.359, 0.104), (0.264, 0.208), (0.137, 0.267), (0.0, 0.294)]),
    (0.50, [(0.459, -0.363), (0.433, -0.202), (0.397, -0.067), (0.34, 0.054), (0.251, 0.154), (0.125, 0.196), (0.0, 0.208)]),
    (0.80, [(0.423, -0.415), (0.408, -0.267), (0.362, -0.143), (0.3, -0.04), (0.211, 0.035), (0.104, 0.065), (0.0, 0.072)]),
    (1.10, [(0.362, -0.469), (0.354, -0.342), (0.303, -0.238), (0.24, -0.158), (0.158, -0.112), (0.078, -0.088), (0.0, -0.078)]),
    (1.30, [(0.318, -0.504), (0.317, -0.394), (0.261, -0.303), (0.19, -0.246), (0.121, -0.215), (0.059, -0.199), (0.0, -0.189)]),
    (1.50, [(0.288, -0.48), (0.27, -0.43), (0.199, -0.379), (0.121, -0.352), (0.069, -0.342), (0.0, -0.342)]),
]
SPLIT = 0.60                     # the canopy's front edge, against the windscreen arch over the HUD
FRONT_PANE_S = 0.80              # the flat front pane's edges across the windscreen's sections


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -1.20, SPLIT, nz=20) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.50, nz=10)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -1.18, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -1.20, SPLIT, 0.05, 0.035, out, inner, steps=20)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.02, out, inner)
    C.member(m, sh, E, FRONT_PANE_S, 0.95, 1.50, 0.03, 0.022, out, inner)
    C.bow(m, sh, E, 1.48, 0.04, 0.025, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.50, 0.05, 0.035, out, inner)
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
