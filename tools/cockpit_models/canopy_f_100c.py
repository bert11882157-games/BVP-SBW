#!/usr/bin/env python3
"""North American F-100C Super Sabre canopy, made by hand: this model has no canopy glass of its own, only an old
frame, so the canopy is made here from smooth sections set on the cockpit's sills (sections.py: x +-0.32, y -0.15
beside the eye), its top at the old hoop's (0.33 m over the eye), back over the spine deck to the fairing 1.60 m behind the eye, and the
long sloping windscreen from its arch 0.48 m ahead down to its foot on the nose deck 1.55 m ahead, over the old
frame's posts.

The F-100C canopy (side views and photos of the type): a windscreen of a flat armoured front pane between two curved
side panes, its two posts down to the foot and the arch at the rear; the clamshell canopy hinged at its back,
frameless but for its edge (the front bow against the windscreen arch, the sill rails, the tail frame on the spine).

Run after the cockpit (f_100c.py), on its output. Usage: python3 tools/cockpit_models/canopy_f_100c.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'f_100c'
SOURCE_BONE = 'wreck_fuselage_1__hull'


def _section(xs, ys, yt, n=7, k=2.6):
    """a smooth section from the sill (xs, ys) up to the top centre (0, yt): a superellipse quadrant (k 2 an ellipse)"""
    out = []
    for i in range(n):
        t = math.pi / 2 * i / (n - 1)
        out.append((round(xs * math.cos(t) ** (2 / k), 4), round(ys + (yt - ys) * math.sin(t) ** (2 / k), 4)))
    return out


# the sill (x, y) and the top's height at each station, metres from the eye
SECTIONS = [(z, _section(xs, ys, yt)) for z, xs, ys, yt in (
    (-1.60, 0.40, -0.06, 0.205), (-1.35, 0.33, -0.08, 0.215), (-1.10, 0.31, -0.10, 0.225), (-0.85, 0.32, -0.13, 0.260),
    (-0.70, 0.32, -0.14, 0.280), (-0.40, 0.33, -0.15, 0.310), (-0.05, 0.33, -0.16, 0.330),
    (0.25, 0.33, -0.17, 0.290), (0.45, 0.32, -0.18, 0.200), (0.60, 0.31, -0.19, 0.110), (0.80, 0.29, -0.20, 0.020),
    (1.00, 0.27, -0.21, -0.060), (1.20, 0.25, -0.23, -0.140), (1.40, 0.22, -0.25, -0.220), (1.55, 0.20, -0.27, -0.270))]
SPLIT = 0.48                     # the canopy's front edge, against the windscreen arch
FRONT_PANE_X = 0.115             # the flat armoured pane's edges, out from the centre line
PANE = (SPLIT + 0.045, 1.55, FRONT_PANE_X, 0.01, 0.025)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    glass = C.glass_triangles(sh, E, -1.60, SPLIT, nz=36) + C.glass_triangles(sh, E, SPLIT + 0.045, 1.55, nz=20)
    # the canopy: its tail frame on the fairing, its front bow, the sill rails
    C.bow(m, sh, E, -1.58, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.028, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -1.60, SPLIT, 0.04, 0.03, out, inner, steps=36)
    # the windscreen: the arch, the front pane's two posts, the base frame on the nose, the side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.035, 0.02, out, inner)
    C.member(m, sh, E, lambda z: sh.s_at_x(z, FRONT_PANE_X), SPLIT + 0.045, 1.55, 0.025, 0.02, out, inner, steps=20)
    C.bow(m, sh, E, 1.535, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 1.55, 0.04, 0.03, out, inner)
    return m, glass


if __name__ == '__main__':
    # the old canopy's blue end wall 1.0 m behind the eye, now under the glass
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], boxes=[((-0.40, -0.12, -1.06), (0.40, 0.26, -0.96))])
