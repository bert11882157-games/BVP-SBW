#!/usr/bin/env python3
"""F-22A canopy, fitted to the F-22A.mtb airframe: a one-piece frameless bubble and its slim frame, built by hand.

The Raptor's canopy (side views and photos): one large blown transparency running from the windscreen base on the nose
deck, up over the pilot and back to a single rear frame where it meets the spine fairing. There is no windscreen arch,
no centre bar and no posts: the only frame is a slim member round the edge of the glass, along the two sills and across
the rear, with a thin seal strip where the glass meets the nose deck (flat_panes/f_22a.json is empty for that reason).

The airframe's own canopy (bone wreck_fuselage_0__canopy: 25 boxes, two chunky side rails, a stack of centre bars over
the crown standing in for glass and a heavy rear bow) is not the real frame, so it is dropped completely and rebuilt:

  * the glass: super-ellipse sections from a foot line sitting on the sill shoulder of the skin (and, ahead of the
    windscreen's side, on the nose deck) up to the crown line, rear frame (z 3.95) to nose deck (z 6.93);
  * the frame: ONE closed member round the glass edge (the rear arch, both sill rails and the front seal strip meet in
    mitred corners, they are not separate bars), a 4 cm band outside the glass edge, 3.4 cm through the glass surface,
    painted with its own swatch. The glass stops at the member's inner face, never runs under it.

CANOPY (eye-relative sections, the right half from the sill up over the top) is what the checks in
work/elite_expansion_2/checks read for the canopy outline. Frames: vehicle-local blocks, model pixels (x, y, -z) * 16.
The frame swatch is painted into the texture at SWATCH (outside the cockpit script's own free area).

Run first (on the airframe's files); the cockpit script f_22a.py then appends the HUD combiner to CanopyGlass.
Usage: python tools/cockpit_models/canopy_f_22a.py [--dry-run]
"""
import json, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/f_22a.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/f_22a.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/f_22a.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/f_22a.png')
TEX_DEAD = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity_dead/f_22a.png')
CANOPY_BONE = 'wreck_fuselage_0__canopy'
TW, TH = 4096, 6144
SWATCH = (2048, 600, 16, 16)               # x, y, w, h of the frame's colour block in the texture
FRAME_RGB = (86, 90, 94)                    # the Raptor's grey

# ---- the glass (absolute blocks, z forward)
# the glass's foot line: station z, x of the foot, y of the skin it stands on (the sill shoulder along the pilot, the
# nose deck ahead of the shoulder's fall). The glass starts W (the frame band) above the skin.
FOOT = [(3.95, 0.500, 2.690), (4.30, 0.520, 2.670), (4.90, 0.520, 2.595), (5.50, 0.520, 2.510), (5.90, 0.515, 2.440),
        (6.20, 0.490, 2.405), (6.50, 0.430, 2.370), (6.75, 0.340, 2.375), (6.93, 0.140, 2.450)]
# the crown line over the centre (z, y)
CROWN = [(3.95, 3.020), (4.20, 3.140), (4.60, 3.220), (5.00, 3.260), (5.50, 3.260), (5.90, 3.190), (6.20, 3.080),
         (6.55, 2.860), (6.80, 2.640), (6.93, 2.520)]
NEXP = 2.4                                  # the sections' super-ellipse exponent
Z_REAR, Z_FRONT = 3.95, 6.93
W = 0.040                                   # the frame's band along the surface, outside the glass edge
H_IN, H_OUT = 0.024, 0.010                  # the frame stands this far under / over the glass surface
SILL_Y = 2.40                               # (not used by the checks directly: the outline starts at the skin, see sections_rel)

STATIONS = [3.95, 3.99, 4.05, 4.15, 4.30, 4.50, 4.70, 4.90, 5.10, 5.30, 5.50, 5.70, 5.90, 6.05, 6.20, 6.35, 6.50, 6.65,
            6.78, 6.88, 6.93]

_EYE = np.array([0.0, 2.98238, 5.43609])


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


def sections_rel(eye):
    """the cabin outline the checks read: from the skin up the foot's inner face, then the glass"""
    out = []
    for z in STATIONS:
        pts = section(z)
        pts = [(pts[0][0], skin_y(z))] + pts
        out.append((z - eye[2], [(x, y - eye[1]) for x, y in pts]))
    return out


CANOPY = sections_rel(_eye())              # eye-relative, as handbuilt_canopy.Shape wants it (the checks read this)


# ---------------------------------------------------------------- the glass

def glass_triangles(ns=14):
    out = []
    for side in (1, -1):
        for za, zb in zip(STATIONS[:-1], STATIONS[1:]):
            for j in range(ns):
                u0, u1 = j / ns, (j + 1) / ns
                a, b = surf(za, u0, side), surf(zb, u0, side)
                c, d = surf(zb, u1, side), surf(za, u1, side)
                for tri in ((a, b, c), (a, c, d)):
                    if np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0])) > 1e-10:
                        out.append(list(tri))
    return out


# ---------------------------------------------------------------- the frame: one closed member round the glass edge

def boundary(ns=14, nrear=16, nfront=6):
    """the glass edge as a closed loop: (point, outward in-surface direction of the edge it starts, kind), starting at the
    front corner on the right, along the right foot to the rear, up and over the rear arc, down the left foot to the front
    and across the front arc back to the start"""
    L = []
    # right foot, front to rear (side -1)
    zs = STATIONS[::-1]
    for z in zs:
        L.append(('foot', surf(z, 0.0, -1)))
    # rear arc from the right foot up over the crown to the left foot (z = Z_REAR)
    for k in range(1, nrear * 2):
        u = 1 - abs(k - nrear) / nrear                 # 0 .. 1 .. 0
        side = -1 if k < nrear else 1
        L.append(('rear', surf(Z_REAR, u, side) if k != nrear else surf(Z_REAR, 1.0, 1)))
    # left foot, rear to front
    for z in STATIONS:
        L.append(('foot', surf(z, 0.0, 1)))
    # front arc from the left foot up over the crown to the right foot (z = Z_FRONT)
    for k in range(1, nfront * 2):
        u = 1 - abs(k - nfront) / nfront
        side = 1 if k < nfront else -1
        L.append(('front', surf(Z_FRONT, u, side) if k != nfront else surf(Z_FRONT, 1.0, 1)))
    return L


def unit(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def frame_mesh(uv):
    L = boundary()
    n_pts = len(L)
    P = np.array([p for _, p in L])
    # the glass normal at each vertex: finite differences of surf over (z, u) near the vertex (outward = away from the
    # canopy's inside, a point under the crown)
    nrm = []
    for k, (kind, p) in enumerate(L):
        side = 1 if p[0] > 1e-6 else (-1 if p[0] < -1e-6 else 1)
        z = p[2]
        # recover u from the height at this station
        yb, yt = foot_y(z), max(crown_y(z), foot_y(z) + 0.03)
        u = float(np.clip((p[1] - yb) / (yt - yb), 0, 1))
        eu, ez = 1e-3, 1e-3
        du = surf(z, min(1.0, u + eu), side) - surf(z, max(0.0, u - eu), side)
        dz = surf(min(Z_FRONT, z + ez), u, side) - surf(max(Z_REAR, z - ez), u, side)
        n = np.cross(dz, du)
        if np.linalg.norm(n) < 1e-12:
            n = np.array([side, 0.0, 0.0])
        n = unit(n)
        inside = np.array([0.0, (yb + yt) / 2 - 0.05, z])
        if n @ (p - inside) < 0:
            n = -n
        nrm.append(n)
    # edge directions and the in-surface outward normal of every edge
    bseg = []
    for k in range(n_pts):
        a, b = P[k], P[(k + 1) % n_pts]
        t = unit(b - a)
        n = unit(nrm[k] + nrm[(k + 1) % n_pts])
        bseg.append(unit(np.cross(n, t)))
    # orient: outward = away from the glass. The first edge is the right foot running rearward: outward is down
    if bseg[0] @ np.array([0.0, -1.0, 0.0]) < 0:
        bseg = [-b for b in bseg]
    # mitre vector at each vertex from the two edges meeting there
    m = []
    for k in range(n_pts):
        b0, b1 = bseg[k - 1], bseg[k]
        m.append((b0 + b1) / (1 + float(b0 @ b1)))
    pm_P, pm_N, pm_U, polys = [], [], [], []

    def add(pts, c):
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
        ni = len(pm_N); pm_N.append([round(float(x), 5) + 0.0 for x in -npx])
        ui = len(pm_U); pm_U.append(list(uv))
        poly = []
        for q in px:
            poly.append([len(pm_P), ni, ui])
            pm_P.append([round(x, 4) + 0.0 for x in q])
        polys.append(poly)

    def corners(k):
        a = P[k]; n = nrm[k]
        o = a + m[k] * W
        return (a - n * H_IN, a + n * H_OUT, o - n * H_IN, o + n * H_OUT)         # inner-in, inner-out, outer-in, outer-out

    C = [corners(k) for k in range(n_pts)]
    for k in range(n_pts):
        j = (k + 1) % n_pts
        ii0, io0, oi0, oo0 = C[k]
        ii1, io1, oi1, oo1 = C[j]
        c = (P[k] + P[j]) / 2 + (m[k] + m[j]) / 2 * W / 2
        add([io0, oo0, oo1, io1], c)           # top (outside of the canopy)
        add([ii0, oi0, oi1, ii1], c)           # bottom
        add([oo0, oi0, oi1, oo1], c)           # outer wall
        add([io0, ii0, ii1, io1], c)           # inner wall (against the glass edge)
    return {'normalized_uvs': True, 'positions': pm_P, 'normals': pm_N, 'uvs': pm_U, 'polys': polys}


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
    gone = len(bone['poly_mesh']['polys'])
    bone['poly_mesh'] = frame_mesh(swatch_uv())
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'f_22a canopy: the airframe\'s {gone} frame faces dropped; {len(bone["poly_mesh"]["polys"])} faces of the one-piece frame; '
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
