#!/usr/bin/env python3
"""F-4C canopies and windscreen, fitted to the F-4C.mtb airframe: the glass for the source's own frames.

The F-4C has two cockpits under two separate canopies (type photos and the -1 side views): the pilot's canopy over the
front seat and the back-seater's over the rear seat, each a low blown bubble held by the rail along its sill, with the
windscreen's front arch ahead of the pilot and one bow arch between the two cockpits. The airframe already carries
that frame (all of it in the two fuselage hull bones: the sill rails, the rear hump behind the back seat, the bow
between the cockpits, the front arch and the two windscreen rails running down to the nose deck). It is the frame, so
this script adds no new frame line: it cuts the airframe's block windscreen arch and rails (stepped boxes 9 to 11 cm\nthick) and puts slim members (5 cm, in bone frame_bars__canopy) on the same lines, then fits the glass to it:

  * the rear canopy glass: from the rear hump's front face to the bow's rear face; the section at every station is a
    super-ellipse from the rail's inner top edge up to the crown (3.52 at the ends, 3.64 over the back seat's headbox);
  * the front canopy glass: from the bow's front face to the rear face of the windscreen arch, the crown falling from
    3.45 at the bow to 3.25 at the arch, the rails as the source built them (0.378 out, falling 3.03 to 2.88);
  * the windscreen, three panes as on the real F-4C (flat centre pane and two quarter panes): the airframe's two rails
    are the posts between them (9 cm wide, 0.09 to 0.17 out, falling from 3.14 at the arch to 2.85 at the nose deck).
    The centre pane lies between the rails, a hair below their top; each quarter pane runs from a rail's outer edge
    down and out to the fairing's edge (0.29 out at 2.80, falling to 0.25 and 2.72), the opening the airframe leaves
    clear on each side. Every pane stops at its member's edge; the arch above them is the airframe's one thin bow.

CANOPY (eye-relative sections, the right half from the sill up over the top) is what the checks in
work/elite_expansion_2/checks read for the canopy outline; it spans the rear canopy, the bow, the front canopy, the
arch and the windscreen (the bow and arch stand in it as their own sections). Frames: vehicle-local blocks, model
pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script f_4c.py then appends the gunsight's combiner to CanopyGlass.
Usage: python tools/cockpit_models/canopy_f_4c.py [--dry-run]
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/f_4c.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/f_4c.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/f_4c.json')

# ---- the frame's measurements (absolute blocks), read off sections of the airframe
# the sill rails' inner top edge: z, x of the inner edge, y of the top
RAIL = [(3.46, 0.340, 3.360), (3.60, 0.361, 3.320), (3.80, 0.370, 3.283), (4.00, 0.379, 3.246), (4.20, 0.389, 3.208),
        (4.40, 0.398, 3.171), (4.55, 0.400, 3.140),
        (4.75, 0.370, 3.120), (4.97, 0.330, 3.110),                                  # under the bow
        (5.10, 0.378, 3.031), (5.30, 0.378, 2.987), (5.50, 0.378, 2.944), (5.70, 0.378, 2.901), (5.80, 0.378, 2.879),
        (5.92, 0.345, 3.000),                                                         # the windscreen arch's legs
        (6.05, 0.200, 3.120), (6.15, 0.060, 3.190)]
# the crown over the centre (z, y): the frame's own top over the bow and arch, the glass's crown over the two cockpits
CROWN = [(3.46, 3.530), (3.62, 3.600), (3.80, 3.640), (4.00, 3.640), (4.25, 3.600), (4.55, 3.530),
         (4.70, 3.540), (4.95, 3.460),
         (4.97, 3.450), (5.20, 3.400), (5.50, 3.330), (5.80, 3.270), (5.92, 3.250),
         (6.05, 3.250), (6.15, 3.200)]
NEXP = 2.2                       # the sections' super-ellipse exponent

# the windscreen: three panes between slim members. The airframe builds its windscreen frame as stepped blocks (an arch
# of boxes 0.11 deep with legs, and two rails 9 cm wide); this script cuts those blocks out (CUT_RUNS) and puts slim bars
# on the same lines: the arch (5 cm deep, 4 cm thick, ARCH below) and two posts (5 cm wide, x 0.11 to 0.16, on the rails'
# line, topped 3.19 at the arch falling to 2.85 at the nose deck). The posts divide the flat centre pane (between them)
# from the two quarter panes (out from the posts' outer edge to the edge of the fairing, 0.29 out and 2.80 high, falling
# to 0.25 and 2.72).
WS_Z = (6.045, 6.80)            # centre pane (from the arch's front face)
WQ_Z = (6.02, 6.66)             # quarter panes
POST_IN, POST_OUT = 0.110, 0.160
BARS_BONE = 'frame_bars__canopy'  # (the name ends in __canopy: the checks count it as the canopy's own)
UV_FRAME = [0.342285, 0.018798]   # the removed blocks' own grey texel
ARCH_HALF, ARCH_T_OUT, ARCH_T_IN = 0.025, 0.012, 0.028
# the arch's centre line (x, y, z), from the leg's foot over the top: it leans forward as the airframe's did
ARCH = [(0.378, 2.80, 5.87), (0.345, 3.00, 5.93), (0.290, 3.10, 5.99), (0.200, 3.20, 6.02), (0.100, 3.26, 6.02),
        (0.000, 3.29, 6.02)]
# runs of the hull piece's face list (wreck_fuselage_0__hull) that make the block arch, legs and rails: first, last, bounding
# box (lo, hi): the box is checked before a run is cut (the runs are found near their index, so the lists may shift)
CUT_RUNS = [(340, 387, (0.0, 2.804, 5.839), (0.378, 3.294, 6.796)),
            (788, 835, (-0.378, 2.804, 5.839), (0.0, 3.294, 6.796))]
HULL_BONE = 'wreck_fuselage_0__hull'


def arch_z(x):                   # z of the arch's centre line at |x|
    xs = [a[0] for a in ARCH][::-1]; zs = [a[2] for a in ARCH][::-1]
    return float(np.interp(abs(x), xs, zs))


def ws_half(z):                  # the centre pane's half width (the posts' inner edge)
    return POST_IN


def ws_y(z):                     # the posts' line: the rails' top surface (the glass lies a hair below it)
    return 3.142 - 0.528 * (z - 6.25) - 0.012


def wq_in(z):                    # the posts' outer edge
    return POST_OUT


QP_SLOPE = 2.70                  # the quarter panes are flat: the plane drops 2.7 per unit x out from the post's outer edge


def wq_out(z):                   # the pane's outer edge: at the fairing's x, on the quarter pane's plane (flat, so y follows)
    x = 0.292 - 0.08 * (z - 6.20)
    return x, ws_y(z) - QP_SLOPE * (x - POST_OUT)

SILL_Y = 2.937                   # the hull's sill: the outline the checks read starts here

# the faces of the frame members the glass stops at
def z_aft(y):                    # the rear hump behind the back seat (its front face leans a little forward at the foot)
    return 3.44 + 0.37 * (3.5 - min(y, 3.5))


def z_bow_rear(y):               # the bow's rear face
    return 4.53 + 0.37 * (3.5 - min(y, 3.5))


def z_bow_front(y):              # the bow's front face
    return min(4.97, 4.89 + 0.30 * (3.5 - min(y, 3.5)))


def z_arch_rear(y):              # the windscreen arch's rear face (the arch's centre line at that height, less its half depth)
    ys = [a[1] for a in ARCH]; zs = [a[2] for a in ARCH]
    return float(np.interp(y, ys, zs)) - ARCH_HALF

# the eye (from the vehicle data), set by f_4c.py; the module also loads without it for the checks
_EYE = np.array([0.0, 3.12, 5.66])


def _eye():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        s = d['Seats'][0]
        return np.array(d['Attachments'][s['CameraPos']['EyeAttachment']]['Position'], float)
    except (OSError, KeyError):
        return _EYE


def base_crown(z):
    """(x of the glass's foot, y of the foot, y of the crown) at station z"""
    zs = [r[0] for r in RAIL]; zc = [c[0] for c in CROWN]
    z = min(max(z, zs[0]), zs[-1])
    return (float(np.interp(z, zs, [r[1] for r in RAIL])), float(np.interp(z, zs, [r[2] for r in RAIL])),
            float(np.interp(z, zc, [c[1] for c in CROWN])))


def section(z, n=22):
    """the right half-section at station z: absolute (x, y) points from the foot up to the crown (x = 0)"""
    xb, yb, yt = base_crown(z)
    us = np.linspace(0, 1, n)
    pts = [(xb * (1 - u ** NEXP) ** (1 / NEXP), yb + (yt - yb) * u) for u in us]
    pts[-1] = (0.0, yt)
    return pts


STATIONS = [3.46, 3.60, 3.80, 4.00, 4.20, 4.40, 4.55, 4.75, 4.97, 5.10, 5.30, 5.50, 5.70, 5.92, 6.05, 6.15,
            6.25, 6.40, 6.55, 6.70, 6.89]


def sections_rel(eye):
    """the canopy outline the checks read: from the sill line straight up the foot's inner face, then the glass; the
    windscreen's stations run from the fairing's edge up over the quarter pane and the rail to the centre pane"""
    out = []
    for z in STATIONS:
        if z <= 6.15:
            pts = section(z)
        else:
            xs, ys = wq_out(min(z, WQ_Z[1]))
            y = ws_y(z)
            pts = [(xs, ys), (wq_in(z) - 0.003, y), (0.0, y)]
        if pts[0][1] > SILL_Y + 0.004:
            pts = [(pts[0][0], SILL_Y)] + pts
        out.append((z - eye[2], [(x, y - eye[1]) for x, y in pts]))
    return out


CANOPY = sections_rel(_eye())            # eye-relative, as handbuilt_canopy.Shape wants it (the checks read this)


def resample(pts, k):
    p = np.array(pts, float)
    d = np.r_[0, np.cumsum(np.linalg.norm(np.diff(p, axis=0), axis=1))]
    t = np.linspace(0, d[-1], k)
    return np.c_[np.interp(t, d, p[:, 0]), np.interp(t, d, p[:, 1])]


def edge(zf, j, z, ns, lo, hi):
    """the station where row j of the section meets the frame face zf(y): the row's height depends on the station, so
    the face is found by iterating (the faces lean slowly, a few passes settle it)"""
    for _ in range(5):
        y = resample(section(z), ns + 1)[j][1]
        z = min(hi, max(lo, zf(y)))
    return z


def patch(zf0, zf1, z_lo, z_hi, nz, ns=14):
    """one glass patch, both sides: row j of the arc-length resampled section runs from the frame face zf0 to the face zf1
    it stops at (both found at the row's own height), so the cut leans with the frame member; z_lo / z_hi clamp the cut"""
    ends = [(edge(zf0, j, z_lo, ns, z_lo, z_hi), edge(zf1, j, z_hi, ns, z_lo, z_hi)) for j in range(ns + 1)]
    out = []
    for side in (1, -1):
        grid = []
        for i in range(nz + 1):
            row = []
            for j in range(ns + 1):
                a, b = ends[j]
                z = a + (b - a) * i / nz
                x, y = resample(section(z), ns + 1)[j]
                row.append(np.array([side * x, y, z]))
            grid.append(row)
        for i in range(nz):
            for j in range(ns):
                a, b, c, d = grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]
                for tri in ((a, b, c), (a, c, d)):
                    if np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0])) > 1e-10:
                        out.append(list(tri))
    return out


def windscreen(nz=10, ns=6):
    """the three panes: the centre one between the posts' inner edges (flat, on their line), and the two quarter panes from
    the posts' outer edge down and out to the fairing's edge; each pane stops at the arch's front face and at its posts"""
    out = []
    zs = np.linspace(WS_Z[0], WS_Z[1], nz + 1)
    for z0, z1 in zip(zs[:-1], zs[1:]):
        w0, w1 = ws_half(z0), ws_half(z1)
        a, b = np.array([-w0, ws_y(z0), z0]), np.array([w0, ws_y(z0), z0])
        c, d = np.array([w1, ws_y(z1), z1]), np.array([-w1, ws_y(z1), z1])
        out += [[a, b, c], [a, c, d]]
    for side in (1, -1):
        # column j runs across the pane at the fraction j/ns between the post's outer edge and the fairing's edge; it starts
        # on the arch's front face (found at the column's own x) and runs to the end of the pane
        def at(z, f):
            xo, yo = wq_out(z)
            x = wq_in(z) + (xo - wq_in(z)) * f
            y0 = ws_y(z)
            return np.array([side * x, ws_y(z) - QP_SLOPE * (x - POST_OUT), z])
        starts = []
        for j in range(ns + 1):
            z = WQ_Z[0]
            for _ in range(3):
                z = max(WQ_Z[0], arch_z(at(z, j / ns)[0]) + ARCH_HALF)
            starts.append(z)
        grid = [[at(starts[j] + (WQ_Z[1] - starts[j]) * i / nz, j / ns) for j in range(ns + 1)] for i in range(nz + 1)]
        for i in range(nz):
            for j in range(ns):
                a, b, c, d = grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]
                for tri in ((a, b, c), (a, c, d)):
                    if np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0])) > 1e-10:
                        out.append(list(tri))
    return out


class Bars:
    """quads of the slim frame members as a poly mesh in model pixels; every face is written with both windings"""
    def __init__(self, uv):
        self.P, self.N, self.U, self.polys, self.uv = [], [], [], [], uv

    def face(self, pts, n):
        for nn, pp in ((n, pts), (-np.array(n), pts[::-1])):
            px = [[float(p[0]) * 16, float(p[1]) * 16, -float(p[2]) * 16] for p in pp]
            npx = np.array([nn[0], nn[1], -nn[2]])
            a, b, c = (np.array(q) for q in px[:3])
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

    def quad(self, q, away_from):
        n = np.cross(q[1] - q[0], q[3] - q[0])
        if np.linalg.norm(n) < 1e-12:
            return
        n /= np.linalg.norm(n)
        if n @ (np.mean(q, 0) - away_from) < 0:
            n = -n
        self.face(q, n)

    def box_sweep(self, A, B, C, D, centre):
        """a bar between the four edge polylines A (top/outer, near), B (top, far), C (bottom, far), D (bottom, near)"""
        for i in range(len(A) - 1):
            for (p, q) in ((A, B), (D, C), (A, D), (B, C)):
                self.quad([p[i], p[i + 1], q[i + 1], q[i]], centre[i])


def arch_bars(b):
    """the slim arch: 5 cm deep (z), 4 cm thick along the section's normal, on the line the block arch ran on"""
    for side in (1, -1):
        pts = [np.array([side * x, y, z]) for x, y, z in ARCH]
        n_xy = []
        for k in range(len(pts)):
            a, c = pts[max(0, k - 1)], pts[min(len(pts) - 1, k + 1)]
            t = np.array([c[0] - a[0], c[1] - a[1]]); t /= np.linalg.norm(t)
            n = np.array([-t[1], t[0]])
            if n @ np.array([pts[k][0], pts[k][1] - 2.9]) < 0:
                n = -n
            if abs(pts[k][0]) < 1e-9 and n[1] < 0:
                n = -n
            n_xy.append(n)
        def edge_line(dz, rad):
            return [np.array([p[0] + n[0] * rad, p[1] + n[1] * rad, p[2] + dz]) for p, n in zip(pts, n_xy)]
        A = edge_line(-ARCH_HALF, ARCH_T_OUT); B = edge_line(ARCH_HALF, ARCH_T_OUT)
        C = edge_line(ARCH_HALF, -ARCH_T_IN); D = edge_line(-ARCH_HALF, -ARCH_T_IN)
        ctr = [np.array([p[0], p[1] - 0.12, p[2]]) for p in pts]       # (inside the bar's section: for the faces' sense)
        b.box_sweep(A, B, C, D, [np.array([p[0] * 0.5, p[1] - 0.3, p[2]]) for p in pts])


def post_bars(b, nz=8):
    """the two posts between the centre pane and the quarter panes: 5 cm wide strips on the glass along the rails' line"""
    zs = np.linspace(WS_Z[0], WS_Z[1], nz + 1)
    for side in (1, -1):
        top_in = [np.array([side * POST_IN, ws_y(z) + 0.012, z]) for z in zs]
        top_out = [np.array([side * POST_OUT, ws_y(z) + 0.012, z]) for z in zs]
        bot_out = [np.array([side * POST_OUT, ws_y(z) - 0.028, z]) for z in zs]
        bot_in = [np.array([side * POST_IN, ws_y(z) - 0.028, z]) for z in zs]
        b.box_sweep(top_in, top_out, bot_out, bot_in, [np.array([side * 0.135, ws_y(z) - 0.008, z]) for z in zs])


def sill_bars(b, nz=8, w=0.04):
    """the quarter panes' outer members: a strip along each pane's outer edge, its inner edge is the glass's edge and it runs
    on out along the pane's plane into the fairing (the glass is flat, so the fairing meets it here and not the other way)"""
    zs = np.linspace(WQ_Z[0] + 0.03, WQ_Z[1], nz + 1)
    for side in (1, -1):
        def pt(z, dx, dy):
            x, y = wq_out(z)
            return np.array([side * (x + dx), y - QP_SLOPE * dx + dy, z])
        top_in = [pt(z, 0.0, 0.012) for z in zs]
        top_out = [pt(z, w, 0.012) for z in zs]
        bot_out = [pt(z, w, -0.028) for z in zs]
        bot_in = [pt(z, 0.0, -0.028) for z in zs]
        b.box_sweep(top_in, top_out, bot_out, bot_in, [pt(z, w / 2, -0.008) for z in zs])


def frame_bars():
    b = Bars(UV_FRAME)
    arch_bars(b)
    post_bars(b)
    sill_bars(b)
    return b


def find_run(P, polys, a, c, lo, hi, tol=0.012):
    """the first face of the run of c - a + 1 faces near index a whose bounding box is (lo, hi), or None"""
    n = c - a + 1
    mins = np.array([P[[v[0] for v in p]].min(0) if p else np.full(3, 9e9) for p in polys])
    maxs = np.array([P[[v[0] for v in p]].max(0) if p else np.full(3, -9e9) for p in polys])
    for s in sorted(range(max(0, a - 400), min(len(polys) - n, a + 400) + 1), key=lambda s: abs(s - a)):
        if np.all(np.abs(mins[s:s + n].min(0) - lo) < tol) and np.all(np.abs(maxs[s:s + n].max(0) - hi) < tol):
            return s
    return None


def cut_blocks(bones, already):
    """removes the airframe's block arch, legs and rails (CUT_RUNS) from the hull piece. A run that is not found stops the
    script unless the cut was made by an earlier run (the bars' bone is in the geo); returns the faces removed"""
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    pm = hull['poly_mesh']
    P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    gone = set()
    for a, c, lo, hi in CUT_RUNS:
        s = find_run(P, pm['polys'], a, c, lo, hi)
        if s is None:
            if already:
                continue
            raise SystemExit(f'{HULL_BONE}: the block arch / rail run {a}-{c} (box {lo}, {hi}) is not in the airframe: '
                             'the face list changed; re-read CUT_RUNS')
        gone.update(range(s, s + c - a + 1))
    pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in gone]
    return len(gone)


def glass_triangles():
    tris = patch(z_aft, z_bow_rear, 3.46, 4.62, 24)                   # the back seat's canopy: rear hump -> bow
    tris += patch(z_bow_front, z_arch_rear, 4.89, 6.02, 22)           # the pilot's canopy: bow -> windscreen arch
    tris += windscreen()                                              # the windscreen's three panes
    return tris


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    already = any(b['name'] == BARS_BONE for b in bones)
    bones[:] = [b for b in bones if b['name'] != BARS_BONE]
    cut = cut_blocks(bones, already)
    bars = frame_bars()
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    bones.insert(bones.index(hull) + 1, {'name': BARS_BONE, 'parent': hull.get('parent', 'hull'), 'pivot': list(hull['pivot']),
                                         'poly_mesh': bars.mesh()})
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'f_4c canopy: the block arch and rails cut out ({cut} faces), {len(bars.polys)} faces of slim arch and posts; '
          f'{len(tris)} glass triangles (x2 windings): rear canopy, front canopy, windscreen panes')
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