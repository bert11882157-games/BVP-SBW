#!/usr/bin/env python3
"""Top views of each cockpit with the glass plan outline (cyan) for checking the windscreen base and rear closure
against the model's frames. Usage: plancheck.py outdir id..."""
import sys, os, json, numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage
import ortho as O, review as R
S = sys.argv[1]; ids = sys.argv[2:]
os.makedirs(S, exist_ok=True); tiles = []
for vid in ids:
    tris, uvs, tex = O.model(vid); eye = O.T.eyes(vid)[0]
    g = R.glass_tris(vid)
    zmin, zmax = (g[..., 2].min(), g[..., 2].max()) if len(g) else (eye[2] - 1.5, eye[2] + 1.5)
    zc = (zmin + zmax) / 2; hl = (zmax - zmin) / 2 + 0.6; hw = 1.0; st = 0.006
    top = tris[..., 1].max() + 1
    c = np.array([0.0, top, zc])
    col, dep, _ = O.render(tris, uvs, tex, 'top', c, hw, hl, st)
    im = O.image(col, dep, 1).convert('RGB')
    # glass plan mask -> outline
    m = Image.new('L', im.size, 0); dm = ImageDraw.Draw(m)
    for t in g: dm.polygon([O.to_pixel(p, 'top', c, hw, hl, st) for p in t], fill=255)
    a = np.array(m) > 0
    edge = a & ~ndimage.binary_erosion(a, iterations=2)
    arr = np.array(im); arr[edge] = (0, 255, 255); im = Image.fromarray(arr)
    d = ImageDraw.Draw(im)
    for k in range(int((zc - hl) * 10), int((zc + hl) * 10) + 1):
        _, y = O.to_pixel((0, 0, k / 10), 'top', c, hw, hl, st)
        L = 10 if k % 5 == 0 else 5
        d.line([(0, y), (L, y)], fill=(255, 0, 0))
        if k % 5 == 0: d.text((12, y - 5), f'{k / 10:.1f}', fill=(255, 0, 0))
    d.text((3, 3), vid, fill=(255, 255, 0))
    im = im.transpose(Image.ROTATE_90)
    im.thumbnail((900, 400)); tiles.append(im)
for s0 in range(0, len(tiles), 4):
    grp = tiles[s0:s0 + 4]; w = max(i.width for i in grp); h = max(i.height for i in grp)
    sh = Image.new('RGB', (w * 2, h * 2), (30, 30, 30))
    for k, i in enumerate(grp): sh.paste(i, ((k % 2) * w, (k // 2) * h))
    sh.save(f'{S}/plan{s0 // 4:02d}.png')
print(len(tiles))
