#!/usr/bin/env python3
"""Eurofighter Typhoon canopy, made by hand: the canopy's shape from the model's old glass (glass_sections.py), the
windscreen's from the model's own dark windscreen fairing (its outer line, from the fuselage's shoulder up to its top),
now glass.

The Typhoon canopy (side views and photos of the type): a long one-piece windscreen falling to the nose with no frame
across it, joined to the canopy by one arch; the canopy one large blown transparency, held by the frame round its edge
(the arch at the joint, the sill rails, the frame at its tail on the spine).

Run after the cockpit (eurofighter_typhoon.py), on its output.
Usage: python3 tools/cockpit_models/canopy_eurofighter_typhoon.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'eurofighter_typhoon'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's sections, metres from the eye: z, then the right half from the sill up over the top
CANOPY = [
    (-1.20, [(0.345, -0.298), (0.344, -0.177), (0.302, -0.074), (0.207, -0.016), (0.127, 0.011), (0.065, 0.036), (0.0, 0.06)]),
    (-1.00, [(0.428, -0.325), (0.397, -0.173), (0.36, -0.051), (0.28, 0.04), (0.182, 0.089), (0.1, 0.148), (0.0, 0.197)]),
    (-0.80, [(0.454, -0.335), (0.412, -0.174), (0.373, -0.047), (0.307, 0.058), (0.214, 0.132), (0.124, 0.218), (0.0, 0.287)]),
    (-0.60, [(0.461, -0.342), (0.42, -0.179), (0.384, -0.049), (0.327, 0.067), (0.243, 0.164), (0.141, 0.264), (0.0, 0.34)]),
    (-0.40, [(0.479, -0.364), (0.44, -0.19), (0.395, -0.055), (0.35, 0.068), (0.259, 0.173), (0.149, 0.273), (0.0, 0.347)]),
    (-0.20, [(0.504, -0.384), (0.463, -0.205), (0.43, -0.06), (0.371, 0.073), (0.274, 0.182), (0.155, 0.283), (0.0, 0.345)]),
    (0.00, [(0.506, -0.389), (0.465, -0.21), (0.433, -0.064), (0.373, 0.07), (0.276, 0.18), (0.155, 0.277), (0.0, 0.342)]),
    (0.20, [(0.483, -0.382), (0.45, -0.211), (0.417, -0.07), (0.363, 0.061), (0.271, 0.172), (0.153, 0.269), (0.0, 0.329)]),
    (0.40, [(0.466, -0.383), (0.419, -0.218), (0.402, -0.082), (0.352, 0.045), (0.263, 0.152), (0.145, 0.236), (0.0, 0.278)]),
    (0.48, [(0.455, -0.39), (0.42, -0.23), (0.393, -0.098), (0.338, 0.022), (0.245, 0.118), (0.132, 0.19), (0.0, 0.225)]),
]
SPLIT = 0.47                     # the canopy's front edge, the arch at the joint with the windscreen


def _section(xs, ys, yt, n=7):
    """a smooth section from the shoulder (xs, ys) up to the top centre (0, yt): a quarter ellipse"""
    return [(round(xs * math.cos(t), 4), round(ys + (yt - ys) * math.sin(t), 4))
            for t in [math.pi / 2 * k / (n - 1) for k in range(n)]]


# the windscreen: the dark fairing's outer line (shoulder x, shoulder y, top y) at each station, measured off the model
WINDSCREEN = [(z, _section(xs, ys, yt)) for z, xs, ys, yt in (
    (0.47, 0.45, -0.39, 0.225), (0.60, 0.42, -0.40, 0.14), (0.80, 0.38, -0.41, 0.045), (1.00, 0.34, -0.40, -0.047),
    (1.20, 0.30, -0.39, -0.141), (1.40, 0.26, -0.40, -0.234), (1.60, 0.20, -0.38, -0.327), (1.72, 0.12, -0.37, -0.36))]


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    can, ws = C.Shape(CANOPY), C.Shape(WINDSCREEN)
    glass = C.glass_triangles(can, E, -1.20, SPLIT - 0.02, nz=24) + C.glass_triangles(ws, E, 0.50, 1.70, nz=14)
    # the canopy: its tail frame, its front bow at the joint, the sill rails
    C.bow(m, can, E, -1.18, 0.04, 0.03, out, inner)
    C.bow(m, can, E, SPLIT - 0.005, 0.03, 0.025, out, inner)
    C.member(m, can, E, 0.0, -1.20, SPLIT, 0.045, 0.03, out, inner, steps=24)
    # the windscreen, one piece: its arch at the joint, the base frame on the nose, the side rails
    C.bow(m, ws, E, 0.485, 0.03, 0.025, out, inner)
    C.bow(m, ws, E, 1.705, 0.03, 0.02, out, inner)
    C.member(m, ws, E, 0.0, 0.47, 1.72, 0.04, 0.03, out, inner, steps=16)
    return m, glass


def main(argv):
    # the old dark windscreen fairing under the new windscreen glass, its stepped sides included
    C.run(VID, SOURCE_BONE, build, argv, clear=(C.Shape(WINDSCREEN), 0.50, 1.72),
          boxes=[((-0.36, -0.36, 0.50), (0.36, 0.25, 1.70))])


if __name__ == '__main__':
    main(sys.argv[1:])
