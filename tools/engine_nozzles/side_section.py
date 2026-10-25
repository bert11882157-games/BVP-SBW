#!/usr/bin/env python3
"""Side section through each exhaust outlet: the faces within a nozzle radius or so of the outlet's x, seen from the
side (+X), drawn far to near, with the outlet (red cross) and its flow direction. For checking by eye where the
flame starts against the nozzle exit.

Usage: python3 tools/engine_nozzles/side_section.py <out_dir> ids...
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import nozzles as N  # noqa: E402

PX = 60             # pixels per block


def section(vid, out_dir):
    tris = N.model_triangles(vid)
    outlets = N.existing(vid)
    seen = set()
    for i, (p, r) in enumerate(outlets):
        key = (round(abs(p[0]), 2), round(p[1], 2), round(p[2], 2))
        if key in seen:
            continue                     # the mirror twin shows the same
        seen.add(key)
        r = r or 0.3
        half = max(1.3 * r, 0.25)
        cx = tris[:, :, 0]
        keep = (cx.max(1) >= p[0] - half) & (cx.min(1) <= p[0] + half)
        near = tris[keep]
        z0, z1 = p[2] - 4.0, p[2] + 6.0
        y0, y1 = p[1] - 2.5, p[1] + 2.5
        w, h = int((z1 - z0) * PX), int((y1 - y0) * PX)
        im = Image.new('RGB', (w, h), (16, 16, 24))
        d = ImageDraw.Draw(im)
        # far side first (smaller x drawn first when looking from +X)
        order = np.argsort(near[:, :, 0].mean(1))
        for t in near[order]:
            xs = [(z1 - v[2]) * PX for v in t]          # nose (+Z) to the left
            ys = [(y1 - v[1]) * PX for v in t]
            n = np.cross(t[1] - t[0], t[2] - t[0])
            ln = np.linalg.norm(n)
            shade = 0.35 + 0.65 * abs(n[0]) / ln if ln > 0 else 0.5
            c = int(70 + 150 * shade)
            d.polygon(list(zip(xs, ys)), fill=(c, c, c), outline=(c - 30, c - 30, c - 30))
        for gz in np.arange(np.ceil(z0), z1, 1.0):
            gx = (z1 - gz) * PX
            d.line([gx, 0, gx, h], fill=(50, 50, 120))
            d.text((gx + 2, 2), f'{gz:.0f}', fill=(140, 140, 255))
        ox, oy = (z1 - p[2]) * PX, (y1 - p[1]) * PX
        d.line([ox - 10, oy, ox + 10, oy], fill=(255, 40, 40), width=3)
        d.line([ox, oy - 10, ox, oy + 10], fill=(255, 40, 40), width=3)
        d.line([ox, oy - r * PX, ox, oy + r * PX], fill=(255, 160, 40), width=1)
        d.text((4, h - 14), f'{vid} outlet {i} x {p[0]:+.2f} z {p[2]:.2f} r {r}', fill=(255, 255, 0))
        im.save(os.path.join(out_dir, f'{vid}_side{i}.png'))


def main(argv):
    out_dir = argv[0]
    os.makedirs(out_dir, exist_ok=True)
    for vid in argv[1:]:
        section(vid, out_dir)


if __name__ == '__main__':
    main(sys.argv[1:])
