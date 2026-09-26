#!/usr/bin/env python3
"""Close side views of each canopy for setting its ends by eye: model (alpha-tested), glass silhouette in cyan,
z ticks every 0.1 block (labels every 0.5), the eye in red. Usage: ends.py outdir glass.json id..."""
import sys, os, json, numpy as np
from PIL import Image, ImageDraw
import ortho as O
S = sys.argv[1]; glass = json.load(open(sys.argv[2])); ids = sys.argv[3:] or sorted(glass)
os.makedirs(S, exist_ok=True)
tiles = []
for vid in ids:
    tris, uvs, tex = O.model(vid); eye = O.T.eyes(vid)[0]
    g = np.array(glass.get(vid, {}).get('Triangles', []), float).reshape(-1, 3, 3)
    zmin = g[..., 2].min() if len(g) else eye[2] - 1.5
    zmax = g[..., 2].max() if len(g) else eye[2] + 1.5
    zc = (zmin + zmax) / 2; hw = (zmax - zmin) / 2 + 0.7
    c = np.array([0.0, eye[1], zc]); hh = 0.75; st = 0.006
    col, dep, _ = O.render(tris, uvs, tex, 'left', c + np.array([0, -0.2, 0]), hw, hh, st)
    im = O.image(col, dep, 1); d = ImageDraw.Draw(im, 'RGBA')
    cc = c + np.array([0, -0.2, 0])
    for t in g:
        d.polygon([O.to_pixel(p, 'left', cc, hw, hh, st) for p in t], fill=(0, 230, 255, 18))
    for k in range(int((zc - hw) * 10), int((zc + hw) * 10) + 1):
        z = k / 10
        x, _ = O.to_pixel((0, 0, z), 'left', cc, hw, hh, st)
        L = 10 if k % 5 == 0 else 5
        d.line([(x, 0), (x, L)], fill=(255, 0, 0, 255))
        if k % 5 == 0:
            d.text((x + 2, 10), f'{z:.1f}', fill=(255, 0, 0, 255))
    ex, ey = O.to_pixel(eye, 'left', cc, hw, hh, st)
    d.ellipse([ex - 3, ey - 3, ex + 3, ey + 3], outline=(255, 0, 0, 255))
    d.text((3, im.height - 12), vid, fill=(255, 255, 0, 255))
    im.thumbnail((620, 400)); tiles.append(im)
for s0 in range(0, len(tiles), 6):
    grp = tiles[s0:s0 + 6]; w = max(i.width for i in grp); h = max(i.height for i in grp)
    sh = Image.new('RGB', (w * 2, h * 3), (30, 30, 30))
    for k, i in enumerate(grp): sh.paste(i, ((k % 2) * w, (k // 2) * h))
    sh.save(f'{S}/ends{s0 // 6:02d}.png')
print(len(tiles))
