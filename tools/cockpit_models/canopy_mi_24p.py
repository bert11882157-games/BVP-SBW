#!/usr/bin/env python3
"""Mil Mi-24P canopies: the Mi-24P.mtb model's OWN canopy frames, kept exactly as the original modeller made them, with hand-fitted glass.

The converted model carries two open-frame canopies (no glass). Nothing of them is cut, regenerated, mirrored or reshaped here: every frame member
(both oval windscreen hoops, the sill and beltline rails, the operator's roof rail, the pilot's B post and roof rails, the wipers), the side-wall
slabs, the partition and its arch plates between the crews, the nose underside stay in the `hull` piece as modelled. Only the old interior is cut:
the seat groups, panels, floors and tub linings that mi_24p.py replaces (CUT_RANGES, mi24p/mk_cut4.py: the old cut of 130fad02 minus
mi24p/restore_sets.py).

The glass is HAND-FITTED and lives in tools/cockpit_models/glass_mi_24p_hand.json: one entry per opening, each opening a few flat panes, every pane's
vertices written out explicitly (vehicle blocks: x to the left, y up, z forward) with the opening it closes named. This script only reads that file
and emits the faces as written (a convex polygon becomes a triangle fan), plus the planarity check's pane list (flat_panes/mi_24p.json). No fitting, no
hull, no auto-glaze: nothing here decides where glass goes.

CANOPY (eye-relative sections of the glass, the half from the sill up over the top) is what the checks in work/elite_expansion_2/checks read; it is
cut from the panes. OB_BL / OB_TL (the operator windscreen's bottom corner and top centre) are read by mi_24p.py.

Run first (on the airframe's files); the cockpit script mi_24p.py follows (mi24p/run_mi24p_cockpit.ps1).
Usage: python tools/cockpit_models/canopy_mi_24p.py [--dry-run]"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

VID = 'mi_24p'
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, f'assets/berts_vehicle_pack/custom_geo/{VID}.geo.json')
ASSET = os.path.join(GEN, f'assets/berts_vehicle_pack/sbw/vehicles/{VID}.json')
PANES_JSON = os.path.join(HERE, 'flat_panes', f'{VID}.json')
GLASS_FILE = os.path.join(HERE, 'glass_mi_24p_hand.json')
HULL_BONE = 'hull'
CANOPY_BONE = 'hull__canopy'

EYE_P = np.array([0.0, 3.2342, 5.2595])          # the pilot's eye (data: pilot_eye)
EYE_G = np.array([0.0, 2.5004, 7.0532])          # the operator's eye (data: gunner_eye)

# faces of the hull piece that the new cockpits replace (the untouched hull has HULL_FACES faces)
CUT_RANGES = [
    (459, 459), (465, 465), (471, 471), (477, 477), (484, 485), (508, 510), (512, 512), (555, 558), (560, 560), (563, 563), (569, 569), (638, 638),
    (641, 641), (647, 648), (650, 651), (659, 659), (680, 680), (686, 686), (692, 692), (708, 708), (714, 714), (720, 720), (726, 726), (782, 784),
    (786, 786), (857, 857), (863, 863), (883, 883), (885, 885), (992, 992), (998, 998), (1004, 1004), (1015, 1016), (1018, 1018), (1023, 1024), (1026,
    1026), (1035, 1035), (1040, 1040), (1384, 1384), (1389, 1389), (1392, 1392), (1394, 1394), (1408, 1408), (1414, 1414), (1421, 1421), (1428, 1428),
    (1434, 1434), (1455, 1455), (1459, 1459), (1465, 1465), (1469, 1470), (1491, 1492), (1495, 1496), (1499, 1500), (1502, 1502), (2410, 2410), (2416,
    2416), (2422, 2422), (2428, 2428), (2431, 2431), (2453, 2453), (2455, 2455), (2460, 2463), (2507, 2511), (2515, 2515), (2521, 2521), (2587, 2588),
    (2599, 2599), (2603, 2603), (2605, 2607), (2640, 2643), (2820, 2820), (2826, 2826), (2837, 2837), (2852, 2852), (2857, 2858), (2861, 2861), (2863,
    2864), (2869, 2869), (2871, 2871), (2875, 2875), (3163, 3163), (3172, 3172), (3177, 3177), (3196, 3196), (3202, 3202), (3210, 3210), (3222, 3222),
    (3243, 3243), (3247, 3247), (3258, 3258), (3712, 3712), (3723, 3724), (3741, 3908), (3912, 3912), (3918, 3918), (3924, 3924), (3945, 3946), (3948,
    4063), (4072, 4072), (4078, 4078), (4082, 4084), (4086, 4108), (4110, 4279), (4282, 4282), (4288, 4288), (4294, 4294), (4298, 4299), (4303, 4305),
    (4307, 4311), (4313, 4314), (4317, 4317), (4323, 4323), (4327, 4362), (4387, 4389), (4394, 4394), (4407, 4407), (4414, 4415), (4422, 4423), (4433,
    4433), (4438, 4439), (4444, 4445), (4450, 4451), (4473, 4473), (4476, 4477), (4549, 4549), (4777, 4872), (5311, 5311), (5317, 5317), (5369, 5369),
    (5375, 5375), (5381, 5381), (5397, 5397), (5403, 5403), (5409, 5409), (5415, 5415), (5469, 5469), (5475, 5475), (5523, 5642), (5644, 5690), (5692,
    5696), (5698, 5702), (5704, 5713), (5715, 5718), (5720, 5784), (5797, 5797), (5799, 5803), (5805, 5805), (5807, 5811), (5813, 5814), (5821, 5821),
    (5823, 5827), (5829, 5829), (5831, 5835), (5837, 5922), (8217, 8262)]
HULL_FACES = 8521


def load_panes():
    """[(opening, pane name, Nx3 vertices)] exactly as written in the hand file"""
    data = json.load(open(GLASS_FILE, encoding='utf-8'))
    out = []
    for op in data['openings']:
        for p in op['panes']:
            out.append((op['name'], p['name'], np.array(p['vertices'], float)))
    return out


PANES = {}
for _op, _nm, _v in load_panes():
    PANES[_nm] = {'opening': _op, 'poly': _v}


def tris_of(poly):
    """the pane's polygon as triangles (an ear-clip of the vertices as written: the windscreens' outlines are not convex)"""
    P = np.array(poly, float)
    c = P.mean(0)
    n = np.linalg.svd(P - c)[2][2]
    u = np.cross(n, [0.0, 1.0, 0.0] if abs(n[1]) < 0.9 else [1.0, 0.0, 0.0])
    u /= np.linalg.norm(u)
    w = np.cross(n, u)
    P2 = [((q - c) @ u, (q - c) @ w) for q in P]
    area = sum(P2[i][0] * P2[(i + 1) % len(P)][1] - P2[(i + 1) % len(P)][0] * P2[i][1] for i in range(len(P)))
    sg = 1.0 if area > 0 else -1.0
    cr = lambda o, a, b: (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    idx = list(range(len(P)))
    out = []
    while len(idx) > 3:
        for k in range(len(idx)):
            i0, i1, i2 = idx[k - 1], idx[k], idx[(k + 1) % len(idx)]
            a, b, c2 = P2[i0], P2[i1], P2[i2]
            if cr(a, b, c2) * sg <= 1e-12:
                continue
            if any(cr(a, b, P2[j]) * sg >= -1e-12 and cr(b, c2, P2[j]) * sg >= -1e-12 and cr(c2, a, P2[j]) * sg >= -1e-12
                   for j in idx if j not in (i0, i1, i2)):
                continue
            out.append((i0, i1, i2))
            idx.pop(k)
            break
        else:
            break
    out.append(tuple(idx))
    return [[P[a], P[b], P[c3]] for a, b, c3 in out]


for _p in PANES.values():
    _p['tris'] = tris_of(_p['poly'])

OB_BL = PANES['operator windscreen']['poly'][0]
OB_TL = PANES['operator windscreen']['poly'][5]


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
Z_FAIRING_FILL = (4.72, 5.14)
HUMP_SECTION = [(0.62, 2.30), (0.62, 2.55), (0.42, 2.76), (0.30, 2.84), (0.0, 2.84)]     # the model's own hump between the crews


def outline_at(z):
    """the glass's section at station z as [(|x|, y), ...] from the sill up to the top on the axis (None if the glass does not reach z)"""
    if Z_FAIRING_FILL[0] <= z < Z_FAIRING_FILL[1]:                # behind the roof glass: the engine fairing's raked front closes the top
        z = Z_FAIRING_FILL[1] + 0.001
    segs = []
    for pane in PANES.values():
        for t in pane['tris']:
            s = _slice(t, z)
            if s:
                segs.append(s)
    if 6.14 <= z <= 6.58 and not segs:
        return HUMP_SECTION
    if not segs:
        return None
    ys = [p[1] for s in segs for p in s]
    y_lo, y_hi = min(ys), max(ys)
    if y_hi - y_lo < 0.15:                                         # a lone windscreen station: one line of glass at one height
        w = max(abs(p[0]) for s in segs for p in s)
        if z > 6.9:
            return [(w, 1.66), (w, y_hi), (0.0, y_hi)]
        return HUMP_SECTION[:3] + [(w, y_hi), (0.0, y_hi)]
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
    if pts[0][1] > ys_ + 0.02:                                    # the glass starts above the sill (a beltline rail): the section starts at the rim
        xs_ = float(np.interp(z, [q[0] for q in SILL_X], [q[1] for q in SILL_X]))
        pts[0:0] = [(xs_, ys_), (xs_, pts[0][1])]
    pts.append((0.0, y_hi))
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


CANOPY = sections_rel()


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
    return {'why': ('Mi-24P canopies: the model\'s own frames, hand-fitted glass (glass_mi_24p_hand.json). Every pane of every opening is listed: one flat polygon each.'),
            'panes': out}


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    bones[:] = [b for b in bones if b['name'] != CANOPY_BONE]
    cut = cut_old(bones)
    glass = [t for p in PANES.values() for t in p['tris']]
    both = [list(map(float, q)) for t in glass for q in t] + [list(map(float, t[k])) for t in glass for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'{VID} canopy: {cut} old interior faces cut (the model\'s canopy frames, hump and nose kept); {len(PANES)} hand-fitted glass panes in '
          f'{len({p["opening"] for p in PANES.values()})} openings, {len(glass)} triangles')
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


if __name__ == '__main__':
    main(sys.argv[1:])
