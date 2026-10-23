#!/usr/bin/env python3
"""F-4C canopies and windscreen, fitted to the F-4C.mtb airframe: the glass for the source's own frames.

The F-4C has two cockpits under two separate canopies (type photos and the -1 side views): the pilot's canopy over the
front seat and the back-seater's over the rear seat, each a low blown bubble held by the rail along its sill, with the
windscreen's front arch ahead of the pilot and one bow arch between the two cockpits. The airframe already carries
that frame (all of it in the two fuselage hull bones: the sill rails, the rear hump behind the back seat, the bow
between the cockpits, the front arch and the two windscreen rails running down to the nose deck). It is the frame, so
this script adds no frame and fits the glass to it:

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

# the windscreen: three panes between the airframe's own members. Its two rails (9 cm wide posts, 0.09 to 0.17 out from the
# centre line, topped 3.14 at the arch falling to 2.85 at the nose deck) divide the centre pane (between them) from the
# two quarter panes (out from the rails' outer edge to the edge of the fairing, 0.29 out and 2.80 high falling to 0.25 and 2.72).
WS_Z = (6.17, 6.80)             # centre pane
WQ_Z = (6.10, 6.66)             # quarter panes


def ws_half(z):                  # the rails' inner edge
    return min(0.090, 0.050 + (z - 6.15) * 0.18)


def ws_y(z):                     # the rails' top surface (the glass lies a hair below it)
    return 3.142 - 0.528 * (z - 6.25) - 0.012


def wq_in(z):                    # the rails' outer edge
    return 0.150 + min(0.025, (z - 6.15) * 0.1)


def wq_out(z):                   # the fairing's edge (x, y)
    return 0.292 - 0.08 * (z - 6.20), 2.80 - 0.16 * (z - 6.20)

SILL_Y = 2.937                   # the hull's sill: the outline the checks read starts here

# the faces of the frame members the glass stops at
def z_aft(y):                    # the rear hump behind the back seat (its front face leans a little forward at the foot)
    return 3.44 + 0.37 * (3.5 - min(y, 3.5))


def z_bow_rear(y):               # the bow's rear face
    return 4.53 + 0.37 * (3.5 - min(y, 3.5))


def z_bow_front(y):              # the bow's front face
    return min(4.97, 4.89 + 0.30 * (3.5 - min(y, 3.5)))


def z_arch_rear(y):              # the windscreen arch's rear face
    return 5.92

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


def windscreen(nz=10):
    """the three panes: the centre one between the rails' inner edges, and the two quarter panes from the rails' outer
    edge down and out to the fairing's edge; each stops at its member's edge"""
    out = []
    zs = np.linspace(WS_Z[0], WS_Z[1], nz + 1)
    for z0, z1 in zip(zs[:-1], zs[1:]):
        w0, w1 = ws_half(z0) + 0.003, ws_half(z1) + 0.003
        a, b = np.array([-w0, ws_y(z0), z0]), np.array([w0, ws_y(z0), z0])
        c, d = np.array([w1, ws_y(z1), z1]), np.array([-w1, ws_y(z1), z1])
        out += [[a, b, c], [a, c, d]]
    zq = np.linspace(WQ_Z[0], WQ_Z[1], nz + 1)
    for side in (1, -1):
        for z0, z1 in zip(zq[:-1], zq[1:]):
            def pt(z, outer):
                if outer:
                    x, y = wq_out(z)
                    return np.array([side * x, y, z])
                return np.array([side * (wq_in(z) - 0.003), ws_y(z), z])
            a, b, c, d = pt(z0, False), pt(z0, True), pt(z1, True), pt(z1, False)
            out += [[a, b, c], [a, c, d]]
    return out

def glass_triangles():
    tris = patch(z_aft, z_bow_rear, 3.46, 4.62, 24)                   # the back seat's canopy: rear hump -> bow
    tris += patch(z_bow_front, z_arch_rear, 4.89, 5.92, 22)           # the pilot's canopy: bow -> windscreen arch
    tris += windscreen()                                              # the windscreen's centre panel
    return tris


def main(argv):
    dry = '--dry-run' in argv
    asset = json.load(open(ASSET, encoding='utf-8'))
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'f_4c canopy: {len(tris)} glass triangles (x2 windings): rear canopy, front canopy, windscreen panes')
    if dry:
        return
    glass_json = asset.pop('CanopyGlass')
    text = json.dumps(asset, indent=2, ensure_ascii=False)
    text = text[:-2] + ',\n  "CanopyGlass": ' + json.dumps(glass_json, separators=(',', ':')) + '\n}'
    with open(ASSET, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text + '\n')


if __name__ == '__main__':
    main(sys.argv[1:])
