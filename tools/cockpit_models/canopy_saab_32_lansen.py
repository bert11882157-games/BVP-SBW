#!/usr/bin/env python3
"""Saab J 32B Lansen canopy, made by hand: the shape the model's old glass gives (glass_sections.py), and the real
frame layout.

The Lansen canopy (side views and photos of the type): a windscreen of a flat armoured front pane between two side
panes, its two posts down to the base frame on the nose and its arch over the gunsight; one long canopy over both
seats, hinged at the back, with no bow across it between the seats: only the frame round its edge (the front bow
against the windscreen arch, the sill rails, the frame at its tail on the spine).

Run after the cockpits (saab_32_lansen.py), on their output. Usage: python3 tools/cockpit_models/canopy_saab_32_lansen.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'saab_32_lansen'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's and windscreen's sections, metres from the pilot's eye: z, then the right half from the sill up over the
# top (the model's old glass)
SECTIONS = [
    (-1.62, [(0.236, -0.131), (0.242, -0.049), (0.191, 0.018), (0.144, 0.063), (0.093, 0.088), (0.044, 0.098), (0.0, 0.101)]),
    (-1.40, [(0.379, -0.209), (0.338, -0.074), (0.301, 0.029), (0.241, 0.109), (0.171, 0.171), (0.096, 0.23), (0.0, 0.256)]),
    (-1.20, [(0.401, -0.257), (0.365, -0.115), (0.329, -0.002), (0.271, 0.09), (0.193, 0.161), (0.106, 0.222), (0.0, 0.256)]),
    (-1.00, [(0.425, -0.282), (0.388, -0.132), (0.354, -0.011), (0.291, 0.088), (0.205, 0.161), (0.111, 0.221), (0.0, 0.256)]),
    (-0.80, [(0.441, -0.29), (0.402, -0.134), (0.368, -0.009), (0.301, 0.094), (0.209, 0.165), (0.112, 0.221), (0.0, 0.256)]),
    (-0.40, [(0.449, -0.283), (0.421, -0.125), (0.371, 0.002), (0.302, 0.104), (0.204, 0.169), (0.109, 0.223), (0.0, 0.255)]),
    (0.00, [(0.451, -0.285), (0.42, -0.124), (0.369, 0.001), (0.3, 0.102), (0.202, 0.165), (0.109, 0.222), (0.0, 0.253)]),
    (0.20, [(0.453, -0.295), (0.415, -0.135), (0.376, -0.007), (0.302, 0.094), (0.206, 0.161), (0.11, 0.216), (0.0, 0.252)]),
    (0.40, [(0.455, -0.31), (0.418, -0.15), (0.371, -0.022), (0.305, 0.083), (0.21, 0.152), (0.112, 0.208), (0.0, 0.251)]),
    (0.60, [(0.456, -0.325), (0.423, -0.164), (0.373, -0.037), (0.309, 0.07), (0.215, 0.144), (0.118, 0.211), (0.0, 0.249)]),
    (0.80, [(0.437, -0.35), (0.402, -0.196), (0.369, -0.071), (0.301, 0.031), (0.205, 0.097), (0.108, 0.149), (0.0, 0.185)]),
    (1.00, [(0.393, -0.371), (0.38, -0.232), (0.346, -0.115), (0.272, -0.026), (0.173, 0.017), (0.088, 0.051), (0.0, 0.076)]),
    (1.20, [(0.341, -0.256), (0.306, -0.152), (0.218, -0.088), (0.125, -0.071), (0.059, -0.061), (0.0, -0.054)]),
    (1.40, [(0.262, -0.247), (0.201, -0.175), (0.098, -0.164), (0.05, -0.165), (0.018, -0.177), (0.0, -0.181)]),
]
SPLIT = 0.58                     # the canopy's front edge, against the windscreen arch over the gunsight
FRONT_PANE_X = 0.13              # the flat front pane's edges, out from the centre line
# the flat armoured front pane (flat_pane): from the arch to the base frame, straight-sided
PANE = (SPLIT + 0.05, 1.40, FRONT_PANE_X, 0.01, 0.03)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -1.62, SPLIT, nz=30) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.40, nz=16)
    # the canopy: its tail frame, its front bow, the sill rails (no bow between the seats)
    C.bow(m, sh, E, -1.60, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, -1.62, SPLIT, 0.045, 0.035, out, inner, steps=30)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.022, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.05, 1.40, 0.03, 0.022, out, inner, sides=(1, -1),
             steps=16)
    C.bow(m, sh, E, 1.385, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.40, 0.045, 0.035, out, inner, steps=16)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
