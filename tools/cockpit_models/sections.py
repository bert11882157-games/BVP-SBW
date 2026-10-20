#!/usr/bin/env python3
"""Cross-sections of an aircraft model around the cockpit, for measuring a hand-built cockpit against the mesh.

Draws, on one sheet, the model cut by planes: transverse cuts (x-y, at the given z stations) and the centre-line cut
(z-y at x = eye x). Old canopy glass (CanopyGlass) is drawn in blue, the hull in black, an existing __cockpit bone in
orange; the eye is a red cross. A grid every 5 cm (bold every 25 cm) is labelled in metres from the eye.

Usage: sections.py VID OUT.png [--z dz1,dz2,...]   (dz: metres ahead of the eye; default -0.6..1.6 every 0.2)
       [--geo path] [--asset path]   (another geo / asset file, e.g. the original from git)
"""
import json, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402


def arg(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


def triangles(geo):
    out = []
    for b in geo['minecraft:geometry'][0]['bones']:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        kind = 'cockpit' if b['name'].endswith('__cockpit') else 'hull'
        for poly in pm['polys']:
            if not poly:
                continue
            V = P[[v[0] for v in poly]]
            for k in range(1, len(V) - 1):
                out.append((V[[0, k, k + 1]], kind))
    return out


def cut(tris, axis, value):
    """segments where the plane coordinate[axis] = value cuts the triangles: [(p, q, kind)]"""
    segs = []
    for T, kind in tris:
        d = T[:, axis] - value
        if d.max() < 0 or d.min() > 0:
            continue
        pts = []
        for i in range(3):
            a, b = T[i], T[(i + 1) % 3]
            da, db = d[i], d[(i + 1) % 3]
            if da == 0:
                pts.append(a)
            if da * db < 0:
                pts.append(a + (b - a) * (da / (da - db)))
        if len(pts) >= 2:
            segs.append((pts[0], pts[1], kind))
    return segs


def main():
    vid, out = sys.argv[1], sys.argv[2]
    ac = L.Aircraft(vid)
    geo = json.load(open(arg('--geo'))) if arg('--geo') else ac.geo
    asset = json.load(open(arg('--asset'))) if arg('--asset') else ac.asset
    E = ac.eye(0)
    tris = triangles(geo)
    cg = asset.get('CanopyGlass')
    if cg:
        G = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
        tris += [(t, 'glass') for t in G]
    zs = [float(v) for v in arg('--z', '-0.6,-0.4,-0.2,0,0.2,0.4,0.6,0.8,1.0,1.2,1.4,1.6').split(',')]
    PX = 300                                   # pixels per metre
    W, H = int(1.4 * PX), int(1.7 * PX)        # one transverse panel: x +-0.7, y +0.4..-1.3 from the eye
    cols = 4
    rows = (len(zs) + cols - 1) // cols
    side_w, side_h = int(2.8 * PX), H          # centre-line cut: z -1.0..+1.8
    sheet = Image.new('RGB', (max(cols * W, side_w), rows * H + side_h + 30), 'white')
    d = ImageDraw.Draw(sheet)
    try:
        f = ImageFont.truetype('arial.ttf', 14)
    except OSError:
        f = ImageFont.load_default()
    col = {'hull': (20, 20, 20), 'glass': (40, 110, 230), 'cockpit': (230, 120, 20)}

    def grid(ox, oy, w, h, x0, y0, flip):
        for k in range(-40, 41):
            m = k * 0.05
            gx = ox + (m - x0) * PX if not flip else ox + (x0 - m) * PX
            gy = oy + (y0 - m) * PX
            c = (200, 200, 200) if k % 5 else (150, 150, 150)
            if ox <= gx <= ox + w:
                d.line([gx, oy, gx, oy + h], fill=c)
            if oy <= gy <= oy + h:
                d.line([ox, gy, ox + w, gy], fill=c)
                if k % 5 == 0:
                    d.text((ox + 2, gy - 14), f'{m:+.2f}', fill=(120, 120, 120), font=f)

    full_d = d
    for n, dz in enumerate(zs):
        panel = Image.new('RGB', (W, H), 'white')
        d = ImageDraw.Draw(panel)
        ox, oy = 0, 0
        # x: pilot's left (+x) drawn on the left, as seen from behind looking forward
        grid(ox, oy, W, H, 0.7, 0.4, True)
        for p, q, kind in cut(tris, 2, E[2] + dz):
            a = (ox + (0.7 - (p[0] - E[0])) * PX, oy + (0.4 - (p[1] - E[1])) * PX)
            b = (ox + (0.7 - (q[0] - E[0])) * PX, oy + (0.4 - (q[1] - E[1])) * PX)
            d.line([a, b], fill=col[kind], width=2)
        cx, cy = ox + 0.7 * PX, oy + 0.4 * PX
        d.line([cx - 6, cy, cx + 6, cy], fill=(220, 0, 0), width=2); d.line([cx, cy - 6, cx, cy + 6], fill=(220, 0, 0), width=2)
        d.rectangle([ox, oy, ox + W - 1, oy + H - 1], outline=(0, 0, 0))
        d.text((ox + W - 90, oy + 4), f'z {dz:+.2f}', fill=(0, 0, 0), font=f)
        sheet.paste(panel, ((n % cols) * W, (n // cols) * H))
    d = full_d
    oy = rows * H + 30
    d.text((4, oy - 22), f'{vid}: centre-line cut (forward to the right), metres from the eye {np.round(E, 3).tolist()}',
           fill=(0, 0, 0), font=f)
    grid(0, oy, side_w, side_h, -1.0, 0.4, False)
    for p, q, kind in cut(tris, 0, E[0]):
        a = ((p[2] - E[2] + 1.0) * PX, oy + (0.4 - (p[1] - E[1])) * PX)
        b = ((q[2] - E[2] + 1.0) * PX, oy + (0.4 - (q[1] - E[1])) * PX)
        d.line([a, b], fill=col[kind], width=2)
    cx, cy = 1.0 * PX, oy + 0.4 * PX
    d.line([cx - 6, cy, cx + 6, cy], fill=(220, 0, 0), width=2); d.line([cx, cy - 6, cx, cy + 6], fill=(220, 0, 0), width=2)
    for dz in zs:
        x = (dz + 1.0) * PX
        d.line([x, oy, x, oy + 8], fill=(220, 0, 0), width=2)
    sheet.save(out)


if __name__ == '__main__':
    main()
