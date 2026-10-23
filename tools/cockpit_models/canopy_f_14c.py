#!/usr/bin/env python3
"""F-14C Tomcat canopy and windscreen, hand-built for the F-14C.mtb airframe (commit 91141440): glass and frames on the
real F-14's layout, replacing the model's own frame-only arches.

The real Tomcat (the F-14A/B/D share it; the F-14C was a never-built F-14B with new avionics, so nothing about the canopy
changes) has ONE rigid windscreen frame carrying a flat bulletproof centre pane between two flat side panes, and ONE
blown (curved) clamshell canopy over both crew, hinged at the rear, with NO frame member between the pilot and the RIO
(owner's rule for this pass; the references found were ambiguous, so: Decision taken: no mid bow. The model's mid arch at z 3.575
was the old model's frame-only arch and is cut out and not replaced). The windscreen's rear arch IS the canopy's front bow:
one member.

  * windscreen: a flat centre pane (|x| <= 0.145 between two slim posts, sloping 23 degrees from the arch down to the deck)
    and a flat side pane each side (a plane through the posts' outer edge dropping 1.2 per block of x outward, so each is a
    triangle-like wedge tapering to the deck). Every pane stops at its member's edge: the arch behind, the posts between,
    a sill strip along the outside and a base bar at the deck;
  * canopy glass: one blown shell in one unbroken piece from the front bow to the rear bow, a super-ellipse section from the
    sill rail's top up to a crown; the rear ends on a closed rear bow (the hinge
    fairing) that stands on the model's own spine steps, so the canopy rear seals on the fuselage;
  * frames (bone frame_bars__canopy, all closed solids, 4 to 5 cm): the sill rails, the front arch (windscreen arch + canopy
    front bow, ruled between the glass edges it joins), the rear bow, the two posts, the side sill strips and
    the base bar.

Model numbers (NOTES.md section 7 of work/elite_expansion_2/f14c): sill y 2.625 (the model's rail foot), roof y 3.41, half
width 0.48, front arch z 5.06, mid arch z 3.575, rear bows z 1.74 .. 1.52. The model's own frame (part 17: the arch with its
posts, the second front arch layer, the roof strips and the sill rails, the mid arch and the rear bows) is cut out of the two
hull pieces by face runs (CUT_RUNS, each found by its bounding box); everything under the sill (the skin, the sill decks, the
spine steps behind the canopy) stays, so the exterior is as it was.

CANOPY (eye-relative sections of the pilot's eye, right half from the sill up over the top) is what the checks in
work/elite_expansion_2/checks read. Frames: vehicle-local blocks (x left, y up, z forward); the mesh is written in model
pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script f_14c.py then appends the HUD combiner glass to CanopyGlass.
Usage: python tools/cockpit_models/canopy_f_14c.py [--dry-run]"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/f_14c.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/f_14c.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/f_14c.json')

BARS_BONE = 'frame_bars__canopy'          # (the name ends in __canopy: the checks count it as the canopy's own)
H0, H1 = 'wreck_fuselage_0__hull', 'wreck_fuselage_1__hull'
UV_FRAME = [0.35730, 0.02185]             # a texel of the model's own frame colour (dark grey, texture cell x 1464, y 4007)

# ---- the model's frame, cut out: (bone, first face, last face, box lo, box hi). The box is checked before a run is cut.
CUT_RUNS = [(H0, 54, 101, (0.0, 2.68, 5.018), (0.394, 3.183, 6.068)),            # right arch half, its posts down to the deck
            (H0, 156, 203, (-0.394, 2.68, 5.018), (0.0, 3.183, 6.068)),          # left
            (H0, 1070, 1161, (-0.481, 2.625, 4.537), (0.481, 3.325, 5.117)),     # the second front arch layer and the roof strip
            (H1, 1967, 2146, (-0.481, 2.625, 1.518), (0.481, 3.412, 4.974))]     # sill rails, mid arch, rear bows, roof strips

# ---- the canopy
SILL_Y = 2.82                  # the sill rail's top: the glass foot, and where the checks' outline starts
Z_REAR = 1.745                 # the glass ends on the rear bow's front face
Z_ARCH = (5.070, 5.120)        # the windscreen arch = the canopy front bow
Z_REAR_BACK = 1.620            # the rear bow (a closed fairing) stands from here to Z_REAR
NEXP = 2.4                     # the sections' super-ellipse exponent
CROWN = [(1.745, 3.170), (2.00, 3.250), (2.45, 3.340), (3.00, 3.395), (3.575, 3.420), (4.10, 3.395), (4.55, 3.330),
         (5.07, 3.190)]        # the crown's height over the centre line


def xb(z):                     # the glass foot's half width
    return float(np.interp(z, [Z_REAR, 4.40, Z_ARCH[0]], [0.455, 0.455, 0.430]))


def crown(z):
    return float(np.interp(z, [c[0] for c in CROWN], [c[1] for c in CROWN]))


def section(z, n=22):
    """right half-section at station z: absolute (x, y) from the foot up to the crown (x = 0)"""
    x0, y0, yt = xb(z), SILL_Y, crown(z)
    us = np.linspace(0, 1, n)
    pts = [(x0 * (1 - u ** NEXP) ** (1 / NEXP), y0 + (yt - y0) * u) for u in us]
    pts[-1] = (0.0, yt)
    return pts


# ---- the windscreen: three flat panes between slim members
WS_Z = (Z_ARCH[1], 6.030)      # the panes run from the arch's front face to the base bar
BASE_Z = (6.030, 6.070)        # the base bar at the deck
YC0, YC_SLOPE = 3.165, -0.4316  # the centre pane's plane: y = YC0 + YC_SLOPE * (z - 5.12) (flat: independent of x)
POST_IN, POST_OUT = 0.145, 0.190
SIDE_SLOPE = 1.2               # the side panes' plane drops 1.2 per block of |x| out from the posts' outer edge
DECK_Y = 2.765                 # where the side panes end at the deck (the deck block's top is 2.76)
SIDE_W = 0.04                  # the sill strips along the side panes' outer edges


def yc(z):
    return YC0 + YC_SLOPE * (z - Z_ARCH[1])


def ys(x, z):                  # the side plane at |x|
    return yc(z) - SIDE_SLOPE * (x - POST_OUT)


def x_out(z):                  # the side pane's outer edge: the foot's line, then the plane's meeting with the deck
    return min(xb(Z_ARCH[1]), POST_OUT + (yc(z) - DECK_Y) / SIDE_SLOPE)


def outline_rel(eye):
    """the canopy's outline the checks read: from the sill line up the glass; the windscreen's stations run from the side
    pane's outer edge over the pane and the post to the centre pane"""
    stations = [1.60, 1.62, 1.745, 2.0, 2.4, 2.8, 3.2, 3.55, 3.60, 4.0, 4.4, 4.8, 5.07, 5.12, 5.3, 5.5, 5.7, 5.9, 6.07]
    out = []
    for z in stations:
        if z <= Z_ARCH[0] + 1e-9:
            pts = section(max(z, Z_REAR))        # (behind the glass: the rear bow's own outline, to the model's rear legs)
        else:
            zz = min(z, WS_Z[1])
            xo = x_out(zz)
            pts = [(xo, ys(xo, zz)), (POST_OUT - 0.003, yc(zz)), (0.0, yc(zz))]
            if pts[0][1] > SILL_Y + 0.004:
                pts = [(pts[0][0], SILL_Y)] + pts
        out.append((z - eye[2], [(x, y - eye[1]) for x, y in pts]))
    return out


def _eye():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        s = d['Seats'][0]
        return np.array(d['Attachments'][s['CameraPos']['EyeAttachment']]['Position'], float)
    except (OSError, KeyError):
        return np.array([0.0, 2.8875, 4.5])


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


def windscreen_glass(nz=10):
    out = []
    # the centre pane: a flat strip between the posts' inner edges
    zs = np.linspace(WS_Z[0], WS_Z[1], nz + 1)
    for z0, z1 in zip(zs[:-1], zs[1:]):
        a, b = np.array([-POST_IN, yc(z0), z0]), np.array([POST_IN, yc(z0), z0])
        c, d = np.array([POST_IN, yc(z1), z1]), np.array([-POST_IN, yc(z1), z1])
        out += [[a, b, c], [a, c, d]]
    # each side pane: the flat polygon between the posts' outer edge, the arch's front face, the outer edge and the base bar
    for side in (1, -1):
        zk = [WS_Z[0]]
        z_turn = Z_ARCH[1] + (YC0 - SIDE_SLOPE * (xb(Z_ARCH[1]) - POST_OUT) - DECK_Y) / (-YC_SLOPE)
        zk += [z_turn] + list(np.linspace(z_turn, WS_Z[1], 7)[1:])
        poly = [np.array([side * POST_OUT, ys(POST_OUT, WS_Z[0]), WS_Z[0]])]
        for z in zk:
            xo = x_out(z)
            poly.append(np.array([side * xo, ys(xo, z), z]))
        poly.append(np.array([side * POST_OUT, ys(POST_OUT, WS_Z[1]), WS_Z[1]]))
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
        # the mesh loader mirrors X and takes (p1 - p0) x (p2 - p0) of the mirrored points as the face normal: in file pixels a
        # face winds clockwise seen from its front (the hull's own faces do the same)
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

    def sweep(self, A, B, C, D, centre):
        """a bar between four polylines A (top, near), B (top, far), C (bottom, far), D (bottom, near); [centre] gives a point
        inside the bar at every segment; the open ends are left to the neighbouring member (or are buried)"""
        for i in range(len(A) - 1):
            ctr = np.mean([L[k] for L in (A, B, C, D) for k in (i, i + 1)], 0)       # (the segment's own centre)
            for p, q in ((A, B), (D, C), (A, D), (B, C)):
                self.quad([p[i], p[i + 1], q[i + 1], q[i]], ctr)

    def cap(self, pts, inside):
        self.quad(pts, inside)


def in_normals(pts, up_hint=np.array([0.0, 1.0])):
    """unit normal (x, y) of a half-section polyline at each point, pointing outward (up and out)"""
    n = []
    for k in range(len(pts)):
        a, c = np.array(pts[max(0, k - 1)], float), np.array(pts[min(len(pts) - 1, k + 1)], float)
        t = c - a
        t /= np.linalg.norm(t)
        nn = np.array([t[1], -t[0]])
        if nn[0] < -1e-9 or (abs(nn[0]) < 1e-9 and nn[1] < 0):      # outward is +x on the right half, up at the crown
            nn = -nn
        n.append(nn)
    return n


T_OUT, T_IN = 0.012, 0.040


def bow_bars(b, zc, half=0.025):
    """a transverse bow over the shell at station zc: 5 cm deep (z), 4 cm radially, standing 1.2 cm proud of the glass"""
    sec = section(zc)
    nrm = in_normals(sec)
    for side in (1, -1):
        def line(dz, rad, lift):
            return [np.array([side * (p[0] + n[0] * rad), p[1] + n[1] * rad + lift, zc + dz]) for p, n in zip(sec, nrm)]
        # the foot goes 1 cm into the rail
        A = line(-half, T_OUT, 0.0); B = line(half, T_OUT, 0.0); C = line(half, -T_IN, 0.0); D = line(-half, -T_IN, 0.0)
        for L in (A, B, C, D):
            L[0] = L[0] + np.array([0, -0.010, 0])
        ctr = [np.array([side * p[0] * 0.9, p[1] - 0.20, zc]) for p in sec]
        b.sweep(A, B, C, D, [np.array([side * 0.0, p[1] - 0.15, zc]) for p in sec])


def arch_bars(b):
    """the windscreen arch = canopy front bow: one member, ruled between the shell's end edge on its rear face (z 5.07) and the
    windscreen's top edge on its front face (z 5.12), so each glass stops exactly on it"""
    sec = section(Z_ARCH[0])
    xs = [p[0] for p in sec]
    nrm = in_normals(sec)
    zr, zf = Z_ARCH
    for side in (1, -1):
        A = [np.array([side * p[0], p[1] + T_OUT, zr]) for p in sec]                 # outer edge on the rear face
        Bf = []
        for x in xs:
            y = YC0 if x <= POST_OUT else ys(x, zf)
            Bf.append(np.array([side * x, y + T_OUT, zf]))
        C = [p - np.array([0, T_IN * 1.15, 0]) if abs(p[0]) < 0.30 else p - np.array([0, T_IN * 1.0, 0]) for p in Bf]
        D = [p - np.array([0, T_IN * 1.15, 0]) if abs(p[0]) < 0.30 else p - np.array([0, T_IN * 1.0, 0]) for p in A]
        for L in (A, Bf, C, D):
            L[0] = L[0] + np.array([0, -0.010, 0])
        b.sweep(A, Bf, C, D, [np.array([side * 0.0, p[1] - 0.15, 0.5 * (zr + zf)]) for p in A])


def rail_bars(b):
    """the sill rails: 7.5 cm wide, from 1 cm into the model's sill deck (top 2.76) up to the glass foot's level"""
    zs = np.array([Z_REAR, 2.4, 3.2, 4.0, 4.4, 4.75, Z_ARCH[0]])
    for side in (1, -1):
        def line(dx, y):
            return [np.array([side * (xb(z) + dx), y, z]) for z in zs]
        A, B = line(-0.045, SILL_Y), line(0.040, SILL_Y)
        C, D = line(0.040, 2.745), line(-0.045, 2.745)
        b.sweep(A, B, C, D, [np.array([side * xb(z), 2.79, z]) for z in zs])
        # the end faces
        for z, sg in ((zs[0], -1), (zs[-1], 1)):
            b.cap([np.array([side * (xb(z) - 0.045), SILL_Y, z]), np.array([side * (xb(z) + 0.040), SILL_Y, z]),
                   np.array([side * (xb(z) + 0.040), 2.745, z]), np.array([side * (xb(z) - 0.045), 2.745, z])],
                  np.array([side * xb(z), 2.79, z - sg * 0.1]))


def rear_bow(b):
    """the rear bow: the hinge fairing, a closed solid from the glass's last station back to Z_REAR_BACK; its outline is the
    shell's section there, its rear face drops 3 cm; it stands on the spine steps the model keeps behind the canopy"""
    z1, z0 = Z_REAR, Z_REAR_BACK
    s1 = section(z1)
    s0 = [(x * 0.985, y - 0.032 * (y - SILL_Y) / (s1[-1][1] - SILL_Y)) for x, y in s1]
    s0[0] = (s0[0][0], SILL_Y)
    for side in (1, -1):
        P1 = [np.array([side * x, y, z1]) for x, y in s1]
        P0 = [np.array([side * x, y, z0]) for x, y in s0]
        for i in range(len(P1) - 1):
            b.quad([P1[i], P0[i], P0[i + 1], P1[i + 1]], np.array([0, SILL_Y, 0.5 * (z0 + z1)]))
    # the two end walls: filled outlines (fans from the foot's mid point)
    for z, sg, s in ((z1, 1, s1), (z0, -1, s0)):
        ring = [np.array([x, y, z]) for x, y in s] + [np.array([-x, y, z]) for x, y in s[::-1]]
        c = np.array([0, SILL_Y + 0.05, z])
        for i in range(len(ring)):
            b.quad([c, ring[i], ring[(i + 1) % len(ring)]], np.array([0, SILL_Y + 0.05, 0.5 * (z0 + z1)]))


def windscreen_bars(b):
    zs = np.linspace(WS_Z[0], WS_Z[1], 9)
    # posts: 4.5 cm strips between the centre pane's edge and the side planes, on the glass line
    for side in (1, -1):
        top_in = [np.array([side * POST_IN, yc(z) + T_OUT, z]) for z in zs]
        top_out = [np.array([side * POST_OUT, yc(z) + T_OUT, z]) for z in zs]
        bot_out = [np.array([side * POST_OUT, yc(z) - 0.030, z]) for z in zs]
        bot_in = [np.array([side * POST_IN, yc(z) - 0.030, z]) for z in zs]
        b.sweep(top_in, top_out, bot_out, bot_in, [np.array([side * 0.5 * (POST_IN + POST_OUT), yc(z) - 0.008, z]) for z in zs])
        # the post's two end faces are buried in the arch and the base bar
    # the base bar at the deck: across the centre pane and the side panes' apexes
    x0 = POST_OUT + 0.012
    zb0, zb1 = BASE_Z
    A =[np.array([-x0, yc(zb0) + T_OUT, zb0]), np.array([x0, yc(zb0) + T_OUT, zb0])]
    B = [np.array([-x0, yc(zb1) + T_OUT, zb1]), np.array([x0, yc(zb1) + T_OUT, zb1])]
    C = [np.array([-x0, yc(zb1) - 0.050, zb1]), np.array([x0, yc(zb1) - 0.050, zb1])]
    D = [np.array([-x0, yc(zb0) - 0.050, zb0]), np.array([x0, yc(zb0) - 0.050, zb0])]
    b.sweep(A, B, C, D, [np.array([0, yc(0.5 * (zb0 + zb1)) - 0.015, 0.5 * (zb0 + zb1)])])
    for sx in (-1, 1):
        b.cap([A[0 if sx < 0 else 1], B[0 if sx < 0 else 1], C[0 if sx < 0 else 1], D[0 if sx < 0 else 1]],
              np.array([0, yc(0.5 * (zb0 + zb1)) - 0.015, 0.5 * (zb0 + zb1)]))
    # the side panes' sill strips: 4 cm along each pane's outer edge, in the pane's plane, with a skirt down to the deck
    for side in (1, -1):
        z_turn = Z_ARCH[1] + (YC0 - SIDE_SLOPE * (xb(Z_ARCH[1]) - POST_OUT) - DECK_Y) / (-YC_SLOPE)
        zk = [Z_ARCH[1], z_turn] + list(np.linspace(z_turn, WS_Z[1], 7)[1:])
        edge = []
        for z in zk:
            xo = x_out(z)
            edge.append(np.array([side * xo, ys(xo, z), z]))
        # in-plane outward direction at each edge point (the plane's gradient direction in x, down the slope)
        top_in, top_out, bot_out, bot_in, skirt = [], [], [], [], []
        for k, p in enumerate(edge):
            q = edge[min(k + 1, len(edge) - 1)] - edge[max(k - 1, 0)]
            tdir = q / np.linalg.norm(q)
            nplane = np.array([side * SIDE_SLOPE, 1.0, -YC_SLOPE])
            nplane /= np.linalg.norm(nplane)
            d = np.cross(tdir, nplane)
            if d[0] * side < 0:
                d = -d
            d /= np.linalg.norm(d)
            top_in.append(p + nplane * T_OUT)
            top_out.append(p + d * SIDE_W + nplane * T_OUT)
            bot_out.append(p + d * SIDE_W - nplane * 0.030)
            bot_in.append(p - nplane * 0.030)
        b.sweep(top_in, top_out, bot_out, bot_in, [(a + c) / 2 - np.array([0, 0.004, 0]) for a, c in zip(top_in, bot_out)])
        # the skirt: from the strip's outer edge straight down to the deck block
        for k in range(len(top_out) - 1):
            p0, p1 = top_out[k], top_out[k + 1]
            lo0 = np.array([p0[0], 2.745, p0[2]]); lo1 = np.array([p1[0], 2.745, p1[2]])
            if p0[1] - 2.745 > 0.002 or p1[1] - 2.745 > 0.002:
                b.quad([p0, p1, lo1, lo0], np.array([0, p0[1], p0[2]]))


def frame_bars():
    b = Bars(UV_FRAME)
    rail_bars(b)
    arch_bars(b)
    rear_bow(b)
    windscreen_bars(b)
    return b


# ---------------------------------------------------------------- the airframe

def find_run(P, polys, a, c, lo, hi, tol=0.012):
    """the first face of the run of c - a + 1 faces near index a whose bounding box is (lo, hi), or None"""
    n = c - a + 1
    mins = np.array([P[[v[0] for v in p]].min(0) if p else np.full(3, 9e9) for p in polys])
    maxs = np.array([P[[v[0] for v in p]].max(0) if p else np.full(3, -9e9) for p in polys])
    for s in sorted(range(max(0, a - 500), min(len(polys) - n, a + 500) + 1), key=lambda s: abs(s - a)):
        if np.all(np.abs(mins[s:s + n].min(0) - lo) < tol) and np.all(np.abs(maxs[s:s + n].max(0) - hi) < tol):
            return s
    return None


def cut_frame(bones, already):
    """removes the model's own frame (CUT_RUNS) from the hull pieces. A run that is not found stops the script unless an
    earlier run made the cut (the bars' bone is in the geo); returns the faces removed"""
    gone = {H0: set(), H1: set()}
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
        gone[name].update(range(s, s + c - a + 1))
    for hull in bones:
        if hull['name'] in gone and gone[hull['name']]:
            pm = hull['poly_mesh']
            pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in gone[hull['name']]]
    return sum(len(v) for v in gone.values())


def glass_triangles():
    tris = shell_patch(Z_REAR, Z_ARCH[0], 30)                  # one unbroken shell: rear bow -> front arch
    tris += windscreen_glass()                                 # the windscreen's three flat panes
    return [np.array(t) for t in tris]


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    already = any(b['name'] == BARS_BONE for b in bones)
    bones[:] = [b for b in bones if b['name'] != BARS_BONE]
    cut = cut_frame(bones, already)
    bars = frame_bars()
    hull = next(b for b in bones if b['name'] == H0)
    bones.insert(bones.index(hull) + 1, {'name': BARS_BONE, 'parent': hull.get('parent', 'hull'), 'pivot': list(hull['pivot']),
                                         'poly_mesh': bars.mesh()})
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'f_14c canopy: the model\'s frame cut out ({cut} faces), {len(bars.polys)} faces of slim frames; '
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
