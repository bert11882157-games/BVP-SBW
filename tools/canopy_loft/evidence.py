#!/usr/bin/env python3
"""Evidence for a canopy, measured on the model from above (alpha-tested height field):

  skin     the airframe surface: the top height field with thin structures (frame bars, aerials, masts, wires)
           opened away (grey opening, OPEN blocks);
  frames   thin structures standing on the skin near the cockpit: canopy frames and window dividers (components with
           a real footprint, not a lone mast or a wire running off aft);
  pit      the cockpit opening: stations where the centre of the skin lies well below its rails;
  rails    per station, the crest of the skin on either side of the opening (the canopy's lower edge there).
"""
import numpy as np
from scipy import ndimage
import ortho as O

STEP = 0.02
OPEN = 0.12          # thin structures narrower than about twice this are frames, not skin
THIN = 0.03          # a frame stands at least this far above the skin
PIT = 0.10           # the opening's centre lies at least this far below the rails
DEPRESSION_R = 0.5   # blocks: the cockpit opening is a depression narrower than this disk
DEPTH = 0.04         # and at least this deep
FRAME_RISE = 0.06    # a frame stands at least this far off the skin (panel lines and stripes do not)


class Evidence:
    def __init__(self, vid):
        self.vid = vid
        tris, uvs, tex = O.model(vid)
        self.tris = tris
        eyes = O.T.eyes(vid)
        self.eye = eyes[0]
        self.eyes = [e for e in eyes if abs(e[0]) < 0.4 and np.linalg.norm(e - eyes[0]) < 3.0]
        if not self.eyes:
            # side-by-side crew (B-1B, Tu-22M, Tu-95): every seat off the centre line; the canopy spans them all
            near = [e for e in eyes if np.linalg.norm(e[1:] - eyes[0][1:]) < 3.0]
            self.eyes = [np.array([0.0, max(e[1] for e in near), z]) for z in sorted({round(float(e[2]), 2) for e in near})]
        zc = float(np.mean([e[2] for e in self.eyes]))
        top_y = tris[..., 1].max() + 1.0
        self.hw, self.hl = 1.8, 4.6
        self.centre = np.array([0.0, top_y, zc])
        _, depth, _ = O.render(tris, uvs, tex, 'top', self.centre, self.hw, self.hl, STEP)
        # image rows run from +z (front) to -z, columns from +x (left) to -x
        self.H = np.where(np.isfinite(depth), top_y - depth, -np.inf)
        ny, nx = self.H.shape
        self.zs = zc + self.hl - np.arange(ny) * STEP
        self.xs = self.hw - np.arange(nx) * STEP
        filled = np.where(np.isfinite(self.H), self.H, self.H[np.isfinite(self.H)].min() - 1)
        r = int(round(OPEN / STEP))
        disk = np.hypot(*np.mgrid[-r:r + 1, -r:r + 1]) <= r
        self.filled = filled
        self.skin = ndimage.grey_opening(filled, footprint=disk)
        self.thin = (filled - self.skin > THIN) & np.isfinite(self.H)
        self._footprint()
        self._rails()
        self._frames()

    def row(self, z):
        return int(np.clip(round((self.zs[0] - z) / STEP), 0, len(self.zs) - 1))

    def col(self, x):
        return int(np.clip(round((self.xs[0] - x) / STEP), 0, len(self.xs) - 1))

    def skin_at(self, x, z):
        return self.skin[self.row(z), self.col(x)]

    def _footprint(self):
        """The cockpit opening seen from above: the depression in the skin (skin below a closing of itself with a
        half-block disk) that holds the crew eyes, with its holes (seat, headrest, stick) filled."""
        r = int(round(DEPRESSION_R / STEP))
        disk = np.hypot(*np.mgrid[-r:r + 1, -r:r + 1]) <= r
        closed = ndimage.grey_closing(self.skin, footprint=disk)
        dep = (closed - self.skin > DEPTH) & np.isfinite(self.H)
        dep = ndimage.binary_opening(dep, iterations=2)
        lab, n = ndimage.label(dep)
        want = {lab[self.row(e[2]), self.col(0.0)] for e in self.eyes} - {0}
        if not want:
            # the eye above a seat or console: nearest depression within half a block
            j0, i0 = self.row(self.eyes[0][2]), self.col(0.0)
            best = None
            for k in range(1, n + 1):
                jj, ii = np.nonzero(lab == k)
                d = np.hypot(jj - j0, ii - i0).min() * STEP
                if d < 0.5 and (best is None or d < best[0]):
                    best = (d, k)
            want = {best[1]} if best else set()
        self.foot = ndimage.binary_fill_holes(np.isin(lab, list(want))) if want else np.zeros_like(dep)
        rows = np.nonzero(self.foot.any(1))[0]
        self.foot_z = (self.zs[rows.min()], self.zs[rows.max()]) if len(rows) else (self.eye[2] + 0.5, self.eye[2] - 0.5)

    def _rails(self):
        """The cockpit rails: seeded at the eye by looking sideways from it (the outermost cockpit wall the crew
        sees, as the old glass tool measured), then tracked station by station along the skin's ridge nearest the
        previous station's rail. A station is open while the ridge holds and the centre lies well below it."""
        import sys, os
        sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'canopy_glass'))
        import glass as G
        _, geo = O.T.load_geo(self.vid)
        tris, uvs = O.T.triangles(geo)
        tex = O.T.load_texture(self.vid)
        n = len(self.zs)
        self.crest = np.zeros((n, 2)); self.centre_y = np.zeros(n); self.open = np.zeros(n, bool)
        for j in range(n):
            self.centre_y[j] = self.skin[j, self.col(0.0)]
        seeds = []
        for e in self.eyes:
            depth = O.T.render_depth(tris, uvs, tex, e)
            w, ry = G.rails(depth, e)
            seeds.append((self.row(e[2]), w - 0.02, ry))
        def ridge(j, x0):
            best = None
            for x in np.arange(max(0.1, x0 - 0.12), x0 + 0.12 + 1e-6, STEP):
                # the raw top field: canopy sill rails are thin bars the skin opening removes
                y = min(self.filled[j, self.col(x)], self.filled[j, self.col(-x)])
                if best is None or y > best[1]:
                    best = (x, y)
            return best
        for j0, x0, y0 in seeds:
            x, y = ridge(j0, x0)
            y = max(y, y0) if abs(y - y0) < 0.15 else y0
            self.crest[j0] = [x, y]; self.open[j0] = True
            for d in (-1, 1):
                j, xp, yp, gap = j0, x, y, 0
                while 0 <= j + d < n:
                    j += d
                    xr, yr = ridge(j, xp)
                    low = self.centre_y[j] < yr - PIT or np.mean([self.skin[j, self.col(t)] < yr - PIT
                                                                  for t in np.arange(-0.7 * xr, 0.7 * xr + 1e-6, STEP)]) > 0.4
                    if yr < yp - 0.2 or not low:
                        gap += 1
                        if gap > int(0.3 / STEP):
                            break
                        continue
                    gap = 0
                    self.crest[j] = [xr, yr]; self.open[j] = True
                    xp, yp = xr, yr
        # fill single-station holes left by the gap bridging
        self.open = ndimage.binary_closing(self.open, iterations=int(0.3 / STEP)) & True
        idx = np.nonzero(self.open)[0]
        if len(idx):
            have = np.nonzero(self.crest[:, 0] > 0)[0]
            for c in (0, 1):
                self.crest[idx, c] = np.interp(idx, have, self.crest[have, c])
            self.pit_rows = (idx.min(), idx.max())
        else:
            r = self.row(self.eye[2]); self.pit_rows = (r, r)
        self.pit_z = (self.zs[self.pit_rows[0]], self.zs[self.pit_rows[1]])

    def _frames(self):
        """Thin structures standing clearly on the skin (not panel lines) near the crew, cropped to a window
        round the cockpit and split into pieces: canopy frames, window dividers and sill rails."""
        zf = max(e[2] for e in self.eyes) + 0.6
        zr = min(e[2] for e in self.eyes) - 0.4
        fx = np.abs(self.xs[np.nonzero(self.foot.any(0))[0]])
        width = max(fx.max() if len(fx) else 0.5, 0.5) + 0.15
        tall = self.thin & (self.filled - self.skin > FRAME_RISE)
        window = (self.zs <= zf + 0.9) & (self.zs >= zr - 1.3)
        tall &= window[:, None]
        tall &= (np.abs(self.xs) <= width + 0.1)[None, :]
        lab, n = ndimage.label(tall, structure=np.ones((3, 3)))
        self.frames = []
        for k in range(1, n + 1):
            jj, ii = np.nonzero(lab == k)
            z = self.zs[jj]; x = self.xs[ii]; y = self.H[jj, ii]
            if len(jj) < 4 or max(np.ptp(z), np.ptp(x)) < 0.2:
                continue                                   # a lone mast, aerial base or lamp
            if np.ptp(x) < 0.12 and np.abs(x).max() < 0.1 and (z.min() < zr - 0.6 or z.max() > zf + 0.4):
                continue                                   # a blade aerial, mast or wire on the spine or nose
            if y.max() < min(e[1] for e in self.eyes) - 0.25:
                continue                                   # never reaches head height: not a canopy frame
            # aerial wires run aft from the canopy to the fin: drop the narrow rows (a single thin strand) of the
            # piece behind the crew (canopy bows and rear glazing are wide)
            behind = z < min(e[2] for e in self.eyes) - 0.5
            if behind.any():
                narrow = np.zeros(len(jj), bool)
                for j in np.unique(jj[behind]):
                    row = jj == j
                    if np.ptp(x[row]) < 0.1:
                        narrow |= row
                jj, ii, z, x, y = jj[~narrow], ii[~narrow], z[~narrow], x[~narrow], y[~narrow]
                if len(jj) < 4:
                    continue
            rail = np.array([self.crest[j, 1] if self.open[j] else -np.inf for j in jj])
            keep = y > np.minimum(rail, y.max()) - 0.02
            if keep.sum() < 4:
                continue
            self.frames.append(np.stack([x[keep], y[keep], z[keep]], 1))
        # how far forward the frames run down onto the nose: thin structures (any height off the skin) joined to
        # the tall frames, within the window (windscreen pillars lie close to the nose skin at their feet)
        thin = self.thin & window[:, None] & (np.abs(self.xs) <= width + 0.1)[None, :]
        lab2, _ = ndimage.label(thin, structure=np.ones((3, 3)))
        ids = set()
        for f in self.frames:
            for x, _, z in f[::5]:
                ids.add(lab2[self.row(z), self.col(x)])
        ids.discard(0)
        self.frame_front = max((self.zs[np.nonzero(lab2 == k)[0]].max() for k in ids), default=None)
