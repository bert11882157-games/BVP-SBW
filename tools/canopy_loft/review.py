#!/usr/bin/env python3
"""Side and top views of the cockpit with the canopy glass drawn over it (cyan edges), for checking by eye.
Usage: python3 tools/canopy_loft/review.py outdir id...  [--glass=file.json to show glass from a file instead]"""
import json, os, sys
import numpy as np
from PIL import Image, ImageDraw
import ortho as O

ASSETS = os.path.join(O.T.GEN, 'assets/berts_vehicle_pack/sbw/vehicles')


def glass_tris(vid, override=None):
    if override is not None:
        block = override.get(vid)
    else:
        block = json.load(open(os.path.join(ASSETS, vid + '.json'))).get('CanopyGlass')
    if not block:
        return np.zeros((0, 3, 3))
    return np.array(block['Triangles'], float).reshape(-1, 3, 3)


def sheet(vid, out, override=None, extra=()):
    tris, uvs, tex = O.model(vid)
    eye = O.T.eyes(vid)[0]
    g = glass_tris(vid, override)
    centre = np.array([0.0, eye[1] + 0.1, eye[2]])
    hw, hh, step = 3.2, 1.3, 0.01
    panels = []
    for view, (w, h) in (('left', (hw, hh)), ('top', (1.4, hw)), ('front', (1.6, hh))):
        c = centre if view != 'top' else np.array([0.0, eye[1], eye[2]])
        col, dep, _ = O.render(tris, uvs, tex, view, c, w, h, step)
        im = O.image(col, dep, scale=1)
        dr = ImageDraw.Draw(im, 'RGBA')
        for t in g:
            pts = [O.to_pixel(p, view, c, w, h, step) for p in t]
            dr.polygon(pts, outline=(0, 255, 255, 110))
        for p, colr in extra:
            x, y = O.to_pixel(p, view, c, w, h, step)
            dr.ellipse([x - 3, y - 3, x + 3, y + 3], fill=colr)
        ex, ey = O.to_pixel(eye, view, c, w, h, step)
        dr.ellipse([ex - 4, ey - 4, ex + 4, ey + 4], outline=(255, 0, 0, 255))
        dr.text((4, 4), f'{vid} {view}', fill=(255, 255, 0, 255))
        panels.append(im)
    W = panels[0].width + panels[1].width
    H = max(panels[0].height + panels[2].height, panels[1].height)
    s = Image.new('RGB', (W, H), (40, 40, 40))
    s.paste(panels[0], (0, 0)); s.paste(panels[2], (0, panels[0].height)); s.paste(panels[1], (panels[0].width, 0))
    s.save(out)


if __name__ == '__main__':
    outdir = sys.argv[1]
    os.makedirs(outdir, exist_ok=True)
    override = None
    for a in sys.argv[2:]:
        if a.startswith('--glass='):
            override = json.load(open(a[8:]))
    for vid in [a for a in sys.argv[2:] if not a.startswith('--')]:
        sheet(vid, os.path.join(outdir, vid + '.png'), override)
        print(vid, flush=True)
