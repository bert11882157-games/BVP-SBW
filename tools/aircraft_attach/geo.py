"""Geometry helpers for BVP poly_mesh geo models (offline tooling, no Minecraft).

Frames
  geo      : model file coordinates in pixels (1/16 block), as stored in `positions`.
  hull     : aircraft hull-local blocks, +Z = nose, "Left" = -X:  hull = (gx, gy, -gz) / 16.
  store    : munition "model-file blocks" = geo / 16 (no x negation).
  anchor   : the MountAnchor frame used by BvpSuspendedStoreRenderer = (-gx, gy, gz) / 16.

Only numpy is required.
"""
import json
import os
from collections import defaultdict

import numpy as np

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(REPO, "bvp/src/generated/resources/assets/berts_vehicle_pack")
DATA = os.path.join(REPO, "bvp/src/generated/resources/data/berts_vehicle_pack/sbw")
CUSTOM_GEO = os.path.join(ASSETS, "custom_geo")


def resource_path(location):
    """berts_vehicle_pack:custom_geo/x.geo.json -> absolute file path in the generated tree."""
    ns, path = location.split(":", 1)
    assert ns == "berts_vehicle_pack", location
    return os.path.join(ASSETS, path)


class Mesh:
    """Triangle soup with island (connected component) labels."""

    def __init__(self, tris, island, names=None):
        self.tris = np.asarray(tris, float).reshape(-1, 3, 3)
        self.island = np.asarray(island, int)
        self.names = names or {}
        if len(self.tris):
            e1 = self.tris[:, 1] - self.tris[:, 0]
            e2 = self.tris[:, 2] - self.tris[:, 0]
            n = np.cross(e1, e2)
            ln = np.linalg.norm(n, axis=1)
            self.area = ln * 0.5
            self.normal = n / np.where(ln[:, None] > 0, ln[:, None], 1)
        else:
            self.area = np.zeros(0)
            self.normal = np.zeros((0, 3))

    def __len__(self):
        return len(self.tris)

    def subset(self, mask):
        m = Mesh(self.tris[mask], self.island[mask], self.names)
        return m

    def islands(self):
        return sorted(set(self.island.tolist()))

    def island_mesh(self, ids):
        ids = set([ids] if isinstance(ids, (int, np.integer)) else ids)
        return self.subset(np.array([i in ids for i in self.island]))

    def bounds(self):
        p = self.tris.reshape(-1, 3)
        return p.min(0), p.max(0)

    def transformed(self, fn):
        m = Mesh(fn(self.tris.reshape(-1, 3)).reshape(-1, 3, 3), self.island, self.names)
        return m


def load_bones(path):
    with open(path) as f:
        data = json.load(f)
    return data["minecraft:geometry"][0]["bones"]


def bone_mesh(bone, island_offset=0):
    """Triangulate a bone's poly_mesh in geo pixels and label connected islands."""
    pm = bone.get("poly_mesh")
    if not pm or not pm.get("polys"):
        return Mesh(np.zeros((0, 3, 3)), np.zeros(0, int)), 0
    pos = np.asarray(pm["positions"], float)
    polys = [[v[0] for v in p] for p in pm["polys"]]
    q = np.round(pos * 1000).astype(np.int64)
    key = {}
    ids = [key.setdefault(tuple(r), len(key)) for r in q]
    parent = list(range(len(key)))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for p in polys:
        for a in p[1:]:
            ra, rb = find(ids[p[0]]), find(ids[a])
            if ra != rb:
                parent[ra] = rb
    roots = {}
    tris, isl = [], []
    for p in polys:
        r = find(ids[p[0]])
        label = roots.setdefault(r, len(roots)) + island_offset
        for i in range(1, len(p) - 1):
            tris.append([pos[p[0]], pos[p[i]], pos[p[i + 1]]])
            isl.append(label)
    return Mesh(np.array(tris), np.array(isl)), len(roots)


def model_mesh(path, bone_filter=None):
    """All bones (optionally filtered by name) merged; island ids are unique per bone island."""
    bones = load_bones(path)
    all_tris, all_isl, names = [], [], {}
    offset = 0
    for b in bones:
        if bone_filter and not bone_filter(b["name"]):
            continue
        m, n = bone_mesh(b, offset)
        if len(m):
            all_tris.append(m.tris)
            all_isl.append(m.island)
            for i in range(offset, offset + n):
                names[i] = b["name"]
        offset += n
    if not all_tris:
        return Mesh(np.zeros((0, 3, 3)), np.zeros(0, int))
    return Mesh(np.concatenate(all_tris), np.concatenate(all_isl), names)


def geo_to_hull(p):
    p = np.asarray(p, float)
    return p * np.array([1.0, 1.0, -1.0]) / 16.0


def hull_to_geo(p):
    p = np.asarray(p, float)
    return p * np.array([1.0, 1.0, -1.0]) * 16.0


def aircraft_mesh(name, include_stores=False):
    """Aircraft structure in hull blocks. Hidden baked stores (`suspended_*`) are excluded by default."""
    path = os.path.join(CUSTOM_GEO, name + ".geo.json")

    def keep(bone):
        if include_stores:
            return True
        return "suspended_" not in bone

    m = model_mesh(path, keep)
    return m.transformed(geo_to_hull)


def baked_store_mesh(name):
    path = os.path.join(CUSTOM_GEO, name + ".geo.json")
    m = model_mesh(path, lambda b: "suspended_" in b)
    return m.transformed(geo_to_hull)


def ray_hits(mesh, origin, direction, tmax=np.inf):
    """All (t, triangle index) where the ray origin + t*dir (t>=0) crosses a triangle (two-sided)."""
    if not len(mesh):
        return np.zeros(0), np.zeros(0, int)
    o = np.asarray(origin, float)
    d = np.asarray(direction, float)
    v0, v1, v2 = mesh.tris[:, 0], mesh.tris[:, 1], mesh.tris[:, 2]
    e1, e2 = v1 - v0, v2 - v0
    h = np.cross(d, e2)
    a = np.einsum("ij,ij->i", e1, h)
    ok = np.abs(a) > 1e-12
    f = np.where(ok, 1.0 / np.where(ok, a, 1), 0)
    s = o - v0
    u = f * np.einsum("ij,ij->i", s, h)
    q = np.cross(s, e1)
    v = f * (q @ d)
    t = f * np.einsum("ij,ij->i", e2, q)
    eps = 1e-9
    hit = ok & (u >= -eps) & (v >= -eps) & (u + v <= 1 + eps) & (t >= -1e-9) & (t <= tmax)
    idx = np.nonzero(hit)[0]
    order = np.argsort(t[idx])
    return t[idx][order], idx[order]


def first_hit(mesh, origin, direction, tmax=np.inf):
    t, idx = ray_hits(mesh, origin, direction, tmax)
    if not len(t):
        return None, None
    return float(t[0]), int(idx[0])


def points_inside(mesh, pts, axis=1):
    """Parity test along +axis for closed-ish meshes. Returns a boolean array."""
    d = np.zeros(3)
    d[axis] = 1.0
    out = []
    for p in np.asarray(pts, float):
        t, _ = ray_hits(mesh, p + d * 1e-7, d)
        out.append(len(dedupe(t)) % 2 == 1)
    return np.array(out, bool)


def dedupe(t, tol=1e-7):
    out = []
    for x in t:
        if not out or abs(x - out[-1]) > tol:
            out.append(x)
    return out


def segment_tri_distance(mesh, p):
    """Minimum distance from point p to the mesh surface (vectorised, exact)."""
    if not len(mesh):
        return np.inf
    return float(np.min(point_triangle_distances(mesh.tris, np.asarray(p, float))))


def point_triangle_distances(tris, p):
    """Exact distances from p to each triangle (Ericson, Real-Time Collision Detection 5.1.5)."""
    a, b, c = tris[:, 0], tris[:, 1], tris[:, 2]
    ab, ac, ap = b - a, c - a, p - a
    d1 = np.einsum("ij,ij->i", ab, ap)
    d2 = np.einsum("ij,ij->i", ac, ap)
    bp = p - b
    d3 = np.einsum("ij,ij->i", ab, bp)
    d4 = np.einsum("ij,ij->i", ac, bp)
    cp = p - c
    d5 = np.einsum("ij,ij->i", ab, cp)
    d6 = np.einsum("ij,ij->i", ac, cp)
    vc = d1 * d4 - d3 * d2
    vb = d5 * d2 - d1 * d6
    va = d3 * d6 - d5 * d4
    res = np.empty((len(tris), 3))
    done = np.zeros(len(tris), bool)

    def put(mask, val):
        nonlocal done
        m = mask & ~done
        res[m] = val[m]
        done |= m

    put((d1 <= 0) & (d2 <= 0), a)
    put((d3 >= 0) & (d4 <= d3), b)
    with np.errstate(divide="ignore", invalid="ignore"):
        v = d1 / (d1 - d3)
        put((vc <= 0) & (d1 >= 0) & (d3 <= 0), a + v[:, None] * ab)
        put((d6 >= 0) & (d5 <= d6), c)
        w = d2 / (d2 - d6)
        put((vb <= 0) & (d2 >= 0) & (d6 <= 0), a + w[:, None] * ac)
        w2 = (d4 - d3) / ((d4 - d3) + (d5 - d6))
        put((va <= 0) & ((d4 - d3) >= 0) & ((d5 - d6) >= 0), b + w2[:, None] * (c - b))
        denom = 1.0 / (va + vb + vc)
        v3 = vb * denom
        w3 = vc * denom
        put(np.ones(len(tris), bool), a + ab * v3[:, None] + ac * w3[:, None])
    res = np.nan_to_num(res)
    return np.linalg.norm(res - p, axis=1)
