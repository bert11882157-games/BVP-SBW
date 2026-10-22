#!/usr/bin/env python3
"""F-15C canopy, fitted to the F-15C.mtb airframe: the glass for the source's own canopy frame.

The F-15C canopy (side views and photos of the type): a large one-piece blown bubble running from the sills up over the
pilot to the spine, held by its rail along each sill, the rear arch on the spine and one bow across behind the seat's
headrest, with the windscreen ahead of the pilot: the front arch (the windscreen frame, the HUD tucked under its rear
face) and the glass sloping from it down to the nose deck.

The airframe already carries exactly that frame (bone wreck_fuselage_0__canopy, one connected piece: the two sill rails, the
rear arch, the bow behind the headrest, the front arch leaning forward over the HUD), so this script keeps it as THE frame,
never adds a second one, and fits the glass to it:

  * three glass patches: the bubble's rear panel (from the front face of the rear arch to the rear face of the bow), its front
    panel (from the bow's front face to the rear face of the front arch) and the windscreen (from the front arch's front
    face to the nose deck). Every patch starts on the inner top edge of the sill rail, and each stops at the face of the
    frame member it meets (the arches lean, so the cut leans with them), never running under it;
  * the section at every station is a super-ellipse from the rail up to the crown, the crown line taken from the frame's own
    centre strip (3.67 over the pilot, falling to 3.49 at the front arch); the windscreen's crown follows the airframe's own
    windscreen stand-in down to the nose deck (3.46 at the arch, 3.11 at its foot);
  * the airframe's stand-ins for glass are removed, since the real glass takes their place: the two long thin centre-strip
    pieces of the frame (opaque pale quads along the top) and the narrow black windscreen slab of the nose.

CANOPY (eye-relative sections, the right half from the sill up over the top) is what the checks in
work/elite_expansion_2/checks read for the canopy outline. Frames: vehicle-local blocks, model pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script f_15c.py then appends the HUD combiner to CanopyGlass.
Usage: python tools/cockpit_models/canopy_f_15c.py [--dry-run]
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/f_15c.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/f_15c.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/f_15c.json')
CANOPY_BONE = 'wreck_fuselage_0__canopy'
HULL_BONE = 'wreck_fuselage_0__hull'

# ---- the frame's measurements (absolute blocks)
# the sill rails' inner top edge: z, x of the inner edge, y of the top
RAIL = [(2.20, 0.425, 3.285), (2.30, 0.452, 3.265), (2.50, 0.463, 3.242), (2.80, 0.481, 3.183), (3.10, 0.492, 3.137),
        (3.40, 0.497, 3.094), (3.62, 0.497, 3.065), (4.00, 0.512, 3.039), (4.40, 0.523, 3.010), (4.80, 0.528, 2.980),
        (5.00, 0.530, 2.965), (5.22, 0.531, 2.960)]
# the crown line over the centre (z, y): the frame's centre strip, smoothed
CROWN = [(2.20, 3.485), (2.50, 3.515), (2.80, 3.545), (3.10, 3.575), (3.40, 3.605), (3.65, 3.628), (4.00, 3.662),
         (4.30, 3.668), (4.60, 3.630), (4.80, 3.600), (5.00, 3.550), (5.12, 3.520), (5.22, 3.490)]
NEXP = 2.2                       # the sections' super-ellipse exponent

# the faces of the frame members the glass stops at (y -> z): the rear arch's front face (it leans back at the bottom),
# the bow's rear and front faces (0.10 thick), the front arch's rear and front faces (it leans forward over the HUD)
def z_rear_arch(y):
    return 2.20 + 0.85 * (3.485 - min(y, 3.485))


def z_bow_rear(y):
    return 3.67 - 0.27 * max(0.0, 3.55 - y)


def z_bow_front(y):
    return z_bow_rear(y) + 0.10


def z_front_rear(y):
    return max(5.10, 5.11 + 0.27 * (y - 3.05))


def z_front_front(y):
    return max(5.27, z_front_rear(y) + 0.10)


# the windscreen: from the front arch (z 5.31) to the nose deck (z 5.90): half width and crown height
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
        return (float(np.interp(z, zs, [r[1] for r in RAIL])), float(np.interp(z, zs, [r[2] for r in RAIL])),
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


def edge(zf, j, z, ns, lo, hi):
    """the station where row j of the section meets the frame face zf(y): the row's height depends on the station, so
    the face is found by iterating (the faces lean slowly, three passes settle it)"""
    for _ in range(4):
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


def glass_triangles():
    tris = patch(z_rear_arch, z_bow_rear, 2.20, 3.70, 22)             # rear panel: rear arch -> bow
    tris += patch(z_bow_front, z_front_rear, 3.55, 5.22, 30)           # front panel: bow -> front arch
    tris += patch(z_front_front, lambda y: WS_Z[1], 5.27, WS_Z[1], 14)  # the windscreen: front arch -> nose deck
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
    frame's rail end caps and the arch's doubled corner pieces, which the source builds twice. The twin stays; the frame's
    copy goes (it only z-fights, nothing shows through its place). Returns how many."""
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


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    gone = 0
    # the stand-ins: the frame's centre strip pieces, and the nose's black windscreen slab
    for name, test in ((CANOPY_BONE, lambda V: np.abs(V[:, 0]).max() <= 0.1865 and np.ptp(V[:, 2]) > 0.35),
                       (HULL_BONE, lambda V: np.abs(V[:, 0]).max() <= 0.1865 and np.ptp(V[:, 2]) > 0.4 and V[:, 1].min() > 3.0
                        and V[:, 2].min() > 5.3)):
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
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'f_15c canopy: {gone} stand-in faces removed (frame centre strips, nose windscreen slab), {gone_twin} doubled frame faces dropped; {len(tris)} glass triangles (x2 windings)')
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
