#!/usr/bin/env python3
"""Perspective, textured, z-buffered renders of an aircraft model from a crew eye (or any camera), for checking a
cockpit the way the pilot sees it. Instruments from the vehicle asset (FlightDisplays / CockpitGauges) are drawn as
flat dials and screens, and the canopy glass (CanopyGlass) as a light tint.

Usage: eyeview.py VID OUT.png [--eye x,y,z] [--look yaw,pitch] [--fov deg] [--size WxH] [--geo path]
       vehicle-local blocks: x left, y up, z forward; yaw positive to the left, pitch positive up.
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles')
TEX = os.environ.get('BVP_TEX_DIR') or os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity')
LIGHT = np.array([0.25, 0.9, 0.35]); LIGHT /= np.linalg.norm(LIGHT)


def model_triangles(geo, skip=()):
    """(tris Nx3x3 vehicle-local blocks, uvs Nx3x2 normalized, bone index N, bone names)"""
    tris, uvs, ids, names = [], [], [], []
    for bone in geo['minecraft:geometry'][0]['bones']:
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys') or bone['name'].startswith(tuple(skip)):
            continue
        names.append(bone['name'])
        pos = np.array(pm['positions'], float) * np.array([1.0, 1.0, -1.0]) / 16.0
        uv = np.array(pm['uvs'], float)
        for poly in pm['polys']:
            idx = [v[0] for v in poly]; tid = [v[2] if len(v) > 2 else 0 for v in poly]
            for k in range(1, len(idx) - 1):
                tris.append(pos[[idx[0], idx[k], idx[k + 1]]]); uvs.append(uv[[tid[0], tid[k], tid[k + 1]]])
                ids.append(len(names) - 1)
    return np.array(tris), np.array(uvs), np.array(ids), names


class Camera:
    def __init__(self, eye, yaw, pitch, fov, w, h):
        self.eye = np.array(eye, float); self.w, self.h = w, h
        y, p = math.radians(yaw), math.radians(pitch)
        f = np.array([math.sin(y) * math.cos(p), math.sin(p), math.cos(y) * math.cos(p)])
        right = np.cross(f, [0, 1.0, 0]); right /= np.linalg.norm(right)      # x is left, so f x up points right
        up = np.cross(right, f)
        self.R = np.stack([right, up, f])
        self.focal = (w / 2) / math.tan(math.radians(fov) / 2)

    def view(self, p):
        return (np.asarray(p, float) - self.eye) @ self.R.T

    def screen(self, v):
        return np.stack([self.w / 2 + self.focal * v[..., 0] / v[..., 2],
                         self.h / 2 - self.focal * v[..., 1] / v[..., 2]], -1)


NEAR = 0.02


def clip(vs, uv):
    """clip a view-space triangle (3x3) with uvs against the near plane: list of (3x3, 3x2) triangles"""
    inside = vs[:, 2] > NEAR
    if inside.all():
        return [(vs, uv)]
    if not inside.any():
        return []
    poly, puv = [], []
    for i in range(3):
        a, b = vs[i], vs[(i + 1) % 3]; ua, ub = uv[i], uv[(i + 1) % 3]
        if a[2] > NEAR:
            poly.append(a); puv.append(ua)
        if (a[2] > NEAR) != (b[2] > NEAR):
            t = (NEAR - a[2]) / (b[2] - a[2]); poly.append(a + t * (b - a)); puv.append(ua + t * (ub - ua))
    return [(np.array([poly[0], poly[k], poly[k + 1]]), np.array([puv[0], puv[k], puv[k + 1]]))
            for k in range(1, len(poly) - 1)]


class Raster:
    def __init__(self, cam, bg=(150, 175, 205)):
        self.cam = cam
        self.depth = np.full((cam.h, cam.w), np.inf)
        self.color = np.zeros((cam.h, cam.w, 3)); self.color[:] = np.array(bg) / 255.0

    def draw(self, tris, uvs, tex, shade=True, alpha_test=True, blend=None, tint=None):
        cam = self.cam; H, W = tex.shape[:2]
        for t, uv in zip(tris, uvs):
            vs = cam.view(t)
            n = np.cross(t[1] - t[0], t[2] - t[0]); ln = np.linalg.norm(n)
            if ln < 1e-12:
                continue
            n /= ln
            lit = 0.55 + 0.45 * abs(float(n @ LIGHT)) if shade else 1.0
            for cv, cuv in clip(vs, uv):
                s = cam.screen(cv)
                x0 = max(0, int(math.floor(s[:, 0].min()))); x1 = min(cam.w - 1, int(math.ceil(s[:, 0].max())))
                y0 = max(0, int(math.floor(s[:, 1].min()))); y1 = min(cam.h - 1, int(math.ceil(s[:, 1].max())))
                if x1 < x0 or y1 < y0:
                    continue
                X, Y = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
                (ax, ay), (bx, by), (cx, cy) = s
                den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
                if abs(den) < 1e-9:
                    continue
                w0 = ((by - cy) * (X - cx) + (cx - bx) * (Y - cy)) / den
                w1 = ((cy - ay) * (X - cx) + (ax - cx) * (Y - cy)) / den
                w2 = 1 - w0 - w1
                m = (w0 >= -1e-4) & (w1 >= -1e-4) & (w2 >= -1e-4)
                if not m.any():
                    continue
                iz = w0 / cv[0, 2] + w1 / cv[1, 2] + w2 / cv[2, 2]            # perspective-correct
                z = 1 / iz
                u = (w0 * cuv[0, 0] / cv[0, 2] + w1 * cuv[1, 0] / cv[1, 2] + w2 * cuv[2, 0] / cv[2, 2]) * z
                v = (w0 * cuv[0, 1] / cv[0, 2] + w1 * cuv[1, 1] / cv[1, 2] + w2 * cuv[2, 1] / cv[2, 2]) * z
                tx = np.clip((u * W).astype(int), 0, W - 1); ty = np.clip(((1 - v) * H).astype(int), 0, H - 1)
                texel = tex[ty, tx]
                if alpha_test:
                    m &= texel[..., 3] > 0.5
                blk = self.depth[y0:y1 + 1, x0:x1 + 1]
                m &= z < blk
                if not m.any():
                    continue
                col = texel[..., :3] * lit
                if tint is not None:
                    col = np.broadcast_to(np.array(tint, float), col.shape)
                if blend is None:
                    blk[m] = z[m]
                    self.color[y0:y1 + 1, x0:x1 + 1][m] = col[m]
                else:
                    c = self.color[y0:y1 + 1, x0:x1 + 1]
                    c[m] = c[m] * (1 - blend) + col[m] * blend

    def image(self):
        return Image.fromarray((np.clip(self.color, 0, 1) * 255).astype(np.uint8))


# ---------------------------------------------------------------- instruments (preview faces only)

def frame(c, n, up):
    n = np.array(n, float); n /= np.linalg.norm(n)
    u = np.array(up, float); u = u - n * (u @ n); u /= np.linalg.norm(u)
    r = np.cross(u, n)                                    # screen right seen from the crew side (facing -n)
    return np.array(c, float), n, u, -r


TITLES = {'ALTIMETER': 'ALT', 'HEADING': '', 'THROTTLE': 'THROTTLE', 'FILLER_FUEL': 'FUEL', 'FILLER_OIL': 'OIL',
          'FILLER_RPM': 'RPM', 'FILLER_CLOCK': '', 'FILLER_TEMP': 'TEMP', 'FILLER_VOLTS': 'VOLTS',
          'FILLER_HYDRAULIC': 'HYD', 'FILLER_OXYGEN': 'OXY', 'FUEL_QTY': 'FUEL', 'RADAR_ALT': 'RAD ALT', 'CLOCK': '',
          'TURN_SLIP': 'TURN', 'ENGINE_RPM': 'RPM', 'EGT': 'EGT', 'AMMO': 'ROUNDS'}
LAMP_RGB = {'amber': (58, 46, 18), 'red': (58, 20, 18), 'green': (20, 58, 24), 'white': (46, 46, 44), 'blue': (20, 36, 58)}


def annunciator_texture(g, px=256):
    """a stand-in for the panel the game draws: dark lamps with their legends, none lit"""
    w, h = g['Width'], g['Height']
    W, H = (px, max(16, int(px * h / w))) if w >= h else (max(16, int(px * w / h)), px)
    im = Image.new('RGBA', (W, H), (34, 34, 36, 255)); d = ImageDraw.Draw(im)
    lamps = g.get('Lamps') or []
    cols = max(1, g.get('Columns', 3)); rows = max(1, (len(lamps) + cols - 1) // cols)
    m = 3; gap = 2
    lw = (W - 2 * m - (cols - 1) * gap) / cols; lh = (H - 2 * m - (rows - 1) * gap) / rows
    for i, lamp in enumerate(lamps):
        x0 = m + (i % cols) * (lw + gap); y0 = m + (i // cols) * (lh + gap)
        d.rectangle([x0, y0, x0 + lw - 1, y0 + lh - 1], fill=LAMP_RGB.get(lamp.get('Colour', 'amber'), LAMP_RGB['amber']) + (255,))
        lines = lamp['Text'].split('|')
        for k, line in enumerate(lines):
            d.text((x0 + lw / 2, y0 + lh / 2 + (k - (len(lines) - 1) / 2) * 9), line, fill=(138, 138, 128, 255), anchor='mm')
    return np.asarray(im).astype(np.float32) / 255.0


def dial_texture(kind, px=128):
    """a stand-in for the face the game paints for [kind] (GaugePainter): enough to read the layout"""
    im = Image.new('RGBA', (px, px), (0, 0, 0, 0)); d = ImageDraw.Draw(im); c = px / 2
    if kind == 'ATTITUDE':
        d.ellipse([2, 2, px - 3, px - 3], fill=(47, 127, 207, 255))
        d.chord([2, 2, px - 3, px - 3], 0, 180, fill=(122, 82, 38, 255))
        d.line([12, c, px - 12, c], fill=(255, 255, 255, 255), width=2)
        d.line([c - 30, c + 1, c - 10, c + 1], fill=(255, 138, 28, 255), width=4)
        d.line([c + 10, c + 1, c + 30, c + 1], fill=(255, 138, 28, 255), width=4)
    else:
        d.ellipse([2, 2, px - 3, px - 3], fill=(20, 22, 24, 255))
        n = 72 if kind == 'HEADING' else 36
        for k in range(n):
            a = math.radians(k * 360 / n)
            major = k % (6 if kind == 'HEADING' else 3) == 0
            r0 = c - 6; r1 = c - (16 if major else 10)
            d.line([c + r0 * math.sin(a), c - r0 * math.cos(a), c + r1 * math.sin(a), c - r1 * math.cos(a)],
                   fill=(236, 236, 228, 255), width=2 if major else 1)
        if kind == 'HEADING':
            d.line([c, c - 26, c, c + 22], fill=(255, 138, 28, 255), width=4)
            d.line([c - 20, c - 4, c + 20, c - 4], fill=(255, 138, 28, 255), width=4)
        else:
            t = TITLES.get(kind, '')
            if t:
                d.text((c, c + 26), t, fill=(216, 216, 204, 255), anchor='mm')
            d.line([c, c, c + 0.62 * c * math.sin(1.1), c - 0.62 * c * math.cos(1.1)], fill=(244, 244, 236, 255), width=3)
    d.ellipse([0, 0, px - 1, px - 1], outline=(40, 40, 42, 255), width=8)
    return np.asarray(im).astype(np.float32) / 255.0


def screen_texture(kind, px=128):
    im = Image.new('RGBA', (px, px), (10, 22, 12, 255)); d = ImageDraw.Draw(im)
    for k in range(1, 4):
        d.line([px * k / 4, 6, px * k / 4, px - 6], fill=(40, 150, 60, 255))
        d.line([6, px * k / 4, px - 6, px * k / 4], fill=(40, 150, 60, 255))
    d.rectangle([0, 0, px - 1, px - 1], outline=(60, 60, 62, 255), width=4)
    return np.asarray(im).astype(np.float32) / 255.0


def instrument_quad(c, n, up, half):
    c, n, u, r = frame(c, n, up)
    c = c + n * 0.001
    P = [c - r * half - u * half, c + r * half - u * half, c + r * half + u * half, c - r * half + u * half]
    UV = [[1, 0], [0, 0], [0, 1], [1, 1]]
    return (np.array([P[0], P[1], P[2]]), np.array([UV[0], UV[1], UV[2]])), \
           (np.array([P[0], P[2], P[3]]), np.array([UV[0], UV[2], UV[3]]))


def rect_quad(c, n, up, hw, hh):
    c, n, u, rr = frame(c, n, up)
    c = c + n * 0.001
    P = [c - rr * hw - u * hh, c + rr * hw - u * hh, c + rr * hw + u * hh, c - rr * hw + u * hh]
    UV = [[1, 0], [0, 0], [0, 1], [1, 1]]
    return (np.array([P[0], P[1], P[2]]), np.array([UV[0], UV[1], UV[2]])), \
           (np.array([P[0], P[2], P[3]]), np.array([UV[0], UV[2], UV[3]]))


def draw_instruments(r, asset):
    for g in (asset.get('CockpitGauges') or {}).get('Gauges', []):
        if g['Kind'] == 'ANNUNCIATOR':
            tex = annunciator_texture(g)
            for t, uv in rect_quad(g['Center'], g['Normal'], g['Up'], g['Width'] / 2, g['Height'] / 2):
                r.draw([t], [uv], tex, shade=False, alpha_test=False)
            continue
        tex = dial_texture(g['Kind'])
        for t, uv in instrument_quad(g['Center'], g['Normal'], g['Up'], g['Diameter'] / 2):
            r.draw([t], [uv], tex, shade=False)
    for d in (asset.get('FlightDisplays') or {}).get('Displays', []):
        tex = screen_texture(d.get('Kind'))
        for t, uv in instrument_quad(d['Center'], d['Normal'], d['Up'], d['Width'] / 2):
            r.draw([t], [uv], tex, shade=False, alpha_test=False)


def render(vid, out, eye=None, yaw=0.0, pitch=-12.0, fov=80.0, size=(1100, 760), geo_path=None, asset=None,
           glass=True, skip=('pilot_view_occluder',)):
    geo = json.load(open(geo_path or os.path.join(GEO, vid + '.geo.json')))
    asset = asset or json.load(open(os.path.join(ASSET, vid + '.json')))
    data = json.load(open(os.path.join(DATA, vid + '.json')))
    if eye is None:
        seat = data['Seats'][0]; name = seat['CameraPos'].get('EyeAttachment')
        eye = data['Attachments'][name]['Position']
    tex = np.asarray(Image.open(os.path.join(TEX, vid + '.png')).convert('RGBA')).astype(np.float32) / 255.0
    cam = Camera(eye, yaw, pitch, fov, *size)
    r = Raster(cam)
    tris, uvs, _, _ = model_triangles(geo, skip)
    # only what can be seen from the cockpit: within 12 blocks of the eye
    keep = np.linalg.norm(tris.mean(1) - np.array(eye), axis=1) < 12
    r.draw(tris[keep], uvs[keep], tex)
    draw_instruments(r, asset)
    if glass and asset.get('CanopyGlass'):
        g = np.array(asset['CanopyGlass']['Triangles'], float).reshape(-1, 3, 3)
        r.draw(g, np.zeros((len(g), 3, 2)), np.ones((1, 1, 4), np.float32), shade=False, alpha_test=False,
               blend=0.05, tint=(0.75, 0.85, 0.8))      # (as faint as the game's CanopyGlass)
    im = r.image(); im.save(out)
    return im


def main(argv):
    vid, out = argv[0], argv[1]
    kw = {}
    a = argv[2:]
    for i, k in enumerate(a):
        if k == '--eye': kw['eye'] = [float(x) for x in a[i + 1].split(',')]
        if k == '--look': kw['yaw'], kw['pitch'] = (float(x) for x in a[i + 1].split(','))
        if k == '--fov': kw['fov'] = float(a[i + 1])
        if k == '--size': kw['size'] = tuple(int(x) for x in a[i + 1].split('x'))
        if k == '--geo': kw['geo_path'] = a[i + 1]
    render(vid, out, **kw)


if __name__ == '__main__':
    main(sys.argv[1:])
