#!/usr/bin/env python3
"""F-16A canopy, fitted to the F-16A.mtb airframe: the glass for the source's own canopy frame.

The F-16 canopy (side views and photos of the type): a one-piece bubble with no windscreen arch or front bow at all,
the transparency running clear from the nose to the single transverse bow just behind the seat's headrest, the frame
along the sills and across the rear, and a slim edge at the nose where the glass seals against the skin.

(Retrofit check against the owner's framing standard: a one-piece bubble with NO windscreen bow and no overhead bar,
only the rear arch and the two sill rails; the airframe's frame is exactly that, so it stays, with nothing added.)

The airframe already carries exactly that frame (bone wreck_fuselage_1__canopy, one connected piece: the two sill
rails, the rear arch that leans back over the top, the front edge), so this script keeps it as THE frame, never adds a
second one, and fits the glass to it:

  * the glass starts on the inner top edge of each sill rail, on the front face of the rear arch (it stops at the arch's
    edge, never runs under it) and ends on the rear face of the front edge;
  * the section at every station is a super-ellipse from the rail up to the crown, the crown line taken from the
    frame's own centre strip (2.945 over the pilot, falling to the nose);
  * the frame's eight centre-strip faces (the airframe's stand-in for the glass: opaque pale quads along the top) are
    removed, since the real glass takes their place.

CANOPY (eye-relative sections, the right half from the sill up over the top) is what the checks in
work/elite_expansion_2/checks read for the canopy outline. Frames: vehicle-local blocks, model pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script f_16a.py then appends the HUD combiner to CanopyGlass.
Usage: python tools/cockpit_models/canopy_f_16a.py [--dry-run]
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt_canopy as C     # noqa: E402

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/f_16a.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/f_16a.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/f_16a.json')
CANOPY_BONE = 'wreck_fuselage_1__canopy'

# ---- the frame's measurements (absolute blocks)
# the rear arch's front face from the sill up over the top: (x of its inner edge, y, z of its front face)
ARCH = [(0.340, 2.446, 2.439), (0.340, 2.514, 2.382), (0.317, 2.650, 2.337), (0.272, 2.740, 2.292), (0.181, 2.820, 2.246),
        (0.0, 2.887, 2.212)]
# the sill rails' inner top edge: z, x, y; the front edge's rear face at z 4.364
RAIL = [(2.439, 0.340, 2.446), (2.700, 0.352, 2.400), (2.982, 0.362, 2.355), (3.300, 0.350, 2.325), (3.600, 0.338, 2.305),
        (3.933, 0.317, 2.287), (4.183, 0.255, 2.287), (4.364, 0.150, 2.287)]
# the crown line over the centre (z, y): the frame's centre strip, smoothed
CROWN = [(2.212, 2.887), (2.30, 2.915), (2.44, 2.935), (2.62, 2.944), (3.00, 2.945), (3.20, 2.935), (3.50, 2.875),
         (3.75, 2.775), (4.00, 2.620), (4.20, 2.470), (4.364, 2.335)]
NEXP = 2.6                       # the sections' super-ellipse exponent
Z_FRONT = 4.364

# the eye (from the vehicle data), set by main(); the module also loads without it for the checks
_EYE = np.array([0.0, 2.60299, 2.86947375])


def _eye():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        s = d['Seats'][0]
        return np.array(d['Attachments'][s['CameraPos']['EyeAttachment']]['Position'], float)
    except (OSError, KeyError):
        return _EYE


def base_of(z):
    """where the glass starts at station z: on the arch's front face behind the sill line, then on the rail"""
    if z < RAIL[0][0]:
        zs = [a[2] for a in ARCH][::-1]
        return (float(np.interp(z, zs, [a[0] for a in ARCH][::-1])), float(np.interp(z, zs, [a[1] for a in ARCH][::-1])))
    zs = [r[0] for r in RAIL]
    return (float(np.interp(z, zs, [r[1] for r in RAIL])), float(np.interp(z, zs, [r[2] for r in RAIL])))


SILL_Y = 2.17                    # the lowest of the hull's sill band under the rails (2.22 at the arch, 2.17 at the front)


def section(z, n=22):
    """the right half-section at station z: absolute (x, y) points from the base up to the crown (x = 0)"""
    xb, yb = base_of(z)
    yt = float(np.interp(z, [c[0] for c in CROWN], [c[1] for c in CROWN]))
    us = np.linspace(0, 1, n)
    pts = [(xb * (1 - u ** NEXP) ** (1 / NEXP), yb + (yt - yb) * u) for u in us]
    pts[-1] = (0.0, yt)
    return pts


STATIONS = [2.2125, 2.225, 2.245, 2.27, 2.30, 2.33, 2.36, 2.39, 2.42, 2.439, 2.50, 2.60, 2.70, 2.85, 2.982, 3.14, 3.30,
            3.45, 3.60, 3.77, 3.933, 4.06, 4.183, 4.28, 4.364]


def sections_rel(eye):
    """the cabin outline the checks read: from the sill line straight up the rail's inner face, then the glass"""
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


def glass_triangles(eye, ns=12):
    """the glass: each station's curve (from the rail / arch up to the crown) resampled to ns + 1 points"""
    rows = [(z, resample(section(z), ns + 1)) for z in STATIONS]
    out = []
    for side in (1, -1):
        for (za, ra), (zb, rb) in zip(rows[:-1], rows[1:]):
            for j in range(ns):
                a = np.array([side * ra[j][0], ra[j][1], za]); b = np.array([side * rb[j][0], rb[j][1], zb])
                c = np.array([side * rb[j + 1][0], rb[j + 1][1], zb]); d = np.array([side * ra[j + 1][0], ra[j + 1][1], za])
                for tri in ((a, b, c), (a, c, d)):
                    if np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0])) > 1e-10:
                        out.append([t for t in tri])
    return out

def main(argv):
    dry = '--dry-run' in argv
    eye = _eye()
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bone = next(b for b in geo['minecraft:geometry'][0]['bones'] if b['name'] == CANOPY_BONE)
    pm = bone['poly_mesh']
    P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    keep, gone = [], 0
    for poly in pm['polys']:
        V = P[[v[0] for v in poly]]
        if np.abs(V[:, 0]).max() <= 0.1365 and np.ptp(V[:, 2]) > 0.35:       # the centre strip standing in for glass
            gone += 1
            continue
        keep.append(poly)
    pm['polys'] = keep
    tris = glass_triangles(eye)
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'f_16a canopy: {gone} centre-strip faces removed from the frame; {len(tris)} glass triangles (x2 windings)')
    if dry:
        return
    with open(GEO, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(geo, f, separators=(',', ':'))
    glass_json = asset.pop('CanopyGlass')
    text = json.dumps(asset, indent=2, ensure_ascii=False)
    text = text[:-2] + ',\n  "CanopyGlass": ' + json.dumps(glass_json, separators=(',', ':')) + '\n}'
    with open(ASSET, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text + '\n')


if __name__ == '__main__':
    main(sys.argv[1:])
