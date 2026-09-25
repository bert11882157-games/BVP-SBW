"""Contact and interpenetration checks between placed munitions and aircraft structure (hull blocks).

gap          smallest distance between the munition's surface and the support island (pylon/skin) it hangs
             from, measured over dense surface samples; a properly attached store touches (<= GAP_LIMIT).
depth        how far munition surface samples reach inside structure islands (and structure samples inside the
             munition body); small pylon/rail islands are tested against their convex hull, large skins by
             ray parity; the munition body is its solid of revolution about its axis (fins are thin).
clash        depth of one copy inside another copy's body.
"""
import numpy as np
from scipy.spatial import Delaunay

import geo

GAP_LIMIT = 0.02
DEPTH_LIMIT = 0.03


def surface_samples(tris, per_tri=6, seed=1):
    """Vertices, edge midpoints, centroids and a few random barycentric points of every triangle."""
    rng = np.random.default_rng(seed)
    a, b, c = tris[:, 0], tris[:, 1], tris[:, 2]
    pts = [a, b, c, (a + b) / 2, (b + c) / 2, (c + a) / 2, (a + b + c) / 3]
    for _ in range(per_tri):
        u = rng.random((len(tris), 1))
        v = rng.random((len(tris), 1))
        flip = (u + v) > 1
        u = np.where(flip, 1 - u, u)
        v = np.where(flip, 1 - v, v)
        pts.append(a + u * (b - a) + v * (c - a))
    return np.unique(np.round(np.concatenate(pts), 6), axis=0)


class StructureIndex:
    """Per-island inside tests for the aircraft structure."""

    def __init__(self, structure):
        self.S = structure
        self.hulls = {}

    def _hull(self, island):
        if island not in self.hulls:
            pts = self.S.island_mesh(island).tris.reshape(-1, 3)
            pts = np.unique(np.round(pts, 6), axis=0)
            try:
                self.hulls[island] = Delaunay(pts)
            except Exception:
                self.hulls[island] = None
        return self.hulls[island]

    def islands_near(self, lo, hi, pad=0.02):
        out = []
        for i, (a, b) in self.S.box.items():
            if np.all(b >= lo - pad) and np.all(a <= hi + pad):
                out.append(i)
        return out

    def inside_depth(self, pts, islands):
        """Max depth of pts inside any of the islands, and the island responsible."""
        best = (0.0, None)
        if not len(pts):
            return best
        for i in islands:
            lo, hi = self.S.box[i]
            sel = np.all((pts >= lo - 1e-6) & (pts <= hi + 1e-6), axis=1)
            if not np.any(sel):
                continue
            p = pts[sel]
            if self.S.is_small(i):
                h = self._hull(i)
                if h is None:
                    continue
                inside = h.find_simplex(p) >= 0
            else:
                inside = parity_inside(self.S.island_mesh(i), p)
            if not np.any(inside):
                continue
            tris = self.S.island_mesh(i).tris
            for q in p[inside]:
                d = float(np.min(geo.point_triangle_distances(tris, q)))
                if d > best[0]:
                    best = (d, i)
        return best


def parity_inside(mesh, pts):
    dirs = [np.array(d, float) for d in ([1, 0.0013, 0.0007], [-1, 0.0011, -0.0009], [0.0012, 1, 0.0005],
                                         [0.0008, -1, 0.0014], [0.0006, 0.0009, 1], [-0.0007, 0.0004, -1])]
    out = []
    for p in pts:
        votes = 0
        for d in dirs:
            t, _ = geo.ray_hits(mesh, p, d / np.linalg.norm(d))
            votes += len(geo.dedupe(t)) % 2
        out.append(votes >= 4)
    return np.array(out, bool)


class PlacedMunition:
    """A munition placed in hull blocks with its body-of-revolution model for inside tests."""

    def __init__(self, geom, mesh_hull, axis_origin, axis_dir):
        self.geom = geom
        self.mesh = mesh_hull
        self.origin = np.asarray(axis_origin, float)       # hull point of the model axis at model z = 0
        self.dir = np.asarray(axis_dir, float)             # hull direction of model +z
        self.samples = surface_samples(mesh_hull.tris)
        lo, hi = mesh_hull.bounds()
        self.lo, self.hi = lo, hi

    def body_depth(self, pts):
        """Depth of pts inside the body of revolution (0 when outside)."""
        g = self.geom
        rel = pts - self.origin
        z = rel @ self.dir
        radial = rel - np.outer(z, self.dir)
        dist = np.linalg.norm(radial, axis=1)
        r = np.interp(z, g.profile_z, np.nan_to_num(g.profile_r), left=0, right=0)
        depth = r - dist
        depth[(z < g.profile_z[0]) | (z > g.profile_z[-1])] = 0
        return np.clip(depth, 0, None)


def min_distance(samples, tris, near_point=None, radius=0.6):
    if near_point is not None:
        samples = samples[np.linalg.norm(samples - near_point, axis=1) <= radius]
    if not len(samples) or not len(tris):
        return np.inf
    lo = tris.reshape(-1, 3).min(0) - 0.1
    hi = tris.reshape(-1, 3).max(0) + 0.1
    samples = samples[np.all((samples >= lo) & (samples <= hi), axis=1)]
    if not len(samples):
        return np.inf
    best = np.inf
    for p in samples:
        best = min(best, float(np.min(geo.point_triangle_distances(tris, p))))
        if best < 1e-4:
            break
    return best
