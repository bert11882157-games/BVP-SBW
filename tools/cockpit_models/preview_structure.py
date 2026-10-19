#!/usr/bin/env python3
"""Review renders of a structured cockpit (structure.py): the pilot's view, the same view with every plate
numbered (legend from plates/<vid>.json), the view down over the consoles, the cockpit from the side with the
canopy off, and a side cutaway through the centre line.

Usage: preview_structure.py OUT_DIR vid...
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import eyeview as E
import cockpit_lib as L

FONT_P = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
FONT = ImageFont.truetype(FONT_P, 15) if os.path.exists(FONT_P) else ImageFont.load_default()
FONT_S = ImageFont.truetype(FONT_P, 13) if os.path.exists(FONT_P) else ImageFont.load_default()
SKIP_OPEN = ('pilot_view_occluder', 'canopy', 'wreck_fuselage_0__canopy', 'wreck_fuselage_1__canopy',
             'wreck_fuselage_2__canopy')


def pilot(vid, out, plates, annotate, size=(1400, 980), yaw=0.0, pitch=-16.0, fov=84.0):
    ac = L.Aircraft(vid)
    e = ac.eye(0)
    im = E.render(vid, out, eye=e, yaw=yaw, pitch=pitch, fov=fov, size=size)
    if not annotate:
        return
    cam = E.Camera(e, yaw, pitch, fov, *size)
    im = im.convert('RGB'); d = ImageDraw.Draw(im)
    legend = []
    for k, p in enumerate(plates['plates'], 1):
        c = np.array(p['centre'])
        v = cam.view(c)
        if v[2] < 0.05:
            continue
        q = cam.screen(v)
        # the plate outline
        R, U = np.array(p['right']), np.array(p['up'])
        hw, hh = p['width'] / 2, p['height'] / 2
        corners = [c - R * hw - U * hh, c + R * hw - U * hh, c + R * hw + U * hh, c - R * hw + U * hh]
        pts = [tuple(cam.screen(cam.view(x))) for x in corners if cam.view(x)[2] > 0.05]
        if len(pts) == 4:
            d.polygon(pts, outline=(255, 214, 0))
        d.ellipse([q[0] - 13, q[1] - 13, q[0] + 13, q[1] + 13], fill=(255, 214, 0), outline=(0, 0, 0), width=2)
        d.text((q[0], q[1]), str(k), fill=(0, 0, 0), font=FONT_S, anchor='mm')
        legend.append(f'{k:2d}  {p["name"]}: {p["label"]}  ({p["width"]*100:.0f} x {p["height"]*100:.0f} cm)')
    W = 620
    sheet = Image.new('RGB', (im.width + W, im.height), (24, 26, 28))
    sheet.paste(im, (0, 0)); d = ImageDraw.Draw(sheet)
    d.text((im.width + 16, 16), f'{vid}: panel plates (stage 1, no instruments yet)', fill=(255, 214, 0), font=FONT)
    for i, line in enumerate(legend):
        d.text((im.width + 16, 48 + i * 24), line, fill=(230, 230, 222), font=FONT_S)
    sheet.save(out)


def cutaway(vid, out, size=(1400, 760)):
    """side view from the left with everything left of the centre line cut away"""
    ac = L.Aircraft(vid)
    e = ac.eye(0)
    tex = np.asarray(Image.open(L.texture_path(vid)).convert('RGBA')).astype(np.float32) / 255.0
    tris, uvs, _, _ = E.model_triangles(ac.geo, ('pilot_view_occluder',))
    keep = (tris[:, :, 0].max(1) < e[0] + 0.02) & (np.abs(tris.mean(1)[:, 2] - e[2] - 0.3) < 3.0)
    cam = E.Camera([e[0] + 5.0, e[1] + 0.35, e[2] + 0.35], -90.0, -4.0, 34.0, *size)
    r = E.Raster(cam)
    r.draw(tris[keep], uvs[keep], tex)
    E.draw_instruments(r, ac.asset)
    im = r.image().convert('RGB'); d = ImageDraw.Draw(im)
    p = cam.screen(cam.view(e))
    d.ellipse([p[0] - 6, p[1] - 6, p[0] + 6, p[1] + 6], fill=(255, 40, 40))
    d.text((p[0] + 10, p[1] - 20), 'pilot eye', fill=(255, 40, 40), font=FONT)
    for z in np.arange(math.floor(e[2] - 1.0), e[2] + 2.01, 0.5):
        a = cam.screen(cam.view([e[0], e[1] - 1.2, z]))
        d.line([a[0], a[1], a[0], a[1] - 8], fill=(255, 255, 255))
        d.text((a[0], a[1] + 4), f'z {z:.1f}', fill=(255, 255, 255), font=FONT_S, anchor='mt')
    im.save(out)


def consoles(vid, out):
    """the two consoles from above the seat, looking down and outward"""
    e = L.Aircraft(vid).eye(0)
    for tag, sg in (('l', 1), ('r', -1)):
        E.render(vid, os.path.join(out, f'{vid}_console_{tag}.png'), eye=[e[0] - sg * 0.1, e[1] + 0.25, e[2] - 0.1],
                 yaw=sg * 55.0, pitch=-58.0, fov=75.0, size=(1000, 760), glass=False, skip=SKIP_OPEN)


def main(argv):
    out = argv[0]; os.makedirs(out, exist_ok=True)
    for vid in argv[1:]:
        plates = json.load(open(os.path.join(HERE, 'plates', vid + '.json')))
        e = L.Aircraft(vid).eye(0)
        pilot(vid, os.path.join(out, f'{vid}_pilot.png'), plates, False)
        pilot(vid, os.path.join(out, f'{vid}_plates.png'), plates, True)
        pilot(vid, os.path.join(out, f'{vid}_down.png'), plates, False, pitch=-50.0, fov=92.0)
        E.render(vid, os.path.join(out, f'{vid}_open.png'), eye=[e[0] + 1.3, e[1] + 0.55, e[2] - 0.5], yaw=-70.0,
                 pitch=-26.0, fov=70.0, size=(1400, 980), glass=False, skip=SKIP_OPEN)
        E.render(vid, os.path.join(out, f'{vid}_open_r.png'), eye=[e[0] - 1.3, e[1] + 0.55, e[2] - 0.5], yaw=70.0,
                 pitch=-26.0, fov=70.0, size=(1400, 980), glass=False, skip=SKIP_OPEN)
        E.render(vid, os.path.join(out, f'{vid}_quarter.png'), eye=[e[0] + 1.6, e[1] + 1.3, e[2] + 2.4], yaw=-145.0,
                 pitch=-30.0, fov=50.0, size=(1400, 980), glass=False, skip=SKIP_OPEN)
        cutaway(vid, os.path.join(out, f'{vid}_cutaway.png'))
        consoles(vid, out)
        print(vid)


if __name__ == '__main__':
    main(sys.argv[1:])
