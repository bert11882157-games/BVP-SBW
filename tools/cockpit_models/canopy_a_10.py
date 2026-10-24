#!/usr/bin/env python3
"""A-10C canopy, fitted to the A-10C.mtb airframe: the glass for the source's own windscreen frame.

The A-10's canopy (side views and photos of the type): a big frameless blown bubble running aft from the windscreen arch over
the pilot to the dorsal fairing behind the seat, hinged at the back and held along the sills by its two rails, and ahead of it
the fixed windscreen: the arch (one bow over the HUD), the flat bulletproof centre pane between its two bars and a quarter
pane on each side, all sloping down to the nose deck.

The airframe carries exactly that frame and nothing else (open at the back: no canopy glass, no rear bow), in the hull
piece wreck_fuselage_0__hull: the two sill rails (a leaning band of boxes whose top edge rises toward the spine), the arch
(a bow of four stepped members, 0.05 to 0.50 out from the centre line, leaning forward) and the two bars of the centre
windscreen pane (x 0.15 to 0.20, from the arch's crown down to the nose deck), which this script keeps as THE frame, never
adds a second one, and fits the glass to:

  * the bubble: from the dorsal fairing (z 3.25, where the glass tapers to nothing on the deck) forward to the rear face of
    the arch. Every row starts on the inner top edge of the sill rail and the patch stops at the arch's rear face (the arch
    leans, so the cut leans with it), never running under it;
  * the windscreen, FLAT glass as on the real jet (owner rule 2026-10-22: a pane that is flat on the aircraft is truly flat, the
    frame is built around it; planarity_check.py reads flat_panes/a_10.json): the centre pane is ONE plane between the posts
    (|x| < 0.14), each quarter pane ONE plane outside them (0.21 out to where the plane meets the nose's skin), all from the
    arch's front face down to the deck at the bars' lower cross-bar (z 6.24). The posts are 7 cm strips lying in the centre
    plane; the quarter plane folds down from the post's outer edge. The arch is one bow with the posts' tops: its rear edge is
    the bubble's front section, its front edge the panes' top edge, so the glass stops at the bow;
  * the section at every bubble station is a super-ellipse from the rail up to the crown (the bubble stays curved); the crown
    over the bubble peaks at 3.665 over the headrest and falls to the arch's top (3.49), and over the windscreen the sections
    are the flat panes' (3.52 at the arch, 2.82 at the deck on the centre line).

CANOPY (eye-relative sections, the right half from the sill up over the top) is what the checks in
work/elite_expansion_2/checks read for the canopy outline. Frames: vehicle-local blocks, model pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script a_10.py then appends the HUD combiner to CanopyGlass.
Usage: python tools/cockpit_models/canopy_a_10.py [--dry-run]
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/a_10.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/a_10.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/a_10.json')
HULL_BONE = 'wreck_fuselage_0__hull'

# ---- the frame's measurements (absolute blocks)
# the sill rails' inner top edge: z, x of the inner edge, y of the top (the rails rise toward the dorsal fairing)
# (y = the hull's own rail top, ray cast down on the hull pieces every 5 cm, less 4 mm: the glass foot is buried in the rail and no
# slit shows under it; the hand-set table of the first pass stood up to 2 cm over the rail)
RAIL = [(3.25, 0.340, 3.2408), (3.30, 0.348, 3.2283), (3.35, 0.356, 3.2158), (3.40, 0.364, 3.2033), (3.45, 0.372, 3.1917),
        (3.50, 0.380, 3.1840), (3.55, 0.384, 3.1763), (3.60, 0.388, 3.1686), (3.65, 0.392, 3.1609), (3.70, 0.396, 3.1533),
        (3.75, 0.400, 3.1456), (3.80, 0.404, 3.1379), (3.85, 0.408, 3.1302), (3.90, 0.412, 3.1225), (3.95, 0.416, 3.1148),
        (4.00, 0.420, 3.1071), (4.05, 0.422, 3.0994), (4.10, 0.425, 3.0910), (4.15, 0.427, 3.0799), (4.20, 0.430, 3.0688),
        (4.25, 0.432, 3.0577), (4.30, 0.435, 3.0466), (4.35, 0.437, 3.0355), (4.40, 0.440, 3.0244), (4.45, 0.442, 3.0132),
        (4.50, 0.445, 3.0021), (4.55, 0.447, 2.9922), (4.60, 0.450, 2.9866), (4.65, 0.452, 2.9811), (4.70, 0.453, 2.9755),
        (4.75, 0.455, 2.9700), (4.80, 0.457, 2.9644), (4.85, 0.458, 2.9589), (4.90, 0.460, 2.9533), (4.95, 0.462, 2.9478),
        (5.00, 0.465, 2.9414), (5.05, 0.467, 2.9323), (5.10, 0.470, 2.9232), (5.15, 0.474, 2.9141), (5.22, 0.480, 2.8971)]
# the hull behind the dome's rear end (z 3.25): the deck's trough between the rails (|x| < 0.305) and the rails' ridge beyond
RAIL = [(z, x + (0.012 if z < 4.95 else 0.0), y) for z, x, y in RAIL]   # (the foot stands mid-way on the rail top, not on its inner edge)
REAR_TROUGH_Y, REAR_RIDGE_X, REAR_RIDGE_Y = 3.171, 0.305, 3.245
# the crown line over the centre (z, y)
CROWN = [(3.25, 3.245), (3.30, 3.290), (3.45, 3.415), (3.60, 3.520), (3.80, 3.600), (4.00, 3.640), (4.30, 3.665),
         (4.60, 3.650), (4.85, 3.600), (5.05, 3.545), (5.14, 3.515), (5.22, 3.490)]
NEXP = 2.2                       # the sections' super-ellipse exponent
ZM0, ZM1 = 4.75, 5.03            # the bubble morphs into the arch's profile from ZM0 (w 0) to ZM1 (w 1)

# the windscreen: from the arch's front face (z 5.29) to the deck (z 6.24): the foot on the nose deck (x, y) and the crown
WS = [(5.29, 0.460, 2.930), (5.55, 0.400, 2.820), (5.90, 0.330, 2.820), (6.24, 0.270, 2.780)]
WS_END = 6.24                    # the panes' base frame starts here; the posts run on into it
GLASS_END = 6.25                 # the glass runs 1 cm into the base frame
FOOT_START = 6.20                # (the old model has a cross-bar face at z 6.238: the bar starts well behind it)
FOOT_END = 6.31                  # the base frame's front edge
FOOT_OUT, FOOT_IN = 0.012, 0.045 # 12 mm over the pane's plane, 45 mm under it (the nose deck is 27 mm under the plane)
BAR_IN, BAR_OUT = 0.140, 0.210   # the centre pane's half width and where the quarter panes start (the posts stand between: 7 cm)
# (the airframe's windscreen bars are cut out as POST_BOX and rebuilt slim on the same line, 7 cm wide and thin in depth, from the arch
# down to the nose deck, one member with the arch bow; the glass stops at their edges)
POST_BOX = ((0.09, 5.28, 2.74), (0.22, 6.25, 3.60))   # lo, hi (|x|, z, y): the airframe's two bars, a 5 cm and a 10 cm member
POST_OUT, POST_IN_T = 0.012, 0.018


def ws_crown(z):
    return 3.435 - 0.706 * (z - 5.39)


# ---- the flat windscreen panes (the real A-10's armoured centre pane and its two quarter panes are flat glass)
# centre pane: ONE plane, sloping down forward along the old crown line (12 mm under it, so that the pane sits inside the arch's
# thickness at its top edge); the posts' faces lie in this plane (they are 7 cm strips of it).
# quarter pane: ONE plane per side, folded down along the post's outer edge (x 0.21) at QK metres of drop per metre outboard
# (the old quarter pane's average drop to its foot was 2.1); the glass runs down to where this plane meets the nose's own
# skin (found by ray casts on the airframe's hull; the table is that line, a hair outboard so the glass edge is buried in the skin
# and leaves no slit); its top edge is the arch's front face, its inner edge the post.
QK = 2.35
QOUT = [(5.10, 0.480), (5.26, 0.480), (5.30, 0.466), (5.35, 0.454), (5.40, 0.436), (5.45, 0.402), (5.50, 0.368), (5.55, 0.346),
        (5.60, 0.346), (5.65, 0.348), (5.70, 0.350), (5.75, 0.352), (5.80, 0.342), (5.85, 0.328), (5.90, 0.314), (5.95, 0.302),
        (6.00, 0.290), (6.05, 0.280), (6.10, 0.268), (6.15, 0.258), (6.20, 0.248), (6.25, 0.244), (6.32, 0.244)]
# (from z 5.30 on: where the pane's plane has sunk 3 mm under the hull's own top, ray cast on the hull; the first pass's hand-set
# table stood 1 to 3 cm off the nose between z 5.45 and 5.70)


def pc(z):
    """the centre plane's height at station z (x does not matter: the plane has no side slope)"""
    return ws_crown(z) - 0.012


def x_out(z):
    """the quarter pane's outer edge (|x|) at station z"""
    return float(np.interp(z, [q[0] for q in QOUT], [q[1] for q in QOUT]))


def flat_h(x, z):
    """the flat panes' surface height at (x, z): the centre plane up to the post's outer edge, the quarter plane outside it"""
    ax = abs(x)
    return pc(z) if ax <= BAR_OUT else pc(z) - QK * (ax - BAR_OUT)


def pane_top(ax):
    """(|x|, y, z) of the pane's top edge at |x| = ax: where the flat surface meets the arch's front face (the face leans with
    the height, so the station is found by iterating); outboard of the quarter pane's edge it stops at the edge"""
    z = 5.25
    for _ in range(8):
        a = min(ax, x_out(z))
        z = z_front_arch(flat_h(a, z))
    a = min(ax, x_out(z))
    return a, flat_h(a, z), z


def flat_section(z):
    """the windscreen's right half-section at station z (what the checks read): the quarter pane's foot, up its plane to the
    post, across the post and the centre pane to the centre line"""
    xo = x_out(z)
    h = pc(z)
    pts = [(xo + (BAR_OUT - xo) * t, flat_h(xo + (BAR_OUT - xo) * t, z)) for t in np.linspace(0, 1, 8)]
    return pts + [(BAR_IN, h), (0.0, h)]


# the arch's faces (y -> z): the rear face the bubble stops at, the front face the windscreen starts at
ARCH_Y = [2.90, 3.15, 3.35, 3.50, 3.60]
# (the airframe's own arch is a 0.15 to 0.35 deep block; the real A-10 arch is a slim bow: this script cuts the block out and
# puts an 8 cm bow on the same line, centred where the block's centre was)
ARCH_REAR = [5.025, 5.075, 5.125, 5.175, 5.175]
ARCH_FRONT = [5.105, 5.155, 5.205, 5.255, 5.255]
ARCH_BOX = (4.93, 5.40, 2.89, 3.56, 0.55)     # z lo, z hi, y lo, y hi, |x| hi: the block's region in the hull piece (the bars' own ends at z 5.389 stay)
BAR_END = 5.389                                # where the windscreen bars' own members start
Z_DOME0 = 3.06                                # the dome's rear end (it is closed onto the deck there)
Z_BOW = (3.58, 3.64)                          # the rear canopy bow (the hinge frame) and the dorsal fairing aft of it
BOW_OUT, BOW_IN = 0.012, 0.030
ARCH_OUT, ARCH_IN = 0.030, 0.030
BARS_BONE = 'frame_bars__canopy'             # (the name ends in __canopy: the checks count it as the canopy's own)


def z_rear_arch(y):
    # (a smooth fit through the table: its kinks made saw-tooth steps along the bow)
    return float(np.polyval([-0.004136, 0.257031, 4.312097], y))


def z_front_arch(y):
    return float(np.polyval([-0.004136, 0.257031, 4.392097], y))


SILL_Y = 2.85                    # the outline the checks read starts here (the leaning rail band's inner face under the rail)

UV_FRAME = [0.043945, 0.632813]   # (the frame's own texel in the atlas: used if the block arch was cut by an earlier run)
_EYE = np.array([0.0, 3.20, 4.58])


def _eye():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        s = d['Seats'][0]
        return np.array(d['Attachments'][s['CameraPos']['EyeAttachment']]['Position'], float)
    except (OSError, KeyError):
        return _EYE


def base_crown(z):
    """(x of the glass's foot, y of the foot, y of the crown) at station z: on the rail, over the arch, or on the nose deck"""
    zs = [r[0] for r in RAIL]
    if z <= RAIL[-1][0]:
        zc = [c[0] for c in CROWN]
        return (float(np.interp(z, zs, [r[1] for r in RAIL])), float(np.interp(z, zs, [r[2] for r in RAIL])),
                float(np.interp(z, zc, [c[1] for c in CROWN])))
    if z >= WS[0][0]:
        zw = [w[0] for w in WS]
        return (float(np.interp(z, zw, [w[1] for w in WS])), float(np.interp(z, zw, [w[2] for w in WS])), ws_crown(z))
    t = (z - RAIL[-1][0]) / (WS[0][0] - RAIL[-1][0])
    a, b = base_crown(RAIL[-1][0]), base_crown(WS[0][0])
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def resample(pts, k):
    p = np.array(pts, float)
    d = np.r_[0, np.cumsum(np.linalg.norm(np.diff(p, axis=0), axis=1))]
    t = np.linspace(0, d[-1], k)
    return np.c_[np.interp(t, d, p[:, 0]), np.interp(t, d, p[:, 1])]


def section(z, n=22):
    """the right half-section at station z: absolute (x, y) points from the foot up to the crown (x = 0)"""
    if z >= WS[0][0]:
        return flat_section(z)               # (the windscreen's panes are flat, not super-ellipses)
    xb, yb, yt = base_crown(z)
    us = np.linspace(0, 1, n)
    pts = [(xb * (1 - u ** NEXP) ** (1 / NEXP), yb + (yt - yb) * u) for u in us]
    pts[-1] = (0.0, yt)
    if z > ZM0:
        # the bubble's last stretch morphs into the flat panes' top-edge profile, so the arch is a uniform slim bow
        w = min(1.0, (z - ZM0) / (ZM1 - ZM0))
        w = w * w * (3 - 2 * w)
        pts = [tuple(p) for p in (1 - w) * resample(pts, 40) + w * resample(end_profile(), 40)]
    return pts


_END = []


def end_profile():
    """the bubble's section at the arch: the panes' top edge (pane_top) from the rail's inner top edge up over the quarter panes,
    the post and the centre pane to the centre line"""
    if not _END:
        xs = np.linspace(0.0, 0.48, 49)
        _END.extend([(0.48, 2.92)] + [(float(x), pane_top(float(x))[1]) for x in xs[::-1]])
    return _END


STATIONS = [3.25, 3.30, 3.40, 3.55, 3.70, 3.85, 4.00, 4.15, 4.30, 4.45, 4.60, 4.75, 4.90, 5.05, 5.14, 5.22, 5.29,
            5.40, 5.55, 5.70, 5.90, 6.10, 6.24]


def sections_rel(eye):
    """the canopy outline the checks read: from the sill line straight up the foot's inner face, then the glass"""
    out = []
    for z in STATIONS:
        pts = section(z)
        if pts[0][1] > SILL_Y + 0.004:
            pts = [(pts[0][0], SILL_Y)] + pts
        out.append((z - eye[2], [(x, y - eye[1]) for x, y in pts]))
    return out


CANOPY = sections_rel(_eye())            # eye-relative (the checks read this)


def edge(zf, j, z, ns, lo, hi):
    """the station where row j of the section meets the frame face zf(y): the row's height depends on the station, so
    the face is found by iterating"""
    for _ in range(4):
        y = resample(section(z), ns + 1)[j][1]
        z = min(hi, max(lo, zf(y)))
    return z


NS = 20                          # segments round the bubble's half section (glass, arch bow and rear bow share the rows)


def bubble(nz=34, ns=NS):
    """the bubble, both sides: row j of the arc-length resampled section runs from the dorsal end (z 3.25) to the arch's
    rear face (found at the row's own height)"""
    z0, z_hi = Z_BOW[1], ARCH_REAR[-1]
    ends = [edge(z_rear_arch, j, z_hi, ns, z0, z_hi) for j in range(ns + 1)]
    out = []
    for side in (1, -1):
        grid = []
        for i in range(nz + 1):
            row = []
            for j in range(ns + 1):
                # denser toward the tapering rear end
                t = (i / nz) ** 1.3
                z = z0 + (ends[j] - z0) * t
                x, y = resample(section(z), ns + 1)[j]
                row.append(np.array([side * x, y, z]))
            grid.append(row)
        out += quads(grid)
    return out


def quads(grid):
    out = []
    for i in range(len(grid) - 1):
        for j in range(len(grid[0]) - 1):
            a, b, c, d = grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]
            for tri in ((a, b, c), (a, c, d)):
                if np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0])) > 1e-10:
                    out.append(list(tri))
    return out


def pane(x_lo, x_hi_of_z, z_end, nz=14, ns=6):
    """one FLAT windscreen pane, both sides: column j runs across the pane at constant fraction between x_lo and x_hi(z); its
    rows start at the arch's front face (found at the row's own height) and run to z_end. Every vertex is on flat_h's plane."""
    out = []
    for side in (1, -1):
        grid = []
        # the start station per column: the arch's front face at the height of the column's top-edge
        starts = []
        for j in range(ns + 1):
            f = j / ns
            z = 5.25
            for _ in range(8):
                x = x_lo + (x_hi_of_z(z) - x_lo) * f
                z = z_front_arch(flat_h(x, z))
            starts.append(z)
        for i in range(nz + 1):
            row = []
            for j in range(ns + 1):
                f = j / ns
                z = starts[j] + (z_end - starts[j]) * i / nz
                x = x_lo + (x_hi_of_z(z) - x_lo) * f
                row.append(np.array([side * x, flat_h(x, z), z]))
            grid.append(row)
        out += quads(grid)
    return out


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

    def bar(self, A, B, t_out, t_in, axis_y=2.85, caps=True, up=False):
        """a bar whose two long edges lie on the glass surface along A[i] and B[i]: a skin t_out over the surface, one t_in
        under it, and the two side walls between, and a cap across each end (a closed member: no hollow shows at a joint)"""
        for i in range(len(A) - 1):
            q = [A[i], A[i + 1], B[i + 1], B[i]]
            n = np.cross(B[i] - A[i], A[i + 1] - A[i])
            if np.linalg.norm(n) < 1e-12:
                continue
            n /= np.linalg.norm(n)
            c = np.mean(q, 0)
            if (n[1] < 0) if up else (n @ (c - np.array([0.0, axis_y, c[2]])) < 0):
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
            cs, ce = caps if isinstance(caps, tuple) else (caps, caps)
            if (cs and i == 0) or (ce and i == len(A) - 2):
                p0, p1 = (0, 3) if i == 0 and cs else (1, 2)
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


BOW_RAMP = 0.035                 # the rear bow's raised band eases up over this length (no step on the skin)
REAR_SKIN_Y = 0.006              # the closing wall's foot is sunk this far into the deck


def bow_off(z):
    """how far the dome's skin stands over the glass's surface at station z: 0 aft of the ramp, BOW_OUT on the band (z 3.58 to 3.64)"""
    z0 = Z_BOW[0]
    if z >= z0:
        return BOW_OUT
    s = min(1.0, max(0.0, (z - (z0 - BOW_RAMP)) / BOW_RAMP))
    return BOW_OUT * s * s * (3 - 2 * s)


def _ring(z, side, ns, off):
    """the section ring at station z pushed out along the section's outward normal by off"""
    pts = resample(section(z), ns + 1)
    t = np.gradient(pts, axis=0)
    nr = np.c_[t[:, 1], -t[:, 0]]
    nr /= np.maximum(np.linalg.norm(nr, axis=1), 1e-12)[:, None]
    nr[-1] = (0.0, 1.0)                  # (the crown's normal is straight up: both halves meet on the centre line)
    q = pts + nr * off
    return [np.array([side * x, y, z]) for x, y in q]


def frame_bars(uv):
    b = Bars(uv)
    ns = NS
    for side in (1, -1):
        # the arch: ONE slim bow with the posts' tops, a uniform 8 cm deep all round. The bubble's last stretch morphs into the
        # panes' top-edge profile (section()), so its rear edge is that profile at the rear face and its front edge the same
        # profile 8 cm on, at the front face: the flat panes' top edge, so the glass stops at the bow. A cap closes its foot end.
        A = [_pt(edge(z_rear_arch, j, ARCH_REAR[-1], ns, 4.9, 5.4), j, side, ns) for j in range(ns + 1)]
        a0 = A[0] + (A[0] - A[1]) * (0.04 / np.linalg.norm(A[0] - A[1]))      # (the foot runs 4 cm on into the rail band)
        A = [a0] + A
        B = [np.array([a[0], a[1], z_front_arch(a[1])]) for a in A]
        b.bar(A, B, ARCH_OUT, ARCH_IN, axis_y=3.20, caps=(True, False))
        # the sill closure: where the quarter plane's top corner stands above the rail (z 5.11 to 5.30) a short member under the
        # glass's outer edge, flush with the plane and 8.5 cm deep (it runs into the rail and into the arch's foot)
        zc0 = pane_top(0.48)[2] - 0.02
        zs = np.linspace(zc0, 5.30, 12)
        A = [np.array([side * x_out(z), flat_h(x_out(z), z), z]) for z in zs]
        B = [np.array([side * (x_out(z) + 0.040), flat_h(x_out(z) + 0.040, z), z]) for z in zs]
        b.bar(A, B, 0.004, 0.12, axis_y=3.20)
        # the windscreen posts: one slim member from inside the arch bow down into the base frame (the airframe's two bars
        # are cut); its top face lies in the centre plane
        n = 44
        za = pane_top(0.175)[2] - 0.02
        zb = WS_END - 0.005             # (inside the base frame, clear of the nose slope that starts at z 6.238)
        zs = [za + (zb - za) * i / n for i in range(n + 1)]
        A = [np.array([side * BAR_IN, flat_h(BAR_IN, z), z]) for z in zs]
        B = [np.array([side * BAR_OUT, flat_h(BAR_OUT, z), z]) for z in zs]
        b.bar(A, B, POST_OUT, POST_IN_T, axis_y=3.20, up=True)
        # the rear dome (the dorsal fairing aft of the canopy and the hinge band): ONE sheet from the deck at z 3.25 to the band's
        # front edge, a smooth surface with the band eased up on it, so nothing steps or saws along the rear arch
        zs = sorted(set(np.round(np.r_[np.linspace(Z_DOME0, Z_BOW[0] - BOW_RAMP, 10), np.linspace(Z_BOW[0] - BOW_RAMP, Z_BOW[1], 12)], 5)))
        b.skin([_ring(z, side, ns, bow_off(z)) for z in zs])
        # the band's front lip (the glass's rear edge stands against it): from the band's top down 12 mm under the glass's surface
        b.skin([_ring(Z_BOW[1], side, ns, BOW_OUT), _ring(Z_BOW[1], side, ns, -BOW_OUT)])
        # the wall that closes the dome onto the deck at its rear end
        top = _ring(Z_DOME0, side, ns, 0.0)
        bot = [np.array([p[0], (REAR_TROUGH_Y if abs(p[0]) < REAR_RIDGE_X else REAR_RIDGE_Y) - REAR_SKIN_Y, Z_DOME0]) for p in top]
        b.skin([top, bot])
    # the base frame: one cross-bar under the lower edge of the three panes, 12 mm proud of the plane and sunk 45 mm into the nose
    xo = x_out(FOOT_START) + 0.02
    xs = sorted(set(np.round(np.r_[np.linspace(-xo, xo, 25), -BAR_OUT, -BAR_IN, BAR_IN, BAR_OUT], 5)))
    A = [np.array([x, flat_h(x, FOOT_START), FOOT_START]) for x in xs]
    B = [np.array([x, flat_h(x, FOOT_END), FOOT_END]) for x in xs]
    b.bar(A, B, FOOT_OUT, FOOT_IN, axis_y=3.20, up=True)
    return b


def cut_arch_block(geo_bones):
    """removes the airframe's block arch (and the crown strip over the bars' upper ends) from the hull piece; returns
    (faces removed, the uv of one of its faces: the frame's own texel)"""
    zlo, zhi, ylo, yhi, xhi = ARCH_BOX
    plo, phi = np.array(POST_BOX[0]), np.array(POST_BOX[1])
    uv = None
    n = 0
    for bone in geo_bones:
        if bone['name'] != HULL_BONE:
            continue
        pm = bone['poly_mesh']
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        keep = []
        for poly in pm['polys']:
            V = P[[v[0] for v in poly]] if poly else None
            if V is not None and V[:, 2].min() >= zlo and V[:, 2].max() <= zhi and V[:, 1].min() >= ylo and \
                    V[:, 1].max() <= yhi and np.abs(V[:, 0]).max() <= xhi and \
                    not (np.ptp(V[:, 2]) < 1e-3 and V[0, 2] >= 5.38):
                n += 1
                if uv is None:
                    uv = list(pm['uvs'][poly[0][2]])
                continue
            if V is not None and np.all(np.abs(V[:, 0]) >= plo[0]) and np.all(np.abs(V[:, 0]) <= phi[0]) and \
                    np.all(V[:, 2] >= plo[1]) and np.all(V[:, 2] <= phi[1]) and np.all(V[:, 1] >= plo[2]) and np.all(V[:, 1] <= phi[2]):
                n += 1
                if uv is None:
                    uv = list(pm['uvs'][poly[0][2]])
                continue
            keep.append(poly)
        pm['polys'] = keep
    return n, uv

def glass_triangles():
    tris = bubble()
    # the centre pane: ONE plane between the posts
    tris += pane(0.0, lambda z: BAR_IN, GLASS_END)
    # the two quarter panes: ONE plane each, outside the posts, down to where the plane meets the nose's skin
    tris += pane(BAR_OUT, x_out, GLASS_END, ns=10)
    return tris


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    old_uv = next((b['poly_mesh']['uvs'][0] for b in bones if b['name'] == BARS_BONE and b.get('poly_mesh', {}).get('uvs')), None)
    bones[:] = [b for b in bones if b['name'] != BARS_BONE]
    cut, uv = cut_arch_block(bones)
    if uv is None:
        uv = old_uv or UV_FRAME              # (a re-run on the already retrofitted geo: the block is gone; keep the frame's own texel)
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    bars = frame_bars(uv)
    bones.insert(bones.index(hull) + 1, {'name': BARS_BONE, 'parent': hull.get('parent', 'hull'), 'pivot': list(hull['pivot']),
                                         'poly_mesh': bars.mesh()})
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'a_10 canopy: the block arch cut out ({cut} faces), {len(bars.polys)} faces of slim arch, bar ends, rear bow and dorsal fairing; {len(tris)} glass triangles (x2 windings)')
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
