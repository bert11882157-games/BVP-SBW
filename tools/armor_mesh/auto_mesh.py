#!/usr/bin/env python3
"""Mesh armor for a vehicle straight from its visual model, without a hand-written spec.

    python3 tools/armor_mesh/auto_mesh.py <id> [<id>...]     # writes armor_mesh/<id>.geo.json
    python3 tools/armor_mesh/auto_mesh.py --targets           # every vehicle in AUTO_TARGETS
    python3 tools/armor_mesh/auto_mesh.py <id> --report       # plate table only, nothing written

The T-90A's armor (build_mesh.py + specs/t90a.json) was measured by hand. This builds the same kind of file for the
other ground vehicles automatically; the output format and the plate construction (mitred 1 px slabs on every
face of a convex solid) are build_mesh.py's own.

* **Frames.** Model bones are sorted into the hull, turret and barrel frames by their parent chain (``turret`` and
  ``barell``). Running gear (wheels, tracks), ERA (``bvpEra*`` bones and anything inside an ERA box of the armor
  profile) and secondary mounts (``passengerWeaponStation*``, missile pods, the MILAN mount...) are left out.
* **Structure.** Each frame keeps the model's connected components (the model's primitives) except thin rods
  (antennas, rails, cables) and specks. The hull solid is their convex hull, with every point below the top of the
  running gear pulled in to the inner face of the tracks/wheels: shots through the running gear go on to the hull
  side behind it (tracks have no hitbox). The turret solid is the convex hull of the turret frame; the mantlet is the
  convex hull of the barrel-frame parts around the trunnion (the gun tube is left out). A collar closes any gap
  between the hull roof and the turret.
* **Thickness.** Every face takes the thickness of the vehicle's existing box plate it lines: a ray from outside
  along the face normal is traced into the box plates of the same frame (tools/armor_mesh/templates/<id>, i.e. the
  armor profile's own boxes in model coordinates) and the first plate it meets gives the millimetres (median over
  sample points on the face; nearest plate when no ray finds one). The face's mirror image is looked up too and
  both sides take the thicker value (box profiles are sometimes lopsided by a mislabelled plate); --report lists
  every such pair. Vehicles without box plates use nominal values per aspect from DEFAULTS.

Engines, ammunition, modules, internals and ERA stay on the profile's boxes: the file defines plates only (the loader
replaces only the categories a mesh file defines).
"""
import argparse
import collections
import json
import math
import os
import re
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_mesh as B  # noqa: E402
import shape as S  # noqa: E402

TEMPLATES = os.path.join(HERE, 'templates')

# Every armored ground vehicle except the ones with hand-authored box armor the owner keeps (BMP-2/2M, BMP-1, T-90A
# (hand-measured mesh), all Abrams, T-72B, M48, M1128, T-62A, BTR-80A, BTR-60, the Toyotas, ZBD-09, Marder 1A2,
# CV9040C, M2 Bradley, BMPT) and the emplacements (tripods, ZU-23). The T-55A, Leopard 2A6, LAV-25, BTR-90, T-72A,
# T-90M and VBCI keep their box armor (owner, 2026-09-28).
AUTO_TARGETS = [
    '9p148', '9p149_shturm', 'bmd_1', 'bmp3m_elite', 'btr_zd', 'challenger_2',
    'gaz_3937_vodnik_aa', 'gepard', 'k2a1_black_panther', 'leclerc_s1', 'leopard_2a4',
    'm109a7_paladin', 'm551a1', 'm60a1', 'marder_1a1', 'marder_1a5', 'pzh_2000', 'qn_506model', 't14_armata',
    't64b_obr1976', 't72b3', 't72b3_ubh_cope', 't80b_obr1976', 't80u_obr1985',
    '9k22_tunguska', 'type_90', 'vt_4a1', 'zsl_92', 'zsu23_4', 'ztl_09', 'ztz99a', 'uaz_469_spg9',
]

# Meshes the owner tuned by hand in Blockbench (tools/armor_authoring/with_armor.py): --targets leaves them alone.
HAND_TUNED = set()

# Nominal plate thickness (mm) per aspect for vehicles whose profile has no box plates. Public figures, rounded:
# BRDM-2 14 front / 7 elsewhere; MT-LB 10 / 7; BMD-1 and BTR-D 15 front / 10 sides (turret 23 / 19, from the
# BMP-1); GAZ-3937 5 (armored body); Gepard on the Leopard 1 hull (70 front at 60 deg, 35 upper / 25 lower sides,
# turret ~20); WZ551 (ZSL-92) 10 front / 8; ZSU-23-4 9.2 front and sides; UAZ-469 sheet steel.
DEFAULTS = {
    '9p148':              {'front': 14, 'side': 7, 'rear': 7, 'roof': 7, 'belly': 5, 'turret': 7},
    '9p149_shturm':       {'front': 10, 'side': 7, 'rear': 7, 'roof': 7, 'belly': 5, 'turret': 7},
    'bmd_1':              {'front': 15, 'side': 10, 'rear': 10, 'roof': 10, 'belly': 7,
                           'turret_front': 23, 'turret': 19},
    'btr_zd':             {'front': 15, 'side': 10, 'rear': 10, 'roof': 10, 'belly': 7, 'turret': 8},
    'gaz_3937_vodnik_aa': {'front': 5, 'side': 5, 'rear': 5, 'roof': 4, 'belly': 4, 'turret': 5},
    'gepard':             {'front': 70, 'side': 35, 'rear': 25, 'roof': 10, 'belly': 15,
                           'turret_front': 20, 'turret': 15},
    'zsl_92':             {'front': 10, 'side': 8, 'rear': 8, 'roof': 6, 'belly': 6, 'turret': 8},
    'zsu23_4':            {'front': 9.2, 'side': 9.2, 'rear': 9.2, 'roof': 9.2, 'belly': 7, 'turret': 9.2},
    'uaz_469_spg9':       {'front': 2, 'side': 2, 'rear': 2, 'roof': 1.5, 'belly': 2, 'turret': 2},
}

# Screens that are not armor and sit on a frame without a bone of their own: (frame, lowest y, top above) - a part
# of that frame starting at or above `lowest y` and reaching above `top above` is left out. The T-72B3's anti-drone
# "cope cage" is modelled as see-through boxes and ribs on the turret bone.
SCREENS = {'t72b3_ubh_cope': [('turret', 35.0, 45.0)]}

TRACK = re.compile(r'^(wheel|track|crudetrack|brokentrack|steer|trackmov|trackrot|roadwheel|sprocket|idler)', re.I)
RUNNING = re.compile(r'^(wheel|track|steer|trackmov|trackrot|roadwheel|sprocket|idler)', re.I)
ERA = re.compile(r'(^|_)era(_|\d|$)|bvpera', re.I)
AUX = re.compile(r'^(passengerweaponstation|pod_|milan_|grenade_|sight_|smoke|antenna|searchlight|light_)', re.I)

ROUND = 0.25     # px: model points are snapped to this grid before the hulls (fewer sliver faces)
ROD_THICK = 1.2  # px: a component thinner than this in its smallest extent and ...
ROD_LONG = 10.0  # px: ... longer than this in its largest is a rod (antenna, rail, cable) and left out
SPECK = 8.0      # px^3: bounding-box volume below which a component is left out
SEGMENT = 32.0   # px: target length of the lengthwise hull segments


# ---------------------------------------------------------------- model

def load_model(vid):
    g = json.load(open(os.path.join(B.GEO, f'{vid}.geo.json')))['minecraft:geometry'][0]
    return {b['name']: b for b in g['bones']}


def chain(bones, name):
    out = []
    while name:
        out.append(name)
        name = bones[name].get('parent') if name in bones else None
    return out


def classify_bones(bones):
    """name -> 'hull' | 'turret' | 'barrel' | None (left out) and name -> True for running-gear bones."""
    frame, running = {}, {}
    for name in bones:
        ch = chain(bones, name)
        running[name] = any(RUNNING.match(c) for c in ch)
        if any(TRACK.match(c) or ERA.search(c) or AUX.match(c) for c in ch):
            frame[name] = None
        elif 'barell' in ch or 'barrel' in ch:
            frame[name] = 'barrel'
        elif 'turret' in ch:
            frame[name] = 'turret'
        else:
            frame[name] = 'hull'
    return frame, running


def bone_points(b):
    """A bone's own geometry as connected components: (points, polygons) per component, geo px."""
    comps = []
    rot = b.get('rotation')
    if rot and any(abs(x) > 1e-6 for x in rot):
        print(f"  note: bone {b['name']} has a rest rotation {rot}; its geometry is used unrotated")
    pm = b.get('poly_mesh')
    if pm and pm.get('polys'):
        P = np.array(pm['positions'], float)
        parent = list(range(len(P)))

        def find(i):
            while parent[i] != i:
                parent[i] = parent[parent[i]]
                i = parent[i]
            return i
        for poly in pm['polys']:
            vs = [v[0] for v in poly]
            for v in vs[1:]:
                parent[find(v)] = find(vs[0])
        same = collections.defaultdict(list)
        for i, p in enumerate(P):
            same[tuple(np.round(p, 2))].append(i)
        for vs in same.values():
            for v in vs[1:]:
                parent[find(v)] = find(vs[0])
        groups = collections.defaultdict(set)
        gpolys = collections.defaultdict(list)
        for poly in pm['polys']:
            for v in poly:
                groups[find(v[0])].add(v[0])
            gpolys[find(poly[0][0])].append(P[[v[0] for v in poly]])
        comps += [(P[sorted(vs)], gpolys[k]) for k, vs in groups.items()]
    for c in b.get('cubes', []):
        o = np.array(c['origin'], float)
        s = np.array(c['size'], float)
        pts = np.array([[o[0] + dx * s[0], o[1] + dy * s[1], o[2] + dz * s[2]]
                        for dx in (0, 1) for dy in (0, 1) for dz in (0, 1)])
        comps.append((pts, [np.array(f) for f in B.box_faces(pts.min(0), pts.max(0))]))
    return comps


# ---------------------------------------------------------------- box armor (templates)

def load_template(vid):
    """Box plates and ERA of the armor profile in model coordinates: {'plates': {frame: [(tris, mm, name)]},
    'era': [halfspace arrays]}. None when the vehicle has no template."""
    path = os.path.join(TEMPLATES, f'{vid}.armor.geo.json')
    if not os.path.exists(path):
        return None
    g = json.load(open(path))['minecraft:geometry'][0]
    bones = {b['name']: b for b in g['bones']}
    plates = collections.defaultdict(list)
    era = []
    for b in g['bones']:
        kind, _, rest = b['name'].partition('__')
        pm = b.get('poly_mesh')
        if not pm:
            continue
        ch = chain(bones, b['name'])
        frame = 'barrel' if 'armor_barrel' in ch else 'turret' if 'armor_turret' in ch else 'hull'
        P = np.array(pm['positions'], float)
        tris = []
        for poly in pm['polys']:
            vs = [P[v[0]] for v in poly]
            for j in range(1, len(vs) - 1):
                tris.append((vs[0], vs[j], vs[j + 1]))
        if kind in ('plate', 'armor'):
            if 'starter_' in rest:
                continue  # placeholder volumes of a vehicle without box armor, not real thickness
            m = re.match(r'([0-9.]+)', rest)
            if m:
                T = np.array(tris)
                # the plate's facing: the normal of its largest face (a box plate is a thin slab)
                cr = np.cross(T[:, 1] - T[:, 0], T[:, 2] - T[:, 0])
                k = int(np.argmax(np.linalg.norm(cr, axis=1)))
                facing = cr[k] / (np.linalg.norm(cr[k]) or 1.0)
                plates[frame].append((T, float(m.group(1)), rest.split('__')[-1], facing))
        elif kind == 'era':
            era.append(np.array(sorted({tuple(p) for p in P.tolist()})))
    return {'plates': plates, 'era': era}


def inside_convex(point, pts):
    """True when point lies inside the convex hull of pts (a box corner set)."""
    try:
        from scipy.spatial import Delaunay
        return Delaunay(pts).find_simplex(point) >= 0
    except Exception:
        return False


def ray_hits(origin, direction, tris):
    """Distances along the ray to every triangle it crosses (Moller-Trumbore, both sides)."""
    v0, v1, v2 = tris[:, 0], tris[:, 1], tris[:, 2]
    e1, e2 = v1 - v0, v2 - v0
    p = np.cross(direction, e2)
    det = (e1 * p).sum(1)
    ok = np.abs(det) > 1e-12
    inv = np.where(ok, 1.0 / np.where(ok, det, 1.0), 0.0)
    s = origin - v0
    u = (s * p).sum(1) * inv
    q = np.cross(s, e1)
    v = (direction * q).sum(1) * inv
    t = (e2 * q).sum(1) * inv
    hit = ok & (u >= -1e-9) & (v >= -1e-9) & (u + v <= 1 + 1e-9) & (t > 0)
    return t[hit]


def point_tri_distance(p, tris):
    """Distance from p to the nearest triangle vertex/centroid (coarse, for the no-hit fallback)."""
    c = tris.mean(1)
    return min(np.linalg.norm(tris.reshape(-1, 3) - p, axis=1).min(), np.linalg.norm(c - p, axis=1).min())


def thickness_from_boxes(template, frame, samples, n):
    """mm of the box plate a shot along -n meets at the sample points (median over the samples), or of the nearest
    plate. Plates facing the same way as the face (|cos| >= 0.6) win over others on the ray: a thin floor or roof
    piece crossing the ray in front of the glacis must not lend the glacis its thickness."""
    sets = template['plates'].get(frame) or []
    if not sets and frame == 'barrel':
        sets = template['plates'].get('turret') or []
    if not sets:
        return None, None
    found = []
    for s in samples:
        origin = s + n * 40.0
        aligned, anyhit = None, None
        for tris, mm, name, facing in sets:
            ts = ray_hits(origin, -n, tris)
            if not len(ts):
                continue
            t = ts.min()
            if t > 40.0 + 24.0:
                continue
            if anyhit is None or t < anyhit[0]:
                anyhit = (t, mm, name)
            if abs(float(facing @ n)) >= 0.6 and (aligned is None or t < aligned[0]):
                aligned = (t, mm, name)
        best = aligned or anyhit
        if best:
            found.append(best)
    if found:
        mms = sorted(f[1] for f in found)
        mm = mms[len(mms) // 2]
        return mm, next(f[2] for f in found if f[1] == mm)
    c = samples[0]
    cand = [(point_tri_distance(c, tris), mm, name) for tris, mm, name, facing in sets if abs(float(facing @ n)) >= 0.6]
    if not cand:
        cand = [(point_tri_distance(c, tris), mm, name) for tris, mm, name, facing in sets]
    dist, mm, name = min(cand)
    return mm, name + '~'


# ---------------------------------------------------------------- structure

def keep_component(pts, era, frame='hull'):
    ext = pts.max(0) - pts.min(0)
    if ext.min() < ROD_THICK and ext.max() > ROD_LONG:
        return False
    e = np.sort(ext)
    # tubes and rails: a gun tube, launch rail or pipe is much longer than it is wide. The hull test is stricter
    # because a hull may be built from long thin side panels.
    if e[2] > 12.0 and (e[2] > 4.0 * e[1] if frame != 'hull' else (e[1] < 3.0 and e[2] > 6.0 * e[1])):
        return False
    if np.prod(np.maximum(ext, 0.5)) < SPECK:
        return False
    if era:
        c = pts.mean(0)
        for box in era:
            lo, hi = box.min(0), box.max(0)
            if np.all(c >= lo - 0.01) and np.all(c <= hi + 0.01) and inside_convex(c, box):
                return False
    return True


def drop_roof_fittings(comps):
    """Leaves out small parts that sit on top of the frame's roof or rise well above it (cupolas, sights,
    periscopes, hatch rings, boxes, launcher posts, masts): they would lift the convex hull's roof into a peak. The roof is the top of the parts with a large
    footprint (at least 10% of the frame's plan area)."""
    if len(comps) < 3:
        return list(range(len(comps)))
    allp = np.concatenate(comps)
    plan = max(np.ptp(allp[:, 0]) * np.ptp(allp[:, 2]), 1.0)
    fp = [np.ptp(c[:, 0]) * np.ptp(c[:, 2]) for c in comps]
    big = [c for c, a in zip(comps, fp) if a >= 0.10 * plan]
    if not big:
        return list(range(len(comps)))
    roof = max(c[:, 1].max() for c in big)
    # small parts resting on the roof, or rising well above it (a launcher post, a mast, a cupola on its ring)
    return [i for i, (c, a) in enumerate(zip(comps, fp))
            if not (a < 0.10 * plan and (c[:, 1].min() >= roof - 1.5 or c[:, 1].max() > roof + 3.0))]


def running_gear(bones, running):
    """(x inner left, x inner right, top y) of the wheels/tracks, or None."""
    pts = []
    for name, b in bones.items():
        if running.get(name) and not re.match(r'^(broken|crude)', name, re.I):
            for c, _ in bone_points(b):
                pts.append(c)
    if not pts:
        return None
    P = np.concatenate(pts)
    left, right = P[P[:, 0] > 0.5], P[P[:, 0] < -0.5]
    if len(left) < 8 or len(right) < 8:
        return None
    return (np.percentile(left[:, 0], 1.0), np.percentile(right[:, 0], 99.0),
            np.percentile(P[:, 1], 99.0))


def clipped_points(poly_groups, z0, z1, y_min=None):
    """Vertices of the model polygons clipped to z0 <= z <= z1 (and y >= y_min), cut points included."""
    out = []
    for group in poly_groups:
        for poly in group:
            q = B.clip(poly, np.array([0.0, 0.0, 1.0]), z1)
            if q is not None:
                q = B.clip(q, np.array([0.0, 0.0, -1.0]), -z0)
            if q is not None and y_min is not None:
                q = B.clip(q, np.array([0.0, -1.0, 0.0]), -y_min)
            if q is not None and x_range is not None and not clamp:
                q = B.clip(q, np.array([1.0, 0.0, 0.0]), x_range[1])
                if q is not None:
                    q = B.clip(q, np.array([-1.0, 0.0, 0.0]), -x_range[0])
            if q is not None:
                out.append(q)
    return np.concatenate(out) if out else np.zeros((0, 3))


def engine_type(vid):
    path = os.path.join(B.DATA if hasattr(B, 'DATA') else '', 'sbw', 'vehicles', f'{vid}.json')
    if not os.path.exists(path):
        path = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack',
                            'sbw', 'vehicles', f'{vid}.json')
    m = re.search(r'"EngineType": *"(\w+)"', open(path).read()) if os.path.exists(path) else None
    return m.group(1) if m else None


def clip_polys(poly_groups, y_min=None, y_max=None, x_range=None, clamp=False, as_polys=False):
    """Vertices of the model polygons clipped to y_min <= y <= y_max (cut points included); with x_range and clamp,
    x is pulled in to the range (the part of the hull between the tracks)."""
    out = []
    for group in poly_groups:
        for poly in group:
            q = poly
            if q is not None and y_max is not None:
                q = B.clip(q, np.array([0.0, 1.0, 0.0]), y_max)
            if q is not None and y_min is not None:
                q = B.clip(q, np.array([0.0, -1.0, 0.0]), -y_min)
            if q is not None and x_range is not None and not clamp:
                q = B.clip(q, np.array([1.0, 0.0, 0.0]), x_range[1])
                if q is not None:
                    q = B.clip(q, np.array([-1.0, 0.0, 0.0]), -x_range[0])
            if q is not None:
                out.append(q)
    if as_polys:
        if x_range is not None and clamp:
            for q in out:
                q[:, 0] = np.clip(q[:, 0], x_range[0], x_range[1])
        return out
    if not out:
        return np.zeros((0, 3))
    P = np.concatenate(out)
    if x_range is not None and clamp:
        P[:, 0] = np.clip(P[:, 0], x_range[0], x_range[1])
    return P


def snap(pts):
    return np.unique(np.round(np.asarray(pts, float) / ROUND) * ROUND, axis=0)


def aspect(n, frame):
    # a hull glacis is steep (the T-72's upper plate is 68 deg from vertical, its normal mostly up): any hull face
    # turned forward by more than ~7 deg is front armor, not roof
    if frame == 'hull' and n[2] < -0.12 and abs(n[1]) < 0.995:
        return 'front'
    if frame == 'hull' and n[2] > 0.25 and abs(n[1]) < 0.97:
        return 'rear'
    if n[1] > 0.7:
        return 'roof'
    if n[1] < -0.7:
        return 'belly' if frame == 'hull' else 'floor'
    if n[2] < -0.5:
        return 'front'
    if n[2] > 0.5:
        return 'rear'
    return 'side'


def default_mm(vid, frame, asp):
    d = DEFAULTS[vid]
    if frame == 'hull':
        return d.get(asp, d['side'])
    if asp == 'front' and f'turret_front' in d:
        return d['turret_front']
    return d.get('turret', d['side'])


def outer_samples(vol):
    """Centroid of the slab's outer face and points half-way to its vertices."""
    n = vol['n']
    tris = [np.array(t) for t in vol['faces']]
    outer = [t for t in tris if np.dot(np.cross(t[1] - t[0], t[2] - t[0]), n) > 0 and
             abs(np.dot(np.cross(t[1] - t[0], t[2] - t[0]) / (np.linalg.norm(np.cross(t[1] - t[0], t[2] - t[0])) or 1), n)) > 0.999]
    if not outer:
        return [vol['centroid']]
    pts = np.concatenate(outer)
    top = pts[np.argmax(pts @ n)] @ n
    pts = pts[np.abs(pts @ n - top) < 1e-3]
    c = pts.mean(0)
    uniq = np.unique(np.round(pts, 4), axis=0)
    return [c] + [c + 0.5 * (p - c) for p in uniq[:8]]


MIRROR = np.array([-1.0, 1.0, 1.0])


def symmetric_thickness(template, frame, samples, n):
    """thickness_from_boxes for the face and for its mirror image across the centre line; the vehicles are
    symmetric, so the two sides take the same value: a plate found by ray wins over a nearest-plate fallback, and
    between two of a kind the thicker (a box profile sometimes mislabels one cheek, e.g. the Challenger 2's left
    turret cheek as a 60 mm rear plate). A differing pair is reported in the source as 'a|b'."""
    a = thickness_from_boxes(template, frame, samples, n)
    b = thickness_from_boxes(template, frame, [s * MIRROR for s in samples], n * MIRROR)
    if a[0] is None or b[0] is None:
        return a if a[0] is not None else b
    if a[0] == b[0]:
        return a
    rank = lambda r: (not r[1].endswith('~'), r[0])
    best = max(a, b, key=rank)
    return best[0], f'{best[1]}|{a[0]:g}/{b[0]:g}'


# Simplified solids (owner, 2026-09-28: "much simpler, well-encompassing"): at most 26 planes per solid, fitted to the
# model's largest hull faces (fitted_directions). DOP_DIRECTIONS (the fixed 26-DOP) is kept for comparison only; it
# was 7-40% larger than the model.
DOP_DIRECTIONS = np.array([d for d in
                           [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)] +
                           [(a, b, 0) for a in (1, -1) for b in (1, -1)] +
                           [(a, 0, b) for a in (1, -1) for b in (1, -1)] +
                           [(0, a, b) for a in (1, -1) for b in (1, -1)] +
                           [(a, b, c) for a in (1, -1) for b in (1, -1) for c in (1, -1)]], float)
DOP_DIRECTIONS /= np.linalg.norm(DOP_DIRECTIONS, axis=1)[:, None]

# The rifle-calibre floor is not baked into the plates: the armor profile's "min_armor_mm" (tools/armor_mesh/
# armor_floor.py, 16 mm on every Tank/APC) is applied at runtime under box and mesh plates alike, so a car (the UAZ)
# keeps its sheet steel.
# No armor on the launcher itself: the 9P149's arm is a weapon module instead (armor profile "launcherreload").
NO_WEAPON_ARMOR = {'9p149_shturm', '9p148'}


def fitted_directions(points, limit=26, min_angle=14.0):
    """Face directions for a simplified solid: the normals of the convex hull's largest facets, most area first,
    skipping any within min_angle of one already taken, then the six box axes where nothing is close. The planes
    lie on the model's real big faces (a glacis, a turret cheek), so the solid stays tight with few faces."""
    from scipy.spatial import ConvexHull
    P = np.asarray(points, float)
    hull = ConvexHull(P)
    tri = P[hull.simplices]
    area = np.linalg.norm(np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0]), axis=1) / 2
    normals = hull.equations[:, :3]
    # area per direction: facets of one planar face share a normal
    order = np.argsort(-area)
    cos_min = math.cos(math.radians(min_angle))
    chosen = []
    for k in order:
        n = normals[k]
        if all(float(n @ c) < cos_min for c in chosen):
            chosen.append(n)
            if len(chosen) >= limit - 6:
                break
    for axis in list(np.eye(3)) + list(-np.eye(3)):
        if all(float(axis @ c) < cos_min for c in chosen):
            chosen.append(axis)
    return np.array(chosen)


def dop_vertices(points):
    """Vertices of the simplified solid: the intersection of the supporting halfspaces along fitted_directions."""
    from scipy.spatial import HalfspaceIntersection
    P = np.asarray(points, float)
    dirs = fitted_directions(P)
    off = (P @ dirs.T).max(0)
    hs = np.c_[dirs, -off]
    hi = HalfspaceIntersection(hs, P.mean(0))
    # not snapped to the model grid: a snapped vertex would bend an oblique face into several plates
    return np.unique(np.round(hi.intersections, 5), axis=0)


# Axis a shot along each aspect travels against (geo frame: front is -z).
ASPECT_AXIS = {'front': np.array([0, 0, -1.0]), 'rear': np.array([0, 0, 1.0]), 'roof': np.array([0, 1.0, 0]),
               'belly': np.array([0, -1.0, 0]), 'floor': np.array([0, -1.0, 0])}
MIN_COS = 0.35  # a box plate counts for an aspect when it faces it within ~70 deg


def aspect_axis(asp, n):
    if asp == 'side':
        return np.array([1.0 if n[0] >= 0 else -1.0, 0, 0])
    return ASPECT_AXIS[asp]


def aspect_thickness(template, frame):
    """{aspect: line-of-sight mm} from the vehicle's own box plates of this frame (the barrel frame falls back to
    the turret). A box plate's line-of-sight thickness along an aspect's axis is its mm over the cosine between its
    facing and that axis, so a sloped glacis keeps the protection it had even though the simplified solid's face is
    sloped differently. The front is the largest line-of-sight value (a shot at composite cheeks or the glacis meets
    the full value); every other aspect is the median weighted by projected area. None when there are no plates."""
    sets = template['plates'].get(frame) if template else None
    if not sets and frame == 'barrel' and template:
        sets = template['plates'].get('turret')
    if not sets:
        return None
    by = collections.defaultdict(list)
    centre = np.mean([tris.reshape(-1, 3).mean(0) for tris, _, _, _ in sets], axis=0)
    for tris, mm, name, facing in sets:
        cr = np.cross(tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0])
        area = float(np.linalg.norm(cr, axis=1).max()) / 2
        # a slab's facing is either of its large faces: take the one pointing away from the frame's centre
        outward = facing if (tris.reshape(-1, 3).mean(0) - centre) @ facing >= 0 else -facing
        for asp in ('front', 'rear', 'roof', 'side', 'belly' if frame == 'hull' else 'floor'):
            c = float(outward @ aspect_axis(asp, outward))
            if c >= MIN_COS:
                by[asp].append((mm / c, area * c))
                if asp == 'front' and frame == 'hull':
                    # upper front plate faces up and forward, the lower one down and forward
                    by['ufp' if outward[1] >= 0 else 'lfp'].append((mm / c, area * c))
    out = {}
    for asp, rows in by.items():
        if asp in ('front', 'ufp', 'lfp'):
            out[asp] = max(los for los, _ in rows)
        else:
            rows.sort()
            total = sum(a for _, a in rows)
            acc = 0.0
            for los, a in rows:
                acc += a
                if acc >= total / 2:
                    out[asp] = los
                    break
    return out


# Upper front plate, line of sight (mm RHA vs kinetic rounds, rounded public / War Thunder-style estimates):
# composite glacis of the MBTs (owner, 2026-09-28: "the upper front plate is typically very strong composite").
UFP_LOS = {
    't64b_obr1976': 330, 't72a': 335, 't72b': 420, 't72b3': 480, 't72b3_ubh_cope': 480, 't80b_obr1976': 335,
    't80u_obr1985': 470, 't90a': 500, 't90m': 520, 't14_armata': 600, 'leopard_2a4': 400, 'leo2a6': 420,
    'challenger_2': 500, 'leclerc_s1': 470, 'type_90': 450, 'k2a1_black_panther': 550, 'vt_4a1': 540,
    'ztz99a': 560,
}
# Lower front plate of the same MBTs: plain steel, 110 mm (owner, 2026-09-28).
LFP_MM = 110.0
# MBT hull side 80 mm and the rear plate behind the engine 40 mm (owner, 2026-09-28).
MBT_SIDE_MM = 80.0
MBT_REAR_MM = 40.0


def front_part(n, centre, mid_y):
    """'ufp' / 'lfp' for a hull front face (by its slope, or its height when it is vertical)."""
    if n[1] > 0.12:
        return 'ufp'
    if n[1] < -0.12:
        return 'lfp'
    return 'ufp' if centre[1] >= mid_y else 'lfp'


def composite_depth(vertices, frame, mid_y, vid, barrel_pivot_z=None):
    """z (geo px, front is -z) behind which a side face is no longer backed by the frontal composite: the rear edge
    of the upper front plate on an MBT hull, the trunnion (+2 px) on a turret. None when not an MBT."""
    if vid not in UFP_LOS:
        return None
    if frame == 'hull':
        ufp = [poly for n, d, poly in B.hull_faces(vertices)
               if aspect(n, 'hull') == 'front' and front_part(n, poly.mean(0), mid_y) == 'ufp']
        if not ufp:
            return None
        # the composite block is at most a quarter of the hull deep, and only as low as the glacis reaches
        front, length = float(vertices[:, 2].min()), float(np.ptp(vertices[:, 2]))
        z = min(max(float(q[:, 2].max()) for q in ufp), front + 0.25 * length)
        return z, min(float(q[:, 1].min()) for q in ufp)
    if frame == 'turret' and barrel_pivot_z is not None:
        return float(barrel_pivot_z) + 2.0, None
    return None


def plates_for_solid(vertices, frame, tag, vid, template, mid_y=None, side_cut_z=None):
    """Plates on the faces of a solid. With side_cut_z, the side faces are split there: the part ahead of it is the
    side of the frontal composite and is as thick seen from the side as from the front (owner, 2026-09-28)."""
    cut_y = None
    if side_cut_z is not None:
        side_cut_z, cut_y = side_cut_z
    planes = [] if side_cut_z is None else [[0.0, 0.0, 1.0, side_cut_z]]
    if cut_y is not None:
        planes.append([0.0, 1.0, 0.0, cut_y])
    cuts = [{'when': {'anx': (0.7, 1.01)}, 'planes': planes}] if planes else []
    vols = B.plates_from(vertices, [{'name': 'plate', 'mm': 1.0}], cuts, frame, tag)
    table = aspect_thickness(template, frame)
    for v in vols:
        n = np.asarray(v['n'])
        asp = aspect(n, frame)
        part = asp
        if frame == 'hull' and asp == 'front':
            part = front_part(n, v['centroid'], mid_y if mid_y is not None else v['centroid'][1])
        if asp == 'side' and side_cut_z is not None and v['centroid'][2] < side_cut_z and \
                (cut_y is None or v['centroid'][1] > cut_y):
            part = 'cheek' if frame != 'hull' else 'ufp_side'
        if part == 'ufp_side':
            v['name'] = f'{tag}_{part}'
            v['mm'], v['source'] = float(UFP_LOS[vid]), 'ufp_table'
            continue
        if part == 'cheek' and table and 'front' in table:
            v['name'] = f'{tag}_{part}'
            v['mm'], v['source'] = float(round(table['front'], 1)), 'profile'
            continue
        v['name'] = f'{tag}_{part}'
        cos = max(MIN_COS, abs(float(n @ aspect_axis(asp, n))))
        if part == 'ufp' and vid in UFP_LOS:
            mm, src = UFP_LOS[vid] * cos, 'ufp_table'
        elif part == 'lfp' and vid in UFP_LOS:
            mm, src = LFP_MM, 'lfp_table'
        elif frame == 'hull' and part == 'side' and vid in UFP_LOS:
            mm, src = MBT_SIDE_MM, 'mbt_side'
        elif frame == 'hull' and part == 'rear' and vid in UFP_LOS:
            mm, src = MBT_REAR_MM, 'mbt_rear'
        elif table and part in table:
            mm, src = table[part] * cos, 'profile'
        elif table and asp in table:
            mm, src = table[asp] * cos, 'profile'
        elif table:
            mm, src = max(table.values()) if asp == 'front' else float(np.median(list(table.values()))), 'profile~'
        else:
            mm, src = (default_mm(vid, frame, asp) if vid in DEFAULTS else 10.0), 'default'
        v['mm'] = float(round(mm, 1))
        v['source'] = src
    return vols


# Ammunition (owner, 2026-09-28: only where it really is - an ammo hit is an instant kill). A carousel autoloader is
# a short thick cylinder on the hull floor under the turret; otherwise the hull racks sit low behind the frontal
# composite, beside the driver. Vehicles not listed keep their profile's ammo boxes (or have none).
AMMO = {
    't64b_obr1976': 'carousel', 't72b3': 'carousel', 't72b3_ubh_cope': 'carousel', 't80b_obr1976': 'carousel',
    't80u_obr1985': 'carousel', 'ztz99a': 'carousel', 'vt_4a1': 'carousel', 't14_armata': 'carousel',
    'bmp3m_elite': 'carousel',
    'leopard_2a4': ['front_left'], 'challenger_2': ['front_left', 'front_right'], 'leclerc_s1': ['front_right'],
    'k2a1_black_panther': ['front_right'], 'type_90': ['front_right'],
}


def box_in_solid(lo, hi, V):
    """The part of the box [lo, hi] inside the convex solid V, as outward polygons (None when empty)."""
    from scipy.spatial import HalfspaceIntersection
    hs = [[1, 0, 0, -hi[0]], [-1, 0, 0, lo[0]], [0, 1, 0, -hi[1]], [0, -1, 0, lo[1]], [0, 0, 1, -hi[2]],
          [0, 0, -1, lo[2]]]
    for n, d, _ in B.hull_faces(V):
        hs.append([n[0], n[1], n[2], -d])
    hs = np.array(hs, float)
    from scipy.optimize import linprog
    A_, b_ = hs[:, :3], -hs[:, 3]
    res = linprog([0, 0, 0, -1], A_ub=np.c_[A_, np.linalg.norm(A_, axis=1)], b_ub=b_,
                  bounds=[(None, None)] * 3 + [(0, None)])
    if not res.success or res.x[3] < 0.3:
        return None
    pts = HalfspaceIntersection(hs, res.x[:3]).intersections
    return B.solid_polys(np.unique(np.round(pts, 5), axis=0))


def internals(vid, hull_V, bones, centre_x, profile_engine_front):
    """Engine and ammunition volumes shaped to the hull solid."""
    out = []
    lo, hi = hull_V.min(0), hull_V.max(0)
    L, H = hi[2] - lo[2], hi[1] - lo[1]
    half = (hi[0] - lo[0]) / 2
    belly = lo[1]
    if profile_engine_front is not None:
        if profile_engine_front:
            # front engine beside the driver (M109, PzH 2000, Marder, ZTL-11): the right front of the hull
            e_lo = [centre_x - half + 1.5, belly + 1.5, lo[2] + 0.08 * L]
            e_hi = [centre_x - 1.0, hi[1] - 2.0, lo[2] + 0.40 * L]
        else:
            e_lo = [centre_x - half + 2.0, belly + 1.5, hi[2] - 0.30 * L]
            e_hi = [centre_x + half - 2.0, hi[1] - 2.0, hi[2] - 1.5]
        faces = box_in_solid(e_lo, e_hi, hull_V)
        if faces:
            out.append({'frame': 'hull', 'kind': 'engine', 'name': 'powerpack', 'parts': [faces]})
    layout = AMMO.get(vid)
    if layout == 'carousel' and 'turret' in bones:
        piv = np.array(bones['turret'].get('pivot', [0, 0, 0]), float)
        r = 0.42 * (hi[0] - lo[0])
        y0, y1 = belly + 1.0, belly + 1.0 + max(4.0, 0.22 * H)
        ring = [[piv[0] + r * math.cos(2 * math.pi * k / 16), y, piv[2] + r * math.sin(2 * math.pi * k / 16)]
                for k in range(16) for y in (y0, y1)]
        out.append({'frame': 'hull', 'kind': 'ammo', 'name': 'carousel', 'param': '10hp',
                    'parts': [B.solid_polys(np.array(ring))]})
    elif isinstance(layout, list):
        for side in layout:
            # a rack beside the driver: the outer part of that side, low, behind the frontal composite
            x_lo, x_hi = ((centre_x + 0.4 * half, centre_x + half - 1.5) if side == 'front_left'
                          else (centre_x - half + 1.5, centre_x - 0.4 * half))
            faces = box_in_solid([x_lo, belly + 1.0, lo[2] + 0.14 * L], [x_hi, belly + 0.45 * H, lo[2] + 0.32 * L],
                                 hull_V)
            if faces:
                out.append({'frame': 'hull', 'kind': 'ammo', 'name': f'rack_{side}', 'param': '10hp',
                            'parts': [faces]})
    return out


# Family commonality (owner, 2026-09-28): members take the reference's solids - the hull fitted to the member's own
# hull extents, the turret and mantlet moved with the turret pivot - so a family reads the same everywhere.
HULL_FROM = {'t80u_obr1985': 't80b_obr1976'}
TURRET_FROM = {'t72b3': 't72a', 't72b3_ubh_cope': 't72a'}


def family_made(vid, made, bones):
    """Replace the hull / turret / mantlet solids [(frame, tag, V)] with the family reference's (HULL_FROM,
    TURRET_FROM)."""
    refs = {HULL_FROM.get(vid), TURRET_FROM.get(vid)} - {None}
    for ref in refs:
        _, rinfo = build(ref, verbose=False)
        theirs_all = rinfo['made']
        if HULL_FROM.get(vid) == ref:
            own = np.vstack([V for f, _, V in made if f == 'hull'])
            theirs = np.vstack([V for f, _, V in theirs_all if f == 'hull'])
            lo, hi, rlo, rhi = own.min(0), own.max(0), theirs.min(0), theirs.max(0)
            scale = (hi - lo) / np.maximum(rhi - rlo, 1e-6)
            made = [m for m in made if m[0] != 'hull'] + \
                   [(f, t, lo + (V - rlo) * scale) for f, t, V in theirs_all if f == 'hull']
        if TURRET_FROM.get(vid) == ref:
            rb = load_model(ref)
            delta = np.array(bones['turret']['pivot'], float) - np.array(rb['turret']['pivot'], float)
            made = [m for m in made if m[0] not in ('turret', 'barrel')] + \
                   [(f, t, V + delta) for f, t, V in theirs_all if f in ('turret', 'barrel')]
    return made


def build(vid, verbose=True):
    bones = load_model(vid)
    frame, running = classify_bones(bones)
    template = load_template(vid)
    era = template['era'] if template else []
    comps = collections.defaultdict(list)
    dropped = collections.Counter()
    for name, b in bones.items():
        f = frame.get(name)
        if f is None:
            continue
        for c, pl in bone_points(b):
            screen = any(sf == f and c[:, 1].min() >= lo and c[:, 1].max() > hi for sf, lo, hi in SCREENS.get(vid, []))
            if not screen and keep_component(c, era, f):
                comps[f].append((c, pl, sum(B.polygon_area(q) for q in pl)))
            else:
                dropped[f] += 1
    # the underlying body: the largest shell plus big long panels; the rest is greeble and does not shape the armor
    for f in ('hull', 'turret'):
        keep = S.structure(comps[f])
        dropped[f + '_greeble'] += len(comps[f]) - len(keep)
        comps[f] = [comps[f][i] for i in keep]
    volumes = []
    info = {'dropped': dict(dropped)}
    hull_polys = [pl for _, pl, _ in comps['hull']]
    body = np.concatenate([c for c, _, _ in comps['hull']])
    centre_x = round(float((body[:, 0].max() + body[:, 0].min()) / 2), 3)
    gear = running_gear(bones, running)
    mid_y = float((body[:, 1].max() + body[:, 1].min()) / 2)
    solids = []
    tracked = engine_type(vid) == 'Track'
    info['tracked'] = tracked
    if gear:
        top = float(gear[2])
        half = min(gear[0] - centre_x, centre_x - gear[1]) - 0.2
        lower = clip_polys(hull_polys, y_max=top, x_range=(centre_x - half, centre_x + half), clamp=True,
                           as_polys=True)
        upper = clip_polys(hull_polys, y_min=top, as_polys=True)
        upper_pts = np.concatenate(upper) if upper else np.zeros((0, 3))
        info['gear'] = [round(float(gear[0]), 2), round(float(gear[1]), 2), round(top, 2)]
        if tracked:
            # the cover over the tracks (fenders, skirts, mudguards, bins) and the running gear are no armor: a
            # tracked hull is the body between the tracks, belly to roof (owner, 2026-09-28). Wheeled vehicles keep
            # the body over their wheels.
            # clipped at the inner faces of the tracks (not squeezed in): the fenders' ends must not shape the nose
            solids += [('hull', 'hull', clip_polys(hull_polys, x_range=(centre_x - half, centre_x + half),
                                                   as_polys=True))]
        elif len(upper_pts) >= 4 and np.ptp(upper_pts[:, 0]) > 2 * half + 2.0 and np.ptp(upper_pts[:, 1]) > 1.0:
            solids += [('hull', 'hull', lower), ('hull', 'sponson', upper)]
            info['sponson'] = True
        else:
            solids += [('hull', 'hull', [q for g in hull_polys for q in g])]
    else:
        solids += [('hull', 'hull', [q for g in hull_polys for q in g])]
    hull_top = None
    made = []
    for f, tag, polys_ in solids:
        V = S.solid_from_polys(polys_, centre_x) if len(polys_) >= 2 else None
        if V is None:
            continue
        made.append(('hull', tag, V))
        info.setdefault('solids', []).append(('hull', V))
        hull_top = V if hull_top is None else np.vstack([hull_top, V])

    if vid in NO_WEAPON_ARMOR:
        comps['turret'], comps['barrel'] = [], []
    turret_front_z = None
    # turret: the underlying shell, carried down to the hull roof so no gap is left under it
    if comps['turret'] and 'turret' in bones:
        piv = np.array(bones['turret'].get('pivot', [0, 0, 0]), float)
        tpts = np.concatenate([c for c, _, _ in comps['turret']])
        tpolys = [q for _, pl, _ in comps['turret'] for q in pl]
        if hull_top is not None:
            near = hull_top[(np.abs(hull_top[:, 0] - piv[0]) < 14) & (np.abs(hull_top[:, 2] - piv[2]) < 14)]
            roof = float(near[:, 1].max()) if len(near) else float(hull_top[:, 1].max())
            floor = float(tpts[:, 1].min())
            base = tpts[tpts[:, 1] < floor + 1.5]
            if floor > roof + 0.3 and len(base) >= 3:
                ring = piv + 0.9 * (base - piv) * np.array([1, 0, 1])
                ring[:, 1] = roof - 0.5
                tpolys.append(ring)
                info['ring'] = [round(roof, 2), round(floor, 2)]
        V = S.solid_from_polys(tpolys, float(piv[0]))
        if V is not None:
            made.append(('turret', 'turret', V))
            info.setdefault('solids', []).append(('turret', V))
            turret_front_z = float(V[:, 2].min())

    # mantlet: the barrel-frame parts around the trunnion and ahead of it (the breech behind it is ignored), a box
    if 'barell' in bones and comps['barrel']:
        piv = np.array(bones['barell'].get('pivot', [0, 0, 0]), float)
        near = [c for c, _, _ in comps['barrel']
                if np.ptp(c, axis=0).max() < 28.0 and np.linalg.norm((c.mean(0) - piv) * [1, 1, 0.6]) < 16.0
                and c[:, 2].mean() <= piv[2] + 1.0]
        if near:
            m_pts = np.concatenate(near)
            if turret_front_z is not None:
                # the mantlet stands at most 4 px proud of the turret front; the gun tube ahead of it is no armor
                m_pts[:, 2] = np.maximum(m_pts[:, 2], turret_front_z - 4.0)
            if np.ptp(m_pts, axis=0).min() > 1.0:
                V = S.box(m_pts, float(piv[0]))
                made.append(('barrel', 'mantlet', V))
                info.setdefault('solids', []).append(('barrel', V))
                info['mantlet_parts'] = len(near)

    made = family_made(vid, made, bones)
    info['solids'] = [(f, V) for f, _, V in made]
    info['made'] = made
    bpz = bones['barell'].get('pivot', [0, 0, 0])[2] if 'barell' in bones else None
    for f, tag, V in made:
        if f == 'hull':
            volumes += plates_for_solid(V, 'hull', tag, vid, template, mid_y,
                                        side_cut_z=composite_depth(V, 'hull', mid_y, vid))
        elif f == 'turret':
            volumes += plates_for_solid(V, 'turret', tag, vid, template,
                                        side_cut_z=composite_depth(V, 'turret', None, vid, bpz))
        else:
            volumes += plates_for_solid(V, f, tag, vid, template)
    if hull_top is not None:
        prof = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack',
                            'armor', f'{vid}.json')
        engines = json.load(open(prof)).get('engines', []) if os.path.exists(prof) else []
        # profile frame: front is -z
        front = None if not engines else float(np.mean([e['center'][2] for e in engines])) < 0
        main_hull = next(V for f, V in info['solids'] if f == 'hull')
        volumes += internals(vid, main_hull, bones, centre_x, front)
    info['asym'] = sum(1 for v in volumes if v.get('asym'))
    count = collections.Counter(v['name'] for v in volumes)
    idx = collections.Counter()
    for v in volumes:
        if count[v['name']] > 1 and v.get('part') != 'ring':
            idx[v['name']] += 1
            v['name'] = f"{v['name']}_{idx[v['name']]:02d}"
    return volumes, info


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument('ids', nargs='*')
    ap.add_argument('--targets', action='store_true')
    ap.add_argument('--report', action='store_true')
    ap.add_argument('--out-dir')
    a = ap.parse_args(argv)
    ids = [v for v in AUTO_TARGETS if v not in HAND_TUNED] if a.targets else a.ids
    for vid in ids:
        B.SKIPPED.clear()
        volumes, info = build(vid)
        kinds = collections.Counter(v['frame'] for v in volumes)
        src = collections.Counter('module' if 'source' not in v else 'default' if v['source'] == 'default' else 'nearest' if str(v['source']).split('|')[0].endswith('~')
                                  else 'ring' if v['source'] == 'ring' else 'ray' for v in volumes)
        shown = {k: v for k, v in info.items() if k not in ('solids', 'made')}
        print(f"{vid}: {len(volumes)} plates {dict(kinds)} thickness {dict(src)} {shown}")
        if a.report:
            for v in volumes:
                print(f"   {v['frame']:7s} {B.bone_name(v):44s} n={np.round(v['n'], 2).tolist() if 'n' in v else '-'} from {v.get('source', v['kind'])}")
            continue
        path = os.path.join(a.out_dir or B.OUT, f'{vid}.geo.json')
        B.write(vid, volumes, path)


if __name__ == '__main__':
    main(sys.argv[1:])
