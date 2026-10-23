#!/usr/bin/env python3
"""F/A-18C (Lot 20) canopy, fitted to the FA-18C.mtb airframe: the glass and the frame the source model left out.

The airframe (commit 54e9fb02) is the open tub of a Hornet: a low hoop in front of the HUD, two thin ribbons standing in for the
canopy (one along the crown from the seat to the hoop, one down the windscreen to the cowl), and nothing else. There is no
canopy bone with faces and no CanopyGlass. This script removes the stand-ins and the old hoop, then builds the Hornet's real
canopy: one blown bubble over the pilot (single seat, bubble canopy, no centre bow), a fixed windscreen ahead of it, and the
frame between the panes:

  * the windscreen arch (the bow where windscreen and bubble meet, 13 cm deep, over the HUD), one member from sill to sill;
  * the two sill rails along the canopy's lower edge, the rear canopy bow behind the seat (the bubble's glass ends at its
    front face) and, behind it, the opaque spine fairing that runs aft and dies into the turtleback;
  * the windscreen's slim base strip along the cowl. The windscreen itself is ONE curved wraparound pane (no centre plate, no posts,
    no flat panes: owner's correction 2026-10-23), from the base strip up to the arch.
Glass stops at every frame edge and never runs under it; where two bows meet they are one member. The forward view is open:
the arch is a slim hoop and there are no posts.

Frame members are the bone frame_bars__canopy (rebuilt every run), painted with a texel of the airframe's own colour. The glass
(CanopyGlass in the client json, both windings) is written fresh; fa_18c.py then appends the HUD combiner to it.

CANOPY (eye-relative sections, the right half from the sill up over the top) is what the checks in
work/elite_expansion_2/checks read for the canopy outline. Frames: vehicle-local blocks, model pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script fa_18c.py then appends the HUD combiner to CanopyGlass.
Usage: python tools/cockpit_models/canopy_fa_18c.py [--dry-run]
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/fa_18c.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/fa_18c.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/fa_18c.json')
HULLS = ('wreck_fuselage_0__hull', 'wreck_fuselage_1__hull')
CANOPY_BONE = 'wreck_fuselage_1__canopy'
BARS_BONE = 'frame_bars__canopy'             # (the name ends in __canopy: the checks count it as the canopy's own)

# ---- the frame's measurements (absolute blocks)
# the glass's foot along the tub's rim (inner crest of the brown lip): z, x of the foot, y of the rim's top; the rim falls
# toward the front and the glass merges into the turtleback toward the rear (these are read off the airframe's skin)
RAIL = [(2.40, 0.300, 3.025), (2.62, 0.350, 2.985), (3.00, 0.400, 2.890), (3.40, 0.440, 2.835), (3.80, 0.460, 2.775),
        (4.20, 0.460, 2.705), (4.60, 0.450, 2.635), (4.88, 0.450, 2.625)]
# the crown over the centre line (z, y): the old ribbon's height, rounded off at both ends
CROWN = [(2.40, 3.030), (2.64, 3.140), (3.00, 3.215), (3.27, 3.262), (3.89, 3.310), (4.47, 3.300), (4.70, 3.240),
         (4.88, 3.165), (4.935, 3.135)]
NEXP = 2.2                       # the sections' super-ellipse exponent

Z_AFT = 2.40                     # the spine fairing starts here, at nothing, on the turtleback

ARCH_REAR, ARCH_FRONT = 4.88, 4.925          # the windscreen arch's depth at the sill ... (see z_front_*: it leans forward at the top)


def z_front_rear(y):
    """the arch's rear face (the bubble's glass ends here): vertical to the sill's top, leaning forward 4 cm over the HUD"""
    return ARCH_REAR + 0.040 * min(1.0, max(0.0, (y - 2.70) / 0.45))


def z_front_front(y):
    """the arch's front face: where the windscreen glass begins"""
    return ARCH_FRONT + 0.012 * min(1.0, max(0.0, (y - 2.70) / 0.45))


# the windscreen: from the arch to the cowl: half width of its foot and the crown's height
WS_Z = (4.935, 5.76)
WS_X = (0.440, 0.300)
WSC = [(4.935, 3.135), (5.05, 3.105), (5.20, 3.030), (5.40, 2.915), (5.60, 2.800), (5.76, 2.712)]

# the cowl's top surface (absolute y) at |x| and z, sampled from the airframe after the stand-ins are gone
_SK_Z = [5.0, 5.2, 5.4, 5.6, 5.8, 5.9]
_SK_X = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5]
_SK = [[2.84, 2.83, 2.80, 2.70, 2.64, 2.60],
       [2.80, 2.79, 2.77, 2.69, 2.64, 2.60],
       [2.75, 2.75, 2.73, 2.67, 2.64, 2.58],
       [2.72, 2.71, 2.70, 2.65, 2.62, 2.56],
       [2.675, 2.67, 2.66, 2.62, 2.60, 2.55],
       [2.64, 2.64, 2.63, 2.61, 2.59, 2.55]]

SILL_Y = 2.50                    # the outline the checks read starts here (inside the tub wall's lip)

_EYE = np.array([0.0, 3.00, 4.22])


def _eye():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        s = d['Seats'][0]
        return np.array(d['Attachments'][s['CameraPos']['EyeAttachment']]['Position'], float)
    except (OSError, KeyError):
        return _EYE


def skin_y(x, z):
    """the cowl's height at |x| beside / ahead of the arch (the skin under the glass's foot, a hair above it)"""
    zz = min(max(z, _SK_Z[0]), _SK_Z[-1])
    row = [float(np.interp(zz, _SK_Z, [r[i] for r in _SK])) for i in range(len(_SK_X))]
    return float(np.interp(abs(x), _SK_X, row)) + 0.010


def base_crown(z):
    """(x of the glass's foot, y of the foot, y of the crown) at station z: on the rim, or on the cowl for the windscreen"""
    zs = [r[0] for r in RAIL]
    if z <= RAIL[-1][0]:
        zc = [c[0] for c in CROWN]
        return (float(np.interp(z, zs, [r[1] for r in RAIL])), float(np.interp(z, zs, [r[2] for r in RAIL])),
                float(np.interp(z, zc, [c[1] for c in CROWN])))
    if z >= WS_Z[0]:
        t = (z - WS_Z[0]) / (WS_Z[1] - WS_Z[0])
        xb = WS_X[0] + (WS_X[1] - WS_X[0]) * t
        return (xb, skin_y(xb, z), float(np.interp(z, [c[0] for c in WSC], [c[1] for c in WSC])))
    # over the arch: between the rail's end and the windscreen's start
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


STATIONS = [2.40, 2.50, 2.60, 2.80, 3.00, 3.20, 3.40, 3.60, 3.80, 4.00, 4.20, 4.40, 4.60, 4.75, 4.88, 4.935, 5.05, 5.20,
            5.40, 5.60, 5.76]


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


# ---- the frame members
#  * the rear canopy bow behind the pilot's seat: a slim hoop over the sills at Z_BOW (the bubble's glass ends at its front
#    face; behind it the spine fairing runs back to the turtleback, opaque, on the glass's old surface)
#  * the windscreen arch between the bubble and the windscreen (one member: the glass of both ends at its faces)
#  * the sill rails along the bubble's lower edge (the glass starts above them)
#  * the windscreen's base strip along the cowl (the windscreen itself is ONE curved wraparound pane, no posts)
Z_BOW = (3.66, 3.72)
BOW_OUT, BOW_IN = 0.012, 0.030               # the hoop stands this far out of the glass surface and this far in
NS = 22                                      # the bubble's rows
RAIL_ROWS = (0, 1)                           # the sill rail fills this band of the bubble's section (rows of NS)
WS_NS = 28
BASE_ROWS = (0, 2)                           # the windscreen's base frame along the cowl fills this band
BASE_OUT, BASE_IN = 0.008, 0.018
ARCH_OUT, ARCH_IN = 0.012, 0.030


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

    def bar(self, A, B, t_out, t_in, axis_y=2.85, caps=True):
        """a bar whose two long edges lie on the glass surface along the paths A[i] and B[i]: a skin t_out over the surface,
        one t_in under it, and the two side walls between. The offsets are taken along a normal averaged at every point
        (shared by the neighbouring faces), so the member is one continuous strip with no steps between its faces."""
        n_pts = len(A)
        axis = lambda c: np.array([0.0, axis_y, c[2]])
        N = []
        for i in range(n_pts):
            d = A[min(i + 1, n_pts - 1)] - A[max(i - 1, 0)]
            w = B[i] - A[i]
            n = np.cross(w, d)
            ln = np.linalg.norm(n)
            n = n / ln if ln > 1e-12 else np.array([0.0, 1.0, 0.0])
            if n @ ((A[i] + B[i]) / 2 - axis((A[i] + B[i]) / 2)) < 0:
                n = -n
            N.append(n)
        At = [A[i] + N[i] * t_out for i in range(n_pts)]; Ab = [A[i] - N[i] * t_in for i in range(n_pts)]
        Bt = [B[i] + N[i] * t_out for i in range(n_pts)]; Bb = [B[i] - N[i] * t_in for i in range(n_pts)]
        for i in range(n_pts - 1):
            nn = (N[i] + N[i + 1]); nn = nn / np.linalg.norm(nn)
            self.face([At[i], At[i + 1], Bt[i + 1], Bt[i]], nn)
            self.face([Ab[i], Ab[i + 1], Bb[i + 1], Bb[i]], -nn)
            for P0, P1, sgn in ((At, Ab, -1), (Bt, Bb, 1)):
                w = (B[i] - A[i]) + (B[i + 1] - A[i + 1]); w = w - nn * (w @ nn)
                if np.linalg.norm(w) > 1e-12:
                    self.face([P0[i], P0[i + 1], P1[i + 1], P1[i]], sgn * w / np.linalg.norm(w))
        if caps:
            for i, s in ((0, -1), (n_pts - 1, 1)):
                d = (A[min(i + 1, n_pts - 1)] - A[max(i - 1, 0)]); d = d / np.linalg.norm(d)
                self.face([At[i], Bt[i], Bb[i], Ab[i]], s * d)


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
                if n @ (c - np.array([0.0, 2.95, c[2]])) < 0:
                    n = -n
                self.face(q, n)

    def mesh(self):
        return {'normalized_uvs': True, 'positions': self.P, 'normals': self.N, 'uvs': self.U, 'polys': self.polys}


def _full(rowfn, ns):
    """a path over the whole section, sill to sill: rowfn(side, j) for the left side up to the crown, then the right side down"""
    return [rowfn(1, j) for j in range(ns + 1)] + [rowfn(-1, j) for j in range(ns - 1, -1, -1)]


def frame_bars(uv):
    b = Bars(uv)
    # the rear bow: ONE hoop from sill to sill, the section's rows at the bow's two faces, from the sill up to the crown
    A = _full(lambda s, j: _pt(Z_BOW[0], j, s, NS), NS)
    B = _full(lambda s, j: _pt(Z_BOW[1], j, s, NS), NS)
    b.bar(A, B, BOW_OUT, BOW_IN)
    for side in (1, -1):
        # the spine fairing behind it, back to where the glass's old surface dies into the turtleback (opaque)
        zs = np.linspace(Z_AFT, Z_BOW[0], 14)
        b.skin([[_pt(z, j, side, NS) for j in range(NS + 1)] for z in zs])
        # the sill rail along the bubble's lower edge, from the rear bow to the arch
        r0, r1 = RAIL_ROWS
        zr0 = [Z_BOW[1] for _ in (r0, r1)]
        zr1 = [edge(z_front_rear, j, ARCH_REAR, NS, 4.7, 4.95) for j in (r0, r1)]
        n = 16
        A = [_pt(zr0[0] + (zr1[0] - zr0[0]) * i / n, r0, side, NS) for i in range(n + 1)]
        B = [_pt(zr0[1] + (zr1[1] - zr0[1]) * i / n, r1, side, NS) for i in range(n + 1)]
        b.bar(A, B, BOW_OUT * 0.7, BOW_IN * 0.7)
    # the windscreen arch: ONE member from sill to sill, between the rear face (the bubble's glass) and the front face
    # (the windscreen's glass); it follows the section from the foot up over the crown
    za = lambda zf, j: edge(zf, j, ARCH_REAR, NS, 4.7, 5.2)
    A = _full(lambda s, j: _pt(za(z_front_rear, j), j, s, NS), NS)
    B = _full(lambda s, j: _pt(za(z_front_front, j), j, s, NS), NS)
    b.bar(A, B, ARCH_OUT, ARCH_IN)
    # the windscreen's base strip along the cowl (the windscreen's rows are finer: its own ring set)
    for side in (1, -1):
        n = 14
        k0, k1 = BASE_ROWS
        g0 = [edge(z_front_front, j, 4.92, WS_NS, 4.92, WS_Z[1]) for j in (k0, k1)]
        g1 = [edge(lambda y: WS_Z[1], j, WS_Z[1], WS_NS, 4.92, WS_Z[1]) for j in (k0, k1)]
        A = [_pt(g0[0] + (g1[0] - g0[0]) * i / n, k0, side, WS_NS) for i in range(n + 1)]
        B = [_pt(g0[1] + (g1[1] - g0[1]) * i / n, k1, side, WS_NS) for i in range(n + 1)]
        b.bar(A, B, BASE_OUT, BASE_IN)
    return b


def glass_triangles():
    r0, r1 = RAIL_ROWS
    tris = patch(lambda y: Z_BOW[1], z_front_rear, Z_BOW[1], 4.94, 26, ns=NS, jr=(r1, NS))   # the bubble: rear bow -> arch
    tris += patch(z_front_front, lambda y: WS_Z[1], 4.92, WS_Z[1], 14, ns=WS_NS, jr=(BASE_ROWS[1], WS_NS))   # the windscreen: one curved wraparound pane, base strip to arch
    return tris


# ---- the stand-ins and the old hoop go
def old_canopy(bones):
    """{bone name: set(poly index)}: (1) the airframe's ribbons (x within 0.135 of the centre line: the windscreen plane, the
    crown strip, the bits of the hoop's crest) (2) the old hoop: its top bar and legs (boxes round the arch zone)"""
    out = {}
    for b in bones:
        if b['name'] not in HULLS:
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        sel = set()
        for i, poly in enumerate(pm['polys']):
            if not poly:
                continue
            V = P[[v[0] for v in poly]]
            c = V.mean(0)
            if np.abs(V[:, 0]).max() <= 0.1351 and V[:, 1].max() > 3.05 and 2.5 < c[2] < 5.95:
                sel.add(i)                                           # the ribbons (and the old headrest's top)
                continue
            # the old hoop: boxes in x .09..0.47, round z 4.80..5.16, the bar and the legs
            if 4.80 <= c[2] <= 5.16 and 0.0 <= abs(c[0]) <= 0.47 and V[:, 2].max() < 5.17 and V[:, 2].min() > 4.79:
                if c[1] >= 2.84 or (c[1] >= 2.62 and abs(c[0]) >= 0.38):
                    sel.add(i)
        if sel:
            out[b['name']] = sel
    return out


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    rm = old_canopy(bones)
    gone = 0
    for b in bones:
        if b['name'] in rm:
            pm = b['poly_mesh']
            pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in rm[b['name']]]
            gone += len(rm[b['name']])
    # the added frame members: their own bone (dropped and rebuilt on every run), painted with a texel of the airframe's colour
    bones[:] = [b for b in bones if b['name'] != BARS_BONE]
    cb = next(b for b in bones if b['name'] == CANOPY_BONE)
    hull = next(b for b in bones if b['name'] == HULLS[1])
    hpm = hull['poly_mesh']
    uv = hpm['uvs'][hpm['polys'][0][0][2]]
    bars = frame_bars(uv)
    bones.insert(bones.index(cb) + 1, {'name': BARS_BONE, 'parent': cb['parent'], 'pivot': list(cb['pivot']),
                                       'poly_mesh': bars.mesh()})
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'fa_18c canopy: {gone} stand-in / old hoop faces removed; {len(tris)} glass triangles (x2 windings), {len(bars.polys)} faces of added frame members')
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
