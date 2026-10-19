#!/usr/bin/env python3
"""Review renders of the MiG-29 cockpit (tools/cockpit_models/mig_29.py): the pilot's view, the same view with every
instrument numbered (legend from mig_29.LAYOUT), a side cutaway through the centre line and an outside view.

Usage: preview_mig_29.py OUT_DIR
"""
import json, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import eyeview as E
import mig_29 as M

FONT = ImageFont.truetype(M.FONT, 15) if os.path.exists(M.FONT) else ImageFont.load_default()
FONT_S = ImageFont.truetype(M.FONT, 13) if os.path.exists(M.FONT) else ImageFont.load_default()


def eye():
    data = json.load(open(M.DATA))
    seat = data['Seats'][0]
    return np.array(data['Attachments'][seat['CameraPos']['EyeAttachment']]['Position'], float)


def pilot(out, annotate):
    e = eye(); size = (1400, 980); yaw, pitch, fov = 0.0, -14.0, 80.0
    im = E.render('mig_29', out, eye=e, yaw=yaw, pitch=pitch, fov=fov, size=size)
    if not annotate:
        return
    cam = E.Camera(e, yaw, pitch, fov, *size)
    legend = []
    im = im.convert('RGB'); d = ImageDraw.Draw(im)
    for k, (name, f, a, y, spec, label) in enumerate(M.LAYOUT, 1):
        p = cam.screen(cam.view(f.at(a, y)))
        d.ellipse([p[0] - 13, p[1] - 13, p[0] + 13, p[1] + 13], fill=(255, 214, 0), outline=(0, 0, 0), width=2)
        d.text((p[0], p[1]), str(k), fill=(0, 0, 0), font=FONT_S, anchor='mm')
        how = {'game': f'game gauge {spec[1]}', 'display': f'game display {spec[1]} (CRT)'}.get(spec[0], 'painted')
        legend.append(f'{k:2d}  {label}  [{how}]')
    p = cam.screen(cam.view([0, 3.39, 3.80]))
    d.ellipse([p[0] - 13, p[1] - 13, p[0] + 13, p[1] + 13], fill=(255, 214, 0), outline=(0, 0, 0), width=2)
    d.text((p[0], p[1]), 'H', fill=(0, 0, 0), font=FONT_S, anchor='mm')
    legend.append(' H  ILS-31 head-up display: combiner glass, frame, control strip')
    W = 560
    sheet = Image.new('RGB', (im.width + W, im.height), (24, 26, 28))
    sheet.paste(im, (0, 0)); d = ImageDraw.Draw(sheet)
    d.text((im.width + 16, 16), 'MiG-29 (9.12) front panel', fill=(255, 214, 0), font=FONT)
    for i, line in enumerate(legend):
        d.text((im.width + 16, 48 + i * 24), line, fill=(230, 230, 222), font=FONT_S)
    sheet.save(out)


def cutaway(out):
    """side view from the left with everything left of x = 0.02 cut away"""
    geo = json.load(open(M.GEO)); asset = json.load(open(M.ASSET))
    tex = np.asarray(Image.open(M.TEX).convert('RGBA')).astype(np.float32) / 255.0
    tris, uvs, _, _ = E.model_triangles(geo)
    keep = (tris[:, :, 0].max(1) < 0.02) & (np.abs(tris.mean(1)[:, 2] - 3.3) < 3.0)
    cam = E.Camera([6.5, 2.95, 3.45], -90.0, 0.0, 24.0, 1400, 760)
    r = E.Raster(cam)
    r.draw(tris[keep], uvs[keep], tex)
    E.draw_instruments(r, asset)
    g = np.array(asset['CanopyGlass']['Triangles'], float).reshape(-1, 3, 3)
    g = g[g[:, :, 0].max(1) < 0.02]
    r.draw(g, np.zeros((len(g), 3, 2)), np.ones((1, 1, 4), np.float32), shade=False, alpha_test=False, blend=0.25,
           tint=(0.6, 0.8, 0.9))
    im = r.image().convert('RGB'); d = ImageDraw.Draw(im)
    p = cam.screen(cam.view(eye()))
    d.ellipse([p[0] - 6, p[1] - 6, p[0] + 6, p[1] + 6], fill=(255, 40, 40))
    d.text((p[0] + 10, p[1] - 20), 'pilot eye', fill=(255, 40, 40), font=FONT)
    # sight line over the coaming lip (lowest view over the nose)
    lip = M.CENTRE.at(0, M.TOP_C) + M.CENTRE.N * 0.045 + np.array([0, 0.03, 0])
    q = cam.screen(cam.view(eye() + (lip - eye()) * 3.0))
    d.line([p[0], p[1], q[0], q[1]], fill=(255, 40, 40), width=1)
    ang = np.degrees(np.arctan2(lip[1] - eye()[1], lip[2] - eye()[2]))
    d.text((q[0] - 160, q[1] + 8), f'over-the-nose {ang:.1f} deg', fill=(255, 40, 40), font=FONT)
    for z in np.arange(2.0, 5.01, 0.5):                       # 0.5 m ticks along the bottom
        a = cam.screen(cam.view([0, 2.05, z]))
        d.line([a[0], a[1], a[0], a[1] - 8], fill=(255, 255, 255))
        d.text((a[0], a[1] + 4), f'z {z:.1f}', fill=(255, 255, 255), font=FONT_S, anchor='mt')
    im.save(out)


def outside(out):
    E.render('mig_29', out, eye=[1.7, 4.3, 5.8], yaw=-150.0, pitch=-24.0, fov=42.0, size=(1000, 700))


def main(argv):
    out = argv[0]; os.makedirs(out, exist_ok=True)
    pilot(os.path.join(out, 'mig29_cockpit_pilot_view.png'), False)
    pilot(os.path.join(out, 'mig29_cockpit_layout_numbered.png'), True)
    cutaway(os.path.join(out, 'mig29_cockpit_side_cutaway.png'))
    outside(os.path.join(out, 'mig29_cockpit_outside.png'))


if __name__ == '__main__':
    main(sys.argv[1:])
