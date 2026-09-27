#!/usr/bin/env python3
"""Preview of cockpit authoring files: side and top views of the cut cockpit (textured), the context in grey, the
displays (magenta) and gauges (yellow). Usage: preview.py out.png file.cockpit.bbmodel|file.cockpit.geo.json..."""
import sys, os, json, copy
import numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'canopy_loft'))
import ortho as O
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cockpit
T = O.T
out = sys.argv[1]; files = sys.argv[2:]
rows = []
for path in files:
    geo = cockpit.load_edited(path); vid = os.path.basename(path).split('.')[0]
    bones = geo['minecraft:geometry'][0]['bones']
    def pick(pred):
        g = copy.deepcopy(geo); g['minecraft:geometry'][0]['bones'] = [b for b in bones if pred(b['name'])]
        try:
            return T.triangles(g)
        except ValueError:
            return np.zeros((0, 3, 3)), np.zeros((0, 3, 2))
    cock, cuv = pick(lambda n: not n.startswith(('ref_', 'display', 'gauge', 'unused_')))
    ctx, xuv = pick(lambda n: n == 'ref_context')
    disp, _ = pick(lambda n: n.startswith(('display__', 'unused_display')))
    gau, _ = pick(lambda n: n.startswith('gauge__'))
    tex = T.load_texture(vid)
    allp = cock.reshape(-1, 3)
    c = (allp.min(0) + allp.max(0)) / 2; half = (allp.max(0) - allp.min(0)) / 2 + 0.3
    tiles = []
    for view, hw, hh in (('left', half[2], half[1]), ('top', half[0], half[2])):
        col, dep, _ = O.render(np.concatenate([cock, ctx]) if len(ctx) else cock,
                               np.concatenate([cuv, xuv]) if len(ctx) else cuv, tex, view, c, hw, hh, 0.01)
        _, dep2, idx = O.render(np.concatenate([cock, ctx]) if len(ctx) else cock,
                                np.concatenate([cuv, xuv]) if len(ctx) else cuv, tex, view, c, hw, hh, 0.01)
        im = O.image(col, dep, 1).convert('RGB'); a = np.array(im).astype(float)
        ctxmask = (idx >= len(cock)) & (idx >= 0)
        a[ctxmask] = a[ctxmask] * 0.35 + 110
        im = Image.fromarray(a.astype(np.uint8)); d = ImageDraw.Draw(im)
        for tris, colr in ((disp, (255, 0, 255)), (gau, (255, 230, 0))):
            for t in tris:
                d.polygon([O.to_pixel(p, view, c, hw, hh, 0.01) for p in t], outline=colr)
        tiles.append(im)
    w = sum(t.width for t in tiles); h = max(t.height for t in tiles)
    row = Image.new('RGB', (w, h + 14), (30, 30, 30)); x = 0
    for t in tiles: row.paste(t, (x, 14)); x += t.width
    ImageDraw.Draw(row).text((3, 1), vid, fill=(255, 255, 0)); rows.append(row)
W = max(r.width for r in rows); H = sum(r.height for r in rows)
sheet = Image.new('RGB', (W, H), (30, 30, 30)); y = 0
for r in rows: sheet.paste(r, (0, y)); y += r.height
sheet.thumbnail((1800, 2400)); sheet.save(out)
