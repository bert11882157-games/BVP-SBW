#!/usr/bin/env python3
"""Panavia Tornado IDS canopy, made by hand: smooth sections set to the model's old glass (glass_sections.py: its sill
and its top at each station; the old glass's own sections are uneven), and the real frame layout.

The Tornado canopy (side views and photos of the type): a windscreen of a flat front pane between two curved side
panes, its two posts down to the base frame on the nose and its arch over the head-up display; one long canopy over
both seats, hinged at the back, with no bow across it: only the frame round its edge (the front bow against the
windscreen arch, the sill rails, the frame at its tail on the spine).

Run after the cockpits (panavia_tornado_ids_marineflieger.py), on their output.
Usage: python3 tools/cockpit_models/canopy_panavia_tornado_ids_marineflieger.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'panavia_tornado_ids_marineflieger'
SOURCE_BONE = 'wreck_fuselage_1__hull'


def _section(xs, ys, yt, n=7, k=3.0):
    """a smooth section from the sill (xs, ys) up to the top centre (0, yt): a superellipse quadrant (k 2 an ellipse,
    larger: the near-vertical sides of this tall, narrow canopy)"""
    out = []
    for i in range(n):
        t = math.pi / 2 * i / (n - 1)
        out.append((round(xs * math.cos(t) ** (2 / k), 4), round(ys + (yt - ys) * math.sin(t) ** (2 / k), 4)))
    return out


# the sill (x, y) and the top's height at each station, metres from the pilot's eye, off the model's old glass
SECTIONS = [(z, _section(xs, ys, yt)) for z, xs, ys, yt in (
    (-2.00, 0.09, 0.16, 0.271), (-1.80, 0.20, -0.05, 0.303), (-1.60, 0.235, -0.22, 0.329), (-1.40, 0.26, -0.33, 0.349),
    (-1.20, 0.275, -0.42, 0.363), (-1.00, 0.28, -0.46, 0.373), (-0.80, 0.283, -0.47, 0.380), (-0.40, 0.284, -0.466, 0.382),
    (0.00, 0.281, -0.45, 0.357), (0.20, 0.278, -0.43, 0.333), (0.40, 0.274, -0.41, 0.301), (0.60, 0.269, -0.385, 0.265),
    (0.80, 0.262, -0.36, 0.222), (1.00, 0.252, -0.31, 0.150), (1.20, 0.239, -0.28, 0.043), (1.40, 0.221, -0.27, -0.077),
    (1.60, 0.186, -0.25, -0.192))]
SPLIT = 0.55                     # the canopy's front edge, against the windscreen arch over the HUD
FRONT_PANE_X = 0.11              # the flat front pane's edges, out from the centre line
# the flat front pane (flat_pane): from the arch to the base frame, straight-sided
PANE = (SPLIT + 0.05, 1.60, FRONT_PANE_X, 0.01, 0.028)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -2.00, SPLIT, nz=34) + C.glass_triangles(sh, E, SPLIT + 0.05, 1.60, nz=18)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -1.98, 0.04, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, -2.00, SPLIT, 0.045, 0.035, out, inner, steps=34)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.03, 0.04, 0.022, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.05, 1.60, 0.026, 0.02, out, inner, sides=(1, -1),
             steps=18)
    C.bow(m, sh, E, 1.585, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.05, 1.60, 0.045, 0.035, out, inner, steps=24)
    return m, glass


def main(argv):
    C.run(VID, SOURCE_BONE, build, argv)


if __name__ == '__main__':
    main(sys.argv[1:])
