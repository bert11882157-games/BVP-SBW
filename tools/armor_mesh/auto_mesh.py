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

TEMPLATES = os.path.join(HERE, 'templates')

# Every armored ground vehicle except the ones with hand-authored box armor the owner keeps (BMP-2/2M, BMP-1, T-90A
# (hand-measured mesh), all Abrams, T-72B, M48, M1128, T-62A, BTR-80A, BTR-60, the Toyotas, ZBD-09, Marder 1A2,
# CV9040C, M2 Bradley, BMPT) and the emplacements (tripods, ZU-23).
AUTO_TARGETS = [
    '9k22_tunguska', '9p148', '9p149_shturm', 'bmd_1', 'bmp3m_elite', 'btr_90', 'btr_zd', 'challenger_2',
    'gaz_3937_vodnik_aa', 'gepard', 'k2a1_black_panther', 'lav25', 'leclerc_s1', 'leo2a6', 'leopard_2a4',
    'm109a7_paladin', 'm551a1', 'm60a1', 'marder_1a1', 'marder_1a5', 'pzh_2000', 'qn_506model', 't14_armata',
    't55a_2_0', 't64b_obr1976', 't72a', 't72b3', 't72b3_ubh_cope', 't80b_obr1976', 't80u_obr1985', 't90m',
    'tunguska', 'type_90', 'vbci', 'vt_4a1', 'zsl_92', 'zsu23_4', 'ztl_09', 'ztz99a', 'uaz_469_spg9',
]

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
            if q is not None:
                out.append(q)
    return np.concatenate(out) if out else np.zeros((0, 3))


def snap(pts):
    return np.unique(np.round(np.asarray(pts, float) / ROUND) * ROUND, axis=0)


def aspect(n, frame):
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


def plates_for(points, frame, tag, vid, template):
    vols = B.plates_from(points, [{'name': 'plate', 'mm': 1.0}], [], frame, tag)
    for v in vols:
        asp = aspect(v['n'], frame)
        v['name'] = f'{tag}_{asp}'
        if template and template['plates']:
            mm, src = symmetric_thickness(template, frame, outer_samples(v), v['n'])
            if src and '|' in src:
                v['asym'] = src
            if mm is None:
                mm, src = default_mm(vid, frame, asp) if vid in DEFAULTS else 10.0, 'default'
        else:
            mm, src = (default_mm(vid, frame, asp) if vid in DEFAULTS else 10.0), 'default'
        v['mm'] = float(mm)
        v['source'] = src
    return vols


def build(vid, verbose=True):
    bones = load_model(vid)
    frame, running = classify_bones(bones)
    template = load_template(vid)
    era = template['era'] if template else []
    comps = collections.defaultdict(list)
    polys = collections.defaultdict(list)
    dropped = collections.Counter()
    for name, b in bones.items():
        f = frame.get(name)
        if f is None:
            continue
        for c, pl in bone_points(b):
            screen = any(sf == f and c[:, 1].min() >= lo and c[:, 1].max() > hi for sf, lo, hi in SCREENS.get(vid, []))
            if not screen and keep_component(c, era, f):
                comps[f].append(c)
                polys[f].append(pl)
            else:
                dropped[f] += 1
    for f in ('hull', 'turret'):
        keep = drop_roof_fittings(comps[f])
        dropped[f + '_roof'] += len(comps[f]) - len(keep)
        comps[f] = [comps[f][i] for i in keep]
        polys[f] = [polys[f][i] for i in keep]
    volumes = []
    info = {'dropped': dict(dropped)}

    # hull: a core between the running gear (every point pulled in to the inner face of the wheels/tracks) and,
    # where the hull is wider above the running gear (sponsons, fenders over the tracks), a second solid from the
    # top of the running gear up. Two solids instead of one hull so no sloped face reaches down over the tracks.
    # Both are built in lengthwise segments (the model's polygons clipped at each cut) so a raised engine deck or a
    # stepped roof keeps its step instead of being bridged by one long slope.
    all_hull = np.concatenate(comps['hull'])
    gear = running_gear(bones, running)
    zmin, zmax = all_hull[:, 2].min(), all_hull[:, 2].max()
    nseg = max(1, int(round((zmax - zmin) / SEGMENT)))
    edges = np.linspace(zmin, zmax, nseg + 1)
    top = gear[2] if gear else None
    sponson = False
    for i in range(nseg):
        z0, z1 = edges[i] - (0.0 if i == 0 else 0.01), edges[i + 1] + (0.0 if i == nseg - 1 else 0.01)
        seg = clipped_points(polys['hull'], z0, z1)
        if len(seg) < 4:
            continue
        core = seg.copy()
        if gear:
            core[:, 0] = np.clip(core[:, 0], gear[1] + 0.2, gear[0] - 0.2)
        core = snap(core)
        if len(core) >= 4 and np.ptp(core, axis=0).min() > 1.0:
            volumes += plates_for(core, 'hull', 'hull', vid, template)
            info.setdefault('solids', []).append(('hull', core))
        if gear:
            up = clipped_points(polys['hull'], z0, z1, y_min=top)
            if len(up) >= 4 and (up[:, 0].max() > gear[0] + 1.0 or up[:, 0].min() < gear[1] - 1.0):
                up = snap(up)
                if np.ptp(up, axis=0).min() > 1.0:
                    volumes += plates_for(up, 'hull', 'sponson', vid, template)
                    info.setdefault('solids', []).append(('hull', up))
                    sponson = True
    hull_pts = snap(np.concatenate([s for f, s in info.get('solids', []) if f == 'hull']))
    info['segments'] = nseg
    if gear:
        info['gear'] = [round(float(gear[0]), 2), round(float(gear[1]), 2), round(float(top), 2)]
        info['sponson'] = sponson

    # turret
    turret_pts = None
    if comps['turret']:
        turret_pts = snap(np.concatenate(comps['turret']))
        if len(turret_pts) >= 4 and np.ptp(turret_pts, axis=0).min() > 1.0:
            volumes += plates_for(turret_pts, 'turret', 'turret', vid, template)
            info.setdefault('solids', []).append(('turret', turret_pts))
        else:
            turret_pts = None

    # mantlet: barrel-frame parts around the trunnion, no gun tube
    if 'barell' in bones and comps['barrel']:
        piv = np.array(bones['barell'].get('pivot', [0, 0, 0]), float)
        near = [c for c in comps['barrel']
                if np.ptp(c, axis=0).max() < 28.0 and np.linalg.norm((c.mean(0) - piv) * [1, 1, 0.6]) < 16.0]
        if near:
            m_pts = snap(np.concatenate(near))
            if len(m_pts) >= 4 and np.ptp(m_pts, axis=0).min() > 1.0:
                volumes += plates_for(m_pts, 'barrel', 'mantlet', vid, template)
                info.setdefault('solids', []).append(('barrel', m_pts))
                info['mantlet_parts'] = len(near)

    # collar between the hull roof and the turret floor
    if turret_pts is not None and 'turret' in bones:
        piv = np.array(bones['turret'].get('pivot', [0, 0, 0]), float)
        floor = turret_pts[:, 1].min()
        near = hull_pts[(np.abs(hull_pts[:, 0] - piv[0]) < 12) & (np.abs(hull_pts[:, 2] - piv[2]) < 12)]
        roof = near[:, 1].max() if len(near) else hull_pts[:, 1].max()
        base = turret_pts[turret_pts[:, 1] < floor + 1.5]
        r_out = 0.9 * min(np.ptp(base[:, 0]), np.ptp(base[:, 2])) / 2 if len(base) >= 3 else 0.0
        if floor > roof + 0.3 and r_out > 3.0:
            ring_mm = min((v['mm'] for v in volumes if v['frame'] == 'turret' and v['name'].endswith('side')),
                          default=20.0)
            for i, part in enumerate(B.ring_segments([piv[0], 0, piv[2]], r_out - 2.0, r_out, roof - 0.5,
                                                      floor + 0.5, 16)):
                a = 2 * math.pi * (i + 0.5) / 16
                volumes.append({'frame': 'hull', 'kind': 'plate', 'name': f'turret_ring_{i:02d}', 'mm': ring_mm,
                                'faces': part, 'part': 'ring', 'n': np.array([math.cos(a), 0.0, math.sin(a)]),
                                'source': 'ring'})
            info['ring'] = [round(r_out, 2), round(roof, 2), round(floor, 2)]

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
    ids = AUTO_TARGETS if a.targets else a.ids
    for vid in ids:
        B.SKIPPED.clear()
        volumes, info = build(vid)
        kinds = collections.Counter(v['frame'] for v in volumes)
        src = collections.Counter('default' if v['source'] == 'default' else 'nearest' if str(v['source']).split('|')[0].endswith('~')
                                  else 'ring' if v['source'] == 'ring' else 'ray' for v in volumes)
        shown = {k: v for k, v in info.items() if k != 'solids'}
        print(f"{vid}: {len(volumes)} plates {dict(kinds)} thickness {dict(src)} {shown}")
        if a.report:
            for v in volumes:
                print(f"   {v['frame']:7s} {B.bone_name(v):44s} n={np.round(v['n'], 2).tolist()} from {v['source']}")
            continue
        path = os.path.join(a.out_dir or B.OUT, f'{vid}.geo.json')
        B.write(vid, volumes, path)


if __name__ == '__main__':
    main(sys.argv[1:])
