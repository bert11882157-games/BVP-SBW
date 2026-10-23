#!/usr/bin/env python3
"""F-15EX canopy, fitted to the F-15EX.mtb airframe (the F-15E frame): the glass for the sill rails, and slim hand-built bows.

The Eagle II's canopy (photos and side views of the type): one long blown bubble over both crew stations, held by the rail
along each sill, a rear arch on the spine behind the WSO, ONE bow across between the two seats (none over the front seat), and
the slim windscreen frame ahead of the pilot, with the windscreen sloping from it down to the nose deck. The open forward
view is the point: the members are slim.

The airframe's own frame is thick and faceted (arches 9 to 18 cm deep, pale stand-in strips along the crown), so this script
rebuilds the arches and strips of the canopy bone and the arch legs the hull carries, keeping the airframe's own sill rails (they fit\nthis hull's sill, step for step):

  * the windscreen bow, the bow between the seats and the rear arch, each ONE swept member of a slim rectangular section (4 to
    5 cm across, 4 to 5.5 cm deep) running up one leg, over the crown and down the other along a smooth 24-segment curve: one
    member, not two bars side by side, leaning like the airframe's own;
  * a thin windscreen frame along the glass's feet on the nose deck (two foot strips and the front cross strip);
  * the rear-view mirror: a dark mirror face in a thin frame, 25 x 8 cm, hung under the windscreen bow's crown behind the HUD;
  * three glass patches: the rear panel over the WSO (from the rear arch's front face to the bow's rear face), the front panel
    over the pilot (from the bow's front face to the windscreen bow's rear face) and the windscreen (from that bow's front face
    to the nose deck). Every patch starts on the inner top edge of the sill rail and stops at the face of the member it meets
    (the bows lean, so the cut leans with them), never running under it. The glass rows are the very rows the members are swept
    along, so the glass ends flush on the member and never stands above it;
  * the section at every station is a super-ellipse from the rail up to the crown, the crown line taken from the old frame's
    centre strip (3.57 at the rear arch, 3.75 over the bow, 3.57 again at the windscreen bow); the windscreen's crown follows the
    airframe's windscreen stand-in down to the nose deck (3.52 at the bow, 3.10 at its foot);
  * the airframe's stand-ins for glass are removed: the nose's black windscreen slab and the old windscreen arch's twin in the hull.

The new members are painted with texels of the airframe's own texture (dark frame grey, mirror black, rail grey), marked by a
sub-texel uv offset. A re-run removes the marked faces first, so the script is idempotent on its own output.

CANOPY (eye-relative sections, the right half from the sill up over the top) is what the checks in
work/elite_expansion_2/checks read for the canopy outline. Frames: vehicle-local blocks, model pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script f_15ex.py then appends the HUD combiner to CanopyGlass.
Usage: python tools/cockpit_models/canopy_f_15ex.py [--dry-run]
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/f_15ex.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/f_15ex.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/f_15ex.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/f_15ex.png')
CANOPY_BONE = 'wreck_fuselage_0__canopy'
HULL_BONE = 'wreck_fuselage_0__hull'
TW, TH = 4096, 8192
PAINT = (16, 16, 4064, 6200)     # the cockpit script's paint area of the texture (x, y, w, h): not the airframe's texels
FRAME_RGB = (46, 50, 56)         # the frame's colour: dark blue-grey, nearest uniform texel block of the airframe's texture
MIRROR_RGB = (18, 20, 24)        # the mirror's face: near black
MIRROR_FRAME_RGB = (104, 108, 112)   # its thin frame: light metal grey
RAIL_RGB = (46, 50, 56)         # the sill rails when they are built here: dark
MARK = 1.1e-4                    # the sub-texel uv offset that marks a face this script made

# ---- the frame's measurements (absolute blocks)
# the sill rails' inner top edge: z, x of the inner edge, y of the top (the rail climbs over the rear seat: the sill is
# 0.23 higher there than beside the pilot)
RAIL = [(1.95, 0.440, 3.330), (2.20, 0.478, 3.243), (2.40, 0.484, 3.225), (2.60, 0.490, 3.206), (2.80, 0.497, 3.187),
        (3.00, 0.497, 3.163), (3.20, 0.497, 3.139), (3.40, 0.497, 3.116), (3.60, 0.500, 3.093), (3.80, 0.508, 3.069),
        (4.00, 0.514, 3.047), (4.20, 0.520, 3.024), (4.40, 0.524, 3.003), (4.60, 0.527, 2.982), (4.80, 0.531, 2.960),
        (5.00, 0.533, 2.939), (5.10, 0.534, 2.928)]
# the crown line over the centre (z, y): the old frame's two centre strips (3.57 at the rear arch, flat 3.75 over the bow,
# falling to 3.57 at the windscreen bow)
CROWN = [(1.84, 3.570), (2.20, 3.622), (2.60, 3.680), (3.00, 3.738), (3.09, 3.750), (3.54, 3.750), (3.63, 3.750),
         (4.22, 3.705), (4.60, 3.637), (4.80, 3.600), (4.98, 3.570), (5.10, 3.545)]
NEXP = 2.2                       # the sections' super-ellipse exponent
NS = 12                          # rows across the glass, and segments up one side of a bow (24 round the whole bow)

# ---- the three bows: the member's width (across the glass) and depth (fore and aft)
W_REAR, W_MID, W_WS = 0.050, 0.045, 0.040
D_REAR, D_MID, D_WS = 0.140, 0.050, 0.040


def z_rear_arch(y):
    """the rear arch's FRONT face (it leans back at the top): the glass behind the WSO stops here"""
    return 1.844 + 0.70 * max(0.0, 3.57 - y)


def z_bow_rear(y):
    """the bow between the seats, rear face (it leans forward going up)"""
    return 3.50 - 0.215 * max(0.0, 3.68 - y)


def z_bow_front(y):
    return z_bow_rear(y) + D_MID


def z_front_rear(y):
    """the windscreen bow's rear face: the front panel over the pilot stops here"""
    return 4.89 + 0.18 * (y - 2.96) - D_WS / 2


def z_front_front(y):
    return z_front_rear(y) + D_WS


# the windscreen: from the windscreen bow (z 5.10) to the nose deck (z 5.93): the foot's half width and the crown height
WS_Z = (5.10, 5.93)
WS_XS = [(5.10, 0.500), (5.40, 0.470), (5.70, 0.400), (5.93, 0.290)]
WS_Y = (3.545, 3.100)

# the nose deck's top height beside the windscreen slab, measured on the untouched airframe: rows z, columns x
_SK_Z = [5.10, 5.20, 5.40, 5.60, 5.80, 5.95]
_SK_X = [0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55]
_SK = [[3.250, 3.237, 3.229, 3.217, 3.175, 3.075, 2.975, 2.935],     # z 5.10
       [3.249, 3.237, 3.224, 3.212, 3.135, 3.035, 2.952, 2.908],     # 5.20
       [3.201, 3.189, 3.176, 3.143, 3.073, 2.973, 2.946, 2.874],     # 5.40
       [3.151, 3.139, 3.105, 3.063, 3.011, 2.941, 2.903, 2.840],     # 5.60
       [3.101, 3.068, 3.026, 2.984, 2.969, 2.925, 2.862, 2.800],     # 5.80
       [3.062, 3.037, 3.010, 2.968, 2.925, 2.878, 2.815, 2.755]]     # 5.95

SILL_Y = 2.937                   # the hull's sill beside the pilot: the outline the checks read starts here

# the eye (from the vehicle data); the module also loads without it for the checks
_EYE = np.array([0.0, 3.39, 4.26])


def _eye():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        s = d['Seats'][0]
        return np.array(d['Attachments'][s['CameraPos']['EyeAttachment']]['Position'], float)
    except (OSError, KeyError):
        return _EYE


def skin_y(x, z):
    """the nose deck's height at (|x|, z) beside the windscreen (the skin under its feet, a hair lower)"""
    zi = np.clip(z, _SK_Z[0], _SK_Z[-1])
    col = [np.interp(zi, _SK_Z, [row[j] for row in _SK]) for j in range(len(_SK_X))]
    return float(np.interp(x, _SK_X, col)) - 0.012


def base_crown(z):
    """(x of the glass's foot, y of the foot, y of the crown) at station z: on the rail, or on the nose deck for the windscreen"""
    zs = [r[0] for r in RAIL]
    zc = [c[0] for c in CROWN]
    if z <= RAIL[-1][0]:
        return (float(np.interp(z, zs, [r[1] for r in RAIL])) - 0.0045, float(np.interp(z, zs, [r[2] for r in RAIL])),   # (4.5 mm clear of the rail's inner face)
                float(np.interp(z, zc, [c[1] for c in CROWN])))
    if z >= WS_Z[0]:
        t = (z - WS_Z[0]) / (WS_Z[1] - WS_Z[0])
        xb = float(np.interp(z, [p[0] for p in WS_XS], [p[1] for p in WS_XS]))
        return (xb, skin_y(xb, z), WS_Y[0] + (WS_Y[1] - WS_Y[0]) * t)
    return base_crown(RAIL[-1][0])


def section(z, n=22):
    """the right half-section at station z: absolute (x, y) points from the foot up to the crown (x = 0)"""
    xb, yb, yt = base_crown(z)
    us = np.linspace(0, 1, n)
    pts = [(xb * (1 - u ** NEXP) ** (1 / NEXP), yb + (yt - yb) * u) for u in us]
    pts[-1] = (0.0, yt)
    return pts


STATIONS = [1.95, 2.05, 2.20, 2.40, 2.60, 2.80, 3.00, 3.20, 3.40, 3.60, 3.80, 4.00, 4.20, 4.40, 4.60, 4.80, 5.00, 5.10,
            5.20, 5.40, 5.60, 5.80, 5.93]


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


def row(z, j, ns=NS):
    """point j of the arc-length resampled section at station z (j = 0 the foot, ns the crown)"""
    return resample(section(z), ns + 1)[j]


def edge(zf, j, z, ns, lo, hi):
    """the station where row j of the section meets the frame face zf(y): the row's height depends on the station, so
    the face is found by iterating (the faces lean slowly, a few passes settle it)"""
    for _ in range(6):
        y = row(z, j, ns)[1]
        z = min(hi, max(lo, zf(y)))
    return z


def patch(zf0, zf1, z_lo, z_hi, nz, ns=NS):
    """one glass patch, both sides: row j of the arc-length resampled section runs from the frame face zf0 to the face zf1
    it stops at (both found at the row's own height), so the cut leans with the frame member; z_lo / z_hi clamp the cut"""
    ends = [(edge(zf0, j, z_lo, ns, z_lo, z_hi), edge(zf1, j, z_hi, ns, z_lo, z_hi)) for j in range(ns + 1)]
    out = []
    for side in (1, -1):
        grid = []
        for i in range(nz + 1):
            r = []
            for j in range(ns + 1):
                a, b = ends[j]
                z = a + (b - a) * i / nz
                x, y = row(z, j, ns)
                r.append(np.array([side * x, y, z]))
            grid.append(r)
        for i in range(nz):
            for j in range(ns):
                a, b, c, d = grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]
                for tri in ((a, b, c), (a, c, d)):
                    if np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0])) > 1e-10:
                        out.append(list(tri))
    return out


def glass_triangles():
    tris = patch(z_rear_arch, z_bow_rear, 1.80, 3.55, 24)               # rear panel over the WSO: rear arch -> bow
    tris += patch(z_bow_front, z_front_rear, 3.50, 5.05, 28)            # front panel over the pilot: bow -> windscreen bow
    tris += patch(z_front_front, lambda y: WS_Z[1], 4.80, WS_Z[1], 14)  # the windscreen: windscreen bow -> nose strip
    return tris


# ---------------------------------------------------------------- the bows and the frame (swept members)

class Mesh:
    def __init__(self):
        self.faces = []                      # (4 points vehicle-local, normal, colour key)

    def quad(self, pts, outward, key='frame'):
        P = np.array(pts, float)
        n = np.cross(P[1] - P[0], P[2] - P[0])
        ln = np.linalg.norm(n)
        if ln < 1e-12:
            return
        n /= ln
        if n @ np.array(outward, float) < 0:
            P = P[::-1]; n = -n
        self.faces.append((P, n, key))

    def sweep(self, path, W, D, hint, key='frame', shear=False):
        """a member of a rectangular section W (across) x D (along the hint direction) swept along the polyline path; with
        shear the depth stays exactly along the hint (the faces cut the member at the rows' own stations)"""
        path = [np.array(p, float) for p in path]
        rings, ctr = [], []
        for i, p in enumerate(path):
            t = path[min(i + 1, len(path) - 1)] - path[max(i - 1, 0)]
            t /= np.linalg.norm(t)
            b = np.array(hint, float)
            if not shear:
                b = b - t * (b @ t)
            b /= np.linalg.norm(b)
            a = np.cross(t, b); a /= np.linalg.norm(a)
            rings.append([p + a * W / 2 + b * D / 2, p - a * W / 2 + b * D / 2, p - a * W / 2 - b * D / 2, p + a * W / 2 - b * D / 2])
            ctr.append(p)
        for i in range(len(path) - 1):
            for k in range(4):
                q = [rings[i][k], rings[i][(k + 1) % 4], rings[i + 1][(k + 1) % 4], rings[i + 1][k]]
                mid = np.mean(q, 0); c = (ctr[i] + ctr[i + 1]) / 2
                self.quad(q, mid - c, key)
        for ring, c, sgn in ((rings[0], ctr[0], -1), (rings[-1], ctr[-1], 1)):
            t = (path[1] - path[0]) if sgn < 0 else (path[-1] - path[-2])
            self.quad(ring, t * sgn, key)

    def loft(self, rects, key='frame'):
        """a solid lofted through rectangles [(z, x0, x1, y0, y1)] (x0 < x1, y0 < y1), closed at both ends"""
        def ring(r):
            z, x0, x1, y0, y1 = r
            return [np.array(p, float) for p in ((x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z))]
        rings = [ring(r) for r in rects]
        for a, b in zip(rings[:-1], rings[1:]):
            for k in range(4):
                q = [a[k], a[(k + 1) % 4], b[(k + 1) % 4], b[k]]
                mid = np.mean(q, 0)
                ctr = np.mean(a + b, 0)
                self.quad(q, mid - ctr, key)
        self.quad(rings[0], np.array([0, 0, -1.0]), key)
        self.quad(rings[-1], np.array([0, 0, 1.0]), key)

    def box(self, lo, hi, key='frame'):
        """an axis-aligned box from corner lo to corner hi"""
        lo, hi = np.array(lo, float), np.array(hi, float)
        c = (lo + hi) / 2
        for ax in range(3):
            for s in (-1, 1):
                o = [k for k in range(3) if k != ax]
                f = c.copy(); f[ax] = hi[ax] if s > 0 else lo[ax]
                q = []
                for da, db in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
                    p = f.copy(); p[o[0]] = (hi[o[0]] if da > 0 else lo[o[0]]); p[o[1]] = (hi[o[1]] if db > 0 else lo[o[1]])
                    q.append(p)
                out = np.zeros(3); out[ax] = s
                self.quad(q, out, key)

    def poly_mesh(self, uvs):
        P, N, U, polys = [], [], [], []
        uvi = {}
        for pts, n, key in self.faces:
            px = [[float(p[0]) * 16, float(p[1]) * 16, -float(p[2]) * 16] for p in pts]
            npx = np.array([n[0], n[1], -n[2]])
            a, b, c = (np.array(q) for q in px[:3])
            if np.cross(b - a, c - a) @ npx > 0:          # (file pixels wind clockwise seen from the front)
                px = px[::-1]
            ni = len(N); N.append([round(float(x), 5) + 0.0 for x in -npx])
            if key not in uvi:
                uvi[key] = len(U); U.append([round(uvs[key][0], 6), round(uvs[key][1], 6)])
            poly = []
            for p in px:
                poly.append([len(P), ni, uvi[key]])
                P.append([round(x, 4) + 0.0 for x in p])
            polys.append(poly)
        return {'positions': P, 'normals': N, 'uvs': U, 'polys': polys}


def arch_path(zf, j_rows, depth, behind, lo, hi, foot_drop=0.03):
    """the centre line of a bow over the glass rows: up the left leg, over the crown, down the right leg (ONE member).
    The member's face is the glass edge zf(y) at each row; the member lies behind it (the glass ends on its front face) or
    ahead of it (the glass starts on its rear face)."""
    pts = []
    for j in range(j_rows + 1):
        z0 = edge(zf, j, lo if behind else hi, j_rows, lo, hi)
        zc = z0 - depth / 2 if behind else z0 + depth / 2
        x, y = row(zc, j, j_rows)
        pts.append((x, y, zc))
    d0 = np.array(pts[1]) - np.array(pts[0]); d0 /= np.linalg.norm(d0)
    pts[0] = tuple(np.array(pts[0]) - d0 * foot_drop)             # the foot sinks into the rail, along its own line
    left = [(-x, y, z) for x, y, z in pts]                        # left foot -> crown
    return left + [(x, y, z) for x, y, z in pts[::-1]][1:]        # ...then down the right leg (the crown point once)


def windscreen_frame_paths(z_start):
    """the thin frame along the windscreen glass's feet on the nose deck: a foot strip each side and the front cross strip
    over the glass's front edge (from foot to foot over the crown)"""
    zs = [z for z in STATIONS if z > z_start] + [WS_Z[1]]
    zs = sorted(set([z_start] + [round(z, 4) for z in zs]))
    feet = []
    for side in (1, -1):
        p = []
        for z in zs:
            xb, yb, _ = base_crown(z)
            p.append((side * (xb + 0.0045 + 0.015), yb - 0.021, z))
        feet.append(p)
    z1 = WS_Z[1]
    sec = resample(section(z1), NS + 1)
    right = [(x, y, z1 + 0.015) for x, y in sec]
    cross = [(-x, y, z) for x, y, z in right[::-1]][:-1] + right
    return feet, cross


def mirror_boxes(m, y_arch, z_face):
    """the rear-view mirror under the windscreen bow's crown: a 25 x 8 cm dark face in a 1.35 cm frame; y_arch is the bow's lower edge at the crown, z_face the bow's rear face there"""
    w, h, d, t = 0.25, 0.08, 0.025, 0.0135
    y1 = y_arch + 0.010; y0 = y1 - h               # the housing's top 10 mm into the bow (the box hangs straight off it)
    z1 = z_face + 0.003; z0 = z1 - d               # the face looks back at the pilot (-z); the housing's front bites 3 mm into the bow
    m.box((-w / 2, y0, z0), (w / 2, y1, z1), 'mfr')                                       # the housing: a plain box, the frame round the face
    m.box((-w / 2 + t, y0 + t, z0 - 0.014), (w / 2 - t, y1 - t, z0 + 0.004), 'mirror')   # the mirror face: a dark plate 14 mm proud of the housing, looking -z


RAIL_END = 4.93                  # the sill rail ends inside the windscreen bow's depth
RAIL_Z = [1.87, 1.95, 2.072, 2.112, 2.20, 2.40, 2.60, 2.795, 2.835, 3.00, 3.20, 3.428, 3.468, 3.60, 3.80, 4.00, 4.151, 4.191,
          4.40, 4.60, RAIL_END]
RAIL_W = [(1.87, 0.100), (3.00, 0.090), (4.20, 0.090), (RAIL_END, 0.080)]      # the rail's width (inner face to outer face)
# its underside, 8 mm into the sill slab that the hull's own steps make (it steps down toward the nose in short ramps, clear of
# the hull's steps; 12 mm under the slab's underside where the slab is thin)
RAIL_BOT = [(1.87, 3.171), (2.072, 3.171), (2.112, 3.081), (2.795, 3.081), (2.835, 3.013), (3.428, 3.013), (3.468, 2.925),
            (4.151, 2.925), (4.191, 2.900), (RAIL_END, 2.900)]
RAIL_IN = 0.045                  # the rail's top reaches this far inboard of the glass foot's line


def rail_rects(side):
    zr = [r[0] for r in RAIL]
    out = []
    for z in RAIL_Z:
        xi = float(np.interp(z, [1.87] + zr, [0.430] + [r[1] for r in RAIL]))      # inner top edge x
        yt = float(np.interp(z, [1.87] + zr, [3.340] + [r[2] for r in RAIL]))      # its height
        w = float(np.interp(z, [a for a, _ in RAIL_W], [b for _, b in RAIL_W]))
        yb = float(np.interp(z, [a for a, _ in RAIL_BOT], [b for _, b in RAIL_BOT]))
        x0, x1 = xi - RAIL_IN, xi + w
        out.append((z, x0, x1, yb, yt) if side > 0 else (z, -x1, -x0, yb, yt))
    return out


OWN_RAILS = False                # True builds the sill rails as lofted solids (the F-15C way); the airframe's own fit this hull's sill


def cut_old_frame(bone, marks):
    """removes from the canopy bone the faces an earlier run of this script made (their uv is a marked swatch) and the airframe's
    old arches, the bow's legs and the centre strips: everything above the sill rails, whose own faces stay. Returns the count."""
    pm = bone['poly_mesh']
    P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    U = np.array(pm['uvs'], float)
    keep, gone = [], 0
    for poly in pm['polys']:
        if not poly:
            continue
        V = P[[v[0] for v in poly]]
        uv = U[poly[0][2]]
        ymax, zc = V[:, 1].max(), V[:, 2].mean()
        drop = (any(np.abs(uv - m).max() < 2.5e-6 for m in marks)                    # (made by an earlier run)
                or ymax > 3.34
                or (zc >= 4.78 and ymax > 2.975)                                     # the windscreen bow's legs
                or (3.30 <= zc <= 3.66 and ymax > 3.125)                             # the bow's legs
                or (zc < 2.0 and ymax > 3.325))                                      # the rear arch's legs
        if drop:
            gone += 1
        else:
            keep.append(poly)
    pm['polys'] = keep
    return gone


def append_mesh(pm, new):
    """adds a poly mesh to the bone's, shifting the indices"""
    p0, n0, u0 = len(pm['positions']), len(pm['normals']), len(pm['uvs'])
    pm['positions'] += new['positions']; pm['normals'] += new['normals']; pm['uvs'] += new['uvs']
    for poly in new['polys']:
        pm['polys'].append([[v[0] + p0, v[1] + n0, v[2] + u0] for v in poly])


def compact(canopy_bone):
    """drop the positions / normals / uvs no face uses any more (so a re-run writes the same bytes), keeping the order"""
    pm = canopy_bone['poly_mesh']
    maps = [{}, {}, {}]
    new = [[], [], []]
    src = [pm['positions'], pm['normals'], pm['uvs']]
    polys = []
    for poly in pm['polys']:
        np_ = []
        for v in poly:
            idx = []
            for k in range(3):
                if v[k] not in maps[k]:
                    maps[k][v[k]] = len(new[k]); new[k].append(src[k][v[k]])
                idx.append(maps[k][v[k]])
            np_.append(idx)
        polys.append(np_)
    pm['positions'], pm['normals'], pm['uvs'], pm['polys'] = new[0], new[1], new[2], polys


def frame_mesh():
    m = Mesh()
    if OWN_RAILS:
        for side in (1, -1):
            m.loft(rail_rects(side), 'grey')                                                                # the sill rails
    m.sweep(arch_path(z_rear_arch, NS, D_REAR, True, 1.80, 2.30), W_REAR, D_REAR, [0, 0, 1], shear=True)     # rear arch
    m.sweep(arch_path(z_bow_rear, NS, D_MID, False, 3.30, 3.66), W_MID, D_MID, [0, 0, 1], shear=True)       # bow between the seats
    wp = arch_path(z_front_rear, NS, D_WS, False, 4.70, 5.05)
    m.sweep(wp, W_WS, D_WS, [0, 0, 1], shear=True)                                                          # windscreen bow
    crown = wp[len(wp) // 2]
    mirror_boxes(m, crown[1] - W_WS / 2, crown[2] - D_WS / 2)
    return m, wp


def find_swatch(rgb, avoid):
    """uv of the centre of the uniform 4 x 4 texel block of the airframe's texture nearest rgb (read only); avoid = the
    cockpit script's paint rect (x, y, w, h)"""
    from PIL import Image
    Image.MAX_IMAGE_PIXELS = None
    try:
        a = np.asarray(Image.open(TEX).convert('RGBA'), dtype=np.int16)
    except Exception:
        return None
    h, w = a.shape[:2]
    blocks = a[:h // 4 * 4, :w // 4 * 4].reshape(h // 4, 4, w // 4, 4, 4)
    flat = (blocks.max((1, 3)) - blocks.min((1, 3))).max(-1) == 0
    opaque = blocks[:, 0, :, 0, 3] == 255
    col = blocks[:, 0, :, 0, :3]
    dist = np.abs(col - np.array(rgb)).sum(-1).astype(float)
    dist[~(flat & opaque)] = 1e9
    x, y, aw, ah = avoid
    dist[y // 4:(y + ah) // 4 + 1, x // 4:(x + aw) // 4 + 1] = 1e9
    by, bx = np.unravel_index(np.argmin(dist), dist.shape)
    print(f'swatch: texel block ({bx * 4}, {by * 4}) = {tuple(int(v) for v in col[by, bx])}, wanted {rgb}')
    return [(bx * 4 + 2) / TW + MARK, 1.0 - (by * 4 + 2) / TH + MARK]


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    uvs = {'frame': find_swatch(FRAME_RGB, PAINT), 'mirror': find_swatch(MIRROR_RGB, PAINT),
           'mfr': find_swatch(MIRROR_FRAME_RGB, PAINT), 'grey': find_swatch(RAIL_RGB, PAINT)}
    if any(v is None for v in uvs.values()):
        raise SystemExit('cannot read the airframe texture for the frame colours')
    # the nose's black windscreen slab (a stand-in for glass): the deck under it, 3.07 to 3.25, stays
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    pm = hull['poly_mesh']
    Ph = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    keep, slab, twin = [], 0, 0
    for poly in pm['polys']:
        if poly:
            V = Ph[[v[0] for v in poly]]
            if np.abs(V[:, 0]).max() <= 0.1865 and np.ptp(V[:, 2]) > 0.4 and V[:, 1].min() > 3.09 and V[:, 2].min() > 5.05:
                slab += 1
                continue
            # the old windscreen arch's twin in the hull (its legs and crown, and the little posts of the old sight inside it):
            # the new bow replaces it; the deck (up to 3.253) and the arch feet stay
            zc, ym, xm = V[:, 2].mean(), V[:, 1].max(), abs(V[:, 0].mean())
            if 4.85 <= zc <= 5.12 and np.ptp(V[:, 2]) < 0.10 and (ym >= 3.275 or (xm > 0.42 and ym > 2.975)):     # (not the dash's side wall, 0.23 deep)
                twin += 1
                continue
        keep.append(poly)
    pm['polys'] = keep
    canopy = next(b for b in bones if b['name'] == CANOPY_BONE)
    if OWN_RAILS:
        old = len(canopy['poly_mesh']['polys'])
        canopy['poly_mesh'] = {'positions': [], 'normals': [], 'uvs': [], 'polys': []}
    else:
        old = cut_old_frame(canopy, list(uvs.values()))
        compact(canopy)
    fm, wp = frame_mesh()
    feet, cross = windscreen_frame_paths(wp[0][2] + D_WS / 2 - 0.015)
    for p in feet:
        fm.sweep(p, 0.030, 0.070, [0, 1, 0])
    fm.sweep(cross, 0.030, 0.030, [0, 0, 1])
    append_mesh(canopy['poly_mesh'], fm.poly_mesh(uvs))
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'f_15ex canopy: {old} old canopy faces removed, {slab} nose slab and {twin} old arch-twin hull faces removed; '
          f'{len(fm.faces)} new frame faces (three {2 * NS}-segment bows, rails, windscreen frame, mirror); '
          f'{len(tris)} glass triangles (x2 windings)')
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
