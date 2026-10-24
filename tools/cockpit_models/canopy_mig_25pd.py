#!/usr/bin/env python3
"""MiG-25PD canopy and windscreen, hand-built for the MiG-25.mtb airframe (commit 61803c19): glass and slim frames on the
real MiG-25P/PD layout, replacing the model's own frame-only canopy.

The real MiG-25P/PD (type photos, the ICM / Hasegawa / Revell kit canopies and the cockpit walk-arounds; no drawing
with dimensions was found, so every number below is the model's own line, not a measurement of the real jet): a
short, low cockpit under ONE blown transparency that is hinged at the rear and lifts up and back, a fixed faired
section behind it (the hinge fairing, carried by the spine), and ahead of the canopy a fixed windscreen frame whose
front pane is FLAT, thick and armoured, steeply raked, narrow (the two posts stand about 29 cm apart), with a small
flat quarter pane each side of it tapering down to the nose deck. The windscreen's arch IS the canopy's front bow: one
member where the two glasses meet.

  * canopy glass: one unbroken blown shell (a super-ellipse section from the sill rail up to a crown) from the rear
    bow, which stands just ahead of the fairing's front face (z 4.441), to the front bow; the section follows the
    fairing's own stepped outline at the rear and narrows with the fuselage forward;
  * windscreen: a flat centre pane between two slim posts (|x| <= 0.145, one plane sloping from the front bow down to
    the base bar on the deck) and a flat quarter pane each side (a plane through the posts' outer edge dropping 2.7 per
    block of |x|, so it is a wedge that tapers to the deck). Every pane stops at its member's edge: the arch behind, the
    posts between, a sill strip along the outside and the base bar at the deck;
  * frames (bone wreck_fuselage_0__canopy, closed solids, 4.5 to 6.5 cm): the two sill rails, the rear bow, the front
    bow / windscreen arch, the two posts, the side sill strips and the base bar. No centre rib: the view ahead is open.

Model numbers (NOTES.md section 6 of work/elite_expansion_2/mig25, rechecked on the faces): fairing front face z 4.441 with
its stepped outline (0.452, 2.938) (0.407, 3.029) (0.271, 3.21) (0.09, 3.30) (0, 3.345); the fuselage's sill ridge runs
(0.43, 2.938) at z 4.441 down to (0.345, 2.848) at z 5.30; the model's spine fell from 3.345 (z 4.44) to 3.17 (z 5.39),
which is below the pilot's eye line (3.164) at the bow, so the crown is lifted to 3.26 there (the glass must stand above
the straight-ahead line). The model's own frame (spine, bow, windscreen rails, side sill rails: 4 runs of hull_0) is cut
out by face runs (CUT_RUNS, each found by its bounding box); everything under the sill (skin, nose deck) stays.

CANOPY (eye-relative sections, the right half from the sill up over the top, windscreen included) is what the checks in
work/elite_expansion_2/checks read. Frames: vehicle-local blocks (x left, y up, z forward); meshes are written in model
pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script mig_25pd.py then appends the sight glass to CanopyGlass.
Usage: python tools/cockpit_models/canopy_mig_25pd.py [--dry-run]"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/mig_25pd.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/mig_25pd.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/mig_25pd.json')

BARS_BONE = 'wreck_fuselage_0__canopy'    # (the airframe's empty canopy bone: the frames break away with the nose piece)
H0 = 'wreck_fuselage_0__hull'
UV_FRAME = [0.13745, 0.02405]             # a texel of the model's own dark grey (62, 62, 62), texture cell x 563, y 3997

# ---- the model's frame, cut out: (bone, first face, last face, box lo, box hi). The box is checked before a run is cut.
CUT_RUNS = [(H0, 42, 65, (0.136, 2.803, 4.441), (0.452, 3.029, 6.204)),         # right canopy side rail, windscreen side sill
            (H0, 102, 173, (0.0, 2.803, 4.441), (0.362, 3.345, 6.250)),         # right spine, front bow half, windscreen rail, base strip
            (H0, 204, 227, (-0.452, 2.803, 4.441), (-0.136, 3.029, 6.204)),     # left
            (H0, 264, 335, (-0.362, 2.803, 4.441), (0.0, 3.345, 6.250))]

# ---- the canopy
Z_FAIR = 4.441                            # the hinge fairing's front face
Z_RB = (4.446, 4.486)                     # rear bow (4 cm, 5 mm clear of the fairing)
Z_ARCH = (5.3275, 5.3725)                 # front bow = windscreen arch
NEXP0, NEXP1 = 1.7, 2.3                   # the sections' super-ellipse exponent, rear to front
CROWN = [(4.441, 3.345), (4.76, 3.335), (5.03, 3.315), (5.20, 3.290), (5.35, 3.260)]   # the crown's height over the centre line


def foot_x(z):                            # the glass foot's half width: the fuselage's sill ridge
    return float(np.interp(z, [Z_FAIR, 5.35], [0.440, 0.345]))


def foot_y(z):                            # the sill ridge's height
    return float(np.interp(z, [Z_FAIR, 5.35], [2.938, 2.848]))


def crown(z):
    return float(np.interp(z, [c[0] for c in CROWN], [c[1] for c in CROWN]))


def section(z, n=24):
    """right half-section at station z: absolute (x, y) from the foot up to the crown (x = 0)"""
    z = min(max(z, Z_FAIR), 5.35)
    x0, y0, yt = foot_x(z), foot_y(z), crown(z)
    e = float(np.interp(z, [Z_FAIR, 5.35], [NEXP0, NEXP1]))
    us = np.linspace(0, 1, n)
    pts = [(x0 * (1 - u ** e) ** (1 / e), y0 + (yt - y0) * u) for u in us]
    pts[-1] = (0.0, yt)
    return pts


# ---- the windscreen: a flat centre pane and two flat quarter panes between slim members
Z_WS = (Z_ARCH[1], 6.190)                 # the panes run from the arch's front face to the base bar's rear face
Z_BASE = (6.190, 6.235)                   # the base bar on the deck
POST_IN, POST_OUT = 0.145, 0.190
SIDE_SLOPE = 2.7                          # the quarter panes' plane drops 2.7 per block of |x| out from the posts' outer edge
SIDE_W = 0.035                            # the sill strips along the quarter panes' outer edges
DECK_Y = 2.745                            # the skirts end buried in the deck block (its top is 2.80 to 2.85)


def yc(z):                                # the centre pane's plane (flat: independent of x)
    return 3.260 - 0.523 * (z - 5.35)


def ys(x, z):                             # the quarter pane's plane at |x|
    return yc(z) - SIDE_SLOPE * (x - POST_OUT)


def x_out(z):                             # the quarter pane's outer edge: along the fuselage's side sill, narrowing to the nose
    return 0.345 - 0.160 * (z - Z_WS[0]) / (Z_WS[1] - Z_WS[0])


def sill_y(z):                            # the outline's sill: the ridge, then the deck
    return foot_y(z) if z <= 5.35 else 2.848 - 0.048 * (z - 5.35) / 0.9


def outline_rel(eye):
    """the canopy's outline the checks read: from the sill line up the glass; the windscreen's stations run from the
    quarter pane's outer edge over the pane and the post to the centre pane"""
    stations = [4.36, 4.446, 4.496, 4.70, 4.90, 5.10, 5.30, 5.3275, 5.3725, 5.55, 5.75, 5.95, 6.10, 6.19]
    out = []
    for z in stations:
        if z <= Z_ARCH[0] + 1e-9:
            pts = section(max(z, Z_FAIR))
        else:
            xo = x_out(z)
            pts = [(xo, ys(xo, z)), (POST_OUT - 0.003, yc(z)), (0.0, yc(z))]
            if pts[0][1] > sill_y(z) + 0.004:
                pts = [(pts[0][0], sill_y(z))] + pts
        out.append((z - eye[2], [(x, y - eye[1]) for x, y in pts]))
    return out


def _eye():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        s = d['Seats'][0]
        return np.array(d['Attachments'][s['CameraPos']['EyeAttachment']]['Position'], float)
    except (OSError, KeyError):
        return np.array([0.0, 3.16444, 4.73527])


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
    zs = np.linspace(Z_WS[0], Z_WS[1], nz + 1)        # the centre pane: a flat strip between the posts' inner edges
    for z0, z1 in zip(zs[:-1], zs[1:]):
        a, b = np.array([-POST_IN, yc(z0), z0]), np.array([POST_IN, yc(z0), z0])
        c, d = np.array([POST_IN, yc(z1), z1]), np.array([-POST_IN, yc(z1), z1])
        out += [[a, b, c], [a, c, d]]
    for side in (1, -1):                                # each quarter pane: one flat polygon
        zk = list(np.linspace(Z_WS[0], Z_WS[1], 8))
        poly = [np.array([side * POST_OUT, ys(POST_OUT, Z_WS[0]), Z_WS[0]])]
        for z in zk:
            xo = x_out(z)
            poly.append(np.array([side * xo, ys(xo, z), z]))
        poly.append(np.array([side * POST_OUT, ys(POST_OUT, Z_WS[1]), Z_WS[1]]))
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


T_OUT, T_IN = 0.012, 0.030


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
            Ln[0] = Ln[0] + np.array([0, -0.012, 0])        # the foot goes into the rail
        b.sweep(A, B, C, D)


def arch_bars(b):
    """the windscreen arch = canopy front bow: one member, ruled between the shell's end edge on its rear face and the
    windscreen's top edge on its front face, so each glass stops exactly on it"""
    zr, zf = Z_ARCH
    sec = section(zr)
    for side in (1, -1):
        A = [np.array([side * p[0], p[1] + T_OUT, zr]) for p in sec]
        Bf = []
        for x, y in sec:
            yy = yc(zf) if x <= POST_OUT else ys(x, zf)
            Bf.append(np.array([side * x, yy + T_OUT, zf]))
        C = [p - np.array([0, T_IN * 1.15, 0]) for p in Bf]
        D = [p - np.array([0, T_IN * 1.15, 0]) for p in A]
        for Ln in (A, Bf, C, D):
            Ln[0] = Ln[0] + np.array([0, -0.012, 0])
        b.sweep(A, Bf, C, D)


def rail_bars(b):
    """the sill rails: 6.5 cm wide, top at the glass foot's line, buried 4.5 cm into the fuselage's sill ridge"""
    zs = np.linspace(Z_RB[0], Z_ARCH[1], 7)
    for side in (1, -1):
        def line(dx, dy):
            return [np.array([side * (foot_x(min(z, 5.35)) + dx), foot_y(min(z, 5.35)) + dy, z]) for z in zs]
        A, B = line(-0.035, 0.0), line(0.030, 0.0)
        C, D = line(0.030, -0.045), line(-0.035, -0.045)
        b.sweep(A, B, C, D)


def windscreen_bars(b):
    zs = np.linspace(Z_WS[0], Z_WS[1], 9)
    for side in (1, -1):                                  # posts: 4.5 cm strips between the centre pane's edge and the quarter planes
        top_in = [np.array([side * POST_IN, yc(z) + T_OUT, z]) for z in zs]
        top_out = [np.array([side * POST_OUT, yc(z) + T_OUT, z]) for z in zs]
        bot_out = [np.array([side * POST_OUT, yc(z) - 0.030, z]) for z in zs]
        bot_in = [np.array([side * POST_IN, yc(z) - 0.030, z]) for z in zs]
        b.sweep(top_in, top_out, bot_out, bot_in)
    x0 = POST_OUT + 0.020                                 # the base bar on the deck: across the centre pane and the quarter panes' tips
    zb0, zb1 = Z_BASE
    A = [np.array([-x0, yc(zb0) + T_OUT, zb0]), np.array([x0, yc(zb0) + T_OUT, zb0])]
    B = [np.array([-x0, yc(zb1) + T_OUT, zb1]), np.array([x0, yc(zb1) + T_OUT, zb1])]
    C = [np.array([-x0, yc(zb1) - 0.055, zb1]), np.array([x0, yc(zb1) - 0.055, zb1])]
    D = [np.array([-x0, yc(zb0) - 0.055, zb0]), np.array([x0, yc(zb0) - 0.055, zb0])]
    b.sweep(A, B, C, D)
    ctr = np.array([0, yc(0.5 * (zb0 + zb1)) - 0.02, 0.5 * (zb0 + zb1)])
    for i in (0, 1):
        b.cap([A[i], B[i], C[i], D[i]], ctr)
    for side in (1, -1):                                  # the quarter panes' sill strips, in the pane's plane, with a skirt down to the deck
        zk = list(np.linspace(Z_ARCH[1], Z_WS[1], 8))
        edge = [np.array([side * x_out(z), ys(x_out(z), z), z]) for z in zk]
        top_in, top_out, bot_out, bot_in = [], [], [], []
        for k, p in enumerate(edge):
            q = edge[min(k + 1, len(edge) - 1)] - edge[max(k - 1, 0)]
            tdir = q / np.linalg.norm(q)
            nplane = np.array([side * SIDE_SLOPE, 1.0, 0.523])
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
            lo0 = np.array([p0[0], DECK_Y, p0[2]]); lo1 = np.array([p1[0], DECK_Y, p1[2]])
            if p0[1] - DECK_Y > 0.002 or p1[1] - DECK_Y > 0.002:
                b.quad([p0, p1, lo1, lo0], np.array([0, p0[1], p0[2]]))


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
    tris = shell_patch(Z_RB[1], Z_ARCH[0], 28)                 # one unbroken shell: rear bow -> front bow
    tris += windscreen_glass()                                 # the windscreen: flat centre pane, two flat quarter panes
    return [np.array(t) for t in tris]


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    bars_bone = next(b for b in bones if b['name'] == BARS_BONE)
    already = bool(bars_bone.get('poly_mesh', {}).get('polys'))
    cut = cut_frame(bones, already)
    bars = frame_bars()
    bars_bone['poly_mesh'] = bars.mesh()
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'mig_25pd canopy: the model\'s frame cut out ({cut} faces), {len(bars.polys)} faces of slim frames; '
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
