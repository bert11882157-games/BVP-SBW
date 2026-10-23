#!/usr/bin/env python3
"""Rear three-quarter pictures for nozzle_check.py: the offline eyeview rasteriser draws the model, a translucent
flame cone (Outlet position, NozzleRadiusBlocks, Flame.LengthBlocks, aft along the outlet direction) is blended in
with a depth test, the measured nozzle exit is outlined in green with a cross at its centre, and every authored
emitter is a red cross (outlets) or a yellow dot (TaP emitters).

Usage: nozzle_sheet.py OUT.jpg VID [VID...]   (two columns of rear views, about 1100 px wide)
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import eyeview as E          # noqa: E402
import nozzle_check as C     # noqa: E402

CELL = (550, 380)


def lookat(eye, target, fov, w, h):
    d = np.array(target, float) - np.array(eye, float)
    yaw = math.degrees(math.atan2(d[0], d[2]))
    pitch = math.degrees(math.asin(d[1] / np.linalg.norm(d)))
    return E.Camera(eye, yaw, pitch, fov, w, h)


def project(cam, p):
    v = cam.view(np.asarray(p, float))
    if v[2] <= 0.05:
        return None
    s = cam.screen(v)
    return float(s[0]), float(s[1]), float(v[2])


def flame(raster, cam, pos, direction, radius, length):
    """Blend a flame cone: discs from the nozzle aft, hot white core to orange, depth tested."""
    h, w = raster.depth.shape
    steps = 26
    for i in range(steps, -1, -1):          # far to near
        t = i / steps
        c = np.asarray(pos, float) + np.asarray(direction, float) * length * t
        rad = radius * (1.0 - 0.55 * t) * (1.0 + 0.25 * math.sin(t * 9.0) * t)
        pr = project(cam, c)
        if pr is None:
            continue
        sx, sy, z = pr
        rr = cam.focal * rad / z
        x0, x1 = int(max(0, sx - rr - 1)), int(min(w - 1, sx + rr + 1))
        y0, y1 = int(max(0, sy - rr - 1)), int(min(h - 1, sy + rr + 1))
        if x1 <= x0 or y1 <= y0:
            continue
        X, Y = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        m = ((X - sx) ** 2 + (Y - sy) ** 2 <= rr * rr) & (raster.depth[y0:y1 + 1, x0:x1 + 1] > z)
        if not m.any():
            continue
        core = np.array([1.0, 0.95, 0.8]); mid = np.array([1.0, 0.55, 0.18]); tail = np.array([0.95, 0.25, 0.1])
        col = core * (1 - min(1, t * 2.2)) + mid * min(1, t * 2.2)
        col = col * (1 - max(0, t - 0.5) * 1.2) + tail * max(0, t - 0.5) * 1.2
        a = 0.16 * (1.0 - 0.6 * t)
        blk = raster.color[y0:y1 + 1, x0:x1 + 1]
        blk[m] = blk[m] * (1 - a) + col * a


# per-jet rear camera (eye offset from the nozzle centre) where the default view hides a nozzle behind a fin or tail plane
EYES = {'a_10': (-0.8, 3.6, -10.5), 'a_10a': (-0.8, 3.6, -10.5), 'f_4c': (2.6, 0.9, -9.0), 'f_4d': (2.6, 0.9, -9.0),
        'f_4e': (2.6, 0.9, -9.0), 'f_4b': (2.6, 0.9, -9.0)}


def visible(raster, pr, slack=0.25):
    x, y, z = int(pr[0]), int(pr[1]), pr[2]
    h, w = raster.depth.shape
    return 0 <= x < w and 0 <= y < h and raster.depth[y, x] > z - slack


def render_cell(vid, res, mode='rear', size=CELL):
    geo = json.load(open(os.path.join(E.GEO, vid + '.geo.json')))
    tris, uvs, _, _ = E.model_triangles(geo, ('pilot_view_occluder',))
    tex = np.asarray(Image.open(os.path.join(E.TEX, vid + '.png')).convert('RGBA')).astype(np.float32) / 255.0
    noz = res['nozzles']
    rows, asset, ab = C.emitters(vid)
    zaft = min(n['z'] for n in noz) if noz else -7.0
    xs = [n['x'] for n in noz] or [0.0]
    cx = float(np.mean(xs))
    cy = float(np.mean([n['y'] for n in noz])) if noz else 2.0
    if mode == 'rear':
        target = (cx * 0.4, cy, zaft + 1.2)
        eye = (cx + 6.5, cy + 3.4, zaft - 9.5)
        if vid in EYES:
            eye = (cx + EYES[vid][0], cy + EYES[vid][1], zaft + EYES[vid][2])
        fov = 38
    else:
        target = (cx * 0.3, cy, zaft)
        eye = (cx + 2.4, cy + 1.2, zaft - 3.4)
        fov = 36
    cam = lookat(eye, target, fov, *size)
    r = E.Raster(cam)
    keep = np.linalg.norm(tris.mean(1) - np.array(target), axis=1) < 16
    r.draw(tris[keep], uvs[keep], tex)
    length = (ab.get('Flame') or {}).get('LengthBlocks', 5)
    for o in ab.get('Outlets', []):
        flame(r, cam, o['Position'], o.get('Direction', [0, 0, -1]), o.get('NozzleRadiusBlocks', 0.42), min(length, 6.0))
    im = r.image().convert('RGB')
    d = ImageDraw.Draw(im)
    for n in noz:                                  # measured exit outline
        pts = []
        for k in range(49):
            a = 2 * math.pi * k / 48
            if n['how'].startswith('rig'):
                ex, ey = n['w'] / 2 * math.cos(a), n['h'] / 2 * math.sin(a)
                ex, ey = (max(-1, min(1, ex / (n['w'] / 2) * 1.25)) * n['w'] / 2,
                          max(-1, min(1, ey / (n['h'] / 2) * 1.25)) * n['h'] / 2)
            else:
                ex, ey = n['r'] * math.cos(a), n['r'] * math.sin(a)
            pts.append(project(cam, (n['x'] + ex, n['y'] + ey, n['z'])))
        for p0, p1 in zip(pts, pts[1:]):             # outline segments hidden behind other geometry are left out
            if p0 and p1 and visible(r, p0) and visible(r, p1):
                d.line([(p0[0], p0[1]), (p1[0], p1[1])], fill=(40, 255, 60), width=2)
        pr = project(cam, (n['x'], n['y'], n['z']))
        if pr and visible(r, pr):
            d.line([pr[0] - 6, pr[1], pr[0] + 6, pr[1]], fill=(40, 255, 60), width=2)
            d.line([pr[0], pr[1] - 6, pr[0], pr[1] + 6], fill=(40, 255, 60), width=2)
    for label, pos, rad, kind in rows:
        pr = project(cam, pos)
        if not pr:
            continue
        if kind == 'outlet':
            d.line([pr[0] - 7, pr[1] - 7, pr[0] + 7, pr[1] + 7], fill=(255, 40, 40), width=2)
            d.line([pr[0] - 7, pr[1] + 7, pr[0] + 7, pr[1] - 7], fill=(255, 40, 40), width=2)
        else:
            d.ellipse([pr[0] - 3, pr[1] - 3, pr[0] + 3, pr[1] + 3], outline=(255, 235, 0))
    d.rectangle([0, 0, size[0] - 1, 15], fill=(0, 0, 0))
    status = 'PASS' if not res['fails'] else 'FAIL'
    d.text((4, 2), f'{vid}  {mode}  {status}  green = measured exit, red x = flame/haze outlet, yellow = TaP', fill=(255, 255, 255))
    return im


def make(ids, out, results=None, mode='rear'):
    results = results or [C.check(v, quiet=True) for v in ids]
    cells = [render_cell(v, r, mode) for v, r in zip(ids, results)]
    cols = 2
    rows = (len(cells) + cols - 1) // cols
    sheet = Image.new('RGB', (CELL[0] * cols, CELL[1] * rows), (20, 20, 20))
    for i, im in enumerate(cells):
        sheet.paste(im, ((i % cols) * CELL[0], (i // cols) * CELL[1]))
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    sheet.save(out, quality=90)
    print('wrote', out, sheet.size)


if __name__ == '__main__':
    args = sys.argv[1:]
    mode = 'rear'
    if args and args[0] == '--close':
        mode = 'close'; args = args[1:]
    make(args[1:], args[0], mode=mode)
