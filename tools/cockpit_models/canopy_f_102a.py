#!/usr/bin/env python3
"""F-102A canopy and windscreen, hand-built for the F-102A.mtb airframe (commit 38e31faf): glass and slim frames on the
real Delta Dagger layout. The model's closed state carries a canopy frame only (no glass anywhere) and a solid wedge of
windscreen fairing, so this builds both transparencies.

The real F-102A (type photos, kit reviews; no dimensioned drawing was reachable, so every number is the model's own line,
not a measurement of the real jet; the pane layout is UNVERIFIED against drawings, see Decision taken in the report):
the early F-102A has the heavily framed V windscreen, two flat bullet-proof front panes meeting at a narrow central post,
with a flat quarter light each side; behind it ONE blown (curved) canopy, hinged at the rear, lifting up and back, no
flat panes on it. The windscreen arch IS the canopy's front bow: one member where the two glasses meet. (Later aircraft
were refitted with a clearer windscreen; the model shows the early heavy frame, so the early layout is built.)

  * canopy glass: one unbroken blown shell from the rear bow (z -0.58, standing against the fuselage's rear wall, the old
    canopy's rear end) to the front bow; its sections run from the rear wall's own outline at the rear bow to a super-ellipse
    over the cockpit, crowned at y +0.46 over the wall's line, and at the front into the windscreen's own section;
  * windscreen: two flat panes (planes y = 0.36 - 0.55 (z - 0.5) - 0.7 |x|, each from the central post out to |x| 0.26) and
    a flat quarter light each side (a steep plane through the pane's outer post and the sill), each a planar polygon whose
    upper edge is the transverse line where its plane meets the shell's end section: that line is the front bow (one
    member, 3 cm deep); the shell stops there;
  * frames (bone wreck_fuselage_1__canopy, closed solids, about 3.5 cm): the two sill rails and the sill strips along the
    quarter lights, the rear bow, the front bow / windscreen arch, the central post, the two outer posts and the base bar.
    No centre rib on the canopy.

Model numbers (eye-relative, eye (0, 2.84744, 4.49618); read off the faces of the untouched model, checks/orig): the
fuselage's rear wall at z -0.55 (a boxy outline: crown y +0.46 over +-0.15, y +0.40 out to x 0.35, then down the shoulder
to the sill, which stands at y -0.21 there and falls to -0.40 at z +0.5; the old roof strip, solid, ends in this wall), the
model's frame bars at y +0.40..0.47 (z -0.55..+0.5) and its windscreen wedge falling from y +0.35 (z 0.7) to the nose deck
at y -0.35 (z 1.9). The model's own frame, roof strip and wedge are cut out by CUT_BOX (everything wholly inside it, z -0.58
forward); the exterior skin it took with it is put back by the restore list (make_restore.py, then restore_exterior.py).

CANOPY (eye-relative sections, the right half from the sill up over the top, windscreen included) is what the checks in
work/elite_expansion_2/checks read. Frames: vehicle-local blocks (x left, y up, z forward); meshes are written in model
pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script f_102a.py then appends the sight glass to CanopyGlass.
Usage: python tools/cockpit_models/canopy_f_102a.py [--dry-run]"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/f_102a.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/f_102a.json')

BARS_BONE = 'wreck_fuselage_1__canopy'    # (a new bone beside the cockpit bone: the frames break away with the forward fuselage)
AFTER_BONE = 'wreck_fuselage_1__hull'
HULLS = ('wreck_fuselage_0__hull', 'wreck_fuselage_1__hull')
FRAME_RGB = (104, 108, 112)               # the frames: a texel of the model's own grey nearest this (set in main)
UV_FRAME = [0.5, 0.5]

EZ, EY = 4.49618, 2.84744                 # the pilot's eye (data)


def zr(z):                                # eye-relative z -> vehicle z
    return EZ + z


def yr(y):
    return EY + y


# ---- the old model's frame, interior and tub: every face of the two fuselage pieces lying wholly in this box (eye-relative
# lo, hi, with a 5 mm tolerance) goes; the exterior skin among them is put back by the restore list (missing.py)
CUT_BOX = ((-0.70, -1.12, -0.58), (0.70, 0.52, 1.95))

# ---- the canopy shell (eye-relative z)
Z_RB = (-0.58, -0.54)                     # rear bow (4 cm), standing against the fuselage's rear wall (the old canopy's rear end, z -0.55)
Z_START = Z_RB[1]
SPINE = [(0.44, -0.22), (0.40, -0.10), (0.36, 0.0), (0.35, 0.10), (0.35, 0.36), (0.31, 0.41), (0.16, 0.44), (0.0, 0.46)]   # the rear wall's outline at the rear bow (the model's roof and shoulder boxes end at z -0.55)
NEXP = 2.3                                # the shell's super-ellipse exponent over the cockpit
CROWN = [(-0.58, 0.46), (0.0, 0.44), (0.3, 0.40), (0.5, 0.36)]
FOOT_X = [(-0.58, 0.45), (0.0, 0.44), (0.5, 0.40), (1.2, 0.36), (2.0, 0.34)]
FOOT_Y_Z0, FOOT_Y_S = -0.215, -0.18       # sill ridge: y = FOOT_Y_Z0 + FOOT_Y_S * (z + 0.58)
BLEND = 0.55                              # the sections reach the full ellipse this far ahead of the rear bow


def foot_x(z):
    return float(np.interp(z, [p[0] for p in FOOT_X], [p[1] for p in FOOT_X]))


def foot_y(z):
    return FOOT_Y_Z0 + FOOT_Y_S * (z - Z_RB[0])


def crown(z):
    return float(np.interp(z, [c[0] for c in CROWN], [c[1] for c in CROWN]))




# ---- the windscreen's planes (defined first: the canopy's front sections are cut to them)
Z_BOW = 0.44                              # the shell ends here (a transverse section); the windscreen's top edge stands 3 cm ahead
Z0 = Z_BOW + 0.03
Y_T, Z_T = 0.36, 0.50                     # the ridge line passes (0, 0.36, 0.5) ...
RAKE = 0.55                               # ... falling 0.55 per block forward (29 degrees)
M_PANE = 0.70                             # each pane falls 0.7 per block of |x| out from the post
X_O = 0.26                                # the panes end at the outer posts
POST_W = 0.020                            # central post half width
ZB0, ZB1 = 1.79, 0.33                     # the base line on the nose deck: z = 1.79 - 0.33 |x|
N1, N2 = 14, 10                           # shell columns: sill to the outer post (N1), on to the crown (N2 more)
NS = N1 + N2


def y_pane(x, z):
    """the pane's plane: y = 0.36 - 0.55 (z - 0.5) - 0.7 |x| (flat)"""
    return Y_T - RAKE * (z - Z_T) - M_PANE * abs(x)


_xf, _yf = foot_x(Z0), foot_y(Z0)
_yo = y_pane(X_O, Z0)
SQ = (_yo - _yf) / (_xf - X_O)            # the quarter light's plane: through the outer post and the sill at Z0, falling SQ per |x|
WS_POLY = ([(_xf + (X_O - _xf) * k / (N1 - 1), _yf + (_yo - _yf) * k / (N1 - 1)) for k in range(N1)] +
           [(X_O * (1 - k / N2), _yo + (y_pane(0, Z0) - _yo) * k / N2) for k in range(1, N2 + 1)])   # the windscreen's section at Z0
U_O = N1 - 1                              # the column on the outer post
FRONT = (-0.10, Z_BOW)                    # the shell's sections blend into the windscreen's section over this range


def y_quarter(x, z):
    """the quarter light's plane (flat): through the outer post, falling SQ per block of |x| beyond it"""
    return y_pane(X_O, z) - SQ * (abs(x) - X_O)



def _spine_pts():
    p = np.array(SPINE, float)
    d = np.r_[0, np.cumsum(np.linalg.norm(np.diff(p, axis=0), axis=1))]
    t = np.linspace(0, d[-1], NS)
    return np.c_[np.interp(t, d, p[:, 0]), np.interp(t, d, p[:, 1])]


SPINE_PTS = _spine_pts()


def section(z, n=NS):
    """right half-section at station z (eye-relative): (x, y) from the foot up to the crown (x = 0)"""
    z = max(z, Z_RB[0])
    x0, y0, yt = foot_x(z), foot_y(z), crown(z)
    us = np.linspace(0, 1, NS)
    ell = np.c_[x0 * (1 - us ** NEXP) ** (1 / NEXP), y0 + (yt - y0) * us]
    ell[-1] = (0.0, yt)
    w = min(1.0, max(0.0, (z - Z_RB[0]) / BLEND))
    w = w * w * (3 - 2 * w)
    sp = SPINE_PTS.copy()
    sp[:, 0] *= x0 / SPINE[0][0]
    sp[:, 1] += y0 - SPINE[0][1]
    pts = sp * (1 - w) + ell * w
    pts[-1] = (0.0, (1 - w) * (SPINE[-1][1] + y0 - SPINE[0][1]) + w * yt)
    wf = min(1.0, max(0.0, (z - FRONT[0]) / (FRONT[1] - FRONT[0])))
    wf = wf * wf * (3 - 2 * wf)
    pts = pts * (1 - wf) + np.array(WS_POLY) * wf
    return [tuple(p) for p in pts]


def shell_y(x, z):
    """height of the shell's upper surface at |x|, z"""
    s = np.array(section(z))
    return float(np.interp(abs(x), s[::-1, 0], s[::-1, 1]))


def y_ws(x, z):
    return y_pane(x, z) if abs(x) <= X_O + 1e-9 else y_quarter(x, z)


def z_base(x):
    return ZB0 - ZB1 * abs(x)


RING = section(Z_BOW)                      # the shell's end ring = the windscreen's section at Z0, foot (u = 0) to crown (u = NS - 1)
WTOP = [(WS_POLY[u][0], WS_POLY[u][1], Z0) for u in range(NS)]      # the windscreen's top edge: all on the planes, one transverse line

def x_out(z):
    return foot_x(z)


def sill_y(z):
    return foot_y(z)


def outline_rel():
    """the canopy's outline the checks read: from the sill line up the glass. The shell's own sections, then, ahead of the
    bow, the windscreen planes (the pane's and the quarter light's) over the nose"""
    out = []
    for z in [-0.62, -0.58, -0.54, -0.4, -0.2, 0.0, 0.2, 0.3, Z_BOW]:
        out.append((z, section(z)))
    for z in [Z_BOW + 0.02, 0.6, 0.8, 1.0, 1.2, 1.4, 1.6, 1.68]:
        xs = np.linspace(0.46, 0.0, 47)
        pts = []
        for x in xs:
            if z > z_base(x) + 1e-9:
                continue
            y = y_ws(x, z)
            if y < foot_y(z) - 1e-9:
                continue
            pts.append((float(x), float(y)))
        sy = foot_y(z)
        if not pts:
            continue
        # the lowest kept point: on the sill if the plane meets it
        x0, y0 = pts[0]
        if y0 > sy + 0.004:
            pts = [(x0, sy)] + pts
        out.append((z, pts))
    return out


CANOPY = outline_rel()                    # eye-relative, as handbuilt_canopy.Shape wants it (the checks read this)


def V(x, y, z):
    """eye-relative point -> vehicle-local"""
    return np.array([x, yr(y), zr(z)], float)


# ---------------------------------------------------------------- glass

def _tri(out, a, b, c):
    if np.linalg.norm(np.cross(b - a, c - a)) > 1e-10:
        out.append([a, b, c])


def shell_glass(nz=26):
    """one unbroken blown shell from the rear bow's front face to the shell's end ring (the front bow stands on it)"""
    out = []
    for side in (1, -1):
        grid = []
        for i in range(nz + 1):
            z = Z_START + (Z_BOW - Z_START) * i / nz
            s = section(z)
            grid.append([V(side * p[0], p[1], z) for p in s])
        for i in range(nz):
            for j in range(NS - 1):
                a, b, c, d = grid[i][j], grid[i][j + 1], grid[i + 1][j + 1], grid[i + 1][j]
                _tri(out, a, b, c)
                _tri(out, a, c, d)
    return out


def _fan(out, poly):
    """a planar polygon (list of 3D points, in order) as a triangle fan from its centroid"""
    c = np.mean(poly, 0)
    for i in range(len(poly)):
        _tri(out, c, poly[i], poly[(i + 1) % len(poly)])


def _top_at(x):
    """the windscreen top edge's point at |x| (on the pane plane), interpolated between shell columns"""
    xs = [w[0] for w in WTOP]
    ys = [w[1] for w in WTOP]
    zs = [w[2] for w in WTOP]
    order = np.argsort(xs)
    xs = np.array(xs)[order]
    return (x, float(np.interp(x, xs, np.array(ys)[order])), float(np.interp(x, xs, np.array(zs)[order])))


def pane_polygon(side):
    """one flat pane: from the central post's edge out to the outer post; the top edge on the planes' cut with the shell's
    end height (the front bow), the base line on the nose deck"""
    xi = POST_W - 0.003
    ti = _top_at(xi)
    top = [(ti[0], y_pane(ti[0], ti[2]), ti[2])]
    for u in range(NS - 2, U_O - 1, -1):
        w = WTOP[u]
        if w[0] > xi:
            top.append((w[0], w[1], w[2]))
    bo = (X_O, y_pane(X_O, z_base(X_O)), z_base(X_O))
    bi = (xi, y_pane(xi, z_base(xi)), z_base(xi))
    pts = top + [bo, bi]
    return [V(side * p[0], p[1], p[2]) for p in pts]


def quarter_polygon(side):
    """the flat quarter light: the outer post, the top edge down to the sill, the straight lower edge back to the post's foot"""
    pts = [WTOP[u] for u in range(U_O, -1, -1)]
    bo = (X_O, y_quarter(X_O, z_base(X_O)), z_base(X_O))
    pts = pts + [bo]
    return [V(side * p[0], p[1], p[2]) for p in pts]


def windscreen_glass():
    out = []
    for s in (1, -1):
        _fan(out, pane_polygon(s))
        _fan(out, quarter_polygon(s))
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
        """a bar between four polylines A (top, near), B (top, far), C (bottom, far), D (bottom, near)"""
        for i in range(len(A) - 1):
            ctr = np.mean([L[k] for L in (A, B, C, D) for k in (i, i + 1)], 0)
            for p, q in ((A, B), (D, C), (A, D), (B, C)):
                self.quad([p[i], p[i + 1], q[i + 1], q[i]], ctr)
        self.quad([A[0], B[0], C[0], D[0]], np.mean([L[1] for L in (A, B, C, D)], 0))
        self.quad([A[-1], B[-1], C[-1], D[-1]], np.mean([L[-2] for L in (A, B, C, D)], 0))


def bar(b, pts, nrm, hw, t_out, t_in):
    """a slim member along the polyline [pts], standing on a surface with outward normal [nrm] (one per point): hw each
    side of the line, t_out proud of the glass, t_in below it"""
    pts = [np.array(p, float) for p in pts]
    nrm = [np.array(n, float) / np.linalg.norm(n) for n in nrm]
    A, B, C, D = [], [], [], []
    for k, (p, n) in enumerate(zip(pts, nrm)):
        t = pts[min(k + 1, len(pts) - 1)] - pts[max(k - 1, 0)]
        t = t / np.linalg.norm(t)
        l = np.cross(t, n)
        l /= np.linalg.norm(l)
        A.append(p + l * hw + n * t_out)
        B.append(p - l * hw + n * t_out)
        C.append(p - l * hw - n * t_in)
        D.append(p + l * hw - n * t_in)
    b.sweep(A, B, C, D)


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


def bar(b, pts, nrm, hw, t_out, t_in):
    """a slim member along the polyline [pts], standing on a surface with outward normal [nrm] (one per point): hw each
    side of the line, t_out proud of the glass, t_in below it"""
    pts = [np.array(p, float) for p in pts]
    nrm = [np.array(n, float) / np.linalg.norm(n) for n in nrm]
    A, B, C, D = [], [], [], []
    for k, (p, n) in enumerate(zip(pts, nrm)):
        t = pts[min(k + 1, len(pts) - 1)] - pts[max(k - 1, 0)]
        t = t / np.linalg.norm(t)
        l = np.cross(t, n)
        l /= np.linalg.norm(l)
        A.append(p + l * hw + n * t_out)
        B.append(p - l * hw + n * t_out)
        C.append(p - l * hw - n * t_in)
        D.append(p + l * hw - n * t_in)
    b.sweep(A, B, C, D)


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
HW = 0.018


def n_pane(side=1):
    n = np.array([side * M_PANE, 1.0, RAKE])
    return n / np.linalg.norm(n)


def n_quarter(side=1):
    n = np.array([side * SQ, 1.0, RAKE])
    return n / np.linalg.norm(n)


def rear_bow(b):
    """the rear bow over the shell at Z_RB, standing against the fuselage's rear wall: a ring member across the crown, down both sides"""
    zc = 0.5 * (Z_RB[0] + Z_RB[1])
    sec = section(zc)
    nrm = out_normals(sec)
    for side in (1, -1):
        def line(z, rad):
            return [V(side * (p[0] + n[0] * rad), p[1] + n[1] * rad, z) for p, n in zip(sec, nrm)]
        A, B, C, D = line(Z_RB[0], T_OUT), line(Z_RB[1], T_OUT), line(Z_RB[1], -T_IN), line(Z_RB[0], -T_IN)
        for Ln in (A, B, C, D):
            Ln[0] = Ln[0] + np.array([0, -0.012, 0])        # the foot goes into the rail
        b.sweep(A, B, C, D)


def arch_bars(b):
    """the front bow = windscreen arch: ONE member from the shell's end ring (its rear face) to the windscreen's top edge (its
    front face), across the crown and down both sides to the sill; each glass stops on it"""
    nrm = out_normals(RING)
    for side in (1, -1):
        def line(pts, rad):
            return [V(side * (p[0] + n[0] * rad), p[1] + n[1] * rad, p[2]) for p, n in zip(pts, nrm)]
        ring3 = [(p[0], p[1], Z_BOW) for p in RING]
        A, B, C, D = line(ring3, T_OUT), line(WTOP, T_OUT), line(WTOP, -T_IN), line(ring3, -T_IN)
        for Ln in (A, B, C, D):
            Ln[0] = Ln[0] + np.array([0, -0.012, 0])        # the foot goes into the sill strip
        b.sweep(A[::-1], B[::-1], C[::-1], D[::-1])


def rail_bars(b):
    """the sill rails: 6.5 cm wide, top at the glass foot's line, buried 4.5 cm into the fuselage's sill ridge"""
    z_end = WTOP[0][2]
    zs = np.linspace(Z_RB[0], z_end, 14)
    for side in (1, -1):
        def line(dx, dy):
            return [V(side * (foot_x(min(z, Z_BOW)) + dx), foot_y(z) + dy, z) for z in zs]
        A, B = line(-0.035, 0.0), line(0.030, 0.0)
        C, D = line(0.030, -0.045), line(-0.035, -0.045)
        b.sweep(A, B, C, D)


def windscreen_bars(b):
    # central post: a narrow bar along the ridge line, from the top edge down to the base line
    zt = _top_at(0.0)[2]
    zs = np.linspace(zt - 0.015, z_base(0.0) + 0.04, 9)
    n = np.array([0.0, 1.0, RAKE])
    n /= np.linalg.norm(n)
    pts = [V(0, y_pane(0, z), z) for z in zs]
    bar(b, pts, [n] * len(pts), POST_W, 0.014, 0.030)
    # outer posts: along the line where the pane meets the quarter light, from the top edge to the base line
    zt_o = WTOP[U_O][2]
    zs = np.linspace(zt_o - 0.01, z_base(X_O) + 0.03, 9)
    for side in (1, -1):
        pts = [V(side * X_O, y_pane(X_O, z), z) for z in zs]
        nm = n_pane(side) + n_quarter(side)
        bar(b, pts, [nm] * len(pts), HW, T_OUT, T_IN)
    for side in (1, -1):
        # base bar along the pane's base line on the nose deck
        pts = [V(side * x, y_pane(x, z_base(x)), z_base(x)) for x in (0.0, X_O)]
        bar(b, pts, [n_pane(side)] * 2, 0.03, 0.020, 0.045)
        # the sill strip along the quarter light's lower edge: from the shell's sill forward-down to the post's foot
        a0 = WTOP[0]
        p1 = (X_O, y_quarter(X_O, z_base(X_O)), z_base(X_O))
        line = [V(side * (a0[0] + (p1[0] - a0[0]) * k), a0[1] + (p1[1] - a0[1]) * k, a0[2] + (p1[2] - a0[2]) * k)
                for k in np.linspace(0, 1, 7)]
        bar(b, line, [n_quarter(side)] * len(line), 0.030, 0.014, 0.045)


def frame_bars():
    b = Bars(UV_FRAME)
    rail_bars(b)
    rear_bow(b)
    arch_bars(b)
    windscreen_bars(b)
    return b


# ---------------------------------------------------------------- the airframe

def cut_box(bones):
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
    tris = shell_glass()                                       # one unbroken shell: rear bow -> front bow
    tris += windscreen_glass()                                 # the windscreen: two flat panes, two flat quarter lights
    return [np.array(t) for t in tris]


def main(argv):
    dry = '--dry-run' in argv
    global UV_FRAME
    import cockpit_lib as L
    UV_FRAME = L.Swatches(L.texture_path('f_102a')).uv(FRAME_RGB)
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    bars_bone = next((b for b in bones if b['name'] == BARS_BONE), None)
    if bars_bone is None:
        hull = next(b for b in bones if b['name'] == 'hull')
        bars_bone = {'name': BARS_BONE, 'parent': 'hull', 'pivot': list(hull['pivot'])}
        at = next(i for i, b in enumerate(bones) if b['name'] == AFTER_BONE) + 1
        bones.insert(at, bars_bone)
    cut = cut_box(bones)
    bars = frame_bars()
    bars_bone['poly_mesh'] = bars.mesh()
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f"f_102a canopy: the model's frame and tub cut out ({cut} faces), {len(bars.polys)} faces of slim frames; "
          f'{len(tris)} glass triangles (x2 windings): one unbroken blown canopy, two flat panes and two flat quarter lights')
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

