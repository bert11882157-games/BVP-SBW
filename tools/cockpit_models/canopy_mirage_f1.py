#!/usr/bin/env python3
"""Mirage F1C canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The Mirage F1 canopy (side views and photos of the type): a long windscreen of a flat front pane between two side
panes, its two posts down to the base frame on the nose and its arch over the head-up display; the canopy one
transparency hinged at the back, held only by the frame round its edge (the front bow against the windscreen arch,
the sill rails, the frame at its tail on the spine).

Run after the cockpit (mirage_f1.py), on its output. Usage: python3 tools/cockpit_models/canopy_mirage_f1.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'mirage_f1'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-1.00, [(0.189, 0.02), (0.196, 0.086), (0.174, 0.145), (0.141, 0.192), (0.075, 0.196), (0.035, 0.202), (0.0, 0.212)]),
    (-0.80, [(0.297, -0.137), (0.278, -0.032), (0.26, 0.06), (0.244, 0.149), (0.158, 0.192), (0.076, 0.209), (0.0, 0.234)]),
    (-0.60, [(0.352, -0.239), (0.327, -0.115), (0.308, -0.011), (0.298, 0.106), (0.211, 0.182), (0.099, 0.199), (0.0, 0.232)]),
    (-0.40, [(0.364, -0.296), (0.349, -0.168), (0.335, -0.05), (0.32, 0.068), (0.235, 0.161), (0.113, 0.187), (0.0, 0.227)]),
    (-0.20, [(0.38, -0.335), (0.362, -0.201), (0.345, -0.08), (0.328, 0.041), (0.242, 0.138), (0.119, 0.171), (0.0, 0.221)]),
    (0.00, [(0.393, -0.361), (0.361, -0.222), (0.35, -0.105), (0.328, 0.021), (0.238, 0.112), (0.121, 0.157), (0.0, 0.212)]),
    (0.20, [(0.382, -0.371), (0.361, -0.237), (0.342, -0.12), (0.323, 0.003), (0.221, 0.074), (0.117, 0.131), (0.0, 0.2)]),
    (0.40, [(0.387, -0.399), (0.358, -0.262), (0.338, -0.148), (0.312, -0.03), (0.213, 0.039), (0.112, 0.091), (0.0, 0.15)]),
    (0.60, [(0.382, -0.428), (0.356, -0.293), (0.337, -0.18), (0.3, -0.069), (0.203, -0.005), (0.106, 0.041), (0.0, 0.08)]),
    (0.80, [(0.366, -0.453), (0.344, -0.324), (0.327, -0.214), (0.284, -0.112), (0.19, -0.054), (0.095, -0.02), (0.0, 0.009)]),
    (1.00, [(0.341, -0.479), (0.33, -0.359), (0.31, -0.254), (0.263, -0.162), (0.165, -0.123), (0.08, -0.102), (0.0, -0.079)]),
    (1.20, [(0.306, -0.502), (0.3, -0.395), (0.285, -0.299), (0.235, -0.218), (0.127, -0.209), (0.06, -0.198), (0.0, -0.186)]),
    (1.40, [(0.269, -0.429), (0.25, -0.38), (0.233, -0.349), (0.142, -0.316), (0.071, -0.319), (0.032, -0.318), (0.0, -0.314)]),
    (1.45, [(0.24, -0.44), (0.225, -0.39), (0.21, -0.36), (0.13, -0.335), (0.065, -0.335), (0.03, -0.335), (0.0, -0.335)]),
]
SPLIT = 0.50                     # the canopy's front edge, against the windscreen arch over the HUD
FRONT_PANE_X = 0.13              # the flat front pane's edges, out from the centre line
# the flat front pane (flat_pane): from the arch to the base frame, straight-sided
PANE = (SPLIT + 0.05, 1.45, FRONT_PANE_X, 0.01, 0.028)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -1.00, SPLIT, nz=18) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.45, nz=14)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -0.98, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, -1.00, SPLIT, 0.045, 0.035, out, inner, steps=18)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.022, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.05, 1.45, 0.028, 0.022, out, inner, sides=(1, -1),
             steps=16)
    C.bow(m, sh, E, 1.435, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.45, 0.045, 0.035, out, inner, steps=16)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
