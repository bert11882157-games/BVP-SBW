#!/usr/bin/env python3
"""Su-15TM canopy and windscreen, hand-built for the Su-15T.mtb airframe (commit 8e78eb39): glass and slim frames on the
real Su-15 layout, replacing the model's own frame-only canopy.

The real Su-15 / Su-15TM (the weaponsandwarfare.com Su-15 entry and the hyperscale / Trumpeter kit descriptions; no
dimensioned drawing was found, so every number below is the model's own line, not a measurement of the real jet): a
fixed windscreen whose centre is ONE elliptical, optically flat, bullet-resistant (silicate glass) front pane in a heavy
metal frame, steeply raked, with a small CURVED triangular Perspex side light each side of it; behind it one blown
(curved) Perspex canopy that slides aft on rails and can be jettisoned. The canopy has no posts and no centre bow (one
unbroken transparency; the Su-9's periscope is not on the Su-15); the model's centre roof bow along the crown is
therefore NOT kept. The windscreen's arch IS the canopy's front bow: one member where the two glasses meet.

  * canopy glass: one unbroken blown shell (a super-ellipse section from the sill rail up to a crown) from the rear bow,
    which stands against the spine fairing's front face (z 2.032), to the front bow = windscreen arch (z 3.15 .. 3.195);
  * windscreen: the centre frame is a metal plate (a trapezoid narrowing toward the arch, |x| 0.12 at the arch to 0.30 at the
    base bar) with an elliptical hole; the flat pane fills the hole, in one plane sloping from the arch down to the base bar
    (the pane is the only flat glass); each side light is one triangle: the arch (its rear edge), the plate's slanting
    side (its front edge) and the sill strip, a curved cone surface. Every piece of glass stops at its member's edge;
  * frames (bone wreck_fuselage_1__canopy, closed solids, 4.5 to 6.5 cm): the two sill rails and the sill strips along the
    side lights, the rear bow, the front bow / windscreen arch, the centre plate and the base bar. No posts, no centre rib.

Model numbers (NOTES.md section 5 of work/elite_expansion_2/su15, rechecked on the faces): the sill skin's top is y 2.29
(x 0.317 .. 0.499); the spine fairing's front face z 2.032 with its outline (0.499, 2.38) (0.363, 2.471) (0.272, 2.52 .. 2.61)
(0.18, 2.65) (0.136, 2.698); the windscreen base on the nose deck at z 3.755, y 2.37; the model's frame (rails, front bow,
windscreen ribs, centre roof bow: runs of the nose-section hull, CUT_RUNS) is cut out by face runs; everything under the sill
and the nose deck stays.

CANOPY (eye-relative sections, the right half from the sill up over the top, windscreen included) is what the checks in
work/elite_expansion_2/checks read. Frames: vehicle-local blocks (x left, y up, z forward); meshes are written in model
pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script su_15tm.py then appends the sight glass to CanopyGlass.
Usage: python tools/cockpit_models/canopy_su_15tm.py [--dry-run]"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/su_15tm.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/su_15tm.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/su_15tm.json')

BARS_BONE = 'wreck_fuselage_1__canopy'    # (made after the hull piece when the airframe has none: the frames go with the section)
H1 = 'wreck_fuselage_1__hull'
UV_FRAME = None                           # a texel of the model's own dark grey, found in main()

# ---- the model's frame, cut out: (bone, first face, last face, box lo, box hi). The box is checked before a run is cut.
# (the rail's rear heel, faces 0-5 and 231-236, stays: it is the fairing's side skin at the sill)
CUT_RUNS = [(H1, 6, 51, (0.0, 2.244, 2.032), (0.408, 2.426, 3.755)),               # right side rail, front bow half, windscreen base rail
            (H1, 154, 230, (-0.045, 2.29, 2.032), (0.363, 2.788, 3.71)),           # centre roof bow, right hoop and windscreen rib
            (H1, 237, 282, (-0.408, 2.244, 2.032), (0.0, 2.426, 3.755)),           # left
            (H1, 385, 431, (-0.363, 2.29, 2.894), (-0.045, 2.743, 3.71))]

# ---- the canopy
Z_FAIR = 2.032                            # the spine fairing's front face
Z_RB = (2.040, 2.080)                     # rear bow (4 cm, 8 mm clear of the fairing face)
Z_ARCH = (3.150, 3.195)                   # front bow = windscreen arch
FOOT_X, FOOT_Y = 0.41, 2.29               # the glass foot: the middle of the sill skin's top
NEXP0, NEXP1 = 2.0, 2.4                   # the sections' super-ellipse exponent, rear to front
CROWN = [(2.040, 2.715), (2.30, 2.765), (2.60, 2.775), (2.90, 2.748), (3.05, 2.722), (3.15, 2.695)]   # height over the centre line
Z_END = Z_ARCH[0]


def crown(z):
    return float(np.interp(z, [c[0] for c in CROWN], [c[1] for c in CROWN]))


def section(z, n=24):
    """right half-section at station z: absolute (x, y) from the foot up to the crown (x = 0)"""
    z = min(max(z, Z_RB[0]), Z_END)
    yt = crown(z)
    e = float(np.interp(z, [Z_RB[0], Z_END], [NEXP0, NEXP1]))
    us = np.linspace(0, 1, n)
    pts = [(FOOT_X * (1 - u ** e) ** (1 / e), FOOT_Y + (yt - FOOT_Y) * u) for u in us]
    pts[-1] = (0.0, yt)
    return pts


def sec_y(x, z=Z_END):
    p = np.array(section(z, 60))[::-1]            # from the crown out
    return float(np.interp(x, p[:, 0], p[:, 1]))


# ---- the windscreen: one flat elliptical pane in a metal plate, a triangular curved side light each side
ZF = Z_ARCH[1]                            # the arch's front face
Z_B0, Z_B1 = 3.745, 3.790                 # the base bar on the nose deck
HW0, HWB = 0.12, 0.30                     # the plate's half width at the arch and at the base bar
SLOPE = 0.574                             # the pane's plane drops 0.574 per block forward
YC0 = sec_y(HW0)                          # the plane's height at the arch's front face: the arch curve's height at the plate's corner
EC = (0.0, 3.477)                         # the ellipse's centre (x, z)
EA, EB = 0.175, 0.235                     # the pane's half width and half length (blocks along z)
T_OUT, T_IN = 0.012, 0.030


def yc(z):                                # the pane's plane (flat: independent of x)
    return YC0 - SLOPE * (z - ZF)


def hw(z):
    return HW0 + (HWB - HW0) * (z - ZF) / (Z_B0 - ZF)


def x_out(z):                             # the side light's outer edge: the sill strip's line
    return FOOT_X + (HWB - FOOT_X) * (z - ZF) / (Z_B0 - ZF)


def sill_y(z):                            # the sill strip's line rises to meet the pane's plane at the base bar
    return FOOT_Y + (yc(Z_B0) - FOOT_Y) * (z - ZF) / (Z_B0 - ZF)


def outline_rel(eye):
    """the canopy's outline the checks read: from the sill line up the glass; the windscreen stations run from the
    side light's sill edge over the side light and the plate to the centre line"""
    stations = [2.0, 2.040, 2.08, 2.30, 2.60, 2.90, 3.05, 3.15, 3.195, 3.30, 3.45, 3.60, 3.745]
    out = []
    for z in stations:
        if z <= Z_END + 1e-9:
            pts = section(max(z, Z_RB[0]))
        else:
            h = hw(z)
            pts = [(x_out(z), sill_y(z)), (h, yc(z)), (0.0, yc(z))]
        out.append((z - eye[2], [(x, y - eye[1]) for x, y in pts]))
    return out


def _eye():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        s = d['Seats'][0]
        return np.array(d['Attachments'][s['CameraPos']['EyeAttachment']]['Position'], float)
    except (OSError, KeyError):
        return np.array([0.0, 2.5842, 2.6899])


CANOPY = outline_rel(_eye())              # eye-relative, as handbuilt_canopy.Shape wants it (the checks read this)


def resample(pts, k):
    p = np.array(pts, float)
    d = np.r_[0, np.cumsum(np.linalg.norm(np.diff(p, axis=0), axis=1))]
    t = np.linspace(0, d[-1], k)
    return np.c_[np.interp(t, d, p[:, 0]), np.interp(t, d, p[:, 1])]


# ---------------------------------------------------------------- glass

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


def arch_curve(k=14):
    """the arch's front-face curve from the plate's corner (|x| = HW0) out to the sill: the side light's rear edge"""
    xs = np.linspace(HW0, FOOT_X, 300)
    ys = [sec_y(x) if x < FOOT_X - 1e-9 else FOOT_Y for x in xs]
    return resample(list(zip(xs, ys)), k + 1)

def ellipse_pt(phi):
    d = np.array([math.cos(phi), math.sin(phi)])
    r = 1.0 / math.sqrt((d[0] / EA) ** 2 + (d[1] / EB) ** 2)
    return EC[0] + r * d[0], EC[1] + r * d[1]


def plate_poly():
    return [(-HW0, ZF), (HW0, ZF), (HWB, Z_B0), (-HWB, Z_B0)]


def plate_outer(phi):
    """where the ray from the ellipse's centre at angle phi leaves the plate's trapezoid"""
    d = np.array([math.cos(phi), math.sin(phi)])
    c = np.array(EC)
    poly = plate_poly()
    best = None
    for i in range(4):
        p, q = np.array(poly[i]), np.array(poly[(i + 1) % 4])
        e = q - p
        M = np.array([[d[0], -e[0]], [d[1], -e[1]]])
        if abs(np.linalg.det(M)) < 1e-12:
            continue
        t, s = np.linalg.solve(M, p - c)
        if t > 0 and -1e-9 <= s <= 1 + 1e-9 and (best is None or t < best):
            best = t
    return c[0] + best * d[0], c[1] + best * d[1]


def plate_angles(n=44):
    ph = [2 * math.pi * k / n for k in range(n)]
    for x, z in plate_poly():
        ph.append(math.atan2(z - EC[1], x - EC[0]) % (2 * math.pi))
    return sorted(set(round(p, 9) for p in ph))


def windscreen_glass(nz=8):
    out = []
    ph = plate_angles()
    for a, b in zip(ph, ph[1:] + [ph[0] + 2 * math.pi]):          # the pane: a fan on the ellipse, all in one plane
        (xa, za), (xb, zb) = ellipse_pt(a), ellipse_pt(b)
        out.append([np.array([EC[0], yc(EC[1]), EC[1]]), np.array([xa, yc(za), za]), np.array([xb, yc(zb), zb])])
    curve = arch_curve()                                            # each side light: a cone from the sill/plane corner B1 to the arch curve
    for side in (1, -1):
        B1 = np.array([side * HWB, yc(Z_B0), Z_B0])
        for k in range(len(curve) - 1):
            for t0, t1 in zip(np.linspace(0, 1, nz + 1)[:-1], np.linspace(0, 1, nz + 1)[1:]):
                def P(kk, t):
                    x, y = curve[kk]
                    return np.array([side * x, y, ZF]) * (1 - t) + B1 * t
                a, b, c, d = P(k, t0), P(k + 1, t0), P(k + 1, t1), P(k, t1)
                for tri in ((a, b, c), (a, c, d)):
                    if np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0])) > 1e-10:
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
            Ln[0] = Ln[0] + np.array([0, -0.045, 0])        # the foot goes into the sill skin
        b.sweep(A, B, C, D)


def arch_bars(b):
    """the windscreen arch = canopy front bow: one member, from the shell's end edge on its rear face to the windscreen's top
    edge on its front face (the curve of the side lights' rear edge, the plate's top for |x| < HW0), so each glass stops
    exactly on it"""
    zr, zf = Z_ARCH
    sec = section(zr)
    for side in (1, -1):
        A = [np.array([side * p[0], p[1] + T_OUT, zr]) for p in sec]
        Bf = []
        for x, y in sec:
            Bf.append(np.array([side * x, (YC0 if x <= HW0 else y) + T_OUT, zf]))
        C = [p - np.array([0, T_IN * 1.15, 0]) for p in Bf]
        D = [p - np.array([0, T_IN * 1.15, 0]) for p in A]
        for Ln in (A, Bf, C, D):
            Ln[0] = Ln[0] + np.array([0, -0.045, 0])
        b.sweep(A, Bf, C, D)


def rail_bars(b):
    """the sill rails: 6.5 cm wide, top at the glass foot's line, buried 4.5 cm into the sill skin"""
    zs = np.linspace(Z_RB[0], ZF, 8)
    for side in (1, -1):
        def line(dx, dy):
            return [np.array([side * (FOOT_X + dx), FOOT_Y + dy, z]) for z in zs]
        A, B = line(-0.035, 0.0), line(0.030, 0.0)
        C, D = line(0.030, -0.045), line(-0.035, -0.045)
        b.sweep(A, B, C, D)


def windscreen_bars(b):
    ph = plate_angles()
    ph2 = ph + [ph[0] + 2 * math.pi]
    # the centre plate: a trapezoid with the ellipse's hole, 1.2 cm proud of the glass plane and 3 cm deep
    for a, c in zip(ph2[:-1], ph2[1:]):
        e0, e1 = ellipse_pt(a), ellipse_pt(c)
        o0, o1 = plate_outer(a), plate_outer(c)

        def pt(q, off):
            return np.array([q[0], yc(q[1]) + off, q[1]])
        top = [pt(e0, T_OUT), pt(e1, T_OUT), pt(o1, T_OUT), pt(o0, T_OUT)]
        bot = [pt(e0, -0.030), pt(e1, -0.030), pt(o1, -0.030), pt(o0, -0.030)]
        mid = np.mean(bot, 0)
        b.quad(top, mid)
        b.quad(bot[::-1], mid + np.array([0, 0.05, 0]))
        b.quad([top[0], top[1], bot[1], bot[0]], pt(o0, 0.0) * 0.5 + pt(o1, 0.0) * 0.5)     # the hole's wall (faces the hole)
        ctr = np.array([EC[0], yc(EC[1]), EC[1]])
        b.quad([top[3], top[2], bot[2], bot[3]], ctr)                                       # the plate's outer wall
    x0 = HWB + 0.02                                       # the base bar on the deck: across the pane's frame and the side lights' tips
    A = [np.array([-x0, yc(Z_B0) + T_OUT, Z_B0]), np.array([x0, yc(Z_B0) + T_OUT, Z_B0])]
    B = [np.array([-x0, yc(Z_B1) + T_OUT, Z_B1]), np.array([x0, yc(Z_B1) + T_OUT, Z_B1])]
    C = [np.array([-x0, yc(Z_B1) - 0.055, Z_B1]), np.array([x0, yc(Z_B1) - 0.055, Z_B1])]
    D = [np.array([-x0, yc(Z_B0) - 0.055, Z_B0]), np.array([x0, yc(Z_B0) - 0.055, Z_B0])]
    b.sweep(A, B, C, D)
    ctr = np.array([0, yc(0.5 * (Z_B0 + Z_B1)) - 0.02, 0.5 * (Z_B0 + Z_B1)])
    for i in (0, 1):
        b.cap([A[i], B[i], C[i], D[i]], ctr)
    zs = np.linspace(ZF, Z_B0, 8)                         # the sill strips along the side lights: 6.5 cm, down into the skin
    for side in (1, -1):
        def line(dx, dy):
            return [np.array([side * (x_out(z) + dx), sill_y(z) + dy, z]) for z in zs]
        A_, B_ = line(-0.035, 0.0), line(0.030, 0.0)
        C_ = [np.array([p[0], 2.245, p[2]]) for p in B_]
        D_ = [np.array([p[0], 2.245, p[2]]) for p in A_]
        b.sweep(A_, B_, C_, D_)


def frame_bars():
    b = Bars(UV_FRAME)
    rail_bars(b)
    bow_bars(b, *Z_RB)
    arch_bars(b)
    windscreen_bars(b)
    return b


# ---------------------------------------------------------------- the airframe

def find_run(P, polys, a, c, lo, hi, tol=0.012):
    """the first face of the run of c - a + 1 faces near index a whose bounding box is (lo, hi), or None"""
    n = c - a + 1
    mins = np.array([P[[v[0] for v in p]].min(0) if p else np.full(3, 9e9) for p in polys])
    maxs = np.array([P[[v[0] for v in p]].max(0) if p else np.full(3, -9e9) for p in polys])
    for s in sorted(range(max(0, a - 700), min(len(polys) - n, a + 700) + 1), key=lambda s: abs(s - a)):
        if np.all(np.abs(mins[s:s + n].min(0) - lo) < tol) and np.all(np.abs(maxs[s:s + n].max(0) - hi) < tol):
            return s
    return None


def cut_frame(bones, already):
    """removes the model's own frame (CUT_RUNS) from the hull piece. A run that is not found stops the script unless an
    earlier run made the cut (the bars' bone holds faces); returns the faces removed"""
    gone = {}
    for name, a, c, lo, hi in CUT_RUNS:
        hull = next(b for b in bones if b['name'] == name)
        pm = hull['poly_mesh']
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        s = find_run(P, pm['polys'], a, c, lo, hi)
        if s is None:
            if already:
                continue
            raise SystemExit(f'{name}: the frame run {a}-{c} (box {lo}, {hi}) is not in the airframe: the face list changed; '
                             're-read CUT_RUNS (nothing was written)')
        gone.setdefault(name, set()).update(range(s, s + c - a + 1))
    for hull in bones:
        if hull['name'] in gone:
            pm = hull['poly_mesh']
            pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in gone[hull['name']]]
    return sum(len(v) for v in gone.values())


def glass_triangles():
    tris = shell_patch(Z_RB[1], Z_ARCH[0], 30)                 # one unbroken shell: rear bow -> front bow (no centre bow)
    tris += windscreen_glass()                                 # the windscreen: one flat elliptical pane, two curved triangular side lights
    return [np.array(t) for t in tris]


def main(argv):
    global UV_FRAME
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    hull = next(b for b in bones if b['name'] == H1)
    # a texel of the model's own opaque dark grey (26, 26, 26), the frame's colour: the first such swatch in the hull's uvs
    from PIL import Image
    tex = Image.open(os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/su_15tm.png')).convert('RGBA')
    W_, H_ = tex.size
    pmh = hull['poly_mesh']
    best = None
    for i in range(len(pmh['uvs'])):
        u, v = pmh['uvs'][i]
        px = tex.getpixel((min(W_ - 1, int(u * W_)), min(H_ - 1, int((1 - v) * H_))))
        if px[3] == 255 and all(20 <= c <= 40 for c in px[:3]) and max(px[:3]) - min(px[:3]) < 4:
            best = [u, v]
            break
    if best is None:
        raise SystemExit('no opaque dark grey (26, 26, 26) texel under the hull uvs: the frame colour needs another source')
    UV_FRAME = best
    bars_bone = next((b for b in bones if b['name'] == BARS_BONE), None)
    if bars_bone is None:
        at = next(i for i, b in enumerate(bones) if b['name'] == H1) + 1
        bars_bone = {'name': BARS_BONE, 'parent': 'hull', 'pivot': list(next(b for b in bones if b['name'] == 'hull')['pivot']),
                     'poly_mesh': {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}}
        bones.insert(at, bars_bone)
    already = bool(bars_bone.get('poly_mesh', {}).get('polys'))
    cut = cut_frame(bones, already)
    bars = frame_bars()
    bars_bone['poly_mesh'] = bars.mesh()
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'su_15tm canopy: the model\'s frame cut out ({cut} faces), {len(bars.polys)} faces of slim frames; '
          f'{len(tris)} glass triangles (x2 windings): one unbroken blown canopy, one flat elliptical pane, two curved side lights')
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
