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
TOP = 0.22                       # the hood's top at the arch (2026-10-23 trace: the windscreen rises about 0.4 m at
                                 # life size from the deck to the arch; the model's arch stood 0.30 over the eye, too
                                 # tall for the 67 deg rake; lowered, the hood's front blended down to it)
BASE = 1.40                      # the windscreen's foot on the flat nose deck (its top there at -0.27): long and low,
                                 # its top line 67 deg from vertical as the trace has it

# the hood's sections, metres from the eye: z, then the right half from the sill up over the top
HOOD = [
    (-0.60, [(0.42, -0.25), (0.40, -0.05), (0.36, 0.10), (0.28, 0.20), (0.15, 0.255), (0.0, 0.27)]),
    (-0.20, [(0.44, -0.25), (0.43, -0.08), (0.40, 0.08), (0.33, 0.19), (0.18, 0.26), (0.0, 0.285)]),
    (0.00, [(0.45, -0.25), (0.44, -0.10), (0.40, 0.05), (0.33, 0.165), (0.20, 0.24), (0.0, 0.275)]),
    (0.20, [(0.44, -0.25), (0.42, -0.11), (0.38, 0.02), (0.30, 0.13), (0.17, 0.195), (0.0, 0.22)]),
]
# the windscreen's sills (on the model's cockpit sills) and its foot's flat section
SILLS = [(0.40, 0.42, -0.25), (0.60, 0.40, -0.25), (0.80, 0.37, -0.265), (1.00, 0.34, -0.28), (1.20, 0.30, -0.28),
         (1.40, 0.26, -0.28)]
FOOT = (1.40, [(0.26, -0.28), (0.23, -0.275), (0.15, -0.272), (0.0, -0.27)])
# the hood's cross-section shape (its 0.20 station), as fractions of the sill's x and of the height over the sill
PROFILE = [(x / 0.44, (y + 0.25) / (TOP + 0.25)) for x, y in HOOD[-1][1]]


def windscreen(z):
    """the windscreen's section at z: the hood's shape over the sill there, its top on the straight line down"""
    sx = float(np.interp(z, [s[0] for s in SILLS], [s[1] for s in SILLS]))
    sy = float(np.interp(z, [s[0] for s in SILLS], [s[2] for s in SILLS]))
    top = TOP + (FOOT[1][-1][1] - TOP) * (z - SPLIT) / (BASE - SPLIT)
    return [(u * sx, sy + v * (top - sy)) for u, v in PROFILE]


SECTIONS = HOOD + [(z, windscreen(z)) for z in (0.30, 0.45, 0.60, 0.75, 0.90, 1.05, 1.20, 1.30)] + [FOOT]

# the armoured front pane's glass, exactly (2026-10-23 trace from 71 windscreen photos and bert's War Thunder shots):
# flat, raked 67 degrees, an ellipse 315 mm wide and 836 mm up the slope with its bottom 76 mm cut off by a 148 mm
# straight foot (head-on the rake shortens it to the round look); mm in the pane's plane, origin the foot's middle
OUTLINE = [(0, 0), (74, 0), (81, 3), (84, 10), (98, 19), (114, 53), (128, 114), (144, 190), (153, 266), (157, 342),
           (155, 410), (151, 471), (142, 532), (130, 585), (117, 631), (102, 669), (83, 699), (68, 722), (49, 741),
           (34, 752), (0, 760), (-34, 752), (-49, 741), (-68, 722), (-83, 699), (-102, 669), (-117, 631), (-130, 585),
           (-142, 532), (-151, 471), (-155, 410), (-157, 342), (-153, 266), (-144, 190), (-128, 114), (-114, 53),
           (-98, 19), (-84, 10), (-81, 3), (-74, 0)]
SCALE = 1.12                     # (between this model's windscreen length, 1.25 times life, and its narrower width:
                                 # the ring about half the canopy's width head-on and from above, as bert's shots)
RING = 0.05 * SCALE              # its ring, 50 mm at life size
PANE_TOP = SPLIT + 0.04          # the ring's apex, just ahead of the arch
_SLOPE = math.atan2(TOP - FOOT[1][-1][1], BASE - SPLIT)
PANE_FOOT = PANE_TOP + C.Oval(PANE_TOP, BASE, 0.1, RING, _SLOPE, outline=OUTLINE, scale=SCALE).foot_d() * math.cos(_SLOPE)
PANE_W = 0.157 * SCALE + RING    # (the ring's outer half width; used where the old code asks for one)
PANE = (PANE_TOP, BASE, PANE_W, PANE_W, RING, PANE_FOOT, 2.2, 1.5)
WEDGE_X = (0.157 * SCALE + RING, 0.237 * SCALE)   # the solid wedge under the ring: its edge at the ring, at the sill

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
    """the periscope fairing on the hood's centre bar (the reference's top and side views, 2026-10-24 check): from
    above a shield, pointed at both ends and widest a little behind its middle, about 0.18 wide and 0.42 long on the
    bar (about a fifth of the hood's width, as the reference), its point just behind the arch (the real MLD 3/4 photos); from the side a low fin rising aft to its mirror housing, the
    mirror's window dark on top"""
    # (z, half width, height over the hood's top): the shield's plan and the fin's side profile together
    prof = [(0.21, 0.012, 0.0), (0.15, 0.06, 0.03), (0.07, 0.085, 0.06), (-0.02, 0.09, 0.09), (-0.08, 0.075, 0.09),
            (-0.15, 0.04, 0.05), (-0.20, 0.012, 0.0)]
    secs = []
    for z, w, h in prof:
        base = sh.point(z, 1.0)[1] - 0.03               # (its foot sunk through the bar and the glass)
        top = sh.point(z, 1.0)[1] + h + 0.004
        secs.append((z, w, base, top))
    up = E + np.array([0, secs[3][3] + 0.5, -0.05])
    for i in range(len(secs) - 1):
        (z0, w0, b0, t0), (z1, w1, b1, t1) = secs[i], secs[i + 1]
        m.flat([E + np.array([w0, t0, z0]), E + np.array([w1, t1, z1]), E + np.array([-w1, t1, z1]),
                E + np.array([-w0, t0, z0])], out, toward=up)
        for sg in (1, -1):
            m.flat([E + np.array([sg * w0, b0, z0]), E + np.array([sg * w1, b1, z1]), E + np.array([sg * w1, t1, z1]),
                    E + np.array([sg * w0, t0, z0])], out, toward=E + np.array([sg * 1.0, t0, (z0 + z1) / 2]))
    for z, w, b, t in (secs[0], secs[-1]):
        m.flat([E + np.array([w, b, z]), E + np.array([w, t, z]), E + np.array([-w, t, z]), E + np.array([-w, b, z])],
               out, toward=E + np.array([0, t, z + (1.0 if z > 0 else -1.0)]))
    # the mirror housing's window, dark, on the shield's back
    z, y = -0.045, secs[3][3]
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    m.box(E + np.array([0, y + 0.003, z]), (X, Y, Z), (0.03, 0.003, 0.03), dark)


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner, dark = atlas.swatch(skin), atlas.swatch((40, 42, 44)), atlas.swatch((26, 27, 28))
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE, outline=OUTLINE, scale=SCALE))   # the windscreen made flat round its front pane
    # (the windscreen's glass from under the arch, so none of the windscreen is left open between them)
    glass = C.glass_triangles(sh, E, -0.60, SPLIT, nz=14) + C.glass_triangles(sh, E, SPLIT, BASE, nz=18)
    # the hood: the rear bow, the sill rails and the band above them, the bar along the top; each pane's corners
    # rounded (fills at the foot, caps at the top, toward each bow)
    rear, front = HOOD_REAR + BOW_W / 2, SPLIT - BOW_W / 2       # the panes' ends, at the bows' inner edges
    C.bow(m, sh, E, HOOD_REAR, BOW_W, 0.03, out, inner)
    C.member(m, sh, E, 0.0, -0.60, SPLIT, 0.045, 0.035, out, inner)
    C.fill(m, sh, E, rear, front, lambda z: BAND + corner(z - rear) + corner(front - z), out, inner, steps=40)
    C.member(m, sh, E, 1.0, HOOD_REAR, SPLIT, 0.08, 0.022, out, inner, sides=(1,), steps=16)   # (the reference's
    # centre bar is about a ninth of the hood's width from above)
    C.cap(m, sh, E, rear, rear + CORNER[0], lambda z: 1.0 - corner(z - rear), out, inner, steps=10)
    C.cap(m, sh, E, front - CORNER[0], front, lambda z: 1.0 - corner(front - z), out, inner, steps=10)
    periscope_fin(m, sh, E, out, dark)
    # the arch: the hood's front bow and the windscreen's top in one thick bar
    C.bow(m, sh, E, SPLIT, BOW_W, 0.024, out, inner)
    # the windscreen: the almond front pane in its ring, the base frame on the nose, the slim side rails
    C.oval_pane(m, sh, E, PANE_TOP, PANE_FOOT, PANE_W, RING, 0.022, out, inner, n=PANE[6], n_bot=PANE[7],
                outline=OUTLINE, scale=SCALE)
    # the solid wedge under the ring, flaring out to the sill (the trace)
    C.cap(m, sh, E, PANE_FOOT - 0.01, BASE - 0.03, lambda z: sh.s_at_x(z, float(np.interp(z, [PANE_FOOT, BASE], WEDGE_X))),
          out, inner, steps=10)
    C.bow(m, sh, E, BASE - 0.015, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + BOW_W / 2, BASE, 0.03, 0.03, out, inner)
    return m, glass


# the old windscreen's two foot posts, standing on the nose deck just ahead of the new windscreen's foot (the reference
# has nothing there): eye-relative boxes, each round one post only (the deck faces between them stay)
OLD_POSTS = [((0.09, -0.31, 1.04), (0.21, -0.14, 1.22)), ((-0.21, -0.31, 1.04), (-0.09, -0.14, 1.22))]

if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], boxes=OLD_POSTS)
