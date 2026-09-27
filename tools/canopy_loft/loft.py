#!/usr/bin/env python3
"""Canopy glass as one lofted sheet (bubble and blown canopies; flat-panel greenhouses are handled elsewhere).

1. Track: a centre-line spine from the canopy's front end (windscreen base on the nose) to its rear end (where it
   meets the spine of the fuselage), taken from the model: it runs through the tops of the canopy frames and window
   dividers (a little under their outer face, so the glass passes through the bars), clears the crew heads and the
   seat, headrest and sight, and ends on the skin at both ends. Front and rear cover every frame of the canopy, so
   multi-section canopies (windscreen, hood, rear glazing) are one sheet.
2. Lower barrier: along the cockpit opening the glass stops at the rail crests on either side; ahead of and behind
   the opening it runs down to the fuselage skin and stops exactly where it meets it.
3. Loft: at every station the cross-section is a superellipse from rail to spine top to rail, its fullness fitted to
   the side frames standing at that station; stations are joined into one connected mesh and clipped exactly at the
   skin and the rails.

Per-aircraft corrections from reference photographs live in loft.json (front/rear ends, extra spine points, width).
Usage: python3 tools/canopy_loft/loft.py [--write] [--out=dir] id...
"""
import json, math, os, sys
import numpy as np
from scipy import ndimage
from scipy.interpolate import PchipInterpolator
import evidence as E
import footprint as F

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG = os.path.join(HERE, 'loft.json')
BAR = 0.012          # the spine runs this far under a frame's top (inside the bar)
HEAD = 0.20          # glass clears the eye by at least this
CLEAR = 0.04         # and anything standing in the cockpit by this
DZ = 0.08            # station spacing (blocks); the glass is drawn every frame, keep it light
N_ARC = 20           # samples across the arch
N_DEFAULT = 2.2      # superellipse exponent without side frames to fit
TRACKS = (0.5, 0.85) # side tracks at these fractions of the half-width, each side
SKIRT = 0.6          # below its base the section runs straight down this far where there are no rails
REFINE = 10


def upper_hull(pts):
    pts = sorted(pts)
    hull = []
    for p in pts:
        while len(hull) >= 2 and ((hull[-1][0] - hull[-2][0]) * (p[1] - hull[-2][1])
                                  - (hull[-1][1] - hull[-2][1]) * (p[0] - hull[-2][0])) >= 0:
            hull.pop()
        hull.append(p)
    return hull


OVAL = 2.4          # plan-view footprint: superellipse exponent (2 = ellipse, higher = fuller)
FP_REACH = (0.5, 0.6)  # blocks the painted footprint may extend the front / rear beyond the frame evidence
FP_COVER = 0.6        # the paint shapes the plan only where it spans at least this share of the canopy
PAINTED_MIN = 0.3      # footprint share that must be dark paint for the painted plan to be used
WINDSCREEN_BASE = 0.45  # the oval's front end as a fraction of its widest (0 = a point)
RAKE = 32.0         # degrees: windscreen slope down from a standing windscreen hoop to the nose


class Loft:
    def __init__(self, vid, cfg=None):
        self.vid = vid
        cfg = cfg or {}
        ev = self.ev = E.Evidence(vid)
        eyes = ev.eyes
        frame_pts = np.vstack(ev.frames) if ev.frames else np.zeros((0, 3))
        zc = cfg.get('centre', float(np.mean([e[2] for e in eyes])))
        # --- width: the rails beside the crew ---
        rw = [ev.crest[j, 0] for j in range(len(ev.zs)) if ev.open[j] and abs(ev.zs[j] - zc) < 0.5]
        W0 = cfg.get('halfwidth', float(np.clip(np.median(rw) if rw else 0.4, 0.22, 0.9)))
        # --- ends ---
        zf = max(e[2] for e in eyes) + 0.5
        zr = min(e[2] for e in eyes) - 0.4
        self.windscreen = []
        if len(frame_pts):
            # the rear end at the last frame standing tall (sill rails and slide tracks run on aft, low); the front
            # end at the foot of the frames that stand tall (windscreen pillars run down forward to the nose)
            head = min(e[1] for e in eyes) - 0.35
            win_front = max(e[2] for e in eyes) + 1.45
            for f in ev.frames:
                tall = f[f[:, 1] >= head]
                if not len(tall):
                    continue
                zr = min(zr, tall[:, 2].min())
                zf = max(zf, f[:, 2].max() if f[:, 2].max() < win_front else tall[:, 2].max())
            if ev.frame_front is not None and ev.frame_front < win_front:
                zf = max(zf, ev.frame_front)
            # the windscreen ahead of the frontmost frame: where the nose skin still climbs steeply toward the frame
            # (a windscreen modelled as a solid, painted slope), the glass runs on down it to where it levels out
            z = zf
            slope = math.tan(math.radians(cfg.get('windscreen_slope', 12.0)))
            while z < zf + 2.0 and ev.skin_at(0.0, z) - ev.skin_at(0.0, z + 0.05) > slope * 0.05:
                self.windscreen.append((z, ev.skin_at(0.0, z) + 0.012))
                z += 0.05
            zf = z
        # sides: as wide as the rails, or as the side frames standing beside the crew
        if len(frame_pts):
            side = frame_pts[(np.abs(frame_pts[:, 2] - zc) < 0.8) & (np.abs(frame_pts[:, 0]) > 0.15)]
            if len(side):
                W0 = cfg.get('halfwidth', float(min(max(W0, np.percentile(np.abs(side[:, 0]), 90)), W0 + 0.12, 0.9)))
        if cfg.get('dark_rear'):
            zr = min(zr, self._dark_rear(ev, eyes))
        # the painted glazing seen from above (tinted, dark on most models) is the canopy's exact plan: the glass
        # covers at least all of it, and follows its outline where it is painted
        self.fp = None
        if cfg.get('plan', 'painted') == 'painted':
            fp = F.Footprint(ev)
            if fp.painted >= PAINTED_MIN:
                self.fp = fp
                # the frames bound the paint's reach: a painted end at most FP_REACH beyond them
                zf = max(zf, min(fp.front, zf + FP_REACH[0])); zr = min(zr, max(fp.rear, zr - FP_REACH[1]))
        zf = cfg.get('front', zf); zr = cfg.get('rear', zr)
        zc = min(max(zc, zr + 0.2), zf - 0.2)
        self.zf, self.zr, self.zc, self.W0 = zf, zr, zc, W0
        # the lip stays on top of the fuselage: at the crew the edge may not hang down the fuselage side
        je = ev.row(zc)
        rail_y = ev.crest[je, 1] if ev.open[je] else ev.skin_at(0.0, zc)
        while 'halfwidth' not in cfg and W0 > 0.22 and \
                max(ev.filled[je, ev.col(W0)], ev.filled[je, ev.col(-W0)]) < rail_y - 0.12:
            W0 -= 0.01
        # stations bunched toward the ends so the oval closes smoothly
        u = np.linspace(0, 1, int((zf - zr) / DZ) + 1)
        self.z = zr + (zf - zr) * (0.5 - 0.5 * np.cos(np.pi * u))
        # plan-view oval (separate front and rear semi-lengths, widest at the crew)
        a = np.where(self.z >= zc, zf - zc, zc - zr)
        r = np.clip(np.abs(self.z - zc) / a, 0, 1)
        # the windscreen stands on the nose across its base: the front of the oval ends on a width (the arch there
        # collapses onto the nose), the rear closes to a point on the spine
        base = np.where(self.z >= zc, cfg.get('windscreen_base', WINDSCREEN_BASE), 0.0)
        W = W0 * cfg.get('width', 1.0) * (base + (1 - base) * (1 - r ** OVAL) ** (1 / OVAL))
        if self.fp is not None and (min(self.fp.front, zf) - max(self.fp.rear, zr)) >= FP_COVER * (zf - zr):
            # the painted outline where there is one (a clear canopy beyond the paint keeps the oval), blended over
            # a few stations so the two meet smoothly
            Wf = self.fp.halfwidth(self.z)
            inside = Wf > 0.04
            wgt = ndimage.gaussian_filter1d(inside.astype(float), 1.5, mode='nearest')
            oval = W
            W = np.where(inside, Wf, W) * wgt + W * (1 - wgt)
            W = ndimage.gaussian_filter1d(W, 1.0, mode='nearest')
            # never much wider than the oval: the ends still close (a cut-off footprint would end in a wall)
            W = np.minimum(W, oval * 1.2 + 0.02)
        # --- lower edge height: the rails (raw top field, so sill rail bars count) or the skin under the oval ---
        yb = np.array([max(ev.filled[ev.row(z), ev.col(w)], ev.filled[ev.row(z), ev.col(-w)])
                       if w > 0.02 else ev.skin_at(0.0, z) for z, w in zip(self.z, W)])
        ok = np.isfinite(yb) & (yb > -1e8)
        yb = np.interp(self.z, self.z[ok], yb[ok])
        # the edge never hangs down the fuselage side: at most 0.12 under the rails (or the centre-line skin where
        # that is lower, toward the ends), so a slim spine or nose leaves the oval edge standing just off the skin
        floor = np.array([min(rail_y, ev.skin_at(0.0, zz)) - 0.12 for zz in self.z])
        yb = np.maximum(yb, floor)
        # a smooth lip: a low-order fit through the measured heights
        deg = 4 if len(self.z) > 12 else 2
        coef = np.polynomial.polynomial.polyfit(self.z - zc, yb, deg)
        Yb = np.polynomial.polynomial.polyval(self.z - zc, coef)
        if 'lip' in cfg:
            Yb = np.full(len(self.z), float(cfg['lip']))
        # --- spine: through frame tops, over the heads and everything in the cockpit, onto the skin at both ends ---
        pts = []
        for f in ev.frames:
            c = f[np.abs(f[:, 0]) < 0.12]
            for zz in np.unique(np.round(c[:, 2] / DZ)):
                m = np.round(c[:, 2] / DZ) == zz
                pts.append((float(c[m, 2].mean()), float(c[m, 1].max() - BAR)))
        for e in eyes:
            for dz in (-0.25, 0.0, 0.2):
                pts.append((e[2] + dz, e[1] + HEAD))
        for zz, w in zip(self.z, W):
            j = ev.row(zz)
            if ev.open[j] and w > 0.1:
                cols = [ev.col(x) for x in np.arange(-0.5 * w, 0.5 * w + 1e-6, E.STEP)]
                pts.append((zz, ev.skin[j, cols].max() + CLEAR))
        for extra in cfg.get('spine', []):
            pts.append(tuple(extra))
        pts += self.windscreen
        pts = [p for p in pts if zr + 0.05 < p[0] < zf - 0.05]
        pts.append((zf, Yb[-1])); pts.append((zr, Yb[0]))
        hull = upper_hull(pts)
        hz = np.array([h[0] for h in hull]); hy = np.array([h[1] for h in hull])
        keep = np.concatenate([[True], np.diff(hz) > 1e-4])
        S = PchipInterpolator(hz[keep], hy[keep])(self.z)
        # clearance on the centre line: heads, seat tops, headrests, sights stay under the roof after smoothing too
        need_c = np.full(len(self.z), -np.inf)
        for zz0, yy in pts:
            k = int(np.argmin(np.abs(self.z - zz0)))
            need_c[k] = max(need_c[k], yy)
        for _ in range(6):
            S = ndimage.gaussian_filter1d(S, 1.5, mode='nearest')
            S = np.maximum(S, need_c)
        if 'spine_max' in cfg:
            S = np.minimum(S, float(cfg['spine_max']))
        S[0], S[-1] = Yb[0], Yb[-1]
        self.S = np.maximum(S, Yb)
        self.W, self.Yb, self.anchors = W, Yb, pts
        # --- side tracks: two more lines on each side, shaped by the window divisions and cleared of the seats ---
        # Each track runs along the canopy at a fixed fraction of the half-width; its height at a station comes from
        # the frame bars (window divisions) standing there, else from a superellipse of the default fullness, and is
        # raised wherever anything in the cockpit (seat tops, headrests) would come through the glass.
        n0 = cfg.get('fullness', N_DEFAULT)
        self.U = np.array(TRACKS)
        H = np.zeros((len(self.z), len(TRACKS)))
        for j, u in enumerate(TRACKS):
            base = Yb + (self.S - Yb) * (1 - u ** n0) ** (1 / n0)
            delta = np.full(len(self.z), np.nan)
            for k, zz in enumerate(self.z):
                if W[k] < 0.05 or not len(frame_pts):
                    continue
                near = frame_pts[np.abs(frame_pts[:, 2] - zz) < DZ * 0.6]
                if not len(near):
                    continue
                uu = np.abs(near[:, 0]) / W[k]
                m = np.abs(uu - u) < 0.12
                if m.any():
                    delta[k] = near[m, 1].max() - BAR - base[k]
            # evidence spreads a little along the canopy, fading away from the division it came from
            have = np.isfinite(delta)
            if have.any():
                idx = np.arange(len(self.z))
                d = np.interp(idx, idx[have], np.clip(delta[have], -0.06, 0.15))
                dist = np.array([np.abs(self.z[have] - zz).min() for zz in self.z])
                d *= np.clip(1.0 - (dist - 0.1) / 0.2, 0.0, 1.0)
                d = ndimage.gaussian_filter1d(d, 1.5, mode='nearest')
            else:
                d = np.zeros(len(self.z))
            H[:, j] = base + d
        # clearance under every track: the cockpit contents (skin field: seats, headrests, sticks; frames excluded)
        for k, zz in enumerate(self.z):
            jrow = ev.row(zz)
            if not ev.open[jrow] or W[k] < 0.1:
                continue
            for j, u in enumerate(TRACKS):
                x = u * W[k]
                cols = [ev.col(sx) for sx in (x - 0.06, x, x + 0.06, -x - 0.06, -x, -x + 0.06)]
                need = ev.skin[jrow, cols].max() + CLEAR
                if need < self.S[k] - 1e-3 and need > H[k, j]:
                    H[k, j] = need
        # keep each section monotone from the spine down to the lip, and smooth along the canopy
        for j in range(len(TRACKS)):
            H[:, j] = ndimage.gaussian_filter1d(H[:, j], 1.0, mode='nearest')
        top = self.S.copy()
        for j in range(len(TRACKS)):
            H[:, j] = np.minimum(H[:, j], top - 0.002)
            H[:, j] = np.maximum(H[:, j], Yb + 0.001)
            top = H[:, j]
        # every section a dome (convex): each track at least on the chord of its neighbours
        U = (0.0,) + tuple(TRACKS) + (1.0,)
        for _ in range(3):
            cols = [self.S] + [H[:, j] for j in range(len(TRACKS))] + [Yb]
            for j in range(len(TRACKS)):
                a, b = U[j], U[j + 2]
                chord = cols[j] + (cols[j + 2] - cols[j]) * (U[j + 1] - a) / (b - a)
                H[:, j] = np.minimum(np.maximum(H[:, j], chord), cols[j] - 0.002)
                cols[j + 1] = H[:, j]
        self.H = H

    @staticmethod
    def _dark_rear(ev, eyes):
        """Rear end of a canopy painted on the model as a dark footprint behind the crew (F-15): the last station
        where the dark patch round the centre line is still at least 40 % of its widest."""
        import ortho as O
        tris, uvs, tex = O.model(ev.vid)
        e = eyes[0]
        top = tris[..., 1].max() + 1
        c = np.array([0.0, top, e[2] - 1.5])
        col, dep, _ = O.render(tris, uvs, tex, 'top', c, 0.8, 2.0, 0.02)
        dark = (col.mean(2) < 0.16) & np.isfinite(dep)
        zs = c[2] + 2.0 - np.arange(dark.shape[0]) * 0.02
        width = np.array([ndimage.label(r)[0][len(r) // 2] and (ndimage.label(r)[0] == ndimage.label(r)[0][len(r) // 2]).sum()
                          for r in dark]) * 0.02
        behind = zs <= e[2]
        wmax = width[behind].max()
        ok = np.nonzero(behind & (width >= 0.4 * wmax))[0]
        return float(zs[ok.max()]) if len(ok) else e[2] - 0.4

    @staticmethod
    def _fill(v, default=None):
        v = np.array(v, float)
        ok = np.isfinite(v)
        if not ok.any():
            return np.full(len(v), default if default is not None else 0.4)
        idx = np.arange(len(v))
        return np.interp(idx, idx[ok], v[ok])

    @staticmethod
    def _exponent(a, b):
        lo, hi = 1.2, 8.0
        for _ in range(40):
            m = (lo + hi) / 2
            if a ** m + b ** m > 1:
                lo = m
            else:
                hi = m
        return (lo + hi) / 2

    def point(self, k, t):
        """Surface point at station index k and arch parameter t in [-1, 1] (edge to edge over the top): a monotone
        cubic across the section through the spine, the side tracks and the lip."""
        z, W, Yb, S = self.z[k], self.W[k], self.Yb[k], self.S[k]
        u = abs(t)
        xs = np.concatenate([[0.0], self.U, [1.0]])
        ys = np.concatenate([[S], self.H[k], [Yb]])
        y = float(PchipInterpolator(xs, ys)(u))
        return np.array([W * u * (1.0 if t >= 0 else -1.0), y, z])

    def mesh(self):
        half = np.sin(np.linspace(0, np.pi / 2, N_ARC // 2 + 1))   # denser toward the lip
        ts = np.concatenate([-half[::-1], half[1:]])
        P = np.array([[self.point(k, t) for t in ts] for k in range(len(self.z))])
        out = []
        for i in range(len(self.z) - 1):
            for j in range(len(ts) - 1):
                a, b, c, d = P[i, j], P[i + 1, j], P[i + 1, j + 1], P[i, j + 1]
                out += [[a, b, c], [a, c, d]]
        return [t for t in out if np.linalg.norm(np.cross(t[1] - t[0], t[2] - t[0])) > 1e-9]


def block(tris):
    flat = [round(float(v), 4) for t in tris for p in t for v in p]
    return {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': flat}


def main(argv):
    cfgs = json.load(open(CONFIG)) if os.path.exists(CONFIG) else {}
    out = next((a[6:] for a in argv if a.startswith('--out=')), None)
    ids = [a for a in argv if not a.startswith('--')]
    results = {}
    for vid in ids:
        cfg = cfgs.get(vid, {})
        if cfg.get('skip'):
            continue
        try:
            L = Loft(vid, cfg)
            tris = L.mesh()
        except Exception as failure:
            print(f'{vid:34s} FAILED {failure!r}', flush=True)
            continue
        results[vid] = block(tris)
        print(f'{vid:34s} {len(tris):5d} triangles  front {L.zf:.2f} rear {L.zr:.2f} eye {L.zc:.2f} halfwidth {L.W0:.2f}', flush=True)
        if '--write' in argv:
            sys.path.insert(0, os.path.join(HERE, '..', 'canopy_glass'))
            import glass as G
            G.write_block(vid, results[vid])
    if out:
        json.dump(results, open(out, 'w'))


if __name__ == '__main__':
    main(sys.argv[1:])
