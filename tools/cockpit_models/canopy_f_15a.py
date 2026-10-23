#!/usr/bin/env python3
"""F-15A canopy, fitted to the F-15A.mtb airframe (the same frame as the F-15C): the glass for the sill rails, and slim hand-built arches.

The F-15A canopy (side views and photos of the type): a large one-piece blown bubble running from the sills up over the
pilot to the spine, held by its rail along each sill and the rear arch on the spine, with the windscreen ahead of the
pilot: the front arch (the windscreen frame, slim, the HUD tucked under its rear face) and the glass sloping from it down
to the nose deck. There is no bow between the arches.

The airframe's own frame is thick and faceted (arches 9 to 18 cm deep in five flat segments, pale stand-in strips along the
crown, a bow behind the headrest), so this script keeps only its sill rails and the feet of the arches and builds:

  * the front (windscreen) arch and the rear arch, each ONE swept member of a slim rectangular section (4.5 to 5 cm across,
    4.5 to 5 cm deep) running up one leg, over the crown and down the other along a smooth 24-segment curve: one member, not
    two bars side by side, leaning like the airframe's own (the front arch over the HUD, the rear arch back at the foot);
  * a thin windscreen frame along the glass's feet on the nose deck (two foot strips and the front cross strip);
  * the rear-view mirror: a dark mirror face in a thin frame, 25 x 8 cm, hung under the front arch's crown behind the HUD;
  * two glass patches: the canopy (from the rear arch's front face to the front arch's rear face) and the windscreen (from
    the front arch's front face to the nose deck). Each starts on the inner top edge of the sill rail and stops at the face
    of the member it meets (the arches lean, so the cut leans with them), never running under it, and the glass rows are
    the very rows the arches are swept along, so the glass ends flush on the arch and never stands above it;
  * the section at every station is a super-ellipse from the rail up to the crown, the crown line taken from the old frame's
    centre strip (3.67 over the pilot, falling to 3.49 at the front arch); the windscreen's crown follows the airframe's own
    windscreen stand-in down to the nose deck (3.46 at the arch, 3.11 at its foot);
  * the airframe's stand-ins for glass are removed, since the real glass takes their place: the long thin centre-strip
    pieces of the frame (opaque pale quads along the top) and the narrow black windscreen slab of the nose.

The new members are painted with texels of the airframe's own texture (dark frame grey, mirror black), marked by a sub-texel
uv offset so a re-run finds and replaces them (the script is idempotent on its own output).

CANOPY (eye-relative sections, the right half from the sill up over the top) is what the checks in
work/elite_expansion_2/checks read for the canopy outline. Frames: vehicle-local blocks, model pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script f_15a.py then appends the HUD combiner to CanopyGlass.
Usage: python tools/cockpit_models/canopy_f_15a.py [--dry-run]
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/f_15a.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/f_15a.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/f_15a.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/f_15a.png')
CANOPY_BONE = 'wreck_fuselage_0__canopy'
HULL_BONE = 'wreck_fuselage_0__hull'
TW, TH = 4096, 4096
PAINT = (1500, 16, 2560, 3000)   # the cockpit script's paint area of the texture (x, y, w, h): not the airframe's texels
FRAME_RGB = (46, 50, 56)         # the frame's colour: dark blue-grey, nearest uniform texel block of the airframe's texture
MIRROR_RGB = (18, 20, 24)        # the mirror's face: near black
RAIL_RGB = (96, 100, 102)        # the sill rails: mid grey paint
MIRROR_FRAME_RGB = (104, 108, 112)   # its thin frame: light metal grey
MARK = 1.1e-4                    # the sub-texel uv offset that marks a face this script made

# ---- the frame's measurements (absolute blocks)
# the sill rails' inner top edge: z, x of the inner edge, y of the top
RAIL = [(2.20, 0.425, 3.285), (2.30, 0.452, 3.265), (2.50, 0.463, 3.242), (2.80, 0.481, 3.183), (3.10, 0.492, 3.137),
        (3.40, 0.497, 3.094), (3.62, 0.497, 3.065), (4.00, 0.512, 3.039), (4.40, 0.523, 3.010), (4.80, 0.528, 2.980),
        (5.00, 0.530, 2.965), (5.22, 0.531, 2.960)]
# the crown line over the centre (z, y): the old frame's centre strip, smoothed
CROWN = [(2.20, 3.485), (2.50, 3.515), (2.80, 3.545), (3.10, 3.575), (3.40, 3.605), (3.65, 3.628), (4.00, 3.662),
         (4.30, 3.668), (4.60, 3.630), (4.80, 3.600), (5.00, 3.550), (5.12, 3.520), (5.22, 3.490)]
NEXP = 2.2                       # the sections' super-ellipse exponent
NS = 12                          # rows across the glass, and segments up one side of an arch (24 round the whole arch)

# ---- the two arches: the member's width (across the glass) and depth (fore and aft)
W_REAR, D_REAR = 0.050, 0.140
W_WS, D_WS = 0.045, 0.045


def z_rear_arch(y):
    """the rear arch's FRONT face (it leans back at the bottom): the canopy glass stops here"""
    return 2.20 + 0.85 * (3.485 - min(y, 3.485))


def z_front_rear(y):
    """the front arch's REAR face (it leans forward over the HUD): the canopy glass stops here"""
    return max(5.10, 5.11 + 0.27 * (y - 3.05))


def z_front_front(y):
    return z_front_rear(y) + D_WS


# the windscreen: from the front arch to the nose deck (z 5.90): half width and crown height of its start
WS_Z = (5.31, 5.90)
WS_X = (0.470, 0.200)
WS_Y = (3.460, 3.112)

SILL_Y = 2.937                   # the hull's sill: the outline the checks read starts here

# the eye (from the vehicle data), set by main(); the module also loads without it for the checks
_EYE = np.array([0.0, 3.265, 4.40])


def _eye():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        s = d['Seats'][0]
        return np.array(d['Attachments'][s['CameraPos']['EyeAttachment']]['Position'], float)
    except (OSError, KeyError):
        return _EYE


def skin_y(x):
    """the nose deck's height at |x| beside the windscreen (the skin under its feet, a hair lower)"""
    return 3.150 - 0.40 * x - 0.012


def base_crown(z):
    """(x of the glass's foot, y of the foot, y of the crown) at station z: on the rail, or on the nose deck for the windscreen"""
    zs = [r[0] for r in RAIL]
    if z <= RAIL[-1][0]:
        zc = [c[0] for c in CROWN]
        return (float(np.interp(z, zs, [r[1] for r in RAIL])) - 0.0045, float(np.interp(z, zs, [r[2] for r in RAIL])),    # (4.5 mm clear of the rail's inner face)
                float(np.interp(z, zc, [c[1] for c in CROWN])))
    if z >= WS_Z[0]:
        t = (z - WS_Z[0]) / (WS_Z[1] - WS_Z[0])
        xb = WS_X[0] + (WS_X[1] - WS_X[0]) * t
        return (xb, skin_y(xb), WS_Y[0] + (WS_Y[1] - WS_Y[0]) * t)
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


STATIONS = [2.20, 2.30, 2.40, 2.60, 2.80, 3.00, 3.20, 3.40, 3.60, 3.80, 4.00, 4.20, 4.40, 4.60, 4.80, 5.00, 5.10, 5.22,
            5.31, 5.40, 5.50, 5.60, 5.70, 5.80, 5.90]


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
    # the canopy is ONE piece of glass from the rear arch to the front arch (the real F-15A has no bow between them), and
    # the windscreen is its own piece from the front arch to the frame strip across the nose deck
    tris = patch(z_rear_arch, z_front_rear, 2.20, 5.22, 52)            # canopy: rear arch -> front arch
    tris += patch(z_front_front, lambda y: WS_Z[1], 5.10, WS_Z[1], 14)  # the windscreen: front arch -> nose strip
    return tris


# ---------------------------------------------------------------- the arches and the frame (swept members)

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
        c = np.mean([[(r[1] + r[2]) / 2, (r[3] + r[4]) / 2, r[0]] for r in rects], 0)
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
            if np.cross(b - a, c - a) @ npx > 0:          # (as f_15ex.py: file pixels wind clockwise seen from the front)
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
    """the centre line of an arch over the glass rows: up the left leg, over the crown, down the right leg (ONE member).
    The member's face is the glass edge zf(y) at each row; the member lies behind it (the canopy glass ends on its front
    face) or ahead of it (the glass starts on its rear face)."""
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
    """the rear-view mirror under the front arch's crown: a 25 x 8 cm dark face in a 1 cm frame, 2.5 cm deep, on a short stalk;
    y_arch is the arch's lower edge at the crown, z_face the arch's rear face there"""
    w, h, d, t = 0.25, 0.08, 0.025, 0.0135
    y1 = y_arch - 0.012; y0 = y1 - h               # the housing's top 12 mm under the arch (the stalk spans it)
    z1 = z_face + 0.003; z0 = z1 - d               # the face looks back at the pilot (-z); the stalk bites 3 mm into the arch
    m.box((-w / 2, y0, z0), (w / 2, y1, z1), 'mfr')                                       # the housing: a plain box, the frame round the face
    m.box((-w / 2 + t, y0 + t, z0 - 0.002), (w / 2 - t, y1 - t, z0 + 0.0005), 'mirror')   # the mirror face, 2 mm proud of the housing, looking -z
    m.box((-0.012, y1 - 0.006, z1 - 0.024), (0.012, y_arch + 0.015, z1 - 0.004), 'mfr')   # the stalk up into the arch

RAIL_Z = [2.14, 2.20, 2.30, 2.50, 2.615, 2.655, 2.80, 3.10, 3.202, 3.242, 3.40, 3.609, 3.649, 4.00, 4.151, 4.191, 4.40, 4.80, 5.00, 5.13]
RAIL_W = [(2.14, 0.120), (3.00, 0.100), (4.20, 0.090), (5.13, 0.080)]       # the rail's width (inner face to outer face)
# its underside, 8 mm into the sill slab that the hull's own steps make (it steps down toward the nose in short ramps, clear of the hull's steps)
RAIL_BOT = [(2.14, 3.126), (2.615, 3.126), (2.655, 3.035), (3.202, 3.035), (3.242, 2.968), (3.609, 2.968), (3.649, 2.925),
            (4.151, 2.925), (4.191, 2.900), (5.13, 2.900)]
RAIL_IN = 0.015                  # the rail's top reaches this far inboard of the glass foot's line


def rail_rects(side):
    zr = [r[0] for r in RAIL]
    out = []
    for z in RAIL_Z:
        xi = float(np.interp(z, [2.14] + zr, [0.400] + [r[1] for r in RAIL]))      # inner top edge x
        yt = float(np.interp(z, [2.14] + zr, [3.297] + [r[2] for r in RAIL]))      # its height
        w = float(np.interp(z, [a for a, _ in RAIL_W], [b for _, b in RAIL_W]))
        yb = float(np.interp(z, [a for a, _ in RAIL_BOT], [b for _, b in RAIL_BOT]))
        x0, x1 = xi - RAIL_IN, xi + w
        out.append((z, x0, x1, yb, yt) if side > 0 else (z, -x1, -x0, yb, yt))
    return out


def frame_mesh():
    m = Mesh()
    for side in (1, -1):
        m.loft(rail_rects(side), 'grey')                                                                  # the sill rails
    m.sweep(arch_path(z_rear_arch, NS, D_REAR, True, 2.20, 3.5), W_REAR, D_REAR, [0, 0, 1], shear=True)     # rear arch
    wp = arch_path(z_front_rear, NS, D_WS, False, 4.9, 5.30)
    m.sweep(wp, W_WS, D_WS, [0, 0, 1], shear=True)                                                       # front (windscreen) arch
    crown = wp[len(wp) // 2]
    mirror_boxes(m, crown[1] - W_WS / 2, crown[2] - D_WS / 2)
    return m, wp


def frame_foot_start(wp):
    """z where the windscreen frame's foot strips start: the front arch's front face at the foot"""
    return wp[0][2] + D_WS / 2


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
    print(f'swatch: texel block ({bx * 4}, {by * 4}) = {tuple(col[by, bx])}, wanted {rgb}')
    return [(bx * 4 + 2) / TW + MARK, 1.0 - (by * 4 + 2) / TH + MARK]


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
    frame's rail end caps, which the source builds twice. The twin stays; the frame's copy goes (it only z-fights, nothing
    shows through its place). Returns how many."""
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
                if abs(c[0]) < 0.75 and 2.0 < c[2] < 6.2 and 2.2 < c[1] < 3.8:
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


def cut_old_frame(bone, marks):
    """removes from the canopy bone: the faces this script made on an earlier run (their uv is a marked swatch), the
    airframe's old arches (the rear arch's legs and crown, the front arch with its legs, the bow behind the headrest and
    the old windscreen strips) and the centre-strip pieces. The sill rails and the arch feet stay. Returns the count."""
    pm = bone['poly_mesh']
    P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    U = np.array(pm['uvs'], float)
    keep, gone = [], 0
    for poly in pm['polys']:
        if not poly:
            continue
        V = P[[v[0] for v in poly]]
        uv = U[poly[0][2]]
        ymin, ymax = V[:, 1].min(), V[:, 1].max()
        zmin, zmax, zc = V[:, 2].min(), V[:, 2].max(), V[:, 2].mean()
        drop = (any(np.abs(uv - m).max() < 2.5e-6 for m in marks)                    # (made by an earlier run)
                or (zmax <= 2.40 and ymax > 3.24)                                    # the rear arch
                or (zmin >= 5.07 and ymax > 2.9605)                                  # the front arch, its legs and the old strips
                or (zmin >= 3.555 and zmax <= 3.77 and ymin >= 3.055)                # the bow behind the headrest
                or (zmin >= 3.555 and zmax <= 3.655 and ymin >= 2.955 and ymax <= 3.075)    # ...with its knuckle on the rail
                or (ymin > 3.55 and 2.4 < zc < 5.0))                                 # a centre-strip piece
        if drop:
            gone += 1
        else:
            keep.append(poly)
    pm['polys'] = keep
    return gone


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


def append_mesh(pm, new):
    """adds a poly mesh to the bone's, shifting the indices"""
    p0, n0, u0 = len(pm['positions']), len(pm['normals']), len(pm['uvs'])
    pm['positions'] += new['positions']; pm['normals'] += new['normals']; pm['uvs'] += new['uvs']
    for poly in new['polys']:
        pm['polys'].append([[v[0] + p0, v[1] + n0, v[2] + u0] for v in poly])


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    uv_frame = find_swatch(FRAME_RGB, PAINT)
    uv_mirror = find_swatch(MIRROR_RGB, PAINT)
    uv_mfr = find_swatch(MIRROR_FRAME_RGB, PAINT)
    uv_grey = find_swatch(RAIL_RGB, PAINT)
    if uv_frame is None or uv_mirror is None or uv_mfr is None:
        raise SystemExit('cannot read the airframe texture for the frame colours')
    gone = 0
    # the stand-ins: the nose's black windscreen slab
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    pm = hull['poly_mesh']
    Ph = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    keep = []
    for poly in pm['polys']:
        if poly and np.abs(Ph[[v[0] for v in poly]][:, 0]).max() <= 0.1865 and np.ptp(Ph[[v[0] for v in poly]][:, 2]) > 0.4 \
                and Ph[[v[0] for v in poly]][:, 1].min() > 3.0 and Ph[[v[0] for v in poly]][:, 2].min() > 5.3:
            gone += 1
            continue
        keep.append(poly)
    pm['polys'] = keep
    canopy = next(b for b in bones if b['name'] == CANOPY_BONE)
    old = len(canopy['poly_mesh']['polys'])               # the whole airframe frame goes: the rails are rebuilt below
    canopy['poly_mesh'] = {'positions': [], 'normals': [], 'uvs': [], 'polys': []}
    gone_twin = 0
    fm, wp = frame_mesh()
    feet, cross = windscreen_frame_paths(frame_foot_start(wp) - 0.015)
    for p in feet:
        fm.sweep(p, 0.030, 0.070, [0, 1, 0])
    fm.sweep(cross, 0.030, 0.030, [0, 0, 1])
    append_mesh(canopy['poly_mesh'], fm.poly_mesh({'frame': uv_frame, 'mirror': uv_mirror, 'mfr': uv_mfr, 'grey': uv_grey}))
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'f_15a canopy: {old} old frame faces removed (arches, strips, bow), {gone} nose slab faces, {gone_twin} doubled rail faces dropped; '
          f'{len(fm.faces)} new frame faces (two {2 * NS}-segment arches, windscreen frame, mirror); {len(tris)} glass triangles (x2 windings)')
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
