#!/usr/bin/env python3
"""F-14D canopy, made by hand: the shape from the model's old glass (glass_sections.py, on the be357476 asset),
cleaned up into smooth sections (metres from the pilot's eye; the RIO's eye is 1.45 m behind it), and the real frame
layout.

The F-14 canopy (side views and photos of the type): a windscreen of a flat armoured centre pane between two curved
side panes, its two posts down to the foot and the arch at the rear; one bubble over both crew, hinged at the back,
frameless but for its edge (the front bow against the windscreen arch, the sill rails, the tail frame on the spine).

Run after the cockpit (f_14d.py), on its output. Usage: python3 tools/cockpit_models/canopy_f_14d.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'f_14d'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's sections, metres from the eye: z, then the right half from the sill up over the top
CANOPY = [
    (-2.75, [(0.144, 0.098), (0.142, 0.149), (0.129, 0.192), (0.114, 0.234), (0.084, 0.267), (0.041, 0.28), (0.0, 0.275)]),
    (-2.50, [(0.197, -0.106), (0.22, -0.029), (0.212, 0.053), (0.204, 0.121), (0.203, 0.202), (0.17, 0.288), (0.092, 0.338), (0.0, 0.344)]),
    (-2.25, [(0.251, -0.197), (0.251, -0.081), (0.248, 0.007), (0.239, 0.087), (0.231, 0.177), (0.209, 0.295), (0.12, 0.375), (0.0, 0.413)]),
    (-2.00, [(0.287, -0.24), (0.276, -0.007), (0.255, 0.181), (0.228, 0.308), (0.131, 0.399), (0.0, 0.477)]),
    (-1.75, [(0.309, -0.252), (0.305, -0.107), (0.285, 0.093), (0.274, 0.203), (0.235, 0.326), (0.142, 0.439), (0.0, 0.53)]),
    (-1.50, [(0.32, -0.248), (0.306, -0.094), (0.297, 0.013), (0.287, 0.109), (0.283, 0.221), (0.24, 0.345), (0.148, 0.468), (0.0, 0.558)]),
    (-1.25, [(0.325, -0.238), (0.309, -0.081), (0.307, 0.027), (0.3, 0.127), (0.284, 0.237), (0.239, 0.358), (0.144, 0.472), (0.0, 0.576)]),
    (-1.00, [(0.327, -0.229), (0.312, -0.071), (0.312, 0.037), (0.301, 0.138), (0.283, 0.246), (0.236, 0.365), (0.143, 0.478), (0.0, 0.584)]),
    (-0.75, [(0.325, -0.228), (0.313, -0.072), (0.312, 0.037), (0.3, 0.137), (0.287, 0.249), (0.234, 0.362), (0.148, 0.494), (0.0, 0.578)]),
    (-0.50, [(0.324, -0.24), (0.314, -0.086), (0.312, 0.024), (0.298, 0.124), (0.282, 0.233), (0.234, 0.349), (0.149, 0.484), (0.0, 0.559)]),
    (-0.25, [(0.322, -0.264), (0.314, -0.112), (0.294, 0.097), (0.279, 0.205), (0.237, 0.328), (0.148, 0.453), (0.0, 0.53)]),
    (0.00, [(0.32, -0.3), (0.31, -0.148), (0.291, 0.058), (0.278, 0.166), (0.241, 0.294), (0.151, 0.426), (0.0, 0.491)]),
    (0.25, [(0.311, -0.352), (0.296, -0.202), (0.285, -0.003), (0.274, 0.103), (0.233, 0.224), (0.147, 0.352), (0.0, 0.409)]),
    (0.50, [(0.295, -0.413), (0.294, -0.275), (0.294, -0.173), (0.278, -0.079), (0.266, 0.024), (0.222, 0.136), (0.138, 0.252), (0.0, 0.294)]),
    (0.75, [(0.276, -0.469), (0.299, -0.351), (0.278, -0.245), (0.267, -0.155), (0.253, -0.058), (0.21, 0.046), (0.123, 0.136), (0.0, 0.173)]),
    (1.00, [(0.27, -0.46), (0.261, -0.305), (0.248, -0.221), (0.23, -0.134), (0.186, -0.045), (0.107, 0.027), (0.0, 0.059)]),
    (1.25, [(0.265, -0.453), (0.245, -0.359), (0.221, -0.284), (0.184, -0.22), (0.129, -0.175), (0.074, -0.126), (0.0, -0.097)]),
]
ARCH = 0.58                      # the windscreen's arch, the canopy's front bow against it
POST_X = 0.12                    # the windscreen's posts either side of the flat centre pane, out from the centre line

# an old fitting left floating high inside the canopy over the RIO's panel (eye-relative box)
GONE = [((-0.08, 0.45, -1.22), (0.08, 0.55, -0.95))]


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    can = C.Shape(CANOPY)
    glass = C.glass_triangles(can, E, -2.75, 1.25, nz=44)
    C.bow(m, can, E, -2.73, 0.04, 0.03, out, inner)             # the tail frame on the spine
    C.bow(m, can, E, ARCH, 0.05, 0.03, out, inner)                    # the windscreen arch and the canopy's front bow
    C.bow(m, can, E, 1.235, 0.03, 0.015, out, inner, s0=0.0, s1=1.0)   # the windscreen's foot on the nose
    C.member(m, can, E, 0.0, -2.75, 1.25, 0.05, 0.035, out, inner, steps=44)   # the sill rails
    C.member(m, can, E, lambda z: can.s_at_x(z, POST_X), ARCH + 0.025, 1.23, 0.025, 0.02, out, inner, steps=14)
    return m, glass


def main(argv):
    C.run(VID, SOURCE_BONE, build, argv, colour=(46, 48, 50), boxes=GONE)   # the frames black, as on the type (reference views)


if __name__ == '__main__':
    main(sys.argv[1:])
