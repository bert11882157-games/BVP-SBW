#!/usr/bin/env python3
"""Hull glazing for canopies the frame-ring fit cannot read (frameless bubbles, open lattices, glazed noses and roof
windows): inside a box round the canopy the model's surfaces are wrapped in their convex hull, each hull facet is cut
into small flat pieces and a piece is glass where a crew eye sees it through an opening (nothing of the model stands
between the eye and it). Pieces over the skin, or bridging a hollow outside it, are hidden from every eye and dropped;
a facet seen whole stays one triangle. Glass is flat per facet and lies on the frame's outer face.

panes.json entry: "hull": [{"box": [x0, y0, z0, x1, y1, z1], "extra_eyes": [[x, y, z]], "down": -0.3, "exclude": [[x0, y0, z0, x1, y1, z1]]}, ...]
"""
import numpy as np
from scipy.spatial import ConvexHull

SPACING = 0.03    # blocks between surface samples
SUB = 8           # pieces per facet edge
SEEN = 0.03       # a piece counts as seen when the eye's view reaches within this of it
BOX_FACE = 0.02   # hull facets lying on a box face are the box's cut, not canopy
SMOOTH_FILL = 0.8  # a facet's visible pieces become one straight-edged pane when they fill this share of their outline


def surface(tris, box):
    lo, hi = np.array(box[:3], float), np.array(box[3:], float)
    keep = ((tris.max(1) >= lo) & (tris.min(1) <= hi)).all(1)
    out = []
    for a, b, c in tris[keep]:
        n = int(min(60, max(1, np.ceil(max(np.linalg.norm(b - a), np.linalg.norm(c - a), np.linalg.norm(c - b)) / SPACING))))
        i, j = np.meshgrid(np.arange(n + 1), np.arange(n + 1))
        m = i + j <= n
        u, v = i[m] / n, j[m] / n
        out.append(a + (b - a) * u[:, None] + (c - a) * v[:, None])
    if not out:
        return np.zeros((0, 3))
    P = np.vstack(out)
    return P[((P >= lo) & (P <= hi)).all(1)]


def pieces(t, sub=SUB):
    a, b, c = t
    out = []
    for i in range(sub):
        for j in range(sub - i):
            p = lambda u, v: a + (b - a) * (u / sub) + (c - a) * (v / sub)
            out.append(np.array([p(i, j), p(i + 1, j), p(i, j + 1)]))
            if i + j + 1 < sub:
                out.append(np.array([p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)]))
    return out


def glaze(T, tris, uvs, tex, eyes, spec):
    box = spec['box']
    lo, hi = np.array(box[:3], float), np.array(box[3:], float)
    P = surface(tris, box)
    for ex in spec.get('exclude', []):
        # parts standing through the box that are not canopy (probes, mirrors, sights)
        P = P[~((P >= np.array(ex[:3])) & (P <= np.array(ex[3:]))).all(1)]
    if len(P) < 10:
        return []
    h = ConvexHull(P)
    eyes = list(eyes) + [np.array(e, float) for e in spec.get('extra_eyes', [])]
    cubes = [T.render_depth(tris, uvs, tex, e) for e in eyes]
    down = spec.get('down', -0.35)
    out = []
    for simplex, eq in zip(h.simplices, h.equations):
        n = eq[:3]
        t = P[simplex]
        if n[1] < down:
            continue
        on_face = [(np.abs(t[:, k] - lo[k]) < BOX_FACE).all() or (np.abs(t[:, k] - hi[k]) < BOX_FACE).all() for k in range(3)]
        if any(on_face):
            continue
        ps = pieces(t)
        C = np.array([p.mean(0) for p in ps])
        seen = np.zeros(len(ps), bool)
        for e, cube in zip(eyes, cubes):
            r = C - e
            s = np.linalg.norm(r, axis=1)
            d = T.lookup(cube, r / s[:, None])
            # the eye's view reaches the piece, and from inside (the facet faces away from the eye)
            seen |= (d >= s - SEEN) & ((r @ n) > 0)
        if seen.all():
            out.append(t)
        elif seen.any():
            kept = [p for p, k in zip(ps, seen) if k]
            # straight edges: the visible pieces' convex outline on the facet, where they fill most of it
            # (otherwise the stepped pieces, which follow a non-convex opening)
            outline = smooth(kept, n)
            out += outline if outline else kept
    return out


def smooth(kept, n, fill=SMOOTH_FILL):
    from scipy.spatial import ConvexHull
    pts = np.vstack(kept)
    u = np.cross(n, [0.0, 1.0, 0.0] if abs(n[1]) < 0.9 else [1.0, 0.0, 0.0]); u /= np.linalg.norm(u)
    v = np.cross(n, u)
    c = pts.mean(0)
    q = np.stack([(pts - c) @ u, (pts - c) @ v], -1)
    try:
        h = ConvexHull(q)
    except Exception:
        return []
    area = sum(np.linalg.norm(np.cross(p[1] - p[0], p[2] - p[0])) / 2 for p in kept)
    if area < fill * h.volume:
        return []
    ring = pts[h.vertices]
    return [np.array([ring[0], ring[k], ring[k + 1]]) for k in range(1, len(ring) - 1)]
