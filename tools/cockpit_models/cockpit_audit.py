#!/usr/bin/env python3
"""Cockpit cleanup audit: measures what zfight_check / missing / side / nose / poke / planarity do not.

Four detectors, all read-only on the generated geo and asset json of an aircraft:

  gaps    see-through cracks between the cockpit tub / canopy frame and the fuselage skin. Rays are cast from the crew
          eye(s) over the sphere, and from outside cameras ringed round the canopy at the rim and the sills. A ray that
          first meets the BACK of a face (it travelled through a crack into a hollow), or meets nothing at all (a hole
          to the sky), is a leak; leaks are clustered in 3-D, each cluster's width is measured by a bundle of parallel
          probe rays (gap size = the narrow side of the leaking patch).
  stray   disconnected small components (welded vertices) that touch nothing, or that sit inside the cabin although
          they belong to the old airframe: leftover frame stubs, post feet, isolated faces.
  jag     frame members whose faces interpenetrate skin / glass at a shallow angle or lie within 2 mm of them over an
          area (phasing), stepped sawtooth edges along rails (consecutive segments offset by small amounts), and frame
          members whose ends do not meet (gap or overlap > 3 mm at a joint).
  zfight  coplanar overlapping faces within 0.5 mm between ANY two parts (own cockpit / canopy, the airframe's own
          bones, glass vs everything, glass vs glass), same-colour clashes marked as harmless.

Every finding carries a position (vehicle-local blocks, x left, y up, z forward), a size in metres, and a score used
to rank it. Units are blocks = metres.

Usage:  cockpit_audit.py VID [VID...] [--all] [--only gaps,stray,jag,zfight] [--jobs N] [--top N] [-q]
                         [--json OUT.json] [--from-json IN.json] [--geo PATH] [--asset PATH]
                         [--report DIR [--per-id 4] [--commit HASH] [--no-images]] [--images DIR]
        --all runs every Elite Expansion 2 hand-built cockpit (FLEET). --report writes DIR/REPORT.md and the pictures in
        DIR/img (and includes DIR/validation.md when it exists). --geo audits another geo json (a deliberately broken copy)
        for the one id given. Read-only on the repository: it never writes a vehicle file. COCKPIT_AUDIT_GEN=DIR reads the
        geo / asset / data json from a copy of bvp/src/generated/resources instead (a frozen snapshot).
"""
import json, math, os, sys, time
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
# COCKPIT_AUDIT_GEN points at a copy of bvp/src/generated/resources (a frozen snapshot of the aircraft, so a re-audit is
# repeatable while other work edits the repository); BVP_TEX_DIR keeps pointing at the real textures
GEN = os.environ.get('COCKPIT_AUDIT_GEN') or os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles')

# ---------------------------------------------------------------- thresholds (metres unless noted)
TH = dict(
    zfight_gap=0.0005,        # coplanar within 0.5 mm
    zfight_cos=math.cos(math.radians(1.5)),
    zfight_area=2.5e-5,       # overlap area (m2) that counts (25 mm2)
    phase_gap=0.002,          # near-coplanar band for the phasing finding (2 mm)
    phase_area=4e-4,          # 4 cm2 of overlap
    shallow_deg=15.0,         # faces crossing at less than this angle interpenetrate "shallowly"
    shallow_len=0.04,         # intersection line at least this long
    joint_gap=0.003,          # member ends further than this apart do not meet (3 mm)
    joint_reach=0.06,         # ...but only members within this reach of each other are paired
    step_min=0.0003,          # lateral offset of collinear neighbours: steps between 0.3 mm and
    step_max=0.012,           # 12 mm are sawtooth
    stray_diag=0.35,          # components smaller than this (bbox diagonal) can be stray
    stray_touch=0.004,        # touching = within 4 mm of another component (or inside it)
    zone_pad=1.2,             # the cockpit zone: bbox of own geometry + glass, grown by this
    leak_probe=0.002,         # probe-ray grid step for gap sizes
    gap_max=0.20,             # slits wider than this are open cockpit, not a crack
    open_view=0.25,           # a sky hole wider than this is an open cabin ("open view"), not a crack
)

OWN_SUFFIX = ('__cockpit', '__canopy')


# ---------------------------------------------------------------- model loading

class Model:
    """All faces of an aircraft as triangles in vehicle-local blocks, tagged with bone, group and welded component."""

    def __init__(self, vid, geo_path=None, asset_path=None):
        self.vid = vid
        geo = json.load(open(geo_path or os.path.join(GEO, vid + '.geo.json'), encoding='utf-8'))
        self.asset = json.load(open(asset_path or os.path.join(ASSET, vid + '.json'), encoding='utf-8'))
        self.data = json.load(open(os.path.join(DATA, vid + '.json'), encoding='utf-8'))
        self.bones = geo['minecraft:geometry'][0]['bones']
        self.eyes = []
        for s in self.data.get('Seats', []):
            try:
                n = s['CameraPos']['EyeAttachment']
                self.eyes.append(np.array(self.data['Attachments'][n]['Position'], float))
            except (KeyError, TypeError):
                pass
        T, UV, B, G, Q = [], [], [], [], []       # tris, uvs, bone idx, group, poly idx
        self.bone_names = []
        self.skipped_cubes = 0
        for bone in self.bones:
            if bone.get('cubes'):
                self.skipped_cubes += len(bone['cubes'])
            pm = bone.get('poly_mesh')
            if not pm or not pm.get('polys'):
                continue
            bi = len(self.bone_names)
            self.bone_names.append(bone['name'])
            nm = bone['name']
            grp = 2 if nm.endswith('__canopy') else (1 if nm.endswith('__cockpit') else 0)
            P = np.array(pm['positions'], float) * np.array([1.0, 1.0, -1.0]) / 16.0
            U = np.array(pm['uvs'], float) if pm.get('uvs') else np.zeros((1, 2))
            for pi, poly in enumerate(pm['polys']):
                if len(poly) < 3:
                    continue
                idx = [v[0] for v in poly]
                tid = [v[2] if len(v) > 2 else 0 for v in poly]
                for k in range(1, len(idx) - 1):
                    T.append(P[[idx[0], idx[k], idx[k + 1]]])
                    UV.append(U[[tid[0], tid[k], tid[k + 1]]])
                    B.append(bi); G.append(grp); Q.append((bi, pi))
        self.T = np.array(T).reshape(-1, 3, 3)
        self.UV = np.array(UV).reshape(-1, 3, 2)
        self.bone = np.array(B, int)
        self.group = np.array(G, int)
        self.poly = np.array([q[1] for q in Q], int)
        cg = self.asset.get('CanopyGlass') or {}
        self.glass = np.array(cg.get('Triangles', []), float).reshape(-1, 3, 3)
        self.finish()

    def finish(self):
        T = self.T
        n = np.cross(T[:, 1] - T[:, 0], T[:, 2] - T[:, 0])
        ln = np.linalg.norm(n, axis=1)
        self.area = ln / 2
        self.ok = ln > 1e-10
        self.N = np.where(ln[:, None] > 1e-10, n / np.maximum(ln, 1e-10)[:, None], 0)
        self.D = (self.N * T[:, 0]).sum(1)
        self.comp = weld_components(T, self.ok)
        csize = np.bincount(self.comp)
        own = (self.group > 0) & (csize[self.comp] >= 8)         # (a stray one-face piece far away must not stretch the zone)
        pts = [T[own].reshape(-1, 3)] if own.any() else []
        if len(self.glass):
            pts.append(self.glass.reshape(-1, 3))
        if pts:
            P = np.concatenate(pts)
            self.own_lo, self.own_hi = P.min(0), P.max(0)
        else:
            self.own_lo = self.own_hi = np.zeros(3)
        self.zone_lo = self.own_lo - TH['zone_pad']
        self.zone_hi = self.own_hi + TH['zone_pad']
        c = T.mean(1)
        self.in_zone = np.all((c >= self.zone_lo) & (c <= self.zone_hi), axis=1)

    def texture(self):
        """the aircraft's texture (uint8 H x W x 4) or None when it is missing or not a real PNG (an LFS pointer)"""
        if not hasattr(self, '_tex'):
            self._tex = None
            d = os.environ.get('BVP_TEX_DIR') or os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources',
                                                              'assets/berts_vehicle_pack/textures/entity')
            p = os.path.join(d, self.vid + '.png')
            try:
                from PIL import Image
                self._tex = np.asarray(Image.open(p).convert('RGBA'))
            except Exception:
                self._tex = None
        return self._tex

    def tri_colour(self, uv):
        """mean texture colour under a triangle's three uvs and its centre (RGB floats), None without a texture"""
        tex = self.texture()
        if tex is None:
            return None
        H, W = tex.shape[:2]
        pts = np.concatenate([uv, uv.mean(0, keepdims=True)])
        x = np.clip((pts[:, 0] * W).astype(int), 0, W - 1)
        y = np.clip(((1 - pts[:, 1]) * H).astype(int), 0, H - 1)
        return tex[y, x, :3].astype(float).mean(0)

    def name(self, i):
        return self.bone_names[self.bone[i]]

    def tag(self, i):
        g = self.group[i]
        return ('cockpit' if g == 1 else 'canopy' if g == 2 else 'airframe') + ':' + self.name(i)


def weld_components(T, ok, tol=1e-5):
    """connected components of triangles that share a vertex position (within tol): union-find on quantised points"""
    n = len(T)
    parent = np.arange(n)

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    q = np.round(T.reshape(-1, 3) / tol).astype(np.int64)
    key = {}
    for i in range(n):
        if not ok[i]:
            continue
        for k in range(3):
            kk = (int(q[i * 3 + k][0]), int(q[i * 3 + k][1]), int(q[i * 3 + k][2]))
            j = key.setdefault(kk, i)
            if j != i:
                a, b = find(i), find(j)
                if a != b:
                    parent[a] = b
    roots = np.array([find(i) for i in range(n)])
    _, comp = np.unique(roots, return_inverse=True)
    return comp


# ---------------------------------------------------------------- geometry helpers

def tri_poly_clip_area(a2, b2):
    """area of the overlap of two 2-D triangles (3 x 2 each), Sutherland-Hodgman"""
    def orient(t):
        d = (t[1, 0] - t[0, 0]) * (t[2, 1] - t[0, 1]) - (t[1, 1] - t[0, 1]) * (t[2, 0] - t[0, 0])
        return t if d > 0 else t[::-1]
    a, b = orient(a2), orient(b2)
    poly = [p for p in a]
    for i in range(3):
        if not poly:
            return 0.0
        c0, c1 = b[i], b[(i + 1) % 3]
        e = c1 - c0
        out = []
        for j in range(len(poly)):
            p, q = poly[j], poly[(j + 1) % len(poly)]
            sp = e[0] * (p[1] - c0[1]) - e[1] * (p[0] - c0[0])
            sq = e[0] * (q[1] - c0[1]) - e[1] * (q[0] - c0[0])
            if sp >= 0:
                out.append(p)
            if (sp >= 0) != (sq >= 0):
                t = sp / (sp - sq)
                out.append(p + (q - p) * t)
        poly = out
    if len(poly) < 3:
        return 0.0
    P = np.array(poly)
    return 0.5 * abs(float((P[:, 0] * np.roll(P[:, 1], -1) - np.roll(P[:, 0], -1) * P[:, 1]).sum()))


def basis_of(n):
    n = np.asarray(n, float)
    h = np.array([1.0, 0, 0]) if abs(n[0]) < 0.9 else np.array([0, 1.0, 0])
    u = np.cross(n, h); u /= np.linalg.norm(u)
    return u, np.cross(n, u)


def moller_trumbore(O, Dv, T, tmin=1e-6):
    """rays (R x 3 origins, R x 3 dirs) against triangles T (M x 3 x 3): t (R x M, inf where no hit) and the sign of
    n.d (R x M, + when the face is seen from behind)"""
    e1 = T[:, 1] - T[:, 0]
    e2 = T[:, 2] - T[:, 0]
    h = np.cross(Dv[:, None, :], e2[None, :, :])
    a = (e1[None] * h).sum(2)
    ok = np.abs(a) > 1e-12
    f = np.where(ok, 1.0 / np.where(ok, a, 1.0), 0.0)
    s = O[:, None, :] - T[None, :, 0]
    u = f * (s * h).sum(2)
    q = np.cross(s, e1[None])
    v = f * (q * Dv[:, None, :]).sum(2)
    t = f * (q * e2[None]).sum(2)
    hit = ok & (u >= 0) & (v >= 0) & (u + v <= 1) & (t > tmin)
    return np.where(hit, t, np.inf), a


def point_tri_dist(P, T):
    """distances of points P (K x 3) to triangles T (M x 3 x 3): K x M (vectorised Ericson closest-point)"""
    a, b, c = T[:, 0][None], T[:, 1][None], T[:, 2][None]
    p = P[:, None, :]
    ab, ac, ap = b - a, c - a, p - a
    d1, d2 = (ab * ap).sum(2), (ac * ap).sum(2)
    bp = p - b
    d3, d4 = (ab * bp).sum(2), (ac * bp).sum(2)
    cp = p - c
    d5, d6 = (ab * cp).sum(2), (ac * cp).sum(2)
    vc = d1 * d4 - d3 * d2
    vb = d5 * d2 - d1 * d6
    va = d3 * d6 - d5 * d4
    with np.errstate(all='ignore'):
        den = 1.0 / (va + vb + vc)
        v = vb * den
        w = vc * den
        q = a + ab * v[..., None] + ac * w[..., None]            # interior
        # edge ab
        tab = np.clip(d1 / (d1 - d3), 0, 1)
        qab = a + ab * tab[..., None]
        # edge ac
        tac = np.clip(d2 / (d2 - d6), 0, 1)
        qac = a + ac * tac[..., None]
        # edge bc
        tbc = np.clip((d4 - d3) / ((d4 - d3) + (d5 - d6)), 0, 1)
        qbc = b + (c - b) * tbc[..., None]
    res = q.copy()
    m = (d1 <= 0) & (d2 <= 0); res[m] = np.broadcast_to(a, res.shape)[m]
    m = (d3 >= 0) & (d4 <= d3); res[m] = np.broadcast_to(b, res.shape)[m]
    m = (vc <= 0) & (d1 >= 0) & (d3 <= 0); res[m] = qab[m]
    m = (d6 >= 0) & (d5 <= d6); res[m] = np.broadcast_to(c, res.shape)[m]
    m = (vb <= 0) & (d2 >= 0) & (d6 <= 0); res[m] = qac[m]
    m = (va <= 0) & ((d4 - d3) >= 0) & ((d5 - d6) >= 0); res[m] = qbc[m]
    return np.linalg.norm(p - res, axis=2)


class Finding(dict):
    pass


def finding(cat, kind, pos, size, score, text, **extra):
    f = Finding(cat=cat, kind=kind, pos=[round(float(x), 4) for x in pos], size=round(float(size), 5),
                score=round(float(score), 3), text=text)
    f.update(extra)
    return f


# ---------------------------------------------------------------- clustering of raw hits into findings

def cluster_points(P, radius):
    """connected groups of points closer than radius: list of index arrays"""
    from scipy.spatial import cKDTree
    from scipy.sparse import coo_matrix
    from scipy.sparse.csgraph import connected_components
    P = np.asarray(P, float).reshape(-1, 3)
    if len(P) == 0:
        return []
    pairs = cKDTree(P).query_pairs(radius, output_type='ndarray')
    n = len(P)
    if len(pairs):
        g = coo_matrix((np.ones(len(pairs)), (pairs[:, 0], pairs[:, 1])), shape=(n, n))
    else:
        g = coo_matrix((n, n))
    _, lab = connected_components(g, directed=False)
    return [np.where(lab == k)[0] for k in range(lab.max() + 1)]


# ---------------------------------------------------------------- detector: z-fighting, phasing, shallow interpenetration

class Items:
    """triangles of the zone (airframe, cockpit, canopy) plus the glass, in one array set"""

    def __init__(self, m):
        z = np.where(m.in_zone & m.ok)[0]
        self.src = z
        gl = m.glass
        gn = np.cross(gl[:, 1] - gl[:, 0], gl[:, 2] - gl[:, 0]) if len(gl) else np.zeros((0, 3))
        gln = np.linalg.norm(gn, axis=1) if len(gl) else np.zeros(0)
        gok = gln > 1e-10
        gl, gn, gln = gl[gok], gn[gok], gln[gok]
        self.T = np.concatenate([m.T[z], gl]) if len(gl) else m.T[z]
        self.N = np.concatenate([m.N[z], gn / gln[:, None]]) if len(gl) else m.N[z]
        self.area = np.concatenate([m.area[z], gln / 2]) if len(gl) else m.area[z]
        self.kind = np.concatenate([m.group[z], np.full(len(gl), 3)])      # 0 airframe 1 cockpit 2 canopy 3 glass
        self.bone = np.concatenate([m.bone[z], np.full(len(gl), -1)])
        self.poly = np.concatenate([m.poly[z], np.arange(len(gl))])
        self.comp = np.concatenate([m.comp[z], np.full(len(gl), -1)])
        self.UV = np.concatenate([m.UV[z], np.zeros((len(gl), 3, 2))]) if len(gl) else m.UV[z]
        self.m = m
        self.lo = self.T.min(1)
        self.hi = self.T.max(1)

    def tag(self, i):
        k = self.kind[i]
        if k == 3:
            return 'glass'
        return ('cockpit' if k == 1 else 'canopy' if k == 2 else 'airframe') + ':' + self.m.bone_names[self.bone[i]]

    def same_colour(self, i, j):
        a, b = self.UV[i], self.UV[j]
        if self.kind[i] == 3 or self.kind[j] == 3:
            return False
        if np.ptp(a, 0).max() < 0.004 and np.ptp(b, 0).max() < 0.004 and np.abs(a.mean(0) - b.mean(0)).max() < 0.004:
            return True
        ca, cb = self.m.tri_colour(a), self.m.tri_colour(b)
        # two faces whose texture lands on (nearly) the same colour do not visibly fight
        return bool(ca is not None and np.abs(ca - cb).max() < 10.0 and np.ptp(a, 0).max() < 0.05 and np.ptp(b, 0).max() < 0.05)


def overlap_area(Ti, Ni, Tj):
    """area of the overlap of triangle j projected on triangle i's plane with triangle i"""
    u, v = basis_of(Ni)
    o = Ti[0]
    a = np.stack([(Ti - o) @ u, (Ti - o) @ v], 1)
    b = np.stack([(Tj - o) @ u, (Tj - o) @ v], 1)
    return tri_poly_clip_area(a, b)


def seg_in_tri(p, q, T, N):
    """length of the part of segment pq (points on T's plane) inside triangle T"""
    u, v = basis_of(N)
    o = T[0]
    A = np.stack([(T - o) @ u, (T - o) @ v], 1)
    P = np.array([(p - o) @ u, (p - o) @ v])
    Q = np.array([(q - o) @ u, (q - o) @ v])
    d = (A[1, 0] - A[0, 0]) * (A[2, 1] - A[0, 1]) - (A[1, 1] - A[0, 1]) * (A[2, 0] - A[0, 0])
    if d < 0:
        A = A[::-1]
    t0, t1 = 0.0, 1.0
    for i in range(3):
        c0, c1 = A[i], A[(i + 1) % 3]
        e = c1 - c0
        sp = e[0] * (P[1] - c0[1]) - e[1] * (P[0] - c0[0])
        sq = e[0] * (Q[1] - c0[1]) - e[1] * (Q[0] - c0[0])
        if sp < 0 and sq < 0:
            return 0.0
        if sp < 0:
            t0 = max(t0, sp / (sp - sq))
        elif sq < 0:
            t1 = min(t1, sp / (sp - sq))
        if t0 >= t1:
            return 0.0
    return float((t1 - t0) * np.linalg.norm(q - p))


def plane_cut(Tb, Na, Da):
    """segment where triangle Tb crosses the plane (Na, Da): (p, q) or None"""
    s = Tb @ Na - Da
    pts = []
    for i in range(3):
        j = (i + 1) % 3
        if (s[i] > 0) != (s[j] > 0) and s[i] != s[j]:
            t = s[i] / (s[i] - s[j])
            pts.append(Tb[i] + (Tb[j] - Tb[i]) * t)
    if len(pts) < 2:
        return None
    return pts[0], pts[1]


def pair_scan(m):
    """near-coplanar overlapping pairs and shallow interpenetrations. Returns raw hit records."""
    it = Items(m)
    n = len(it.T)
    pad = TH['phase_gap'] * 1.5
    hits = []
    cos_c = TH['zfight_cos']
    cos_s = math.cos(math.radians(TH['shallow_deg']))
    own = it.kind
    for i in range(n):
        sel = np.where((it.lo[i + 1:, 0] <= it.hi[i, 0] + pad) & (it.hi[i + 1:, 0] >= it.lo[i, 0] - pad) &
                       (it.lo[i + 1:, 1] <= it.hi[i, 1] + pad) & (it.hi[i + 1:, 1] >= it.lo[i, 1] - pad) &
                       (it.lo[i + 1:, 2] <= it.hi[i, 2] + pad) & (it.hi[i + 1:, 2] >= it.lo[i, 2] - pad))[0] + i + 1
        if not len(sel):
            continue
        ca = np.abs(it.N[sel] @ it.N[i])
        for j in sel[ca >= cos_s]:
            ki, kj = own[i], own[j]
            if ki == 0 and kj == 0 and it.comp[i] == it.comp[j] and it.bone[i] == it.bone[j] and False:
                continue
            # the smaller triangle lies in the larger one's plane?
            big, small = (i, j) if it.area[i] >= it.area[j] else (j, i)
            d = np.abs((it.T[small] - it.T[big][0]) @ it.N[big])
            dmax = float(d.max())
            cosang = float(it.N[i] @ it.N[j])
            if abs(cosang) >= cos_c and dmax <= TH['phase_gap']:
                same = cosang > 0
                glass_pair = ki == 3 or kj == 3
                if not same and not glass_pair:
                    continue                                   # back to back: each shows from its own side only
                if ki == 3 and kj == 3 and not same:
                    # glass is stored with both windings by design: an exact twin is not a clash
                    pass
                if dmax > TH['zfight_gap'] and not (ki in (2, 3) or kj in (2, 3) or {ki, kj} == {0, 1}):
                    continue                                   # (the 0.5-2 mm phasing band: frames, glass, tub vs skin only)
                A = overlap_area(it.T[big], it.N[big], it.T[small])
                if A < (TH['zfight_area'] if dmax <= TH['zfight_gap'] else TH['phase_area']):
                    continue
                if ki == 3 and kj == 3 and not same and dmax <= 1e-5 and A > 0.9 * min(it.area[i], it.area[j]):
                    continue                                   # the glass's back-face twin
                hits.append(('zfight' if dmax <= TH['zfight_gap'] else 'phase', i, j, dmax, A, cosang))
            elif abs(cosang) < cos_c and (ki in (1, 2)) != (kj in (1, 2)) and (ki == 3 or kj == 3 or ki == 0 or kj == 0):
                # shallow crossing of a cockpit / canopy face with the skin or the glass
                a, b = (i, j) if ki in (1, 2) else (j, i)
                sa = it.T[b] @ it.N[a] - float(it.N[a] @ it.T[a][0])
                if not (sa.max() > 0 and sa.min() < 0):
                    continue
                sb = it.T[a] @ it.N[b] - float(it.N[b] @ it.T[b][0])
                if not (sb.max() > 0 and sb.min() < 0):
                    continue
                seg = plane_cut(it.T[b], it.N[a], float(it.N[a] @ it.T[a][0]))
                if seg is None:
                    continue
                L = seg_in_tri(seg[0], seg[1], it.T[a], it.N[a])
                if L < TH['shallow_len']:
                    continue
                ang = math.degrees(math.acos(min(1.0, abs(cosang))))
                hits.append(('shallow', a, b, L, ang, cosang))
    return it, hits


def summarise_pairs(m, it, hits):
    out = []
    groups = {}
    for kind, i, j, v1, v2, c in hits:
        ta, tb = sorted((it.tag(i), it.tag(j)))
        groups.setdefault((kind, ta, tb), []).append((i, j, v1, v2, c))
    for (kind, ta, tb), rec in groups.items():
        cen = np.array([(it.T[i].mean(0) + it.T[j].mean(0)) / 2 for i, j, *_ in rec])
        for idx in cluster_points(cen, 0.06):
            sub = [rec[k] for k in idx]
            P = cen[idx]
            pos = P.mean(0)
            ext = float(np.linalg.norm(P.max(0) - P.min(0)))
            if kind == 'zfight':
                area = sum(r[3] for r in sub)
                same = all(it.same_colour(r[0], r[1]) for r in sub)
                dmax = max(r[2] for r in sub)
                txt = f'{ta} / {tb}: {len(sub)} coplanar overlaps, {area * 1e4:.1f} cm2, <= {dmax * 1e3:.2f} mm apart'
                if same:
                    txt += ' (same colour: not visible)'
                sc = 0.0 if same else math.log10(1 + area * 1e4) * 2 + 1 + (2 if 3 in (it.kind[sub[0][0]], it.kind[sub[0][1]]) else 0)
                own_pair = not (ta.startswith('airframe') and tb.startswith('airframe'))
                if not own_pair:
                    sc *= 0.3
                out.append(finding('zfight', 'coplanar', pos, ext, sc, txt, n=len(sub), area_cm2=round(area * 1e4, 2),
                                   parts=[ta, tb], harmless=bool(same), scope='new' if own_pair else 'airframe'))
            elif kind == 'phase':
                area = sum(r[3] for r in sub)
                dmax = max(r[2] for r in sub)
                txt = f'{ta} / {tb}: {len(sub)} faces within {dmax * 1e3:.1f} mm of each other over {area * 1e4:.1f} cm2'
                out.append(finding('jag', 'phasing', pos, ext, math.log10(1 + area * 1e4) * 1.5 + 1, txt, n=len(sub),
                                   area_cm2=round(area * 1e4, 2), parts=[ta, tb]))
            else:
                L = sum(r[2] for r in sub)
                ang = min(r[3] for r in sub)
                txt = f'{ta} crosses {tb} at {ang:.1f} deg over {L * 100:.0f} cm of intersection ({len(sub)} face pairs)'
                out.append(finding('jag', 'shallow', pos, L, math.log10(1 + L * 100) * 1.5 + max(0.0, 8 - ang) * 0.15, txt,
                                   n=len(sub), angle=round(ang, 2), parts=[ta, tb]))
    return out


def detect_pairs(m):
    it, hits = pair_scan(m)
    return summarise_pairs(m, it, hits)


# ---------------------------------------------------------------- detector: gaps (depth / id buffer slit finding)

class Cam:
    """pinhole camera in vehicle-local blocks. fwd = unit look direction, up hint (0, 1, 0)."""

    def __init__(self, pos, fwd, fov_deg, w, h, up=(0.0, 1.0, 0.0)):
        self.pos = np.array(pos, float)
        f = np.array(fwd, float); f /= np.linalg.norm(f)
        up = np.array(up, float)
        if abs(f @ up) > 0.98:
            up = np.array([0.0, 0.0, 1.0]) if abs(f[2]) < 0.9 else np.array([1.0, 0.0, 0.0])
        r = np.cross(f, up); r /= np.linalg.norm(r)       # x is left, so f x up is the camera's right (as eyeview)
        u = np.cross(r, f)
        self.R = np.stack([r, u, f])
        self.w, self.h = w, h
        self.f = (w / 2) / math.tan(math.radians(fov_deg) / 2)
        self.fwd = f

    def view(self, P):
        return (np.asarray(P, float) - self.pos) @ self.R.T

    def project(self, P):
        v = self.view(P)
        return np.stack([self.w / 2 + self.f * v[..., 0] / v[..., 2], self.h / 2 - self.f * v[..., 1] / v[..., 2]], -1), v[..., 2]

    def unproject(self, x, y, z):
        """world point at pixel (x, y) with view depth z"""
        v = np.array([(x - self.w / 2) * z / self.f, -(y - self.h / 2) * z / self.f, z])
        return self.pos + v @ self.R


NEAR = 0.03


def rasterize(cam, T, front_ok=None):
    """depth (view z, inf = empty) and triangle id (-1) for triangles T (M x 3 x 3), nearest wins"""
    H, W = cam.h, cam.w
    depth = np.full((H, W), np.inf, np.float32)
    ident = np.full((H, W), -1, np.int32)
    V = (T - cam.pos) @ cam.R.T
    zmin = V[:, :, 2].min(1)
    zmax = V[:, :, 2].max(1)
    for t in np.where(zmax > NEAR)[0]:
        vs = V[t]
        polys = [vs]
        if zmin[t] <= NEAR:                                   # clip against the near plane
            poly = []
            for i in range(3):
                a, b = vs[i], vs[(i + 1) % 3]
                if a[2] > NEAR:
                    poly.append(a)
                if (a[2] > NEAR) != (b[2] > NEAR):
                    poly.append(a + (NEAR - a[2]) / (b[2] - a[2]) * (b - a))
            polys = [np.array([poly[0], poly[k], poly[k + 1]]) for k in range(1, len(poly) - 1)]
        for cv in polys:
            sx = W / 2 + cam.f * cv[:, 0] / cv[:, 2]
            sy = H / 2 - cam.f * cv[:, 1] / cv[:, 2]
            x0 = max(0, int(math.floor(sx.min()))); x1 = min(W - 1, int(math.ceil(sx.max())))
            y0 = max(0, int(math.floor(sy.min()))); y1 = min(H - 1, int(math.ceil(sy.max())))
            if x1 < x0 or y1 < y0:
                continue
            den = (sy[1] - sy[2]) * (sx[0] - sx[2]) + (sx[2] - sx[1]) * (sy[0] - sy[2])
            if abs(den) < 1e-12:
                continue
            X = np.arange(x0, x1 + 1) + 0.5
            Y = (np.arange(y0, y1 + 1) + 0.5)[:, None]
            w0 = ((sy[1] - sy[2]) * (X - sx[2]) + (sx[2] - sx[1]) * (Y - sy[2])) / den
            w1 = ((sy[2] - sy[0]) * (X - sx[2]) + (sx[0] - sx[2]) * (Y - sy[2])) / den
            w2 = 1 - w0 - w1
            m = (w0 >= -2e-3) & (w1 >= -2e-3) & (w2 >= -2e-3)
            if not m.any():
                continue
            z = 1.0 / (w0 / cv[0, 2] + w1 / cv[1, 2] + w2 / cv[2, 2])
            blk = depth[y0:y1 + 1, x0:x1 + 1]
            m &= z < blk
            if not m.any():
                continue
            blk[m] = z[m]
            ident[y0:y1 + 1, x0:x1 + 1][m] = t
    return depth, ident


def near_tris(T, centre, radius):
    """indices of triangles whose bounding box comes within radius of centre"""
    lo, hi = T.min(1), T.max(1)
    d = np.maximum(np.maximum(lo - centre, centre - hi), 0)
    return np.where(np.linalg.norm(d, axis=1) < radius)[0]


class Scene:
    """the triangle set the buffer detectors look at: zone airframe + cockpit + canopy faces and the glass (as opaque)"""

    def __init__(self, m):
        it = Items(m)
        self.it = it
        self.T = it.T
        self.N = it.N
        self.kind = it.kind
        self.m = m
        # a global index for glass vs other: kind 3

    def label(self, i):
        return self.it.tag(i) if i >= 0 else 'sky'

    def tags(self):
        if not hasattr(self, '_tags'):
            self._tags = np.array([self.it.tag(i) for i in range(len(self.T))])
        return self._tags


def clearance(scene, P, tag_a, tag_b, r=0.05):
    """how far apart the two parts of a crack are near P: the median over sample points of each part (within r of P)
    of the distance to the other part, the smaller of the two directions; None when either part is not near"""
    T = scene.T
    lo, hi = T.min(1), T.max(1)
    near = np.where(np.all(hi >= P - r, axis=1) & np.all(lo <= P + r, axis=1))[0]
    ia = [i for i in near if scene.label(int(i)) == tag_a]
    ib = [i for i in near if scene.label(int(i)) == tag_b]
    if not ia or not ib:
        return None
    # the partner's triangles a little wider, so the nearest face is found even when it is bigger than the window
    lo2, hi2 = lo, hi
    wide = np.where(np.all(hi2 >= P - 3 * r, axis=1) & np.all(lo2 <= P + 3 * r, axis=1))[0]
    ja = [i for i in wide if scene.label(int(i)) == tag_a]
    jb = [i for i in wide if scene.label(int(i)) == tag_b]
    res = []
    for src, dst in ((ia, jb), (ib, ja)):
        S = sample_points(T[src], cap=120)
        S = S[np.linalg.norm(S - P, axis=1) < 2 * r]
        if not len(S):
            continue
        d = point_tri_dist(S, T[dst]).min(axis=1)
        res.append(float(np.median(d)))
    return min(res) if res else None


def slits(cam, depth, ident, scene, cfg, label):
    """find thin deep slits in the depth image, and big sky / hollow regions. Returns finding dicts"""
    from scipy import ndimage as ndi
    H, W = depth.shape
    Zf = np.where(np.isfinite(depth), depth, 300.0).astype(np.float32)
    typical = cfg['typical']
    s = int(np.clip(cfg['gap_max'] * cam.f / typical, 9, min(H, W) // 2))
    if s % 2 == 0:
        s += 1
    er = ndi.minimum_filter1d(ndi.minimum_filter1d(Zf, s, axis=0), s, axis=1)
    op = ndi.maximum_filter1d(ndi.maximum_filter1d(er, s, axis=0), s, axis=1)
    top = Zf - op
    mask = top > (cfg['rise'] + 0.01 * op)
    out = []
    edge = 4
    lab, n = ndi.label(mask, structure=np.ones((3, 3)))
    if n == 0:
        return out
    objs = ndi.find_objects(lab)
    for k, sl in enumerate(objs, 1):
        ys, xs = sl
        hh, ww = ys.stop - ys.start, xs.stop - xs.start
        area = int((lab[sl] == k).sum())
        if area < cfg['min_px'] or max(hh, ww) < cfg['min_len_px']:
            continue
        if ys.start < edge or xs.start < edge or ys.stop > H - edge or xs.stop > W - edge:
            continue
        comp = lab[sl] == k
        dist = ndi.distance_transform_edt(np.pad(comp, 1))[1:-1, 1:-1]
        wid_px = 2 * float(dist.max())
        # neighbours
        pad = 5
        y0, y1 = max(0, ys.start - pad), min(H, ys.stop + pad)
        x0, x1 = max(0, xs.start - pad), min(W, xs.stop + pad)
        sub = lab[y0:y1, x0:x1] == k
        ring = ndi.binary_dilation(sub, iterations=4) & ~ndi.binary_dilation(sub, iterations=1)
        ids = ident[y0:y1, x0:x1][ring]
        rz = depth[y0:y1, x0:x1][ring]
        if (ids < 0).mean() > 0.2 or len(ids) < 8:
            continue                                   # a silhouette against the sky, not a crack
        fin = rz[np.isfinite(rz)]
        if np.percentile(fin, 90) - np.percentile(fin, 10) > 0.06 + 0.05 * float(np.median(fin)):
            continue                                   # the two sides lie at different depths: a line-of-sight window
        ids = ids[ids >= 0]
        if not len(ids):
            continue
        ids_u, cnt = np.unique(ids, return_counts=True)
        kinds = scene.kind[ids_u]
        w_own = int(cnt[kinds >= 1].sum()); w_air = int(cnt[kinds == 0].sum())
        yy, xx = np.nonzero(comp)
        cy, cx = yy.mean() + ys.start, xx.mean() + xs.start
        zc = float(op[int(round(cy)), int(round(cx))])
        zc = min(zc, 60.0)
        pxm = zc / cam.f
        P = cam.unproject(cx + 0.5, cy + 0.5, zc)
        rise = float(top[sl][comp].max())
        sky = bool((depth[sl][comp] == np.inf).mean() > 0.5) or rise > 100.0
        width = max(wid_px * pxm, pxm)
        length = float(np.hypot(hh, ww)) * pxm
        scope = 'new' if w_own > 0 else 'airframe'
        pairs = sorted(set(scene.label(int(i)) for i in ids_u[np.argsort(-cnt)][:4]))
        # which two sides: most common own and most common airframe neighbour
        order = np.argsort(-cnt)
        sides = []
        if w_own > 0 and w_air > 0:                        # the new part first, then the airframe piece it meets
            for want_own in (True, False):
                for i in ids_u[order]:
                    if (scene.kind[i] >= 1) == want_own:
                        sides.append(scene.label(int(i)))
                        break
        for i in ids_u[order][:6]:
            tg = scene.label(int(i))
            if tg not in sides:
                sides.append(tg)
        clear = clearance(scene, P, sides[0], sides[1]) if len(sides) > 1 else None
        if clear is not None and clear > 0.05:
            clear = None                                   # not the same crack (the sides are far apart here)
        if w_own > 0 and w_air > 0:
            kind = 'rim gap'
            base = 4.0
        elif w_own > 0:
            kind = 'internal slit'
            base = 1.0
        else:
            kind = 'airframe seam'
            base = 0.0
        # glass is clear: a crack beside a glass pane that only shows the sky is invisible; one that shows the tub,
        # the hull's inside or a hollow is a dark line
        glass_side = bool((kinds[np.argsort(-cnt)][:2] == 3).any())
        if glass_side:
            base = min(base, 2.5)
        sky_bonus = 0.0 if glass_side else (2.0 if sky else 0.0)
        if glass_side and sky:
            base = min(base, 1.0)
        gap = max(width, clear) if clear else width        # the visible opening can only be narrower than the gap
        size_txt = (f'{gap * 1e3:.1f} mm gap (opening seen {width * 1e3:.1f} mm)' if clear and clear > width * 1.15
                    else f'{gap * 1e3:.1f} mm wide')
        score_val = 0.0 if scope == 'airframe' else (base + 1.2 * math.log10(1 + length * 100) + sky_bonus +
                                                      1.0 * math.exp(-gap * 60))
        if gap > TH['gap_max'] and kind == 'rim gap':          # wider than a crack: an opening
            kind, score_val = 'open view', min(score_val, 3.0)
        if label.startswith('eye'):
            score_val *= eye_visibility(P - cam.pos)
        out.append(finding('gaps', kind, P, gap, score_val,
                           f'{kind} {size_txt} x {length * 100:.0f} cm long, {"sees the sky" if sky else f"{rise * 100:.0f} cm deep"}'
                           f' [{label}] between {sides[0]} and {sides[1] if len(sides) > 1 else "?"}',
                           width_mm=round(width * 1e3, 2), clear_mm=round(clear * 1e3, 2) if clear else None,
                           length_cm=round(length * 100, 1), sky=sky, depth_cm=round(rise * 100, 1),
                           view=label, scope=scope, sides=sides[:3], cam=[cam.pos.round(3).tolist(), cam.fwd.round(4).tolist()]))
    return out


def sky_regions(cam, depth, ident, scene, cfg, label):
    """regions of the view with nothing in them where there should be a cabin: sky seen from a crew eye"""
    from scipy import ndimage as ndi
    H, W = depth.shape
    sky = ~np.isfinite(depth)
    lab, n = ndi.label(sky)
    out = []
    for k, sl in enumerate(ndi.find_objects(lab), 1):
        comp = lab[sl] == k
        area = int(comp.sum())
        if area < 4:
            continue
        yy, xx = np.nonzero(comp)
        cy, cx = yy.mean() + sl[0].start, xx.mean() + sl[1].start
        # distance of the surrounding surface
        ring = ndi.binary_dilation(lab == k, iterations=3) & ~(lab == k)
        zs = depth[ring]
        zs = zs[np.isfinite(zs)]
        z = float(np.median(zs)) if len(zs) else 5.0
        pxm = z / cam.f
        P = cam.unproject(cx + 0.5, cy + 0.5, z)
        w = 2 * float(ndi.distance_transform_edt(np.pad(comp, 1)).max()) * pxm
        l = float(np.hypot(sl[0].stop - sl[0].start, sl[1].stop - sl[1].start)) * pxm
        rid = ident[ring]
        kinds = set(int(k) for k in scene.kind[rid[rid >= 0]].tolist())
        names = sorted(set(scene.label(int(i)) for i in np.unique(rid[rid >= 0])[:8]))
        out.append((P, w, l, area * pxm * pxm, z, kinds, names))
    return out


def eye_visibility(d):
    """how likely a pilot is to look at a direction d (from the eye): ahead and to the sides within 60 degrees of the
    nose count in full, the beam a little less, behind the shoulder much less"""
    c = float(d[2] / max(float(np.linalg.norm(d)), 1e-9))
    return 1.0 if c > 0.5 else 0.85 if c > -0.2 else 0.6


def crew_eyes(m):
    pad = 0.35
    out = []
    for e in m.eyes:
        if np.all(e >= m.own_lo - pad) and np.all(e <= m.own_hi + pad):
            if not any(np.linalg.norm(e - o) < 0.05 for o in out):
                out.append(e)
    return out


def inside_solid(sc, p):
    """True when point p is inside some airframe component (odd crossings of a ray with that component)"""
    ids = np.where(sc.kind == 0)[0]
    if not len(ids):
        return False
    t, _ = moller_trumbore(p[None], np.array([[0.5773, 0.5101, 0.6373]]) / np.linalg.norm([0.5773, 0.5101, 0.6373]),
                           sc.T[ids])
    hits = np.where(np.isfinite(t[0]))[0]
    if not len(hits):
        return False
    cnt = np.bincount(sc.it.comp[ids[hits]])
    return bool((cnt % 2 == 1).any())


def cube_cams(pos, res, fov=92.0):
    out = []
    for name, f in (('fwd', (0, 0, 1)), ('back', (0, 0, -1)), ('left', (1, 0, 0)), ('right', (-1, 0, 0)),
                    ('up', (0, 1, 0)), ('down', (0, -1, 0))):
        out.append((name, Cam(pos, f, fov, res, res)))
    return out


def outside_cams(m, res):
    """cameras ringed round the canopy, looking at the rim from outside: azimuth every 30 degrees at three
    elevations plus one overhead and one each from front and rear"""
    c = (m.own_lo + m.own_hi) / 2
    half = (m.own_hi - m.own_lo) / 2
    out = []
    rad = max(2.6, 0.9 * float(np.linalg.norm(half)) + 1.2)
    for el in (12.0, 40.0, 68.0):
        for az in range(0, 360, 30):
            a, e = math.radians(az), math.radians(el)
            d = np.array([math.sin(a) * math.cos(e), math.sin(e), math.cos(a) * math.cos(e)])
            out.append((f'out az{az} el{int(el)}', c + d * rad, c))
    out.append(('out top', c + np.array([0.0, rad, 0.0]), c))
    return out, rad


def detect_gaps(m, res_eye=1400, res_out=1500, log=None):
    sc = Scene(m)
    cfg = dict(rise=0.025, min_px=5, min_len_px=6, gap_max=TH['gap_max'], typical=1.0)
    found = []
    eyes = crew_eyes(m)
    sky_hits = []
    for ei, e in enumerate(eyes[:3]):
        idx = near_tris(sc.T, e, 7.0)
        for name, cam in cube_cams(e, res_eye):
            depth, ident = rasterize(cam, sc.T[idx])
            ident = np.where(ident >= 0, idx[np.maximum(ident, 0)], -1)
            lab = f'eye{ei} {name}'
            found += slits(cam, depth, ident, sc, cfg, lab)
            for P, w, l, area, z, kinds, names in sky_regions(cam, depth, ident, sc, cfg, lab):
                sky_hits.append((P, w, l, area, lab, e, kinds, names))
    outs, rad = outside_cams(m, res_out)
    cfg_o = dict(cfg, typical=rad)
    for lab, pos, tgt in outs:
        if inside_solid(sc, pos):
            continue
        cam = Cam(pos, tgt - pos, 2 * math.degrees(math.atan(1.5 / rad)) if rad > 0 else 40, res_out, res_out)
        idx = near_tris(sc.T, tgt, 4.0 + 0.0)
        depth, ident = rasterize(cam, sc.T[idx])
        ident = np.where(ident >= 0, idx[np.maximum(ident, 0)], -1)
        found += slits(cam, depth, ident, sc, cfg_o, lab)
    # sky seen from the crew eye
    for P, w, l, area, lab, e, kinds, names in sky_hits:
        d = (P - e)
        glass_only = kinds <= {3}
        # a hole between two glass panes only shows as an untinted line (the glass is clear): minor. A hole bounded
        # by frame, tub or skin shows the outside where the aircraft is solid.
        kind = 'glass seam' if glass_only else 'sky hole'
        sc_ = (0.8 + 0.4 * math.log10(1 + area * 1e4)) if glass_only else (5.0 + 1.5 * math.log10(1 + area * 1e4))
        if not glass_only and w > TH['open_view']:
            kind, sc_ = 'open view', min(sc_, 3.0)             # a wide opening (an open cabin), not a crack
        sc_ *= eye_visibility(d)
        found.append(finding('gaps', kind, P, w, sc_,
                             f'{"untinted seam between glass panes" if glass_only else "sky visible from the eye"} through a '
                             f'{w * 1e3:.0f} mm x {l * 1e3:.0f} mm hole [{lab}] next to {", ".join(names[:3])}',
                             width_mm=round(w * 1e3, 1), length_cm=round(l * 100, 1), sky=not glass_only, view=lab, scope='new',
                             cam=[e.round(3).tolist(), (d / np.linalg.norm(d)).round(4).tolist()]))
    return merge_gap_findings(found)


def merge_gap_findings(found):
    if not found:
        return []
    out = []
    # one finding per crack: slits of the same kind between the same two parts, within 12 cm of each other (a long
    # crack is cut into pieces by the camera views and by the depth-step filter)
    groups = {}
    for f in found:
        groups.setdefault((f['kind'], f.get('scope'), tuple(sorted((f.get('sides') or [])[:2]))), []).append(f)
    for fs in groups.values():
        P = np.array([f['pos'] for f in fs])
        for idx in cluster_points(P, 0.12):
            grp = [fs[i] for i in idx]
            grp.sort(key=lambda f: -f['score'])
            best = dict(grp[0])
            views = sorted(set(g['view'] for g in grp))
            best['views'] = views[:6]
            best['n_views'] = len(views)
            best['n_pieces'] = len(grp)
            best['size'] = round(float(np.median([g['size'] for g in grp])), 5)
            pts = P[idx]
            best['span_cm'] = round(float(np.linalg.norm(pts.max(0) - pts.min(0))) * 100 + best.get('length_cm', 0), 1)
            best['score'] = round(best['score'] + 0.3 * min(len(views), 4) + 0.6 * math.log10(len(grp)), 3)
            out.append(Finding(best))
    return out


# ---------------------------------------------------------------- pictures

KIND_RGB = {0: (150, 156, 164), 1: (80, 130, 200), 2: (232, 150, 60), 3: (110, 220, 230)}


def flat_view(sc, cam, centre, radius, hl=None):
    """flat-colour diagnostic render: airframe grey, cockpit blue, canopy orange, glass cyan; empty = magenta;
    the faces in hl (scene indices) yellow"""
    idx = near_tris(sc.T, centre, radius)
    depth, ident = rasterize(cam, sc.T[idx])
    ident = np.where(ident >= 0, idx[np.maximum(ident, 0)], -1)
    img = np.zeros((cam.h, cam.w, 3), np.float32)
    img[:] = (230, 40, 200)
    hit = ident >= 0
    k = sc.kind[np.maximum(ident, 0)]
    base = np.zeros((cam.h, cam.w, 3), np.float32)
    for kk, col in KIND_RGB.items():
        base[hit & (k == kk)] = col
    if hl is not None and len(hl):                    # the faces the finding is about
        base[hit & np.isin(ident, hl)] = (255, 235, 0)
    n = sc.N[np.maximum(ident, 0)]
    d = sc.T[np.maximum(ident, 0)].mean(2) - cam.pos
    d /= np.maximum(np.linalg.norm(d, axis=2, keepdims=True), 1e-9)
    lam = 0.45 + 0.55 * np.abs((n * d).sum(2))
    img[hit] = (base * lam[..., None])[hit]
    return img.astype(np.uint8), depth, ident


def textured_view(m, cam, centre, radius, glass=True):
    """textured render with the repository's own eyeview rasteriser (needs the aircraft's texture png)"""
    import eyeview as EV
    from PIL import Image
    tex_path = os.path.join(EV.TEX, m.vid + '.png')
    tex = np.asarray(Image.open(tex_path).convert('RGBA')).astype(np.float32) / 255.0
    ecam = EV.Camera(cam.pos, 0, 0, 60, cam.w, cam.h)
    ecam.R = cam.R
    ecam.focal = cam.f
    r = EV.Raster(ecam)
    sel = np.where(m.ok)[0]
    cen = m.T[sel].mean(1)
    lo, hi = m.T[sel].min(1), m.T[sel].max(1)
    d = np.maximum(np.maximum(lo - centre, centre - hi), 0)
    keep = sel[np.linalg.norm(d, axis=1) < radius]
    r.draw(m.T[keep], m.UV[keep], tex)
    if glass and len(m.glass):
        g = m.glass
        d = np.maximum(np.maximum(g.min(1) - centre, centre - g.max(1)), 0)
        g = g[np.linalg.norm(d, axis=1) < radius]
        tint = tuple(c / 255.0 for c in m.asset['CanopyGlass']['Tint']) if m.asset.get('CanopyGlass', {}).get('Tint') \
            else (0.75, 0.85, 0.8)
        if len(g):
            r.draw(g, np.zeros((len(g), 3, 2)), np.ones((1, 1, 4), np.float32), shade=False, alpha_test=False,
                   blend=0.18, tint=tint)
    return np.asarray(r.image())


def mark(img, cam, P, text, colour=(255, 40, 40), r=22):
    from PIL import Image, ImageDraw
    im = Image.fromarray(img)
    d = ImageDraw.Draw(im)
    s, z = cam.project(P)
    if z > 0:
        x, y = float(s[0]), float(s[1])
        d.ellipse([x - r, y - r, x + r, y + r], outline=colour, width=3)
        d.line([x - r - 10, y, x - r + 4, y], fill=colour, width=3)
        d.line([x + r - 4, y, x + r + 10, y], fill=colour, width=3)
    d.rectangle([0, 0, im.width, 20], fill=(0, 0, 0))
    d.text((4, 4), text, fill=(255, 255, 255))
    return np.asarray(im)


def render_finding(m, f, path, sc=None, size=(800, 560)):
    """one picture per finding: two views (each textured | flat colours) of the marked point, chosen among an outside
    close-up, the crew eye and from above, preferring those that can actually see the point. Returns the path."""
    from PIL import Image
    sc = sc or Scene(m)
    P = np.array(f['pos'], float)
    c = (m.own_lo + m.own_hi) / 2
    eyes = crew_eyes(m) or list(m.eyes[:1])
    out = P - c
    out[1] = max(out[1], 0.0) * 0.6 + 0.45
    n = np.linalg.norm(out)
    out = out / n if n > 1e-6 else np.array([1.0, 0.5, 0.0])
    ext = float(max(f.get('size', 0.05), 0.0))
    dist = 0.75 + min(1.0, ext * 2.0)
    cands = [('outside close-up', Cam(P + out * dist, -out, 34, size[0], size[1]), P, 2.2)]
    # the detecting camera, when it was a crew eye or an outside view
    if f.get('cam'):
        pos, fw = np.array(f['cam'][0], float), np.array(f['cam'][1], float)
        d = P - pos
        dd = float(np.linalg.norm(d))
        if dd > 0.3:
            near = pos + d / dd * max(0.0, dd - (0.9 if str(f.get('view', '')).startswith('out') else dd))
            lab = 'from the eye' if str(f.get('view', '')).startswith('eye') else 'detecting view'
            cands.insert(0, (lab, Cam(near if lab == 'detecting view' else pos, d, 40 if lab == 'detecting view' else 46,
                                      size[0], size[1]), P if lab == 'detecting view' else pos, 6.0))
    if eyes:
        e = min(eyes, key=lambda q: np.linalg.norm(q - P))
        de = P - e
        if np.linalg.norm(de) > 0.05 and np.all(P >= m.own_lo - 0.15) and np.all(P <= m.own_hi + 0.15):
            cands.append(('from the eye', Cam(e, de, 46, size[0], size[1]), e, 6.0))
    up = P + np.array([0.0, 0.9 + min(0.8, ext), 0.0])
    cands.append(('from above', Cam(up, P - up, 50, size[0], size[1], up=(0.0, 0.0, 1.0)), P, 2.6))
    hl = None
    if f.get('parts') and f['cat'] in ('zfight', 'jag'):
        tags = sc.tags()
        R = max(0.05, ext / 2 + 0.03)
        hl = np.where(np.isin(tags, f['parts']) & (np.linalg.norm(sc.T.mean(1) - P, axis=1) < R))[0]
    chosen, rest = [], []
    for lab, cam, cen, rad in cands:
        flat, depth, _ = flat_view(sc, cam, cen, rad, hl)
        s, z = cam.project(P)
        dd = float(np.linalg.norm(P - cam.pos))
        ok = False
        if z > 0 and 0 <= s[0] < cam.w and 0 <= s[1] < cam.h:
            win = depth[int(max(0, s[1] - 3)):int(s[1] + 4), int(max(0, s[0] - 3)):int(s[0] + 4)]
            fin = win[np.isfinite(win)]
            ok = bool((len(fin) and abs(float(fin.min()) - z) < 0.08) or (f.get('sky') and np.isinf(win).any()))
        (chosen if ok else rest).append((lab, cam, cen, rad, flat))
    pick = (chosen + rest)[:2]
    rows = []
    for lab, cam, cen, rad, flat in pick:
        try:
            tex = textured_view(m, cam, cen, rad)
        except Exception:                           # no texture: flat only
            tex = flat
        occl = '' if (lab, cam, cen, rad, flat) in chosen else ' (point hidden here)'
        title = f'{m.vid}  {lab}{occl}  {f["cat"]}/{f["kind"]}  {f["text"][:90]}'
        rows.append(np.concatenate([mark(tex, cam, P, title),
                                    mark(flat, cam, P, 'grey airframe, blue cockpit, orange canopy, cyan glass, yellow = faces concerned, magenta = nothing')], 1))
    Image.fromarray(np.concatenate(rows, 0)).save(path)
    return path


# ---------------------------------------------------------------- detector: stray geometry

CONTROL_BONES = ('stick', 'pedal', 'throttle', 'yoke', 'cyclic', 'collective', 'rudder', 'control', 'lever', 'pitch',
                 'roll', 'wheel_', 'gear', 'nozzle', 'flap', 'aileron', 'elevator', 'brake', 'prop', 'rotor', 'door',
                 'hatch', 'turret', 'gun', 'canopy_')


def is_moving_bone(name):
    n = name.lower()
    if n.endswith('__canopy') or n.endswith('__cockpit') or n.endswith('__hull'):
        return False
    return any(k in n.split('__')[-1] for k in CONTROL_BONES)


def vertex_ids(T, tol=2e-5):
    q = np.round(T.reshape(-1, 3) / tol).astype(np.int64)
    _, inv = np.unique(q, axis=0, return_inverse=True)
    return inv.reshape(-1, 3)


def sample_points(Tc, cap=360):
    """vertices, face centroids and edge midpoints of a triangle set, capped"""
    V = Tc.reshape(-1, 3)
    P = np.concatenate([V, Tc.mean(1), (Tc[:, 0] + Tc[:, 1]) / 2, (Tc[:, 1] + Tc[:, 2]) / 2, (Tc[:, 2] + Tc[:, 0]) / 2])
    if len(P) > cap:
        P = P[np.linspace(0, len(P) - 1, cap).astype(int)]
    return P


def detect_stray(m):
    out = []
    T = m.T
    z = m.in_zone & m.ok
    comps = np.unique(np.concatenate([m.comp[z], m.comp[(m.group > 0) & m.ok]]))    # (new parts anywhere, airframe in the zone)
    zone_idx = np.where(z)[0]
    lo_all, hi_all = T.min(1), T.max(1)
    sc_glass = m.glass
    inside_dir = np.array([0.5773, 0.5101, 0.6373]); inside_dir /= np.linalg.norm(inside_dir)
    eyes = crew_eyes(m)
    for c in comps:
        idx = np.where((m.comp == c) & m.ok)[0]
        V = T[idx].reshape(-1, 3)
        lo, hi = V.min(0), V.max(0)
        diag = float(np.linalg.norm(hi - lo))
        if diag > TH['stray_diag'] and diag > 0.5:
            continue
        gs = m.group[idx]
        grp = int(np.bincount(gs).argmax())
        names = sorted(set(m.bone_names[b] for b in m.bone[idx]))
        if grp == 0 and all(is_moving_bone(n) for n in names):
            continue
        pad = 0.06
        mask = (m.comp != c) & m.ok & np.all(hi_all >= lo - pad, axis=1) & np.all(lo_all <= hi + pad, axis=1)
        cand = np.where(mask)[0]
        S = sample_points(T[idx])
        d = np.inf
        if len(cand):
            d = float(point_tri_dist(S, T[cand]).min())
        touching = d <= TH['stray_touch']
        cen = (lo + hi) / 2
        ntri = len(idx)
        if not touching and diag <= TH['stray_diag']:
            # embedded in a solid? (odd crossings of a ray with a component that is not this one)
            inside = False
            for p in S[:: max(1, len(S) // 8)][:8]:
                t, _ = moller_trumbore(p[None], inside_dir[None], T[zone_idx[m.comp[zone_idx] != c]])
                hit = np.where(np.isfinite(t[0]))[0]
                if len(hit):
                    cnt = np.bincount(m.comp[zone_idx[m.comp[zone_idx] != c]][hit])
                    if (cnt % 2 == 1).any():
                        inside = True
                        break
            if inside:
                continue
            kind = 'isolated face' if ntri <= 2 else 'floating part'
            near = f'{d * 1e3:.0f} mm from the nearest surface' if np.isfinite(d) and d < 1.0 else 'nothing within 1 m'
            owner = {0: 'airframe', 1: 'cockpit', 2: 'canopy'}[grp]
            sc = 1.5 + (2.0 if d < 0.05 else 0.0) + (1.0 if grp == 2 else 0.0) + min(1.5, diag * 4) + (0.5 if grp == 0 else 0.0)
            out.append(finding('stray', kind, cen, diag, sc,
                               f'{kind}: {ntri} faces, {diag * 100:.1f} cm across, {near} ({owner}: {", ".join(names)[:60]})',
                               faces=int(ntri), owner=owner, bones=names[:3], gap_mm=round(d * 1e3, 1) if np.isfinite(d) else None))
        elif grp == 0 and diag <= 0.5:
            # a small piece of the airframe left inside the cabin (old frame stub, post foot, seat, panel):
            # under the glass, above the floor, not part of the skin
            if not eyes:
                continue
            e = min(eyes, key=lambda q: np.linalg.norm(q - cen))
            if cen[1] < e[1] - 1.1 or abs(cen[0] - e[0]) > 0.9 or abs(cen[2] - e[2]) > 2.0:
                continue
            if len(sc_glass):
                t, _ = moller_trumbore(cen[None], np.array([[0.0, 1.0, 0.0]]), sc_glass)
                up = float(np.min(t[0])) if np.isfinite(t[0]).any() else np.inf
            else:
                up = np.inf
            if up > 1.6:
                continue
            # it must be seen from the eye without looking through the glass (a skin panel outside is not "left inside")
            blockers = np.concatenate([T[zone_idx[m.comp[zone_idx] != c]], sc_glass]) if len(sc_glass) else \
                T[zone_idx[m.comp[zone_idx] != c]]
            seen = 0
            for p in S[:: max(1, len(S) // 10)][:10]:
                d = p - e
                dl = float(np.linalg.norm(d))
                t, _ = moller_trumbore(e[None], (d / dl)[None], blockers)
                if not (np.isfinite(t[0]) & (t[0] < dl - 0.004)).any():
                    seen += 1
            if seen == 0:
                continue
            owner = 'airframe'
            out.append(finding('stray', 'leftover in cabin', cen, diag,
                               1.0 + (1.0 if ntri <= 12 else 0.0) + min(1.0, diag * 3) + (0.8 if not touching else 0.0),
                               f'airframe piece left inside the canopy: {ntri} faces, {diag * 100:.1f} cm across, {up * 100:.0f} cm under the glass '
                               f'({", ".join(names)[:60]})', faces=int(ntri), owner=owner, bones=names[:3]))
    return out


# ---------------------------------------------------------------- detector: frame joints and steps

def seg_dist(p, a, b):
    ab = b - a
    t = np.clip(((p - a) @ ab) / max(float(ab @ ab), 1e-18), 0, 1)
    return float(np.linalg.norm(p - (a + ab * t)))


def tri_dist_2d(A, B):
    """distance between two 2-D triangles (3 x 2): 0 when they overlap"""
    if tri_poly_clip_area(A, B) > 1e-12:
        return 0.0
    d = 1e9
    for P, Q in ((A, B), (B, A)):
        for p in P:
            for i in range(3):
                d = min(d, seg_dist(p, Q[i], Q[(i + 1) % 3]))
    return d


def detect_steps(m, raw=False):
    """parallel faces of a frame member against its neighbours (other members, the skin, the glass) that stand
    0.3-12 mm apart yet touch or overlap in their own plane: a step, the sawtooth of a rail that is built in short
    pieces or lofted with an offset"""
    it = Items(m)
    n = len(it.T)
    pad = 0.014
    cos_p = math.cos(math.radians(4.0))
    recs = []
    own = np.where(it.kind == 2)[0]
    for i in own:
        sel = np.where((it.lo[:, 0] <= it.hi[i, 0] + pad) & (it.hi[:, 0] >= it.lo[i, 0] - pad) &
                       (it.lo[:, 1] <= it.hi[i, 1] + pad) & (it.hi[:, 1] >= it.lo[i, 1] - pad) &
                       (it.lo[:, 2] <= it.hi[i, 2] + pad) & (it.hi[:, 2] >= it.lo[i, 2] - pad))[0]
        sel = sel[(sel != i) & (np.abs(it.N[sel] @ it.N[i]) >= cos_p)]
        for j in sel:
            if it.kind[j] == 2 and it.comp[j] == it.comp[i] and it.comp[i] >= 0:
                continue                                       # the same member
            if it.kind[j] == 2 and j < i:
                continue                                       # count member-member pairs once
            if it.kind[j] == 3:
                continue                                       # a frame stands proud of its glass by design
            if float(it.N[i] @ it.N[j]) <= 0:
                continue
            dv = (it.T[j] - it.T[i][0]) @ it.N[i]
            off = float(np.median(dv))
            if not (TH['step_min'] <= abs(off) <= TH['step_max']) or float(np.ptp(dv)) > 0.004:
                continue
            u, v = basis_of(it.N[i])
            o = it.T[i][0]
            A2 = np.stack([(it.T[i] - o) @ u, (it.T[i] - o) @ v], 1)
            B2 = np.stack([(it.T[j] - o) @ u, (it.T[j] - o) @ v], 1)
            if tri_dist_2d(A2, B2) > 0.003:
                continue
            recs.append((i, j, abs(off)))
    # events: one step where two members' faces are offset (clusters of face pairs within 8 cm); a sawtooth rail is a
    # chain of such events (within 30 cm of each other), a lone offset between two big faces is only a rebate
    events = []
    groups = {}
    for i, j, off in recs:
        ta, tb = sorted((it.tag(i), it.tag(j)))
        groups.setdefault((ta, tb), []).append((i, j, off))
    for (ta, tb), rec in groups.items():
        cen = np.array([(it.T[i].mean(0) + it.T[j].mean(0)) / 2 for i, j, _ in rec])
        for idx in cluster_points(cen, 0.035):
            sub = [rec[k] for k in idx]
            P = cen[idx]
            area = sum(it.area[r[0]] for r in sub)
            if len(sub) < 2 and area < 1e-4:
                continue
            events.append((ta, tb, P.mean(0), float(np.max([r[2] for r in sub])), area, len(sub)))
    out = []
    if raw:
        return events
    if not events:
        return out
    for key in sorted(set((e[0], e[1]) for e in events)):
        ev = [e for e in events if (e[0], e[1]) == key]
        pts = np.array([e[2] for e in ev])
        for idx in cluster_points(pts, 0.15):
            sub = [ev[k] for k in idx]
            P = pts[idx]
            offs = np.array([e[3] for e in sub])
            ext = float(np.linalg.norm(P.max(0) - P.min(0)))
            area = sum(e[4] for e in sub)
            ta, tb = key
            offs_med = offs                                    # (each event carries its worst offset)
            kind = 'step to skin' if ta.startswith('airframe') or tb.startswith('airframe') else 'sawtooth rail'
            sc = 1.4 + 1.9 * math.log10(1 + len(sub)) + 0.5 * math.log10(1 + area * 1e4)
            txt = (f'{ta} / {tb}: {len(sub)} step{"s" if len(sub) > 1 else ""} of {offs.min() * 1e3:.1f}-{offs.max() * 1e3:.1f} mm '
                   f'between neighbouring faces over {ext * 100:.0f} cm of rail')
            out.append(finding('jag', kind, P.mean(0), max(ext, 0.02), sc, txt, n=len(sub),
                               step_mm=round(float(np.median(offs)) * 1e3, 2), parts=[ta, tb]))
    return out


def box_of(V):
    """oriented box (centre, axes 3 x 3, half sizes) of a point set by PCA; for a true box the axes are its edges"""
    mu = V.mean(0)
    w, ev = np.linalg.eigh(np.cov((V - mu).T))
    ax = ev[:, ::-1].T
    pr = (V - mu) @ ax.T
    lo, hi = pr.min(0), pr.max(0)
    c = mu + ((lo + hi) / 2) @ ax
    return c, ax, (hi - lo) / 2


def box_dist(p, box):
    """signed distance of points p (k x 3) to a box: > 0 outside, < 0 inside (depth)"""
    c, ax, h = box
    q = np.abs((p - c) @ ax.T) - h
    out = np.linalg.norm(np.maximum(q, 0), axis=1)
    return np.where(q.max(1) > 0, out, q.max(1))


def detect_joints(m):
    """frame members (welded components of the canopy bones that are slim, straight pieces) that nearly meet
    another member: a gap over 3 mm (up to the reach) between members that should join, or collinear neighbours
    overlapping more than 3 mm. Only mutual nearest neighbours with nothing bridging them count: in a chain of short
    pieces the piece two along is not expected to touch."""
    comps = np.unique(m.comp[(m.group == 2) & m.ok])
    boxes, info = [], []
    for c in comps:
        idx = np.where((m.comp == c) & m.ok)[0]
        V = np.unique(np.round(m.T[idx].reshape(-1, 3), 5), axis=0)
        if len(V) < 6 or len(V) > 64:
            continue                                           # (loops and lofted members are measured by the pair scans)
        b = box_of(V)
        ext = np.sort(b[2] * 2)[::-1]
        if ext[0] < 0.02 or ext[0] > 1.5:
            continue
        boxes.append(b)
        info.append((c, idx, V, m.bone_names[m.bone[idx[0]]], ext))
    out = []
    n = len(boxes)
    if n < 2:
        return out
    cen = np.array([b[0] for b in boxes])
    diag = np.array([np.linalg.norm(b[2]) for b in boxes])
    gap = np.full((n, n), np.inf)
    ins = np.zeros((n, n))
    near = {}
    for i in range(n):
        for j in range(i + 1, n):
            if np.linalg.norm(cen[i] - cen[j]) > diag[i] + diag[j] + 0.25:
                continue
            di = box_dist(info[j][2], boxes[i])
            dj = box_dist(info[i][2], boxes[j])
            gap[i, j] = gap[j, i] = float(min(di.min(), dj.min()))
            ins[i, j] = ins[j, i] = float(max(-di.min(), -dj.min(), 0.0))
            near[(i, j)] = (di, dj)
    contact = (ins > 0) | (gap <= TH['joint_gap'])
    np.fill_diagonal(contact, False)
    for (i, j), (di, dj) in near.items():
        g, ov = float(gap[i, j]), float(ins[i, j])
        ci, cj = boxes[i], boxes[j]
        ai, aj = ci[1][0], cj[1][0]
        par = abs(float(ai @ aj))
        pos = (cen[i] + cen[j]) / 2
        ti = info[i][3]
        if TH['joint_gap'] < g <= TH['joint_reach'] and ov == 0:
            if (contact[i] & contact[j]).any():
                continue                                       # bridged by a third member
            ni = np.where(~contact[i])[0]
            nj = np.where(~contact[j])[0]
            if gap[i, ni].min() < 0.8 * g or gap[j, nj].min() < 0.8 * g:
                continue                                       # something else is nearer: not the partner
            if di.min() <= dj.min():
                pv, bx = info[j][2][int(np.argmin(di))], ci
            else:
                pv, bx = info[i][2][int(np.argmin(dj))], cj
            q = np.clip((pv - bx[0]) @ bx[1].T, -bx[2], bx[2])
            line = pv - (bx[0] + q @ bx[1])
            ln = float(np.linalg.norm(line))
            if ln < 1e-9:
                continue
            line /= ln
            long_i = info[i][4][0] > 1.6 * info[i][4][1]
            long_j = info[j][4][0] > 1.6 * info[j][4][1]
            along = max(abs(float(line @ ai)) if long_i else 0.0, abs(float(line @ aj)) if long_j else 0.0)
            chain = not long_i and not long_j
            if along < 0.85 and not chain:
                continue
            out.append(finding('jag', 'joint gap', pos, g, 2.0 + math.log10(1 + g * 1000) + (1.0 if along >= 0.85 else 0.0),
                               f'canopy members {ti} #{info[i][0]} and #{info[j][0]} do not meet: {g * 1e3:.1f} mm gap at the joint',
                               gap_mm=round(g * 1e3, 2), members=[int(info[i][0]), int(info[j][0])]))
        elif par > 0.97 and info[i][4][0] > 1.6 * info[i][4][1] and info[j][4][0] > 1.6 * info[j][4][1]:
            # collinear slim members: the overlap along their common axis
            d = cen[j] - cen[i]
            ov_ax = float(ci[2][0] + cj[2][0] - abs(float(d @ ai)))
            lat = float(np.linalg.norm(d - (d @ ai) * ai))
            if lat < 0.03 and TH['joint_gap'] < ov_ax <= 0.024:
                out.append(finding('jag', 'joint overlap', pos, ov_ax, 1.6 + math.log10(1 + ov_ax * 1000),
                                   f'canopy members {ti} #{info[i][0]} and #{info[j][0]} (collinear) overlap {ov_ax * 1e3:.1f} mm at the joint',
                                   overlap_mm=round(ov_ax * 1e3, 2), members=[int(info[i][0]), int(info[j][0])]))
    return out


def detect_zigzag(m):
    """sawtooth along the long edges of the canopy frames: the creases and free edges of the frame faces are chained
    where they run on (a turn under 30 degrees); a chain whose sideways deviations alternate left, right, left over
    four or more vertices by 0.3-12 mm is a zig-zag (a smooth arc deviates the same way every time)"""
    out = []
    sel = np.where((m.group == 2) & m.ok)[0]
    if not len(sel):
        return out
    T = m.T[sel]
    N = m.N[sel]
    vid = vertex_ids(T)
    pos = {}
    edges = {}
    for k in range(len(T)):
        for e in range(3):
            a, b = int(vid[k][e]), int(vid[k][(e + 1) % 3])
            pos[a] = T[k][e]
            edges.setdefault((min(a, b), max(a, b)), []).append(k)
    cos35 = math.cos(math.radians(35))
    adj = {}
    for (a, b), ks in edges.items():
        crease = len(ks) == 1 or (len(ks) >= 2 and float(N[ks[0]] @ N[ks[1]]) < cos35)
        if crease and np.linalg.norm(pos[a] - pos[b]) > 0.004:
            adj.setdefault(a, []).append(b)
            adj.setdefault(b, []).append(a)
    nxt = {}
    for v, nb in adj.items():
        for u in nb:
            d1 = pos[v] - pos[u]
            d1 /= np.linalg.norm(d1)
            best, bang = None, 1e9
            for w in nb:
                if w == u:
                    continue
                d2 = pos[w] - pos[v]
                d2 /= np.linalg.norm(d2)
                ang = math.acos(max(-1.0, min(1.0, float(d1 @ d2))))
                if ang < bang:
                    best, bang = w, ang
            if best is not None and bang < math.radians(30):
                nxt[(u, v)] = (v, best)
    has_prev = set(nxt.values())
    seen = set()
    chains = []
    for start in nxt:
        if start in has_prev or start in seen:
            continue
        ch = [start[0], start[1]]
        cur = start
        while cur in nxt and nxt[cur] not in seen:
            seen.add(cur)
            cur = nxt[cur]
            ch.append(cur[1])
            if len(ch) > 400:
                break
        seen.add(cur)
        if len(ch) >= 5:
            chains.append(ch)
    for ch in chains:
        P = np.array([pos[v] for v in ch])
        dev = P[1:-1] - (P[:-2] + P[2:]) / 2
        mag = np.linalg.norm(dev, axis=1)
        run = []
        best = []
        for i in range(len(dev)):
            ok = TH['step_min'] <= mag[i] <= TH['step_max']
            if ok and run and float(dev[i] @ dev[run[-1]]) < -0.4 * mag[i] * mag[run[-1]]:
                run.append(i)
            elif ok:
                if len(run) > len(best):
                    best = run
                run = [i]
            else:
                if len(run) > len(best):
                    best = run
                run = []
        if len(run) > len(best):
            best = run
        if len(best) >= 4:
            sub = P[np.array(best) + 1]
            amp = float(np.median(mag[best]))
            length = float(np.linalg.norm(sub.max(0) - sub.min(0)))
            out.append(finding('jag', 'zig-zag edge', sub.mean(0), max(length, 0.02), 2.2 + 0.5 * len(best) ** 0.5 + 0.5 * math.log10(1 + length * 100),
                               f'canopy frame edge zig-zags {amp * 1e3:.1f} mm left and right over {len(best)} vertices ({length * 100:.0f} cm)',
                               n=len(best), amp_mm=round(amp * 1e3, 2)))
    if len(out) > 1:                                           # one finding per frame edge, not one per crease line
        P = np.array([f['pos'] for f in out])
        out = [max((out[i] for i in idx), key=lambda f: f['score']) for idx in cluster_points(P, 0.15)]
    return out


def pick_for_pictures(res, n):
    """the worst findings of one aircraft: the best of each category first, then the next worst, n in all"""
    F = sorted([f for f in res['findings'] if significant(f)], key=lambda f: -f['score'])
    chosen = []
    for cat in ('gaps', 'jag', 'zfight', 'stray'):
        for f in F:
            if f['cat'] == cat and (cat != 'gaps' or f['kind'] != 'glass seam'):
                chosen.append(f)
                break
    chosen = sorted(chosen, key=lambda f: -f['score'])[:n]
    for f in F:
        if len(chosen) >= n:
            break
        if f not in chosen:
            chosen.append(f)
    return chosen


def _pictures_for_id(task):
    vid, items, outdir, geo, asset = task
    from PIL import Image
    done = {}
    try:
        m = Model(vid, geo, asset)
        sc = Scene(m)
    except Exception as ex:
        return {k: f'ERROR {ex}' for k, _ in items}
    for key, f in items:
        d = os.path.join(outdir, 'img', vid)
        os.makedirs(d, exist_ok=True)
        png = os.path.join(d, key + '.png')
        jpg = os.path.join(d, key + '.jpg')
        try:
            render_finding(m, f, png, sc)
            im = Image.open(png).convert('RGB')
            if im.width > 1500:
                im = im.resize((1500, int(im.height * 1500 / im.width)), Image.LANCZOS)
            im.save(jpg, quality=86)
            os.remove(png)
            done[key] = os.path.relpath(jpg, outdir).replace('\\', '/')
        except Exception as ex:
            done[key] = f'ERROR {type(ex).__name__}: {ex}'
    return done


def make_pictures(results, outdir, per_id, jobs, geo=None, asset=None):
    """per aircraft: pictures of its worst findings (outside close-up and eye / overhead view, the point marked);
    plus the ten worst overall. Sets f['image'] on every picture's finding and returns nothing."""
    os.makedirs(outdir, exist_ok=True)
    tasks = {}
    for r in results:
        if r.get('error'):
            continue
        for rank, f in enumerate(pick_for_pictures(r, per_id), 1):
            key = f'{r["vid"]}_{rank}_{f["cat"]}'
            tasks.setdefault(r['vid'], []).append((key, f))
    for rank, f in enumerate(worst_overall(results, 10), 1):
        key = f'top{rank:02d}_{f["id"]}_{f["cat"]}'
        tasks.setdefault(f['id'], []).append((key, f))
    work = [(vid, items, outdir, geo, asset) for vid, items in tasks.items()]
    if jobs > 1 and len(work) > 1:
        import multiprocessing as mp
        with mp.Pool(jobs) as pool:
            outs = pool.map(_pictures_for_id, work)
    else:
        outs = [_pictures_for_id(w) for w in work]
    paths = {}
    for o in outs:
        paths.update(o)
    for vid, items in tasks.items():
        for key, f in items:
            p = paths.get(key)
            if p and not p.startswith('ERROR'):
                f.setdefault('images', []).append(p)
                f['image'] = p if not key.startswith('top') else f.get('image', p)
                if key.startswith('top'):
                    f['top_image'] = p
            elif p:
                print('picture failed', key, p)
    return paths


def write_report(results, outdir, paths=None, tool_hash='', commit='', validation_md=''):
    """REPORT.md: method, thresholds, per-aircraft table, worst ten, validation"""
    os.makedirs(outdir, exist_ok=True)
    L = []
    w = L.append
    w('# Elite Expansion 2 cockpit audit (phase 1, detection only)')
    w('')
    w(f'Tool: `tools/cockpit_models/cockpit_audit.py`, sha256 `{tool_hash}`' + (f', commit `{commit}`' if commit else ''))
    w('')
    w('Nothing in the vehicle files was changed. Every number below comes from the generated geo and asset json of branch '
      '`elite-expansion-2`' + (f', read from the frozen copy `{os.environ["COCKPIT_AUDIT_GEN"]}` of the working tree '
                              '(other threads were editing the repository while the audit ran; `snapshot.txt` lists what was copied)'
                              if os.environ.get('COCKPIT_AUDIT_GEN') else ' as checked out when the audit ran') +
      '. Units are metres (1 block = 1 m); positions are vehicle-local (x left, y up, z forward).')
    w('')
    w('## What it measures')
    w('')
    w('| category | method | what counts |')
    w('|---|---|---|')
    w('| gaps | depth/id buffers rendered from every crew eye (six cube faces, ~1 mm per pixel at 1 m) and from 25 outside cameras '
      'ringed round the canopy (~1 mm per pixel); thin deep slits found by a grey-scale opening of the depth image, width '
      'measured on the slit, the two sides identified from the id buffer | `rim gap`: a slit between cockpit tub / canopy frame / glass and the airframe '
      'skin; `sky hole`: sky seen from the eye where tub, frame or skin should be; `internal slit`: a slit between two new parts; '
      '`glass seam`: untinted line between two panes. Sides must lie at similar depth (a line-of-sight window past a nose is not a crack). '
      'Glass is clear: a crack beside a pane that only shows sky scores low |')
    w('| stray | welded components (shared vertices) smaller than 35 cm | `floating part` / `isolated face`: touches nothing within 4 mm and is not buried in a solid; '
      '`leftover in cabin`: a small airframe piece seen from the eye inside the canopy (low confidence) |')
    w('| jag | pair scan of every face in the cockpit zone; member analysis of the canopy bones | `shallow`: a tub/frame face crossing skin or glass at under 15 deg over 4 cm or more; '
      '`phasing`: faces within 2 mm of skin, glass or each other over 4 cm2; `sawtooth rail` / `step to skin`: parallel neighbouring faces 0.3-12 mm apart '
      'that touch (chains of steps within 30 cm); `joint gap` / `joint overlap`: frame members that should join but are over 3 mm apart (up to 6 cm), '
      'or collinear members overlapping over 3 mm; `zig-zag edge`: frame edge chains whose deviations alternate sides |')
    w('| zfight | all pairs of near-parallel overlapping faces within 0.5 mm (1.5 deg), any bone, plus glass vs everything and glass vs glass | '
      'same-colour pairs (swatch or texture within 10 levels) are marked harmless and not counted; clashes wholly inside the original airframe are counted in their own column |')
    w('')
    w('Each finding is scored (higher = worse): size, visibility (eye or outside view, sky vs hollow), glass vs not, and repeat count.')
    w('')
    ok = [r for r in results if not r.get('error')]
    w('## Per aircraft')
    w('')
    w('| id | gaps (rim / sky) | internal slits | stray | jag | z-fight (new parts) | z-fight (original airframe, visible) | worst gap | worst other item |')
    w('|---|---:|---:|---:|---:|---:|---:|---|---|')
    for r in results:
        if r.get('error'):
            w(f'| {r["vid"]} | ERROR | | | | | | {r["error"].splitlines()[0]} | |')
            continue
        S = [f for f in r['findings'] if significant(f)]
        gr = sum(1 for f in S if f['cat'] == 'gaps' and f['kind'] in ('rim gap', 'sky hole'))
        gi = sum(1 for f in S if f['cat'] == 'gaps' and f['kind'] not in ('rim gap', 'sky hole'))
        st = sum(1 for f in S if f['cat'] == 'stray')
        jg = sum(1 for f in S if f['cat'] == 'jag')
        zf = sum(1 for f in S if f['cat'] == 'zfight')
        air = sum(1 for f in r['findings'] if f['cat'] == 'zfight' and f.get('scope') == 'airframe' and not f.get('harmless'))
        def cell_of(fs):
            fs = sorted(fs, key=lambda f: -f['score'])[:1]
            if not fs:
                return ''
            f = fs[0]
            c = f'{f["cat"]} {f["score"]:.1f}: {f["text"][:100]}'.replace('|', '/')
            return f'[{c}]({f["image"]})' if f.get('image') else c
        w(f'| {r["vid"]} | {gr} | {gi} | {st} | {jg} | {zf} | {air} | {cell_of([f for f in S if f["cat"] == "gaps"])} | '
          f'{cell_of([f for f in S if f["cat"] != "gaps"])} |')
    w('')
    w('## Worst items per aircraft')
    w('')
    for r in ok:
        allS = sorted([f for f in r['findings'] if significant(f)], key=lambda f: -f['score'])
        S = []
        for cat in CATS:                                       # the two worst of each category, mirror twins once
            k = 0
            for f in allS:
                if f['cat'] != cat or k >= 2:
                    continue
                if any(g['cat'] == cat and abs(g['pos'][0] + f['pos'][0]) < 0.04 and abs(g['pos'][1] - f['pos'][1]) < 0.04 and
                       abs(g['pos'][2] - f['pos'][2]) < 0.04 for g in S):
                    continue
                S.append(f)
                k += 1
        S.sort(key=lambda f: -f['score'])
        w(f'### {r["vid"]}')
        w('')
        if not S:
            w('Nothing above the floors.')
            w('')
            continue
        for f in S:
            line = f'- **{f["cat"]} / {f["kind"]}** (score {f["score"]:.1f}) at {tuple(f["pos"])}: {f["text"]}'
            if f.get('image'):
                line += f' ([picture]({f["image"]}))'
            w(line)
        w('')
    w('## Worst ten overall')
    w('')
    for k, f in enumerate(worst_overall(results, 10), 1):
        img = f.get('top_image') or f.get('image') or ''
        w(f'{k}. **{f["id"]}** {f["cat"]} / {f["kind"]} (score {f["score"]:.1f}) at {tuple(f["pos"])}: {f["text"]}' +
          (f'  \n   ![{f["id"]}]({img})' if img else ''))
    w('')
    w('## Reading the numbers')
    w('')
    w('- Counts are findings, not cracks: a symmetric defect appears once per side, and a long crack can be several findings. The worst-ten list counts '
      'mirror twins once, at most 3 per aircraft and 5 per category.')
    w('- `gaps` are seen by cameras (crew eyes: six faces each, first three crew seats inside the cockpit; outside: 25 cameras at 12, 40 and 68 degrees '
      'elevation every 30 degrees of azimuth plus one overhead). Nothing looks up at the underside. Glass is drawn opaque in the buffers.')
    w('- A rim gap is a dark or see-through line only if the crack really is open to something deeper; "opening seen" is what a camera sees of it, '
      '"gap" is the clearance between the two parts near it (about +-30%).')
    w('- `leftover in cabin` is a low-confidence heuristic (an airframe piece seen from the eye under the glass); `open view` is a wide opening '
      '(an open helicopter cabin), listed in the internal column, not a crack.')
    w('- Winding is not used. z-fight clashes inside the original airframe are counted apart: they are not part of the cockpit work but they sit at the sills.')
    w('')
    if validation_md:
        w(validation_md.rstrip())
        w('')
    wind = [(r['vid'], r['info'].get('winding')) for r in ok if r['info'].get('winding')]
    if wind:
        w('## Side observation: face winding')
        w('')
        w('Loader volume sign (positive = outward by the munition test) of bones with 6+ faces, [positive, negative]. '
          'The airframe hulls of the converted models are mostly negative, the new jet cockpit bones positive: the two '
          'are wound opposite ways. The audit does not use winding (all faces are treated as double-sided). '
          'If the game culls back faces this matters; it was not measured here.')
        w('')
        w('| id | airframe bones [+, -] | new bones [+, -] |')
        w('|---|---|---|')
        for vid, wd in wind:
            w(f'| {vid} | {wd["air"]} | {wd["own"]} |')
        w('')
    with open(os.path.join(outdir, 'REPORT.md'), 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('\n'.join(L) + '\n')
    return os.path.join(outdir, 'REPORT.md')


# ---------------------------------------------------------------- the audit

CATS = ('gaps', 'stray', 'jag', 'zfight')

# every Elite Expansion 2 hand-built cockpit (cockpit script + canopy script in tools/cockpit_models on the branch)
FLEET = ['f_22a', 'fa_18e', 'fa_18c', 'f_16c', 'f_16a', 'f_15c', 'f_15a', 'f_15e', 'f_15ex', 'a_10', 'a_10a',
         'f_4b', 'f_4c', 'f_4d', 'f_4e', 'f_14c', 'mig_25pd', 'su_15tm', 'mirage_3e', 'mirage_4a', 'f_102a', 'fc_31',
         'yak_38m', 'yak_141', 'vautour_iin', 'vulcan_b2', 'h_6k', 'ah1w_super_cobra', 'ah_56a', 'sa341f_gazelle',
         'mi_24p', 'mi_8amtsh']


def winding_info(m):
    """loader volume sign of the airframe hull bones and of the new bones (informational: the airframe of some
    converted models is wound the other way round from the new cockpit faces)"""
    import cockpit_lib as L
    res = {'air': [0, 0], 'own': [0, 0]}
    for b in m.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or len(pm['polys']) < 6:
            continue
        try:
            v = L.loader_volume(pm)
        except (IndexError, ValueError):
            continue
        key = 'own' if b['name'].endswith(OWN_SUFFIX) else 'air'
        res[key][0 if v > 0 else 1] += 1
    return res


def audit(vid, only=None, geo=None, asset=None, log=None):
    """run the detectors on one aircraft. Returns dict(vid, seconds, info, findings)."""
    t0 = time.time()
    only = set(only or CATS)
    m = Model(vid, geo, asset)
    F = []
    if only & {'zfight', 'jag'}:
        for f in detect_pairs(m):
            if f['cat'] in only:
                F.append(f)
    if 'jag' in only:
        F += detect_steps(m)
        F += detect_joints(m)
        F += detect_zigzag(m)
    if 'stray' in only:
        F += detect_stray(m)
    if 'gaps' in only:
        F += detect_gaps(m)
    for f in F:
        f.setdefault('scope', 'new')
        f['id'] = vid
    info = {'tris': int(len(m.T)), 'glass_tris': int(len(m.glass)), 'eyes': len(crew_eyes(m)),
            'winding': winding_info(m), 'cubes_skipped': m.skipped_cubes}
    return dict(vid=vid, seconds=round(time.time() - t0, 1), info=info, findings=[dict(f) for f in F])


def significant(f):
    """a finding that belongs in the per-id table: new geometry, not harmless, above the category's floor"""
    if f.get('scope') == 'airframe' or f.get('harmless'):
        return False
    c, k = f['cat'], f['kind']
    if c == 'gaps':
        return f['size'] <= 0.03 or f.get('sky')
    if c == 'zfight':
        return f['score'] >= 1.5
    if c == 'stray':
        return k != 'leftover in cabin' or f['score'] >= 2.4
    return f['score'] >= 2.0


def top_findings(res, n=3, cats=CATS):
    F = [f for f in res['findings'] if f['cat'] in cats and significant(f)]
    return sorted(F, key=lambda f: -f['score'])[:n]


def summary_line(res):
    counts = {c: sum(1 for f in res['findings'] if f['cat'] == c and significant(f)) for c in CATS}
    air = sum(1 for f in res['findings'] if f['cat'] == 'zfight' and f.get('scope') == 'airframe' and not f.get('harmless'))
    return counts, air


def run_one(args):
    vid, only, geo, asset = args
    try:
        return audit(vid, only, geo, asset)
    except Exception as ex:                                    # one bad aircraft must not stop the fleet
        import traceback
        return dict(vid=vid, seconds=0, info={}, findings=[], error=f'{type(ex).__name__}: {ex}\n{traceback.format_exc()}')


def print_result(res, top=6, out=print):
    if res.get('error'):
        out(f'{res["vid"]}: ERROR {res["error"]}')
        return
    counts, air = summary_line(res)
    out(f'{res["vid"]}: gaps {counts["gaps"]}  stray {counts["stray"]}  jag {counts["jag"]}  zfight {counts["zfight"]}'
        f'  (+{air} z-fight clashes inside the original airframe)  [{res["seconds"]} s]')
    F = sorted([f for f in res['findings'] if significant(f)], key=lambda f: -f['score'])[:top]
    for f in F:
        out(f'   {f["cat"]:6} {f["score"]:6.2f}  at {f["pos"]}  {f["text"]}')


def worst_overall(results, n=10, per_id=3, per_cat=5):
    """the n worst findings, mirror twins (x and -x) counted once, at most per_id from one aircraft and per_cat of
    one category (so the list shows what to dispatch, not ten cracks of the same kind)"""
    F = []
    for r in results:
        for f in r['findings']:
            if significant(f):
                F.append(f)
    out, count, ccount = [], {}, {}
    for f in sorted(F, key=lambda f: -f['score']):
        if count.get(f['id'], 0) >= per_id or ccount.get(f['cat'], 0) >= per_cat:
            continue
        x, y, z = f['pos']
        if any(g['id'] == f['id'] and g['cat'] == f['cat'] and abs(g['pos'][0] + x) < 0.04 and abs(g['pos'][1] - y) < 0.04 and
               abs(g['pos'][2] - z) < 0.04 for g in out):
            continue
        out.append(f)
        count[f['id']] = count.get(f['id'], 0) + 1
        ccount[f['cat']] = ccount.get(f['cat'], 0) + 1
        if len(out) >= n:
            break
    return out


def main(argv):
    def opt(name, default=None):
        return argv[argv.index(name) + 1] if name in argv else default
    only = opt('--only')
    only = only.split(',') if only else None
    top = int(opt('--top', 6))
    jobs = int(opt('--jobs', 1))
    geo, asset = opt('--geo'), opt('--asset')
    skip = {opt(k) for k in ('--only', '--top', '--jobs', '--geo', '--asset', '--json', '--images', '--report', '--per-id',
                             '--commit', '--from-json')}
    vids = [a for a in argv if not a.startswith('-') and a not in skip]
    if '--all' in argv:
        vids = list(FLEET)
    if not vids and not opt('--from-json'):
        print(__doc__)
        return 2
    work = [(v, only, geo, asset) for v in vids]
    if opt('--from-json'):
        results = json.load(open(opt('--from-json'), encoding='utf-8'))
        work = []
        for r in results:
            if '-q' not in argv:
                print_result(r, top)
    elif jobs > 1 and len(work) > 1:
        import multiprocessing as mp
        with mp.Pool(jobs) as pool:
            results = []
            for r in pool.imap(run_one, work):
                results.append(r)
                if '-q' not in argv:
                    print_result(r, top)
                    sys.stdout.flush()
    else:
        results = []
        for w in work:
            r = run_one(w)
            results.append(r)
            if '-q' not in argv:
                print_result(r, top)
                sys.stdout.flush()
    if opt('--json'):
        with open(opt('--json'), 'w', encoding='utf-8', newline='\n') as fh:
            json.dump(results, fh, separators=(',', ':'))
    outdir = opt('--report') or opt('--images')
    if outdir and '--no-images' not in argv:
        make_pictures(results, outdir, int(opt('--per-id', 4)), jobs, geo, asset)
        if opt('--json'):
            with open(opt('--json'), 'w', encoding='utf-8', newline='\n') as fh:
                json.dump(results, fh, separators=(',', ':'))
    if opt('--report'):
        import hashlib
        h = hashlib.sha256(open(os.path.abspath(__file__), 'rb').read()).hexdigest()
        vp = os.path.join(outdir, 'validation.md')
        val = open(vp, encoding='utf-8').read() if os.path.exists(vp) else ''
        p = write_report(results, outdir, tool_hash=h, commit=opt('--commit', ''), validation_md=val)
        print('report:', p)
    bad = [r for r in results if r.get('error')]
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
