#!/usr/bin/env python3
"""Mirage IVA canopies and windscreen, hand-built for the Dassault Mirage IV.mtb airframe (commits bacade87, 4d53a7e7): two
crew in tandem, each under their own transparency, on the model's own pear-shaped fuselage.

The model's cockpit zone (all numbers eye-relative, from the pilot's eye (0, 2.82852, 6.19348); read off the faces of the
untouched model, checks/orig, with the section sheet and a ray scan): a frame-only windscreen ahead of the front bay
(old top bow at y +0.05, z +0.40, the centre posts falling to the nose deck at y -0.45, z +1.3), the front crew's closed
hood (old roof y +0.20 at z -1.1 falling to +0.05 at z +0.4), a rear crew bay open at the top and sides between two
floating frame arcs (z -1.67 .. -1.13, the old walls end at y -0.18) and a closed spine behind it (top y +0.20). The
outline of the old skin fits a half-ellipse from the wall top (x 0.40..0.58, y -0.25 / -0.18) to the crown on the centre
line, so the new members use that ellipse and the hull outside them does not change.

The real Mirage IVA (Dassault documentation, type photos; no drawing with dimensions was found, so every number is the
model's own line): two seats in tandem under separate clamshell transparencies hinged at the rear, a stout fixed
windscreen frame whose front pane is flat and armoured, the rear crew (navigator / bomb aimer) under a small, mostly
opaque hood with tiny side windows. Here:

  * pilot canopy: one unbroken blown shell (half-ellipse sections from the sill rail up to a crown) from the shared bow
    behind the pilot to the front bow; two hinge lugs on the bow's top; no bow between;
  * windscreen: the flat armoured centre pane between two stout posts (|x| <= 0.10, one plane from the front bow down to the
    base bar on the nose deck) and a flat quarter pane each side (a plane through the posts' outer edge, bounded by a sill
    strip along the nose skin). The windscreen's arch IS the canopy's front bow: one member where the two glasses meet;
  * rear crew's hood: closed shell (outer skin in the airframe's grey, dark inner lining) over the open bay with one small
    window each side in a slim frame; its front wall and the pilot canopy's rear bow are ONE member (the shared bow);
  * frames (bone wreck_fuselage_1__canopy, closed solids, 4 to 6.5 cm): the sill rails, the shared bow with its hinge
    lugs, the front bow / windscreen arch, two posts, the side sill strips, the base bar, the hood's window frames.

CANOPY (eye-relative sections, the right half from the sill up over the top, rear hood and windscreen included) is what the
checks in work/elite_expansion_2/checks read. Frames: vehicle-local blocks (x left, y up, z forward); meshes are written in
model pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script mirage_4a.py then appends the sight glass to CanopyGlass.
Usage: python tools/cockpit_models/canopy_mirage_4a.py [--dry-run]"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/mirage_4a.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/mirage_4a.json')

BARS_BONE = 'wreck_fuselage_1__canopy'    # (a new bone beside the cockpit bone: the frames break away with the forward fuselage)
AFTER_BONE = 'wreck_fuselage_1__hull'
HULLS = ('wreck_fuselage_0__hull', 'wreck_fuselage_1__hull')
# texels of the model's own palette (grey 192 body, black 26 frame lines), 5 x 5 uniform blocks of the texture (x, y)
TEX_W, TEX_H = 2048, 3072
UV_FRAME = [round((1329 + 0.5) / TEX_W, 5), round(1 - (3004 + 0.5) / TEX_H, 5)]
UV_BODY = [round((1391 + 0.5) / TEX_W, 5), round(1 - (3004 + 0.5) / TEX_H, 5)]

EZ, EY = 6.19348, 2.82852                 # the pilot's eye (data)
NAV_DZ = 4.51899 - EZ                     # the navigator's eye, eye-relative


def zr(z):                                # eye-relative z -> vehicle z
    return EZ + z


def yr(y):
    return EY + y


# ---- the old frame, interior and tubs: every face of the two fuselage pieces lying wholly in one of these boxes (eye-relative
# lo, hi, with a 5 mm tolerance) goes; the exterior skin among them is put back by the restore list (make_restore.py)
CUT_BOXES = (((-0.70, -1.14, -1.33), (0.70, 0.45, 1.46)),        # the pilot's tub, hood (its side plates start at z -1.31), old windscreen
             ((-0.70, -0.19, -1.70), (0.70, 0.45, -1.07)),       # the old frame arcs over the rear bay
             ((-0.70, -0.30, -2.20), (0.70, 0.20, -1.74)),       # the old rear seat's headbox under the closed hood
             ((-0.70, -0.42, -2.16), (0.70, -0.05, -1.20)),      # the old rear bay's side plates (skin below the sill is restored clipped)
             ((-0.70, -1.14, -2.42), (0.70, -0.19, -1.07)))      # the rear crew's tub

# ---- the pilot's canopy (eye-relative z): the shared bow, then the unbroken shell to the front bow
Z_RB = (-1.12, -1.06)                     # the shared bow: rear crew's front wall = pilot's rear bow (6 cm)
Z_ARCH = (0.40, 0.44)                     # front bow = windscreen arch
SILL_P = -0.25                            # the pilot's sill behind z 0 (the old walls' top); it falls to -0.355 at the front bow with them (the old side skin steps down there)
FOOT_Y_P = [(-1.12, -0.25), (0.0, -0.25), (0.20, -0.27), (0.35, -0.29), (0.40, -0.355)]
EXP = 2.0                                 # the sections are half ellipses (they fit the old skin within 2 cm)
FOOT_X_P = [(-1.12, 0.50), (-0.80, 0.49), (-0.40, 0.455), (0.0, 0.43), (0.20, 0.418), (0.40, 0.405)]
CROWN_P = [(-1.12, 0.20), (-0.80, 0.18), (-0.40, 0.15), (0.0, 0.11), (0.20, 0.085), (0.40, 0.055)]

# ---- the rear crew's hood over the open bay (eye-relative z)
Z_HOOD = (-1.67, -1.12)                   # the old closed hood stands behind z -1.67
SILL_N = -0.18                            # the old walls' top at the bay
CROWN_N = 0.20
FOOT_X_N = [(-2.45, 0.616), (-2.00, 0.571), (-1.67, 0.541), (-1.40, 0.516), (-1.12, 0.496)]
WIN_Z = (-1.58, -1.24)                    # the small side windows (z, then y)
WIN_Y = (-0.10, 0.04)
HOOD_T = 0.030                            # shell thickness


def _interp(tab, z):
    return float(np.interp(z, [p[0] for p in tab], [p[1] for p in tab]))


def foot_x(z):
    return _interp(FOOT_X_P, z) if z > Z_RB[0] + 1e-9 else _interp(FOOT_X_N, z)


def foot_y(z):
    return _interp(FOOT_Y_P, z) if z > Z_RB[0] + 1e-9 else SILL_N


def crown(z):
    return _interp(CROWN_P, z) if z > Z_RB[0] + 1e-9 else CROWN_N


def half_section(z, n=24):
    """right half-section at station z: (x, y) from the foot up to the crown (x = 0), a half ellipse"""
    if z > Z_RB[0] + 1e-9:
        z = min(max(z, Z_RB[0]), Z_ARCH[0])
    else:
        z = max(z, -2.45)
    x0, y0, yt = foot_x(z), foot_y(z), crown(z)
    ts = np.linspace(0, math.pi / 2, n)
    pts = [(x0 * math.cos(t), y0 + (yt - y0) * math.sin(t)) for t in ts]
    pts[-1] = (0.0, yt)
    return pts


def section(z, n=24):                     # (the name the pilot script and the checks use)
    return half_section(z, n)


# ---- the windscreen: a flat centre pane and two flat quarter panes between stout members
Z_BASE = (1.30, 1.345)                    # the base bar on the nose deck
Z_WS = (Z_ARCH[1], Z_BASE[0])             # the panes run from the arch's front face to the base bar's rear face
POST_IN, POST_OUT = 0.100, 0.155
SIDE_SLOPE = 0.9                          # the quarter panes' plane drops 0.9 per block of |x| out from the posts' outer edge
SIDE_W = 0.030                            # the sill strips along the quarter panes' outer edges
DECK_Y = -0.52                            # the skirts end buried in the deck


def yc(z):                                # the centre pane's plane (flat: independent of x)
    return 0.055 - 0.57 * (z - Z_ARCH[1])


def ys(x, z):                             # the quarter pane's plane at |x|
    return yc(z) - SIDE_SLOPE * (x - POST_OUT)


def x_out(z):                             # the quarter pane's outer edge: along the nose skin, narrowing to the nose
    return 0.42 - 0.14 * (z - Z_WS[0]) / (Z_WS[1] - Z_WS[0])


def sill_y(z):
    return foot_y(z) if z <= Z_ARCH[0] else -0.355 - 0.20 * (z - Z_ARCH[0])


def outline_rel():
    """the outline the checks read, from the sill line up the glass: the rear hood's and the pilot canopy's sections, then
    the windscreen's stations (from the quarter pane's outer edge over the pane and the post to the centre pane)"""
    out = []
    for z in (-2.45, -2.0, -1.67, -1.4, -1.13):
        out.append((z, half_section(z)))
    for z in (-1.12, -0.8, -0.4, 0.0, 0.2, 0.4):
        out.append((z, half_section(z)))
    for z in (0.46, 0.6, 0.8, 1.0, 1.2, 1.3):
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
            r = resample(half_section(z), ns + 1)
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


# ---------------------------------------------------------------- the rear hood's surface

HOOD_Z = [-1.67, -1.625, WIN_Z[0], -1.50, -1.41, -1.32, WIN_Z[1], -1.18, -1.12]
HOOD_U = None                             # set below (u = (y - sill) / (crown - sill))


def _u(y):
    return (y - SILL_N) / (CROWN_N - SILL_N)


HOOD_U = [0.0, _u(WIN_Y[0]), 0.22, 0.34, 0.45, _u(WIN_Y[1]), 0.66, 0.77, 0.885, 1.0]


def hood_pt(side, z, u, off=0.0):
    """point on the hood's outer ellipse (parameter u = height fraction), moved [off] along the outward normal"""
    a = foot_x(z)
    h = CROWN_N - SILL_N
    t = math.asin(min(1.0, max(0.0, u)))
    x, y = a * math.cos(t), SILL_N + h * math.sin(t)
    if off:
        nx, ny = math.cos(t) / a, math.sin(t) / h
        ln = math.hypot(nx, ny)
        x, y = x + off * nx / ln, y + off * ny / ln
    return V(side * x, y, z)


def in_window(i, j):
    """the grid cell (z index i, u index j) lies inside the window opening"""
    z0, z1 = HOOD_Z[i], HOOD_Z[i + 1]
    u0, u1 = HOOD_U[j], HOOD_U[j + 1]
    return z0 >= WIN_Z[0] - 1e-9 and z1 <= WIN_Z[1] + 1e-9 and u0 >= _u(WIN_Y[0]) - 1e-9 and u1 <= _u(WIN_Y[1]) + 1e-9


def hood_glass():
    out = []
    for side in (1, -1):
        for i in range(len(HOOD_Z) - 1):
            for j in range(len(HOOD_U) - 1):
                if not in_window(i, j):
                    continue
                a = hood_pt(side, HOOD_Z[i], HOOD_U[j], -0.012)
                b = hood_pt(side, HOOD_Z[i + 1], HOOD_U[j], -0.012)
                c = hood_pt(side, HOOD_Z[i + 1], HOOD_U[j + 1], -0.012)
                d = hood_pt(side, HOOD_Z[i], HOOD_U[j + 1], -0.012)
                for tri in ((a, b, c), (a, c, d)):
                    out.append(list(tri))
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

    def box(self, c, half):
        """an axis-aligned closed box (vehicle-local centre c and half sizes)"""
        c = np.array(c, float)
        h = np.array(half, float)
        for k in range(3):
            for s in (-1, 1):
                i, j = [m for m in range(3) if m != k]
                e = np.zeros(3); e[k] = s * h[k]
                ei = np.zeros(3); ei[i] = h[i]
                ej = np.zeros(3); ej[j] = h[j]
                f = c + e
                self.quad([f - ei - ej, f + ei - ej, f + ei + ej, f - ei + ej], c)


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
    sec = half_section(zc)
    nrm = out_normals(sec)
    for side in (1, -1):
        def line(z, rad):
            return [V(side * (p[0] + n[0] * rad), p[1] + n[1] * rad, z) for p, n in zip(sec, nrm)]
        A, B, C, D = line(z0, T_OUT), line(z1, T_OUT), line(z1, -T_IN), line(z0, -T_IN)
        for Ln in (A, B, C, D):
            Ln[0] = Ln[0] + np.array([0, -0.012, 0])        # the foot goes into the rail
        b.sweep(A, B, C, D)


def hinge_lugs(b):
    """two hinge lugs on the shared bow's top (the pilot's canopy is hinged at the rear)"""
    zc = 0.5 * (Z_RB[0] + Z_RB[1])
    top = crown(zc)
    for s in (1, -1):
        b.box(V(s * 0.085, top + T_OUT + 0.011, zc), (0.020, 0.011, 0.034))
        b.box(V(s * 0.085, top + T_OUT + 0.026, zc), (0.012, 0.005, 0.012))


def arch_bars(b):
    """the windscreen arch = canopy front bow: one member, ruled between the shell's end edge on its rear face and the
    windscreen's top edge on its front face, so each glass stops exactly on it"""
    z_r, z_f = Z_ARCH
    sec = half_section(z_r)
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
    """the pilot's sill rails: 6.5 cm wide, top at the glass foot's line, buried 4.5 cm into the fuselage's sill ridge"""
    zs = np.linspace(Z_RB[0], Z_ARCH[1], 14)
    for side in (1, -1):
        def line(dx, dy):
            return [V(side * (foot_x(max(min(z, Z_ARCH[0]), Z_RB[0] + 1e-6)) + dx), foot_y(max(min(z, Z_ARCH[0]), Z_RB[0] + 1e-6)) + dy, z) for z in zs]
        A, B = line(-0.035, 0.0), line(0.022, 0.0)
        C, D = line(0.022, -0.045), line(-0.035, -0.045)
        b.sweep(A, B, C, D)


def windscreen_bars(b):
    zs = np.linspace(Z_WS[0], Z_WS[1], 9)
    for side in (1, -1):                                  # posts: 5.5 cm strips between the centre pane's edge and the quarter planes
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
            nplane = np.array([side * SIDE_SLOPE, 1.0, 0.57])
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


def hood_body(b):
    """the rear crew's hood: outer skin in the airframe grey, a dark lining 3 cm inside, rims; no skin in the window openings"""
    nz, nu = len(HOOD_Z) - 1, len(HOOD_U) - 1
    for side in (1, -1):
        for i in range(nz):
            for j in range(nu):
                if in_window(i, j):
                    continue
                q_out = [hood_pt(side, HOOD_Z[i], HOOD_U[j]), hood_pt(side, HOOD_Z[i + 1], HOOD_U[j]),
                         hood_pt(side, HOOD_Z[i + 1], HOOD_U[j + 1]), hood_pt(side, HOOD_Z[i], HOOD_U[j + 1])]
                q_in = [hood_pt(side, HOOD_Z[i], HOOD_U[j], -HOOD_T), hood_pt(side, HOOD_Z[i + 1], HOOD_U[j], -HOOD_T),
                        hood_pt(side, HOOD_Z[i + 1], HOOD_U[j + 1], -HOOD_T), hood_pt(side, HOOD_Z[i], HOOD_U[j + 1], -HOOD_T)]
                c = np.mean(q_out, 0)
                inner_pt = V(0.0, -0.1, c[2] - EZ)
                far = c + (c - inner_pt) * 3
                b.uv = UV_BODY
                b.quad(q_out, inner_pt)
                b.uv = UV_FRAME
                b.quad(q_in, far)
        b.uv = UV_FRAME
        for i in range(nz):                                # the base rim (the shell's lower edge)
            q = [hood_pt(side, HOOD_Z[i], 0.0), hood_pt(side, HOOD_Z[i + 1], 0.0),
                 hood_pt(side, HOOD_Z[i + 1], 0.0, -HOOD_T), hood_pt(side, HOOD_Z[i], 0.0, -HOOD_T)]
            b.quad(q, V(0.0, 0.4, 0.5 * (HOOD_Z[i] + HOOD_Z[i + 1])))
        for j in range(nu):                                # the rear end's rim
            q = [hood_pt(side, HOOD_Z[0], HOOD_U[j]), hood_pt(side, HOOD_Z[0], HOOD_U[j + 1]),
                 hood_pt(side, HOOD_Z[0], HOOD_U[j + 1], -HOOD_T), hood_pt(side, HOOD_Z[0], HOOD_U[j], -HOOD_T)]
            b.quad(q, V(0.0, 0.0, HOOD_Z[0] + 0.5))
    # the front wall of the hood is the shared bow (bow_bars); the lining's front edge butts onto it


def window_frames(b):
    """slim frames round the hood's two windows (2.2 cm wide, outside the opening's edge; the glass stops at their edge)"""
    b.uv = UV_FRAME
    z0, z1 = WIN_Z
    u0, u1 = _u(WIN_Y[0]), _u(WIN_Y[1])
    w = 0.022
    zn = np.linspace(z0 - w, z1 + w, 9)
    un = np.linspace(u0, u1, 6)
    for side in (1, -1):
        def solid(zs, us):
            """a bar over the patch (zs list, us list of u): outer face 1 cm proud, inner face into the window reveal"""
            grid_o = [[hood_pt(side, z, u, 0.010) for u in us] for z in zs]
            grid_i = [[hood_pt(side, z, u, -HOOD_T) for u in us] for z in zs]
            ctr = np.mean([p for r in grid_i for p in r], 0)
            for i in range(len(zs) - 1):
                for j in range(len(us) - 1):
                    b.quad([grid_o[i][j], grid_o[i + 1][j], grid_o[i + 1][j + 1], grid_o[i][j + 1]], ctr)
            for i in range(len(zs) - 1):
                for k in (0, len(us) - 1):
                    b.quad([grid_o[i][k], grid_o[i + 1][k], grid_i[i + 1][k], grid_i[i][k]], ctr)
            for j in range(len(us) - 1):
                for k in (0, len(zs) - 1):
                    b.quad([grid_o[k][j], grid_o[k][j + 1], grid_i[k][j + 1], grid_i[k][j]], ctr)
        du = (u1 - u0) * 0.14
        solid(zn, np.linspace(u1, u1 + du, 3))                      # top bar
        solid(zn, np.linspace(u0 - du, u0, 3))                      # bottom bar
        solid(np.linspace(z0 - w, z0, 3), np.linspace(u0, u1, 5))   # front/rear ends
        solid(np.linspace(z1, z1 + w, 3), np.linspace(u0, u1, 5))


def frame_bars():
    b = Bars(UV_FRAME)
    rail_bars(b)
    bow_bars(b, *Z_RB)
    hinge_lugs(b)
    arch_bars(b)
    windscreen_bars(b)
    hood_body(b)
    window_frames(b)
    return b


# ---------------------------------------------------------------- the airframe

def cut_box(bones, already):
    """removes every face of the fuselage pieces lying wholly in one of CUT_BOXES (eye-relative); returns the faces removed.
    A second run (the bars' bone already holds faces) finds none and cuts nothing more"""
    off = np.array([0.0, EY, EZ])
    n = 0
    for hull in bones:
        if hull['name'] not in HULLS:
            continue
        pm = hull['poly_mesh']
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        keep = []
        for poly in pm['polys']:
            gone = False
            if poly:
                Q = P[[v[0] for v in poly]]
                for lo, hi in CUT_BOXES:
                    if np.all(Q.min(0) >= np.array(lo) + off - 0.005) and np.all(Q.max(0) <= np.array(hi) + off + 0.005):
                        gone = True
                        break
            if gone:
                n += 1
                continue
            keep.append(poly)
        pm['polys'] = keep
    return n


def glass_triangles():
    tris = shell_patch(Z_RB[1], Z_ARCH[0], 34)                 # one unbroken shell: shared bow -> front bow
    tris += windscreen_glass()                                 # the windscreen: flat centre pane, two flat quarter panes
    tris += hood_glass()                                       # the rear crew's two small windows
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
    print(f"mirage_4a canopy: the model's frames, hood and tubs cut out ({cut} faces), {len(bars.polys)} faces of slim frames and "
          f'the rear hood; {len(tris)} glass triangles (x2 windings): the pilot canopy, windscreen panes, two hood windows')
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
