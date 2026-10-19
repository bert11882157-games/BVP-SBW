#!/usr/bin/env python3
"""Orthographic top-down and side views of each aircraft's crew area, for marking the canopy glass bounds by hand.

Each image covers the crew stations (every seat's eye, with a margin, so glazed noses and gunner stations are in),
at one scale for both views, with a grid in vehicle-local blocks (thin every 0.25, labelled every 1.0). A JSON file
beside each image records the projection, so marks drawn on the image map back to model coordinates:
  top view  (looking down, nose up):        pixel (u, v) -> x = cx - (u - W/2) / scale, z = cz - (v - H/2) / scale
  side view (from the left, nose to the left): pixel (u, v) -> z = cz - (u - W/2) / scale, y = cy - (v - H/2) / scale
The model is drawn as it is (no canopy glass, no instruments).

Usage: python3 tools/canopy_models/ortho.py OUTDIR [id ...]   (no ids: every Airplane and Helicopter)
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'cockpit_models'))
import eyeview as EV  # noqa: E402

LONG_SIDE = 2000                  # pixels along the image's longer side
DEPTH = 1000.0                    # depth offset: keeps every point in front of the near plane


class Ortho:
    """an orthographic camera with eyeview.Camera's interface: view() gives (right, up, depth), screen() pixels"""

    def __init__(self, centre, right, up, fwd, scale, w, h, depth_ref):
        self.c, self.R = np.array(centre, float), np.stack([right, up, fwd]).astype(float)
        self.scale, self.w, self.h, self.depth_ref = scale, w, h, depth_ref

    def view(self, p):
        v = (np.asarray(p, float) - self.c) @ self.R.T
        v[..., 2] = v[..., 2] - self.depth_ref + DEPTH
        return v

    def screen(self, v):
        return np.stack([self.w / 2 + self.scale * v[..., 0], self.h / 2 - self.scale * v[..., 1]], -1)


def crew_box(data):
    eyes = []
    for seat in data.get('Seats', []):
        name = (seat.get('CameraPos') or {}).get('EyeAttachment')
        if name and name in data.get('Attachments', {}):
            eyes.append(data['Attachments'][name]['Position'])
    return np.array(eyes, float)


def views(vid, out_dir):
    geo = json.load(open(os.path.join(EV.GEO, vid + '.geo.json'), encoding='utf-8'))
    data = json.load(open(os.path.join(EV.DATA, vid + '.json'), encoding='utf-8'))
    tex = np.asarray(Image.open(os.path.join(EV.TEX, vid + '.png')).convert('RGBA')).astype(np.float32) / 255.0
    # rotors, propellers and fan blades would lie across the canopy in the top view: left out
    import re
    spin = re.compile(r'(rotor|propeller|blade|turbine_fan|spinner)')
    bones = geo['minecraft:geometry'][0]['bones']
    geo['minecraft:geometry'][0]['bones'] = [b for b in bones if not spin.search(b['name'].split('__')[-1])]
    tris, uvs, _, _ = EV.model_triangles(geo, ('pilot_view_occluder',))
    eyes = crew_box(data)
    if not len(eyes):
        eyes = np.array([tris.reshape(-1, 3).mean(0)])
    lo, hi = eyes.min(0), eyes.max(0)
    # the region: the crew stations, 2.5 ahead / 2.0 behind, 1.5 either side, 1.3 below / 1.0 above the eyes
    lo = lo - [1.5, 1.3, 2.0]; hi = hi + [1.5, 1.0, 2.5]
    c = (lo + hi) / 2
    span_z, span_x, span_y = hi[2] - lo[2], hi[0] - lo[0], hi[1] - lo[1]
    scale = LONG_SIDE / max(span_z, span_x, span_y)
    P = tris.reshape(-1, 3)
    near = (np.abs(tris.mean(1)[:, 2] - c[2]) < span_z) & (np.abs(tris.mean(1)[:, 0] - c[0]) < span_x * 2)
    X, Y, Z = np.eye(3)
    specs = {
        # top: looking down; screen right = -x (the left wing on the image's left), up = +z (nose up)
        'top': dict(right=-X, up=Z, fwd=-Y, w=int(span_x * scale), h=int(span_z * scale)),
        # side: from the left (+x) looking at -x; screen right = -z (nose to the left), up = +y
        'side': dict(right=-Z, up=Y, fwd=-X, w=int(span_z * scale), h=int(span_y * scale)),
    }
    written = []
    for name, sp in specs.items():
        # depth grows away from the camera (fwd points into the scene); DEPTH keeps it positive
        cam = Ortho(c, sp['right'], sp['up'], sp['fwd'], scale, sp['w'], sp['h'], 0.0)
        r = EV.Raster(cam, bg=(236, 240, 244))
        r.draw(tris[near], uvs[near], tex)
        im = r.image().convert('RGB')
        grid(im, cam, name, lo, hi)
        path = os.path.join(out_dir, f'{vid}_{name}.png')
        im.save(path)
        meta = {'vehicle': vid, 'view': name, 'width': cam.w, 'height': cam.h, 'scale_px_per_block': round(scale, 4),
                'centre': [round(float(v), 4) for v in c],
                'mapping': ('x = cx - (u - W/2) / scale, z = cz - (v - H/2) / scale' if name == 'top' else
                            'z = cz - (u - W/2) / scale, y = cy - (v - H/2) / scale'),
                'crew_eyes': [[round(float(v), 4) for v in e] for e in eyes]}
        json.dump(meta, open(path[:-4] + '.json', 'w'), indent=1)
        written.append(path)
    return written


def grid(im, cam, name, lo, hi):
    """a light grid in vehicle-local blocks: thin lines every 0.25, darker and labelled every 1.0"""
    d = ImageDraw.Draw(im, 'RGBA')
    W, H = im.size
    if name == 'top':
        axes = (('x', 0, lo[0], hi[0]), ('z', 2, lo[2], hi[2]))
    else:
        axes = (('z', 2, lo[2], hi[2]), ('y', 1, lo[1], hi[1]))
    for label, k, a, b in axes:
        for val in np.arange(math.floor(a * 4) / 4, b + 1e-6, 0.25):
            p = np.array(cam.c, float); p[k] = val
            s = cam.screen(cam.view(p))
            major = abs(val - round(val)) < 1e-6
            col = (40, 60, 90, 110) if major else (40, 60, 90, 40)
            vertical = (name == 'top' and k == 0) or (name == 'side' and k == 2)
            if vertical:
                d.line([(s[0], 0), (s[0], H)], fill=col, width=1)
                if major:
                    d.text((s[0] + 3, 3), f'{label}={val:g}', fill=(30, 50, 80, 220))
            else:
                d.line([(0, s[1]), (W, s[1])], fill=col, width=1)
                if major:
                    d.text((3, s[1] + 2), f'{label}={val:g}', fill=(30, 50, 80, 220))


def main(argv):
    out = argv[0]
    os.makedirs(out, exist_ok=True)
    ids = argv[1:]
    if not ids:
        for f in sorted(os.listdir(EV.DATA)):
            d = json.load(open(os.path.join(EV.DATA, f), encoding='utf-8'))
            if d.get('Type') in ('Airplane', 'Helicopter') and \
                    os.path.exists(os.path.join(EV.GEO, f[:-5] + '.geo.json')):
                ids.append(f[:-5])
    for vid in ids:
        try:
            views(vid, out); print('ok', vid, flush=True)
        except Exception as e:
            print('FAILED', vid, type(e).__name__, e, flush=True)


if __name__ == '__main__':
    main(sys.argv[1:])
