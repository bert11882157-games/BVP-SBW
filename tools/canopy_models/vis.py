"""Which faces of a model are outer surface: seen from outside the aircraft other than through the cockpit opening.

Orthographic face-id renders from 26 directions around the cockpit (the cube's faces, edges and corners), with the
canopy glass drawn as an opaque occluder pulled slightly in toward the crew. A face counts as seen where it fills a
small pixel window outside the screen outline of the canopy (what shows inside that outline is seen through the
opening: the cockpit). Canopy frames lie in the outline too; the caller keeps them by their distance to the glass.
"""
import itertools, math
import numpy as np

DIRS = [np.array(d, float) / np.linalg.norm(d) for d in itertools.product((-1, 0, 1), repeat=3) if any(d)]


def _basis(d):
    up = np.array([0.0, 1.0, 0.0]) if abs(d[1]) < 0.9 else np.array([0.0, 0.0, 1.0])
    r = np.cross(up, d); r /= np.linalg.norm(r)
    return r, np.cross(d, r)


def _hull2(p):
    p = sorted(map(tuple, p))
    def half(pts):
        h = []
        for q in pts:
            while len(h) >= 2 and (h[-1][0] - h[-2][0]) * (q[1] - h[-2][1]) - \
                    (h[-1][1] - h[-2][1]) * (q[0] - h[-2][0]) <= 0:
                h.pop()
            h.append(q)
        return h
    return half(p)[:-1] + half(p[::-1])[:-1]


def _opening(sx, sy, z, size, grow=3):
    """pixels inside the screen outline of each canopy (the occluder split where its z has gaps over 0.3): what is
    seen there is seen through the cockpit opening"""
    from PIL import Image, ImageDraw
    m = Image.new('L', (size, size), 0); d = ImageDraw.Draw(m)
    X, Y, Z = sx.ravel(), sy.ravel(), z.ravel()
    order = np.argsort(Z); cut = [0]
    for i in range(1, len(order)):
        if Z[order[i]] - Z[order[i - 1]] > 0.3:
            cut.append(i)
    cut.append(len(order))
    for a, b in zip(cut, cut[1:]):
        h = _hull2(np.stack([X[order[a:b]], Y[order[a:b]]], 1))
        if len(h) >= 3:
            d.polygon(h, fill=255, outline=255, width=2 * grow + 1)
    return np.asarray(m) > 0


def visible(tris, occ, centre, radius, px=0.006, alpha=None, core=2, through_opening=True):
    """set of indices into tris (N x 3 x 3) seen from outside; occ (M x 3 x 3) occludes but is not reported.
    alpha: optional callable(index, bary weights array) -> bool mask of opaque pixels (alpha-tested textures)."""
    allt = np.concatenate([tris, occ]) if len(occ) else tris
    n = len(tris)
    size = int(math.ceil(2 * radius / px))
    seen = set()
    for d in DIRS:
        r, u = _basis(d)
        rel = allt - centre
        sx = (rel @ r + radius) / px
        sy = (rel @ u + radius) / px
        dz = rel @ d                                   # larger: farther from a camera at -d looking along +d
        depth = np.full((size, size), np.inf); ids = np.full((size, size), -1, np.int64)
        for i in range(len(allt)):
            ax, bx, cx = sx[i]; ay, by, cy = sy[i]
            x0 = max(0, int(math.floor(min(ax, bx, cx)))); x1 = min(size - 1, int(math.ceil(max(ax, bx, cx))))
            y0 = max(0, int(math.floor(min(ay, by, cy)))); y1 = min(size - 1, int(math.ceil(max(ay, by, cy))))
            if x1 < x0 or y1 < y0:
                continue
            den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if abs(den) < 1e-9:
                continue
            X, Y = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            w0 = ((by - cy) * (X - cx) + (cx - bx) * (Y - cy)) / den
            w1 = ((cy - ay) * (X - cx) + (ax - cx) * (Y - cy)) / den
            w2 = 1 - w0 - w1
            m = (w0 >= -0.02) & (w1 >= -0.02) & (w2 >= -0.02)    # a little conservative: thin faces still show
            if not m.any():
                continue
            if alpha is not None and i < n:
                m &= alpha(i, np.stack([w0, w1, w2], -1))
            z = w0 * dz[i, 0] + w1 * dz[i, 1] + w2 * dz[i, 2]
            blk = depth[y0:y1 + 1, x0:x1 + 1]
            m &= z < blk - 1e-5
            blk[m] = z[m]
            ids[y0:y1 + 1, x0:x1 + 1][m] = i
        # a face counts as seen where it fills a (2*core+1)^2 pixel window: a sliver glimpsed through a slit between
        # the glass and the skin does not make it outer surface
        eq = ids >= 0
        for dx in range(-core, core + 1):
            for dy in range(-core, core + 1):
                if dx or dy:
                    eq &= ids == np.roll(np.roll(ids, dx, 1), dy, 0)
        if len(occ) and through_opening:
            eq &= ~_opening(sx[n:], sy[n:], occ[:, :, 2], size)
        v = np.unique(ids[eq])
        seen.update(int(k) for k in v if 0 <= k < n)
    return seen


def skirt(glass, eyes, drop=0.15, out=0.10):
    """quads hanging from every open edge of the glass, `drop` blocks down and `out` blocks outward (away from the
    nearest crew eye, horizontally): they close the slits between the glass and the sill or deck, which are part of
    the cockpit opening"""
    E = np.asarray(eyes, float).reshape(-1, 3)
    key = lambda p: tuple(np.round(p, 3))
    count = {}
    for t in glass:
        for a, b in ((0, 1), (1, 2), (2, 0)):
            k = frozenset((key(t[a]), key(t[b])))
            count[k] = count.get(k, 0) + 1
    quads = []
    down = np.array([0.0, drop, 0.0])
    for t in glass:
        for a, b in ((0, 1), (1, 2), (2, 0)):
            if count[frozenset((key(t[a]), key(t[b])))] == 1:
                p, q = t[a], t[b]
                m = (p + q) / 2
                e = E[np.argmin(((E - m) ** 2).sum(1))]
                h = m - e; h[1] = 0; h /= max(np.linalg.norm(h), 1e-9)
                off = h * out - down
                quads += [[p, q, q + off], [p, q + off, p + off]]
    return np.array(quads, float).reshape(-1, 3, 3)


def pull_in(glass, eyes, by=0.02):
    """the glass moved toward the nearest crew eye by `by` blocks"""
    g = glass.reshape(-1, 3).copy()
    E = np.asarray(eyes, float).reshape(-1, 3)
    near = E[np.argmin(((g[:, None, :] - E[None]) ** 2).sum(-1), 1)]
    d = near - g; d /= np.maximum(np.linalg.norm(d, axis=1, keepdims=True), 1e-9)
    return (g + d * by).reshape(glass.shape)
