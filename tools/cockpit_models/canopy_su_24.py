#!/usr/bin/env python3
"""Sukhoi Su-24 canopy, made by hand: this model has no canopy glass of its own, only an old frame, so the canopy is
made here from smooth sections set on the cockpit's sills (sections.py: x +-0.74 from the centre line, y -0.22 at the
pilot's eye height), symmetric about the centre line (the crew sit side by side, the pilot left, the navigator right).

The Su-24M (photos of 09 Blue at Monino, the type's four-view): a long windscreen of two large panes either side of a
centre post, down from the arch to a point on the nose; behind the arch two hatches side by side, each hinged at its
back and lifting up, glazed over their front two thirds (a pane with rounded rear corners in a thick frame) and solid
metal behind it to the spine; the bar between the hatches along the top; a wide sill ledge.

Run after the cockpit (su_24.py), on its output. Usage: python3 tools/cockpit_models/canopy_su_24.py
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'su_24'
SOURCE_BONE = 'wreck_fuselage_1__hull'
CENTRE_LINE = True               # the sections are about the aircraft's centre line, not the pilot's eye


def _section(xs, ys, yt, n=8, k=3.0):
    """a smooth section from the sill (xs, ys) up to the top centre (0, yt): a superellipse quadrant (k 2 an ellipse;
    higher, the wide flat top of a side-by-side canopy)"""
    out = []
    for i in range(n):
        t = math.pi / 2 * i / (n - 1)
        out.append((round(xs * math.cos(t) ** (2 / k), 4), round(ys + (yt - ys) * math.sin(t) ** (2 / k), 4)))
    return out


# the sill (x, y) and the top's height at each station, metres from the centre line at the pilot's eye
SECTIONS = [(z, _section(xs, ys, yt)) for z, xs, ys, yt in (
    (-0.74, 0.70, -0.22, 0.420), (-0.40, 0.74, -0.22, 0.430), (0.00, 0.75, -0.22, 0.430), (0.35, 0.74, -0.22, 0.410),
    (0.60, 0.70, -0.22, 0.250), (0.85, 0.62, -0.22, 0.080), (1.05, 0.52, -0.22, -0.060), (1.25, 0.40, -0.22, -0.200))]
ARCH = 0.35                      # the windscreen's arch, the hatches' front edge
GLASS_END = -0.30                # the hatches' glass ends here; solid metal behind it to the spine
BACK = -0.74
BASE = 1.25
BOW_W = 0.06
BAND = 0.10                      # the sill band under the hatch glass, an s
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
    glass = C.glass_triangles(sh, E, GLASS_END, ARCH, nz=16) + C.glass_triangles(sh, E, ARCH, BASE, nz=18)
    # the hatches: solid metal behind the glass, the bow closing it, the sill ledge, the band with the panes' rounded
    # lower rear corners (their upper rear corners meet the bar between the two hatches)
    C.skin(m, sh, E, BACK, GLASS_END, out, nz=6, ns=10)
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
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], centre_line=CENTRE_LINE)
