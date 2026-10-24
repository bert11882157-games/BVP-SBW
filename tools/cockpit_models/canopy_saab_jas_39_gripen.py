#!/usr/bin/env python3
"""Saab JAS 39 Gripen canopy, made by hand: the canopy's shape from the model's old glass (glass_sections.py), the
windscreen's from the model's own dark windscreen fairing (its outer line, from the fuselage's shoulder up to its top),
now glass.

The Gripen canopy (side views and photos of the type): a one-piece windscreen falling to the nose with no frame across
it, joined to the canopy by one arch; the canopy one large blown transparency hinged at its rear, held by the frame
round its edge (the arch at the joint, the sill rails, the frame at its tail on the spine).

Run after the cockpit (saab_jas_39_gripen.py), on its output.
Usage: python3 tools/cockpit_models/canopy_saab_jas_39_gripen.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'saab_jas_39_gripen'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's sections, metres from the eye: z, then the right half from the sill up over the top
CANOPY = [
    (-1.00, [(0.257, -0.046), (0.261, 0.044), (0.245, 0.126), (0.215, 0.205), (0.134, 0.235), (0.057, 0.229), (0.0, 0.222)]),
    (-0.80, [(0.366, -0.211), (0.348, -0.082), (0.333, 0.03), (0.322, 0.156), (0.221, 0.229), (0.099, 0.232), (0.0, 0.234)]),
    (-0.60, [(0.423, -0.289), (0.4, -0.14), (0.382, -0.012), (0.366, 0.13), (0.257, 0.221), (0.119, 0.238), (0.0, 0.247)]),
    (-0.40, [(0.453, -0.316), (0.43, -0.157), (0.413, -0.018), (0.383, 0.127), (0.268, 0.22), (0.127, 0.244), (0.0, 0.261)]),
    (-0.20, [(0.466, -0.315), (0.434, -0.151), (0.415, -0.01), (0.383, 0.134), (0.259, 0.214), (0.127, 0.252), (0.0, 0.274)]),
    (0.00, [(0.468, -0.303), (0.438, -0.137), (0.42, 0.004), (0.367, 0.136), (0.25, 0.218), (0.125, 0.26), (0.0, 0.287)]),
    (0.20, [(0.465, -0.291), (0.436, -0.127), (0.406, 0.011), (0.345, 0.133), (0.242, 0.218), (0.124, 0.268), (0.0, 0.297)]),
    (0.40, [(0.453, -0.282), (0.42, -0.13), (0.383, 0.008), (0.323, 0.121), (0.235, 0.211), (0.124, 0.271), (0.0, 0.304)]),
    (0.60, [(0.423, -0.279), (0.365, -0.128), (0.34, -0.013), (0.296, 0.094), (0.225, 0.189), (0.122, 0.257), (0.0, 0.29)]),
]
SPLIT = 0.58                     # the canopy's front edge, the arch at the joint with the windscreen


def _section(xs, ys, yt, n=7):
    """a smooth section from the shoulder (xs, ys) up to the top centre (0, yt): a quarter ellipse"""
    return [(round(xs * math.cos(t), 4), round(ys + (yt - ys) * math.sin(t), 4))
            for t in [math.pi / 2 * k / (n - 1) for k in range(n)]]


# the windscreen: the dark fairing's outer line (shoulder x, shoulder y, top y) at each station, measured off the model
WINDSCREEN = [(z, _section(xs, ys, yt)) for z, xs, ys, yt in (
    (0.58, 0.42, -0.28, 0.285), (0.70, 0.40, -0.40, 0.17), (0.80, 0.38, -0.44, 0.099), (1.00, 0.37, -0.46, 0.008),
    (1.20, 0.34, -0.44, -0.103), (1.40, 0.30, -0.45, -0.243), (1.55, 0.22, -0.42, -0.34))]


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    can, ws = C.Shape(CANOPY), C.Shape(WINDSCREEN)
    glass = C.glass_triangles(can, E, -1.00, SPLIT - 0.02, nz=22) + C.glass_triangles(ws, E, 0.61, 1.53, nz=12)
    # the canopy: its tail frame, its front bow at the joint, the sill rails
    C.bow(m, can, E, -0.98, 0.04, 0.03, out, inner)
    C.bow(m, can, E, SPLIT - 0.005, 0.03, 0.025, out, inner)
    C.member(m, can, E, 0.0, -1.00, SPLIT, 0.045, 0.03, out, inner, steps=22)
    # the windscreen, one piece: its arch at the joint, the base frame on the nose, the side rails
    C.bow(m, ws, E, 0.595, 0.03, 0.025, out, inner)
    C.bow(m, ws, E, 1.535, 0.03, 0.02, out, inner)
    C.member(m, ws, E, 0.0, 0.58, 1.55, 0.04, 0.03, out, inner, steps=14)
    return m, glass


def main(argv):
    # the old dark windscreen fairing under the new windscreen glass, its stepped sides included
    C.run(VID, SOURCE_BONE, build, argv, clear=(C.Shape(WINDSCREEN), 0.61, 1.55),
          boxes=[((-0.36, -0.40, 0.61), (0.36, 0.25, 1.53))])


if __name__ == '__main__':
    main(sys.argv[1:])
