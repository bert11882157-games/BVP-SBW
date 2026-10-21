#!/usr/bin/env python3
"""Su-25 canopy, made by hand: the shape set against the model's cockpit walls and nose deck (sections.py) and the
old glass, and the real frame layout.

The Su-25 canopy (side views and photos of the type): a windscreen of a thick flat armoured front pane between two
side panes, its two heavy posts down to the base frame on the nose and its arch at the rear; the canopy hinged on the
right and opening sideways, one transparency over the armoured tub held by its frame (the front bow against the
windscreen arch, the rear bow where it meets the metal hump behind the seat, the sill rails) with the periscope housing on top.

Run after the cockpit (su_25.py), on its output. Usage: python3 tools/cockpit_models/canopy_su_25.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'su_25'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [   # the DCS reference model's outline (viewer raycasts, work/handbuilt/su25_sections.py), on our sill
    (-0.45, [(0.45, -0.15), (0.40, -0.03), (0.33, 0.12), (0.24, 0.22), (0.12, 0.265), (0.0, 0.27)]),
    (-0.297, [(0.45, -0.18), (0.403, 0.046), (0.372, 0.117), (0.353, 0.152), (0.287, 0.222), (0.142, 0.293), (0.102, 0.299), (0.0, 0.303)]),
    (-0.143, [(0.45, -0.201), (0.442, -0.094), (0.421, -0.024), (0.385, 0.082), (0.347, 0.152), (0.286, 0.222), (0.102, 0.298), (0.0, 0.302)]),
    (0.010, [(0.45, -0.213), (0.441, -0.13), (0.422, -0.059), (0.393, 0.046), (0.342, 0.152), (0.283, 0.222), (0.142, 0.293), (0.0, 0.302)]),
    (0.164, [(0.45, -0.224), (0.438, -0.165), (0.414, -0.059), (0.396, 0.011), (0.352, 0.117), (0.276, 0.222), (0.137, 0.293), (0.0, 0.301)]),
    (0.317, [(0.45, -0.234), (0.433, -0.2), (0.421, -0.13), (0.395, -0.024), (0.356, 0.082), (0.318, 0.152), (0.262, 0.222), (0.0, 0.296)]),
    (0.471, [(0.45, -0.24), (0.407, -0.13), (0.39, -0.059), (0.35, 0.046), (0.314, 0.117), (0.262, 0.187), (0.102, 0.269), (0.0, 0.279)]),
    (0.701, [(0.45, -0.25), (0.388, -0.2), (0.365, -0.13), (0.339, -0.059), (0.304, 0.011), (0.254, 0.082), (0.165, 0.152), (0.0, 0.171)]),
    (0.778, [(0.45, -0.276), (0.383, -0.235), (0.358, -0.165), (0.33, -0.094), (0.294, -0.024), (0.27, 0.011), (0.195, 0.082), (0.0, 0.11)]),
    (0.931, [(0.45, -0.332), (0.356, -0.276), (0.328, -0.206), (0.312, -0.17), (0.292, -0.135), (0.237, -0.065), (0.102, -0.019), (0.0, -0.019)]),
    (1.085, [(0.446, -0.368), (0.307, -0.304), (0.289, -0.269), (0.263, -0.234), (0.233, -0.199), (0.153, -0.171), (0.0, -0.171)]),
    (1.20, [(0.44, -0.38), (0.36, -0.32), (0.26, -0.29), (0.14, -0.285), (0.0, -0.285)]),   # its foot on the nose deck
]
SPLIT = 0.55                     # the canopy's front edge, against the windscreen arch (the reference's joint)
FRONT_PANE_X = 0.17              # the armoured front pane's edges, out from the centre line


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner, dark = atlas.swatch(skin), atlas.swatch((40, 42, 44)), atlas.swatch((26, 27, 28))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, 0.0, SPLIT, nz=12) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.20, nz=10)
    # the canopy: the metal hump behind the seat, its rear bow, its front bow, the sill rails, the periscope
    C.skin(m, sh, E, -0.45, 0.0, out)                               # (the reference's hump reaches to over the headrest)
    C.bow(m, sh, E, -0.44, 0.03, 0.03, out, inner)
    C.bow(m, sh, E, 0.015, 0.045, 0.035, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.035, 0.022, out, inner)
    C.member(m, sh, E, 0.0, -0.45, SPLIT, 0.05, 0.04, out, inner)
    C.periscope(m, sh, E, 0.32, out, dark)
    # the windscreen: the arch, the armoured front pane's two heavy posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.035, 0.045, 0.025, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.05, 1.20, 0.04, 0.03, out, inner)   # (the flat pane runs the whole windscreen)
    C.bow(m, sh, E, 1.18, 0.04, 0.025, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.20, 0.05, 0.04, out, inner)
    # the deck under the windscreen ahead of the coaming, closing off the nose bay seen through the glass
    C.deck(m, sh, E, 0.95, 1.20, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], clear=(C.Shape(SECTIONS), -0.45, 1.20),     # the old cage's plates
          boxes=[((-0.20, -0.20, 0.72), (0.20, 0.15, 1.25)),                    # the old armoured-glass posts
                 ((-0.56, -0.31, 0.58), (0.56, 0.06, 0.78)),
                 ((-0.20, 0.0, 0.45), (0.20, 0.36, 0.95)),                      # the old posts' tops, over the new lower windscreen
                 ((0.40, -0.05, -0.27), (0.49, 0.05, -0.18))])                  # an old hump plate in the new hump's skin (z-fought)                   # the old windscreen's side plates, out past the new glass
