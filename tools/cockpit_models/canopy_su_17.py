#!/usr/bin/env python3
"""Su-17M4 canopy, made by hand: the shape set against the model's cockpit wall tops and nose deck (sections.py; the
old glass came down inside the walls), and bert's War Thunder reference (2026-10-23, shots 010041-010052), the WT
shape winning by his rule.

The reference: a windscreen of a flat armoured front pane in a thick ring between two long tapered side panes, the
pane's round top under a solid metal bridge that runs back to the windscreen arch, its straight foot on a short base
strip; the canopy sliding aft, its glass a low band over metal sides (the sill line high), held by the front bow
against the arch, the rear bow against the spine, and a full-length centre bar carrying the shield-shaped periscope
housing at its middle.

The exact armoured pane (2026-10-23 trace, WT shape): mm in the pane's flat plane, origin the middle of its foot,
raked 62 deg +-3, W 345, H 640, a round-topped arch with near-parallel sides and a 290 mm straight foot, in a ring
about 65 mm. This model's windscreen is 0.88 m from the arch to the nose deck (fuselage skin: fixed), so the trace
goes in at 1.10 times life to leave room for the bridge and the base strip (inside the trace's +-10-15 % on size,
its shape exact), and the arch comes down from 0.25 to 0.21 over the eye (the hood's front blended down to it)
for a 59 deg rake, the trace's low limit, without touching the deck.

Run after the cockpit (su_17.py), on its output. Usage: python3 tools/cockpit_models/canopy_su_17.py
"""
import math, os, sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'su_17'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the model's canopy and windscreen sections, metres from the eye: z, then the right half from the sill up over the
# top (sections.py, 2026-10-22)
MODEL = [
    (-0.45, [(0.37, -0.50), (0.30, -0.30), (0.22, -0.05), (0.16, 0.15), (0.09, 0.27), (0.0, 0.28)]),
    (-0.20, [(0.43, -0.50), (0.36, -0.35), (0.30, -0.15), (0.285, 0.05), (0.25, 0.18), (0.14, 0.28), (0.0, 0.30)]),
    (0.00, [(0.43, -0.50), (0.36, -0.35), (0.30, -0.15), (0.285, 0.05), (0.25, 0.18), (0.14, 0.28), (0.0, 0.30)]),
    (0.20, [(0.43, -0.50), (0.365, -0.35), (0.30, -0.15), (0.28, 0.04), (0.24, 0.16), (0.13, 0.26), (0.0, 0.29)]),
    (0.40, [(0.43, -0.50), (0.37, -0.35), (0.30, -0.15), (0.27, 0.03), (0.22, 0.14), (0.12, 0.22), (0.0, 0.27)]),
    (0.60, [(0.44, -0.50), (0.38, -0.36), (0.32, -0.18), (0.27, -0.02), (0.19, 0.10), (0.10, 0.155), (0.0, 0.17)]),
    (0.80, [(0.42, -0.50), (0.36, -0.40), (0.30, -0.27), (0.25, -0.12), (0.17, 0.01), (0.09, 0.08), (0.0, 0.10)]),
    (1.00, [(0.33, -0.45), (0.29, -0.36), (0.25, -0.24), (0.18, -0.12), (0.09, -0.04), (0.0, -0.02)]),
    (1.20, [(0.27, -0.42), (0.23, -0.33), (0.18, -0.25), (0.10, -0.18), (0.0, -0.15)]),
    (1.32, [(0.22, -0.40), (0.18, -0.36), (0.12, -0.33), (0.06, -0.32), (0.0, -0.32)]),
]
SPLIT = 0.44                     # the canopy's front edge, against the windscreen arch
TOP = 0.21                       # the windscreen's top at the arch (the model's was 0.25)
BASE = 1.32                      # the windscreen's foot on the nose deck, its top there at -0.32
# the hood's top blended down to the arch: (z, its new top)
HOOD_FRONT = [(0.0, 0.30), (0.20, 0.28), (0.40, 0.225)]


def restack(z, sec, top):
    """the model's section at z with its top moved to [top], every point's height over the sill scaled to match"""
    sy, oy = sec[0][1], sec[-1][1]
    return [(x, sy + (y - sy) * (top - sy) / (oy - sy)) for x, y in sec]


def sections():
    out = []
    for z, sec in MODEL:
        if z <= SPLIT:
            top = dict(HOOD_FRONT).get(z, sec[-1][1])
        else:
            top = TOP + (-0.32 - TOP) * (z - SPLIT) / (BASE - SPLIT)       # the windscreen's straight top line
        out.append((z, restack(z, sec, top)))
    return out


SECTIONS = sections()

# the armoured pane's glass edge, the WT shape (2026-10-23 trace), mm in its plane, origin the foot's middle
_HALF = [(0, 0), (145, 0), (157, 5), (161, 13), (164, 32), (167, 64), (172, 115), (172, 173), (169, 230), (166, 288),
         (161, 346), (156, 397), (151, 448), (140, 493), (126, 531), (111, 563), (93, 589), (76, 608), (59, 624),
         (40, 634), (0, 640)]
OUTLINE = _HALF + [(-x, y) for x, y in reversed(_HALF[1:-1])]
SCALE = 1.10
RING = 0.065 * SCALE             # the ring, about 65 mm in WT
BRIDGE = 0.08                    # the solid bridge from the ring's apex back to the arch, in plan (WT top view: about
                                 # a sixth of the pane's length)
PANE_TOP = SPLIT + 0.025 + BRIDGE
_SLOPE = math.atan2(TOP + 0.32, BASE - SPLIT)
PANE_FOOT = PANE_TOP + C.Oval(PANE_TOP, BASE, 0.1, RING, _SLOPE, outline=OUTLINE, scale=SCALE).foot_d() * math.cos(_SLOPE)
PANE_W = 0.172 * SCALE + RING
PANE = (PANE_TOP, BASE, PANE_W, PANE_W, RING, PANE_FOOT, 2.6, 8.0)

# the sill line under the side glass (WT: the hood's glass a band about 0.28 of its length tall, the windscreen's side
# panes tapering down to its foot): (z, height over the eye)
SILL_LINE = [(-0.45, -0.17), (SPLIT, -0.15), (BASE - 0.02, -0.36)]   # (WT's band would put the sill above this model's
# eye, which sits low in its tall canopy; the sill is set 0.15 below the eye instead, the glass band about half the
# hood's length tall, so the pilot still sees out sideways)


def sill_s(sh):
    return lambda z: sh.s_at_y(z, float(np.interp(z, [p[0] for p in SILL_LINE], [p[1] for p in SILL_LINE])))


def seam_strip(m, sh, E, sfun, z0, z1, out, inner, ds=0.014, proud=0.004, steps=60):
    """a thin lip over the sill band's top edge, 4 mm proud of the glass: the glass stops a pad short of the band
    (cut_glass), and this closes that slit without being a frame the glass is cut round again"""
    zs = np.linspace(z0, z1, steps + 1)
    for side in (1, -1):
        for za, zb in zip(zs[:-1], zs[1:]):
            q = []
            for z in (za, zb):
                s = sfun(z)
                for sv in (s - 0.004, s + ds):
                    p = sh.point(z, sv, side); n = sh.normal(z, sv, side)
                    q.append((p + n * proud + E, p - n * 0.01 + E))
            (a0, a1), (b0, b1), (c0, c1), (d0, d1) = q
            m.flat([a0, b0, d0, c0], out, toward=(a0 + d0) / 2 + (a0 - a1))
            m.flat([a1, b1, d1, c1], inner, toward=(a1 + d1) / 2 + (a1 - a0))
            m.flat([b0, d0, d1, b1], inner, toward=(b0 + d1) / 2 + (b0 - a0))


def shield(m, sh, E, z, out, dark):
    """the periscope fitting on the canopy's centre bar: from above a shield, broad at its rear, pointed ahead"""
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    top = sh.point(z, 1.0) + E
    m.box(top + Y * 0.004, (X, Y, Z), (0.048, 0.024, 0.05), out)                      # the broad rear
    m.box(top + Y * 0.004 + Z * 0.07, (X, Y, Z), (0.032, 0.022, 0.025), out)          # tapering ahead
    m.box(top + Y * 0.002 + Z * 0.11, (X, Y, Z), (0.016, 0.018, 0.02), out)         # its point
    m.box(top + Y * 0.012 - Z * 0.0505, (X, Y, Z), (0.034, 0.014, 0.002), dark)      # the periscope's window, aft


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner, dark = atlas.swatch(skin), atlas.swatch((40, 42, 44)), atlas.swatch((26, 27, 28))
    # the armoured front pane is a flat slab: the windscreen made flat round it
    sh = C.Shape(C.flat_pane(SECTIONS, *PANE, outline=OUTLINE, scale=SCALE))
    glass = C.glass_triangles(sh, E, -0.45, SPLIT, nz=12) + C.glass_triangles(sh, E, SPLIT, BASE, nz=14)
    # the canopy: its rear bow, its front bow, the sill rails, the metal sides up to the sill line
    C.bow(m, sh, E, -0.43, 0.045, 0.03, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.02, out, inner)
    C.fill(m, sh, E, -0.45, BASE - 0.02, sill_s(sh), out, inner, steps=44)
    seam_strip(m, sh, E, sill_s(sh), -0.43, BASE - 0.03, out, inner)
    # the bar along the canopy's top, its full length, its two halves meeting on it; the shield-shaped periscope
    # fitting on it at mid-length (the reference's top view)
    C.member(m, sh, E, 1.0, -0.43, SPLIT - 0.02, 0.035, 0.022, out, inner, sides=(1,), steps=18)
    shield(m, sh, E, 0.0, out, dark)
    # the windscreen: the arch, the solid bridge back from the ring's apex, the exact pane in its ring, the base strip
    C.bow(m, sh, E, SPLIT + 0.015, 0.04, 0.022, out, inner)
    C.cap(m, sh, E, SPLIT + 0.03, PANE_TOP + 0.02, lambda z: sh.s_at_x(z, 0.10), out, inner, steps=8)
    C.oval_pane(m, sh, E, PANE_TOP, PANE_FOOT, PANE_W, RING, 0.022, out, inner, n=PANE[6], n_bot=PANE[7],
                outline=OUTLINE, scale=SCALE)
    C.fill(m, sh, E, PANE_FOOT - 0.01, BASE - 0.015, lambda z: 1.0, out, inner, steps=6)
    C.bow(m, sh, E, BASE - 0.015, 0.03, 0.02, out, inner)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
