#!/usr/bin/env python3
"""Mirage IIIE canopy and windscreen, hand-built for the Dassault Mirage IIIE.mtb airframe (commit 1ca20a8a): glass and slim
frames on the real Mirage III layout. The model has no canopy at all (a windscreen frame over an open cockpit tub, no
glass anywhere), so this builds the closed canopy.

The real Mirage III (type photos and kit reviews; no drawing with dimensions was found and no web page describes the
panes, so every number below is the model's own line, not a measurement of the real jet): a short, low cockpit under ONE
blown transparency hinged at the rear (it lifts up and back) with a thin frame, a sill rail and a closed rear arch; ahead
of it a fixed windscreen frame whose front pane is FLAT, thick and armoured, steeply raked, narrow, between two posts,
with a small flat quarter pane each side of it. The windscreen's arch IS the canopy's front bow: one member where the
two glasses meet. Two small rear-view mirror blisters stand on the canopy's top just behind that bow (War Thunder's cockpit
has two mirrors; the model none).

  * canopy glass: one unbroken blown shell (a super-ellipse section from the sill rail up to a crown) from the rear bow,
    which stands on the faired spine's front face (z 2.945), to the front bow; no bow between;
  * windscreen: a flat centre pane between two slim posts (|x| <= 0.085, one plane from the front bow down to the base bar
    on the nose deck) and a flat quarter pane each side (a plane through the posts' outer edge dropping 1.5 per block of |x|,
    bounded by a sill strip along the nose skin). Every pane stops at its member's edge;
  * frames (bone wreck_fuselage_1__canopy, closed solids, 4 to 6.5 cm): the two sill rails, the rear bow, the front bow /
    windscreen arch, the two posts, the side sill strips, the base bar and the two mirror housings. No centre rib.

Model numbers (eye-relative, eye (0, 2.31624, 3.73464); read off the faces of the untouched model, checks/orig): the faired
spine's front face z -0.79 with its outline (0, 0.154) (0.12, 0.138) (0.20, 0.087) (0.28, 0.007) (0.32, -0.05) (0.36, -0.15)
then the wall at x 0.40; the sill ridge (the tub walls' top) at x 0.36..0.40, y -0.27 behind the seat falling to -0.34 at
the windscreen's arch (z 0.585; the nose skin's shoulder, the glass foot stands on it); the model's windscreen top bow at y +0.07 (z 0.53..0.71), its two posts at x +-0.085
falling from y +0.02 (z 0.71) to the deck at y -0.38 (z 1.37). The model's own frame is cut out by face runs (CUT_BOX).

CANOPY (eye-relative sections, the right half from the sill up over the top, windscreen included) is what the checks in
work/elite_expansion_2/checks read. Frames: vehicle-local blocks (x left, y up, z forward); meshes are written in model
pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script mirage_3e.py then appends the sight glass to CanopyGlass.
Usage: python tools/cockpit_models/canopy_mirage_3e.py [--dry-run]"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/mirage_3e.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/mirage_3e.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/mirage_3e.json')

BARS_BONE = 'wreck_fuselage_1__canopy'    # (a new bone beside the cockpit bone: the frames break away with the forward fuselage)
AFTER_BONE = 'wreck_fuselage_1__hull'
HULLS = ('wreck_fuselage_0__hull', 'wreck_fuselage_1__hull')
UV_FRAME = [0.44678, 0.79712]             # a texel of the model's own dark grey (60, 62, 65), texture cell x 457, y 415

EZ, EY = 3.73464, 2.31624                 # the pilot's eye (data)


def zr(z):                                # eye-relative z -> vehicle z
    return EZ + z


def yr(y):
    return EY + y


# ---- the old model's frame, interior and tub: every face of the two fuselage pieces lying wholly in this box (eye-relative
# lo, hi, with a 5 mm tolerance) goes; the exterior skin among them is put back by the restore list (missing.py)
CUT_BOX = ((-0.51, -1.135, -0.795), (0.51, 0.155, 1.375))

# ---- the canopy (eye-relative z)
Z_FAIR = -0.79                            # the spine fairing's front face
Z_RB = (-0.785, -0.745)                   # rear bow (4 cm, 5 mm clear of the fairing)
Z_ARCH = (0.585, 0.625)                   # front bow = windscreen arch
NEXP0, NEXP1 = 1.9, 2.3                   # the sections' super-ellipse exponent, rear to front
CROWN = [(-0.79, 0.154), (-0.40, 0.152), (0.0, 0.145), (0.30, 0.130), (0.585, 0.110)]    # the crown's height over the centre line
FOOT_X = [(-0.79, 0.385), (0.0, 0.372), (0.585, 0.335)]
FOOT_Y = [(-0.79, -0.27), (-0.40, -0.285), (0.0, -0.31), (0.30, -0.33), (0.585, -0.34)]


def foot_x(z):
    return float(np.interp(z, [p[0] for p in FOOT_X], [p[1] for p in FOOT_X]))


def foot_y(z):
    return float(np.interp(z, [p[0] for p in FOOT_Y], [p[1] for p in FOOT_Y]))


def crown(z):
    return float(np.interp(z, [c[0] for c in CROWN], [c[1] for c in CROWN]))


def section(z, n=24):
    """right half-section at station z (eye-relative): (x, y) from the foot up to the crown (x = 0)"""
    z = min(max(z, Z_FAIR), Z_ARCH[0])
    x0, y0, yt = foot_x(z), foot_y(z), crown(z)
    e = float(np.interp(z, [Z_FAIR, Z_ARCH[0]], [NEXP0, NEXP1]))
    us = np.linspace(0, 1, n)
    pts = [(x0 * (1 - u ** e) ** (1 / e), y0 + (yt - y0) * u) for u in us]
    pts[-1] = (0.0, yt)
    return pts


# ---- the windscreen: a flat centre pane and two flat quarter panes between slim members
Z_BASE = (1.325, 1.370)                   # the base bar on the nose deck
Z_WS = (Z_ARCH[1], Z_BASE[0])             # the panes run from the arch's front face to the base bar's rear face
POST_IN, POST_OUT = 0.085, 0.130
SIDE_SLOPE = 1.5                          # the quarter panes' plane drops 1.5 per block of |x| out from the posts' outer edge
SIDE_W = 0.030                            # the sill strips along the quarter panes' outer edges
DECK_Y = -0.46                            # the skirts end buried in the deck


def yc(z):                                # the centre pane's plane (flat: independent of x)
    return 0.105 - 0.64 * (z - Z_ARCH[1])


def ys(x, z):                             # the quarter pane's plane at |x|
    return yc(z) - SIDE_SLOPE * (x - POST_OUT)


def x_out(z):                             # the quarter pane's outer edge: along the nose skin, narrowing to the nose
    return 0.335 - 0.150 * (z - Z_WS[0]) / (Z_WS[1] - Z_WS[0])


def sill_y(z):
    return foot_y(z) if z <= Z_ARCH[0] else -0.34 - 0.20 * (z - Z_ARCH[0])


def outline_rel():
    """the canopy's outline the checks read: from the sill line up the glass; the windscreen's stations run from the
    quarter pane's outer edge over the pane and the post to the centre pane"""
    stations = [-0.85, -0.79, -0.745, -0.5, -0.2, 0.1, 0.4, 0.585, 0.63, 0.8, 1.0, 1.2, 1.325]
    out = []
    for z in stations:
        if z <= Z_ARCH[0] + 1e-9:
            pts = section(max(z, Z_FAIR))
        else:
            xo = x_out(z)
            pts = [(xo, ys(xo, z)), (POST_OUT - 0.003, yc(z)), (0.0, yc(z))]
            if pts[0][1] > sill_y(z) + 0.004:
                pts = [(pts[0][0], sill_y(z))] + pts
        out.append((z, pts))
    return out


CANOPY = outline_rel()                    # eye-relative, as handbuilt_canopy.Shape wants it (the checks read this)


def resample(pts, k):
    p = np.array(pts, float)
    d = np.r_[0, np.cumsum(np.linalg.norm(np.diff(p, axis=0), axis=1))]
    t = np.linspace(0, d[-1], k)
    return np.c_[np.interp(t, d, p[:, 0]), np.interp(t, d, p[:, 1])]


def V(x, y, z):
    """eye-relative point -> vehicle-local"""
    return np.array([x, yr(y), zr(z)], float)


# ---------------------------------------------------------------- glass

def shell_patch(z0, z1, nz, ns=16):
    out = []
    for side in (1, -1):
        grid = []
        for i in range(nz + 1):
            z = z0 + (z1 - z0) * i / nz
            r = resample(section(z), ns + 1)
            grid.append([V(side * x, y, z) for x, y in r])
        for i in range(nz):
            for j in range(ns):
                a, b, c, d = grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]
                for tri in ((a, b, c), (a, c, d)):
                    if np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0])) > 1e-10:
                        out.append(list(tri))
    return out


def windscreen_glass(nz=10):
    out = []
    zs = np.linspace(Z_WS[0], Z_WS[1], nz + 1)        # the centre pane: a flat strip between the posts' inner edges
    for z0, z1 in zip(zs[:-1], zs[1:]):
        a, b = V(-POST_IN, yc(z0), z0), V(POST_IN, yc(z0), z0)
        c, d = V(POST_IN, yc(z1), z1), V(-POST_IN, yc(z1), z1)
        out += [[a, b, c], [a, c, d]]
    for side in (1, -1):                                # each quarter pane: one flat polygon
        zk = list(np.linspace(Z_WS[0], Z_WS[1], 8))
        poly = [V(side * POST_OUT, ys(POST_OUT, Z_WS[0]), Z_WS[0])]
        for z in zk:
            xo = x_out(z)
            poly.append(V(side * xo, ys(xo, z), z))
        poly.append(V(side * POST_OUT, ys(POST_OUT, Z_WS[1]), Z_WS[1]))
        for k in range(1, len(poly) - 1):
            t = [poly[0], poly[k], poly[k + 1]]
            if np.linalg.norm(np.cross(t[1] - t[0], t[2] - t[0])) > 1e-10:
                out.append(t)
    return out


# ---------------------------------------------------------------- frames (closed solids)

class Bars:
    """faces of the slim frame members as a poly mesh in model pixels; each face has one winding, outward from the member"""
    def __init__(self, uv):
        self.P, self.N, self.U, self.polys, self.uv = [], [], [], [], uv

    def face(self, pts, n):
        px = [[float(q[0]) * 16, float(q[1]) * 16, -float(q[2]) * 16] for q in pts]
        npx = np.array([n[0], n[1], -n[2]])
        a, b, c = (np.array(q) for q in px[:3])
        # the mesh loader mirrors X: in file pixels a face winds clockwise seen from its front (the hull's own faces do the same)
        if np.cross(b - a, c - a) @ npx > 0:
            px = px[::-1]
        ni = len(self.N)
        self.N.append([round(float(x), 5) + 0.0 for x in -npx])
        ui = len(self.U)
        self.U.append(list(self.uv))
        poly = []
        for p in px:
            poly.append([len(self.P), ni, ui])
            self.P.append([round(x, 4) + 0.0 for x in p])
        self.polys.append(poly)

    def mesh(self):
        return {'normalized_uvs': True, 'positions': self.P, 'normals': self.N, 'uvs': self.U, 'polys': self.polys}

    def quad(self, q, inside):
        """q: 3 or 4 points; the normal points away from the point [inside]"""
        q = [np.array(p, float) for p in q]
        n = np.cross(q[1] - q[0], q[2] - q[0])
        if len(q) == 4:
            n = n + np.cross(q[2] - q[0], q[3] - q[0])
        ln = np.linalg.norm(n)
        if ln < 1e-12:
            return
        n /= ln
        if n @ (np.mean(q, 0) - np.array(inside, float)) < 0:
            n = -n
        self.face(q, n)

    def sweep(self, A, B, C, D):
        """a bar between four polylines A (top, near), B (top, far), C (bottom, far), D (bottom, near); the open ends are
        left to the neighbouring member (or are buried)"""
        for i in range(len(A) - 1):
            ctr = np.mean([L[k] for L in (A, B, C, D) for k in (i, i + 1)], 0)
            for p, q in ((A, B), (D, C), (A, D), (B, C)):
                self.quad([p[i], p[i + 1], q[i + 1], q[i]], ctr)

    def cap(self, pts, inside):
        self.quad(pts, inside)


def out_normals(pts):
    n = []
    for k in range(len(pts)):
        a, c = np.array(pts[max(0, k - 1)], float), np.array(pts[min(len(pts) - 1, k + 1)], float)
        t = c - a
        t /= np.linalg.norm(t)
        nn = np.array([t[1], -t[0]])
        if nn[0] < -1e-9 or (abs(nn[0]) < 1e-9 and nn[1] < 0):
            nn = -nn
        n.append(nn)
    return n


T_OUT, T_IN = 0.012, 0.030
T_ARCH = 0.024                            # the arch's depth below the glass edge (slimmer than the sill rails)


def bow_bars(b, z0, z1):
    """a transverse bow over the shell between z0 and z1 (3.8 cm radially, 1.2 cm proud of the glass)"""
    zc = 0.5 * (z0 + z1)
    sec = section(zc)
    nrm = out_normals(sec)
    for side in (1, -1):
        def line(z, rad):
            return [V(side * (p[0] + n[0] * rad), p[1] + n[1] * rad, z) for p, n in zip(sec, nrm)]
        A, B, C, D = line(z0, T_OUT), line(z1, T_OUT), line(z1, -T_IN), line(z0, -T_IN)
        for Ln in (A, B, C, D):
            Ln[0] = Ln[0] + np.array([0, -0.012, 0])        # the foot goes into the rail
        b.sweep(A, B, C, D)


def arch_bars(b):
    """the windscreen arch = canopy front bow: one member, ruled between the shell's end edge on its rear face and the
    windscreen's top edge on its front face, so each glass stops exactly on it"""
    z_r, z_f = Z_ARCH
    sec = section(z_r)
    for side in (1, -1):
        A = [V(side * p[0], p[1] + T_OUT, z_r) for p in sec]
        Bf = []
        for x, y in sec:
            yy = yc(z_f) if x <= POST_OUT else ys(x, z_f)
            Bf.append(V(side * x, yy + T_OUT, z_f))
        C = [p - np.array([0, T_ARCH, 0]) for p in Bf]
        D = [p - np.array([0, T_ARCH, 0]) for p in A]
        for Ln in (A, Bf, C, D):
            Ln[0] = Ln[0] + np.array([0, -0.012, 0])
        b.sweep(A, Bf, C, D)


def rail_bars(b):
    """the sill rails: 6.5 cm wide, top at the glass foot's line, buried 4.5 cm into the fuselage's sill ridge"""
    zs = np.linspace(Z_RB[0], Z_ARCH[1], 9)
    for side in (1, -1):
        def line(dx, dy):
            return [V(side * (foot_x(min(z, Z_ARCH[0])) + dx), foot_y(min(z, Z_ARCH[0])) + dy, z) for z in zs]
        A, B = line(-0.035, 0.0), line(0.030, 0.0)
        C, D = line(0.030, -0.045), line(-0.035, -0.045)
        b.sweep(A, B, C, D)


def windscreen_bars(b):
    zs = np.linspace(Z_WS[0], Z_WS[1], 9)
    for side in (1, -1):                                  # posts: 4.5 cm strips between the centre pane's edge and the quarter planes
        top_in = [V(side * POST_IN, yc(z) + T_OUT, z) for z in zs]
        top_out = [V(side * POST_OUT, yc(z) + T_OUT, z) for z in zs]
        bot_out = [V(side * POST_OUT, yc(z) - 0.030, z) for z in zs]
        bot_in = [V(side * POST_IN, yc(z) - 0.030, z) for z in zs]
        b.sweep(top_in, top_out, bot_out, bot_in)
    x0 = POST_OUT + 0.020                                 # the base bar on the deck
    zb0, zb1 = Z_BASE
    A = [V(-x0, yc(zb0) + T_OUT, zb0), V(x0, yc(zb0) + T_OUT, zb0)]
    B = [V(-x0, yc(zb1) + T_OUT, zb1), V(x0, yc(zb1) + T_OUT, zb1)]
    C = [V(-x0, yc(zb1) - 0.055, zb1), V(x0, yc(zb1) - 0.055, zb1)]
    D = [V(-x0, yc(zb0) - 0.055, zb0), V(x0, yc(zb0) - 0.055, zb0)]
    b.sweep(A, B, C, D)
    ctr = V(0, yc(0.5 * (zb0 + zb1)) - 0.02, 0.5 * (zb0 + zb1))
    for i in (0, 1):
        b.cap([A[i], B[i], C[i], D[i]], ctr)
    for side in (1, -1):                                  # the quarter panes' sill strips, in the pane's plane, with a skirt down to the deck
        zk = list(np.linspace(Z_ARCH[1], Z_WS[1], 8))
        edge = [V(side * x_out(z), ys(x_out(z), z), z) for z in zk]
        top_in, top_out, bot_out, bot_in = [], [], [], []
        for k, p in enumerate(edge):
            q = edge[min(k + 1, len(edge) - 1)] - edge[max(k - 1, 0)]
            tdir = q / np.linalg.norm(q)
            nplane = np.array([side * SIDE_SLOPE, 1.0, 0.64])
            nplane /= np.linalg.norm(nplane)
            d = np.cross(tdir, nplane)
            if d[0] * side < 0:
                d = -d
            d /= np.linalg.norm(d)
            top_in.append(p + nplane * T_OUT)
            top_out.append(p + d * SIDE_W + nplane * T_OUT)
            bot_out.append(p + d * SIDE_W - nplane * 0.030)
            bot_in.append(p - nplane * 0.030)
        b.sweep(top_in, top_out, bot_out, bot_in)
        for k in range(len(top_out) - 1):                 # the skirt: from the strip's outer edge straight down into the deck
            p0, p1 = top_out[k], top_out[k + 1]
            lo0 = np.array([p0[0], yr(DECK_Y), p0[2]]); lo1 = np.array([p1[0], yr(DECK_Y), p1[2]])
            if p0[1] - yr(DECK_Y) > 0.002 or p1[1] - yr(DECK_Y) > 0.002:
                b.quad([p0, p1, lo1, lo0], np.array([0, p0[1], p0[2]]))


def mirror_bars(b, seg=14):
    """the two rear-view mirror blisters on the canopy's top, just behind the front bow: half-ellipsoid housings (7 x 4.5 cm
    on the canopy, 3 cm proud of the glass), closed solids standing on the shell"""
    zc, xc = 0.33, 0.19
    a_z, a_x, h = 0.040, 0.026, 0.032
    for side in (1, -1):
        sec = section(zc)
        xs = [p[0] for p in sec]
        ys_ = [p[1] for p in sec]
        base = float(np.interp(xc, xs[::-1], ys_[::-1])) - 0.004
        c = V(side * xc, base, zc)
        rings = []
        for i in range(5):                               # latitude: 0 at the base rim, up to the dome
            t = i / 4.0
            ang = t * math.pi / 2
            rz, rx, hh = a_z * math.cos(ang), a_x * math.cos(ang), h * math.sin(ang)
            ring = []
            for k in range(seg):
                a = 2 * math.pi * k / seg
                ring.append(c + np.array([rx * math.cos(a), hh, rz * math.sin(a)]))
            rings.append(ring)
        inside = c + np.array([0, h * 0.2, 0])
        for i in range(len(rings) - 1):
            for k in range(seg):
                q = [rings[i][k], rings[i][(k + 1) % seg], rings[i + 1][(k + 1) % seg], rings[i + 1][k]]
                if i == len(rings) - 2:
                    q = [q[0], q[1], q[2]] if np.linalg.norm(q[2] - q[3]) < 1e-9 else q
                b.quad(q, inside)


def frame_bars():
    b = Bars(UV_FRAME)
    rail_bars(b)
    bow_bars(b, *Z_RB)
    arch_bars(b)
    windscreen_bars(b)
    mirror_bars(b)
    return b


# ---------------------------------------------------------------- the airframe

def cut_box(bones, already):
    """removes every face of the fuselage pieces lying wholly in CUT_BOX (eye-relative); returns the faces removed. A second
    run (the bars' bone already holds faces) finds none and cuts nothing more"""
    off = np.array([0.0, EY, EZ])
    lo = np.array(CUT_BOX[0]) + off - 0.005
    hi = np.array(CUT_BOX[1]) + off + 0.005
    n = 0
    for hull in bones:
        if hull['name'] not in HULLS:
            continue
        pm = hull['poly_mesh']
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        keep = []
        for poly in pm['polys']:
            if poly:
                Q = P[[v[0] for v in poly]]
                if np.all(Q.min(0) >= lo) and np.all(Q.max(0) <= hi):
                    n += 1
                    continue
            keep.append(poly)
        pm['polys'] = keep
    return n


def glass_triangles():
    tris = shell_patch(Z_RB[1], Z_ARCH[0], 28)                 # one unbroken shell: rear bow -> front bow
    tris += windscreen_glass()                                 # the windscreen: flat centre pane, two flat quarter panes
    return [np.array(t) for t in tris]


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    bars_bone = next((b for b in bones if b['name'] == BARS_BONE), None)
    if bars_bone is None:
        hull = next(b for b in bones if b['name'] == 'hull')
        bars_bone = {'name': BARS_BONE, 'parent': 'hull', 'pivot': list(hull['pivot'])}
        at = next(i for i, b in enumerate(bones) if b['name'] == AFTER_BONE) + 1
        bones.insert(at, bars_bone)
    cut = cut_box(bones, False)
    bars = frame_bars()
    bars_bone['poly_mesh'] = bars.mesh()
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f"mirage_3e canopy: the model's frame and tub cut out ({cut} faces), {len(bars.polys)} faces of slim frames; "
          f'{len(tris)} glass triangles (x2 windings): one unbroken blown canopy, windscreen panes')
    if dry:
        return
    with open(GEO, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(geo, f, separators=(',', ':'))
        f.write('\n')
    glass_json = asset.pop('CanopyGlass')
    text = json.dumps(asset, indent=2, ensure_ascii=False)
    text = text[:-2] + ',\n  "CanopyGlass": ' + json.dumps(glass_json, separators=(',', ':')) + '\n}'
    with open(ASSET, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text + '\n')


if __name__ == '__main__':
    main(sys.argv[1:])
