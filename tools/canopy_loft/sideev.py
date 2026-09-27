#!/usr/bin/env python3
"""Side-view evidence of where the canopy glass starts and ends, taken from the model's own geometry.

From the side (orthographic, alpha-tested) the fuselage is a solid silhouette and the canopy frame (windscreen
pillars, bows, sill rails, rear frame) stands on it as thin bars; anything inside the cockpit (seat, panel, stick)
is set back from the side and is ignored by keeping only surfaces near the outer side of the fuselage.
  skin   = opening of the outer-surface mask with a small disk (thin bars removed): its top edge per station is the
           sill line (where the glass meets the fuselage at the side);
  frames = outer-surface pixels the opening removed, above the sill;
  feet   = where each frame bar meets the sill: the front-most foot is where the windscreen starts, the rear-most
           where the glazing ends, every foot is an exact point of the glass edge.
"""
import numpy as np
from scipy import ndimage
import ortho as O

STEP = 0.01
BAR = 0.07           # frame bars are narrower than twice this (blocks)
OUTER = 0.7          # surfaces at least this fraction of the half-width out from the centre line are the side


class SideEvidence:
    def __init__(self, vid, zc, yc, halfwidth, eye, hl=3.4, hh=1.2):
        tris, uvs, tex = O.model(vid)
        self.c = np.array([0.0, yc, zc]); self.hl, self.hh = hl, hh
        col, dep, _ = O.render(tris, uvs, tex, 'left', self.c, hl, hh, STEP)
        self.col = col
        ny, nx = dep.shape
        self.zs = zc + hl - np.arange(nx) * STEP
        self.ys = yc + hh - np.arange(ny) * STEP
        # the camera plane sits at x = +(something); first-hit x = camera x - depth; only relative values matter
        top = tris[..., 0].max() + 1.0
        xhit = np.where(np.isfinite(dep), 0.0, -np.inf)
        # render() measures depth from the window centre plane (x = 0) along -x: depth = -(x - 0) for view 'left'
        xhit = np.where(np.isfinite(dep), -dep, -np.inf)
        self.xhit = xhit
        outer = np.isfinite(xhit) & (xhit >= OUTER * halfwidth)
        r = int(round(BAR / STEP))
        disk = np.hypot(*np.mgrid[-r:r + 1, -r:r + 1]) <= r
        skin = ndimage.binary_opening(outer, structure=disk)
        # small skin islands (a thick frame joint, a mirror) are not fuselage
        lab, n = ndimage.label(skin)
        if n > 1:
            sizes = np.bincount(lab.ravel())[1:]
            skin = lab == 1 + int(sizes.argmax())
        self.skin = skin
        # sill line: top edge of the skin per column
        self.sill = np.full(nx, -np.inf)
        for i in range(nx):
            j = np.nonzero(skin[:, i])[0]
            if len(j):
                self.sill[i] = self.ys[j.min()]
        frames = outer & ~skin
        frames &= self.ys[:, None] > self.sill[None, :] - 0.01
        lab, n = ndimage.label(frames, structure=np.ones((3, 3)))
        self.frames = []
        self.feet = []
        for k in range(1, n + 1):
            jj, ii = np.nonzero(lab == k)
            if len(jj) < 25:
                continue
            y = self.ys[jj]; z = self.zs[ii]
            if y.max() < eye[1] - 0.3 or abs(z.mean() - eye[2]) > 3.0:
                continue                               # not a canopy frame (antenna base, gun fairing)
            if np.ptp(y) < 0.12 and np.ptp(z) > 0.8:
                continue                               # a wire or a strake lying along the fuselage
            self.frames.append((z, y))
            # feet: frame pixels right on the sill
            on = np.abs(y - self.sill[ii]) < 0.03
            for zz in np.unique(np.round(z[on], 1)):
                m = on & (np.round(z, 1) == zz)
                self.feet.append((float(z[m].mean()), float(y[m].min())))
        self.feet.sort()

    def ends(self):
        if not self.feet:
            return None
        return self.feet[0][0], self.feet[-1][0]
