#!/usr/bin/env python3
"""Yak-38M canopy and windscreen, hand-built for the Yak-38M.mtb airframe (commits 411a832a, 25641d56): glass and slim
frames on the real Yak-38 layout, replacing the model's own frame-only canopy.

The real Yak-38 / Yak-38M (the Yak-36 / Yak-38 descriptions and kit reviews; no dimensioned drawing and no clear photo of
the glazing was found, so every number below is the model's own line, not a measurement of the real jet): a framed
windscreen with ONE flat, steeply raked armoured front pane and a flat quarter pane each side of it (separated from the
front pane by two slim posts), and behind it ONE blown (curved) canopy with a rim frame, hinged along its right side
(it swings up and to starboard) and jettisoned in the ejection sequence. The canopy has no centre bow and no posts of its
own. The windscreen's arch IS the canopy's front bow: one member where the shell and the windscreen glass meet.

  * canopy glass: one unbroken blown shell from the rear bow, which stands against the dorsal fairing's front face
    (z -0.848 from the eye), to the front bow = windscreen arch. The shell's section is rounded at the rear and becomes the
    arch's flat-topped hexagon by the front, so the arch bar is one member of even depth whose straight sides are the
    top edges of the quarter panes;
  * windscreen: the front pane is one flat trapezoid in the plane y = YC0 - SLOPE (z - ZF) from the arch down to the base
    bar on the nose deck; the two posts are strips of the same plane; each quarter pane is one flat triangle (arch corner,
    arch foot, base-bar corner), so it is planar by construction. Every piece of glass stops at its member's edge;
  * frames (bone wreck_fuselage_1__canopy, closed solids, 4 to 6.5 cm, airframe grey): the two sill rails (with the
    console ledge along their inner side) and the sill strips along the quarter panes, the rear bow, the front bow / windscreen arch, the two posts and the base bar, and on
    the right (starboard) rail three hinge lugs and a hinge strip. No centre rib.

Model numbers (work/elite_expansion_2/yak38/NOTES.md section 8, rechecked on the faces; all from the pilot's eye at
(0, 2.34618, 2.46611)): the shoulders beside the cockpit slope down toward the nose along the line the sill rails follow
(SILL: y -0.11 at z -0.8 to -0.575 at z 0.6, 1.5 cm over the shoulders' top at x 0.30 .. 0.35); the dorsal fairing's front face stands at z -0.848 (its plates top at y +0.05); the
nose deck is flat at y -0.50 at z 1.27 (x < 0.2) and the base bar stands there. The model's own frame (the headrest hoop,
the rear bulkhead, the windscreen ribs: CUT_BOXES, source elements 14..20, 39..41, 77, 172..179, 690..692 and their
mirrors) is cut out by element boxes; everything else of the skin stays.

CANOPY (eye-relative sections, the right half from the sill up over the top, windscreen included) is what the checks in
work/elite_expansion_2/checks read. Frames: vehicle-local blocks (x left, y up, z forward); meshes are written in model
pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script yak_38m.py then appends the sight glass to CanopyGlass.
Usage: python tools/cockpit_models/canopy_yak_38m.py [--dry-run]"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/yak_38m.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/yak_38m.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/yak_38m.json')
TEXTURE = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/yak_38m.png')


def _eye():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        s = d['Seats'][0]
        return np.array(d['Attachments'][s['CameraPos']['EyeAttachment']]['Position'], float)
    except (OSError, KeyError):
        return np.array([0.0, 2.34618, 2.46611])


EYE = _eye()
BARS_BONE = 'wreck_fuselage_1__canopy'    # (made after the hull piece when the airframe has none: the frames go with the section)
H1 = 'wreck_fuselage_1__hull'
H0 = 'wreck_fuselage_0__hull'
FRAME_RGB = (62, 77, 89)                  # the airframe's blue-grey (the model's own texel); UV_FRAME is found in main()
UV_FRAME = None

# ---- the model's frame, cut out: every face of the two hull pieces lying wholly inside one of these element boxes (absolute
# blocks, x0 y0 z0 x1 y1 z1). EXPECT_CUT is the face count the first run must find (the airframe's face list must be the
# committed one); a second run finds none.
CUT_BOXES = [
    (0.300, 2.047, 1.867, 0.349, 2.147, 2.017), (0.300, 1.947, 1.867, 0.449, 2.147, 2.017), (0.399, 1.398, 1.867, 0.449, 1.947, 2.017),
    (0.000, 1.947, 1.617, 0.449, 2.246, 1.867), (0.000, 1.398, 1.617, 0.449, 1.947, 1.867), (0.250, 2.147, 1.867, 0.349, 2.296, 2.017),
    (0.000, 2.147, 1.617, 0.349, 2.296, 1.867), (0.000, 2.296, 1.617, 0.300, 2.396, 1.867), (0.150, 2.296, 1.867, 0.300, 2.396, 1.967),
    (0.000, 2.346, 1.867, 0.200, 2.396, 1.967), (0.399, 2.147, 1.568, 0.499, 2.396, 1.917), (0.300, 1.797, 2.766, 0.349, 1.997, 3.015),
    (0.250, 1.997, 2.915, 0.349, 2.196, 3.115), (0.150, 2.196, 3.015, 0.300, 2.296, 3.165), (0.000, 2.246, 3.065, 0.200, 2.346, 3.165),
    (0.100, 2.147, 3.165, 0.200, 2.296, 3.265), (0.000, 2.171, 3.165, 0.100, 2.296, 3.265), (0.100, 1.997, 3.265, 0.250, 2.221, 3.464),
    (0.150, 1.897, 3.464, 0.250, 2.072, 3.614), (-0.349, 2.047, 1.867, -0.300, 2.147, 2.017), (-0.449, 1.947, 1.867, -0.300, 2.147, 2.017),
    (-0.449, 1.398, 1.867, -0.399, 1.947, 2.017), (-0.449, 1.398, 1.617, 0.000, 1.947, 1.867), (-0.349, 2.147, 1.867, -0.250, 2.296, 2.017),
    (-0.349, 2.147, 1.617, 0.000, 2.296, 1.867), (-0.300, 2.296, 1.617, 0.000, 2.396, 1.867), (-0.300, 2.296, 1.867, -0.150, 2.396, 1.967),
    (-0.200, 2.346, 1.867, 0.000, 2.396, 1.967), (-0.499, 2.147, 1.568, -0.399, 2.396, 1.917), (-0.349, 1.797, 2.766, -0.300, 1.997, 3.015),
    (-0.349, 1.997, 2.915, -0.250, 2.196, 3.115), (-0.300, 2.196, 3.015, -0.150, 2.296, 3.165), (-0.200, 2.147, 3.165, -0.100, 2.296, 3.265),
    (-0.100, 2.171, 3.165, 0.000, 2.296, 3.265), (-0.250, 1.997, 3.265, -0.100, 2.221, 3.464), (-0.250, 1.897, 3.464, -0.150, 2.072, 3.614),
    (0.000, 2.246, 3.065, 0.050, 2.346, 3.165), (-0.200, 2.246, 3.065, 0.000, 2.346, 3.165), (-0.050, 2.246, 3.065, 0.000, 2.346, 3.165),
    (-0.449, 1.947, 1.617, 0.000, 2.246, 1.867),
]
EXPECT_CUT = 258
CUT_TOL = 0.004


def R(x, y, z):
    return np.array([x, y, z], float)


# ---- the canopy (eye-relative blocks: x to the left, y up, z forward)
Z_RB = (-0.845, -0.805)                   # rear bow (4 cm, 3 mm clear of the dorsal fairing's front face at -0.848)
Z_ARCH = (0.585, 0.630)                   # front bow = windscreen arch
Z_END = Z_ARCH[0]
X_F = 0.330                               # the glass foot: the middle of the sill rail (the rail's inner edge at 0.295)
# the sill line: the top of the shoulders beside the cockpit (inner top edge, x 0.30 .. 0.35) read off the model, z -> y
# (the rails' top stands 1.5 cm above the shoulders' top: nothing of the skin may stand inside the glass above the sill)
SILL = [(-0.80, -0.110), (-0.70, -0.145), (-0.60, -0.180), (-0.50, -0.240), (-0.40, -0.295), (-0.30, -0.325), (-0.20, -0.360),
        (0.00, -0.425), (0.20, -0.485), (0.40, -0.530), (0.60, -0.575)]
HEX_X = 0.21                              # the arch's flat top ends here (the post's outer edge at the arch)
CROWN = [(-0.800, 0.035), (-0.650, 0.100), (-0.500, 0.140), (-0.200, 0.170), (0.100, 0.160), (0.400, 0.125), (0.585, 0.095)]
YC0 = 0.095                               # the front pane's top edge: the arch's crown height
NEXP0, NEXP1 = 2.2, 1.8
W0, W1 = 0.0, 0.585                       # the section morphs from rounded to the arch's hexagon between these stations


def y_foot(z):
    return float(np.interp(min(max(z, -0.8), Z_END), [s[0] for s in SILL], [s[1] for s in SILL]))


def x_foot(z):
    return X_F


def crown(z):
    return float(np.interp(z, [c[0] for c in CROWN], [c[1] for c in CROWN]))


def resample(pts, k):
    p = np.array(pts, float)
    d = np.r_[0, np.cumsum(np.linalg.norm(np.diff(p, axis=0), axis=1))]
    t = np.linspace(0, d[-1], k)
    return np.c_[np.interp(t, d, p[:, 0]), np.interp(t, d, p[:, 1])]


def round_section(z, n=60):
    yt, yf, xf = crown(z), y_foot(z), x_foot(z)
    e = float(np.interp(z, [Z_RB[0], Z_END], [NEXP0, NEXP1]))
    us = np.linspace(0, 1, n)
    pts = [(xf * (1 - u ** e) ** (1 / e), yf + (yt - yf) * u) for u in us]
    pts[-1] = (0.0, yt)
    return pts


def hex_section(z):
    yt, yf, xf = crown(z), y_foot(z), x_foot(z)
    return [(xf, yf), (HEX_X, yt), (0.0, yt)]


def smooth(t):
    t = min(max(t, 0.0), 1.0)
    return t * t * (3 - 2 * t)


def section(z, n=24):
    """right half-section at station z (eye-relative): from the sill foot up to the crown (x = 0)"""
    z = min(max(z, Z_RB[0]), Z_END)
    w = smooth((z - W0) / (W1 - W0))
    a = resample(round_section(z), n)
    b = resample(hex_section(z), n)
    # (the hexagon's corner at HEX_X: put the morph's parameter on the corner for both)
    pts = a * (1 - w) + b * w
    pts[0] = (x_foot(z), y_foot(z))
    pts[-1] = (0.0, crown(z))
    return [tuple(p) for p in pts]


def sec_y(x, z=Z_END):
    p = np.array(section(z, 90))[::-1]            # from the crown out
    return float(np.interp(x, p[:, 0], p[:, 1]))


# ---- the windscreen: one flat front pane, two posts, two flat triangular quarter panes
ZF = Z_ARCH[1]                            # the arch's front face
Z_B0, Z_B1 = 1.270, 1.315                 # the base bar on the nose deck
YB = -0.505                               # the front pane's plane meets the nose deck here (the deck stands at -0.50)
SLOPE = (YC0 - YB) / (Z_B0 - ZF)          # the plane drops this much per block forward
HW0, HWB = 0.190, 0.130                   # the post's centre line: half width at the arch and at the base bar
POST = 0.020                              # half width of a post
T_OUT, T_IN = 0.012, 0.030
Y_FOOT_ARCH = y_foot(Z_END)               # the arch foot / the sill strip's start


def yc(z):                                # the front pane's plane (flat: independent of x)
    return YC0 - SLOPE * (z - ZF)


def hw(z):
    return HW0 + (HWB - HW0) * (z - ZF) / (Z_B0 - ZF)


# the quarter pane: the triangle T1 (arch corner on the post's outer edge), T2 (arch foot), B1 (the post's outer edge at the base)
T1 = R(HW0 + POST, YC0, ZF)
T2 = R(X_F, Y_FOOT_ARCH, ZF)
B1 = R(HWB + POST, YB, Z_B0)


def strip_xy(z):
    """the sill strip's inner top edge (the quarter pane's lower edge): T2 -> B1"""
    t = (z - ZF) / (Z_B0 - ZF)
    p = T2 + (B1 - T2) * t
    return p[0], p[1]


def outline_rel():
    """the canopy's outline the checks read: from the sill line up the glass; the windscreen stations run from the sill
    strip's edge over the quarter pane and the post to the centre line"""
    stations = [-0.84, -0.80, -0.5, -0.2, 0.1, 0.35, 0.585, 0.630, 0.80, 1.00, 1.15, 1.27]
    out = []
    for z in stations:
        if z <= Z_END + 1e-9:
            pts = section(max(z, Z_RB[0]))
        else:
            xo, yo = strip_xy(z)
            pts = [(xo, yo), (hw(z) + POST, yc(z)), (0.0, yc(z))]
        out.append((z, [(x, y) for x, y in pts]))
    return out


CANOPY = outline_rel()                    # eye-relative, as handbuilt_canopy.Shape wants it (the checks read this)


# ---------------------------------------------------------------- glass (eye-relative, converted when written)

def shell_patch(z0, z1, nz, ns=16):
    """the blown shell between two stations: row j of the arc-length resampled section at every station, both sides"""
    out = []
    for side in (1, -1):
        grid = []
        for i in range(nz + 1):
            z = z0 + (z1 - z0) * i / nz
            r = resample(section(z), ns + 1)
            grid.append([np.array([side * x, y, z]) for x, y in r])
        for i in range(nz):
            for j in range(ns):
                a, b, c, d = grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]
                for tri in ((a, b, c), (a, c, d)):
                    if np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0])) > 1e-10:
                        out.append(list(tri))
    return out


def windscreen_glass():
    out = []
    # the front pane: one trapezoid in the plane y = yc(z), between the posts' inner edges
    g0, gb = HW0 - POST, HWB - POST
    q = [R(-g0, yc(ZF), ZF), R(g0, yc(ZF), ZF), R(gb, yc(Z_B0), Z_B0), R(-gb, yc(Z_B0), Z_B0)]
    out += [[q[0], q[1], q[2]], [q[0], q[2], q[3]]]
    for side in (1, -1):                  # the quarter panes: one flat triangle each
        t = [R(side * T1[0], T1[1], T1[2]), R(side * T2[0], T2[1], T2[2]), R(side * B1[0], B1[1], B1[2])]
        out.append(t)
    return out


# ---------------------------------------------------------------- frames (closed solids)

class Bars:
    """faces of the slim frame members as a poly mesh in model pixels; each face has one winding, outward from the member.
    Points come in eye-relative blocks"""
    def __init__(self, uv):
        self.P, self.N, self.U, self.polys, self.uv = [], [], [], [], uv

    def face(self, pts, n):
        px = [[float(q[0] + EYE[0]) * 16, float(q[1] + EYE[1]) * 16, -float(q[2] + EYE[2]) * 16] for q in pts]
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

    def box(self, lo, hi):
        lo, hi = np.array(lo, float), np.array(hi, float)
        c = (lo + hi) / 2
        for ax in range(3):
            for s in (lo, hi):
                i, j = [m for m in range(3) if m != ax]
                q = []
                for (a, b) in ((0, 0), (1, 0), (1, 1), (0, 1)):
                    p = np.zeros(3)
                    p[ax] = s[ax]
                    p[i] = (lo, hi)[a][i]
                    p[j] = (lo, hi)[b][j]
                    q.append(p)
                self.quad(q, c)


def out_normals(pts):
    """unit normal (x, y) of a half-section polyline at each point, pointing outward (up and out)"""
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


def bow_bars(b, z0, z1):
    """a transverse bow over the shell between z0 and z1 (z1 - z0 deep, 3.8 cm radially, 1.2 cm proud of the glass)"""
    zc = 0.5 * (z0 + z1)
    sec = section(zc)
    nrm = out_normals(sec)
    for side in (1, -1):
        def line(z, rad):
            return [np.array([side * (p[0] + n[0] * rad), p[1] + n[1] * rad, z]) for p, n in zip(sec, nrm)]
        A, B, C, D = line(z0, T_OUT), line(z1, T_OUT), line(z1, -T_IN), line(z0, -T_IN)
        for Ln in (A, B, C, D):
            Ln[0] = Ln[0] + np.array([0, -0.045, 0])        # the foot goes into the sill
        b.sweep(A, B, C, D)


def arch_bars(b):
    """the windscreen arch = canopy front bow: one member, from the shell's end edge on its rear face to the windscreen's top
    edge on its front face (the hexagon's own line, so the front pane's and the quarter panes' top edges lie on it)"""
    zr, zf = Z_ARCH
    sec = section(Z_END)
    for side in (1, -1):
        A = [np.array([side * p[0], p[1] + T_OUT, zr]) for p in sec]
        Bf = [np.array([side * p[0], p[1] + T_OUT, zf]) for p in sec]
        C = [p - np.array([0, T_IN * 1.15, 0]) for p in Bf]
        D = [p - np.array([0, T_IN * 1.15, 0]) for p in A]
        for Ln in (A, Bf, C, D):
            Ln[0] = Ln[0] + np.array([0, -0.045, 0])
        b.sweep(A, Bf, C, D)


def rail_bars(b):
    """the sill rails: 6.5 cm wide, top at the glass foot's line, buried 4.5 cm into the sill skin"""
    zs = np.linspace(Z_RB[0], ZF, 12)
    for side in (1, -1):
        def line(dx, dy):
            return [np.array([side * (x_foot(z) + dx), y_foot(z) + dy, z]) for z in zs]
        A, B = line(-0.035, 0.0), line(0.030, 0.0)
        C, D = line(0.030, -0.045), line(-0.035, -0.045)
        b.sweep(A, B, C, D)
        # the console ledge: from the cockpit's side wall (x 0.268, 4 mm clear) out to the rail, 2 cm under the rail's top, 2.5 cm thick
        A, B = line(-0.058, -0.020), line(-0.035, -0.020)
        C, D = line(-0.035, -0.045), line(-0.058, -0.045)
        b.sweep(A, B, C, D)


def windscreen_bars(b):
    # the two posts: strips of the front pane's plane, 4 cm wide, 1.2 cm proud and 3 cm deep, arch to base bar
    zs = np.linspace(ZF, Z_B0, 6)
    for side in (1, -1):
        def line(dx, dy):
            return [np.array([side * (hw(z) + dx), yc(z) + dy, z]) for z in zs]
        A, B = line(-POST, T_OUT), line(POST, T_OUT)
        C, D = line(POST, -0.030), line(-POST, -0.030)
        b.sweep(A, B, C, D)
    # the base bar on the deck: across the posts and the quarter panes' feet, in the front pane's plane
    x0 = HWB + POST + 0.045
    A = [R(-x0, yc(Z_B0) + T_OUT, Z_B0), R(x0, yc(Z_B0) + T_OUT, Z_B0)]
    B = [R(-x0, yc(Z_B1) + T_OUT, Z_B1), R(x0, yc(Z_B1) + T_OUT, Z_B1)]
    C = [R(-x0, yc(Z_B1) - 0.055, Z_B1), R(x0, yc(Z_B1) - 0.055, Z_B1)]
    D = [R(-x0, yc(Z_B0) - 0.055, Z_B0), R(x0, yc(Z_B0) - 0.055, Z_B0)]
    b.sweep(A, B, C, D)
    ctr = R(0, yc(0.5 * (Z_B0 + Z_B1)) - 0.02, 0.5 * (Z_B0 + Z_B1))
    for i in (0, 1):
        b.cap([A[i], B[i], C[i], D[i]], ctr)
    # the sill strips along the quarter panes: from the arch foot to the base bar's end, outboard of the glass's lower
    # edge, top at that edge's height, 6.5 cm wide, down into the skin
    zs = np.linspace(ZF, Z_B0, 6)
    for side in (1, -1):
        def line(dx, dy):
            return [np.array([side * (strip_xy(z)[0] + dx), strip_xy(z)[1] + dy, z]) for z in zs]
        A_, B_ = line(0.0, 0.0), line(0.065, 0.0)
        C_ = [np.array([p[0], p[1] - 0.060, p[2]]) for p in B_]
        D_ = [np.array([p[0], p[1] - 0.060, p[2]]) for p in A_]
        b.sweep(A_, B_, C_, D_)


def hinge_bars(b):
    """the canopy hinges on its right (starboard, x < 0) rail: a hinge strip along the outside of the rail and three lugs"""
    zs = [-0.64, -0.12, 0.40]
    side = -1
    for z in zs:
        x_in, x_out = x_foot(z) + 0.030, x_foot(z) + 0.062
        yt = y_foot(z) + 0.010
        b.box(R(side * x_out, yt - 0.060, z - 0.040), R(side * x_in, yt, z + 0.040))
    za, zb = zs[0] - 0.040, zs[-1] + 0.040
    # the strip itself: a thin plate along the rail's outer face, in line with the lugs' inner face
    zz = np.linspace(za, zb, 10)
    A = [R(side * (x_foot(z) + 0.030), y_foot(z) + 0.004, z) for z in zz]
    B = [R(side * (x_foot(z) + 0.036), y_foot(z) + 0.004, z) for z in zz]
    C = [R(side * (x_foot(z) + 0.036), y_foot(z) - 0.040, z) for z in zz]
    D = [R(side * (x_foot(z) + 0.030), y_foot(z) - 0.040, z) for z in zz]
    b.sweep(A, B, C, D)


def frame_bars():
    b = Bars(UV_FRAME)
    rail_bars(b)
    bow_bars(b, *Z_RB)
    arch_bars(b)
    windscreen_bars(b)
    hinge_bars(b)
    return b


# ---------------------------------------------------------------- the airframe

def cut_frame(bones, already):
    """removes the model's own frame (CUT_BOXES) from the two hull pieces: every face wholly inside a box. The first run must
    find EXPECT_CUT faces; returns the faces removed"""
    gone = {}
    box = np.array(CUT_BOXES, float)
    lo, hi = box[:, :3] - CUT_TOL, box[:, 3:] + CUT_TOL
    for name in (H1, H0):
        pm = next(b for b in bones if b['name'] == name)['poly_mesh']
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        idx = set()
        for k, poly in enumerate(pm['polys']):
            if not poly:
                continue
            V = P[[v[0] for v in poly]]
            vl, vh = V.min(0), V.max(0)
            if np.any(np.all(vl >= lo, axis=1) & np.all(vh <= hi, axis=1)):
                idx.add(k)
        gone[name] = idx
    n = sum(len(v) for v in gone.values())
    if n != EXPECT_CUT and not already and n:
        raise SystemExit(f'the frame cut found {n} faces, expected {EXPECT_CUT}: the airframe changed; re-read CUT_BOXES (nothing was written)')
    for name, idx in gone.items():
        pm = next(b for b in bones if b['name'] == name)['poly_mesh']
        pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in idx]
    return n


def paint_fairing_faces(bones):
    """the dorsal fairing's front faces (z -0.848 for the plates, -0.899 for the side walls) were hidden by the cut hoop and are
    painted in the cockpit's teal or white: given the airframe's grey, so the rear bow meets a plain grey fairing"""
    n = 0
    for name in (H1, H0):
        pm = next(b for b in bones if b['name'] == name)['poly_mesh']
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0 - EYE
        pm['uvs'].append(list(UV_FRAME))
        k = len(pm['uvs']) - 1
        for poly in pm['polys']:
            if not poly:
                continue
            V = P[[v[0] for v in poly]]
            for zf in (-0.848, -0.899):
                if np.all(np.abs(V[:, 2] - zf) < 0.003) and np.all(np.abs(V[:, 0]) <= 0.60) and V[:, 1].min() > -0.52 and V[:, 1].max() < 0.14:
                    for v in poly:
                        v[2] = k
                    n += 1
                    break
    return n


def glass_triangles():
    tris = shell_patch(Z_RB[1], Z_ARCH[0], 30)                 # one unbroken shell: rear bow -> front bow (no centre bow)
    tris += windscreen_glass()                                 # the front pane and the two quarter panes
    return [np.array(t) + EYE for t in tris]


def main(argv):
    global UV_FRAME
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    hull = next(b for b in bones if b['name'] == H1)
    # a texel of the model's own airframe colour (62, 77, 89), the frame's colour: the first such swatch in the hull's uvs
    from PIL import Image
    tex = Image.open(TEXTURE).convert('RGBA')
    W_, H_ = tex.size
    pmh = hull['poly_mesh']
    best = None
    for i in range(len(pmh['uvs'])):
        u, v = pmh['uvs'][i]
        px = tex.getpixel((min(W_ - 1, int(u * W_)), min(H_ - 1, int((1 - v) * H_))))
        if px == FRAME_RGB + (255,):
            best = [u, v]
            break
    if best is None:
        raise SystemExit(f'no opaque texel {FRAME_RGB} under the hull uvs: the frame colour needs another source')
    UV_FRAME = best
    bars_bone = next((b for b in bones if b['name'] == BARS_BONE), None)
    if bars_bone is None:
        at = next(i for i, b in enumerate(bones) if b['name'] == H1) + 1
        bars_bone = {'name': BARS_BONE, 'parent': 'hull', 'pivot': list(next(b for b in bones if b['name'] == 'hull')['pivot']),
                     'poly_mesh': {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}}
        bones.insert(at, bars_bone)
    already = bool(bars_bone.get('poly_mesh', {}).get('polys'))
    cut = cut_frame(bones, already)
    painted = paint_fairing_faces(bones)
    bars = frame_bars()
    bars_bone['poly_mesh'] = bars.mesh()
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'yak_38m canopy: the model\'s frame cut out ({cut} faces), {painted} fairing front faces painted grey, {len(bars.polys)} faces of slim frames; '
          f'{len(tris)} glass triangles (x2 windings): one unbroken blown canopy, one flat front pane, two flat quarter panes')
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
