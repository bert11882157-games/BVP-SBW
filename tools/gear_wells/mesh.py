"""Polygon helpers for tools/gear_wells: bone meshes as lists of corner records, box subtraction, ray casts.

A corner is a tuple (position[3], normal[3], uv[2]) as numpy arrays; a polygon is a list of corners (convex,
planar, in winding order). Everything here is in geo-file model pixels (x left, y up, nose toward -z).
"""
import numpy as np

EPS = 1e-6


def bone_polys(pm):
    """The poly_mesh of a bone as polygons of corners."""
    P = np.array(pm['positions'], float)
    N = np.array(pm.get('normals') or [[0, 1, 0]], float)
    U = np.array(pm['uvs'], float)
    out = []
    for poly in pm['polys']:
        out.append([(P[v[0]], N[v[1]] if v[1] < len(N) else np.array([0, 1.0, 0]), U[v[2]]) for v in poly])
    return out


def write_polys(pm, polys, digits=5):
    """Rebuilds positions/normals/uvs/polys of a poly_mesh from polygons (shared corners de-duplicated)."""
    pos, nrm, uvs = {}, {}, {}

    def key(table, v):
        k = tuple(round(float(c), digits) + 0.0 for c in v)
        if k not in table:
            table[k] = len(table)
        return table[k]

    out = []
    for poly in polys:
        out.append([[key(pos, p), key(nrm, n), key(uvs, u)] for p, n, u in poly])
    pm['positions'] = [list(k) for k in pos]
    pm['normals'] = [list(k) for k in nrm]
    pm['uvs'] = [list(k) for k in uvs]
    pm['polys'] = out


def area(poly):
    p = [c[0] for c in poly]
    s = np.zeros(3)
    for k in range(1, len(p) - 1):
        s += np.cross(p[k] - p[0], p[k + 1] - p[0])
    return 0.5 * float(np.linalg.norm(s))


def lerp(a, b, t):
    n = a[1] + (b[1] - a[1]) * t
    ln = np.linalg.norm(n)
    return (a[0] + (b[0] - a[0]) * t, n / ln if ln > EPS else a[1], a[2] + (b[2] - a[2]) * t)


def split(poly, axis, value, keep_above):
    """(part on the kept side of the plane coord[axis] = value, part on the other side); either may be None."""
    d = [c[0][axis] - value for c in poly]
    if not keep_above:
        d = [-x for x in d]
    if all(x >= -EPS for x in d):
        return poly, None
    if all(x <= EPS for x in d):
        return None, poly
    keep, drop = [], []
    n = len(poly)
    for i in range(n):
        a, b = poly[i], poly[(i + 1) % n]
        da, db = d[i], d[(i + 1) % n]
        if da >= -EPS:
            keep.append(a)
        if da <= EPS:
            drop.append(a)
        if (da > EPS and db < -EPS) or (da < -EPS and db > EPS):
            t = da / (da - db)
            c = lerp(a, b, t)
            c = (c[0].copy(), c[1], c[2])
            c[0][axis] = value                       # exactly on the plane: walls reuse these points
            keep.append(c)
            drop.append(c)
    keep = clean(keep)
    drop = clean(drop)
    return keep, drop


def clean(poly):
    if poly is None:
        return None
    out = []
    for c in poly:
        if not out or np.linalg.norm(c[0] - out[-1][0]) > 1e-5:
            out.append(c)
    if len(out) > 1 and np.linalg.norm(out[0][0] - out[-1][0]) <= 1e-5:
        out.pop()
    if len(out) < 3 or area(out) < 1e-7:
        return None
    return out


def subtract_box(poly, lo, hi):
    """(pieces outside the box, piece inside the box or None)."""
    outside = []
    cur = poly
    for axis in range(3):
        for value, above in ((lo[axis], True), (hi[axis], False)):
            if cur is None:
                return outside, None
            keep, drop = split(cur, axis, value, above)
            if drop is not None:
                outside.append(drop)
            cur = keep
    return outside, cur


def overlaps_box(poly, lo, hi, pad=0.0):
    p = np.array([c[0] for c in poly])
    return bool(np.all(p.max(0) >= lo - pad) and np.all(p.min(0) <= hi + pad))


def triangulate(poly):
    """Fan triangles (the loader takes triangles and quads); a planar convex quad is kept whole."""
    if len(poly) <= 4:
        return [poly]
    return [[poly[0], poly[k], poly[k + 1]] for k in range(1, len(poly) - 1)]


def tri_array(polys):
    """Nx3x3 triangle positions of polygons (fan triangulated)."""
    t = []
    for poly in polys:
        for k in range(1, len(poly) - 1):
            t.append([poly[0][0], poly[k][0], poly[k + 1][0]])
    return np.array(t, float).reshape(-1, 3, 3)


def ray_hits(tris, origin, direction):
    """Sorted distances t >= 0 at which origin + t*direction crosses the triangles (two-sided)."""
    if len(tris) == 0:
        return np.array([])
    o = np.asarray(origin, float); d = np.asarray(direction, float)
    v0, v1, v2 = tris[:, 0], tris[:, 1], tris[:, 2]
    e1, e2 = v1 - v0, v2 - v0
    p = np.cross(d, e2)
    det = np.einsum('ij,ij->i', e1, p)
    ok = np.abs(det) > 1e-12
    inv = np.where(ok, 1.0 / np.where(ok, det, 1), 0)
    s = o - v0
    u = np.einsum('ij,ij->i', s, p) * inv
    q = np.cross(s, e1)
    v = (q @ d) * inv
    t = np.einsum('ij,ij->i', e2, q) * inv
    m = ok & (u >= -1e-7) & (v >= -1e-7) & (u + v <= 1 + 1e-7) & (t >= 0)
    return np.sort(t[m])


def repair_t_junctions(polys, points, tol=2e-4):
    """Splits polygon edges that pass through any of `points` (new cut vertices), so the mesh stays watertight."""
    if len(points) == 0:
        return polys
    pts = np.array(points, float)
    out = []
    for poly in polys:
        if len(poly) < 3:          # a degenerate face in the source model is passed through untouched
            out.append(poly)
            continue
        p = np.array([c[0] for c in poly])
        lo, hi = p.min(0) - tol, p.max(0) + tol
        cand = pts[np.all((pts >= lo) & (pts <= hi), axis=1)]
        if len(cand) == 0:
            out.append(poly)
            continue
        new = []
        changed = False
        n = len(poly)
        for i in range(n):
            a, b = poly[i], poly[(i + 1) % n]
            new.append(a)
            ab = b[0] - a[0]
            L2 = float(ab @ ab)
            if L2 < 1e-12:
                continue
            t = (cand - a[0]) @ ab / L2
            dist = np.linalg.norm(a[0] + np.outer(t, ab) - cand, axis=1)
            sel = (t > 1e-4) & (t < 1 - 1e-4) & (dist < tol)
            for tt, q in sorted(zip(t[sel], cand[sel]), key=lambda z: z[0]):
                c = lerp(a, b, tt)
                new.append((q.copy(), c[1], c[2]))
                changed = True
        if not changed:
            out.append(poly)
        else:
            out.extend(triangulate_any(new))
    return out


def triangulate_any(poly):
    """A convex polygon with collinear extra corners, as triangles fanned from a true corner."""
    if len(poly) == 3:
        return [poly]
    # fan from the corner with the largest turn, so no triangle is degenerate
    n = len(poly)
    best, bi = -1, 0
    for i in range(n):
        a, b, c = poly[i - 1][0], poly[i][0], poly[(i + 1) % n][0]
        s = float(np.linalg.norm(np.cross(a - b, c - b)))
        if s > best:
            best, bi = s, i
    poly = poly[bi:] + poly[:bi]
    tris = [[poly[0], poly[k], poly[k + 1]] for k in range(1, n - 1)]
    return [t for t in tris if area(t) > 1e-9]
