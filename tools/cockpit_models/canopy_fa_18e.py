#!/usr/bin/env python3
"""F/A-18E canopy, fitted to the FA-18E.mtb airframe: the glass for the source's own canopy frame.

The Super Hornet's canopy (side views and photos of the Block II): one blown bubble running aft from the windscreen over
the pilot along two slim sill rails, merging into the spine behind the seat, with the fixed windscreen ahead of it: the
windscreen arch (bow) rising from the sills over the HUD, and the glass sloping from the arch down to the nose deck.

The airframe already carries that frame (bone wreck_fuselage_1__canopy: the two sill rails that rise toward the rear, the
closing piece where they meet the spine, a centre strip along the crown, and the front arch whose top bar sits in
wreck_fuselage_0__hull), so this script keeps it as THE frame, never adds a second one, and fits the glass to it:

  * two glass patches: the bubble (from the closing piece at the spine to the rear face of the arch) and the windscreen (from
    the front face of the arch to the nose deck). Every row starts on the inner top edge of the sill rail (or the nose deck),
    and each patch stops at the face of the frame member it meets, never running under it;
  * the section at every station is a super-ellipse from the rail up to the crown, the crown line taken from the frame's own
    centre strip (3.165 over the seat, falling to 2.92 where it meets the spine); the windscreen's crown follows the
    airframe's own windscreen stand-in down to the nose deck (3.0 at the arch, 2.53 at its foot);
  * the airframe's stand-ins for glass are removed, since the real glass takes their place: the long opaque centre strip of the
    frame (flat quads along the crown) and the narrow slab on the nose in front of the arch.

Retrofit (owner standard: framing as in life): the added frame members (own bone frame_bars__canopy, rebuilt every run) are the
rear canopy bow behind the seat with the opaque spine fairing aft of it (the glass ends at the bow's front face), the two
quarter-pane posts along the windscreen that split the front panel from the quarter panes, and the windscreen's base frame
along the nose deck. The front arch and the canopy bow just aft of it are the airframe's one member (they meet).

CANOPY (eye-relative sections, the right half from the sill up over the top) is what the checks in
work/elite_expansion_2/checks read for the canopy outline. Frames: vehicle-local blocks, model pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script fa_18e.py then appends the HUD combiner to CanopyGlass.
Usage: python tools/cockpit_models/canopy_fa_18e.py [--dry-run]
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/fa_18e.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/fa_18e.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/fa_18e.json')
CANOPY_BONE = 'wreck_fuselage_1__canopy'
HULL_BONE = 'wreck_fuselage_0__hull'

# ---- the frame's measurements (absolute blocks)
# the sill rails' inner top edge: z, x of the inner edge, y of the top (the rails rise toward the spine)
RAIL = [(2.40, 0.200, 2.916), (2.60, 0.250, 2.865), (2.835, 0.280, 2.825), (3.15, 0.315, 2.780), (3.47, 0.340, 2.735),
        (3.80, 0.360, 2.700), (4.10, 0.375, 2.667), (4.40, 0.375, 2.667), (4.71, 0.345, 2.667)]
# the crown line over the centre (z, y): the frame's centre strip
CROWN = [(2.40, 2.916), (2.655, 2.984), (3.152, 3.074), (3.92, 3.165), (4.463, 3.165), (4.734, 3.074), (4.80, 3.045),
         (4.915, 3.000)]
NEXP = 2.2                       # the sections' super-ellipse exponent

Z_AFT = 2.40                     # the closing piece's front face: the bubble starts here, on the spine


def z_front_rear(y):
    """the arch's rear face (its legs rise from the rail, the top bar leans forward over the HUD)"""
    return 4.712 + 0.045 * min(1.0, max(0.0, (y - 2.60) / 0.15)) + 0.035 * min(1.0, max(0.0, (y - 2.85) / 0.15))


def z_front_front(y):
    """the arch's front face: where the windscreen glass begins"""
    return 4.757 + 0.158 * min(1.0, max(0.0, (y - 2.55) / 0.40))


# the windscreen: from the arch (z 4.915) to the nose deck: half width of its foot and the crown's height (the stand-in's top)
WS_Z = (4.915, 5.86)
WS_X = (0.335, 0.200)
WSC = [(4.757, 3.040), (4.915, 3.000), (5.231, 2.848), (5.864, 2.532)]

# the nose deck's top surface (absolute y) at |x| and z, sampled from the airframe: the glass's foot a hair above it
_SK_Z = [4.757, 5.0, 5.2, 5.4, 5.6, 5.8, 5.9]
_SK_X = [0.0, 0.1, 0.2, 0.3, 0.4]
_SK = [[2.70, 2.70, 2.68, 2.62, 2.47],
       [2.735, 2.688, 2.674, 2.651, 2.424],
       [2.651, 2.651, 2.626, 2.559, 2.392],
       [2.613, 2.607, 2.601, 2.497, 2.367],
       [2.576, 2.576, 2.576, 2.487, 2.341],
       [2.542, 2.527, 2.505, 2.396, 2.306],
       [2.526, 2.503, 2.458, 2.375, 2.288]]

SILL_Y = 2.60                    # the outline the checks read starts here (the rails' lower edge)

_EYE = np.array([0.0, 2.86, 4.12])


def _eye():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        s = d['Seats'][0]
        return np.array(d['Attachments'][s['CameraPos']['EyeAttachment']]['Position'], float)
    except (OSError, KeyError):
        return _EYE


def skin_y(x, z):
    """the nose deck's height at |x| beside / ahead of the arch (the skin under the glass's foot, a hair above it)"""
    zz = min(max(z, _SK_Z[0]), _SK_Z[-1])
    row = [float(np.interp(zz, _SK_Z, [r[i] for r in _SK])) for i in range(len(_SK_X))]
    return float(np.interp(abs(x), _SK_X, row)) + 0.010


def base_crown(z):
    """(x of the glass's foot, y of the foot, y of the crown) at station z: on the rail, or on the nose deck for the windscreen"""
    zs = [r[0] for r in RAIL]
    if z <= RAIL[-1][0]:
        zc = [c[0] for c in CROWN]
        return (float(np.interp(z, zs, [r[1] for r in RAIL])), float(np.interp(z, zs, [r[2] for r in RAIL])),
                float(np.interp(z, zc, [c[1] for c in CROWN])))
    if z >= WS_Z[0]:
        t = (z - WS_Z[0]) / (WS_Z[1] - WS_Z[0])
        xb = WS_X[0] + (WS_X[1] - WS_X[0]) * t
        return (xb, skin_y(xb, z), float(np.interp(z, [c[0] for c in WSC], [c[1] for c in WSC])))
    # over the front arch: between the rail's end and the windscreen's start
    t = (z - RAIL[-1][0]) / (WS_Z[0] - RAIL[-1][0])
    a, b = base_crown(RAIL[-1][0]), base_crown(WS_Z[0])
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def section(z, n=22):
    """the right half-section at station z: absolute (x, y) points from the foot up to the crown (x = 0)"""
    xb, yb, yt = base_crown(z)
    us = np.linspace(0, 1, n)
    pts = [(xb * (1 - u ** NEXP) ** (1 / NEXP), yb + (yt - yb) * u) for u in us]
    pts[-1] = (0.0, yt)
    return pts


STATIONS = [2.40, 2.50, 2.60, 2.80, 3.00, 3.20, 3.40, 3.60, 3.80, 4.00, 4.20, 4.40, 4.60, 4.71, 4.80, 4.915, 5.00, 5.10,
            5.20, 5.40, 5.60, 5.80, 5.86]


def sections_rel(eye):
    """the canopy outline the checks read: from the sill line straight up the foot's inner face, then the glass"""
    out = []
    for z in STATIONS:
        pts = section(z)
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


def patch(zf0, zf1, z_lo, z_hi, nz, ns=14, jr=None):
    """one glass patch, both sides: row j of the arc-length resampled section runs from the frame face zf0 to the face zf1
    it stops at (both found at the row's own height), so the cut leans with the frame member; z_lo / z_hi clamp the cut;
    jr = (first row, last row) keeps only that band of the section (the rest is a frame member or another pane)"""
    ends = [(edge(zf0, j, z_lo, ns, z_lo, z_hi), edge(zf1, j, z_hi, ns, z_lo, z_hi)) for j in range(ns + 1)]
    j0, j1 = jr or (0, ns)
    out = []
    for side in (1, -1):
        grid = []
        for i in range(nz + 1):
            row = []
            for j in range(j0, j1 + 1):
                a, b = ends[j]
                z = a + (b - a) * i / nz
                x, y = resample(section(z), ns + 1)[j]
                row.append(np.array([side * x, y, z]))
            grid.append(row)
        for i in range(nz):
            for j in range(j1 - j0):
                a, b, c, d = grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]
                for tri in ((a, b, c), (a, c, d)):
                    if np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0])) > 1e-10:
                        out.append(list(tri))
    return out


# ---- the Super Hornet's frame members added to the airframe's own (its sill rails and the windscreen arch):
#  * the rear canopy bow behind the pilot's seat: a slim hoop over the sills at Z_BOW (the canopy glass ends at its front
#    face; behind it the spine fairing runs back to the closing piece, opaque, on the glass's old surface)
#  * the quarter-pane posts: one slim diagonal post each side along the windscreen, from the arch down to the nose deck,
#    splitting the large front panel from the small quarter panes beside it
Z_BOW = (3.56, 3.62)
BOW_OUT, BOW_IN = 0.012, 0.030               # the hoop stands this far out of the glass surface and this far in
WS_NS = 28
POST_ROWS = (12, 14)                         # the post fills this band of the windscreen section (rows of WS_NS)
BASE_ROWS = (0, 2)                          # the windscreen's base frame along the nose deck fills this band
POST_OUT, POST_IN = 0.008, 0.018
BARS_BONE = 'frame_bars__canopy'             # (the name ends in __canopy: the checks count it as the canopy's own)


def _pt(z, j, side, ns):
    x, y = resample(section(z), ns + 1)[j]
    return np.array([side * x, y, z])


class Bars:
    """quads of the added frame members, as a poly mesh in model pixels; every face is written with both windings"""
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

    def bar(self, A, B, t_out, t_in, axis_y=2.85):
        """a bar whose two long edges lie on the glass surface along A[i] and B[i]: a skin t_out over the surface, one t_in
        under it, and the two side walls between"""
        for i in range(len(A) - 1):
            q = [A[i], A[i + 1], B[i + 1], B[i]]
            n = np.cross(B[i] - A[i], A[i + 1] - A[i])
            if np.linalg.norm(n) < 1e-12:
                continue
            n /= np.linalg.norm(n)
            c = np.mean(q, 0)
            if n @ (c - np.array([0.0, axis_y, c[2]])) < 0:
                n = -n
            top = [p + n * t_out for p in q]
            bot = [p - n * t_in for p in q]
            self.face(top, n)
            self.face(bot, -n)
            for (p0, p1) in ((0, 1), (2, 3)):
                w = [top[p0], top[p1], bot[p1], bot[p0]]
                wn = np.cross(w[1] - w[0], w[3] - w[0])
                if np.linalg.norm(wn) > 1e-12:
                    self.face(w, wn / np.linalg.norm(wn))

    def skin(self, rows):
        """an opaque sheet on the surface: rows[i] = the points of one section ring, consecutive rings are joined"""
        for i in range(len(rows) - 1):
            for j in range(len(rows[i]) - 1):
                q = [rows[i][j], rows[i + 1][j], rows[i + 1][j + 1], rows[i][j + 1]]
                n = np.cross(q[1] - q[0], q[3] - q[0])
                if np.linalg.norm(n) < 1e-12:
                    q = [q[0], q[1], q[2]]
                    n = np.cross(q[1] - q[0], q[2] - q[0])
                    if np.linalg.norm(n) < 1e-12:
                        continue
                n /= np.linalg.norm(n)
                c = np.mean(q, 0)
                if n @ (c - np.array([0.0, 2.85, c[2]])) < 0:
                    n = -n
                self.face(q, n)

    def mesh(self):
        return {'normalized_uvs': True, 'positions': self.P, 'normals': self.N, 'uvs': self.U, 'polys': self.polys}


def frame_bars(uv):
    b = Bars(uv)
    ns = 14
    for side in (1, -1):
        # the rear bow: the section's rows at the bow's two faces, from the sill up to the crown
        A = [_pt(Z_BOW[0], j, side, ns) for j in range(ns + 1)]
        B = [_pt(Z_BOW[1], j, side, ns) for j in range(ns + 1)]
        b.bar(A, B, BOW_OUT, BOW_IN)
        # the spine fairing behind it, to the closing piece (the glass's old surface, opaque)
        zs = np.linspace(Z_AFT, Z_BOW[0], 12)
        b.skin([[_pt(z, j, side, ns) for j in range(ns + 1)] for z in zs])
    # the quarter-pane posts (the windscreen's rows are finer: its own ring set)
    for side in (1, -1):
        j0, j1 = POST_ROWS
        e0 = [edge(z_front_front, j, 4.757, WS_NS, 4.757, WS_Z[1]) for j in (j0, j1)]
        e1 = [edge(lambda y: WS_Z[1], j, WS_Z[1], WS_NS, 4.757, WS_Z[1]) for j in (j0, j1)]
        n = 14
        A = [_pt(e0[0] + (e1[0] - e0[0]) * i / n, j0, side, WS_NS) for i in range(n + 1)]
        B = [_pt(e0[1] + (e1[1] - e0[1]) * i / n, j1, side, WS_NS) for i in range(n + 1)]
        b.bar(A, B, POST_OUT, POST_IN)
        # the base frame along the nose deck (the glass starts above it)
        k0, k1 = BASE_ROWS
        g0 = [edge(z_front_front, j, 4.757, WS_NS, 4.757, WS_Z[1]) for j in (k0, k1)]
        g1 = [edge(lambda y: WS_Z[1], j, WS_Z[1], WS_NS, 4.757, WS_Z[1]) for j in (k0, k1)]
        A = [_pt(g0[0] + (g1[0] - g0[0]) * i / n, k0, side, WS_NS) for i in range(n + 1)]
        B = [_pt(g0[1] + (g1[1] - g0[1]) * i / n, k1, side, WS_NS) for i in range(n + 1)]
        b.bar(A, B, POST_OUT, POST_IN)
    return b


def glass_triangles():
    tris = patch(lambda y: Z_BOW[1], z_front_rear, Z_BOW[1], 4.80, 26)          # the bubble: the rear bow -> the arch
    j0, j1 = POST_ROWS
    tris += patch(z_front_front, lambda y: WS_Z[1], 4.757, WS_Z[1], 14, ns=WS_NS, jr=(BASE_ROWS[1], j0))   # quarter panes
    tris += patch(z_front_front, lambda y: WS_Z[1], 4.757, WS_Z[1], 14, ns=WS_NS, jr=(j1, WS_NS))        # front panel
    return tris


def _overlap2d(a, b):
    """separating-axis test of two triangles in 2-D (shrunk a little so touching edges do not count)"""
    def shrink(t):
        c = t.mean(0)
        return c + (t - c) * 0.96
    a, b = shrink(a), shrink(b)
    for t in (a, b):
        for i in range(3):
            e = t[(i + 1) % 3] - t[i]
            n = np.array([-e[1], e[0]])
            pa, pb = a @ n, b @ n
            if pa.max() <= pb.min() or pb.max() <= pa.min():
                return False
    return True


def drop_buried(bones, gap=0.002):
    """Faces of the frame that lie in the same plane as another face of the airframe (same way round, overlapping): the
    frame's doubled corner pieces, which the source builds twice. The twin stays; the frame's copy goes (it only
    z-fights, nothing shows through its place). Returns how many."""
    tris = []                                    # (bone, poly index, triangle 3x3)
    for b in bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or b['name'] not in (CANOPY_BONE, HULL_BONE, 'wreck_fuselage_1__hull'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        for i, poly in enumerate(pm['polys']):
            V = P[[v[0] for v in poly]]
            for k in range(1, len(V) - 1):
                T = V[[0, k, k + 1]]
                c = T.mean(0)
                if abs(c[0]) < 0.75 and 2.3 < c[2] < 5.7 and 1.0 < c[1] < 3.4:
                    tris.append((b['name'], i, T))
    gone = set()
    n = len(tris)
    N = []
    for _, _, T in tris:
        v = np.cross(T[1] - T[0], T[2] - T[0]); ln = np.linalg.norm(v)
        N.append(v / ln if ln > 1e-10 else v * 0)
    for i in range(n):
        bi, pi, Ti = tris[i]
        if bi != CANOPY_BONE or (bi, pi) in gone or not N[i].any():
            continue
        for j in range(n):
            bj, pj, Tj = tris[j]
            if j == i or (bj, pj) == (bi, pi) or (bj, pj) in gone or not N[j].any():
                continue
            if bj == CANOPY_BONE and j < i:
                continue                        # (twins inside the frame: the earlier one stays)
            if N[i] @ N[j] < np.cos(np.radians(2.5)):
                continue
            if abs((Tj.mean(0) - Ti.mean(0)) @ N[i]) > gap:
                continue
            ax = np.argsort(np.abs(N[i]))[:2]
            if _overlap2d(Ti[:, ax], Tj[:, ax]):
                gone.add((bi, pi)); break
    for b in bones:
        if b['name'] == CANOPY_BONE:
            b['poly_mesh']['polys'] = [p for i, p in enumerate(b['poly_mesh']['polys']) if (CANOPY_BONE, i) not in gone]
    return len(gone)


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    gone = 0
    # the stand-ins: the frame's centre strip pieces (over the crown), and the nose's slab ahead of the arch
    for name, test in ((CANOPY_BONE, lambda V: np.abs(V[:, 0]).max() <= 0.165 and np.ptp(V[:, 2]) > 0.2 and V[:, 1].min() > 2.85
                        and V[:, 2].max() < 4.83),
                       (HULL_BONE, lambda V: np.abs(V[:, 0]).max() <= 0.165 and np.ptp(V[:, 2]) > 0.3 and V[:, 1].min() > 2.5
                        and V[:, 2].min() > 4.9 and V[:, 2].max() < 5.9)):
        bone = next(b for b in bones if b['name'] == name)
        pm = bone['poly_mesh']
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        keep = []
        for poly in pm['polys']:
            if poly and test(P[[v[0] for v in poly]]):
                gone += 1
                continue
            keep.append(poly)
        pm['polys'] = keep
    gone_twin = drop_buried(bones)
    # the added frame members: their own bone (dropped and rebuilt on every run), painted with the frame's own texel
    bones[:] = [b for b in bones if b['name'] != BARS_BONE]
    cb = next(b for b in bones if b['name'] == CANOPY_BONE)
    cpm = cb['poly_mesh']
    uv = cpm['uvs'][cpm['polys'][0][0][2]]
    bars = frame_bars(uv)
    bones.insert(bones.index(cb) + 1, {'name': BARS_BONE, 'parent': cb['parent'], 'pivot': list(cb['pivot']),
                                       'poly_mesh': bars.mesh()})
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'fa_18e canopy: {gone} stand-in faces removed (frame centre strip, nose slab), {gone_twin} doubled frame faces dropped; {len(tris)} glass triangles (x2 windings), {len(bars.polys)} faces of added frame members')
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
