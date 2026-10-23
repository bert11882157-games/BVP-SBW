#!/usr/bin/env python3
"""MiG-23MLD canopy, made by hand: the shape set against the model's cockpit sills and nose deck (sections.py; the
old glass stops short of both), and the frame layout of bert's War Thunder reference (2026-10-23, shots 005926,
005934, 005938, 005944).

The reference: the hood and the windscreen about as long as each other. The windscreen's top runs straight down from
the arch to the nose, and its flat armoured front pane is a narrow almond in a thick ring, its apex at the arch and
its foot pointed on the nose, clear side panes either side. The hood is hinged at the back and lifts up. Each side is
one rectangular pane with rounded corners in a thick frame: the arch ahead, the rear bow, the bar along the top (the
halves meet on it), and a band along the sill (the glass stands clear of the sill). On top, a third of the way back,
is the rear-view periscope fairing, a fin with the square mirror housing on its back.

Run after the cockpit (mig_23mld.py), on its output. Usage: python3 tools/cockpit_models/canopy_mig_23mld.py
"""
import math, os, sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'mig_23mld'
SOURCE_BONE = 'wreck_fuselage_1__hull'

SPLIT = 0.25                     # the arch: the hood's front edge, the windscreen's top
TOP = 0.30                       # the hood's top at the arch
BASE = 1.00                      # the windscreen's foot on the nose deck (its top there at -0.27)

# the hood's sections, metres from the eye: z, then the right half from the sill up over the top
HOOD = [
    (-0.60, [(0.42, -0.25), (0.40, -0.05), (0.36, 0.10), (0.28, 0.20), (0.15, 0.255), (0.0, 0.27)]),
    (-0.20, [(0.44, -0.25), (0.43, -0.08), (0.40, 0.08), (0.33, 0.19), (0.18, 0.26), (0.0, 0.285)]),
    (0.00, [(0.45, -0.25), (0.44, -0.10), (0.40, 0.06), (0.33, 0.18), (0.20, 0.26), (0.0, 0.30)]),
    (0.20, [(0.44, -0.25), (0.42, -0.10), (0.38, 0.05), (0.30, 0.18), (0.17, 0.265), (0.0, 0.30)]),
]
# the windscreen's sills (on the model's cockpit sills) and its foot's flat section
SILLS = [(0.40, 0.42, -0.25), (0.60, 0.40, -0.25), (0.80, 0.36, -0.27), (1.00, 0.32, -0.29)]
FOOT = (1.00, [(0.32, -0.29), (0.29, -0.28), (0.20, -0.275), (0.0, -0.27)])
# the hood's cross-section shape (its 0.20 station), as fractions of the sill's x and of the height over the sill
PROFILE = [(x / 0.44, (y + 0.25) / 0.55) for x, y in HOOD[-1][1]]


def windscreen(z):
    """the windscreen's section at z: the hood's shape over the sill there, its top on the straight line down"""
    sx = float(np.interp(z, [s[0] for s in SILLS], [s[1] for s in SILLS]))
    sy = float(np.interp(z, [s[0] for s in SILLS], [s[2] for s in SILLS]))
    top = TOP + (FOOT[1][-1][1] - TOP) * (z - SPLIT) / (BASE - SPLIT)
    return [(u * sx, sy + v * (top - sy)) for u, v in PROFILE]


SECTIONS = HOOD + [(z, windscreen(z)) for z in (0.30, 0.45, 0.60, 0.75, 0.90)] + [FOOT]

PANE_TOP = SPLIT + 0.045         # the front pane's frame's apex, just ahead of the arch
PANE_FOOT = 0.975                # its pointed foot, on the base frame
PANE_W = 0.155                   # its half width (two fifths of the windscreen's width, the reference's top view)
RING = 0.04                      # its frame, even all round
PANE = (PANE_TOP, BASE, PANE_W, PANE_W, RING, PANE_FOOT, 2.2, 1.5)    # (n_bot 1.5: the almond's point)

HOOD_REAR = -0.58                # the rear bow
BOW_W = 0.07                     # the arch and the rear bow, as thick as the reference's
BAND = 0.14                      # the sill band under the hood's glass, an s
CORNER = (0.06, 0.09)            # the panes' corners: radius along z (m) and across (s)


def corner(d):
    """how far a rounded corner reaches in from its edge, d along z from the corner's bow"""
    R, r = CORNER
    if d >= R:
        return 0.0
    t = 1 - max(0.0, d) / R
    return r * (1 - math.sqrt(max(0.0, 1 - t * t)))


def periscope_fin(m, sh, E, out, dark):
    """the periscope fairing on the hood's top: a fin rising aft from its foot, the square mirror housing on its back,
    the mirror's window dark on top (the reference's side and top views)"""
    # side profile: (z, height over the hood's top): from a seventh of the hood back from the arch to nearly half way
    prof = [(0.14, 0.0), (-0.07, 0.09), (-0.115, 0.09), (-0.14, 0.0)]
    half = 0.022
    pts = []
    for z, h in prof:
        y = sh.point(z, 1.0)[1] - 0.012                 # (its foot sunk through the glass)
        pts.append((z, y + h + 0.012 * (h > 0)))
    L_ = [E + np.array([half, y, z]) for z, y in pts]
    R_ = [E + np.array([-half, y, z]) for z, y in pts]
    up = E + np.array([0, pts[1][1] + 0.5, -0.05])
    m.flat(L_, out, toward=E + np.array([1.0, pts[1][1], -0.05]))
    m.flat(R_, out, toward=E + np.array([-1.0, pts[1][1], -0.05]))
    for i in range(len(pts) - 1):
        m.flat([L_[i], L_[i + 1], R_[i + 1], R_[i]], out, toward=up)
    # the mirror housing: a square box on the fin's back, its window on top
    z, y = -0.095, pts[1][1]
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    m.box(E + np.array([0, y + 0.006, z]), (X, Y, Z), (0.03, 0.012, 0.03), out)
    m.box(E + np.array([0, y + 0.019, z]), (X, Y, Z), (0.017, 0.002, 0.017), dark)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner, dark = atlas.swatch(skin), atlas.swatch((40, 42, 44)), atlas.swatch((26, 27, 28))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE))     # the windscreen made flat round its front pane
    # (the windscreen's glass from under the arch, so none of the windscreen is left open between them)
    glass = C.glass_triangles(sh, E, -0.60, SPLIT, nz=14) + C.glass_triangles(sh, E, SPLIT, BASE, nz=12)
    # the hood: the rear bow, the sill rails and the band above them, the bar along the top; each pane's corners
    # rounded (fills at the foot, caps at the top, toward each bow)
    rear, front = HOOD_REAR + BOW_W / 2, SPLIT - BOW_W / 2       # the panes' ends, at the bows' inner edges
    C.bow(m, sh, E, HOOD_REAR, BOW_W, 0.03, out, inner)
    C.member(m, sh, E, 0.0, -0.60, SPLIT, 0.045, 0.035, out, inner)
    C.fill(m, sh, E, rear, front, lambda z: BAND + corner(z - rear) + corner(front - z), out, inner, steps=40)
    C.member(m, sh, E, 1.0, HOOD_REAR, SPLIT, 0.05, 0.022, out, inner, sides=(1,), steps=16)
    C.cap(m, sh, E, rear, rear + CORNER[0], lambda z: 1.0 - corner(z - rear), out, inner, steps=10)
    C.cap(m, sh, E, front - CORNER[0], front, lambda z: 1.0 - corner(front - z), out, inner, steps=10)
    periscope_fin(m, sh, E, out, dark)
    # the arch: the hood's front bow and the windscreen's top in one thick bar
    C.bow(m, sh, E, SPLIT, BOW_W, 0.024, out, inner)
    # the windscreen: the almond front pane in its ring, the base frame on the nose, the slim side rails
    C.oval_pane(m, sh, E, PANE_TOP, PANE_FOOT, PANE_W, RING, 0.022, out, inner, n=PANE[6], n_bot=PANE[7])
    C.bow(m, sh, E, 0.985, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + BOW_W / 2, BASE, 0.03, 0.03, out, inner)
    return m, glass


# the old windscreen's two foot posts, standing on the nose deck just ahead of the new windscreen's foot (the reference
# has nothing there): eye-relative boxes, each round one post only (the deck faces between them stay)
OLD_POSTS = [((0.09, -0.31, 1.04), (0.21, -0.14, 1.22)), ((-0.21, -0.31, 1.04), (-0.09, -0.14, 1.22))]

if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], boxes=OLD_POSTS)
