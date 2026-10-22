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
  * the windscreen: the centre pane between the bars (|x| < 0.145) and one quarter pane each side outside them
    (0.205 out to the foot on the nose deck), all from the arch's front face down to the deck at the bars' lower cross-bar
    (z 6.24); the glass leaves the 5 cm where each bar stands bare, so it never runs under the bar;
  * the section at every station is a super-ellipse from the rail (or the deck) up to the crown; the crown over the bubble
    peaks at 3.665 over the headrest and falls to the arch's top (3.49), and over the windscreen follows the bars' slope
    (3.51 at the arch, 2.84 at the deck).

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

# ---- the frame's measurements (absolute blocks)
# the sill rails' inner top edge: z, x of the inner edge, y of the top (the rails rise toward the dorsal fairing)
RAIL = [(3.25, 0.340, 3.235), (3.50, 0.380, 3.190), (3.75, 0.400, 3.145), (4.00, 0.420, 3.100), (4.30, 0.435, 3.070),
        (4.60, 0.450, 3.000), (4.90, 0.460, 2.955), (5.10, 0.470, 2.930), (5.22, 0.480, 2.920)]
# the crown line over the centre (z, y)
CROWN = [(3.25, 3.245), (3.30, 3.290), (3.45, 3.415), (3.60, 3.520), (3.80, 3.600), (4.00, 3.640), (4.30, 3.665),
         (4.60, 3.650), (4.85, 3.600), (5.05, 3.545), (5.14, 3.515), (5.22, 3.490)]
NEXP = 2.2                       # the sections' super-ellipse exponent

# the windscreen: from the arch's front face (z 5.29) to the deck (z 6.24): the foot on the nose deck (x, y) and the crown
WS = [(5.29, 0.460, 2.930), (5.55, 0.400, 2.820), (5.90, 0.330, 2.820), (6.24, 0.270, 2.780)]
WS_END = 6.24
BAR_IN, BAR_OUT = 0.145, 0.205   # the centre pane's half width and where the quarter panes start (the bars stand between)


def ws_crown(z):
    return 3.435 - 0.706 * (z - 5.39)


# the arch's faces (y -> z): the rear face the bubble stops at, the front face the windscreen starts at
ARCH_Y = [2.90, 3.15, 3.35, 3.50, 3.60]
ARCH_REAR = [4.94, 5.04, 5.09, 5.14, 5.14]
ARCH_FRONT = [5.19, 5.19, 5.24, 5.29, 5.29]


def z_rear_arch(y):
    return float(np.interp(y, ARCH_Y, ARCH_REAR))


def z_front_arch(y):
    return float(np.interp(y, ARCH_Y, ARCH_FRONT))


SILL_Y = 2.85                    # the outline the checks read starts here (the leaning rail band's inner face under the rail)

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


def section(z, n=22):
    """the right half-section at station z: absolute (x, y) points from the foot up to the crown (x = 0)"""
    xb, yb, yt = base_crown(z)
    us = np.linspace(0, 1, n)
    pts = [(xb * (1 - u ** NEXP) ** (1 / NEXP), yb + (yt - yb) * u) for u in us]
    pts[-1] = (0.0, yt)
    return pts


def height_at(x, z):
    """the glass surface's height at (x, z): the section's super-ellipse"""
    xb, yb, yt = base_crown(z)
    u = (1 - min(1.0, abs(x) / xb) ** NEXP) ** (1 / NEXP) if xb > 0 else 0.0
    return yb + (yt - yb) * u


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


def resample(pts, k):
    p = np.array(pts, float)
    d = np.r_[0, np.cumsum(np.linalg.norm(np.diff(p, axis=0), axis=1))]
    t = np.linspace(0, d[-1], k)
    return np.c_[np.interp(t, d, p[:, 0]), np.interp(t, d, p[:, 1])]


def edge(zf, j, z, ns, lo, hi):
    """the station where row j of the section meets the frame face zf(y): the row's height depends on the station, so
    the face is found by iterating"""
    for _ in range(4):
        y = resample(section(z), ns + 1)[j][1]
        z = min(hi, max(lo, zf(y)))
    return z


def bubble(nz=34, ns=14):
    """the bubble, both sides: row j of the arc-length resampled section runs from the dorsal end (z 3.25) to the arch's
    rear face (found at the row's own height)"""
    z0, z_hi = 3.25, 5.14
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


def pane(x_lo, x_hi_of_z, z_start_of_y, z_end, nz=14, ns=6):
    """one windscreen pane, both sides: column j runs across the pane at constant fraction between x_lo and x_hi(z); its rows
    start at the arch's front face (found at the row's own height) and run to z_end"""
    out = []
    for side in (1, -1):
        grid = []
        # the start station per column: the arch's front face at the height of the column's top-edge
        starts = []
        for j in range(ns + 1):
            f = j / ns
            z = WS[0][0]
            for _ in range(4):
                x = x_lo + (x_hi_of_z(z) - x_lo) * f
                z = max(WS[0][0], z_start_of_y(height_at(x, z)))
            starts.append(z)
        for i in range(nz + 1):
            row = []
            for j in range(ns + 1):
                f = j / ns
                z = starts[j] + (z_end - starts[j]) * i / nz
                x = x_lo + (x_hi_of_z(z) - x_lo) * f
                row.append(np.array([side * x, height_at(x, z), z]))
            grid.append(row)
        out += quads(grid)
    return out


def glass_triangles():
    tris = bubble()
    # the centre pane: between the bars (the arch's crown strip ends at z 5.39 where the bars start)
    tris += pane(0.0, lambda z: BAR_IN, lambda y: 5.39, WS_END)
    # the two quarter panes: outside the bars, to the foot on the nose deck (a hair inside it)
    tris += pane(BAR_OUT, lambda z: base_crown(z)[0] - 0.004, z_front_arch, WS_END)
    return tris


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'a_10 canopy: the airframe has no glass stand-ins to remove; {len(tris)} glass triangles (x2 windings)')
    if dry:
        return
    glass_json = asset.pop('CanopyGlass')
    text = json.dumps(asset, indent=2, ensure_ascii=False)
    text = text[:-2] + ',\n  "CanopyGlass": ' + json.dumps(glass_json, separators=(',', ':')) + '\n}'
    with open(ASSET, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text + '\n')


if __name__ == '__main__':
    main(sys.argv[1:])
