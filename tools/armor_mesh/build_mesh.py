#!/usr/bin/env python3
"""Builds a mesh armor file (armor_mesh/<id>.geo.json) from a vehicle's own visual model and a small spec.

    python3 tools/armor_mesh/build_mesh.py t90a            # writes bvp/src/main/resources/.../armor_mesh/t90a.geo.json
    python3 tools/armor_mesh/build_mesh.py t90a --report   # plate table only

The armor captures the vehicle's underlying structure, not its fittings (see docs/ARMOR_MESH.md for the format):

* **Hull**: a convex solid from a side profile (z, y) and a half width, measured on the model's hull core (the
  plates between the tracks; fenders, skirts, stowage, fuel tanks, lights and ERA are left out).
* **Turret / mantlet**: convex hulls of chosen components of the model's turret / barrel bones (the turret shell
  and the cheek blocks; sights, cupola, Shtora, smoke launchers, stowage and the gun tube are left out).
* Every face of those solids becomes its own **plate**: a slab (the face, extruded 1 px inward) named after its
  region, so any plate can be re-thicknessed, and weak spots / thick portions are separate plates. Faces are first
  split by cut planes where a region boundary falls inside one face (driver's port, sight notch, roof zones...).
* A region's thickness is either nominal (`mm`) or a line-of-sight target (`los`: [dx, dy, dz, mm]): the plate is
  then made just thick enough that a shot along (dx, dy, dz) meets `mm` of armor, whatever the face's slope.
* **Modules** are generated from the hull solid: the engine fills the rear compartment, the ammunition sits where
  the vehicle's layout puts it (autoloader carousel under the turret ring, hull racks), and the lower track runs
  become track modules so low side hits register on the tracks instead of missing.

ERA is not part of the file: the vehicle keeps its ERA boxes from armor/<id>.json (the loader replaces only the
categories a mesh file defines).
"""
import argparse
import collections
import json
import math
import os
import re
import sys

import numpy as np
from scipy.optimize import linprog
from scipy.spatial import ConvexHull, HalfspaceIntersection

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
GEO = os.path.join(REPO, 'bvp/src/generated/resources/assets/berts_vehicle_pack/custom_geo')
OUT = os.path.join(REPO, 'bvp/src/main/resources/data/berts_vehicle_pack/armor_mesh')
SPECS = os.path.join(HERE, 'specs')
SLAB_DEPTH = 1.0  # px


# ---------------------------------------------------------------- model components

def model_components(vid, bone):
    """Connected components (shared positions) of one bone's own polygons: list of point arrays."""
    g = json.load(open(os.path.join(GEO, f'{vid}.geo.json')))['minecraft:geometry'][0]
    b = {x['name']: x for x in g['bones']}[bone]
    pm = b['poly_mesh']
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
    for poly in pm['polys']:
        for v in poly:
            groups[find(v[0])].add(v[0])
    return [P[sorted(vs)] for vs in groups.values()]


def select(components, sel):
    return np.concatenate(components_in(components, sel))


def components_in(components, sel):
    """Components whose bounds lie inside sel['within'] ([min xyz], [max xyz]) and whose volume is large enough."""
    lo, hi = np.array(sel['within'][0], float), np.array(sel['within'][1], float)
    out = []
    for c in components:
        mn, mx = c.min(0), c.max(0)
        if np.all(mn >= lo - 1e-6) and np.all(mx <= hi + 1e-6) and \
                np.prod(np.maximum(mx - mn, 0.5)) >= sel.get('minVolume', 0):
            out.append(c)
    if not out:
        raise SystemExit(f'selection {sel} matched no component')
    return out


def select_each(components, sel):
    return components_in(components, sel)


# ---------------------------------------------------------------- convex solids and faces

def hull_faces(points):
    """Convex hull of the points as a list of (outward unit normal, offset, ordered polygon)."""
    points = np.unique(np.round(np.asarray(points, float), 6), axis=0)
    h = ConvexHull(points)
    planes = collections.OrderedDict()
    for simplex, eq in zip(h.simplices, h.equations):
        n = eq[:3] / np.linalg.norm(eq[:3])
        d = -eq[3] / np.linalg.norm(eq[:3])
        key = None
        for k in planes:
            if np.dot(planes[k][0], n) > 1 - 1e-6 and abs(planes[k][1] - d) < 1e-4:
                key = k
                break
        if key is None:
            key = len(planes)
            planes[key] = [n, d, set()]
        planes[key][2].update(simplex.tolist())
    faces = []
    for n, d, idx in planes.values():
        pts = points[sorted(idx)]
        poly = order_polygon(pts, n)
        if poly is not None:
            faces.append((n, d, poly))
    return faces


def order_polygon(pts, n):
    c = pts.mean(0)
    u = np.cross(n, [0, 1, 0] if abs(n[1]) < 0.9 else [1, 0, 0])
    u /= np.linalg.norm(u)
    v = np.cross(n, u)
    ang = np.arctan2((pts - c) @ v, (pts - c) @ u)
    poly = pts[np.argsort(ang)]
    # drop collinear / duplicate points
    keep = []
    for i in range(len(poly)):
        a, b, cc = poly[i - 1], poly[i], poly[(i + 1) % len(poly)]
        if np.linalg.norm(np.cross(b - a, cc - b)) > 1e-7 and np.linalg.norm(b - a) > 1e-6:
            keep.append(b)
    poly = np.array(keep)
    if len(poly) < 3:
        return None
    if np.dot(np.cross(poly[1] - poly[0], poly[2] - poly[0]), n) < 0:
        poly = poly[::-1]
    return poly


def clip(poly, pn, pd):
    """Sutherland-Hodgman: the part of the polygon with pn.p <= pd."""
    out = []
    m = len(poly)
    for i in range(m):
        a, b = poly[i], poly[(i + 1) % m]
        da, db = a @ pn - pd, b @ pn - pd
        if da <= 1e-9:
            out.append(a)
        if (da < -1e-9 < db) or (db < -1e-9 < da):
            t = da / (da - db)
            out.append(a + (b - a) * t)
    return np.array(out) if len(out) >= 3 else None


def split(poly, planes):
    pieces = [poly]
    for p in planes:
        pn, pd = np.array(p[:3], float), float(p[3])
        pn_len = np.linalg.norm(pn)
        pn, pd = pn / pn_len, pd / pn_len
        nxt = []
        for q in pieces:
            for piece in (clip(q, pn, pd), clip(q, -pn, -pd)):
                if piece is not None and polygon_area(piece) > 0.05:
                    nxt.append(piece)
        pieces = nxt
    return pieces


def clean(poly):
    """Drops duplicate and collinear points (clipping leaves them on the cut line)."""
    pts = []
    for p in poly:
        if not pts or np.linalg.norm(p - pts[-1]) > 1e-6:
            pts.append(p)
    while len(pts) > 1 and np.linalg.norm(pts[0] - pts[-1]) <= 1e-6:
        pts.pop()
    changed = True
    while changed and len(pts) >= 3:
        changed = False
        for i in range(len(pts)):
            a, b, c = pts[i - 1], pts[i], pts[(i + 1) % len(pts)]
            if np.linalg.norm(np.cross(b - a, c - b)) < 1e-7 * max(1.0, np.linalg.norm(b - a) * np.linalg.norm(c - b)):
                pts.pop(i)
                changed = True
                break
    return np.array(pts) if len(pts) >= 3 else None


def polygon_area(poly):
    s = np.zeros(3)
    for i in range(len(poly)):
        s += np.cross(poly[i], poly[(i + 1) % len(poly)])
    return np.linalg.norm(s) / 2


def slab(poly, n, depth=SLAB_DEPTH):
    """Closed prism: the face and its copy `depth` px inward. Returns list of polygons (outward winding)."""
    outer = [np.array(p) for p in poly]
    inner = [p - n * depth for p in outer]
    faces = [outer, inner[::-1]]
    m = len(outer)
    for i in range(m):
        a, b = outer[i], outer[(i + 1) % m]
        faces.append([b, a, inner[i], inner[(i + 1) % m]])
    return faces


def mitred_slab(face, planes, cuts, piece, depth=SLAB_DEPTH):
    """The part of the convex solid within `depth` of this face and nearer to it than to any other face (so
    neighbouring slabs meet on the bisector planes inside the solid and no slab side ever reaches the outer surface),
    bounded by the cut planes that separate this piece from the rest of the face. Outward polygons."""
    n, d = face
    hs = [np.r_[n, -d], np.r_[-n, d - depth]]
    for nb, db in planes:
        if np.dot(nb, n) > 1 - 1e-9 and abs(db - d) < 1e-6:
            continue
        hs.append(np.r_[nb, -db])
        m = n - nb
        hs.append(np.r_[-m, d - db])
    c = piece.mean(0)
    for cn, cd in cuts:
        side = 1.0 if c @ cn - cd >= 0 else -1.0
        hs.append(np.r_[-side * cn, side * cd])
    hs = np.array(hs)
    # coincident halfspaces (a miter or cut repeating a face plane) make the dual hull degenerate, and qhull then
    # reports spurious intersection points inside faces: keep one row per plane
    hs = hs / np.linalg.norm(hs[:, :3], axis=1)[:, None]
    uniq = []
    for row in hs:
        if not any(np.abs(row - u).max() < 1e-9 for u in uniq):
            uniq.append(row)
    hs = np.array(uniq)
    # Chebyshev centre: a strictly interior point for the intersection
    norms = np.linalg.norm(hs[:, :3], axis=1)
    res = linprog(np.r_[0, 0, 0, -1], A_ub=np.c_[hs[:, :3], norms], b_ub=-hs[:, 3], bounds=[(None, None)] * 3 + [(0, None)])
    if not res.success or res.x[3] < 1e-6:
        raise SystemExit(f'slab for face n={np.round(n, 3)} has no interior')
    pts = HalfspaceIntersection(hs, res.x[:3]).intersections
    return halfspace_solid(hs, pts)


def cluster(points, tol=1e-6):
    """Merges points closer than tol (px) into their mean; returns the distinct points rounded to the output
    precision, so every face built from them shares exactly the same written coordinates."""
    out = []
    for p in np.asarray(points, float):
        for q in out:
            if np.linalg.norm(q[0] / q[1] - p) < tol:
                q[0] += p
                q[1] += 1
                break
        else:
            out.append([p.copy(), 1])
    return np.array([s / c for s, c in out])


def halfspace_solid(hs, points):
    """Closed convex solid through the halfspace intersection points, as qhull's triangulated hull (outward
    triangles). A triangulated hull is closed and 2-manifold by construction; merging its triangles back into
    polygons is what opened seams on the many near-coplanar faces of a curved part, so faces stay triangles."""
    pts = np.round(cluster(points, 1e-5), 6)
    pts = np.unique(pts, axis=0)
    # a point on (or within 1e-4 px of) the segment between two others adds no shape but makes zero-area slivers
    changed = True
    while changed:
        changed = False
        for k in range(len(pts)):
            rest = np.delete(pts, k, axis=0)
            if on_some_segment(pts[k], rest, 1e-4):
                pts, changed = rest, True
                break
    h = ConvexHull(pts)
    tris = []
    for simplex, eq in zip(h.simplices, h.equations):
        t = pts[simplex]
        if np.dot(np.cross(t[1] - t[0], t[2] - t[0]), eq[:3]) < 0:
            t = t[::-1]
        tris.append(list(t))
    return tris


def on_some_segment(p, pts, tol):
    for a in range(len(pts)):
        ab = pts[a + 1:] - pts[a]
        L = (ab * ab).sum(1)
        t = ((p - pts[a]) @ ab.T) / np.where(L > 0, L, 1)
        inside = (t > 0) & (t < 1)
        dist = np.linalg.norm(pts[a] + t[:, None] * ab - p, axis=1)
        if np.any(inside & (dist < tol)):
            return True
    return False


def order_loop(pts, n):
    """Orders coplanar points counter-clockwise about n, keeping collinear points; None if they span no area."""
    c = pts.mean(0)
    u = np.cross(n, [0, 1, 0] if abs(n[1]) < 0.9 else [1, 0, 0])
    u /= np.linalg.norm(u)
    v = np.cross(n, u)
    idx = np.argsort(np.arctan2((pts - c) @ v, (pts - c) @ u))
    poly = pts[idx]
    if polygon_area(poly) < 1e-6:
        return None, None
    if np.dot(np.cross(poly[1] - poly[0], poly[2] - poly[0]), n) < 0 and \
            np.dot(sum(np.cross(poly[i], poly[(i + 1) % len(poly)]) for i in range(len(poly))), n) < 0:
        poly, idx = poly[::-1], idx[::-1]
    elif np.dot(sum(np.cross(poly[i], poly[(i + 1) % len(poly)]) for i in range(len(poly))), n) < 0:
        poly, idx = poly[::-1], idx[::-1]
    return poly, idx


def box_faces(lo, hi):
    (x0, y0, z0), (x1, y1, z1) = lo, hi
    c = lambda x, y, z: np.array([x, y, z], float)
    return [
        [c(x1, y0, z0), c(x1, y1, z0), c(x1, y1, z1), c(x1, y0, z1)],
        [c(x0, y0, z1), c(x0, y1, z1), c(x0, y1, z0), c(x0, y0, z0)],
        [c(x0, y1, z0), c(x0, y1, z1), c(x1, y1, z1), c(x1, y1, z0)],
        [c(x0, y0, z1), c(x0, y0, z0), c(x1, y0, z0), c(x1, y0, z1)],
        [c(x1, y0, z1), c(x1, y1, z1), c(x0, y1, z1), c(x0, y0, z1)],
        [c(x0, y0, z0), c(x0, y1, z0), c(x1, y1, z0), c(x1, y0, z0)],
    ]


def solid_polys(points):
    """Closed convex solid (all hull faces) as outward polygons."""
    return [list(f[2]) for f in hull_faces(points)]


# ---------------------------------------------------------------- regions

def matches(cond, n, c):
    for key, (lo, hi) in cond.items():
        val = {'nx': n[0], 'ny': n[1], 'nz': n[2], 'x': c[0], 'y': c[1], 'z': c[2],
               'ax': abs(c[0]), 'anx': abs(n[0])}[key]
        if not (lo <= val <= hi):
            return False
    return True


def classify(rules, n, c):
    for r in rules:
        if matches(r.get('when', {}), n, c):
            if 'los' in r:
                d = np.array(r['los'][:3], float)
                d /= np.linalg.norm(d)
                mm = r['los'][3] * abs(float(n @ d))
                mm = max(mm, r.get('minMm', 10.0))
            else:
                mm = r['mm']
            return r['name'], round(mm, 1)
    raise SystemExit(f'no region for face n={np.round(n, 3)} c={np.round(c, 2)}')


SKIPPED = []


def plates_from(points, rules, cuts, frame, tag):
    out = []
    faces = hull_faces(points)
    planes = [(n, d) for n, d, _ in faces]
    for n, d, poly in faces:
        used = []
        for cut in cuts:
            if matches(cut.get('when', {}), n, poly.mean(0)):
                used += cut['planes']
        norm_cuts = []
        for p in used:
            cn = np.array(p[:3], float)
            ln = np.linalg.norm(cn)
            norm_cuts.append((cn / ln, float(p[3]) / ln))
        for piece in split(poly, used):
            piece = clean(piece)
            if piece is None or polygon_area(piece) < 0.3:
                SKIPPED.append((tag, np.round(n, 3).tolist(), None if piece is None else round(polygon_area(piece), 3)))
                continue  # sliver faces of the model's convex hull; the 0.4 px ray skin covers them
            c = piece.mean(0)
            name, mm = classify(rules, n, c)
            crossing = [(cn, cd) for cn, cd in norm_cuts
                        if (piece @ cn - cd).min() < -1e-6 or (piece @ cn - cd).max() > 1e-6]
            relevant = [(cn, cd) for cn, cd in norm_cuts if any(abs(p @ cn - cd) < 1e-5 for p in piece)]
            out.append({'frame': frame, 'kind': 'plate', 'name': name, 'mm': mm, 'n': n,
                        'faces': mitred_slab((n, d), planes, relevant, piece), 'centroid': c,
                        'area': polygon_area(piece), 'part': tag})
    return out


# ---------------------------------------------------------------- modules

def ring_segments(center, r_in, r_out, y0, y1, count=12):
    parts = []
    for i in range(count):
        a0, a1 = 2 * math.pi * i / count, 2 * math.pi * (i + 1) / count
        pts = []
        for a in (a0, a1):
            for r in (r_in, r_out):
                for y in (y0, y1):
                    pts.append([center[0] + r * math.cos(a), y, center[2] + r * math.sin(a)])
        parts.append(solid_polys(np.array(pts)))
    return parts


def annulus(center, r_in, r_out, y0, y1, count=24):
    """One closed ring solid (outer and inner walls, top and bottom rings), outward polygons."""
    polys = []
    for i in range(count):
        a0, a1 = 2 * math.pi * i / count, 2 * math.pi * (i + 1) / count
        p = lambda r, a, y: np.array([center[0] + r * math.cos(a), y, center[2] + r * math.sin(a)])
        polys.append([p(r_out, a0, y0), p(r_out, a0, y1), p(r_out, a1, y1), p(r_out, a1, y0)])
        polys.append([p(r_in, a1, y0), p(r_in, a1, y1), p(r_in, a0, y1), p(r_in, a0, y0)])
        polys.append([p(r_in, a0, y1), p(r_in, a1, y1), p(r_out, a1, y1), p(r_out, a0, y1)])
        polys.append([p(r_in, a1, y0), p(r_in, a0, y0), p(r_out, a0, y0), p(r_out, a1, y0)])
    return polys


def auto_modules(spec, hull_pts):
    mods = []
    m = spec['modules']
    lo, hi = hull_pts.min(0), hull_pts.max(0)
    belly, roof = lo[1], hi[1]
    half = spec['hull']['halfWidth']
    ring = spec['ring']
    if 'engine' in m:
        e = m['engine']
        z0 = ring['center'][2] + ring['rOuter'] + e.get('gapAfterRing', 8.0)
        z1 = hi[2] - e.get('insetRear', 3.0)
        lo_e = [-(half - e.get('insetSide', 2.5)), belly + e.get('insetBottom', 1.5), z0]
        hi_e = [half - e.get('insetSide', 2.5), roof - e.get('insetTop', 2.5), z1]
        mods.append({'frame': 'hull', 'kind': 'engine', 'name': e.get('name', 'powerpack'),
                     'parts': [box_faces(lo_e, hi_e)]})
    if 'carousel' in m:
        c = m['carousel']
        r = ring['rOuter']
        mods.append({'frame': 'hull', 'kind': 'ammo', 'name': c.get('name', 'carousel'),
                     'parts': [annulus(ring['center'], r * c.get('rInner', 0.28), r * c.get('rOuter', 0.85),
                                       belly + c.get('bottom', 0.8), belly + c.get('top', 4.5))]})
    for rack in m.get('racks', []):
        mods.append({'frame': rack.get('frame', 'hull'), 'kind': 'ammo', 'name': rack['name'],
                     'parts': [box_faces(rack['min'], rack['max'])]})
    if 'tracks' in m:
        t = m['tracks']
        for side, sx in (('left', 1), ('right', -1)):
            xs = sorted([sx * t['xInner'], sx * t['xOuter']])
            for name, (y0, y1, z0, z1) in t['boxes'].items():
                mods.append({'frame': 'hull', 'kind': 'track', 'name': f'{side}_{name}', 'param': side,
                             'parts': [box_faces([xs[0], y0, z0], [xs[1], y1, z1])]})
    return mods


# ---------------------------------------------------------------- output

def fmt_mm(mm):
    return (f'{mm:.1f}'.rstrip('0').rstrip('.')) + 'mm'


def bone_name(v):
    if v['kind'] == 'plate':
        return f"plate__{fmt_mm(v['mm'])}__{v['name']}"
    return f"{v['kind']}__{v.get('param', '')}__{v['name']}"


def triangulate(poly):
    """Triangles of a convex polygon (which may carry collinear vertices). The loader fans every polygon from its
    first vertex, which makes a zero-area triangle when that vertex is collinear with a later edge; so fan from a
    vertex that avoids that, or from the centroid when none does. Every boundary edge is kept exactly."""
    poly = [np.round(np.array(p, float), 6) for p in poly]
    m = len(poly)
    if m == 3:
        return [poly]
    area = lambda a, b, c: np.linalg.norm(np.cross(b - a, c - a))
    for s in range(m):
        q = poly[s:] + poly[:s]
        if all(area(q[0], q[j], q[j + 1]) > 1e-6 for j in range(1, m - 1)):
            return [[q[0], q[j], q[j + 1]] for j in range(1, m - 1)]
    c = np.round(sum(poly) / m, 6)
    return [[c, poly[j], poly[(j + 1) % m]] for j in range(m)]


def closure_errors(polys):
    """Undirected edges not shared by exactly two faces (after rounding to the written precision)."""
    edges = collections.Counter()
    for t in [t for p in polys for t in triangulate(p)]:
        k = [tuple(np.round(p, 6)) for p in t]
        for i in range(3):
            edges[tuple(sorted((k[i], k[(i + 1) % 3])))] += 1
    return sum(1 for c in edges.values() if c != 2)


def poly_mesh(polys):
    pos, nrm, uvs, out = [], [], [], []
    for poly in [t for p in polys for t in triangulate(p)]:
        poly = [np.array(p, float) for p in poly]
        n = np.cross(poly[1] - poly[0], poly[2] - poly[0])
        n = n / (np.linalg.norm(n) or 1)
        nrm.append([round(float(x), 5) for x in n])
        face = []
        for k, p in enumerate(poly):
            pos.append([round(float(x), 6) for x in p])
            uvs.append([[0, 0], [1, 0], [1, 1], [0, 1]][k % 4])
            face.append([len(pos) - 1, len(nrm) - 1, len(uvs) - 1])
        out.append(face)
    return {'normalized_uvs': True, 'positions': pos, 'normals': nrm, 'uvs': uvs, 'polys': out}


def write(vid, volumes, path):
    bones = [{'name': 'armor_hull', 'pivot': [0, 0, 0]}, {'name': 'armor_turret', 'pivot': [0, 0, 0]},
             {'name': 'armor_barrel', 'pivot': [0, 0, 0]}]
    seen = collections.Counter()
    for v in volumes:
        name = bone_name(v)
        seen[name] += 1
        if seen[name] > 1:
            raise SystemExit(f'duplicate volume {name}')
        polys = v['faces'] if 'faces' in v else [f for part in v['parts'] for f in part]
        bad = sum(closure_errors(part) for part in ([v['faces']] if 'faces' in v else v['parts']))
        if bad:
            print(f'WARNING {name}: {bad} open/non-manifold edge(s)')
        bone = {'name': name, 'parent': f"armor_{v['frame']}", 'pivot': [0, 0, 0], 'poly_mesh': poly_mesh(polys)}
        if 'n' in v:
            # the vehicle face this slab lines: the loader scores skin-only grazes against it
            bone['bvp_surface_normal'] = [round(float(x), 6) for x in v['n']]
        bones.append(bone)
    geo = {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': f'geometry.{vid}_armor', 'texture_width': 16, 'texture_height': 16,
                        'visible_bounds_width': 16, 'visible_bounds_height': 8, 'visible_bounds_offset': [0, 2, 0]},
        'bones': bones}]}
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(geo, f, separators=(',', ':'))
        f.write('\n')


def hull_points(spec):
    """Corner points of the hull core solid: the side profile extruded to +-halfWidth, cut by the optional
    chamfer planes ([nx, ny, nz, d], outward normal, geo px; "mirrorX" adds the X-mirrored plane)."""
    h = spec['hull']
    pts = np.array([[sx * h['halfWidth'], y, z] for z, y in h['profile'] for sx in (-1, 1)], float)
    planes = [np.r_[n, -d] for n, d, _ in hull_faces(pts)]
    for c in h.get('chamfers', []):
        n = np.array(c['plane'][:3], float)
        ln = np.linalg.norm(n)
        planes.append(np.r_[n / ln, -c['plane'][3] / ln])
        if c.get('mirrorX'):
            planes.append(np.r_[n[0] / -ln, n[1] / ln, n[2] / ln, -c['plane'][3] / ln])
    if len(planes) == len(hull_faces(pts)):
        return pts
    return np.round(HalfspaceIntersection(np.array(planes), pts.mean(0)).intersections, 6)


def build(vid):
    spec = json.load(open(os.path.join(SPECS, f'{vid}.json')))
    volumes = []
    # hull solid from the side profile
    h = spec['hull']
    hull_pts = hull_points(spec)
    volumes += plates_from(hull_pts, h['regions'], h.get('cuts', []), 'hull', 'hull')
    # turret ring collar (hull frame)
    ring = spec['ring']
    for i, part in enumerate(ring_segments(ring['center'], ring['rInner'], ring['rOuter'], ring['y'][0], ring['y'][1],
                                            ring.get('segments', 16))):
        a = 2 * math.pi * (i + 0.5) / ring.get('segments', 16)
        volumes.append({'frame': 'hull', 'kind': 'plate', 'name': f"{ring['name']}_{i:02d}", 'mm': ring['mm'],
                        'faces': part, 'part': 'ring', 'n': np.array([math.cos(a), 0.0, math.sin(a)])})
    # turret / barrel parts from model components
    for part in spec['parts']:
        comps = model_components(vid, part['bone'])
        if part.get('each'):
            # one convex solid per selected model component: a stepped or staggered part (mantlet steps, the
            # cheek's offset blocks) keeps its steps instead of being bridged by one hull; overlapping solids are
            # fine because a shot always meets the union's outer surface first
            groups = [c for s in part['select'] for c in select_each(comps, s)]
        else:
            groups = [np.concatenate([select(comps, s) for s in part['select']])]
        for pts in groups:
            volumes += plates_from(pts, part['regions'], part.get('cuts', []), part['frame'], part['name'])
    # unique names within each region
    count = collections.Counter(v['name'] for v in volumes)
    idx = collections.Counter()
    for v in volumes:
        if count[v['name']] > 1 and v.get('part') != 'ring':
            idx[v['name']] += 1
            v['name'] = f"{v['name']}_{idx[v['name']]:02d}"
    volumes += auto_modules(spec, hull_pts)
    return spec, volumes


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument('vid')
    ap.add_argument('--report', action='store_true')
    ap.add_argument('--out')
    a = ap.parse_args(argv)
    spec, volumes = build(a.vid)
    for v in volumes:
        if v['kind'] == 'plate' and 'area' in v:
            print(f"{v['frame']:7s} {bone_name(v):48s} n={np.round(v['n'], 2).tolist()} area={v['area']:.1f}")
        else:
            print(f"{v['frame']:7s} {bone_name(v):48s} parts={len(v.get('parts', [v.get('faces')]))}")
    kinds = collections.Counter(v['kind'] for v in volumes)
    print(dict(kinds))
    if not a.report:
        path = a.out or os.path.join(OUT, f'{a.vid}.geo.json')
        write(a.vid, volumes, path)
        print('wrote', os.path.relpath(path, REPO))


if __name__ == '__main__':
    main(sys.argv[1:])
