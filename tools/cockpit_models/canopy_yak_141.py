#!/usr/bin/env python3
"""Yak-141 (Yak-41M prototype) canopy, fitted to the Yak-141.mtb airframe: framed windscreen and a one-piece canopy, built by hand.

Real aircraft (Yakovlev Yak-141 'Freestyle'; the prototypes' cockpit is the Yak-38M's): a framed windscreen with a flat armoured
front pane between two slanted posts, a curved quarter pane each side, and a small one-piece bubble canopy hinged on the RIGHT
(starboard) side. The canopy ends at the long dorsal spine (no view to the rear). The model carries bare bars for it: a front arch
with a centre rib, side bows, a rear arch and spine ribs, no glass. They are cut out in yak_141.py and rebuilt here:

  * the windscreen: a flat centre pane (a trapezoid between the two posts, perfectly planar: flat_panes/yak_141.json) and, each
    side of the posts, a curved quarter pane (super-ellipse sections) down to the sill. The rake follows the old model's: the bow
    over the pilot's head, the base on the nose deck 0.75 m ahead of it;
  * the canopy: one curved transparency from the front bow back to a slim rear arch that seals onto the dorsal spine's outline
    (the spine's top is 2.9 m, the glass crown 2.92 there); hinge lugs on the right sill, a latch on the left;
  * the frame, slim: ONE bow across the arch (the windscreen's rear edge and the canopy's front bow are the same member, built
    once), the two posts, the base seal and side rails of the windscreen, the canopy's sill rails and rear arch. Each stands a
    band W outside its glass edge; the glass stops at the frame's face and never runs under it.

UNVERIFIED: I found no drawing or photograph that settles the front pane (flat armoured pane with curved quarters is the
Yak-38M / Yak-141 type as far as the texts say); the post positions and the quarter-pane curvature are my reading of the model.

CANOPY and WINDSCREEN (eye-relative sections, the right half from the sill up over the top) are what the checks in
work/elite_expansion_2/checks read for the outline. Frames: vehicle-local blocks, model pixels (x, y, -z) * 16.
The frame swatch is painted into the texture at SWATCH (outside the cockpit script's own free area).

Run first (on the airframe's files); yak_141.py then cuts the old interior and appends the HUD combiner to CanopyGlass.
Usage: python tools/cockpit_models/canopy_yak_141.py [--dry-run]
"""
import json, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/yak_141.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/yak_141.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/yak_141.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/yak_141.png')
TEX_DEAD = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity_dead/yak_141.png')
CANOPY_BONE = 'wreck_fuselage_0__canopy'
TW, TH = 4096, 3072
SWATCH = (1040, 3000, 16, 16)              # x, y, w, h of the frame's colour block in the texture (the model's own skin is in 0..1026)
FRAME_RGB = (62, 66, 64)                    # dark grey-green: the prototype's interior frame

# ---- the glass (absolute blocks, z forward; the airframe's own measurements)
# the glass's foot line: station z, x of the foot, y of the skin it stands on (the wall top beside the pilot, sloping down forward;
# ahead of the bow the nose shoulder beside the windscreen). The glass starts W (the frame band) above the skin.
FOOT = [(4.25, 0.500, 2.444), (4.70, 0.470, 2.394), (5.10, 0.430, 2.344), (5.42, 0.405, 2.304), (5.60, 0.375, 2.274),
        (5.80, 0.340, 2.274), (6.00, 0.300, 2.244), (6.15, 0.270, 2.214)]
# the crown line over the centre (z, y): the dorsal spine's top (2.90) at the rear, the bow, then the windscreen's rake
CROWN = [(4.25, 2.915), (4.60, 2.950), (5.00, 2.965), (5.42, 2.960), (6.15, 2.300)]
NEXP = 2.4                                  # the sections' super-ellipse exponent
Z_REAR = 4.25                               # the canopy's rear edge
Z_BOW = 5.42                                # the bow's centre line
BW = 0.03                                   # the bow's half width along z: the glass edges are at Z_BOW -+ BW
Z_C_FRONT, Z_W_REAR = Z_BOW - BW, Z_BOW + BW
Z_FRONT = 6.15                              # the windscreen's front edge on the nose deck
W = 0.034                                   # the rails' band along the surface, outside the glass edge
H_IN, H_OUT = 0.022, 0.009                  # the rails stand this far under / over the glass surface
B_IN, B_OUT = 0.030, 0.016                  # the bow
XP_TOP, XP_BOT = 0.150, 0.170               # the posts' centre lines at the bow and at the base
WP = 0.036                                  # the posts' width

CANOPY_ST = [4.25, 4.29, 4.37, 4.50, 4.70, 4.90, 5.10, 5.25, 5.33, 5.39]
WINDSCREEN_ST = [5.45, 5.52, 5.60, 5.68, 5.76, 5.84, 5.92, 6.00, 6.08, 6.15]

_EYE = np.array([0.0, 2.7, 4.9])


def _eye():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        s = d['Seats'][0]
        return np.array(d['Attachments'][s['CameraPos']['EyeAttachment']]['Position'], float)
    except (OSError, KeyError):
        return _EYE


def skin_y(z):
    return float(np.interp(z, [f[0] for f in FOOT], [f[2] for f in FOOT]))


def foot_x(z):
    return float(np.interp(z, [f[0] for f in FOOT], [f[1] for f in FOOT]))


def crown_y(z):
    return float(np.interp(z, [c[0] for c in CROWN], [c[1] for c in CROWN]))


def foot_y(z):
    """where the glass starts: the frame band W above the skin"""
    return skin_y(z) + W


def post_x(z):
    """the posts' centre line (x > 0) at station z of the windscreen"""
    return XP_TOP + (XP_BOT - XP_TOP) * (z - Z_W_REAR) / (Z_FRONT - Z_W_REAR)


def surf(z, u, side=1):
    """point of the glass at station z, u = 0 at the foot .. 1 at the crown (x = 0); side +1 left (x > 0), -1 right"""
    xb, yb, yt = foot_x(z), foot_y(z), max(crown_y(z), foot_y(z) + 0.03)
    x = xb * (1 - u ** NEXP) ** (1 / NEXP) if u < 1 else 0.0
    return np.array([side * x, yb + (yt - yb) * u, z])


def u_at_x(z, x):
    """the u whose surface point has |x| = x at station z"""
    xb = foot_x(z)
    r = min(max(x / xb, 0.0), 1.0)
    return (1 - r ** NEXP) ** (1 / NEXP)


def section(z, n=22):
    """the right half-section at station z (absolute (x, y) from the foot up to the crown)"""
    us = np.linspace(0, 1, n)
    return [(float(surf(z, u)[0]), float(surf(z, u)[1])) for u in us]


def sections_rel(eye, stations):
    """the cabin outline the checks read: from the skin up the foot's inner face, then the glass"""
    out = []
    for z in stations:
        pts = section(z)
        pts = [(pts[0][0], skin_y(z))] + pts
        out.append((z - eye[2], [(x, y - eye[1]) for x, y in pts]))
    return out


CANOPY = sections_rel(_eye(), CANOPY_ST)               # eye-relative, as handbuilt_canopy.Shape wants it (the checks read these)
WINDSCREEN = sections_rel(_eye(), WINDSCREEN_ST)


# ---------------------------------------------------------------- the glass

def glass_triangles(stations, ns=14, u_max=None, sides=(1, -1)):
    """the curved glass between stations; u_max(z) limits the patch (the quarter panes stop at the post)"""
    out = []
    for side in sides:
        for za, zb in zip(stations[:-1], stations[1:]):
            for j in range(ns):
                ua1 = u_max(za) if u_max else 1.0
                ub1 = u_max(zb) if u_max else 1.0
                a, b = surf(za, ua1 * j / ns, side), surf(zb, ub1 * j / ns, side)
                c, d = surf(zb, ub1 * (j + 1) / ns, side), surf(za, ua1 * (j + 1) / ns, side)
                for tri in ((a, b, c), (a, c, d)):
                    if np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0])) > 1e-10:
                        out.append(list(tri))
    return out


def quarter_u(z):
    """the quarter pane's inner limit: the post's outer edge"""
    return u_at_x(z, post_x(z) + WP / 2)


def centre_pane():
    """the flat armoured pane: a trapezoid between the posts' inner edges, from the bow's windscreen face to the front edge. The
    four corners lie on the glass surface, symmetric about x = 0, so they are coplanar."""
    out = []
    pts = []
    for z in (Z_W_REAR, Z_FRONT):
        xin = post_x(z) - WP / 2
        y = float(surf(z, u_at_x(z, xin))[1])
        pts.append((xin, y, z))
    (x0, y0, z0), (x1, y1, z1) = pts
    P = [np.array(p, float) for p in ((-x0, y0, z0), (x0, y0, z0), (x1, y1, z1), (-x1, y1, z1))]
    return [[P[0], P[1], P[2]], [P[0], P[2], P[3]]]


# ---------------------------------------------------------------- the frame

def unit(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def normal_at(p, side):
    """outward glass normal at the surface point p (finite differences of surf over (z, u))"""
    z = p[2]
    yb, yt = foot_y(z), max(crown_y(z), foot_y(z) + 0.03)
    u = float(np.clip((p[1] - yb) / (yt - yb), 0, 1))
    eu, ez = 1e-3, 1e-3
    du = surf(z, min(1.0, u + eu), side) - surf(z, max(0.0, u - eu), side)
    dz = surf(z + ez, u, side) - surf(z - ez, u, side)
    n = np.cross(dz, du)
    if np.linalg.norm(n) < 1e-12:
        n = np.array([side, 0.0, 0.0])
    n = unit(n)
    inside = np.array([0.0, (yb + yt) / 2 - 0.05, z])
    return -n if n @ (p - inside) < 0 else n


class Frame:
    """polygons of the frame, as a poly_mesh in model pixels"""

    def __init__(self, uv):
        self.uv = uv
        self.P, self.N, self.U, self.polys = [], [], [], []

    def add(self, pts, c):
        pts = [np.array(q) for q in pts]
        nn = np.zeros(3)
        for i in range(len(pts)):
            a_, b_ = pts[i], pts[(i + 1) % len(pts)]
            nn += np.array([(a_[1] - b_[1]) * (a_[2] + b_[2]), (a_[2] - b_[2]) * (a_[0] + b_[0]), (a_[0] - b_[0]) * (a_[1] + b_[1])])
        ln = np.linalg.norm(nn)
        if ln < 1e-12:
            return
        nn /= ln
        if nn @ (np.mean(pts, 0) - c) < 0:               # outward from the member's centre line
            pts = pts[::-1]; nn = -nn
        px = [[float(q[0]) * 16, float(q[1]) * 16, -float(q[2]) * 16] for q in pts]
        npx = np.array([nn[0], nn[1], -nn[2]])
        a, b, cc = (np.array(q) for q in px[:3])
        if np.cross(b - a, cc - a) @ npx > 0:
            px = px[::-1]
        ni = len(self.N); self.N.append([round(float(x), 5) + 0.0 for x in -npx])
        ui = len(self.U); self.U.append(list(self.uv))
        poly = []
        for q in px:
            poly.append([len(self.P), ni, ui])
            self.P.append([round(x, 4) + 0.0 for x in q])
        self.polys.append(poly)

    def band(self, A, Nn, B, c_ref, h_in, h_out):
        """a member along the chain: A = inner edge points, B = outer edge points (both on the glass surface), Nn = normals;
        four faces per span (top, bottom, outer wall, inner wall); no end caps"""
        for k in range(len(A) - 1):
            ii0, io0 = A[k] - Nn[k] * h_in, A[k] + Nn[k] * h_out
            oi0, oo0 = B[k] - Nn[k] * h_in, B[k] + Nn[k] * h_out
            ii1, io1 = A[k + 1] - Nn[k + 1] * h_in, A[k + 1] + Nn[k + 1] * h_out
            oi1, oo1 = B[k + 1] - Nn[k + 1] * h_in, B[k + 1] + Nn[k + 1] * h_out
            c = (A[k] + A[k + 1] + B[k] + B[k + 1]) / 4
            self.add([io0, oo0, oo1, io1], c)           # top (outside of the canopy)
            self.add([ii0, oi0, oi1, ii1], c)           # bottom
            self.add([oo0, oi0, oi1, oo1], c)           # outer wall
            self.add([io0, ii0, ii1, io1], c)           # inner wall (against the glass edge)

    def box(self, centre, half):
        """an axis-aligned block (hinge lugs, latch): six faces"""
        c = np.array(centre, float); h = np.array(half, float)
        for ax in range(3):
            for s in (-1, 1):
                i, j = [m for m in range(3) if m != ax]
                fc = c.copy(); fc[ax] += s * h[ax]
                pts = []
                for a_, b_ in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
                    p = fc.copy(); p[i] += a_ * h[i]; p[j] += b_ * h[j]
                    pts.append(p)
                self.add(pts, c)

    def result(self):
        return {'normalized_uvs': True, 'positions': self.P, 'normals': self.N, 'uvs': self.U, 'polys': self.polys}


def chain_rail(fr, pts, sides, interior, w=W):
    """a rail along the glass edge `pts` (open chain, mitred at its vertices), band w outside the edge"""
    P = np.array(pts)
    n = len(P)
    nrm = [normal_at(P[k], sides[k]) for k in range(n)]
    seg = []
    for k in range(n - 1):
        t = unit(P[k + 1] - P[k])
        nn = unit(nrm[k] + nrm[k + 1])
        b = unit(np.cross(nn, t))
        if b @ ((P[k] + P[k + 1]) / 2 - interior) < 0:
            b = -b
        seg.append(b)
    m = []
    for k in range(n):
        if k == 0:
            m.append(seg[0])
        elif k == n - 1:
            m.append(seg[-1])
        else:
            m.append((seg[k - 1] + seg[k]) / (1 + float(seg[k - 1] @ seg[k])))
    B = [P[k] + m[k] * w for k in range(n)]
    fr.band(list(P), nrm, B, interior, H_IN, H_OUT)


def post_bar(fr, side):
    """a post: a straight bar from the bow down to the base seal along the post's centre line, WP wide, standing the same
    H_IN / H_OUT under / over the glass; its ends sink into the bow and the base seal"""
    z0, z1 = Z_W_REAR - 0.012, Z_FRONT + 0.012
    ends = []
    for z in (z0, z1):
        x = post_x(z)
        ends.append(surf(z, u_at_x(z, x), side))
    a, b = ends
    nr = [normal_at(a, side), normal_at(b, side)]
    t = unit(b - a)
    A, B = [], []
    for p, n_ in zip((a, b), nr):
        w = unit(np.cross(n_, t))
        if w[0] * side < 0:
            w = -w
        A.append(p - w * WP / 2); B.append(p + w * WP / 2)
    fr.band(A, nr, B, None, H_IN, H_OUT)


def frame_mesh(uv):
    fr = Frame(uv)
    # -- the canopy's rails: right foot rear to front (into the bow), the rear arch, left foot rear to front; ONE chain
    zc = [Z_BOW] + CANOPY_ST[::-1]                       # front -> rear (the first point is inside the bow)
    pts, sides = [], []
    for z in zc:                                         # right foot, front to rear
        pts.append(surf(z, 0.0, -1)); sides.append(-1)
    nrear = 16
    for k in range(1, nrear * 2):                        # rear arc: right foot up over the crown to the left foot
        u = 1 - abs(k - nrear) / nrear
        side = -1 if k < nrear else 1
        pts.append(surf(Z_REAR, u, side) if k != nrear else surf(Z_REAR, 1.0, 1)); sides.append(side)
    for z in CANOPY_ST + [Z_BOW]:                        # left foot, rear to front
        pts.append(surf(z, 0.0, 1)); sides.append(1)
    chain_rail(fr, pts, sides, np.array([0.0, 2.75, 4.7]))
    # -- the windscreen's rails: the left foot from the bow to the nose, the base seal across the front, the right foot back
    zw = [Z_BOW] + WINDSCREEN_ST
    pts, sides = [], []
    for z in zw:
        pts.append(surf(z, 0.0, 1)); sides.append(1)
    nfront = 6
    for k in range(1, nfront * 2):
        u = 1 - abs(k - nfront) / nfront
        side = 1 if k < nfront else -1
        pts.append(surf(Z_FRONT, u, side) if k != nfront else surf(Z_FRONT, 1.0, 1)); sides.append(side)
    for z in zw[::-1]:
        pts.append(surf(z, 0.0, -1)); sides.append(-1)
    chain_rail(fr, pts, sides, np.array([0.0, 2.5, 5.8]))
    # -- the bow: ONE member, windscreen rear edge and canopy front bow at once (faces at Z_BOW -+ BW), foot to foot over the crown
    nb = 16
    us = [(k / nb, 1) for k in range(0, nb)] + [(1.0, 1)] + [((nb - k) / nb, -1) for k in range(1, nb + 1)]
    A, Bk, Nn = [], [], []
    for u, side in us:                                   # A: the canopy's face, Bk: the windscreen's face
        pa, pb = surf(Z_C_FRONT, u, side), surf(Z_W_REAR, u, side)
        A.append(pa); Bk.append(pb); Nn.append(normal_at((pa + pb) / 2, side))
    for end, (i, sd) in ((0, (0, 1)), (1, (len(us) - 1, -1))):      # the ends reach down W to the skin
        pa, pb = A[i], Bk[i]
        ea, eb = pa - np.array([0, W, 0]), pb - np.array([0, W, 0])
        if end == 0:
            A.insert(0, ea); Bk.insert(0, eb); Nn.insert(0, Nn[0])
        else:
            A.append(ea); Bk.append(eb); Nn.append(Nn[-1])
    fr.band(A, Nn, Bk, None, B_IN, B_OUT)
    # -- the two posts of the flat armoured pane
    for side in (1, -1):
        post_bar(fr, side)
    # -- the canopy opens to the RIGHT (starboard, x < 0): two hinge lugs outside the right sill rail, a latch on the left rail
    for z in (4.55, 5.05):
        x = foot_x(z) + W + 0.014
        fr.box([-x, foot_y(z) - 0.012, z], [0.014, 0.020, 0.030])
    z = 5.15
    fr.box([foot_x(z) + W + 0.012, foot_y(z) - 0.012, z], [0.012, 0.018, 0.026])
    return fr.result()


def paint_swatch(path, dead=False):
    im = Image.open(path).convert('RGBA')
    c = FRAME_RGB if not dead else tuple(int(v * 0.35) for v in FRAME_RGB)
    x, y, w, h = SWATCH
    im.paste(Image.new('RGBA', (w, h), tuple(c) + (255,)), (x, y))
    im.save(path, optimize=True)


def swatch_uv():
    x, y, w, h = SWATCH
    return [(x + w / 2) / TW, 1.0 - (y + h / 2) / TH]


def all_glass():
    """every glass triangle: the canopy, the two curved quarter panes, the flat centre pane"""
    return glass_triangles(CANOPY_ST) + glass_triangles(WINDSCREEN_ST, u_max=quarter_u) + centre_pane()


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bone = next(b for b in geo['minecraft:geometry'][0]['bones'] if b['name'] == CANOPY_BONE)
    gone = len(bone.get('poly_mesh', {}).get('polys', []))
    bone['poly_mesh'] = frame_mesh(swatch_uv())
    tris = all_glass()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'yak_141 canopy: {gone} old canopy-bone faces dropped; {len(bone["poly_mesh"]["polys"])} faces of the frame; '
          f'{len(tris)} glass triangles (x2 windings)')
    if dry:
        return
    with open(GEO, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(geo, f, separators=(',', ':'))
    glass_json = asset.pop('CanopyGlass')
    text = json.dumps(asset, indent=2, ensure_ascii=False)
    text = text[:-2] + ',\n  "CanopyGlass": ' + json.dumps(glass_json, separators=(',', ':')) + '\n}'
    with open(ASSET, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text + '\n')
    paint_swatch(TEX)
    paint_swatch(TEX_DEAD, dead=True)


if __name__ == '__main__':
    main(sys.argv[1:])
