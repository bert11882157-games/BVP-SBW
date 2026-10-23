#!/usr/bin/env python3
"""AH-1W SuperCobra canopy, built by hand on the AH-1W.mtb airframe's own frame lines: flat-plate glazing in slim frames.

The real AH-1W (and the AH-1S / AH-1F before it) has a tandem canopy of FLAT plates, no curved glass: the gunner's raked
windscreen with a small overhead plate, two side plates a side (a door plate and a quarter plate), the pilot's side door plates,
and a small overhead plate in the pilot's roof; both crew sit under the same roof line, which falls from the pilot's
roof to the nose. (References: War Thunder ah_1w external model; AH-1W photographs and the AH-1S "seven-plane semi-flat
canopy" description. The AH-1Z has a different, curved-edge canopy; it was not used.)

The airframe carries the canopy only as a skeleton of 9 x 6 cm boxes (hull piece `hull`): one bar a side (roof rail, upper A-pillar,
lower A-pillar, all at x 0.318), a header, and two leaning posts from the sill rails to the header's ends. This script cuts exactly
those five loose pieces out (they are the old frame; the sill rails, the body, the nose stay) and rebuilds the same lines slim:

  * the frame lines are kept: roof rail J1 (z 3.05) - J2 (z 4.875, the header) - J3 (z 5.258, the bow) - J4 (z 6.022, the nose);
  * every pane is ONE plane, found from those lines (nothing is fitted to the glass afterwards):
      - pilot side plate: through the roof rail with a 3.0 m/m drop outward (the old leaning post lies exactly in it);
      - gunner door plate: through the upper A-pillar and the same post; gunner quarter plate: through the lower A-pillar,
        meeting the door plate along the vertical crease at the bow (z 5.258); both drop 3.287 m/m outward;
      - gunner windscreen: the lower A-pillar's plane across the nose (|x| < 0.318); gunner overhead plate: the upper A-pillar's;
      - pilot overhead plate: in the roof plane over the pilot's head;
  * the frame members are 4 cm round tubes on the pane edges, the glass stops 5 mm from them; the roof between the rails is a
    closed skin (airframe colour, a dark underside) with a hole for the overhead plate; the pilot's rear wall closes the canopy
    above the old rear bulkhead.

The posts and the rail are one line each: the B-post is the crease of the pilot and gunner plates, the header and the bow are
single bars across, the rail with the A-pillars is one polyline member.

CANOPY (eye-relative sections: z, then the right half from the sill up over the top) is what the checks in
work/elite_expansion_2/checks read for the canopy outline (eye = the pilot's).

Run first (on the airframe's files); the cockpit script ah1w_super_cobra.py then appends its sight glass to CanopyGlass.
Usage: python tools/cockpit_models/canopy_ah1w_super_cobra.py [--dry-run]
"""
import json, math, os, sys
import numpy as np
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

VID = 'ah1w_super_cobra'
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, f'assets/berts_vehicle_pack/custom_geo/{VID}.geo.json')
ASSET = os.path.join(GEN, f'assets/berts_vehicle_pack/sbw/vehicles/{VID}.json')
DATA = os.path.join(GEN, f'data/berts_vehicle_pack/sbw/vehicles/{VID}.json')
HULL_BONE = 'hull'
CANOPY_BONE = 'hull__canopy'

EYE_P = np.array([0.0, 3.06176, 3.345])          # the pilot's eye (the checks work eye-relative to it)

# ---- the airframe's frame pieces (loose connected parts of the hull piece): bounding boxes (lo, hi) in blocks
OLD_FRAME = [((0.274, 2.431, 2.962), (0.363, 3.451, 6.022)), ((-0.363, 2.431, 2.962), (-0.274, 3.451, 6.022)),     # rail + A-pillars
             ((-0.287, 3.196, 4.811), (0.287, 3.266, 4.939)),                                                     # header
             ((0.319, 2.236, 4.397), (0.606, 3.256, 4.932)), ((-0.606, 2.236, 4.397), (-0.319, 3.256, 4.971))]   # leaning posts

# ---- the frame lines (centres of the old bars), blocks
RX = 0.318
Z1, Z2, Z3, Z4 = 3.05, 4.875, 5.258, 6.022      # rear door post, header, bow, nose


def rail_y(z):
    """the roof rail's centre line over the pilot (the old bar's centre: top 3.451 at z 2.962 falling 0.1005 per m)"""
    return 3.419 - 0.10055 * (z - 2.962)


def a_up(z):
    """the upper A-pillar's centre line, header to bow"""
    return rail_y(Z2) - 0.3329 * (z - Z2)


def a_low(z):
    """the lower A-pillar's centre line, bow to nose"""
    return a_up(Z3) - 0.8359 * (z - Z3)


K_S = 3.0                                       # pilot side plate: metres of drop per metre outboard of the rail
K_G = 3.287                                     # gunner door and quarter plates (through the leaning post)


def plane_s(x, z):  # pilot side plate: height at (|x|, z)
    return rail_y(z) - K_S * (x - RX)


def plane_gd(x, z):  # gunner door plate
    return a_up(z) - K_G * (x - RX)


def plane_gq(x, z):  # gunner quarter plate
    return a_low(z) - K_G * (x - RX)


def x_on(plane, y, z):
    """|x| where the plane is at height y at station z"""
    k = K_S if plane is plane_s else K_G
    base = {plane_s: rail_y, plane_gd: a_up, plane_gq: a_low}[plane](z)
    return RX + (base - y) / k


# the sill rails' top edge (outer corner) along the old model's door rail: z -> y (the airframe's own rails stay)
RAIL_Z = [2.96, 3.28, 3.47, 5.89]
RAIL_Y = [2.84, 2.69, 2.43, 2.07]


def rail_top(z):
    return float(np.interp(z, RAIL_Z, RAIL_Y))


def pt(x, z, plane):
    return np.array([x, plane(x, z), z])


# the B-post: where the pilot's and the gunner's door plates meet (the old leaning post lies on it)
def post_x(z):
    # x where Sigma_S and Sigma_GD meet at station z: rail_y(z) - K_S (x - RX) = a_up(z) - K_G (x - RX)
    return RX + (rail_y(z) - a_up(z)) / (K_S - K_G)


J1 = pt(RX, Z1, plane_s)
J2 = pt(RX, Z2, plane_s)
J3 = np.array([RX, a_up(Z3), Z3])
J4 = np.array([RX, a_low(Z4), Z4])
PB = pt(post_x(4.53), 4.53, plane_s)            # post foot on the sill rail
# the pilot's door bottom edge: 5 cm over the sill rail, never outboard of the plane's meeting with x 0.60 (the rail's outer edge)
BOT_Z = [4.53, 3.95, 3.47, 3.28, Z1]
PILOT_BOTTOM = []
for _z in BOT_Z:
    _y = max(rail_top(_z) + 0.05, plane_s(0.60, _z))
    PILOT_BOTTOM.append(np.array([x_on(plane_s, _y, _z), _y, _z]))
PILOT_BOTTOM[0] = PB
CB = np.array([x_on(plane_gd, rail_top(Z3) + 0.05, Z3), rail_top(Z3) + 0.05, Z3])    # the crease's foot
QM = np.array([x_on(plane_gq, rail_top(5.6) + 0.05, 5.6), rail_top(5.6) + 0.05, 5.6])
QF = np.array([x_on(plane_gq, rail_top(5.89) + 0.05, 5.89), rail_top(5.89) + 0.05, 5.89])

R_TUBE = 0.020          # main frame tubes
R_SMALL = 0.014         # the overhead plate's frame
GLASS_GAP = -0.016      # the glass ends 12 mm inside the tube's silhouette, hidden in it (the audit's 5 mm slits beside every member were see-through seams)
ROOF_Z = (3.30, 3.78)   # pilot overhead plate: z range of its frame centre line
ROOF_X = 0.17           # ... and half width

# ---- the section the checks read (eye-relative)


def _side(z):
    """(sill point, plane of the side plate) at station z: the pane's bottom edge and the roof line over it"""
    if z <= 4.53:
        zs = [b[2] for b in PILOT_BOTTOM][::-1]
        xs = [b[0] for b in PILOT_BOTTOM][::-1]
        ys = [b[1] for b in PILOT_BOTTOM][::-1]
        return (float(np.interp(z, zs, xs)), float(np.interp(z, zs, ys))), rail_y(z)
    if z <= Z3:
        t = (z - 4.53) / (Z3 - 4.53)
        y = PB[1] + (CB[1] - PB[1]) * t
        return (x_on(plane_gd, y, z), y), (rail_y(z) if z <= Z2 else a_up(z))
    zq = [CB[2], QM[2], QF[2], J4[2]]
    xq = [CB[0], QM[0], QF[0], J4[0]]
    yq = [CB[1], QM[1], QF[1], J4[1]]
    return (float(np.interp(z, zq, xq)), float(np.interp(z, zq, yq))), a_low(z)


def sections_rel():
    out = []
    for z in [Z1, 3.28, 3.47, 3.95, 4.53, 4.7, Z2, 5.1, Z3, 5.45, 5.6, 5.75, 5.89, 5.98, Z4 - 0.002]:
        (xs_, ys_), top = _side(z)
        top = max(top, ys_ + 0.002)
        # the outline starts on the sill rail's top and goes straight up the door sill wall (built under every door plate's
        # bottom frame) to the glass: the cockpit stands inside it, over the rail
        y_rail = min(ys_, rail_top(z) + 0.002) if z < 5.9 else ys_
        pts = [(xs_, y_rail - EYE_P[1])] if y_rail < ys_ - 0.003 else []
        out.append((z - EYE_P[2], pts + [(xs_, ys_ - EYE_P[1]), (RX, top - EYE_P[1]), (0.0, top - EYE_P[1])]))
    return out


CANOPY = sections_rel()

# ---------------------------------------------------------------- mesh (absolute blocks in, model pixels out)


def unit(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


class Mesh:
    def __init__(self):
        self.faces = []

    def face(self, pts, uv, normal):
        P = np.array(pts, float)
        n = np.array(normal, float)
        n /= np.linalg.norm(n)
        c = P.mean(0)
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
        Pl, Nl, Ul, polys = [], [], [], []
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
                poly.append([len(Pl), ni, ui])
                Pl.append([round(x, 4) + 0.0 for x in p])
            polys.append(poly)
        return {'normalized_uvs': True, 'positions': Pl, 'normals': Nl, 'uvs': Ul, 'polys': polys}


def tube(m, pts, r, uv, seg=8, close_start=True, close_end=True):
    """a round member along a polyline (mitred at the bends, flat caps)"""
    P = [np.array(p, float) for p in pts]
    n = len(P)
    T = []
    for i in range(n):
        a = unit(P[i] - P[i - 1]) if i > 0 else None
        b = unit(P[i + 1] - P[i]) if i < n - 1 else None
        T.append(unit(a + b) if a is not None and b is not None else (a if a is not None else b))
    ref = np.array([0.0, 1.0, 0.0])
    rings = []
    for i in range(n):
        t = T[i]
        r0 = ref if abs(t @ ref) < 0.9 else np.array([1.0, 0.0, 0.0])
        u = unit(np.cross(t, r0))
        v = unit(np.cross(t, u))
        # keep the thickness across a bend: stretch the ring along the bend's plane
        k = 1.0
        if 0 < i < n - 1:
            a, b = unit(P[i] - P[i - 1]), unit(P[i + 1] - P[i])
            k = 1.0 / max(0.5, math.cos(0.5 * math.acos(float(np.clip(a @ b, -1, 1)))))
            bend = unit(b - a) if np.linalg.norm(b - a) > 1e-9 else u
            # stretch the ring's component along `bend`
        ring = []
        for j in range(seg):
            ang = 2 * math.pi * (j + 0.5) / seg
            d = u * math.cos(ang) + v * math.sin(ang)
            if 0 < i < n - 1 and k > 1.0001:
                d = d + bend * (d @ bend) * (k - 1.0)
            ring.append(P[i] + d * r)
        rings.append(ring)
    for i in range(n - 1):
        for j in range(seg):
            j2 = (j + 1) % seg
            q = [rings[i][j], rings[i][j2], rings[i + 1][j2], rings[i + 1][j]]
            c = np.mean(q, 0)
            axis = (P[i] + P[i + 1]) / 2
            m.face(q, uv, c - axis)
    if close_start:
        m.face(rings[0], uv, -T[0])
    if close_end:
        m.face(rings[-1], uv, T[-1])


def mirror(p):
    return np.array([-p[0], p[1], p[2]])


# ---------------------------------------------------------------- glass polygons (planar by construction)


def inset_polygon(poly3, normal, d):
    """the polygon (convex, in one plane) moved in by d inside its plane"""
    P = np.array(poly3, float)
    c = P.mean(0)
    n = unit(normal)
    out = []
    m = len(P)
    # offset every edge inward by d and intersect neighbours
    lines = []
    for i in range(m):
        a, b = P[i], P[(i + 1) % m]
        e = unit(b - a)
        inward = unit(np.cross(n, e))
        if inward @ (c - a) < 0:
            inward = -inward
        lines.append((a + inward * d, e))
    for i in range(m):
        (p0, e0), (p1, e1) = lines[i - 1], lines[i]
        # solve p0 + s e0 = p1 + t e1 in the plane
        A = np.array([e0, -e1]).T
        sol, *_ = np.linalg.lstsq(A, p1 - p0, rcond=None)
        out.append(p0 + sol[0] * e0)
    return np.array(out)


def plane_normal(poly3):
    P = np.array(poly3, float)
    nn = np.zeros(3)
    for i in range(len(P)):
        a, b = P[i], P[(i + 1) % len(P)]
        nn += np.array([(a[1] - b[1]) * (a[2] + b[2]), (a[2] - b[2]) * (a[0] + b[0]), (a[0] - b[0]) * (a[1] + b[1])])
    return unit(nn)


def fan(poly):
    return [[poly[0], poly[k], poly[k + 1]] for k in range(1, len(poly) - 1)]


PANES = {}      # name -> list of glass polygons (3D), registered for the flat-pane list


def add_pane(name, poly, r):
    n = plane_normal(poly)
    g = inset_polygon(poly, n, r + GLASS_GAP)
    PANES.setdefault(name, []).append(g)
    return g


# ---------------------------------------------------------------- the canopy


HULL_TRIS = None


def load_hull_tris(bones):
    global HULL_TRIS
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    pm = hull['poly_mesh']
    P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    T = []
    for poly in pm['polys']:
        idx = [v[0] for v in poly]
        for k in range(1, len(idx) - 1):
            T.append(P[[idx[0], idx[k], idx[k + 1]]])
    HULL_TRIS = np.array(T)


def hull_target(p, reach=0.40):
    """the point 1.5 cm inside the hull's surface nearest below and outboard of p (the same side), in the cross-section z = p[2]"""
    s = 1.0 if p[0] >= 0 else -1.0
    T = HULL_TRIS
    z = p[2]
    m = (T[:, :, 2].min(1) <= z) & (T[:, :, 2].max(1) >= z)
    segs = []
    for t in T[m]:
        d = t[:, 2] - z
        q = []
        for i in range(3):
            a_, b_ = t[i], t[(i + 1) % 3]
            if (d[i] > 0) != (d[(i + 1) % 3] > 0):
                f = d[i] / (d[i] - d[(i + 1) % 3])
                q.append(a_ + (b_ - a_) * f)
        if len(q) == 2:
            segs.append((q[0][:2], q[1][:2]))
    best, bp = reach, None
    for a_, b_ in segs:
        if min(a_[0] * s, b_[0] * s) < p[0] * s - 0.03 or min(a_[1], b_[1]) > p[1] + 0.02:
            continue
        ab = b_ - a_
        L2 = float(ab @ ab)
        u = 0.0 if L2 < 1e-12 else float(np.clip(((p[:2] - a_) @ ab) / L2, 0, 1))
        c = a_ + ab * u
        if c[0] * s < p[0] * s - 0.03 or c[1] > p[1] + 0.02:
            continue
        d = float(np.linalg.norm(c - p[:2]))
        if d < best and d > 0.012:
            best, bp = d, c
    if bp is None:
        return np.array([p[0], p[1] - 0.04, p[2]])
    dirv = (bp - p[:2]) / np.linalg.norm(bp - p[:2])
    t = bp + dirv * 0.027
    return np.array([t[0], t[1], z])

def ribbon_skirt(m, top, bot, uv_out, uv_in, thick=0.037):
    """one closed solid along a run: the outer strip from `top` (a frame member's centre line) down to `bot` (inside the hull), an inner
    strip `thick` inboard of it (3.7 cm: no face lies within 12 mm of the member tube's flats) and the closing top, bottom and end faces.
    All stations share vertices, so the strip is one welded part that touches the frame."""
    top = [np.array(p, float) for p in top]
    bot = [np.array(p, float) for p in bot]
    n_st = len(top)
    N = []
    for k in range(n_st):
        t = top[min(k + 1, n_st - 1)] - top[max(k - 1, 0)]
        nn = np.cross(t, bot[k] - top[k])
        nn = nn / np.linalg.norm(nn)
        if nn[0] * (1.0 if top[k][0] >= 0 else -1.0) < 0:
            nn = -nn
        N.append(nn)
    itop = [top[k] - N[k] * thick for k in range(n_st)]
    ibot = [bot[k] - N[k] * thick for k in range(n_st)]
    for k in range(n_st - 1):
        nm = N[k] + N[k + 1]
        nm = nm / np.linalg.norm(nm)
        m.face([top[k], top[k + 1], bot[k + 1], bot[k]], uv_out, nm)
        m.face([itop[k], itop[k + 1], ibot[k + 1], ibot[k]], uv_in, -nm)
        m.face([top[k], top[k + 1], itop[k + 1], itop[k]], uv_out, np.array([0.0, 1.0, 0.0]))
        m.face([bot[k], bot[k + 1], ibot[k + 1], ibot[k]], uv_out, np.array([0.0, -1.0, 0.0]))
    for k, sg in ((0, -1.0), (n_st - 1, 1.0)):
        t = top[min(k + 1, n_st - 1)] - top[max(k - 1, 0)]
        t = t / np.linalg.norm(t)
        m.face([top[k], bot[k], ibot[k], itop[k]], uv_out, t * sg)


def build(uv_out, uv_in):
    m = Mesh()
    glass = []                                   # triangles, one winding
    sides = (1, -1)

    def S(p, s):
        return p if s > 0 else mirror(p)

    # --- members
    # rail (rear post top -> J2 -> J3 -> nose) one polyline per side
    for s in sides:
        tube(m, [S(J1, s), S(J2, s), S(J3, s), S(J4, s)], R_TUBE, uv_out)
        # B-post: J2 -> foot (embedded into the sill rail a hair)
        tube(m, [S(J2, s), S(PB, s)], R_TUBE, uv_out)
        # pilot door: bottom polyline, then the rear door post up to the rail's rear end
        tube(m, [S(p, s) for p in PILOT_BOTTOM] + [S(J1, s)], R_TUBE, uv_out)
        # gunner: crease down to the sill, bottom edge back to the post foot and forward round the quarter plate to the nose
        tube(m, [S(J3, s), S(CB, s)], R_TUBE, uv_out)
        tube(m, [S(PB, s), S(CB, s), S(QM, s), S(QF, s), S(J4, s)], R_TUBE, uv_out)
    tube(m, [J2, mirror(J2)], R_TUBE, uv_out)    # header
    tube(m, [J3, mirror(J3)], R_TUBE, uv_out)    # the bow between the windscreen and the overhead plate
    tube(m, [J4, mirror(J4)], R_TUBE, uv_out)    # the windscreen's foot on the nose deck
    # the pilot's roof: a slim centre spine and the rear bow (the header and the rails frame the two roof plates)
    tube(m, [np.array([0.0, rail_y(Z1), Z1]), np.array([0.0, rail_y(Z2), Z2])], R_TUBE, uv_out)
    tube(m, [np.array([RX, rail_y(Z1), Z1]), np.array([-RX, rail_y(Z1), Z1])], R_TUBE, uv_out)

    # --- glass panes
    def pane(name, poly, r=R_TUBE):
        g = add_pane(name, poly, r)
        glass.extend(fan(list(g)))

    for s in sides:
        pane('pilot side plate', [S(J1, s), S(J2, s), S(PB, s)] + [S(p, s) for p in PILOT_BOTTOM[1:]])
        pane('gunner door plate', [S(J2, s), S(J3, s), S(CB, s), S(PB, s)])
        pane('gunner quarter plate', [S(J3, s), S(J4, s), S(QF, s), S(QM, s), S(CB, s)])
    pane('gunner windscreen', [J3, J4, mirror(J4), mirror(J3)])
    pane('gunner overhead plate', [J2, J3, mirror(J3), mirror(J2)])
    for s in sides:                              # the pilot's roof: one flat plate each side of the spine, in the roof plane
        pane('pilot roof plate', [np.array([0.0, rail_y(Z1), Z1]), np.array([s * RX, rail_y(Z1), Z1]),
                                  np.array([s * RX, rail_y(Z2), Z2]), np.array([0.0, rail_y(Z2), Z2])])

    # --- opaque parts: the roof skin, the rear wall (airframe colour outside, dark inside)
    def slab(poly, normal, thick):
        P = [np.array(p, float) for p in poly]
        n = unit(normal)
        m.face(P, uv_out, n)
        m.face([p - n * thick for p in P], uv_in, -n)
        cen = np.mean(P, 0)
        for i in range(len(P)):
            a, b = P[i], P[(i + 1) % len(P)]
            out = unit(np.cross(b - a, n))
            if out @ ((a + b) / 2 - cen) < 0:
                out = -out
            m.face([a, b, b - n * thick, a - n * thick], uv_out, out)

    # the rear wall in the plane z = Z1 - 0.012: from the roof's rear edge down the rear door posts to the old bulkhead's top (y 3.13)
    # The wall is a 14 cm bulkhead block whose rear face stands 2 cm INSIDE the airframe's rear block (face z 2.962), so no slit shows between
    # them, and whose front face is 1.3 cm clear of the rear bow's and posts' flats (no sawtooth steps against them).
    zr = 2.94
    yb = 3.12
    xb = RX + (rail_y(Z1) - yb) / K_S
    wall = [[RX, rail_y(Z1), zr], [-RX, rail_y(Z1), zr], [-xb, yb, zr], [xb, yb, zr]]
    slab(wall, [0, 0, -1], 0.141)

    # --- the sill skirts: the door plates' bottom frames stand up to 15 cm over the sill rails (the planes lean out), so each side gets an
    # opaque strip from the bottom frame's centre line to the nearest hull surface below and outside it (1.5 cm into it): airframe colour
    # outside, dark inside; no slot to the sky. The hull's surface is found in the cross-section at every 8 cm of the frame's run.
    path = [PILOT_BOTTOM[4], PILOT_BOTTOM[3], PILOT_BOTTOM[2], PILOT_BOTTOM[1], PB, CB, QM, QF, J4]
    for s in sides:
        pts = []
        for a, b in zip(path[:-1], path[1:]):
            n_st = max(1, int(math.ceil(float(np.linalg.norm(b - a)) / 0.12)))
            pts += [S(a + (b - a) * (k / n_st), s) for k in range(n_st)]
        pts.append(S(path[-1], s))
        ribbon_skirt(m, pts, [hull_target(p) for p in pts], uv_out, uv_in)
    # the rear posts' fairing: between the airframe's rear block (face z 2.962) and the posts (z 3.05) a 3.7 cm plate each side closes the
    # slit that showed from behind the posts
    for s in sides:
        ys = [PILOT_BOTTOM[4][1], rail_y(Z1)]
        xs = [x_on(plane_s, y, Z1) for y in ys]
        quad = [np.array([s * xs[0], ys[0], 2.94]), np.array([s * xs[0], ys[0], Z1]), np.array([s * xs[1], ys[1], Z1]), np.array([s * xs[1], ys[1], 2.94])]
        dx, dy = xs[1] - xs[0], ys[1] - ys[0]
        nn = unit([-dy * s, dx * s, 0.0])
        if nn[0] * s < 0:
            nn = -nn
        slab(quad, nn, 0.037)
    return m, glass


def _components(pm):
    P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    key, ids = {}, []
    for p in P:
        ids.append(key.setdefault(tuple(np.round(p, 3)), len(key)))
    ids = np.array(ids)
    rows, cols = [], []
    for poly in pm['polys']:
        vs = [ids[v[0]] for v in poly]
        for a in vs[1:]:
            rows.append(vs[0]); cols.append(a)
    n = len(key)
    _, lab = connected_components(coo_matrix((np.ones(len(rows)), (rows, cols)), shape=(n, n)), directed=False)
    return P, ids, lab


def cut_old_frame(bones):
    """removes the five loose frame parts from the hull piece; returns (faces removed, the frame's own uv texel)"""
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    pm = hull['poly_mesh']
    P, ids, lab = _components(pm)
    comp = {}
    for i, poly in enumerate(pm['polys']):
        if poly:
            comp.setdefault(lab[ids[poly[0][0]]], []).append(i)
    drop, uv = set(), None
    for fl in comp.values():
        V = np.concatenate([P[[v[0] for v in pm['polys'][i]]] for i in fl])
        lo, hi = V.min(0), V.max(0)
        for blo, bhi in OLD_FRAME:
            if np.all(np.abs(lo - blo) < 0.003) and np.all(np.abs(hi - bhi) < 0.003):
                drop.update(fl)
                if uv is None:
                    uv = list(pm['uvs'][pm['polys'][fl[0]][0][2]])
    pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in drop]
    return len(drop), uv


TW, TH = 2048, 2176


def texel(px, py):
    """normalized uv of the centre of texel (px, py) of textures/entity/ah1w_super_cobra.png (v up)"""
    return [(px + 2) / TW, 1.0 - (py + 2) / TH]


# uniform 4 x 4 blocks of the airframe's own texture, inside the area the model's UVs use (never erased by the cockpit's atlas)
UV_GREY = texel(140, 1156)       # (169, 176, 186) the airframe grey
UV_DARK = texel(528, 1236)       # (31, 31, 31) the dark interior


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    bones[:] = [b for b in bones if b['name'] != CANOPY_BONE]
    cut, _ = cut_old_frame(bones)
    load_hull_tris(bones)
    m, glass = build(UV_GREY, UV_DARK)
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    pm = m.poly_mesh()
    bones.insert(bones.index(hull) + 1, {'name': CANOPY_BONE, 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm})
    both = [list(map(float, p)) for t in glass for p in t] + [list(map(float, t[k])) for t in glass for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS',
                            'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'ah1w canopy: old frame cut ({cut} faces), {len(pm["polys"])} faces of slim frame, roof and rear wall; '
          f'{len(glass)} glass triangles in {sum(len(v) for v in PANES.values())} flat panes')
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
