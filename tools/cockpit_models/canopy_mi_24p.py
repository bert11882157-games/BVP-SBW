#!/usr/bin/env python3
"""Mil Mi-24P canopies, rebuilt to the exact envelope of the Mi-24P.mtb model's own canopies, in flat glass.

The original model carries two open-frame canopies (no glass): the operator's in the nose (low): one raked oval windscreen hoop, a sill rail
along each side, a roof rail; the pilot's behind and above it: one raked oval windscreen hoop, a beltline rail rising to the rear, a B post and
its roof rails. The oval hoops are flat to 6 mm (the author built them flat). That canopy is the MOULD here: its frame members (the hoops, the
sill and beltline rails, the roof rails, the B post, the wipers) are cut out of the `hull` piece completely (CUT_RANGES, with the old seat
groups, panels, tub interior and the partition between the crews; mi24p/gen_cut.py minus mi24p/frame_sets.py), and the two canopies are built
new from the member centre lines, which are the corners below (the model's element numbers in the comments). The skin outside the canopy (the
sill planks, the side walls beside the pilot, the nose) stays as modelled.

  OPERATOR  windscreen (1 flat pane in the hoop's outline), a side window each side (sill rail up to the roof rail, cut into 3 flat facets), a roof
            pane between the roof rails, a small corner facet where the hoop's top meets the roof rail;
  PILOT     windscreen (1 flat pane in the hoop's outline), the side window each side cut at the B post (front, rear; 3 and 2 flat facets), a roof
            pane, a corner facet at each top corner of the hoop.

EVERY glass facet is exactly one plane (printed: flat to 0.000 mm; planarity_check 0.01 to 0.08 mm after the file's rounding) and every
opening has exactly ONE glass layer. Facets share their corners: solve_corners() moves the corners (by at most 10 cm at the B post, 6.6 cm
elsewhere, against the model's member centre lines: the original side windows are not plane, flat ones are what the pack's Mi-24 family uses)
so that each facet is planar and the facets of a window still meet edge to edge. Slim 4 cm round frame members run along the outlines of
the windows; where two facets of one window meet (a crease) there is no member, the glass meets the glass. The hump between the crews is
opaque skin (hump()). The pilot's outside rear-view mirror stands on the left of the windscreen hoop.
Photographs (work/cockpit-refs mi24v and mi_24d): tandem flat armoured windscreens raked back, the long side glass rising to the rear, the pilot
canopy higher and behind. UNVERIFIED: the facet layout of the side windows (a modelling choice), the pane sizes against drawings.

CANOPY (eye-relative sections of the glass, the half from the sill up over the top) is what the checks in work/elite_expansion_2/checks read;
it is cut from the panes below. OB_BL / OB_TL (the operator windscreen's bottom corner and top centre) are read by mi_24p.py.

Run first (on the airframe's files); the cockpit script mi_24p.py follows (mi24p/run_mi24p_cockpit.ps1).
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

# faces of the hull piece that the new cockpits replace (the untouched hull has HULL_FACES faces), from mi24p/gen_cut.py minus the frame members
CUT_RANGES = [
    (459, 459), (465, 465), (471, 471), (477, 477), (484, 485), (508, 510), (512, 512), (555, 558), (560, 560), (563, 563), (569, 569), (633, 642),
    (644, 648), (650, 654), (656, 656), (659, 659), (680, 680), (686, 686), (692, 692), (708, 708), (714, 714), (720, 720), (726, 726), (782, 784),
    (786, 786), (857, 857), (863, 863), (881, 885), (992, 992), (998, 998), (1004, 1004), (1015, 1016), (1018, 1018), (1021, 1047), (1376, 1434),
    (1453, 1455), (1457, 1497), (1499, 1500), (1502, 1502), (2410, 2410), (2416, 2416), (2422, 2422), (2428, 2428), (2431, 2431), (2453, 2453), (2455,
    2455), (2460, 2463), (2507, 2511), (2515, 2515), (2521, 2521), (2585, 2595), (2597, 2601), (2603, 2607), (2611, 2611), (2640, 2643), (2709, 2713),
    (2820, 2820), (2826, 2826), (2837, 2837), (2849, 2875), (3163, 3222), (3241, 3243), (3245, 3264), (3711, 3908), (3912, 3912), (3918, 3918), (3924,
    3924), (3945, 3946), (3948, 4063), (4072, 4072), (4078, 4078), (4082, 4084), (4086, 4108), (4110, 4279), (4282, 4282), (4288, 4288), (4294, 4294),
    (4298, 4299), (4303, 4305), (4307, 4311), (4313, 4314), (4317, 4317), (4323, 4323), (4327, 4362), (4387, 4478), (4549, 4549), (4555, 4560), (4777,
    4872), (5309, 5326), (5369, 5369), (5375, 5375), (5381, 5381), (5397, 5397), (5403, 5403), (5409, 5409), (5415, 5415), (5469, 5469), (5475, 5475),
    (5523, 5642), (5644, 5690), (5692, 5696), (5698, 5702), (5704, 5713), (5715, 5718), (5720, 5784), (5797, 5797), (5799, 5803), (5805, 5805), (5807,
    5811), (5813, 5814), (5821, 5821), (5823, 5827), (5829, 5829), (5831, 5835), (5837, 5922), (8217, 8262)]
HULL_FACES = 8521

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def unit(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def lerp(a, b, t):
    return np.array(a, float) * (1 - t) + np.array(b, float) * t


# ---------------------------------------------------------------- the panes
# Corners: vehicle blocks (x to the left, y up, z forward), the centres of the model's own frame members (Mi-24P.mtb element numbers in the
# comments; the x > 0 side is the mirror image where the model has members on one side only). x < 0 is the right of the helicopter. A facet's corners
# are moved by solve_corners() so that every facet is exactly planar.
def mx(p):
    return np.array([-p[0], p[1], p[2]], float)


def sym(half):
    """a loop that is symmetric about x = 0, from the half at x > 0 listed bottom to top (the last point on the axis)"""
    h = [np.array(p, float) for p in half]
    return h[:-1] + [h[-1]] + [mx(p) for p in h[:-1][::-1]]


OP_WS_HALF = [(0.330, 1.660, 7.770),                          # (ring foot: the hoop's bottom bar 282 / 692 sits just under it)
              (0.367, 1.946, 7.700), (0.334, 2.142, 7.646),  # 297, 298
              (0.264, 2.286, 7.591), (0.158, 2.356, 7.570),  # 299, 300
              (0.000, 2.378, 7.570)]                         # 301 / 711
PIL_WS_HALF = [(0.114, 2.821, 6.309),                         # 989 (the bar across the foot of the ring)
               (0.285, 2.772, 6.292), (0.345, 2.924, 6.200),  # 288, 290
               (0.367, 3.120, 6.083), (0.334, 3.256, 5.993),  # 291, 292
               (0.239, 3.354, 5.925), (0.122, 3.424, 5.878),  # 293, 294
               (0.000, 3.452, 5.857)]                         # 295 / 705

# x < 0 side windows (the other side is the mirror image)
OP_SIDE = [(-0.330, 1.660, 7.770),                           # ring foot
           (-0.367, 1.946, 7.700), (-0.334, 2.142, 7.646),   # 707, 708 (the hoop's outer leg)
           (-0.264, 2.286, 7.591), (-0.217, 2.397, 7.510),   # 709, 712
           (-0.258, 2.460, 7.407), (-0.272, 2.598, 7.064),   # 844, 845 (roof rail)
           (-0.272, 2.693, 6.672), (-0.236, 2.691, 6.488),   # 846, 644 (the arch over the hump)
           (-0.308, 2.568, 6.443), (-0.582, 2.234, 6.591),   # 642, 1794 (sill rail)
           (-0.581, 2.086, 6.863), (-0.571, 1.921, 7.198),   # 1795, 1797
           (-0.448, 1.719, 7.604)]                           # 1799
OP_ROOF = [(-0.217, 2.397, 7.510), (-0.258, 2.460, 7.407),   # 712, 844
           (-0.272, 2.598, 7.064), (-0.272, 2.693, 6.672),   # 845, 846
           (-0.163, 2.745, 6.445), (0.163, 2.745, 6.445),    # 645, m645 (the arch over the hump)
           (0.272, 2.693, 6.672), (0.272, 2.598, 7.064),
           (0.258, 2.460, 7.407), (0.217, 2.397, 7.510),
           (0.158, 2.356, 7.570), (0.000, 2.378, 7.570),     # 300, 301 (the hoop's top bow: the roof starts on the windscreen's top edge)
           (-0.158, 2.356, 7.570)]
OP_CORNER = [(-0.264, 2.286, 7.591), (-0.158, 2.356, 7.570), (-0.217, 2.397, 7.510)]     # 709, 710, 712: closes the hoop's top corner
PIL_SIDE_FRONT = [(-0.440, 2.571, 6.200), (-0.285, 2.772, 6.292),   # 696, 698
                  (-0.345, 2.924, 6.200),                           # 700
                  (-0.367, 3.120, 6.083), (-0.334, 3.256, 5.993),   # 701, 702
                  (-0.239, 3.354, 5.925), (-0.193, 3.446, 5.788),   # 703, 988
                  (-0.283, 3.484, 5.613), (-0.467, 3.343, 5.613),   # 985 (the post's head, on the roof rail), 981
                  (-0.538, 3.153, 5.613), (-0.582, 2.903, 5.613),   # 980, 979
                  (-0.587, 2.740, 5.602), (-0.566, 2.673, 5.595),   # 978, 977
                  (-0.522, 2.549, 5.906)]                           # 640 (beltline rail)
PIL_SIDE_REAR = [(-0.566, 2.673, 5.595), (-0.587, 2.740, 5.602),    # 977, 978
                 (-0.582, 2.903, 5.613), (-0.538, 3.153, 5.613),    # 979, 980
                 (-0.467, 3.343, 5.613), (-0.283, 3.484, 5.613),    # 981, 985
                 (-0.279, 3.486, 5.395), (-0.272, 3.452, 5.187),    # 986, 987 (roof rail)
                 (-0.270, 3.450, 5.140),                            # (tucked into the engine fairing's raked front)
                 (-0.530, 2.994, 4.680),                            # (the beltline's end against the fairing)
                 (-0.530, 2.935, 4.865), (-0.546, 2.723, 5.384)]    # 638, 639 (beltline rail)
PIL_ROOF = [(0.000, 3.452, 5.857), (-0.122, 3.424, 5.878),          # 705, 704 (the hoop's top bow: the roof starts on the windscreen's top edge)
            (-0.193, 3.446, 5.788), (-0.283, 3.484, 5.613),         # 988, 985
            (-0.279, 3.486, 5.395), (-0.272, 3.452, 5.187),         # 986, 987
            (-0.270, 3.450, 5.140),                                 # (the fairing)
            (0.270, 3.450, 5.140), (0.272, 3.452, 5.187),
            (0.279, 3.486, 5.395), (0.283, 3.484, 5.613),
            (0.193, 3.446, 5.788), (0.122, 3.424, 5.878)]
PIL_CORNER = [(-0.239, 3.354, 5.925), (-0.122, 3.424, 5.878), (-0.193, 3.446, 5.788)]    # 703, 704, 988: closes the hoop's top corner

# Each side window of a bubble is cut into flat facets along chords between its corners (indices into the corner list; no frame member runs
# along a chord): a window whose corners do not lie in one plane within 3 cm is split so that every facet is one plane.
FACETS = {'operator side right': [[7, 8, 9, 10], [10, 11, 12, 2, 3, 4, 5, 6, 7], [12, 13, 0, 1, 2]],
          'pilot side front right': [[4, 5, 6, 7, 8], [12, 13, 0, 1], [1, 2, 3, 4, 8, 9, 10, 11, 12]],
          'pilot side rear right': [[3, 4, 5, 6, 7, 8, 9], [9, 10, 11, 0, 1, 2, 3]]}
BASE_PANES = [('operator windscreen', sym(OP_WS_HALF)),
              ('operator side right', [np.array(p, float) for p in OP_SIDE]),
              ('operator roof', [np.array(p, float) for p in OP_ROOF]),
              ('operator corner right', [np.array(p, float) for p in OP_CORNER]),
              ('pilot windscreen', sym(PIL_WS_HALF)),
              ('pilot side front right', [np.array(p, float) for p in PIL_SIDE_FRONT]),
              ('pilot side rear right', [np.array(p, float) for p in PIL_SIDE_REAR]),
              ('pilot roof', [np.array(p, float) for p in PIL_ROOF]),
              ('pilot corner right', [np.array(p, float) for p in PIL_CORNER])]


def fit_plane(P):
    P = np.array(P, float)
    c = P.mean(0)
    _, _, vt = np.linalg.svd(P - c)
    n = vt[2]
    return c, n


def ear_clip(P2):
    """triangulates a simple polygon given as 2-D points (any winding): index triples"""
    n = len(P2)
    idx = list(range(n))
    area = 0.0
    for i in range(n):
        a, b = P2[i], P2[(i + 1) % n]
        area += a[0] * b[1] - b[0] * a[1]
    sgn = 1.0 if area > 0 else -1.0

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    def inside(p, a, b, c):
        return cross(a, b, p) * sgn >= -1e-12 and cross(b, c, p) * sgn >= -1e-12 and cross(c, a, p) * sgn >= -1e-12
    out = []
    guard = 0
    while len(idx) > 3 and guard < 10000:
        guard += 1
        for k in range(len(idx)):
            i0, i1, i2 = idx[k - 1], idx[k], idx[(k + 1) % len(idx)]
            a, b, c = P2[i0], P2[i1], P2[i2]
            if cross(a, b, c) * sgn <= 1e-12:
                continue
            if any(inside(P2[j], a, b, c) for j in idx if j not in (i0, i1, i2)):
                continue
            out.append((i0, i1, i2))
            idx.pop(k)
            break
        else:
            break
    if len(idx) == 3:
        out.append(tuple(idx))
    return out


Z_FAIRING_FILL = (4.72, 5.09)
PANES = {}             # name -> {'poly': Nx3 (on the plane), 'tris': [..], 'dev': max corner shift, 'normal': n}
EDGES = []             # unique frame edges (pairs of corners) of the facets
CORNERS = {}           # key -> corner after the solve


def _key(p):
    return tuple(float(v) for v in np.round(p, 3))


def _facets():
    """[(name, [corner keys])] of every flat facet, both sides"""
    out = []
    for name, pts in BASE_PANES:
        pts = [np.array(p, float) for p in pts]
        sides = [(name, pts)]
        if name.endswith(' right'):
            sides.append((name[:-6] + ' left', [mx(p) for p in pts][::-1]))
        for nm, pp in sides:
            if name in FACETS:
                idx = FACETS[name]
                if nm != name:
                    n = len(pts)
                    idx = [[n - 1 - k for k in f][::-1] for f in idx]
                    nm_list = [f'{nm} {i + 1}' for i in range(len(idx))]
                else:
                    nm_list = [f'{nm} {i + 1}' for i in range(len(idx))]
                for f, n_ in zip(idx, nm_list):
                    out.append((n_, [pp[k] for k in f]))
            else:
                out.append((nm, pp))
    return out


def solve_corners(facets, iters=300):
    """moves the shared corners so that every facet is exactly planar: planes are fitted to the facets, each corner is moved (minimum distance) onto the
    planes of all facets that share it, repeated until nothing moves"""
    V, orig, users = {}, {}, {}
    ids = []
    for fi, (nm, pts) in enumerate(facets):
        keys = []
        for p in pts:
            k = _key(p)
            V.setdefault(k, np.array(p, float))
            orig[k] = np.array(p, float)
            users.setdefault(k, []).append(fi)
            keys.append(k)
        ids.append(keys)
    for _ in range(iters):
        planes = [fit_plane([V[k] for k in ks]) for ks in ids]
        move = 0.0
        for k, fl in users.items():
            N = np.array([planes[f][1] for f in fl])
            c = np.array([planes[f][0] for f in fl])
            r = -np.einsum('ij,ij->i', N, V[k] - c)
            dv = N.T @ np.linalg.lstsq(N @ N.T, r, rcond=1e-3)[0]
            V[k] = V[k] + 0.6 * dv
            move = max(move, float(np.abs(dv).max()))
        if move < 1e-7:
            break
    return V, orig, ids


def build_glass():
    facets = _facets()
    V, orig, ids = solve_corners(facets)
    CORNERS.clear()
    CORNERS.update(V)
    PANES.clear()
    EDGES.clear()
    glass, uses, order = [], {}, []
    for (nm, _), ks in zip(facets, ids):
        Q = np.array([V[k] for k in ks])
        c, n = fit_plane(Q)
        u = unit(np.cross(n, Y if abs(n @ Y) < 0.9 else X))
        v = np.cross(n, u)
        P2 = [((q - c) @ u, (q - c) @ v) for q in Q]
        tris = [[Q[i], Q[j], Q[k]] for i, j, k in ear_clip(P2)]
        shift = max(float(np.linalg.norm(V[k] - orig[k])) for k in ks)
        PANES[nm] = {'poly': Q, 'tris': tris, 'dev': shift, 'normal': n, 'flat': float(np.abs((Q - c) @ n).max())}
        glass += tris
        base = nm.rsplit(' ', 1)[0] if nm.rsplit(' ', 1)[-1].isdigit() else nm
        for i in range(len(ks)):
            a, b = ks[i], ks[(i + 1) % len(ks)]
            e = frozenset((a, b))
            if e not in uses:
                order.append((a, b))
            uses.setdefault(e, []).append(base)
    # frame members run along the window outlines; the creases between two facets of one window carry no member (glass meets glass there)
    EDGES.extend(ed for ed in order if not (len(uses[frozenset(ed)]) == 2 and uses[frozenset(ed)][0] == uses[frozenset(ed)][1]))
    return glass


# ---------------------------------------------------------------- CANOPY: the glass's cross-sections (what the checks read)
def _slice(tri, z):
    """the segment where the triangle meets the plane at z: ((x, y), (x, y)) or None"""
    d = [p[2] - z for p in tri]
    pts = []
    for i in range(3):
        a, b = tri[i], tri[(i + 1) % 3]
        if (d[i] > 0) != (d[(i + 1) % 3] > 0):
            f = d[i] / (d[i] - d[(i + 1) % 3])
            q = a + (b - a) * f
            pts.append((float(q[0]), float(q[1])))
    return (pts[0], pts[1]) if len(pts) == 2 else None


# the untouched skin's rim (the sill) at station z: half width and height (measured off the model, vehicle blocks)
SILL_X = [(4.60, 0.60), (5.0, 0.61), (5.4, 0.62), (5.8, 0.62), (6.34, 0.62), (6.6, 0.60), (6.8, 0.58), (7.05, 0.54), (7.20, 0.52), (7.35, 0.48), (7.63, 0.40), (7.9, 0.30)]
SILL_Y = [(4.60, 2.53), (4.72, 2.53), (5.05, 2.48), (5.38, 2.43), (5.62, 2.38), (6.00, 2.33), (6.34, 2.30), (6.58, 2.20), (6.80, 2.10), (7.05, 2.00), (7.20, 1.96),
          (7.35, 1.90), (7.63, 1.70)]


def outline_at(z):
    if Z_FAIRING_FILL[0] <= z < Z_FAIRING_FILL[1]:                # behind the roof pane: the engine fairing's raked front closes the top; keep the roof's section
        z = Z_FAIRING_FILL[1] + 0.001
    """the glass's section at station z as [(|x|, y), ...] from the sill up to the top on the axis (None if the glass does not reach z)"""
    segs = []
    for pane in PANES.values():
        for t in pane['tris']:
            s = _slice(t, z)
            if s:
                segs.append(s)
    if 6.28 <= z <= 6.58:                                          # the hump between the crews (the model's own, opaque): its section
        return [(0.62, 2.30), (0.62, 2.55), (0.42, 2.76), (0.30, 2.84), (0.0, 2.84)]
    if not segs:
        return None
    ys = [p[1] for s in segs for p in s]
    y_lo, y_hi = min(ys), max(ys)
    # a lone windscreen station: the glass is a single line at one height; stand the section on the deck / the hump
    if y_hi - y_lo < 0.15:
        w = max(abs(p[0]) for s in segs for p in s)
        if z > 6.9:
            return [(w, 1.66), (w, y_hi), (0.0, y_hi)]
        return [(0.62, 2.30), (0.62, 2.55), (0.42, 2.76), (w, y_hi), (0.0, y_hi)]               # (the pilot's windscreen stands on the hump: wide at the sill)
    levels = np.linspace(y_lo, y_hi, 7)
    pts = []
    for y in levels:
        xs = []
        for (a, b) in segs:
            if (a[1] - y) * (b[1] - y) <= 0 and abs(a[1] - b[1]) > 1e-9:
                f = (y - a[1]) / (b[1] - a[1])
                xs.append(abs(a[0] + (b[0] - a[0]) * f))
            elif abs(a[1] - b[1]) <= 1e-9 and abs(a[1] - y) < 1e-6:
                xs += [abs(a[0]), abs(b[0])]
        if xs:
            pts.append((max(xs), float(y)))
    if not pts:
        return None
    ys_ = float(np.interp(z, [q[0] for q in SILL_Y], [q[1] for q in SILL_Y]))
    if pts[0][1] > ys_ + 0.02:                                  # the glass starts above the sill (a beltline rail): the section starts at the rim
        xs_ = float(np.interp(z, [q[0] for q in SILL_X], [q[1] for q in SILL_X]))
        pts[0:0] = [(xs_, ys_), (xs_, pts[0][1])]                # (the skin's wall up to the glass)
    pts.append((0.0, y_hi))
    # strictly from the sill up, x not growing (a pane's overhang folded away)
    out = [pts[0]]
    for p in pts[1:]:
        out.append((min(p[0], out[-1][0]), p[1]))
    return out


def sections_rel():
    out = []
    zs = sorted(set([4.725] + [round(4.80 + 0.02 * k, 3) for k in range(0, 149)] + [7.765]))
    for z in zs:
        sec = outline_at(z)
        if sec is None:
            continue
        out.append((z - EYE_P[2], [(max(0.0, x), y - EYE_P[1]) for x, y in sec]))
    return out


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


def box(m, centre, axes, half, uv):
    c = np.array(centre, float)
    A_ = [unit(a) for a in axes]
    for k in range(3):
        for s in (-1, 1):
            i, j = [q for q in range(3) if q != k]
            fc = c + A_[k] * half[k] * s
            m.face([fc - A_[i] * half[i] - A_[j] * half[j], fc + A_[i] * half[i] - A_[j] * half[j],
                    fc + A_[i] * half[i] + A_[j] * half[j], fc - A_[i] * half[i] + A_[j] * half[j]], uv, A_[k] * s)


def rear_mirror(m, uv_frame, uv_glass):
    """the pilot's outside rear-view mirror: a short stalk from the left side of the pilot's windscreen hoop out and forward into a framed plate
    facing the pilot (rearward); the glass plate's back face is the frame's front face (no two faces 1-2 mm apart)"""
    p = np.array([0.367, 3.120, 6.083])                 # the hoop's leg (member 291)
    c = p + np.array([0.115, 0.045, -0.040])
    tube(m, p, c + np.array([0.0, 0.0, 0.004]), 0.009, uv_frame, seg=6)
    box(m, c + np.array([0.0, 0.0, -0.006]), (X, Y, Z), (0.062, 0.044, 0.006), uv_frame)
    box(m, c + np.array([0.0, 0.0, -0.0145]), (X, Y, Z), (0.056, 0.038, 0.0025), uv_glass)


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
    """one entry per pane: its glass triangles are selected one by one (a box round each centroid) and all are fitted to one plane together"""
    out = []
    for name, pane in PANES.items():
        boxes = []
        for g in pane['tris']:
            c = (g[0] + g[1] + g[2]) / 3
            boxes.append([[round(float(v) - 0.0004, 4) for v in c], [round(float(v) + 0.0004, 4) for v in c]])
        out.append({'name': name, 'mirror': False, 'boxes': boxes})
    return {'why': ('Mi-24P canopies: the model\'s own frames (two raked oval windscreen hoops, sill and beltline rails, roof rails, the pilot\'s B posts) '
                    'glazed with flat panes: each windscreen, each side window and each roof pane is ONE planar polygon with its corners on frame '
                    'members (canopy_mi_24p.py). Listed: every pane.'),
            'panes': out}


R_TUBE = 0.020


def frame_members():
    """straight members along the unique edges of the facets (collinear neighbours joined): [(a, b)] in blocks. The edges that run into the engine
    fairing's front (the glass's rear edges) are hidden in it and get no member."""
    nb = {}
    for a, b in EDGES:
        va, vb = CORNERS[a], CORNERS[b]
        if max(va[2], vb[2]) <= 5.17 and max(va[1], vb[1]) > 3.3:
            continue
        nb.setdefault(a, []).append(b)
        nb.setdefault(b, []).append(a)
    used = set()
    out = []

    def straight(p, q, r):
        d1, d2 = unit(CORNERS[q] - CORNERS[p]), unit(CORNERS[r] - CORNERS[q])
        return float(d1 @ d2) > math.cos(math.radians(4.0))
    for a, b in EDGES:
        if frozenset((a, b)) in used or b not in nb.get(a, []):
            continue
        chain = [a, b]
        used.add(frozenset((a, b)))
        for end in (1, 0):
            while True:
                cur, prev = (chain[-1], chain[-2]) if end else (chain[0], chain[1])
                nxt = [n for n in nb[cur] if n != prev and frozenset((cur, n)) not in used]
                if len(nb[cur]) == 2 and len(nxt) == 1 and straight(prev, cur, nxt[0]):
                    used.add(frozenset((cur, nxt[0])))
                    if end:
                        chain.append(nxt[0])
                    else:
                        chain.insert(0, nxt[0])
                else:
                    break
        out.append((CORNERS[chain[0]], CORNERS[chain[-1]]))
    return out


# The hump between the crews is opaque skin (the model's own old partition and its arch plates are cut with the rest of the old interior, because
# the pilot's panel stands in front of them): a top strip from the pilot windscreen's lower edge to the operator roof's rear edge, and a side wall
# on each side from the pilot's side window to the operator's. Corners are the glass's own corners (coordinates as in the tables above) and the
# rim point of the skin (-0.61, 2.28, 6.40).
HUMP_TOP = [(-0.285, 2.772, 6.292), (-0.114, 2.821, 6.309), (0.114, 2.821, 6.309), (0.285, 2.772, 6.292),
            (0.236, 2.691, 6.488), (0.163, 2.745, 6.445), (-0.163, 2.745, 6.445), (-0.236, 2.691, 6.488)]
HUMP_WALL = [(-0.285, 2.772, 6.292), (-0.440, 2.571, 6.200), (-0.520, 2.520, 6.120), (-0.620, 2.300, 6.120), (-0.610, 2.280, 6.400),
             (-0.582, 2.234, 6.591), (-0.308, 2.568, 6.443), (-0.236, 2.691, 6.488)]


def fill(m, pts, uv, normal):
    """an opaque polygon (not necessarily flat): ear-clipped in its mean plane, every triangle facing [normal]"""
    P = [np.array(q, float) for q in pts]
    n = unit(normal)
    u = unit(np.cross(n, Y if abs(n @ Y) < 0.9 else X))
    v = np.cross(n, u)
    c = np.mean(P, 0)
    for i, j, k in ear_clip([((q - c) @ u, (q - c) @ v) for q in P]):
        m.face([P[i], P[j], P[k]], uv, n)


def hump(m, uv):
    cor = lambda q: CORNERS.get(_key(q), np.array(q, float))
    fill(m, [cor(q) for q in HUMP_TOP], uv, Y)
    for sgn in (1, -1):
        wall = [cor(q) for q in HUMP_WALL]
        if sgn == 1:
            wall = [mx(w) for w in wall]
        fill(m, wall, uv, np.array([sgn, 0.0, 0.0]))


def build_frames(uv_frame, uv_mirror, uv_skin):
    m = Mesh()
    hump(m, uv_skin)
    for a, b in frame_members():
        tube(m, a, b, R_TUBE, uv_frame)
    rear_mirror(m, uv_frame, uv_mirror)
    return m.poly_mesh()


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    bones[:] = [b for b in bones if b['name'] != CANOPY_BONE]
    cut = cut_old(bones)
    sw = L.Swatches(L.texture_path(VID))
    uv_frame = sw.uv((128, 128, 96))
    uv_mirror = sw.uv((120, 138, 146))
    glass = build_glass()
    pm = build_frames(uv_frame, uv_mirror, sw.uv((128, 128, 96)))
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    bones.insert(bones.index(hull) + 1, {'name': CANOPY_BONE, 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm})
    both = [list(map(float, p)) for t in glass for p in t] + [list(map(float, t[k])) for t in glass for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'{VID} canopy: {cut} old canopy and interior faces cut, {len(pm["polys"])} faces of frame members and mirror; '
          f'{len(PANES)} flat panes, {len(glass)} glass triangles; swatch error {sw.worst:.0f}')
    for name, pane in PANES.items():
        print(f'   {name:24s} {len(pane["poly"]):2d} corners, flat to {pane["flat"] * 1000:.3f} mm, corners moved from the model frame by up to {pane["dev"] * 1000:.0f} mm')
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


CANOPY = None


def _init():
    global CANOPY, OB_BL, OB_TL
    build_glass()
    ws = PANES['operator windscreen']['poly']          # the cockpit script's reference: the operator windscreen's bottom and top (y, z used)
    OB_BL, OB_TL = ws[0], ws[5]
    CANOPY = sections_rel()


_init()

if __name__ == '__main__':
    main(sys.argv[1:])
