#!/usr/bin/env python3
"""Lockheed AH-56A Cheyenne canopy, hand-built on the AH-56A.mtb airframe's own frame lines: flat panes in slim frames, one raised crew
section over the pilot behind a lower one over the gunner, as on the real Cheyenne (two separate glazed sections, tandem, with a fixed
fairing between them).

Measured off the untouched model (checks/orig/ah_56a.geo.json and the source elements, ah56/cutlist.py; vehicle blocks, x to the left,
y up, z forward): the sill's top edge stands at y 3.23 from z 5.05 to 8.15 (its outer edge at |x| 0.905), the old frames were a skeleton
of 4 to 6 px bars: roof rails at |x| 0.32 .. 0.39 (y 4.39 over the pilot, falling over the gunner to 3.65 at the windscreen's top bow,
z 8.54), posts leaning in and forward from the sill (centres |x| 0.81 at y 3.4, 0.61 at the shoulder y 4.14 .. 4.27) at z 5.3 / 6.4 / 6.95
(feet), a front post at z 8.2, a header across at z 8.54 and centre bars at |x| 0.355 running down to the nose deck (y 2.975) at z 9.06.
All of that is cut out (CUT_RANGES, the faces of the elements found by ah56/gen_cut.py: the window frames, the posts, the blocks that
stand on the sill, both seat groups, the old panels and consoles, the old sight bits; the sills, the shell, the floors, the nose block,
the engine intakes and the spine stay).

The new canopy is ONE convex solid (an intersection of half-spaces) and every glass or skin piece is a face of it, so every pane is one
exact plane and neighbouring panes share their edges:
  * side plane (both sides): x = 0.875 - 0.30 (y - 3.23), the posts' lean;
  * shoulder facets leaning 27 degrees from the roof rails out to the side plane, per roof segment;
  * roof: pilot roof flat at y 4.39 (z 5.47 .. 6.80), the fairing roof between the crews falling 0.22 per m (z 6.80 .. 7.34), the gunner's
    overhead plane falling 0.52 per m to the windscreen's top bow (z 7.34 .. 8.54, y 3.65);
  * gunner windscreen: two flat panes meeting in a shallow V (the real Cheyenne's centre post), raked from the top bow down to the nose
    deck; a flat corner pane each side from the windscreen's edge back to the sill's front end.
  Glazed: pilot side, pilot shoulder, pilot roof (two panes either side of a centre spine), gunner side, gunner shoulder, gunner overhead,
  gunner corner, gunner windscreen (x2). Skin (airframe olive outside, dark inside): the fixed fairing between the crews (side, shoulder
  and roof), the rear fairing lofted from the pilot's rear bow back onto the spine (sealed to the model's spine block), the sill skirt.
  Frames: round 4 cm members on every crease and post (one member per straight run), the glass stops 4 mm from each member.
  Opening sections: each side pane and shoulder is a hinged section (hinge barrels on the shoulder crease, a latch on the sill rail).

UNVERIFIED against photographs: pane count and sizes, the V post, the fairing between the crews (the layout follows the model's own bars).

CANOPY (eye-relative sections, the right half from the sill up over the top, the lofted rear included) is what the checks in
work/elite_expansion_2/checks read. EYE_P is the pilot's eye as the cockpit script sets it (0.113 lower than the airframe's: the old
roof rail stood at eye height).

Run first (on the airframe's files); the cockpit script ah_56a.py follows.
Usage: python tools/cockpit_models/canopy_ah_56a.py [--dry-run]"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402

VID = 'ah_56a'
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, f'assets/berts_vehicle_pack/custom_geo/{VID}.geo.json')
ASSET = os.path.join(GEN, f'assets/berts_vehicle_pack/sbw/vehicles/{VID}.json')
PANES_JSON = os.path.join(HERE, 'flat_panes', f'{VID}.json')
HULL_BONE = 'hull'
CANOPY_BONE = 'hull__canopy'

EYE_P = np.array([0.0, 4.275, 5.56612])          # the pilot's eye (the cockpit script writes it into the vehicle data)
EYE_G = np.array([0.0, 3.93566, 7.44025])        # the gunner's eye (unchanged)

# faces of the hull piece that the new canopy and cockpits replace (the untouched hull has 2160 faces): the source elements
# 93-114 / 238-259 (window frames, posts, the blocks standing on the sill), 700-717, 720, 724-734, 737-739, 748-760 (both seat
# groups, panels, consoles, sight stands) and the part 7 / 24 sight bits, as face ranges (ah56/gen_cut.py)
CUT_RANGES = [(473, 604), (1156, 1287), (1444, 1623), (1636, 1653), (1696, 1773), (2132, 2159)]
HULL_FACES = 2160

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def unit(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


# ---------------------------------------------------------------- the canopy solid: half-spaces n.p <= d

SILL_Y = 3.24                    # the frames' bottom line at the pilot's rear post (z Z_P0); it falls with the model's sill toward the nose
SILL_SLOPE = 0.12                # ... by this much per m (the model's sill top falls 0.145 per m from y 3.23 at z 5.05 to 2.78 at z 8.15)
DECK_Y = 2.93                    # the lowest the glass goes: the nose deck under the windscreen (the model's deck top is 2.84 .. 2.975)
WALL_X = 0.905                   # the fuselage's outer side
C_IN = np.array([0.0, 3.9, 6.9])  # a point inside the canopy


class Pl:
    def __init__(self, n, d, name, grp):
        n = np.array(n, float)
        ln = np.linalg.norm(n)
        self.n, self.d, self.name, self.grp = n / ln, d / ln, name, grp

    def side(self, p):
        return float(self.n @ p - self.d)


def through(p0, p1, p2, name, grp):
    p0, p1, p2 = (np.array(p, float) for p in (p0, p1, p2))
    n = unit(np.cross(p1 - p0, p2 - p0))
    d = float(n @ p0)
    if n @ C_IN > d:
        n, d = -n, -d
    return Pl(n, d, name, grp)


def mirror_pl(p, name):
    return Pl(p.n * np.array([-1, 1, 1]), p.d, name, p.grp)


Z_P0, Z_P1 = 5.275, 0.17         # the rear plane: z = Z_P0 + Z_P1 (y - 3.24) (raked like the pilot's rear post)
SD_K = 0.25                      # side plane lean: dx per dy above the sill line
SD_X0 = 0.88                     # its |x| on the sill line
SD_Z = SD_K * SILL_SLOPE
ROOF_Y = 4.39


def y_bot(z):
    """the glass's lowest line (the sill line, never under the deck)"""
    return max(SILL_Y - SILL_SLOPE * (z - Z_P0), DECK_Y)


def SDX(y, z):
    """|x| of the side plane at height y, station z"""
    return SD_X0 - SD_K * (y - SILL_Y) - SD_Z * (z - Z_P0)


ZB1, ZB2, ZB3 = 6.80, 7.34, 8.54          # roof breaks: pilot roof / fairing roof / gunner overhead / windscreen top bow
Y_B1 = ROOF_Y
Y_B2 = 4.27
Y_B3 = 3.65
S_G1 = (Y_B1 - Y_B2) / (ZB2 - ZB1)        # fall per m
S_G2 = (Y_B2 - Y_B3) / (ZB3 - ZB2)
RAIL_X = 0.355
CH_K = 0.5                      # shoulder facets: fall per m of x from the rail outward
Z_FOOT = ZB3 + (Y_B3 - DECK_Y) / 1.303                       # windscreen foot z (the windscreen falls 1.303 per m)
V_D = 0.05                      # the V post's apex stands this far forward of the windscreen's edge line
A_PT = np.array([RAIL_X, Y_B3, ZB3])
B_PT = np.array([RAIL_X, DECK_Y, Z_FOOT])
D_PT = np.array([SDX(DECK_Y, 8.30), DECK_Y, 8.30])                  # the sill front end (the corner pane's foot on the side plane)

PLANES = {}


def add(p):
    PLANES[p.name] = p
    return p


add(Pl([1, SD_K, SD_Z], SD_X0 + SD_K * SILL_Y + SD_Z * Z_P0, 'Sd_r', 'sd'))
add(mirror_pl(PLANES['Sd_r'], 'Sd_l'))
add(Pl([0, 1, 0], ROOF_Y, 'roofP', 'roof'))
add(Pl([0, 1, S_G1], Y_B1 + S_G1 * ZB1, 'roofG1', 'roof'))
add(Pl([0, 1, S_G2], Y_B2 + S_G2 * ZB2, 'roofG2', 'roof'))
for nm, slope, zb, yb in (('Cp', 0.0, 0.0, Y_B1), ('Cg1', S_G1, ZB1, Y_B1), ('Cg2', S_G2, ZB2, Y_B2)):
    # y + CH_K (x - RAIL_X) <= roof(z) = yb - slope (z - zb)
    add(Pl([CH_K, 1, slope], yb + slope * zb + CH_K * RAIL_X, nm + '_r', 'ch'))
    add(mirror_pl(PLANES[nm + '_r'], nm + '_l'))
add(through(A_PT, B_PT, D_PT, 'K_r', 'front'))
add(mirror_pl(PLANES['K_r'], 'K_l'))
C0 = np.array([0.0, Y_B3, ZB3 + V_D])
add(through(A_PT, B_PT, C0, 'Wr', 'front'))
add(mirror_pl(PLANES['Wr'], 'Wl'))
add(Pl([0, Z_P1, -1], -Z_P0 + Z_P1 * SILL_Y, 'Rb', 'back'))
_BOT = Pl([0, -1, -SILL_SLOPE], -(SILL_Y + SILL_SLOPE * Z_P0), 'BOT', 'x')
_DECK = Pl([0, -1, 0], -DECK_Y, 'DECK', 'x')
BOTTOM = {'sd': [_BOT, _DECK], 'ch': [_BOT, _DECK], 'roof': [_BOT, _DECK], 'back': [_BOT, _DECK],
          'front': [_DECK, Pl([1, 0, 0], WALL_X, 'WALL_r', 'x'), Pl([-1, 0, 0], WALL_X, 'WALL_l', 'x')]}


def dedupe(poly, eps=1e-7):
    out = []
    for p in poly:
        if not out or np.linalg.norm(p - out[-1]) > eps:
            out.append(p)
    if len(out) > 1 and np.linalg.norm(out[0] - out[-1]) <= eps:
        out.pop()
    return out


def clip_poly(poly, n, d, eps=1e-9):
    out = []
    for i, p in enumerate(poly):
        q = poly[(i + 1) % len(poly)]
        sp, sq = n @ p - d, n @ q - d
        if sp <= eps:
            out.append(p)
        if (sp < -eps and sq > eps) or (sp > eps and sq < -eps):
            out.append(p + (q - p) * (sp / (sp - sq)))
    return dedupe(out)


def base_poly(pl):
    n = pl.n
    ref = Y if abs(n @ Y) < 0.9 else X
    u = unit(np.cross(n, ref))
    v = np.cross(n, u)
    c0 = C_IN + n * (pl.d - n @ C_IN)
    big = 12.0
    return [c0 + big * (-u - v), c0 + big * (u - v), c0 + big * (u + v), c0 + big * (-u + v)]


def facet(name):
    pl = PLANES[name]
    poly = base_poly(pl)
    for other in PLANES.values():
        if other is not pl:
            poly = clip_poly(poly, other.n, other.d)
    for b in BOTTOM.get(pl.grp, []):
        poly = clip_poly(poly, b.n, b.d)
    return poly


FACETS = {nm: facet(nm) for nm in PLANES}


def three(a, b, c):
    return np.linalg.solve(np.array([a.n, b.n, c.n]), np.array([a.d, b.d, c.d]))


S1 = three(PLANES['Sd_r'], PLANES['Cp_r'], PLANES['Rb'])
S2 = three(PLANES['Sd_r'], PLANES['Cp_r'], PLANES['Cg1_r'])
S3 = three(PLANES['Sd_r'], PLANES['Cg1_r'], PLANES['Cg2_r'])
S4 = three(PLANES['Sd_r'], PLANES['K_r'], PLANES['Cg2_r'])
F2 = np.array([SDX(y_bot(6.38), 6.38), y_bot(6.38), 6.38])        # post feet on the sill line
F3 = np.array([SDX(y_bot(6.95), 6.95), y_bot(6.95), 6.95])


def check_convex():
    bad = 0.0
    for nm, poly in FACETS.items():
        for p in poly:
            for pl in PLANES.values():
                if pl.name != nm:
                    bad = max(bad, pl.side(p))
    return bad


def split_poly(poly, p0, p1, n_plane):
    """a convex polygon cut by the line p0-p1 in its plane: (piece behind (smaller z), piece ahead)"""
    sn = unit(np.cross(np.array(p1) - np.array(p0), n_plane))
    d = float(sn @ np.array(p0))
    a = clip_poly(poly, sn, d)
    b = clip_poly(poly, -sn, -d)
    if np.mean([p[2] for p in a]) > np.mean([p[2] for p in b]):
        a, b = b, a
    return a, b


def mirror_poly(poly):
    return [p * np.array([-1, 1, 1]) for p in poly][::-1]


# ---------------------------------------------------------------- the pieces: glass panes and skin panels, one per face (or part of one)
# (name, polygon, kind) kind: 'glass' | 'skin'
def pieces():
    out = []
    sd = FACETS['Sd_r']
    nS = PLANES['Sd_r'].n
    s_pilot, rest = split_poly(sd, F2, S2, nS)
    s_joint, rest2 = split_poly(rest, F3, S3, nS)
    s_gun = rest2
    for s, side in ((1, 'r'), (-1, 'l')):
        f = (lambda p: p) if s > 0 else mirror_poly
        out.append(('pilot side pane', f(s_pilot), 'glass'))
        out.append(('fairing side', f(s_joint), 'skin'))
        out.append(('gunner side pane', f(s_gun), 'glass'))
        out.append(('pilot shoulder pane', f(FACETS['Cp_r']), 'glass'))
        out.append(('fairing shoulder', f(FACETS['Cg1_r']), 'skin'))
        out.append(('gunner shoulder pane', f(FACETS['Cg2_r']), 'glass'))
        out.append(('gunner corner pane', f(FACETS['K_r']), 'glass'))
        out.append(('gunner windscreen pane', f(FACETS['Wr']), 'glass'))
    # the pilot's roof: a centre spine splits it in two flat panes
    rp = FACETS['roofP']
    nR = PLANES['roofP'].n
    right = clip_poly(rp, np.array([-1.0, 0, 0]), 0.0)
    left = clip_poly(rp, np.array([1.0, 0, 0]), 0.0)
    out.append(('pilot roof pane', right, 'glass'))
    out.append(('pilot roof pane', left, 'glass'))
    out.append(('fairing roof', FACETS['roofG1'], 'skin'))
    out.append(('gunner overhead pane', FACETS['roofG2'], 'glass'))
    return out


R_TUBE = 0.020
GLASS_GAP = 0.004
SLAB = 0.012

# ---------------------------------------------------------------- mesh (absolute blocks in, model pixels out)


class Mesh:
    def __init__(self):
        self.faces = []

    def face(self, pts, uv, normal):
        P = np.array(pts, float)
        n = unit(normal)
        nn = np.zeros(3)
        for i in range(len(P)):
            a, b = P[i], P[(i + 1) % len(P)]
            nn += np.array([(a[1] - b[1]) * (a[2] + b[2]), (a[2] - b[2]) * (a[0] + b[0]), (a[0] - b[0]) * (a[1] + b[1])])
        if np.linalg.norm(nn) < 1e-12:
            return
        if nn @ n < 0:
            P = P[::-1]
        if len(P) > 4:
            for k in range(1, len(P) - 1):
                self.faces.append((P[[0, k, k + 1]], n, uv))
        else:
            self.faces.append((P, n, uv))

    def poly_mesh(self):
        Pl_, Nl, Ul, polys = [], [], [], []
        for pts, n, uv in self.faces:
            px = [[float(p[0]) * 16, float(p[1]) * 16, -float(p[2]) * 16] for p in pts]
            npx = np.array([n[0], n[1], -n[2]])
            a, b, c = (np.array(q) for q in px[:3])
            if np.cross(b - a, c - a) @ npx > 0:
                px = px[::-1]
            ni = len(Nl)
            Nl.append([round(float(x), 5) + 0.0 for x in -npx])
            ui = len(Ul)
            Ul.append([round(uv[0], 6), round(uv[1], 6)])
            poly = []
            for p in px:
                poly.append([len(Pl_), ni, ui])
                Pl_.append([round(x, 4) + 0.0 for x in p])
            polys.append(poly)
        return {'normalized_uvs': True, 'positions': Pl_, 'normals': Nl, 'uvs': Ul, 'polys': polys}


def tube(m, a, b, r, uv, seg=8):
    """a round member from a to b (ends run on by r so joints overlap)"""
    a, b = np.array(a, float), np.array(b, float)
    t = unit(b - a)
    a, b = a - t * r, b + t * r
    ref = Y if abs(t @ Y) < 0.9 else X
    u = unit(np.cross(t, ref))
    v = unit(np.cross(t, u))
    ring = lambda p: [p + r * (u * math.cos(2 * math.pi * (j + .5) / seg) + v * math.sin(2 * math.pi * (j + .5) / seg)) for j in range(seg)]
    A_, B_ = ring(a), ring(b)
    for j in range(seg):
        j2 = (j + 1) % seg
        q = [A_[j], A_[j2], B_[j2], B_[j]]
        m.face(q, uv, np.mean(q, 0) - (a + b) / 2)
    m.face(A_, uv, -t)
    m.face(B_, uv, t)


def box(m, centre, axes, half, uv):
    c = np.array(centre, float)
    A_ = [unit(a) for a in axes]
    for k in range(3):
        for s in (-1, 1):
            i, j = [q for q in range(3) if q != k]
            fc = c + A_[k] * half[k] * s
            m.face([fc - A_[i] * half[i] - A_[j] * half[j], fc + A_[i] * half[i] - A_[j] * half[j],
                    fc + A_[i] * half[i] + A_[j] * half[j], fc - A_[i] * half[i] + A_[j] * half[j]], uv, A_[k] * s)


def merge_edges(edges):
    """straight runs: collinear overlapping edges joined into one member"""
    lines = []
    for a, b in edges:
        a, b = np.array(a, float), np.array(b, float)
        Ln = np.linalg.norm(b - a)
        if Ln < 1e-5:
            continue
        d = (b - a) / Ln
        for ln in lines:
            o, dd, iv = ln
            if abs(abs(d @ dd) - 1) < 1e-7 and np.linalg.norm(np.cross(a - o, dd)) < 3e-5:
                t0, t1 = (a - o) @ dd, (b - o) @ dd
                iv.append((min(t0, t1), max(t0, t1)))
                break
        else:
            lines.append([a, d, [(0.0, Ln)]])
    out = []
    for o, dd, iv in lines:
        iv.sort()
        cur = list(iv[0])
        for s, e in iv[1:]:
            if s <= cur[1] + 1e-6:
                cur[1] = max(cur[1], e)
            else:
                out.append((o + dd * cur[0], o + dd * cur[1]))
                cur = [s, e]
        out.append((o + dd * cur[0], o + dd * cur[1]))
    return out


def inset_polygon(poly3, normal, d):
    P = np.array(poly3, float)
    c = P.mean(0)
    n = unit(normal)
    m = len(P)
    lines = []
    for i in range(m):
        a, b = P[i], P[(i + 1) % m]
        e = unit(b - a)
        inward = unit(np.cross(n, e))
        if inward @ (c - a) < 0:
            inward = -inward
        lines.append((a + inward * d, e))
    out = []
    for i in range(m):
        (p0, e0), (p1, e1) = lines[i - 1], lines[i]
        A_ = np.array([e0, -e1]).T
        sol, *_ = np.linalg.lstsq(A_, p1 - p0, rcond=None)
        out.append(p0 + sol[0] * e0)
    return np.array(out)


def plane_normal(poly3):
    P = np.array(poly3, float)
    nn = np.zeros(3)
    for i in range(len(P)):
        a, b = P[i], P[(i + 1) % len(P)]
        nn += np.array([(a[1] - b[1]) * (a[2] + b[2]), (a[2] - b[2]) * (a[0] + b[0]), (a[0] - b[0]) * (a[1] + b[1])])
    return unit(nn)


# ---------------------------------------------------------------- the rear fairing: lofted from the pilot's rear bow onto the spine block
Z_END = 4.40
# right-half profile at the spine (sill corner, shoulder, roof edge): inside the model's spine blocks (top y 4.2 at |x| 0.58, 3.75 at 0.71)
END_PROFILE = [(0.905, 3.23), (0.575, 4.185), (RAIL_X, 4.185)]


def rear_polygon():
    """the canopy's section at the rear plane (6 vertices, right half sorted bottom to top, then the left)"""
    P = FACETS['Rb']
    r = sorted([p for p in P if p[0] > 1e-6], key=lambda p: p[1])
    l = sorted([p for p in P if p[0] < -1e-6], key=lambda p: p[1])
    return r, l


def build_loft(m, uv_out, uv_in):
    r, l = rear_polygon()
    if len(r) != 3 or len(l) != 3:
        raise SystemExit(f'rear section has {len(r)} / {len(l)} vertices, expected 3 / 3')
    ring = [r[0], r[1], r[2], l[2], l[1], l[0]]                       # bottom right, up, over the top, down the left
    end = ([np.array([x, y, Z_END]) for x, y in END_PROFILE] +
           [np.array([-x, y, Z_END]) for x, y in END_PROFILE[::-1]])
    cen = np.array([0.0, 3.9, 5.0])
    for i in range(len(ring) - 1):
        q = [ring[i], ring[i + 1], end[i + 1], end[i]]
        warped_quad(m, q, cen, uv_out, uv_in)
    m.face(end, uv_out, -Z)                   # the end cap: the loft ends flush at the spine block, no hole where the blocks' steps fall short of it
    return ring, end


def warped_quad(m, q, cen, uv_out, uv_in):
    """a (not exactly planar) quad as two triangles, each facing away from cen, with a dark lining a slab's thickness inside"""
    for tri in ((q[0], q[1], q[2]), (q[0], q[2], q[3])):
        n = unit(np.cross(tri[1] - tri[0], tri[2] - tri[0]))
        if n @ (np.mean(tri, 0) - cen) < 0:
            n = -n
        m.face(list(tri), uv_out, n)
        m.face([p - n * SLAB for p in tri][::-1], uv_in, -n)


def slab(m, poly, normal, uv_out, uv_in):
    P = [np.array(p, float) for p in poly]
    n = unit(normal)
    m.face(P, uv_out, n)
    m.face([p - n * SLAB for p in P], uv_in, -n)
    cen = np.mean(P, 0)
    for i in range(len(P)):
        a, b = P[i], P[(i + 1) % len(P)]
        out = unit(np.cross(b - a, n))
        if out @ ((a + b) / 2 - cen) < 0:
            out = -out
        m.face([a, b, b - n * SLAB, a - n * SLAB], uv_out, out)


# ---------------------------------------------------------------- the section the checks read (eye-relative)
def _clip2(poly, nx, ny, d):
    out = []
    for i, p in enumerate(poly):
        q = poly[(i + 1) % len(poly)]
        sp, sq = nx * p[0] + ny * p[1] - d, nx * q[0] + ny * q[1] - d
        if sp <= 1e-9:
            out.append(p)
        if (sp < -1e-9 and sq > 1e-9) or (sp > 1e-9 and sq < -1e-9):
            out.append(p + (q - p) * (sp / (sp - sq)))
    return out


def half_outline(poly):
    """a convex polygon (counter-clockwise in x, y): the right half from its lowest outer point up to the top at x = 0"""
    P = [np.array(p, float) for p in poly]
    i0 = min(range(len(P)), key=lambda i: (round(P[i][1], 4), -P[i][0]))
    seq = P[i0:] + P[:i0]
    out = []
    for p in seq:
        out.append(p)
        if p[0] <= 1e-6 and len(out) > 1:
            break
    if out[-1][0] > 1e-6:
        out.append(np.array([0.0, out[-1][1]]))
    return [(max(0.0, float(p[0])), float(p[1])) for p in out]


def section_at(z):
    big = [np.array(p) for p in ((-3, 0), (3, 0), (3, 8), (-3, 8))]
    poly = big
    for pl in PLANES.values():
        poly = _clip2(poly, pl.n[0], pl.n[1], pl.d - pl.n[2] * z)
        if not poly:
            return None
    poly = _clip2(poly, 0, -1, -y_bot(z))
    poly = _clip2(poly, 1, 0, WALL_X)
    poly = _clip2(poly, -1, 0, WALL_X)
    return poly if len(poly) >= 3 else None


def sections_rel():
    out = []
    r, l = rear_polygon()
    ring_r = [r[0], r[1], r[2]]
    zr = [p[2] for p in ring_r]
    for z in (Z_END, 4.75, 5.10, 5.30, 5.44):
        pts = []
        for (ex, ey), rv in zip(END_PROFILE, ring_r):
            t = min(1.0, (z - Z_END) / (rv[2] - Z_END))
            pts.append((ex + (rv[0] - ex) * t, ey + (rv[1] - ey) * t))
        pts.append((0.0, pts[-1][1]))
        out.append((z - EYE_P[2], [(x, y - EYE_P[1]) for x, y in pts]))
    for z in (5.50, 5.80, 6.10, 6.40, 6.70, 6.90, 7.10, 7.30, 7.60, 7.90, 8.10, 8.30, 8.45, 8.60, 8.75, 8.90, 9.02):
        poly = section_at(z)
        if poly is None:
            continue
        pts = half_outline(poly)
        out.append((z - EYE_P[2], [(x, y - EYE_P[1]) for x, y in pts]))
    return out


CANOPY = sections_rel()

# ---------------------------------------------------------------- build
PANES = {}                       # name -> list of glass polygons (3D), for flat_panes


def build(uv_out, uv_in, uv_frame):
    m = Mesh()
    glass = []
    edges = []
    ps = pieces()
    for name, poly, kind in ps:
        poly = dedupe([np.array(p, float) for p in poly])
        if len(poly) < 3:
            continue
        n = plane_normal(poly)
        for i in range(len(poly)):
            edges.append((poly[i], poly[(i + 1) % len(poly)]))
        if kind == 'glass':
            g = inset_polygon(poly, n, R_TUBE + GLASS_GAP)
            PANES.setdefault(name, []).append(g)
            for k in range(1, len(g) - 1):
                glass.append([g[0], g[k], g[k + 1]])
        else:
            slab(m, poly, n, uv_out, uv_in)
    # frame members: one per straight run along every pane edge
    for a, b in merge_edges(edges):
        tube(m, a, b, R_TUBE, uv_frame)
    # the rear fairing and its lining
    build_loft(m, uv_out, uv_in)
    # the sill skirt: from the frame line down to the model's sill slab (opaque, flush with the fuselage side)
    zs = [Z_P0, 7.73, 8.30]
    for s in (1, -1):
        for za, zb in zip(zs[:-1], zs[1:]):
            ya, yb_ = y_bot(za), y_bot(zb)
            oa, ob = 3.10 - 0.1452 * (za - 5.05) + 0.01, 3.10 - 0.1452 * (zb - 5.05) + 0.01
            quad = [np.array([s * SDX(ya, za), ya, za]), np.array([s * SDX(yb_, zb), yb_, zb]),
                    np.array([s * (SDX(yb_, zb) + 0.020), ob, zb]), np.array([s * (SDX(ya, za) + 0.020), oa, za])]
            warped_quad(m, quad, np.array([0.0, 3.3, (za + zb) / 2]), uv_out, uv_in)
    # opening sections: hinge barrels on the shoulder crease, a latch handle on the sill rail
    for s in (1, -1):
        for z0, z1 in ((5.60, 6.20), (7.60, 8.10)):
            for zh in (z0, z1):
                p = _shoulder_point(zh)
                if p is None:
                    continue
                p = p * np.array([s, 1, 1])
                tube(m, p + np.array([s * 0.018, 0.012, -0.05]), p + np.array([s * 0.018, 0.012, 0.05]), 0.013, uv_frame)
        for zl in (5.85, 7.75):
            c = np.array([s * (SDX(y_bot(zl), zl) + 0.020), y_bot(zl) + 0.040, zl])
            box(m, c, (X, Y, Z), (0.012, 0.022, 0.040), uv_frame)
    return m, glass


def _shoulder_point(z):
    """the point of the shoulder crease (side plane meets the shoulder facets) at station z, or None"""
    best = None
    for nm in ('Cp_r', 'Cg1_r', 'Cg2_r'):
        P = FACETS[nm]
        for i in range(len(P)):
            a, b = P[i], P[(i + 1) % len(P)]
            if abs(a[0] - SDX(a[1], a[2])) < 1e-4 and abs(b[0] - SDX(b[1], b[2])) < 1e-4 and min(a[2], b[2]) - 1e-6 <= z <= max(a[2], b[2]) + 1e-6 and abs(a[2] - b[2]) > 1e-6:
                t = (z - a[2]) / (b[2] - a[2])
                best = a + (b - a) * t
    return best


# ---------------------------------------------------------------- the airframe

def cut_old(bones):
    """removes the faces of CUT_RANGES from the hull piece (the untouched hull has HULL_FACES faces); returns the count"""
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    pm = hull['poly_mesh']
    n = len(pm['polys'])
    drop = set()
    for a, b in CUT_RANGES:
        drop.update(range(a, b + 1))
    if n == HULL_FACES:
        pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in drop]
        return len(drop)
    if n == HULL_FACES - len(drop):
        return 0
    raise SystemExit(f'hull has {n} faces: neither the untouched airframe ({HULL_FACES}) nor an already cut one ({HULL_FACES - len(drop)})')


def flat_panes_json():
    """flat_panes/ah_56a.json: one entry per pane name, selected by the glass triangles' centroids (a box round the pane's glass when no
    other pane's glass lies in it, one small box per triangle otherwise)"""
    T = {}
    for name, polys in PANES.items():
        for g in polys:
            for k in range(1, len(g) - 1):
                T.setdefault(name, []).append(np.array([g[0], g[k], g[k + 1]]))
    cents = {n: [t.mean(0) for t in ts] for n, ts in T.items()}
    out = []
    names = sorted(PANES)
    for name in names:
        # a pane on both sides of the centre line is one piece (mirror false); one on each side is checked as |x| (mirror true)
        spans = [(min(p[:, 0].min() for p in [g]), max(p[:, 0].max() for p in [g])) for g in PANES[name]]
        centre = any(lo < -1e-6 and hi > 1e-6 for lo, hi in spans)
        mirror = not centre and len(PANES[name]) > 1
        if mirror:
            use = [t for t in T[name] if t.mean(0)[0] > 1e-6]
        else:
            use = T[name]
        if not centre and len(PANES[name]) == 1:
            use = T[name]
        sel = (lambda p: np.array([abs(p[0]), p[1], p[2]])) if mirror else (lambda p: p)
        pts = np.concatenate([t.reshape(-1, 3) for t in use])
        sel = (lambda p: np.array([abs(p[0]), p[1], p[2]])) if mirror else (lambda p: p)
        lo, hi = pts.min(0), pts.max(0)
        if mirror:
            lo = np.array([min(abs(pts[:, 0])), lo[1], lo[2]])
            hi = np.array([max(abs(pts[:, 0])), hi[1], hi[2]])
        lo, hi = lo - 0.0005, hi + 0.0005
        clash = False
        for other, cs in cents.items():
            if other == name:
                continue
            for c in cs:
                q = sel(c)
                if np.all(q >= lo) and np.all(q <= hi):
                    clash = True
        entry = {'name': name, 'mirror': mirror}
        if not clash:
            entry['box'] = [[round(float(v), 4) for v in lo], [round(float(v), 4) for v in hi]]
        else:
            boxes = []
            for t in use:
                c = sel(t.mean(0))
                boxes.append([[round(float(v) - 0.0004, 4) for v in c], [round(float(v) + 0.0004, 4) for v in c]])
            entry['boxes'] = boxes
        out.append(entry)
    return {'why': ('AH-56A Cheyenne canopy: tandem crew sections of flat panes in slim frames (the real Cheyenne has flat plates, a V-shaped '
                    'windscreen centre post, flat side panes and separate raised rear and lower front sections). Layout from the model\'s own '
                    'frame bars (canopy_ah_56a.py): every pane is a face of one convex canopy solid, so each is one exact plane; pane count '
                    'and sizes are UNVERIFIED against photographs. Glazed: pilot side / shoulder / roof (two, either side of a centre spine), '
                    'gunner side / shoulder / overhead / corner / windscreen (two, meeting in the V). The fairing between the crews and the '
                    'rear fairing are opaque.'),
            'panes': out}


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    bones[:] = [b for b in bones if b['name'] != CANOPY_BONE]
    cut = cut_old(bones)
    sw = L.Swatches(L.texture_path(VID))
    uv_out, uv_in, uv_frame = sw.uv((63, 66, 33)), sw.uv((24, 26, 22)), sw.uv((46, 49, 28))
    m, glass = build(uv_out, uv_in, uv_frame)
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    pm = m.poly_mesh()
    bones.insert(bones.index(hull) + 1, {'name': CANOPY_BONE, 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm})
    both = [list(map(float, p)) for t in glass for p in t] + [list(map(float, t[k])) for t in glass for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'ah_56a canopy: {cut} old interior and frame faces cut, convexity {check_convex():.2e}, {len(pm["polys"])} faces of frame, skin and '
          f'rear fairing; {len(glass)} glass triangles in {sum(len(v) for v in PANES.values())} flat panes; swatch error {sw.worst:.0f}')
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
    with open(PANES_JSON, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(flat_panes_json(), f, indent=2)
        f.write('\n')


if __name__ == '__main__':
    main(sys.argv[1:])
