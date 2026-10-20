"""Glass in frames, found from where each crew member looks out.

From each eye, rays in every direction (a grid of azimuth and elevation) are cast at the model. A ray that hits
nothing within NEAR looks out of the aircraft; the rest hit its frames, sills, coamings and cockpit. The directions
that look out fall into regions:
- the one that holds straight up is the open hood, the bubble fit.py builds;
- every other region is an opening enclosed by frames: a windscreen pane, a side window, a roof window.
Each enclosed opening gets a pane, in this order of preference:
- flush_pane: looked at from outside, square on, the hole in the skin and the skin's face round it; the pane is the
  hole's straightened outline laid on that face, so it sits flush however deep the frame is;
- pane: the plane the frame lies in (or a gentle quadric), glazed out to wherever that surface is open from outside;
- else the membrane (window_panes) across the frame as the eye sees it.

Directions: az from straight ahead, positive to the left (+x); el up from level. Frames: vehicle-local blocks, x left,
y up, z forward."""
import math
import numpy as np
from scipy import ndimage

STEP = 1.0                         # degrees between rays
EL = (-60.0, 89.0)
MIN_CELLS = 10                     # smallest opening taken for a pane, in grid cells
PLANE_RMS = 0.022                  # frame points within this of a plane: a flat pane (frames have depth)
QUAD_RMS = 0.022                   # ... of a quadric: a curved pane
GROW = 7.0                         # degrees a pane may run on into the frames around it
GROW_DOWN = 30.0                   # ... down behind a coaming, until it meets the skin
SHORT = 3                          # rays a pane may run past what the eye sees, where its frame is not closed


def dirs(az, el):
    a, e = np.radians(az), np.radians(el)
    return np.stack([np.sin(a) * np.cos(e), np.sin(e), np.cos(a) * np.cos(e)], -1)


def to_azel(v):
    v = np.asarray(v, float)
    r = np.linalg.norm(v, axis=-1)
    return np.degrees(np.arctan2(v[..., 0], v[..., 2])), np.degrees(np.arcsin(np.clip(v[..., 1] / np.maximum(r, 1e-12), -1, 1)))


class EyeMap:
    """what one eye sees: hit distance and point per direction, the regions that look out"""

    def __init__(self, m, eye, near=None):
        self.eye = np.asarray(eye, float)
        self.az = np.arange(-180.0, 180.0, STEP)
        self.el = np.arange(EL[0], EL[1] + 1e-9, STEP)
        A, E = np.meshgrid(self.az, self.el)
        self.d = dirs(A, E)
        self.D = np.full(A.shape, np.nan); self.P = np.full(A.shape + (3,), np.nan)
        self.Nh = np.full(A.shape + (3,), np.nan)
        o = tuple(self.eye)
        for i in range(A.shape[0]):
            for j in range(A.shape[1]):
                h = m.cast(o, tuple(self.d[i, j]), maxd=8.0)
                if h:
                    self.D[i, j] = h[0]; self.P[i, j] = h[1]; self.Nh[i, j] = m.N[h[2]]
        if near is None:
            # a few times the distance to the cockpit's side walls just under eye level
            side = np.abs(np.abs(A) - 90) < 15
            low = (E > -35) & (E < -5)
            w = self.D[side & low]
            w = w[~np.isnan(w)]
            near = float(np.clip(3.2 * np.median(w), 1.5, 2.6)) if len(w) else 1.8
        self.near = near
        self.out = np.isnan(self.D) | (self.D > near)
        self.lab, self.n = self._label(self.out)
        top = self.lab[-1][self.out[-1]]
        self.outer = int(np.bincount(top).argmax()) if len(top) else 0

    @staticmethod
    def _label(mask):
        lab, n = ndimage.label(mask, structure=np.ones((3, 3), bool))
        # the grid wraps round in azimuth
        a, b = lab[:, 0], lab[:, -1]
        for i in range(len(a)):
            for di in (-1, 0, 1):
                k = i + di
                if 0 <= k < len(b) and a[i] and b[k] and a[i] != b[k]:
                    lab[lab == b[k]] = a[i]
                    b = lab[:, -1]
        u = np.unique(lab[lab > 0])
        remap = np.zeros(lab.max() + 1, int); remap[u] = np.arange(1, len(u) + 1)
        return remap[lab], len(u)

    def cells(self, k):
        return self.lab == k

    def ring(self, mask, width=1):
        """the non-looking-out cells next to a region (its frame), as a mask"""
        grown = ndimage.binary_dilation(np.pad(mask, ((0, 0), (2, 2)), mode='wrap'), structure=np.ones((3, 3), bool),
                                        iterations=width)[:, 2:-2]
        return grown & ~self.out

    def azel_index(self, az, el):
        """fractional grid indices (row, col) of directions"""
        return (np.asarray(el) - self.el[0]) / STEP, (np.asarray(az) - self.az[0]) / STEP


class Surface:
    """a plane through c with normal n (pointing away from the eye), bent into w = q(u, v) when curved"""

    def __init__(self, c, n, U, V, q=None):
        self.c, self.n, self.U, self.V, self.q = c, n, U, V, q

    def w(self, u, v):
        if self.q is None:
            return np.zeros_like(u)
        a = self.q
        return a[0] + a[1] * u + a[2] * v + a[3] * u * u + a[4] * u * v + a[5] * v * v

    def hit(self, o, d):
        """distance along each ray o + t d to the surface (nan when it never meets it ahead)"""
        o = np.asarray(o, float); d = np.asarray(d, float)
        dn = d @ self.n
        with np.errstate(divide='ignore', invalid='ignore'):
            t = ((self.c - o) @ self.n) / dn
        if self.q is not None:
            for _ in range(8):                     # Newton on the bent surface
                p = o + t[..., None] * d - self.c
                u, v, w = p @ self.U, p @ self.V, p @ self.n
                a = self.q
                f = w - self.w(u, v)
                du = a[1] + 2 * a[3] * u + a[4] * v; dv = a[2] + a[4] * u + 2 * a[5] * v
                df = dn - du * (d @ self.U) - dv * (d @ self.V)
                with np.errstate(divide='ignore', invalid='ignore'):
                    t = t - f / df
        t = np.where(t > 0.05, t, np.nan)
        return t

    def dist(self, P):
        p = np.asarray(P, float) - self.c
        return p @ self.n - self.w(p @ self.U, p @ self.V)


def fit_surface(P, eye, dc):
    """the plane laid on the frame points P from outside (seen from the eye, looking along dc): every frame point on
    the eye's side of it or within a few cm of it, and as close to them all as it can be; bent into a quadric when the
    frame is curved -> (Surface, rms, kind, inliers) or None"""
    rel = P - eye
    best = None
    Uc = np.cross(dc, [0, 1, 0]) if abs(dc[1]) < 0.9 else np.cross(dc, [1, 0, 0])
    Uc /= np.linalg.norm(Uc); Vc = np.cross(dc, Uc)
    for a in np.radians(np.arange(-75, 75.1, 3)):
        for b in np.radians(np.arange(-75, 75.1, 3)):
            n = dc * math.cos(a) * math.cos(b) + Uc * math.sin(a) * math.cos(b) + Vc * math.sin(b)
            pr = rel @ n
            h = np.percentile(pr, 92)
            gap = h - pr
            cost = np.mean(np.minimum(gap, 0.25)) + 3 * np.mean(np.maximum(-gap - 0.02, 0))
            if best is None or cost < best[0]:
                best = (cost, n, h)
    _, n, h = best
    c0 = eye + n * h
    gap = h - rel @ n
    keep = gap < 0.04                                       # the frame points the plane lies on
    if keep.sum() < 6:
        return None
    c = P[keep].mean(0)
    U = np.cross(n, Uc); U /= np.linalg.norm(U); V = np.cross(n, U)
    rms = float(np.sqrt(np.mean(((P[keep] - c0) @ n) ** 2)))
    surf = Surface(c0, n, U, V)
    if rms <= PLANE_RMS:
        return surf, rms, 'flat', keep
    return None
    p = P[keep] - c0
    u, v, w = p @ U, p @ V, p @ n
    A = np.stack([np.ones_like(u), u, v, u * u, u * v, v * v], 1)
    q, *_ = np.linalg.lstsq(A, w, rcond=None)
    qr = float(np.sqrt(np.mean((A @ q - w) ** 2)))
    if qr <= QUAD_RMS:
        return Surface(c0, n, U, V, q), qr, 'curved', keep
    return None


class Pane:
    """region: the directions the pane covers; zone: the directions nearer this opening than any other (the
    opening and its half of the frames round it), where no other glass goes"""
    def __init__(self, em, k, surf, kind, region, zone, rms):
        self.em, self.k, self.surf, self.kind, self.region, self.zone, self.rms = em, k, surf, kind, region, zone, rms
        self.sdf = signed_distance(region)
        self.zone_sdf = signed_distance(zone)


def signed_distance(mask):
    """in grid cells, positive inside, wrapping in azimuth"""
    pad = np.pad(mask, ((0, 0), (40, 40)), mode='wrap')
    s = ndimage.distance_transform_edt(pad) - ndimage.distance_transform_edt(~pad)
    s = s - 0.5 * np.sign(s)                   # the zero line on the cell edges between in and out
    return s[:, 40:-40]


def sample(grid, em, az, el):
    """bilinear sample of a grid over the eye map at directions (az, el)"""
    i, j = em.azel_index(az, el)
    i = np.clip(i, 0, grid.shape[0] - 1.001)
    j = np.mod(j, grid.shape[1])
    i0 = np.floor(i).astype(int); j0 = np.floor(j).astype(int); fi = i - i0; fj = j - j0
    j1 = (j0 + 1) % grid.shape[1]
    return ((1 - fi) * (1 - fj) * grid[i0, j0] + (1 - fi) * fj * grid[i0, j1]
            + fi * (1 - fj) * grid[i0 + 1, j0] + fi * fj * grid[i0 + 1, j1])


def find_panes(m, eye, below_skin=None, log=print, em=None):
    """the enclosed openings an eye looks out through, each with its fitted surface and the region of directions
    its pane covers (the opening, carried on into its frames). below_skin(points) -> bool marks points under the
    model's skin (stops a pane running down behind a coaming)."""
    em = em or EyeMap(m, eye)
    sizes = ndimage.sum(np.ones_like(em.lab), em.lab, range(1, em.n + 1))
    cand = [k for k in range(1, em.n + 1) if k != em.outer and sizes[k - 1] >= MIN_CELLS]
    panes = []
    for k in cand:
        C = em.cells(k)
        # it has to look outside: through a window much of the view is open sky (or ground, which is not modelled),
        # not the inside of a cargo hold or a cabin behind a bulkhead
        if np.isnan(em.D[C]).mean() < 0.25:
            continue
        ring = em.ring(C, 1)
        # not the floor the opening looks down on (the nose ahead of a windscreen, a sill's top): the glass stands on
        # that, it does not run through it
        floor = (em.d[..., 1] < -0.08) & (np.abs(em.Nh[..., 1]) > 0.6)
        P = em.P[ring & ~floor]
        P = P[~np.isnan(P[:, 0])]
        if len(P) < 8:
            continue
        dc = em.d[C].mean(0); dc /= np.linalg.norm(dc)
        r = fit_surface(P, em.eye, dc)
        if r is None:
            log(f'    opening {k} ({int(C.sum())} cells): frame not flat or smoothly curved, left open')
            continue
        surf, rms, kind, _ = r
        panes.append([k, surf, kind, C, rms])
    # each non-looking-out cell goes to the nearest opening (panes and the open hood), so neighbouring panes meet
    # halfway across the frame between them
    seeds = [p[3] for p in panes] + ([em.cells(em.outer)] if em.outer else [])
    dist = np.stack([ndimage.distance_transform_edt(~np.pad(s, ((0, 0), (40, 40)), mode='wrap'))[:, 40:-40] for s in seeds]) if seeds else None
    out = []
    for idx, (k, surf, kind, C, rms) in enumerate(panes):
        d_me = dist[idx]
        zone = (dist.argmin(0) == idx)
        t = surf.hit(em.eye, em.d)
        t_open = np.nanmax(np.where(C, t, np.nan)) if (C & ~np.isnan(t)).any() else np.nan
        t = np.where(t < min(3 * em.near, 1.4 * t_open + 0.1), t, np.nan)
        Pt = em.eye + t[..., None] * em.d
        ok = ~np.isnan(t)
        # in a frame the pane runs inside it: no further out than a little past the frame's face toward the eye
        inside = ok & (np.isnan(em.D) | (t > em.D - 0.03)) & (np.isnan(em.D) | (t < em.D + 0.15))
        rows = np.arange(C.shape[0])[:, None]
        down = rows < np.where(C.any(0), np.argmax(C, 0), -1)[None, :]
        # into the frames round it, halfway to the next opening; further down behind a coaming while above the skin
        frame = zone & ~em.out & inside & (d_me <= GROW / STEP)
        deep = zone & ~em.out & ok & (t > em.D) & down & (d_me > GROW / STEP) & (d_me <= GROW_DOWN / STEP)
        if below_skin is not None and deep.any():
            under = np.zeros(C.shape, bool)
            under[deep] = below_skin(Pt[deep])
            deep &= ~under
        region = (C & ok) | frame | deep
        lab, n = ndimage.label(region, structure=np.ones((3, 3), bool))
        keep = np.unique(lab[C & region]); keep = keep[keep > 0]
        region = np.isin(lab, keep)
        zone &= (d_me <= GROW_DOWN / STEP)
        log(f'    pane {k}: {kind} (rms {rms * 1000:.0f} mm), {int(C.sum())} cells open, {int(region.sum())} with frames')
        out.append(Pane(em, k, surf, kind, region, zone | region, rms))
    return em, out


def pane_mesh(p, step=2.0):
    """the pane's surface over its region: a grid of directions, clipped along the region's edge -> (V, F)"""
    em = p.em
    rows, cols = np.nonzero(p.region)
    if len(rows) == 0:
        return np.zeros((0, 3)), np.zeros((0, 3), int)
    s = int(round(step / STEP))
    # columns may wrap round; take the region's az span in unwrapped form
    azc = em.az[cols]; ref = azc[0]
    azu = (azc - ref + 180) % 360 - 180 + ref
    a0, a1 = azu.min() - 2 * step, azu.max() + 2 * step
    e0, e1 = max(em.el[0], em.el[rows].min() - 2 * step), min(em.el[-1], em.el[rows].max() + 2 * step)
    AZ, EL = np.meshgrid(np.arange(a0, a1 + 1e-9, step), np.arange(e0, e1 + 1e-9, step))
    D = dirs(AZ, EL)
    t = p.surf.hit(em.eye, D)
    # outside the region the grid is only there to be cut away: keep it near, so no cut lands far off
    t_reg = p.surf.hit(em.eye, em.d[p.region])
    t = np.minimum(t, np.nanmax(t_reg) * 1.05)
    V = em.eye + t[..., None] * D
    sd = sample(p.sdf, em, (AZ + 180) % 360 - 180, EL)
    sd = np.where(np.isnan(t), -9.0, sd)
    return grid_clip(V, sd)


def grid_clip(V, s):
    """triangles of a structured grid of points V (r, c, 3), clipped to where s >= 0 -> indexed (V, F)"""
    R, Cn = s.shape
    verts = list(V.reshape(-1, 3)); vals = list(np.asarray(s, float).reshape(-1))
    bad = [bool(np.isnan(v).any()) for v in verts]
    F = []
    cache = {}

    def cut(a, b):
        key = (min(a, b), max(a, b))
        if key not in cache:
            u = vals[a] / (vals[a] - vals[b])
            verts.append(verts[a] + u * (verts[b] - verts[a])); vals.append(0.0); bad.append(False)
            cache[key] = len(verts) - 1
        return cache[key]

    def tri(vs):
        if any(bad[v] for v in vs):
            return
        ins = [vals[v] >= 0 for v in vs]
        n = sum(ins)
        if n == 3:
            F.append(tuple(vs)); return
        if n == 0:
            return
        r = (ins.index(True) if n == 1 else ins.index(False))
        a, b, c = vs[r:] + vs[:r]
        if n == 1:
            F.append((a, cut(a, b), cut(a, c)))
        else:                                    # a out, b and c in
            x, y = cut(c, a), cut(a, b)
            F.append((b, c, x)); F.append((b, x, y))

    for i in range(R - 1):
        for j in range(Cn - 1):
            a, b, c, d = i * Cn + j, i * Cn + j + 1, (i + 1) * Cn + j + 1, (i + 1) * Cn + j
            tri([a, b, c]); tri([a, c, d])
    Vv = np.array(verts); Ff = np.array(F, int).reshape(-1, 3)
    used = np.unique(Ff)
    remap = -np.ones(len(Vv), int); remap[used] = np.arange(len(used))
    return Vv[used], remap[Ff]


def solidify(V, F, thick, inward):
    """an indexed sheet given thickness: the sheet, a copy moved thick along each vertex's normal turned toward
    inward(V) (unit vectors, one per vertex), and walls along its open edges -> triangles (n, 3, 3)"""
    if len(F) == 0:
        return np.zeros((0, 3, 3))
    fn = np.cross(V[F[:, 1]] - V[F[:, 0]], V[F[:, 2]] - V[F[:, 0]])
    vn = np.zeros_like(V)
    for k in range(3):
        np.add.at(vn, F[:, k], fn)
    vn /= np.linalg.norm(vn, axis=1, keepdims=True) + 1e-12
    vn *= np.sign(np.sum(vn * inward, axis=1, keepdims=True) + 1e-12)
    Vi = V + thick * vn
    T = [V[F], Vi[F[:, ::-1]]]
    edges = {}
    for f in F:
        for a, b in ((f[0], f[1]), (f[1], f[2]), (f[2], f[0])):
            k = (min(a, b), max(a, b))
            edges[k] = None if k in edges else (a, b)
    W = []
    for e in edges.values():
        if e is None:
            continue
        a, b = e
        W.append([V[b], V[a], Vi[a]]); W.append([V[b], Vi[a], Vi[b]])
    if W:
        T.append(np.array(W))
    return np.concatenate(T)


def window_panes(em, log=print):
    """a pane in every enclosed opening that looks outside, as a membrane stretched across its frame: through each
    direction of the opening the glass lies where the frame's distance, carried smoothly across from all round it
    (harmonic in 1/distance, exact for a flat window), puts it. The pane's edge is the frame itself, so no gap. A frame
    ray that grazes past the frame onto something far behind, or catches something near the eye, is no frame: the
    pane runs on over it at its own distance."""
    sizes = ndimage.sum(np.ones_like(em.lab), em.lab, range(1, em.n + 1))
    out = []
    for k in range(1, em.n + 1):
        if k == em.outer or sizes[k - 1] < MIN_CELLS:
            continue
        C = em.cells(k)
        if np.isnan(em.D[C]).mean() < 0.25:
            continue
        ring = em.ring(C, 1) & ~np.isnan(em.D)
        # the frame's distance, steadied along the frame (a ray now and then grazes past it or catches a fitting)
        D = _ring_median(em.D, ring)
        free = np.zeros_like(C)
        U = _harmonic(C, ring, D)
        for _ in range(6):
            if U is None:
                break
            # each frame cell against the pane beside it
            adj = _neighbour_mean(np.where(C | free, U, np.nan))
            Dm = 1.0 / adj
            bad = ring & ~free & ~np.isnan(adj) & (np.abs(D - Dm) > 0.05 + 0.10 * Dm)
            if not bad.any() or (free | bad).sum() > 0.4 * ring.sum():
                break
            U2 = _harmonic(C | free | bad, ring & ~(free | bad), D)
            if U2 is None or not ((C | free | bad) & (U2 > 1e-3)).sum() == (C | free | bad).sum():
                break            # a piece of the opening left with no frame to hold it: keep the last fit
            free |= bad; U = U2
        if U is None or ((C | free) & ~(U > 1e-3)).any():
            log(f'    opening {k}: could not span it, left open'); continue
        keep = ring & ~free
        # the pane's edge runs on one ray into the frame at the pane's own depth, not at the frame's raw hit points
        ext = _neighbour_mean(np.where(C | free, U, np.nan))
        U[keep] = np.where(np.isnan(ext[keep]), 1.0 / D[keep], ext[keep])
        P = em.eye + em.d / U[..., None]
        R = C | ring
        out.append((k, P, R))
        log(f'    window {k}: {int(C.sum())} cells, frame {np.nanmin(D[keep]):.2f}..{np.nanmax(D[keep]):.2f} m away'
            f', {int(free.sum())} of {int(ring.sum())} frame rays passed over')
    return out


def _harmonic(X, B, D):
    """u harmonic over the cells X (the grid wraps in azimuth), equal to 1/D on the cells B, free (no flux) elsewhere"""
    from scipy.sparse import coo_matrix
    from scipy.sparse.linalg import spsolve
    R_, C_ = X.shape
    cells = np.argwhere(X)
    if len(cells) == 0 or not B.any():
        return None
    idx = -np.ones(X.shape, int); idx[X] = np.arange(len(cells))
    rows, cols, vals = [], [], []
    diag = np.zeros(len(cells)); b = np.zeros(len(cells))
    n_ = np.arange(len(cells))
    for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        ii = cells[:, 0] + di; jj = (cells[:, 1] + dj) % C_
        inside = (ii >= 0) & (ii < R_)
        iic = np.clip(ii, 0, R_ - 1)
        inX = inside & X[iic, jj]; inB = inside & ~X[iic, jj] & B[iic, jj]
        diag += inX | inB
        rows.append(n_[inX]); cols.append(idx[iic[inX], jj[inX]]); vals.append(-np.ones(inX.sum()))
        b[inB] += 1.0 / D[iic[inB], jj[inB]]
    rows.append(n_); cols.append(n_); vals.append(np.maximum(diag, 1e-9))
    A = coo_matrix((np.concatenate(vals), (np.concatenate(rows), np.concatenate(cols))), shape=(len(cells),) * 2).tocsr()
    u = spsolve(A, b)
    U = np.full(X.shape, np.nan); U[X] = u
    return U


def _ring_median(D, ring, r=2):
    """on the ring, the median of the ring's distances within r cells (the grid wraps in azimuth); nan elsewhere"""
    R_, C_ = D.shape
    out = np.full(D.shape, np.nan)
    Dr = np.where(ring, D, np.nan)
    for i, j in np.argwhere(ring):
        w = Dr[max(0, i - r):i + r + 1].take(range(j - r, j + r + 1), axis=1, mode='wrap')
        out[i, j] = np.nanmedian(w)
    return out


def _neighbour_mean(V):
    """mean of the 4-neighbours that are not nan (the grid wraps in azimuth)"""
    s = np.zeros(V.shape); n = np.zeros(V.shape)
    for sh, ax in ((1, 0), (-1, 0), (1, 1), (-1, 1)):
        W = np.roll(V, sh, axis=ax)
        if ax == 0:
            if sh == 1:
                W[0] = np.nan
            else:
                W[-1] = np.nan
        ok = ~np.isnan(W); s[ok] += W[ok]; n += ok
    return np.where(n > 0, s / np.maximum(n, 1), np.nan)


def pane(m, em, k, M, log=print, lim=10, tol=0.010, flat=0.015, curved=0.02, grid=0.05, depth=0.045):
    """a window as one clean pane on a smooth surface: the plane its frame lies in, or else a gently curved quadric
    through its membrane M. The window is every direction near the opening k whose point on that surface is open from
    outside (a ray from just outside reaches it and runs on depth past it unhindered: no skin just under it), so the
    parts of it the eye cannot see past the panel or a post are glazed too. Its outline is smoothed and straightened
    (a window near enough convex is taken as convex) and runs one ray on into the frame -> (V, F), or None when no
    smooth surface fits"""
    C = em.cells(k)
    ring = em.ring(C, 1) & ~np.isnan(em.D)
    q = np.zeros(6)
    plane = _frame_plane(em.P[ring], flat)
    if plane is not None:
        c, n = plane
        u = np.cross(n, [0, 1, 0] if abs(n[1]) < 0.9 else [1, 0, 0]); u /= np.linalg.norm(u); v = np.cross(n, u)
    else:
        c = M.mean(0); _, _, vt = np.linalg.svd(M - c, full_matrices=False)
        u, v, n = vt[0], vt[1], vt[2]
    if (c - em.eye) @ n < 0:
        n = -n; v = -v
    if plane is None:
        X = M - c; U, Vv, Wv = X @ u, X @ v, X @ n
        A = np.stack([U * U, U * Vv, Vv * Vv, U, Vv, np.ones_like(U)], 1)
        q = np.linalg.lstsq(A, Wv, rcond=None)[0]
        r = np.sqrt(np.mean((A @ q - Wv) ** 2))
        if r > curved:
            log(f'    window {k}: frame off a smooth surface by {r:.3f} m rms'); return None
    w = lambda uu, vv: q[0] * uu * uu + q[1] * uu * vv + q[2] * vv * vv + q[3] * uu + q[4] * vv + q[5]
    L = _grow(C, lim)
    d = em.d; e = em.eye - c
    eu, ev, en = e @ u, e @ v, e @ n; du, dv, dn = d @ u, d @ v, d @ n
    with np.errstate(divide='ignore', invalid='ignore'):
        t = (0 - en) / dn                                   # the plane first, then on to the surface
        for _ in range(8):
            uu, vv = eu + t * du, ev + t * dv
            f = en + t * dn - w(uu, vv)
            df = dn - ((2 * q[0] * uu + q[1] * vv + q[3]) * du + (q[1] * uu + 2 * q[2] * vv + q[4]) * dv)
            t = t - f / df
    ok = L & (dn > 0.2) & np.isfinite(t) & (t > 0.05) & (t < 4.0)
    Pp = em.eye + d * np.where(ok, t, 0.0)[..., None]
    free = np.zeros(C.shape, bool)
    for i, j in np.argwhere(ok & ~C):
        free[i, j] = m.cast(tuple(Pp[i, j] + 0.3 * n), tuple(-n), maxd=0.3 + depth) is None
    free |= C & ok
    lab, _ = ndimage.label(free, structure=np.ones((3, 3), bool))
    seeds = np.unique(lab[C & free]); seeds = seeds[seeds > 0]
    Wm = np.isin(lab, seeds)
    rim = L & ~ndimage.binary_erosion(L)
    if (Wm & rim).sum() > 0.1 * max(1, rim.sum()) or Wm.sum() > 3 * C.sum():
        # the frame does not close round it on this surface: only what the eye sees through it
        log(f'    window {k}: open past its frame on that surface, held to its opening')
        Wm = C & ok
    else:                                   # smoothed: a pixel step in the frame's edge is no part of the window
        Wm = ndimage.binary_opening(ndimage.binary_closing(Wm, iterations=2), iterations=1) | (C & ok)
    Wm = ndimage.binary_fill_holes(ndimage.binary_dilation(Wm) & ok)
    V, F = membrane_mesh(Pp, Wm)
    if len(F) == 0:
        return None
    V, F = outline_pane(V, F, n, tol, inner=None if not q[:3].any() else grid)
    if q[:3].any():                             # interior points lifted onto the curved surface
        X = V - c; V = c + np.outer(X @ u, u) + np.outer(X @ v, v) + np.outer(w(X @ u, X @ v), n)
    return V, F


def flush_pane(m, em, k, M, log=print, **kw):
    """a window seen from outside: looking in at it square on (along the normal of its membrane M, or else along the
    eye's line to it), the hole is where a ray runs on deep past the skin round it unhindered; the pane is that hole's
    outline, straightened, laid on the skin's own surface across it (a plane, or gently curved), inset a little, so it
    sits flush in the frame however deep the frame is -> (V, F), or None when no closed hole is found that way"""
    c = M.mean(0); _, _, vt = np.linalg.svd(M - c, full_matrices=False)
    for n in (vt[2], (c - em.eye) / np.linalg.norm(c - em.eye)):
        r = _flush(m, em, k, M, c, n if (c - em.eye) @ n > 0 else -n, log, **kw)
        if r is not None:
            return r
    return None


def _flush(m, em, k, M, c, n, log, step=0.01, margin=0.25, out=0.5, deep=0.2, inset=0.004, tol=0.008):
    u = np.cross(n, [0, 1, 0] if abs(n[1]) < 0.9 else [1, 0, 0]); u /= np.linalg.norm(u); v = np.cross(n, u)
    U, Vv = (M - c) @ u, (M - c) @ v
    us = np.arange(U.min() - margin, U.max() + margin + 1e-9, step)
    vs = np.arange(Vv.min() - margin, Vv.max() + margin + 1e-9, step)
    o = c + out * n
    S = np.full((len(vs), len(us)), np.nan)
    for i, vv in enumerate(vs):
        for j, uu in enumerate(us):
            h = m.cast(tuple(o + uu * u + vv * v), tuple(-n), maxd=out + 1.0)
            if h:
                S[i, j] = h[0]
    sk = np.nanmedian(S[~np.isnan(S)]) if (~np.isnan(S)).any() else np.nan
    if np.isnan(sk):
        return None
    hole = np.isnan(S) | (S > out + deep)
    # the hole the window's own directions fall in
    ju = np.clip(np.round((U - us[0]) / step).astype(int), 0, len(us) - 1)
    iv = np.clip(np.round((Vv - vs[0]) / step).astype(int), 0, len(vs) - 1)
    lab, _ = ndimage.label(hole)
    seeds = np.bincount(lab[iv, ju], minlength=lab.max() + 1); seeds[0] = 0
    if seeds.max() == 0:
        return None
    H = lab == int(seeds.argmax())
    border = np.zeros_like(H); border[0] = border[-1] = True; border[:, 0] = border[:, -1] = True
    if (H & border).any():
        return None                             # not a closed hole looked at this way
    H = ndimage.binary_opening(ndimage.binary_closing(H, iterations=2), iterations=1)
    if H.sum() < 20:
        return None
    # the skin round it, a plane or a gentle curve, from the hits a little way out from the hole: its outer face,
    # whatever lies deeper (the frame's bevel running down into the hole, the next window) dropped
    near = ndimage.binary_dilation(H, iterations=15) & ~ndimage.binary_dilation(H, iterations=3) & ~hole
    I, J = np.nonzero(near)
    if len(I) < 12:
        return None
    Pk = np.stack([us[J], vs[I], out - S[I, J]], 1)              # (u, v, height along n)
    A = np.stack([Pk[:, 0] ** 2, Pk[:, 0] * Pk[:, 1], Pk[:, 1] ** 2, Pk[:, 0], Pk[:, 1], np.ones(len(Pk))], 1)
    keep = np.ones(len(Pk), bool)
    for _ in range(6):
        qq = np.linalg.lstsq(A[keep], Pk[keep, 2], rcond=None)[0]
        r = Pk[:, 2] - A @ qq; keep = r > -0.01
    if keep.mean() < 0.4 or np.sqrt(np.mean(r[keep] ** 2)) > 0.015:
        return None
    # the opening in that face: everything round the hole that lies under it (the frame's bevel and the hole)
    GU, GV = np.meshgrid(us, vs)
    face = qq[0] * GU * GU + qq[1] * GU * GV + qq[2] * GV * GV + qq[3] * GU + qq[4] * GV + qq[5]
    with np.errstate(invalid='ignore'):
        sunk = hole | (out - S < face - 0.01)
    lab2, _ = ndimage.label(sunk)
    Ho = np.isin(lab2, np.unique(lab2[H])) & (lab2 > 0)
    if (Ho & border).any() or Ho.sum() > 4 * H.sum():
        Ho = H                                  # the face is not closed round it: the hole alone
    H = ndimage.binary_opening(ndimage.binary_closing(Ho, iterations=2), iterations=1) | H
    Hd = ndimage.binary_dilation(H, iterations=2)               # tucked in under the frame's lip
    Ii, Jj = np.nonzero(Hd)
    G = np.zeros(Hd.shape + (3,))
    G[..., 0], G[..., 1] = np.meshgrid(us, vs)
    Vg = c + G[..., :1] * u + G[..., 1:2] * v
    V, F = membrane_mesh(Vg, Hd)
    if len(F) == 0:
        return None
    span = max(np.ptp(us[J]), np.ptp(vs[I])) / 2
    curved = (abs(qq[0]) + abs(qq[1]) + abs(qq[2])) * span * span > 0.003
    V, F = outline_pane(V, F, n, tol, inner=0.05 if curved else None)
    X = V - c; uu, vv = X @ u, X @ v
    hgt = qq[0] * uu * uu + qq[1] * uu * vv + qq[2] * vv * vv + qq[3] * uu + qq[4] * vv + qq[5] - inset
    V = c + np.outer(uu, u) + np.outer(vv, v) + np.outer(hgt, n)
    log(f'    window {k}: flush in its frame, {H.sum() * step * step:.3f} m2')
    return V, F


def _grow(C, k):
    """C grown by k cells (the grid wraps in azimuth)"""
    return ndimage.binary_dilation(np.pad(C, ((0, 0), (k, k)), mode='wrap'), structure=np.ones((3, 3), bool),
                                   iterations=k)[:, k:-k]


def _frame_plane(Pf, tol, share=0.6, tries=200):
    """the plane most of the frame points Pf lie within tol of (a robust fit) -> (point, normal) or None"""
    Pf = Pf[np.isfinite(Pf).all(1)]
    if len(Pf) < 12:
        return None
    rng = np.random.default_rng(0)
    best = None
    for _ in range(tries):
        a, b, c = Pf[rng.choice(len(Pf), 3, replace=False)]
        nn = np.cross(b - a, c - a); L = np.linalg.norm(nn)
        if L < 1e-6:
            continue
        nn /= L
        inl = np.abs((Pf - a) @ nn) < tol
        if best is None or inl.sum() > best.sum():
            best = inl
    if best is None or best.mean() < share:
        return None
    Q = Pf[best]; c0 = Q.mean(0); _, _, vt = np.linalg.svd(Q - c0, full_matrices=False)
    if np.sqrt(np.mean(((Q - c0) @ vt[2]) ** 2)) > tol:
        return None
    return c0, vt[2]


def outline_pane(V, F, n, tol, inner=None):
    """a sheet's outer outline, straightened to within tol, as one polygon cut into triangles (with points spaced
    inner apart inside it, for a curved pane) -> (V, F), on the sheet's plane"""
    import bpy  # noqa: F401
    import bmesh
    edges = {}
    for f in F:
        for a, b in ((f[0], f[1]), (f[1], f[2]), (f[2], f[0])):
            key = (min(a, b), max(a, b))
            edges[key] = None if key in edges else (a, b)
    nxt = {}
    for e in edges.values():
        if e is not None:
            nxt.setdefault(e[0], []).append(e[1])
    u = np.cross(n, [0, 1, 0] if abs(n[1]) < 0.9 else [1, 0, 0]); u /= np.linalg.norm(u); v = np.cross(n, u)
    Q = np.stack([V @ u, V @ v], 1)
    loops, used = [], set()
    for a0 in list(nxt):
        for b0 in nxt[a0]:
            if (a0, b0) in used:
                continue
            loop = [a0]; a, b = a0, b0
            while (a, b) not in used:
                used.add((a, b)); loop.append(b)
                cand = [x for x in nxt.get(b, []) if (b, x) not in used]
                if not cand:
                    break
                a, b = b, cand[0]
            if len(loop) > 3 and loop[-1] == loop[0]:
                loops.append(loop[:-1])
    if not loops:
        return V, F
    area = lambda L: 0.5 * abs(np.sum(Q[L, 0] * np.roll(Q[L, 1], -1) - np.roll(Q[L, 0], -1) * Q[L, 1]))
    loop = max(loops, key=area)
    from scipy.spatial import ConvexHull
    hull = ConvexHull(Q[loop])
    if area(loop) > 0.9 * hull.volume:          # near enough convex: its hull, so its edges run straight
        loop = list(np.array(loop)[hull.vertices])
    keep = _dp_closed(Q[loop], tol)
    pts = V[np.array(loop)[keep]]
    if inner:
        return _fill(pts, Q[np.array(loop)[keep]], u, v, n, inner)
    bm = bmesh.new()
    vs = [bm.verts.new(tuple(p)) for p in pts]
    try:
        bm.faces.new(vs)
    except ValueError:
        bm.free(); return V, F
    bmesh.ops.triangulate(bm, faces=list(bm.faces))
    bm.verts.index_update()
    V2 = np.array([x.co[:] for x in bm.verts]).reshape(-1, 3)
    F2 = np.array([[x.index for x in f.verts] for f in bm.faces], int).reshape(-1, 3)
    bm.free()
    return V2, F2


def _fill(pts, Q, u, v, n, step):
    """the polygon (3-D points pts, 2-D Q in the u, v plane) cut into triangles round points spaced step apart
    inside it; the 3-D points come back on the polygon's mean plane"""
    from scipy.spatial import Delaunay
    from matplotlib.path import Path
    poly = Path(Q)
    lo, hi = Q.min(0), Q.max(0)
    gu, gv = np.meshgrid(np.arange(lo[0] + step / 2, hi[0], step), np.arange(lo[1] + step / 2, hi[1], step))
    G = np.stack([gu.ravel(), gv.ravel()], 1)
    if len(G):
        G = G[poly.contains_points(G)]
        if len(G):     # not too near the outline
            dmin = np.min(np.linalg.norm(G[:, None] - Q[None], axis=2), 1)
            G = G[dmin > step / 2]
    Q2 = np.concatenate([Q, G]) if len(G) else Q
    tri = Delaunay(Q2).simplices
    cen = Q2[tri].mean(1)
    tri = tri[poly.contains_points(cen)]
    c0 = pts.mean(0); h = (pts - c0) @ n
    P3 = c0 + np.outer(Q2[:, 0] - (c0 @ u), u) + np.outer(Q2[:, 1] - (c0 @ v), v) + np.outer(np.full(len(Q2), h.mean()), n)
    P3[:len(pts)] = pts
    return P3, tri


def _dp_closed(Q, tol):
    """indices of a closed 2-D polyline kept by Douglas-Peucker to within tol"""
    Qc = np.concatenate([Q, Q[:1]])

    def dp(i0, i1):
        if i1 <= i0 + 1:
            return []
        a, ab = Qc[i0], Qc[i1] - Qc[i0]; L = np.linalg.norm(ab)
        seg = Qc[i0 + 1:i1] - a
        d = np.abs(seg[:, 0] * ab[1] - seg[:, 1] * ab[0]) / L if L > 1e-12 else np.linalg.norm(seg, axis=1)
        m = int(np.argmax(d))
        if d[m] <= tol:
            return []
        im = i0 + 1 + m
        return dp(i0, im) + [im] + dp(im, i1)
    far = int(np.argmax(np.linalg.norm(Q - Q[0], axis=1)))
    return sorted(set([0, far] + dp(0, far) + [i for i in dp(far, len(Q)) if i < len(Q)]))


def membrane_mesh(P, R):
    """quads of the direction grid whose four corners are in R -> indexed (V, F)"""
    R_, C_ = R.shape
    vid = -np.ones(R.shape, int); vid[R] = np.arange(int(R.sum()))
    F = []
    for i in range(R_ - 1):
        for j in range(C_):
            j1 = (j + 1) % C_
            a, b, c, d = vid[i, j], vid[i, j1], vid[i + 1, j1], vid[i + 1, j]
            if min(a, b, c, d) >= 0:
                F += [(a, b, c), (a, c, d)]
            elif min(a, b, c) >= 0:
                F.append((a, b, c))
            elif min(a, c, d) >= 0:
                F.append((a, c, d))
            elif min(a, b, d) >= 0:
                F.append((a, b, d))
            elif min(b, c, d) >= 0:
                F.append((b, c, d))
    return P[R], np.array(F, int).reshape(-1, 3)
