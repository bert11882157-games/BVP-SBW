"""Canopy glass fitted to each aircraft's own model: the hood over an open cockpit.

- the opening is found from above (plan.py): the cockpit the crew sit in, its sills and outline, taken as symmetric;
- the shape is a stack of cross sections along the fuselage (z), each a curve from one sill over the top to the
  other, from the canopy spec (tools/canopy_panels/spec.json: windscreen rake, hood section, rear end) around the
  model's own opening and crew;
- it is raised, smoothly and only where needed, so every part of the model inside it (seat tops, the exterior's HUD,
  the crew's heads) stays at least CLEAR under the glass, and it covers the opening to its ends;
- the glass is the convex hull of that bubble, the frames, arches, coamings and posts round the opening (each raised
  by its clearance) and the opening's outline (hull.py): flat panes form between straight frames by themselves, the
  underside is dropped and the foot is cut exactly on the skin, so the glass stands on the sills with no gap;
- the sheet is given THICK of thickness inward.

Frames: vehicle-local blocks, x left, y up, z forward (1 block = 1 m)."""
import sys, os, json, math, time, hashlib
import numpy as np
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import model as M
import plan as P
import hull as H
import panes as PN

SPEC = json.load(open(os.path.join(M.REPO, 'tools/canopy_panels/spec.json')))
CACHE = os.environ.get('CANOPY_FIT_CACHE', '/tmp/canopy_fit_cache')

EXP = {'round': 2.4, 'flatside': 3.4, 'flattop': 4.5}          # superellipse exponent of the section
RAKE = {'curved': 30, 'flat1': 36, 'flat3': 40, 'integrated': 26, 'none': 34}   # windscreen, degrees from level
REAR = {'fairing': 45, 'glazed': 35, 'taper': 18}              # rear end, degrees from level
CLEAR = 0.02           # model parts under the glass
HEAD = (0.14, 0.05)    # crew head: radius around the eye, clearance above it
THICK = 0.015
FOOT_DROP = 0.03       # the section's ends start this far under the sill so the cut always finds it
BUMP = 0.6             # along z: parts of the skin around the opening shorter than this are frames, coamings, posts
DZ = 0.02              # station spacing while fitting
MESH_DZ = 0.10         # station spacing in the bubble the hull is laid over
HULL_CELL = 0.05       # frame points taken into the hull, one per cell this size
HULL_EDGE = 0.07       # longest edge near the skin, where the hull's foot is cut
NCOL = 15              # points across each section of the bubble
ROUND = 0.12           # along z: how far the crown line's corners are rounded
GAP = 0.14             # along z: the longest gap between stations the glass spans
COVER = 0.05           # the least height of the glass over the sills, everywhere along the opening
BAR = 6                # cells (0.01 m) of a bar inside the opening taken into it, either side of its centre
NMAX = 4.5             # the squarest section allowed before the glass is made taller instead
SMAX = 1.8             # the tallest the glass is made over its shape; a part still in the way past that is left out


# ------------------------------------------------------------------ sections

def superellipse(a, yf, top, n, k=161):
    """from x = +a (left foot) over the top (y = top) to x = -a"""
    phi = np.linspace(0, np.pi, k)
    c, s = np.cos(phi), np.sin(phi)
    return np.stack([a * np.sign(c) * np.abs(c) ** (2 / n), yf + (top - yf) * np.abs(s) ** (2 / n)], 1)


def cut(sec, z, skin, ext=0.6, ext_step=0.01, lean=0.25):
    """the section from foot to foot where it meets the skin: walking down each side from the top, it stops at the
    first point at or under the skin (exactly on it), and a side that runs out first is carried on along its own
    slope (leaning outward) until it meets the skin. None when the top is already under the skin or a side never
    meets it."""
    k0 = int(np.argmax(sec[:, 1]))
    sk = skin.at(sec[k0, 0], z)
    if not np.isnan(sk) and sec[k0, 1] <= sk + 0.005:
        return None
    sides = []
    for step in (-1, 1):                       # -1 toward index 0 (left), +1 toward the end (right)
        pts = [sec[k0]]
        i = k0; foot = None
        while 0 <= i + step < len(sec):
            p, q = sec[i], sec[i + step]
            f = _meet(p, q, z, skin)
            if f is not None:
                foot = f; break
            pts.append(q); i += step
        if foot is None:
            q = sec[i]; pr = sec[i - step] if 0 <= i - step < len(sec) else sec[k0]
            t = q - pr; t = t / (np.linalg.norm(t) + 1e-12)
            if t[1] > -0.25:
                t = t + np.array([0, -0.6]); t /= np.linalg.norm(t)
            t = t + lean * np.array([np.sign(q[0]) if abs(q[0]) > 0.02 else 0.0, 0]); t /= np.linalg.norm(t)
            p = q
            for s in range(1, int(ext / ext_step) + 1):
                nq = q + t * ext_step * s
                f = _meet(p, nq, z, skin)
                if f is not None:
                    foot = f; break
                pts.append(nq); p = nq
        if foot is None:
            return None
        pts.append(foot)
        sides.append(np.array(pts))
    left, right = sides
    return np.concatenate([left[::-1], right[1:]])


def _meet(p, q, z, skin):
    """where the segment p -> q first goes under the skin (bisection on the skin height), or None"""
    sq = skin.at(q[0], z)
    if np.isnan(sq) or q[1] > sq:
        return None
    sp = skin.at(p[0], z)
    if not np.isnan(sp) and p[1] <= sp:
        return p.copy()
    lo, hi = 0.0, 1.0
    for _ in range(14):
        u = (lo + hi) / 2; r = p + u * (q - p); s = skin.at(r[0], z)
        if not np.isnan(s) and r[1] <= s:
            hi = u
        else:
            lo = u
    r = p + hi * (q - p); s = skin.at(r[0], z)
    return np.array([r[0], s if not np.isnan(s) else r[1]])


def resample(Q, n):
    seg = np.linalg.norm(np.diff(Q, axis=0), axis=1); s = np.concatenate([[0], np.cumsum(seg)])
    t = np.linspace(0, s[-1], n)
    return np.stack([np.interp(t, s, Q[:, c]) for c in range(Q.shape[1])], 1)


def top_at(sec, x):
    """glass height over x (the section runs from +x to -x without turning back; nan off it)"""
    return np.interp(x, sec[::-1, 0], sec[::-1, 1], left=np.nan, right=np.nan)


# ------------------------------------------------------------------ the parametric shape

def upper_hull(z, y):
    """upper convex envelope of the points (z, y), as its vertices in z order"""
    o = np.argsort(z); pts = list(zip(z[o], y[o]))
    hull = []
    for p in pts:
        while len(hull) >= 2:
            (z1, y1), (z2, y2) = hull[-2], hull[-1]
            if (y2 - y1) * (p[0] - z1) <= (p[1] - y1) * (z2 - z1):
                hull.pop()
            else:
                break
        hull.append(p)
    return np.array(hull)


class Param:
    """the canopy built from the spec around the model's opening: width and foot from the sills, and a crown line
    that clears everything that has to fit under it (frames, seat tops, heads, coaming and posts ahead of the opening),
    falling away from it no steeper than the windscreen in front and the spec's rear end behind, then rounded"""

    def __init__(self, fx):
        self.fx = fx
        sp = fx.spec
        ws = (sp.get('windscreen') or {}); hood = (sp.get('hood') or {}); rear = (sp.get('rear') or {})
        self.n = EXP.get(hood.get('section'), 2.4)
        self.rake = math.radians(ws.get('rake') or RAKE.get(ws.get('type'), 32))
        self.rear = math.radians(REAR.get(rear.get('type'), 30))
        R = fx.rimrows
        zs = fx.zs_fit
        self.zs = zs
        z = R[:, 0]
        self.z_lo, self.z_hi = z.min(), z.max()
        # half width: the opening's inside its length, narrowing smoothly ahead of and behind it
        ar = ndimage.uniform_filter1d((R[:, 1] - R[:, 3]) / 2 + 0.005, 9, mode='nearest')
        a = np.interp(zs, z, ar)
        a = np.where(zs > z.max(), ar[-1] * (1 - 0.3 * np.clip((zs - z.max()) / 1.2, 0, 1)), a)
        a = np.where(zs < z.min(), ar[0] * (1 - 0.35 * np.clip((z.min() - zs) / 1.2, 0, 1)), a)
        self.a = ndimage.gaussian_filter1d(a, 0.08 / DZ, mode='nearest')
        yr0 = np.nanmean(R[:, [2, 4]], 1); yr0 = np.where(np.isnan(yr0), np.nanmedian(yr0), yr0)
        self.bumps = fx.bumps(self.width)                  # frames and coamings found, the skin under them set
        # foot: the sills inside the opening's length, the skin under the sides ahead of and behind it
        yr = np.nanmean(R[:, [2, 4]], 1)
        yr = ndimage.uniform_filter1d(np.where(np.isnan(yr), np.nanmedian(yr), yr), 9, mode='nearest')
        yf = np.interp(zs, z, yr)
        for k, zz in enumerate(zs):
            if z.min() <= zz <= z.max():
                continue
            v = [fx.skin.at(sx * x, zz) for sx in (-1, 1) for x in np.arange(self.a[k] - 0.04, self.a[k] + 0.041, 0.01)]
            v = [u for u in v if not np.isnan(u)]
            yf[k] = np.median(v) if v else yf[k - 1] if zz > z.max() else np.nan
        for k in range(len(zs) - 2, -1, -1):              # behind: fill from the station ahead
            if np.isnan(yf[k]):
                yf[k] = yf[k + 1]
        self.yf = ndimage.gaussian_filter1d(yf, 0.08 / DZ, mode='nearest')
        # the top each obstacle needs (the section through it must clear it)
        need = []
        for p, c in fx.obstacles():
            k = self._k(p[2]); aa = self.a[k]; ff = self.yf[k] - FOOT_DROP
            u = abs(p[0]) / aa
            if u > 0.9:
                continue
            need.append((p[2], ff + (p[1] + c - ff) / (1 - u ** self.n) ** (1 / self.n)))
        # and the glass covers the opening to its ends, standing at least COVER over the sills there
        for zz in R[:, 0]:
            need.append((zz, self.yf[self._k(zz)] + COVER))
        need = np.array(need)
        self.need = need
        # tents: from each obstacle the crown may fall away at the windscreen's angle forward, the rear's angle aft
        tf, tr = math.tan(self.rake), math.tan(self.rear)
        tent = np.full(len(zs), -9.0)
        for zi, yi in need:
            tent = np.maximum(tent, yi - tf * np.maximum(0, zs - zi) - tr * np.maximum(0, zi - zs))
        hull = upper_hull(zs, tent)
        crown = np.interp(zs, hull[:, 0], hull[:, 1])
        # rounded, and lifted locally (smoothly) wherever the rounding dipped under what it has to clear
        sm = ndimage.gaussian_filter1d(crown, ROUND / DZ, mode='nearest')
        self.crown = lift(sm, tent, 0.3)
        self.tent = tent

    def _k(self, z):
        return int(np.clip(round((z - self.zs[0]) / DZ), 0, len(self.zs) - 1))

    def width(self, z):
        return self.a[self._k(z)]

    def section(self, k, z, n=None, shrink=1.0):
        a = self.a[k] * shrink; yf = self.yf[k]; top = self.crown[k]
        if top < yf + 0.01:
            return None
        return superellipse(a, yf - FOOT_DROP, top, n or self.n)


def lift(v, floor, reach):
    """v raised smoothly (over about reach along z) to stay at or above floor"""
    d = np.maximum(0, floor - v)
    if not d.any():
        return v
    L = ndimage.gaussian_filter1d(ndimage.grey_dilation(d, size=int(reach / DZ) | 1), reach / 4 / DZ, mode='nearest')
    return v + np.maximum(L, d)


# ------------------------------------------------------------------ the fit

class Fit:
    def __init__(self, vid, ref=None, log=print, model=None):
        self.vid, self.ref, self.log = vid, ref, log
        self.spec = SPEC.get(vid) or {}
        self.m = model or M.Model(vid, ref)
        self.eyes = self.m.eyes()
        E = np.array(self.eyes)
        self.Ec = np.array([0.0, E[:, 1].max(), E[:, 2].mean()])
        self.zb_map = 3.2 + (self.Ec[2] - E[:, 2].min()); self.zf_map = 3.8 + (E[:, 2].max() - self.Ec[2])
        self.xs, self.zs, self.H = self._heightmap()
        reg = np.zeros(self.H.shape, bool); levels = []
        for e in self.eyes:
            r, t = P.opening(self.xs, self.zs, self.H, e)
            if r is not None:
                reg |= r; levels.append(t)
        if not levels:
            raise RuntimeError('no cockpit opening found from the crew eyes')
        lab, n = ndimage.label(reg)
        keep = np.zeros(n + 1, bool)
        for e in self.eyes:
            j = int(np.argmin(abs(self.zs - e[2]))); i = int(np.argmin(abs(self.xs - e[0])))
            win = lab[max(0, j - 40):j + 40, max(0, i - 20):i + 20]
            keep[np.unique(win[win > 0])] = True
        self.reg = keep[lab] if keep.any() else reg
        self.reg |= self.reg[:, ::-1]            # cockpits are symmetric: a centre bar must not halve the opening
        # a bar or rail inside the opening (narrower than BAR across) is in it, to be cleared like the rest
        self.reg |= ndimage.binary_closing(np.pad(self.reg, ((0, 0), (BAR, BAR))), structure=np.ones((1, 2 * BAR + 1), bool))[:, BAR:-BAR]
        # and a seam no wider than that (the crack down a model's centre line) is no part of it
        self.reg = ndimage.binary_opening(self.reg, structure=np.ones((1, 2 * BAR + 1), bool))
        self.rim_level = max(levels)
        self.rimrows = P.rim(self.xs, self.zs, self.H, self.reg)
        if len(self.rimrows) < 5:
            raise RuntimeError('no cockpit opening wide enough across the centre line')
        self.skin = P.Skin(self.xs, self.zs, self.H, self.reg)
        self.zs_fit = np.arange(self.rimrows[:, 0].min() - 2.0, self.rimrows[:, 0].max() + 2.6, DZ)

    def _heightmap(self):
        key = hashlib.md5(f'{self.vid}|{self.ref}|{self.Ec.round(3)}|{self.zb_map:.2f}|{self.zf_map:.2f}'.encode()).hexdigest()[:12]
        p = os.path.join(CACHE, f'hm_{self.vid}_{key}.npz')
        if os.path.exists(p):
            d = np.load(p); return d['xs'], d['zs'], d['H']
        xs, zs, H = P.heightmap(self.m, self.Ec, xr=1.4, zb=self.zb_map, zf=self.zf_map, step=0.01)
        os.makedirs(CACHE, exist_ok=True); np.savez(p, xs=xs, zs=zs, H=H)
        return xs, zs, H

    def obstacles(self):
        """(point, clearance): the model's parts inside the opening as seen from above, and each crew head"""
        inside = ndimage.binary_erosion(self.reg, iterations=3)
        J, I = np.nonzero(inside[::2, ::2])
        out = [(np.array([self.xs[2 * i], self.H[2 * j, 2 * i], self.zs[2 * j]]), CLEAR) for j, i in zip(J, I)
               if not np.isnan(self.H[2 * j, 2 * i])]
        r, c = HEAD
        for e in self.eyes:
            ctr = e + np.array([0, 0.03, -0.06])
            for th in np.radians(np.arange(0, 91, 15)):
                for ph in np.radians(np.arange(0, 360, 30)):
                    d = np.array([math.sin(th) * math.cos(ph), math.cos(th), math.sin(th) * math.sin(ph)])
                    out.append((ctr + r * d, c))
        return out

    def bumps(self, width, reach=0.5, base=(0.5, 1.1), side=0.06, window=0.4):
        """(point, clearance): parts of the skin around the opening that stand up out of it over a short stretch
        along z (frame arches and bows, coamings, HUD posts), within the canopy's width plus side. Ahead of and behind
        the opening the skin's line is fitted per column over base (distances from the opening) and carried back to
        it; beside the opening it is the running median along z over window. Sets self.under (every cell the glass
        has to clear: the opening and these parts) and replaces self.skin with the skin under them."""
        z0, z1 = self.rimrows[:, 0].min(), self.rimrows[:, 0].max()
        d = self.zs[1] - self.zs[0]
        mask = np.zeros(self.H.shape, bool)
        Hb = self.H.copy()                                   # the skin with those parts taken off
        w_all = np.array([width(z) for z in self.zs])
        for end in (-1, 1):
            ze = z1 if end > 0 else z0
            fit_rows = np.nonzero((end * (self.zs - ze) >= base[0]) & (end * (self.zs - ze) <= base[1]))[0]
            rows = np.nonzero((end * (self.zs - ze) > 0) & (end * (self.zs - ze) < reach))[0]
            if len(fit_rows) < 10 or len(rows) == 0:
                continue
            for i in range(len(self.xs)):
                col = self.H[fit_rows, i]; ok = ~np.isnan(col) & ~self.reg[fit_rows, i]
                if ok.sum() < 10:
                    continue
                c1, c0 = np.polyfit(self.zs[fit_rows][ok], col[ok], 1)
                h = self.H[rows, i]; line = c0 + c1 * self.zs[rows]
                with np.errstate(invalid='ignore'):
                    m = (abs(self.xs[i]) <= w_all[rows] + side) & ~self.reg[rows, i] & (h - line > 0.015)
                mask[rows, i] |= m; Hb[rows[m], i] = line[m]
        rows = np.nonzero((self.zs >= z0) & (self.zs <= z1))[0]
        hw = int(window / d / 2)
        for i in range(len(self.xs)):
            if abs(self.xs[i]) > w_all[rows].max() + side:
                continue
            col = np.where(self.reg[:, i], np.nan, self.H[:, i])
            pad = np.concatenate([np.full(hw, np.nan), col, np.full(hw, np.nan)])
            win = np.lib.stride_tricks.sliding_window_view(pad, 2 * hw + 1)[rows]
            with np.errstate(invalid='ignore'), __import__('warnings').catch_warnings():
                __import__('warnings').simplefilter('ignore')
                med = np.nanmedian(win, axis=1)
                m = (abs(self.xs[i]) <= w_all[rows] + side) & ~np.isnan(col[rows]) & (col[rows] - med > 0.015)
            mask[rows[m], i] = True; Hb[rows[m], i] = med[m]
        # only what stands at the opening's edge (a frame, a coaming, a post): not a nose curving away ahead of it
        lab, n = ndimage.label(mask, structure=np.ones((3, 3), bool))
        if n:
            touch = np.unique(lab[ndimage.binary_dilation(self.reg, iterations=3) & mask])
            mask = np.isin(lab, touch[touch > 0])
            Hb = np.where(mask, Hb, self.H)
        self.under = ndimage.binary_erosion(self.reg, iterations=3)
        self.frames = mask
        self.skin = P.Skin(self.xs, self.zs, Hb, self.reg)
        J, I = np.nonzero(mask[::2, ::2])
        return [(np.array([self.xs[2 * i], self.H[2 * j, 2 * i], self.zs[2 * j]]), CLEAR) for j, i in zip(J, I)]

    # ---- build
    def build(self, shape=None):
        """sections cut to the skin; where something under a section comes within CLEAR of it the section is made
        fuller (squarer) up to NMAX, and past that taller, each change spread smoothly along z"""
        t0 = time.time()
        shape = shape or Param(self)
        K = len(self.zs_fit)
        n = np.full(K, shape.n); scale = np.ones(K)
        spread = lambda v, base: np.maximum(v, base + ndimage.gaussian_filter1d(
            ndimage.grey_dilation(v - base, size=int(0.16 / DZ) | 1), 0.06 / DZ, mode='nearest'))
        for it in range(6):
            secs = [self._section(shape, k, n[k], scale[k]) for k in range(K)]
            secs = self._longest_run(secs)
            need = np.array([self._need(k, s) for k, s in enumerate(secs)])
            bad = need > 1.0005
            self.log(f'  pass {it}: {sum(s is not None for s in secs)} stations, {int(bad.sum())} short of clearance'
                     f' (worst x{need.max():.3f}), fullness {n.min():.2f}..{n.max():.2f}, stretch to x{scale.max():.3f}')
            if not bad.any():
                break
            n_req = n.copy(); s_req = scale.copy()
            for k in np.nonzero(bad)[0]:
                for nn in np.arange(n[k] + 0.25, NMAX + 1e-6, 0.25):
                    if self._need(k, self._section(shape, k, nn, scale[k])) <= 1.0005:
                        n_req[k] = nn; break
                else:
                    n_req[k] = max(n[k], NMAX)
                    s_req[k] = min(SMAX, scale[k] * self._need(k, self._section(shape, k, n_req[k], scale[k])))
            n0, s0 = n, scale
            n = spread(n_req, shape.n); scale = np.minimum(SMAX, spread(s_req, 1.0))
            if np.allclose(n, n0) and np.allclose(scale, s0):
                break
        self.secs = secs; self.n = n; self.scale = scale
        self.grid = self._grid(secs)
        self.mesh = self._hull(self.grid, shape.bumps)
        self.log(f'  {len(self.mesh)} triangles, {time.time() - t0:.0f}s')
        return self.mesh

    def _section(self, shape, k, n, scale):
        """the section cut to the skin; where its sides stand off the fuselage (a nose narrower than the opening)
        it is taken in until they meet it"""
        z = self.zs_fit[k]
        for shrink in (1.0, 0.92, 0.84, 0.76, 0.68, 0.6, 0.5):
            sec = shape.section(k, z, n, shrink)
            if sec is None:
                return None
            if scale != 1.0:
                yb = min(sec[0, 1], sec[-1, 1])
                sec = np.stack([sec[:, 0], yb + (sec[:, 1] - yb) * scale], 1)
            c = cut(sec, z, self.skin)
            if c is not None:
                return c
        return None

    @staticmethod
    def _longest_run(secs):
        ok = np.array([s is not None for s in secs])
        lab, n = ndimage.label(ok)
        if n == 0:
            raise RuntimeError('no section met the skin')
        best = 1 + int(np.argmax(ndimage.sum(ok, lab, range(1, n + 1))))
        keep = lab == best
        # runs separated from it by a short gap (a frame the crown dips under) are kept: the mesh spans the gap
        while True:
            ks = np.nonzero(keep)[0]; grown = False
            for k in range(1, n + 1):
                if k == best or keep[lab == k].any():
                    continue
                kk = np.nonzero(lab == k)[0]
                if (kk.min() > ks.max() and kk.min() - ks.max() <= GAP / DZ) or (kk.max() < ks.min() and ks.min() - kk.max() <= GAP / DZ):
                    keep |= lab == k; grown = True
            if not grown:
                break
        return [s if keep[k] else None for k, s in enumerate(secs)]

    def _need(self, k, sec):
        """the vertical stretch (about the feet) the section at station k needs so nothing under it comes within
        CLEAR of it (1 = none)"""
        if sec is None:
            return 1.0
        z = self.zs_fit[k]
        j = int(round((z - self.zs[0]) / (self.zs[1] - self.zs[0])))
        if j < 0 or j >= len(self.zs):
            return 1.0
        yb = min(sec[0, 1], sec[-1, 1])
        x0, x1 = sorted((sec[0, 0], sec[-1, 0]))
        need = 1.0
        sel = (self.xs > x0 + 0.04) & (self.xs < x1 - 0.04) & ~np.isnan(self.H[j]) & self.under[j]
        if sel.any():
            x = self.xs[sel]; h = self.H[j, sel]
            g = top_at(sec, x)
            ok = ~np.isnan(g) & (g - yb >= 0.02) & (h + CLEAR > g)
            if ok.any():
                need = max(need, float(((h[ok] + CLEAR - yb) / (g[ok] - yb)).max()))
        r, c = HEAD
        for e in self.eyes:
            hc = e + np.array([0, 0.03, -0.06])
            dz = abs(z - hc[2])
            if dz < r:
                w = math.sqrt(r * r - dz * dz)
                x = np.linspace(hc[0] - w, hc[0] + w, 9)
                g = top_at(sec, x)
                yh = hc[1] + np.sqrt(np.maximum(0, w * w - (x - hc[0]) ** 2)) + c
                ok = ~np.isnan(g) & (yh > g)
                if ok.any():
                    need = max(need, float(((yh[ok] - yb) / (g[ok] - yb)).max()))
        return need

    def _grid(self, secs):
        ks = [k for k, s in enumerate(secs) if s is not None]
        step = max(1, int(round(MESH_DZ / DZ)))
        pick = ks[::step]
        if pick[-1] != ks[-1]:
            pick.append(ks[-1])
        rows = [np.column_stack([resample(secs[k], NCOL), np.full(NCOL, self.zs_fit[k])]) for k in pick]
        rows = [r[:, [0, 1, 2]] for r in rows]
        # close each end onto the skin half a station on
        for end, k in ((0, pick[0]), (-1, pick[-1])):
            r = rows[end].copy(); z = self.zs_fit[k] + (-DZ / 2 if end == 0 else DZ / 2)
            for i in range(NCOL):
                s = self.skin.at(r[i, 0], z)
                r[i, 1] = min(r[i, 1], s) if not np.isnan(s) else r[i, 1]
            r[:, 2] = z
            if end == 0:
                rows.insert(0, r)
            else:
                rows.append(r)
        return np.array(rows)                    # (K, NCOL, 3) in x, y, z

    def _hull(self, G, bumps):
        """the hull of the bubble and the frames round it (each raised by its clearance), its foot on the skin"""
        B = np.array([p + np.array([0, c, 0]) for p, c in bumps]).reshape(-1, 3)
        # and the opening's own outline, at the skin beside it: the glass covers all of the opening, to its ends
        edge = self.reg & ~ndimage.binary_erosion(self.reg)
        Hs = ndimage.grey_dilation(np.where(self.reg | np.isnan(self.H), -9.0, self.H), size=(5, 5))
        J, I = np.nonzero(edge & (Hs > -8))
        O = np.stack([self.xs[I], Hs[J, I] + 0.005, self.zs[J]], 1)
        B = np.concatenate([B, O])
        if len(B):                               # one point per 4 cm cell is plenty for the hull
            key = np.round(B / HULL_CELL).astype(int)
            _, first = np.unique(key, axis=0, return_index=True)
            B = B[first]
        V, F = H.hull_sheet(np.concatenate([G.reshape(-1, 3), B]), self.skin, max_edge=HULL_EDGE)
        self.sheet = (V, F)
        inward = np.stack([-V[:, 0], (self.rim_level - 0.4) - V[:, 1], np.zeros(len(V))], 1)
        return PN.solidify(V, F, THICK, inward)


def solidify(G, thick):
    """outer grid G (stations x columns), an inner grid offset inward by thick, and the four edges closed"""
    K, A, _ = G.shape
    du = np.gradient(G, axis=1); dv = np.gradient(G, axis=0)
    n = np.cross(du, dv)
    n /= np.linalg.norm(n, axis=2, keepdims=True) + 1e-12
    ctr = G.copy(); ctr[..., 0] = 0; ctr[..., 1] = G[..., 1].min() - 0.3
    inward = np.sign(np.sum(n * (ctr - G), axis=2, keepdims=True)); inward[inward == 0] = 1
    n = n * inward
    Gi = G + thick * n
    T = []
    for k in range(K - 1):
        for a in range(A - 1):
            p, q, r, s = G[k, a], G[k, a + 1], G[k + 1, a + 1], G[k + 1, a]
            T += [[p, q, r], [p, r, s]]
            p, q, r, s = Gi[k, a], Gi[k, a + 1], Gi[k + 1, a + 1], Gi[k + 1, a]
            T += [[p, r, q], [p, s, r]]
    def strip(o, i):
        for a in range(len(o) - 1):
            T.extend([[o[a], i[a], i[a + 1]], [o[a], i[a + 1], o[a + 1]]])
    strip(G[0], Gi[0]); strip(G[-1][::-1], Gi[-1][::-1]); strip(G[:, 0][::-1], Gi[:, 0][::-1]); strip(G[:, -1], Gi[:, -1])
    return np.array(T)


def to_json(T):
    return {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS',
            'Triangles': [round(float(v), 4) for v in np.asarray(T).reshape(-1)]}


if __name__ == '__main__':
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument('vids', nargs='+'); ap.add_argument('--ref', default=None); ap.add_argument('--out', default=None)
    a = ap.parse_args()
    for vid in a.vids:
        print(vid)
        f = Fit(vid, a.ref)
        T = f.build()
        if a.out:
            os.makedirs(a.out, exist_ok=True)
            np.savez(os.path.join(a.out, vid + '.npz'), outer=T, E=np.array(f.eyes), grid=np.array(0))
