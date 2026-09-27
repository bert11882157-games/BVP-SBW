#!/usr/bin/env python3
"""The canopy's plan footprint as the model paints it: seen from above, the glazing on most models is textured
dark (tinted glass) and joins the open cockpit. The footprint is the dark region connected to the cockpit opening
(Evidence.foot), closed over thin frame bars; per station its half-width is the glass outline in plan and its
extents are the canopy's front and rear ends.
Usage (check sheet): footprint.py outdir id..."""
import numpy as np
from scipy import ndimage
import ortho as O
import evidence as E

DARK = 0.17          # mean colour below this is dark paint (tinted glazing)
FULL = 0.85          # dark paint at least this share of the airframe's width is not glazing
BRIDGE = 0.08        # blocks: frame bars narrower than about twice this are bridged


class Footprint:
    def __init__(self, ev):
        tris, uvs, tex = O.model(ev.vid)
        col, dep, _ = O.render(tris, uvs, tex, 'top', ev.centre, ev.hw, ev.hl, E.STEP)
        self.ev = ev
        dark = (col.mean(2) < DARK) & np.isfinite(dep)
        r = int(round(BRIDGE / E.STEP))
        disk = np.hypot(*np.mgrid[-r:r + 1, -r:r + 1]) <= r
        seed = ev.foot
        region = ndimage.binary_closing(dark | seed, structure=disk)
        region = ndimage.binary_fill_holes(region)
        # only the part within the fuselage's width near the cockpit
        xs = ev.xs[None, :]; zs = ev.zs[:, None]
        region &= (np.abs(xs) < 1.0) & (np.abs(zs - ev.eye[2]) < 4.0)
        lab, n = ndimage.label(region)
        keep = set(np.unique(lab[seed])) - {0}
        if not keep:
            j, i = ev.row(ev.eye[2]), ev.col(0.0)
            keep = {lab[j, i]} - {0}
        self.mask = np.isin(lab, list(keep)) if keep else np.zeros_like(region)
        # the painted canopy is symmetric: per row, the run through the centre line, as far as both sides reach
        n = len(ev.zs)
        self.half = np.zeros(n)
        c0 = ev.col(0.0)
        for j in range(n):
            row = self.mask[j]
            if not row[c0]:
                continue
            lo = c0
            while lo > 0 and row[lo - 1]: lo -= 1
            hi = c0
            while hi < len(row) - 1 and row[hi + 1]: hi += 1
            self.half[j] = (min(c0 - lo, hi - c0) + 0.5) * E.STEP
        # the airframe's own half-width per row (the solid run through the centre line): dark paint as wide as
        # the airframe is a painted nose, radome or an all-dark scheme, not glazing, and ends the footprint
        solid = np.isfinite(dep)
        body = np.zeros(n)
        for j in range(n):
            row = solid[j]
            if not row[c0]:
                continue
            lo = c0
            while lo > 0 and row[lo - 1]: lo -= 1
            hi = c0
            while hi < len(row) - 1 and row[hi + 1]: hi += 1
            body[j] = (min(c0 - lo, hi - c0) + 0.5) * E.STEP
        full = (self.half > 0) & (self.half >= FULL * body - 0.02)
        self.body = body
        # the extents: the run of rows with a footprint through the eye row, up to any full-width row
        je = ev.row(ev.eye[2])
        ok = (self.half > 0) & ~full
        if not ok[je]:
            self.painted = 0.0
            self.front = self.rear = float(ev.eye[2]); self.rows = (je, je)
            self.smooth = np.zeros(n)
            return
        a = b = je
        while a > 0 and ok[a - 1]: a -= 1
        while b < n - 1 and ok[b + 1]: b += 1
        self.front, self.rear = float(ev.zs[a]), float(ev.zs[b])
        self.rows = (a, b)
        # the outline smooth: frame rails and bars bite notches into the paint, so a running max bridges them
        # (0.12 block) before a gentle smoothing
        h = ndimage.maximum_filter1d(self.half, 7)
        h = ndimage.gaussian_filter1d(h, 2.5)
        h[:a] = 0; h[b + 1:] = 0
        self.smooth = h
        # how much of the footprint is painted glass rather than open cockpit (models with clear canopies paint none)
        m = self.mask[a:b + 1]
        self.painted = float((m & dark[a:b + 1]).sum()) / max(1, m.sum())

    def halfwidth(self, z):
        """Plan half-width at station z (smoothed over 0.1 block)."""
        ev = self.ev
        a, b = self.rows
        zz = ev.zs[a:b + 1][::-1]; hh = self.smooth[a:b + 1][::-1]
        return np.interp(z, zz, hh, left=0.0, right=0.0)


if __name__ == '__main__':
    import sys, os, json
    from PIL import Image, ImageDraw
    import review as R
    S = sys.argv[1]; ids = sys.argv[2:]
    os.makedirs(S, exist_ok=True); tiles = []; res = {}
    for vid in ids:
        ev = E.Evidence(vid); fp = Footprint(ev)
        tris, uvs, tex = O.model(vid)
        col, dep, _ = O.render(tris, uvs, tex, 'top', ev.centre, ev.hw, ev.hl, E.STEP)
        im = O.image(col, dep, 1).convert('RGB')
        a = np.array(im)
        edge = fp.mask & ~ndimage.binary_erosion(fp.mask)
        a[edge] = (255, 0, 255)
        g = R.glass_tris(vid)
        m = Image.new('L', im.size, 0); dm = ImageDraw.Draw(m)
        for t in g: dm.polygon([O.to_pixel(p, 'top', ev.centre, ev.hw, ev.hl, E.STEP) for p in t], fill=255)
        gm = np.array(m) > 0
        a[gm & ~ndimage.binary_erosion(gm)] = (0, 255, 255)
        im = Image.fromarray(a); d = ImageDraw.Draw(im)
        for zz in (fp.front, fp.rear):
            y = ev.row(zz); d.line([(0, y), (im.width, y)], fill=(255, 255, 0))
        a0, b0 = fp.rows
        pad = 15
        im = im.crop((0, max(0, a0 - pad), im.width, min(im.height, b0 + pad)))
        ImageDraw.Draw(im).text((3, 3), f'{vid} {fp.front:.2f}..{fp.rear:.2f}', fill=(255, 255, 0))
        im = im.transpose(Image.ROTATE_90)
        im = im.resize((im.width * 3, im.height * 3), Image.NEAREST)
        tiles.append(im); res[vid] = (fp.front, fp.rear, float(g[..., 2].max()) if len(g) else None, float(g[..., 2].min()) if len(g) else None)
        print(f'{vid:30s} painted {fp.painted:.2f} foot {fp.front:5.2f}..{fp.rear:5.2f}  glass {res[vid][2]}..{res[vid][3]}', flush=True)
    for s0 in range(0, len(tiles), 6):
        grp = tiles[s0:s0 + 6]; w = max(i.width for i in grp); h = max(i.height for i in grp)
        sh = Image.new('RGB', (w * 2, h * 3), (30, 30, 30))
        for k, i in enumerate(grp): sh.paste(i, ((k % 2) * w, (k // 2) * h))
        sh.thumbnail((2400, 2400)); sh.save(f'{S}/foot{s0 // 6:02d}.png')
    json.dump(res, open(f'{S}/foot.json', 'w'))
