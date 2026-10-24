#!/usr/bin/env python3
"""FC-31 (prototype 31001) canopy, fitted to the FC-31.mtb airframe: the model's own two-piece framed canopy, built by hand.

31001 (2012) is the first prototype: a fixed curved WINDSCREEN and, behind it, a separate one-piece CANOPY with a front bow
(the 2016 prototype 31003 and the J-35 changed to a frameless bubble; this vehicle is 31001, as its fuselage marking shows).
The model carries the same layout as bare bars: an arch hoop over the pilot's knees, side rails and a spine, no glass, and
chunky centre bars standing in for a windscreen post. They are cut out in fc_31.py and rebuilt here:

  * the windscreen: one curved panel (super-ellipse sections), from its base on the nose deck back to the arch. No post, no
    centre plate: the forward view is open;
  * the canopy: one curved transparency from the front bow back to a slim rear frame that seals onto the spine fairing;
  * the frame, slim: ONE bow across the arch (the windscreen's rear edge and the canopy's front bow are the same member, built
    once, not two bars side by side), the windscreen's base seal and side rails, the canopy's sill rails and rear arch. Each
    stands a band W outside its glass edge; the glass stops at the frame's face and never runs under it. Rails end inside the
    bow, so no two faces are coplanar.

flat_panes/fc_31.json is empty: both pieces are curved (no flat pane on 31001).

CANOPY and WINDSCREEN (eye-relative sections, the right half from the sill up over the top) are what the checks in
work/elite_expansion_2/checks read for the outline. Frames: vehicle-local blocks, model pixels (x, y, -z) * 16.
The frame swatch is painted into the texture at SWATCH (outside the cockpit script's own free area).

Run first (on the airframe's files); fc_31.py then cuts the old interior and appends the HUD combiner to CanopyGlass.
Usage: python tools/cockpit_models/canopy_fc_31.py [--dry-run]
"""
import json, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/fc_31.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/fc_31.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/fc_31.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/fc_31.png')
TEX_DEAD = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity_dead/fc_31.png')
CANOPY_BONE = 'wreck_fuselage_0__canopy'
TW, TH = 4096, 5120
SWATCH = (2048, 600, 16, 16)               # x, y, w, h of the frame's colour block in the texture
FRAME_RGB = (70, 74, 80)                    # the prototype's dark grey

# ---- the glass (absolute blocks, z forward)
# the glass's foot line: station z, x of the foot, y of the skin it stands on (the sill along the pilot, the model's inner wall top, rising
# 0.19 m from the arch back to z 3.96; ahead of the arch the nose deck beside the windscreen). The glass starts W (the frame band) above the skin.
FOOT = [(3.30, 0.470, 2.490), (3.96, 0.470, 2.489), (4.02, 0.455, 2.478), (4.91, 0.452, 2.296), (5.00, 0.450, 2.270),
        (5.12, 0.445, 2.255), (5.20, 0.440, 2.250), (5.75, 0.340, 2.250), (5.85, 0.270, 2.235), (5.95, 0.140, 2.250)]
# the crown line over the centre (z, y)
CROWN = [(3.30, 2.830), (3.60, 2.880), (4.00, 2.915), (4.50, 2.930), (4.90, 2.915), (5.05, 2.870), (5.25, 2.780),
         (5.50, 2.630), (5.75, 2.450), (5.95, 2.325)]
NEXP = 2.4                                  # the sections' super-ellipse exponent
Z_REAR = 3.30                               # the canopy's rear edge
Z_BOW = 5.00                                # the bow's centre line
BW = 0.03                                   # the bow's half width along z: the glass edges are at Z_BOW -+ BW
Z_C_FRONT, Z_W_REAR = Z_BOW - BW, Z_BOW + BW
Z_FRONT = 5.95                              # the windscreen's front edge on the nose deck
W = 0.034                                   # the rails' band along the surface, outside the glass edge
H_IN, H_OUT = 0.022, 0.009                  # the rails stand this far under / over the glass surface
B_IN, B_OUT = 0.030, 0.016                  # the bow

CANOPY_ST = [3.30, 3.34, 3.42, 3.55, 3.75, 3.95, 4.15, 4.35, 4.55, 4.75, 4.88, 4.96]
WINDSCREEN_ST = [5.04, 5.10, 5.17, 5.25, 5.34, 5.43, 5.52, 5.61, 5.70, 5.78, 5.85, 5.91, 5.95]

_EYE = np.array([0.0, 2.639, 4.64951])


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


def surf(z, u, side=1):
    """point of the glass at station z, u = 0 at the foot .. 1 at the crown (x = 0); side +1 left (x > 0), -1 right"""
    xb, yb, yt = foot_x(z), foot_y(z), max(crown_y(z), foot_y(z) + 0.03)
    x = xb * (1 - u ** NEXP) ** (1 / NEXP) if u < 1 else 0.0
    return np.array([side * x, yb + (yt - yb) * u, z])


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

def glass_triangles(stations, ns=14):
    out = []
    for side in (1, -1):
        for za, zb in zip(stations[:-1], stations[1:]):
            for j in range(ns):
                u0, u1 = j / ns, (j + 1) / ns
                a, b = surf(za, u0, side), surf(zb, u0, side)
                c, d = surf(zb, u1, side), surf(za, u1, side)
                for tri in ((a, b, c), (a, c, d)):
                    if np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0])) > 1e-10:
                        out.append(list(tri))
    return out


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


def frame_mesh(uv):
    fr = Frame(uv)
    # -- the canopy's rails: left foot rear to front (into the bow), the rear arch, right foot rear to front; ONE chain
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
    chain_rail(fr, pts, sides, np.array([0.0, 2.75, 4.1]))
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
    chain_rail(fr, pts, sides, np.array([0.0, 2.55, 5.4]))
    # -- the bow: ONE member, windscreen rear edge and canopy front bow at once (faces at Z_BOW -+ BW), foot to foot over the crown
    nb = 16
    us = [(k / nb, 1) for k in range(0, nb)] + [(1.0, 1)] + [((nb - k) / nb, -1) for k in range(1, nb + 1)]
    A, Bk, Nn = [], [], []
    for u, side in us:                                   # A: the canopy's face, Bk: the windscreen's face
        pa, pb = surf(Z_C_FRONT, u, side), surf(Z_W_REAR, u, side)
        A.append(pa); Bk.append(pb); Nn.append(normal_at((pa + pb) / 2, side))
    # the ends reach down W to the skin
    for end, (i, sd) in ((0, (0, 1)), (1, (len(us) - 1, -1))):
        pa, pb = A[i], Bk[i]
        ea, eb = pa - np.array([0, W, 0]), pb - np.array([0, W, 0])
        if end == 0:
            A.insert(0, ea); Bk.insert(0, eb); Nn.insert(0, Nn[0])
        else:
            A.append(ea); Bk.append(eb); Nn.append(Nn[-1])
    # (the bow's sweep is built with the generic band: inner edge = the canopy face, outer = the windscreen face)
    fr.band(A, Nn, Bk, None, B_IN, B_OUT)
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


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bone = next(b for b in geo['minecraft:geometry'][0]['bones'] if b['name'] == CANOPY_BONE)
    gone = len(bone.get('poly_mesh', {}).get('polys', []))
    bone['poly_mesh'] = frame_mesh(swatch_uv())
    tris = glass_triangles(CANOPY_ST) + glass_triangles(WINDSCREEN_ST)
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'fc_31 canopy: {gone} old canopy-bone faces dropped; {len(bone["poly_mesh"]["polys"])} faces of the frame; '
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
