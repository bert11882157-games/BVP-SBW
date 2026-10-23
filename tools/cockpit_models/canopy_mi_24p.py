#!/usr/bin/env python3
"""Mil Mi-24P canopies, hand-built on the Mi-24P.mtb airframe's own frame lines: TWO separate bubble canopies, the operator's in the
nose (low) and the pilot's behind and above it, each with ONE flat armoured windscreen, curved side blisters and roof, slim frames.

Measured off the untouched model (checks/orig/mi_24p.geo.json, vehicle blocks, x to the left, y up, z forward; 1 block = 0.87 m): the skin's
top edge at the cockpit rim (the sill, |x| 0.5 .. 0.62) stands at y 1.7 (z 7.6) .. 2.2 (z 6.6), 2.3 over the hump between the crews (z 6.3) and
rises to y 2.53 behind the pilot (z 4.7); the old frames were thick open skeletons (oval hoops at both windscreens, roof rails at y 2.75 over
the operator and 3.53 over the pilot, the pilot's rear wall raked from (z 4.71, y 3.06) to (z 5.15, y 3.52) under the engine fairing).
All of that, both seat groups, panels and the tub interior are cut out (CUT_RANGES, found by mi24p/gen_cut.py: faces and the elements
that are mostly in the cockpit column); the skin at and below the rim, the nose, the engine fairing and the floors stay.

Layout (checked against Mi-24V/P photographs from memory, UNVERIFIED against drawings: the pane sizes and the door layout):
  OPERATOR (front, low): one flat armoured windscreen leaning back 16 degrees (trapezoid, 0.68 wide at the deck, 0.54 at the top bow), a curved
  one-piece side blister and roof on each side (the whole canopy hinges to the RIGHT: hinge barrels on the right shoulder rail, a latch on the
  left sill), a rear arch against the cockpit hump.
  PILOT (rear, raised): a narrow flat armoured windscreen leaning back 37 degrees from the vertical, standing on the hump behind the operator,
  curved side blisters and roof; the DOOR is on the RIGHT (its outline in frame bars: sill, front post (the A pillar), rear post, header; two hinge
  barrels on the front post, a handle on the rear post); the left side is a fixed window. The rear arch lies on the engine fairing's raked front face.
  Frames are slim 3.6 cm round members, one polyline member per run (bows and the windscreen frames are single members, mitred),
  the glass stops 4 mm short of every member.

CANOPY (eye-relative sections of the union, the left half from the sill up over the top) is what the checks in work/elite_expansion_2/checks
read; the pilot's eye is the reference (seat 0).

Run first (on the airframe's files); the cockpit script mi_24p.py follows.
Usage: python tools/cockpit_models/canopy_mi_24p.py [--dry-run]"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402

VID = 'mi_24p'
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, f'assets/berts_vehicle_pack/custom_geo/{VID}.geo.json')
ASSET = os.path.join(GEN, f'assets/berts_vehicle_pack/sbw/vehicles/{VID}.json')
PANES_JSON = os.path.join(HERE, 'flat_panes', f'{VID}.json')
HULL_BONE = 'hull'
CANOPY_BONE = 'hull__canopy'

EYE_P = np.array([0.0, 3.2342, 5.2595])          # the pilot's eye (data: pilot_eye)
EYE_G = np.array([0.0, 2.5004, 7.0532])          # the operator's eye (data: gunner_eye)

# faces of the hull piece that the new canopies and cockpits replace (the untouched hull has HULL_FACES faces), from mi24p/gen_cut.py
CUT_RANGES = [
    (459, 459), (465, 465), (471, 471), (477, 477), (479, 480), (482, 486), (488, 489), (494, 494), (500, 503), (505, 506), (508, 510), (512, 512),
    (555, 558), (560, 560), (563, 563), (569, 569), (633, 642), (644, 648), (650, 654), (656, 656), (659, 659), (680, 680), (686, 686), (692, 692),
    (708, 708), (714, 714), (720, 720), (726, 726), (782, 784), (786, 786), (851, 851), (857, 857), (863, 863), (881, 885), (992, 992), (998, 998),
    (1004, 1047), (1317, 1317), (1319, 1319), (1321, 1325), (1327, 1328), (1330, 1333), (1375, 1434), (1453, 1455), (1457, 1497), (1499, 1500), (1502,
    1502), (2410, 2410), (2416, 2416), (2422, 2422), (2428, 2428), (2431, 2432), (2434, 2438), (2440, 2441), (2445, 2445), (2451, 2451), (2453, 2455),
    (2457, 2458), (2460, 2463), (2507, 2511), (2515, 2515), (2521, 2521), (2585, 2595), (2597, 2601), (2603, 2607), (2611, 2611), (2640, 2643), (2709,
    2713), (2820, 2820), (2826, 2826), (2832, 2875), (3163, 3222), (3241, 3243), (3245, 3264), (3711, 3908), (3912, 3912), (3918, 3918), (3924, 3924),
    (3945, 3946), (3948, 4063), (4072, 4072), (4078, 4078), (4082, 4084), (4086, 4108), (4110, 4279), (4282, 4282), (4288, 4288), (4294, 4294), (4298,
    4299), (4303, 4305), (4307, 4311), (4313, 4314), (4317, 4317), (4323, 4323), (4327, 4362), (4387, 4478), (4549, 4549), (4555, 4560), (4777, 4872),
    (5309, 5326), (5369, 5369), (5375, 5375), (5381, 5381), (5397, 5397), (5403, 5403), (5409, 5409), (5415, 5415), (5463, 5463), (5469, 5469), (5475,
    5475), (5489, 5489), (5491, 5491), (5493, 5495), (5499, 5499), (5523, 5642), (5644, 5690), (5692, 5696), (5698, 5702), (5704, 5713), (5715, 5718),
    (5720, 5784), (5797, 5797), (5799, 5803), (5805, 5805), (5807, 5811), (5813, 5814), (5821, 5821), (5823, 5827), (5829, 5829), (5831, 5835), (5837,
    5922), (8217, 8262)]
HULL_FACES = 8521

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
R_TUBE = 0.018
GLASS_GAP = 0.004
SLAB = 0.012


def unit(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def lerp(a, b, t):
    return np.array(a, float) * (1 - t) + np.array(b, float) * t


# ---------------------------------------------------------------- the two bubbles: rings of 6 points (the LEFT half, x >= 0), mirrored
NP = 6


def arc(xs, ys, yt, z, bulge=0.04, n=NP):
    """the half section of a blister: an ellipse arc from the sill point (xs, ys) out by [bulge], up over the shoulder to the top centre (0, yt)"""
    a = xs + bulge
    s0 = -math.sqrt(1 - (xs / a) ** 2)
    yc = (ys - s0 * yt) / (1 - s0)
    b = yt - yc
    phi0 = math.asin(s0)
    out = []
    for i in range(n):
        f = phi0 + (math.pi / 2 - phi0) * i / (n - 1)
        out.append([a * math.cos(f) if i < n - 1 else 0.0, yc + b * math.sin(f), z])
    return np.array(out)


# operator windscreen: a flat trapezoid on the nose deck, leaning back 18 degrees (measured: the old front hoop's foot (z 7.76, y 1.87), its
# top bow (z 7.49, y 2.50))
OB_BL = np.array([0.40, 1.70, 7.63])             # bottom outer corner (on the nose deck)
OB_TL = np.array([0.31, 2.56, 7.38])             # top outer corner
OB_TM = np.array([0.0, 2.56, 7.38])
# pilot windscreen: a flat trapezoid standing on the hump behind the operator, leaning back 41 degrees from the horizontal
# (measured: the old frame from (z 6.34, y 2.88) to (z 5.85, y 3.46))
PB_S0 = np.array([0.62, 2.30, 6.34])             # the side window's front foot on the sill (the A pillar runs from here to the pane's corner)
PB_BL = np.array([0.40, 2.88, 6.34])
PB_TL = np.array([0.34, 3.54, 5.84])
PB_TM = np.array([0.0, 3.54, 5.84])
DECK_OP = 1.66                                   # the nose deck's top at the operator's windscreen


def ring0_ob():
    return np.array([OB_BL, lerp(OB_BL, OB_TL, 0.5), OB_TL, lerp(OB_TL, OB_TM, 0.34), lerp(OB_TL, OB_TM, 0.67), OB_TM])


def ring0_pb():
    return np.array([PB_S0, PB_BL, lerp(PB_BL, PB_TL, 0.5), PB_TL, lerp(PB_TL, PB_TM, 0.5), PB_TM])


def rear_ring():
    """the pilot's rear arch lies on the engine fairing's sloping front face (z 4.72 up to the sill's rear wall, then raked to z 5.16 at the roof)"""
    r = arc(0.60, 2.53, 3.52, 4.72)
    for p in r:
        p[2] = 4.72 + 0.96 * max(0.0, p[1] - 3.06)
    return r


_P4 = rear_ring()
_P2 = arc(0.62, 2.43, 3.54, 5.38)
OB_RINGS = [ring0_ob(), arc(0.52, 1.96, 2.68, 7.20), arc(0.56, 2.05, 2.76, 6.92), arc(0.59, 2.13, 2.78, 6.74), arc(0.60, 2.20, 2.79, 6.58)]
PB_RINGS = [ring0_pb(), arc(0.62, 2.38, 3.54, 5.62), _P2, lerp(_P2, _P4, 0.5), _P4]
S33 = lerp(PB_S0, PB_BL, 1 / 3)
S66 = lerp(PB_S0, PB_BL, 2 / 3)
TIE_FAR = np.array([PB_S0, S33, S66, PB_BL, [0.20, 2.88, 6.34], [0.0, 2.88, 6.34]])
OB_BOWS = [4]
PB_BOWS = [3, 4]
Z_END = 4.72

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
    """a straight round member from a to b (ends run on by r so joints overlap)"""
    a, b = np.array(a, float), np.array(b, float)
    t = unit(b - a)
    a, b = a - t * r, b + t * r
    ref = Y if abs(t @ Y) < 0.9 else X
    u = unit(np.cross(t, ref))
    v = unit(np.cross(t, u))
    ring = lambda p: [p + r * (u * math.cos(2 * math.pi * j / seg) + v * math.sin(2 * math.pi * j / seg)) for j in range(seg)]
    A_, B_ = ring(a), ring(b)
    for j in range(seg):
        j2 = (j + 1) % seg
        q = [A_[j], A_[j2], B_[j2], B_[j]]
        m.face(q, uv, np.mean(q, 0) - (a + b) / 2)
    m.face(A_, uv, -t)
    m.face(B_, uv, t)


def polytube(m, pts, r, uv, seg=8):
    """ONE member along a polyline: the cross-section at every vertex lies in the plane that bisects the two segments (mitred joints)"""
    P = [np.array(p, float) for p in pts]
    n = len(P)
    tang = []
    for i in range(n):
        if i == 0:
            t = unit(P[1] - P[0])
        elif i == n - 1:
            t = unit(P[-1] - P[-2])
        else:
            t = unit(unit(P[i] - P[i - 1]) + unit(P[i + 1] - P[i]))
        tang.append(t)
    rings = []
    ref0 = Y if abs(tang[0] @ Y) < 0.9 else X
    u_prev = unit(np.cross(tang[0], ref0))
    for i in range(n):
        t = tang[i]
        u = u_prev - t * (u_prev @ t)
        if np.linalg.norm(u) < 1e-6:
            u = unit(np.cross(t, Y if abs(t @ Y) < 0.9 else X))
        u = unit(u)
        v = unit(np.cross(t, u))
        u_prev = u
        ring = [P[i] + r * (u * math.cos(2 * math.pi * j / seg) + v * math.sin(2 * math.pi * j / seg)) for j in range(seg)]
        rings.append(ring)
    for i in range(n - 1):
        for j in range(seg):
            j2 = (j + 1) % seg
            q = [rings[i][j], rings[i][j2], rings[i + 1][j2], rings[i + 1][j]]
            m.face(q, uv, np.mean(q, 0) - (P[i] + P[i + 1]) / 2)
    m.face(rings[0], uv, -tang[0])
    m.face(rings[-1], uv, tang[-1])


def box(m, centre, axes, half, uv):
    c = np.array(centre, float)
    A_ = [unit(a) for a in axes]
    for k in range(3):
        for s in (-1, 1):
            i, j = [q for q in range(3) if q != k]
            fc = c + A_[k] * half[k] * s
            m.face([fc - A_[i] * half[i] - A_[j] * half[j], fc + A_[i] * half[i] - A_[j] * half[j],
                    fc + A_[i] * half[i] + A_[j] * half[j], fc - A_[i] * half[i] + A_[j] * half[j]], uv, A_[k] * s)


def plane_normal(poly3):
    P = np.array(poly3, float)
    nn = np.zeros(3)
    for i in range(len(P)):
        a, b = P[i], P[(i + 1) % len(P)]
        nn += np.array([(a[1] - b[1]) * (a[2] + b[2]), (a[2] - b[2]) * (a[0] + b[0]), (a[0] - b[0]) * (a[1] + b[1])])
    return unit(nn)


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


def slab_poly(m, poly, normal, uv_out, uv_in):
    P = [np.array(p, float) for p in poly]
    n = unit(normal)
    m.face(P, uv_out, n)
    m.face([p - n * SLAB for p in P][::-1], uv_in, -n)
    cen = np.mean(P, 0)
    for i in range(len(P)):
        a, b = P[i], P[(i + 1) % len(P)]
        out = unit(np.cross(b - a, n))
        if out @ ((a + b) / 2 - cen) < 0:
            out = -out
        m.face([a, b, b - n * SLAB, a - n * SLAB], uv_out, out)


def warped_quad(m, q, cen, uv_out, uv_in):
    """a (not exactly planar) quad as two triangles, each facing away from cen, with a dark lining a slab's thickness inside"""
    for tri in ((q[0], q[1], q[2]), (q[0], q[2], q[3])):
        n = np.cross(tri[1] - tri[0], tri[2] - tri[0])
        if np.linalg.norm(n) < 1e-12:
            continue
        n = unit(n)
        if n @ (np.mean(tri, 0) - cen) < 0:
            n = -n
        m.face(list(tri), uv_out, n)
        m.face([p - n * SLAB for p in tri][::-1], uv_in, -n)


def mirror_pts(P):
    return np.array(P, float) * np.array([-1, 1, 1])


# ---------------------------------------------------------------- loft surfaces between rings (left half; mirrored for the right)

class Loft:
    """rings: list of (NP x 3) arrays from front to rear; surface(u, v): u in [0, NP-1] along the profile (sill to top), v in [0, len-1] along z"""

    def __init__(self, rings):
        self.R = [np.array(r, float) for r in rings]

    def pt(self, u, v):
        k = int(min(len(self.R) - 2, math.floor(v)))
        f = v - k
        i = int(min(NP - 2, math.floor(u)))
        g = u - i
        a = lerp(self.R[k][i], self.R[k][i + 1], g)
        b = lerp(self.R[k + 1][i], self.R[k + 1][i + 1], g)
        return lerp(a, b, f)

    def seg_len_u(self, i, v):
        return float(np.linalg.norm(self.pt(i + 1, v) - self.pt(i, v)))

    def patch(self, u0, u1, v0, v1, nu_extra=0):
        """triangles of the sub-surface u0..u1, v0..v1 (sampled at every ring and profile point inside)"""
        us = sorted(set([u0, u1] + [float(i) for i in range(int(math.ceil(u0 + 1e-9)), int(math.floor(u1 - 1e-9)) + 1)]))
        vs = sorted(set([v0, v1] + [float(k) for k in range(int(math.ceil(v0 + 1e-9)), int(math.floor(v1 - 1e-9)) + 1)]))
        tris = []
        for a, b in zip(vs[:-1], vs[1:]):
            for c, d in zip(us[:-1], us[1:]):
                p00, p10, p11, p01 = self.pt(c, a), self.pt(d, a), self.pt(d, b), self.pt(c, b)
                tris.append([p00, p10, p11])
                tris.append([p00, p11, p01])
        return tris


def dv_for(loft, u, v, dist, direction):
    """the v offset that moves [dist] along the z direction from ring line v (direction +1 toward the rear)"""
    k = int(min(len(loft.R) - 2, math.floor(v if direction > 0 else v - 1e-9)))
    L_ = float(np.linalg.norm(loft.pt(u, k + 1) - loft.pt(u, k)))
    return dist / max(L_, 1e-6)


def du_for(loft, i, v, dist):
    return dist / max(loft.seg_len_u(i, v), 1e-6)


# ---------------------------------------------------------------- the pieces
def tri_of_poly(poly):
    return [[poly[0], poly[k], poly[k + 1]] for k in range(1, len(poly) - 1)]


def window_ring(rings, k):
    """the whole bow at ring k as one polyline from the left sill over the top to the right sill"""
    return [p for p in rings[k]] + [mirror_pts(p) for p in rings[k][::-1][1:]]


def outline_at(z):
    """the canopies' union section at station z: the left half from the sill up to the top at x = 0, as [(x, y), ...] (None if not covered)"""
    def ring_sec(rings, zq, z0):
        zs = [z0] + [float(np.mean(r[:, 2])) for r in rings[1:]]
        if zq > zs[0] or zq < zs[-1]:
            return None
        for i in range(len(rings) - 1):
            if zs[i] >= zq >= zs[i + 1]:
                f = (zs[i] - zq) / max(zs[i] - zs[i + 1], 1e-9)
                return [(float(p[0]), float(p[1])) for p in lerp(rings[i], rings[i + 1], f)]
        return None
    if z > OB_BL[2]:
        return None
    if OB_TL[2] < z <= OB_BL[2]:
        f = (OB_BL[2] - z) / (OB_BL[2] - OB_TL[2])
        y = OB_BL[1] + f * (OB_TL[1] - OB_BL[1])
        x = OB_BL[0] + f * (OB_TL[0] - OB_BL[0])
        return [(x, DECK_OP), (x, y), (0.0, y)]
    if z <= OB_TL[2] and z >= OB_RINGS[-1][0][2]:
        return ring_sec(OB_RINGS, z, OB_TL[2])
    if PB_BL[2] < z < OB_RINGS[-1][0][2]:
        f = (OB_RINGS[-1][0][2] - z) / (OB_RINGS[-1][0][2] - PB_BL[2])
        return [(float(p[0]), float(p[1])) for p in lerp(OB_RINGS[-1], TIE_FAR, f)]
    if PB_TL[2] < z <= PB_BL[2]:
        f = (PB_BL[2] - z) / (PB_BL[2] - PB_TL[2])
        y = PB_BL[1] + f * (PB_TL[1] - PB_BL[1])
        x = PB_BL[0] + f * (PB_TL[0] - PB_BL[0])
        return [(PB_S0[0], PB_S0[1]), (x, y), (0.0, y)]
    zmin = float(np.mean(PB_RINGS[-1][:, 2]))
    if z >= zmin:
        return ring_sec(PB_RINGS, z, PB_TL[2])
    if Z_END <= z < zmin:
        return [(float(p[0]), float(p[1])) for p in PB_RINGS[-1]]
    return None


def sections_rel():
    out = []
    zs = [Z_END + 0.005, 4.85, 5.00, 5.15, 5.30, 5.45, 5.60, 5.75, 5.85, 5.95, 6.10, 6.25, 6.34, 6.40, 6.50, 6.58, 6.70, 6.85, 7.05, 7.20, 7.35, 7.47, 7.55, 7.63, 7.72]
    for z in zs:
        sec = outline_at(z)
        if sec is None:
            continue
        out.append((z - EYE_P[2], [(max(0.0, x), y - EYE_P[1]) for x, y in sec]))
    return out


CANOPY = sections_rel()
PANES = {}


def flat_pane(name, bl, tl):
    quad = [bl, mirror_pts(bl), mirror_pts(tl), tl]
    n = plane_normal(quad)
    g = inset_polygon(quad, n, R_TUBE + GLASS_GAP)
    PANES[name] = [g]
    return tri_of_poly(list(g))


def blister_glass(rings, bows, door_side=None, door_bays=None):
    """curved side and roof glass: one lofted bay between neighbouring bows per side; the door side (x < 0) is split at the header (u = 3)
    in the bays listed in door_bays"""
    loft = Loft(rings)
    d = R_TUBE + GLASS_GAP
    last = len(rings) - 1
    bw = sorted(set([0] + list(bows) + [last]))
    out = []
    for s in (1, -1):
        sg = np.array([s, 1, 1])
        for a, b in zip(bw[:-1], bw[1:]):
            va = a + dv_for(loft, 0.5, a, d, +1)
            vb = b - dv_for(loft, 0.5, b - 1e-6, d, +1)
            u0 = du_for(loft, 0, a, d)
            if door_side == s and (a, b) in (door_bays or []):
                u3 = 3.0
                parts = [(u0, u3 - du_for(loft, 2, a, d)), (u3 + du_for(loft, 3, a, d), NP - 1.0)]
            else:
                parts = [(u0, NP - 1.0)]
            for ua, ub in parts:
                for t in loft.patch(ua, ub, va, vb):
                    out.append([p * sg for p in t])
    return out


def frames(m, uv_frame):
    # the two windscreen frames: one closed member round each pane
    for bl, tl in ((OB_BL, OB_TL), (PB_BL, PB_TL)):
        polytube(m, [bl, tl, mirror_pts(tl), mirror_pts(bl), bl], R_TUBE, uv_frame)
    for rings, bows in ((OB_RINGS, OB_BOWS), (PB_RINGS, PB_BOWS)):
        for k in bows:
            polytube(m, window_ring(rings, k), R_TUBE, uv_frame)
        for s in (1, -1):                                       # sill rails
            polytube(m, [r[0] * np.array([s, 1, 1]) for r in rings], R_TUBE, uv_frame)
    for s in (1, -1):                                           # the pilot's A pillars
        tube(m, PB_S0 * np.array([s, 1, 1]), PB_BL * np.array([s, 1, 1]), R_TUBE, uv_frame)
    # the pilot's door, on the RIGHT (x < 0): the header along the shoulder (u = 3) from the A pillar to the rear post (ring 3 is the post)
    polytube(m, [PB_RINGS[k][3] * np.array([-1, 1, 1]) for k in range(0, 4)], R_TUBE, uv_frame)
    # hinges on the A pillar and the latch on the rear post
    for t in (0.22, 0.78):
        p = lerp(PB_S0, PB_BL, t) * np.array([-1, 1, 1])
        tube(m, p + np.array([-0.020, 0, -0.045]), p + np.array([-0.020, 0, 0.045]), 0.012, uv_frame)
    p = PB_RINGS[3][1] * np.array([-1, 1, 1])
    box(m, p + np.array([-0.020, -0.02, 0.0]), (X, Y, Z), (0.012, 0.030, 0.012), uv_frame)
    # the operator's canopy hinges to the RIGHT: hinge barrels on the right shoulder rail, a latch on the left sill
    for k in (1, 3):
        p = OB_RINGS[k][1] * np.array([-1, 1, 1])
        tube(m, p + np.array([-0.020, 0.0, -0.05]), p + np.array([-0.020, 0.0, 0.05]), 0.012, uv_frame)
    p = OB_RINGS[2][0]
    box(m, p + np.array([0.022, 0.030, 0.0]), (X, Y, Z), (0.010, 0.020, 0.040), uv_frame)


def build(uv_frame):
    m = Mesh()
    glass = []
    glass += flat_pane('operator windscreen', OB_BL, OB_TL)
    glass += flat_pane('pilot windscreen', PB_BL, PB_TL)
    glass += blister_glass(OB_RINGS, OB_BOWS)
    glass += blister_glass(PB_RINGS, PB_BOWS, door_side=-1, door_bays=[(0, 3)])
    frames(m, uv_frame)
    return m, glass


def hump(m, uv_out, uv_in):
    """the opaque hump between the crews: from the operator's rear arch up to the pilot's windscreen foot"""
    cen = np.array([0.0, 2.4, 6.46])
    for s in (1, -1):
        sg = np.array([s, 1, 1])
        for j in range(NP - 1):
            q = [OB_RINGS[-1][j] * sg, OB_RINGS[-1][j + 1] * sg, TIE_FAR[j + 1] * sg, TIE_FAR[j] * sg]
            warped_quad(m, q, cen, uv_out, uv_in)


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
    """one entry per windscreen: its glass triangles are selected one by one (a box round each centroid, so the gunsight glass inside the
    pane's bounding box is not taken for part of it), and all are fitted to one plane together"""
    out = []
    for name, polys in sorted(PANES.items()):
        boxes = []
        for g in polys:
            for k in range(1, len(g) - 1):
                c = (g[0] + g[k] + g[k + 1]) / 3
                boxes.append([[round(float(v) - 0.0004, 4) for v in c], [round(float(v) + 0.0004, 4) for v in c]])
        out.append({'name': name, 'mirror': False, 'boxes': boxes})
    return {'why': ('Mi-24P canopies: two separate bubbles, each with ONE flat armoured windscreen (operator: lower, leaning back 16 degrees from the vertical; '
                    'pilot: raised on the hump, leaning back 37 degrees from the vertical). Only these two windscreens are flat on the real '
                    'helicopter; the side blisters and the roofs of both canopies are curved (lofted, not listed). Pane sizes UNVERIFIED '
                    'against drawings (canopy_mi_24p.py).'),
            'panes': out}


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    bones[:] = [b for b in bones if b['name'] != CANOPY_BONE]
    cut = cut_old(bones)
    sw = L.Swatches(L.texture_path(VID))
    uv_out, uv_in, uv_frame = sw.uv((128, 128, 96)), sw.uv((24, 26, 22)), sw.uv((46, 52, 40))
    m, glass = build(uv_frame)
    hump(m, uv_out, uv_in)
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    pm = m.poly_mesh()
    bones.insert(bones.index(hull) + 1, {'name': CANOPY_BONE, 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm})
    both = [list(map(float, p)) for t in glass for p in t] + [list(map(float, t[k])) for t in glass for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'{VID} canopy: {cut} old interior and frame faces cut, {len(pm["polys"])} faces of frame and hump; '
          f'{len(glass)} glass triangles; swatch error {sw.worst:.0f}')
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