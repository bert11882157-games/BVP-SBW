"""Clean armor solids from a vehicle model: the underlying body, three orthogonal outlines, symmetric, flat faces.

Used by auto_mesh.py (owner's brief, 2026-09-28: "what is the upper front plate, the lower front plate, the side,
the other side, the underlying shape of the turret, the roof and the back; make it all connected, symmetric and
aesthetically pleasing, with far fewer polygons; ignore greeble and the gun breech").

* Body. A frame's structure is its largest connected component (the hull or turret shell) plus the other large,
  long components (skirts, fender panels, a separate roof plate, cheek blocks). Everything else - hatches,
  periscopes, boxes, lights, tools, cables, handles - is greeble and does not shape the armor.
* Solid. The intersection of three extrusions: the side outline (z, y) gives the upper and lower front plates,
  roof, engine deck and rear plate; the front outline (x, y) the side walls and their slope; the top outline
  (x, z) the plan shape (a tapered nose, turret cheeks). Each outline is the convex hull of the body, mirrored
  about the centreline so both sides match, and simplified to a few vertices (a corner is cut only when that loses
  under 1.5 % of the outline's area). Every face therefore lies on a flat plane of the real body.
"""
import math

import numpy as np
from scipy.spatial import ConvexHull, HalfspaceIntersection

MAX_VERTICES = 8        # per outline
MAX_CORNER_LOSS = 0.015  # share of the outline area one simplification step may cut away
MIN_PANEL_AREA = 0.04    # a secondary component is structure when it has at least this share of the frame's area
MIN_PANEL_LENGTH = 0.40  # ... and is at least this long relative to the main component's longest extent
MIN_BLOCK_VOLUME = 0.15  # or when its bounding box is at least this share of the main component's
PANEL_THICKNESS = 2.5    # px: a panel is a thin sheet (a skirt, a roof plate); boxes and bins are greeble


def polygon_area(P):
    x, y = P[:, 0], P[:, 1]
    return 0.5 * abs(float(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1))))


def structure(components):
    """Indices of the structural components among [(points, polys, area)]: the largest plus big long panels."""
    if not components:
        return []
    areas = np.array([a for _, _, a in components])
    main = int(np.argmax(areas))
    total = float(areas.sum())
    main_len = float(np.ptp(components[main][0], axis=0).max())
    main_vol = float(np.prod(np.maximum(np.ptp(components[main][0], axis=0), 0.5)))
    keep = [main]
    for i, (pts, _, a) in enumerate(components):
        if i == main:
            continue
        ext = np.ptp(pts, axis=0)
        # a big block (a lower hull, a rear plate, a cheek) or a big long panel (a skirt, a fender, a roof plate)
        block = float(np.prod(np.maximum(ext, 0.5))) >= MIN_BLOCK_VOLUME * main_vol
        panel = (a >= MIN_PANEL_AREA * total and ext.max() >= MIN_PANEL_LENGTH * main_len
                 and ext.min() <= PANEL_THICKNESS)
        if block or panel:
            keep.append(i)
    return keep


def outline(points2d, mirror_axis=None, max_vertices=MAX_VERTICES, max_loss=MAX_CORNER_LOSS):
    """Convex outline of 2-D points (counter-clockwise), mirrored about u = mirror_axis when given, with small
    corners cut (in mirror pairs) down to max_vertices. None for degenerate input."""
    P = np.asarray(points2d, float)
    if mirror_axis is not None:
        M = P.copy()
        M[:, 0] = 2 * mirror_axis - M[:, 0]
        P = np.vstack([P, M])
    P = np.unique(np.round(P, 4), axis=0)
    if len(P) < 3 or min(np.ptp(P, axis=0)) < 0.5:
        return None
    try:
        h = ConvexHull(P)
    except Exception:
        return None
    V = P[h.vertices]  # counter-clockwise
    area0 = polygon_area(V)

    def mirror_index(V, i):
        if mirror_axis is None:
            return None
        target = np.array([2 * mirror_axis - V[i, 0], V[i, 1]])
        d = np.linalg.norm(V - target, axis=1)
        j = int(np.argmin(d))
        return j if d[j] < 1e-3 and j != i else None

    def corner(V, i):
        a, b, c = V[i - 1], V[i], V[(i + 1) % len(V)]
        return abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])) / 2

    while True:
        # collinear points are always removed; otherwise only while above the vertex budget
        costs = []
        for i in range(len(V)):
            j = mirror_index(V, i)
            cost = corner(V, i) + (corner(V, j) if j is not None else 0.0)
            costs.append((cost, i, j))
        cost, i, j = min(costs)
        collinear = cost < 1e-6 * max(area0, 1.0)
        if not collinear and (len(V) <= max_vertices or cost > max_loss * area0):
            break
        if len(V) - (2 if j is not None else 1) < 3:
            break
        drop = {i} | ({j} if j is not None else set())
        V = V[[k for k in range(len(V)) if k not in drop]]
    return V


def halfspaces_2d(V):
    """Outward (normal, offset) of each edge of a counter-clockwise polygon: n . p <= d inside."""
    out = []
    for k in range(len(V)):
        a, b = V[k], V[(k + 1) % len(V)]
        e = b - a
        n = np.array([e[1], -e[0]])
        ln = np.linalg.norm(n)
        if ln < 1e-9:
            continue
        n /= ln
        out.append((n, float(n @ a)))
    return out


def solid(points, centre_x, side_pts=None, front_pts=None, top_pts=None):
    """Vertices of the intersection of the side (z, y), front (x, y) and top (x, z) outline extrusions of the
    points, symmetric about x = centre_x. None when an outline is degenerate."""
    P = np.asarray(points, float)
    sp = P if side_pts is None else np.asarray(side_pts, float)
    fp = P if front_pts is None else np.asarray(front_pts, float)
    tp = P if top_pts is None else np.asarray(top_pts, float)
    side = outline(sp[:, [2, 1]])
    front = outline(fp[:, [0, 1]], mirror_axis=centre_x)
    top = outline(tp[:, [0, 2]], mirror_axis=centre_x)
    if side is None or front is None or top is None:
        return None
    hs = []
    for n, d in halfspaces_2d(side):      # (z, y)
        hs.append([0.0, n[1], n[0], -d])
    for n, d in halfspaces_2d(front):     # (x, y)
        hs.append([n[0], n[1], 0.0, -d])
    for n, d in halfspaces_2d(top):       # (x, z)
        hs.append([n[0], 0.0, n[1], -d])
    hs = np.array(hs)
    # a strictly interior point: the centroid of the three outlines' interiors
    inner = np.array([centre_x, side[:, 1].mean(), side[:, 0].mean()])
    if np.any(hs[:, :3] @ inner + hs[:, 3] >= -1e-6):
        # fall back to the Chebyshev centre
        from scipy.optimize import linprog
        A, b = hs[:, :3], -hs[:, 3]
        norm = np.linalg.norm(A, axis=1)
        res = linprog([0, 0, 0, -1], A_ub=np.c_[A, norm], b_ub=b, bounds=[(None, None)] * 3 + [(0, None)])
        if not res.success or res.x[3] < 1e-3:
            return None
        inner = res.x[:3]
    try:
        hi = HalfspaceIntersection(hs, inner)
    except Exception:
        return None
    return np.unique(np.round(hi.intersections, 5), axis=0)


SPAN_WIDTH = 0.2    # side outline: faces spanning at least this share of the body's width (glacis, roof, belly)
SPAN_LENGTH = 0.15  # front outline: faces spanning at least this share of its length (side walls, roof, belly)


def solid_from_polys(polys, centre_x):
    """solid() from model polygons, each outline drawn only from the faces that shape that view: a sliver, a
    headlight or a fender bracket poking out at the nose spans little of the width, so it does not bend the glacis.
    The top outline takes faces that span either way."""
    polys = [np.asarray(q, float) for q in polys if q is not None and len(q) >= 3]
    if not polys:
        return None
    P = np.concatenate(polys)
    width, length = float(np.ptp(P[:, 0])), float(np.ptp(P[:, 2]))
    wide = [q for q in polys if np.ptp(q[:, 0]) >= SPAN_WIDTH * width]
    long = [q for q in polys if np.ptp(q[:, 2]) >= SPAN_LENGTH * length]
    side_pts = np.concatenate(wide) if wide else P
    front_pts = np.concatenate(long) if long else P
    top_pts = np.concatenate(wide + long) if wide or long else P
    return solid(P, centre_x, side_pts=side_pts, front_pts=front_pts, top_pts=top_pts)


def box(points, centre_x):
    """A box around the points, symmetric about x = centre_x."""
    P = np.asarray(points, float)
    half = float(np.max(np.abs(P[:, 0] - centre_x)))
    lo = np.array([centre_x - half, P[:, 1].min(), P[:, 2].min()])
    hi = np.array([centre_x + half, P[:, 1].max(), P[:, 2].max()])
    return np.array([[x, y, z] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])])
