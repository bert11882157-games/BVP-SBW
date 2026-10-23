#!/usr/bin/env python3
"""Rafale canopy, made by hand: the canopy's shape from the model's old glass (glass_sections.py), the windscreen's from
the model's own dark windscreen fairing (its outer line, from the fuselage's shoulder up to its top), now glass.

The Rafale canopy (side views and photos of the type): a long one-piece windscreen down to the nose, with no frame
across it, joined to the canopy by one arch; the canopy one large blown transparency hinged at its rear, held by the
frame round its edge (the arch at the joint, the sill rails, the frame at its tail on the spine).

Run after the cockpit (rafale.py), on its output. Usage: python3 tools/cockpit_models/canopy_rafale.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'rafale'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's sections, metres from the eye: z, then the right half from the sill up over the top
CANOPY = [
    (-0.70, [(0.25, -0.10), (0.24, -0.03), (0.21, 0.04), (0.15, 0.09), (0.08, 0.12), (0.0, 0.13)]),
    (-0.60, [(0.315, -0.225), (0.296, -0.114), (0.278, -0.02), (0.239, 0.066), (0.168, 0.125), (0.085, 0.156), (0.0, 0.171)]),
    (-0.40, [(0.406, -0.306), (0.382, -0.163), (0.358, -0.043), (0.31, 0.069), (0.216, 0.143), (0.11, 0.186), (0.0, 0.213)]),
    (-0.20, [(0.442, -0.351), (0.414, -0.195), (0.393, -0.063), (0.343, 0.061), (0.238, 0.142), (0.121, 0.187), (0.0, 0.215)]),
    (0.00, [(0.449, -0.363), (0.419, -0.205), (0.401, -0.07), (0.351, 0.057), (0.243, 0.139), (0.122, 0.184), (0.0, 0.209)]),
    (0.20, [(0.444, -0.354), (0.416, -0.197), (0.397, -0.064), (0.345, 0.06), (0.238, 0.14), (0.119, 0.18), (0.0, 0.2)]),
    (0.40, [(0.427, -0.325), (0.404, -0.174), (0.381, -0.046), (0.326, 0.07), (0.222, 0.142), (0.11, 0.176), (0.0, 0.194)]),
    (0.60, [(0.396, -0.281), (0.377, -0.142), (0.35, -0.023), (0.29, 0.077), (0.195, 0.137), (0.098, 0.17), (0.0, 0.187)]),
    (0.86, [(0.33, -0.25), (0.32, -0.12), (0.29, -0.01), (0.236, 0.074), (0.165, 0.13), (0.082, 0.156), (0.0, 0.17)]),
]
SPLIT = 0.84                     # the canopy's front edge, the arch at the joint with the windscreen


def _section(xs, ys, yt, n=7):
    """a smooth section from the shoulder (xs, ys) up to the top centre (0, yt): a quarter ellipse"""
    return [(round(xs * math.cos(t), 4), round(ys + (yt - ys) * math.sin(t), 4))
            for t in [math.pi / 2 * k / (n - 1) for k in range(n)]]


# the windscreen: the dark fairing's outer line (shoulder x, shoulder y, top y) at each station, measured off the model
WINDSCREEN = [(z, _section(xs, ys, yt)) for z, xs, ys, yt in (
    (0.84, 0.40, -0.40, 0.155), (0.90, 0.40, -0.43, 0.12), (1.00, 0.38, -0.45, 0.035), (1.20, 0.33, -0.462, -0.052),
    (1.40, 0.30, -0.485, -0.18), (1.60, 0.26, -0.51, -0.309), (1.75, 0.22, -0.52, -0.405))]


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    can, ws = C.Shape(CANOPY), C.Shape(WINDSCREEN)
    glass = C.glass_triangles(can, E, -0.70, SPLIT - 0.02, nz=20) + C.glass_triangles(ws, E, 0.87, 1.74, nz=12)
    # the canopy: its tail frame, its front bow at the joint, the sill rails
    C.bow(m, can, E, -0.68, 0.04, 0.03, out, inner)
    C.bow(m, can, E, SPLIT - 0.005, 0.03, 0.025, out, inner)          # (a slim joint: the Rafale's frames are light)
    C.member(m, can, E, 0.0, -0.70, SPLIT, 0.045, 0.03, out, inner, steps=20)
    # the windscreen, one piece: its arch at the joint, the base frame on the nose, the side rails
    C.bow(m, ws, E, 0.855, 0.035, 0.025, out, inner)
    C.bow(m, ws, E, 1.735, 0.03, 0.02, out, inner)
    C.member(m, ws, E, 0.0, 0.84, 1.75, 0.04, 0.03, out, inner, steps=14)
    return m, glass


def main(argv):
    # the old dark windscreen fairing under the new windscreen glass, its stepped sides included
    C.run(VID, SOURCE_BONE, build, argv, clear=(C.Shape(WINDSCREEN), 0.86, 1.75),
          boxes=[((-0.38, -0.40, 0.86), (0.38, 0.20, 1.72))])


if __name__ == '__main__':
    main(sys.argv[1:])
