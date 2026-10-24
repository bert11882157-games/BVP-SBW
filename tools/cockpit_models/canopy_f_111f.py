#!/usr/bin/env python3
"""F-111F canopy, made by hand: the shape after the model's old glass (sections.py on the be357476 asset), as smooth
sections symmetric about the centre line (the crew sit side by side, the pilot left, the WSO right): the sills x +-0.57
from the centre line at y -0.55 beside the eyes, the top on the centre line 0.20 over the eyes, back to the hump
behind the crew module 0.55 m behind them, the windscreen down to its foot on the nose 1.5 m ahead.

The F-111F (photos and the type's four-view): a windscreen of two large panes either side of a centre post, down from
the arch to the nose; behind the arch the two crew hatches side by side, each hinged on the centre line and lifting
up, glazed but for a frame round them; the bar between the hatches along the top; a sill ledge.

Run after the cockpit (f_111f.py), on its output. Usage: python3 tools/cockpit_models/canopy_f_111f.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'f_111f'
SOURCE_BONE = 'wreck_fuselage_1__hull'
CENTRE_LINE = True               # the sections are about the aircraft's centre line, not the pilot's eye


def _section(xs, ys, yt, n=8, k=2.4):
    """a smooth section from the sill (xs, ys) up to the top centre (0, yt): a superellipse quadrant (k 2 an ellipse;
    higher, the wide flat top of a side-by-side canopy)"""
    out = []
    for i in range(n):
        t = math.pi / 2 * i / (n - 1)
        out.append((round(xs * math.cos(t) ** (2 / k), 4), round(ys + (yt - ys) * math.sin(t) ** (2 / k), 4)))
    return out


# the sill (x, y) and the top's height at each station, metres from the centre line at the pilot's eye
SECTIONS = [(z, _section(xs, ys, yt)) for z, xs, ys, yt in (
    (-0.55, 0.32, -0.20, 0.170), (-0.35, 0.46, -0.40, 0.195), (-0.15, 0.53, -0.50, 0.200), (0.05, 0.56, -0.54, 0.200),
    (0.30, 0.56, -0.55, 0.190), (0.45, 0.53, -0.53, 0.140), (0.60, 0.48, -0.51, 0.080), (0.90, 0.37, -0.49, -0.040),
    (1.20, 0.25, -0.46, -0.180), (1.48, 0.13, -0.46, -0.340))]
ARCH = 0.45                      # the windscreen's arch, the hatches' front edge
GLASS_END = -0.47                # the hatches' glass ends here; a short metal frame behind it to the hump
BACK = -0.55
BASE = 1.48
BOW_W = 0.06
BAND = 0.05                      # the sill band under the hatch glass, an s
# removed (boxes about the centre line): an old block standing over the WSO's panel (the old radar hood), and the old
# windscreen's centre post, running on past the new windscreen's foot down into the nose, and an old shelf over the
# WSO's knees
GONE = [((-0.45, -0.40, 0.34), (-0.12, 0.02, 0.58)), ((-0.07, -0.60, 0.70), (0.07, 0.0, 1.95)),
        ((-0.50, -0.70, 0.0), (-0.10, 0.30, 0.45))]
CORNER = (0.08, 0.10)            # the hatch panes' rear corners: radius along z (m) and across (s)


def corner(d):
    R, r = CORNER
    if d >= R:
        return 0.0
    t = 1 - max(0.0, d) / R
    return r * (1 - math.sqrt(max(0.0, 1 - t * t)))


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    rear = GLASS_END + 0.029                                  # the hatch panes' rear edge, just off the rear bow
    glass = C.glass_triangles(sh, E, GLASS_END, ARCH, nz=16) + C.glass_triangles(sh, E, ARCH, BASE, nz=20)
    # the hatches: solid metal behind the glass, the bow closing it, the sill ledge, the band with the panes' rounded
    # lower rear corners (their upper rear corners meet the bar between the two hatches)
    C.skin(m, sh, E, BACK, GLASS_END, out, nz=3, ns=10)
    C.bow(m, sh, E, GLASS_END, 0.05, 0.03, out, inner)
    C.member(m, sh, E, 0.0, BACK, BASE, 0.05, 0.035, out, inner, steps=30)
    # (the corner's arc started a little past its vertical, so the band's top edge does not fold back on itself)
    C.fill(m, sh, E, rear, ARCH - BOW_W / 2, lambda z: BAND + corner(z - rear + 0.012), out, inner, steps=48)
    C.member(m, sh, E, 1.0, BACK, BASE, 0.06, 0.025, out, inner, sides=(1,), steps=40)
    # the arch (the hatches' front bow against it) and the windscreen's base on the nose
    C.bow(m, sh, E, ARCH, BOW_W, 0.03, out, inner)
    C.bow(m, sh, E, BASE - 0.015, 0.03, 0.02, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], boxes=GONE, centre_line=CENTRE_LINE)
