#!/usr/bin/env python3
"""Adds metal texture and panel lines to an aircraft skin.

Every exterior triangle of the model is rasterised in texture space with its 3D position per texel, so the detail
follows the airframe, not the texture layout:
  * panel lines: thin darker seams where the skin crosses fuselage stations (every FUSE_STATION blocks along the
    length) and, on the wings and tail surfaces, a grid of spanwise and chordwise seams;
  * metal: fine grain plus a soft low-frequency mottle, a few percent either way.
Lines are functions of |x|, so left/right faces that share mirrored texels get the same seams. Cockpit interior
faces, stores and landing gear are left alone.

Usage: python3 tools/texture_detail/detail.py [--write] [--out DIR] <id>...
"""
import json, math, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_frames'))
import thin as T  # noqa: E402

FUSE_STATION = 0.95
WING_SPAN = 0.85
WING_CHORD = 0.9
LINE_DARK = 0.78
GRAIN = 0.02
MOTTLE = 0.035
APPLIED = os.path.join(HERE, 'applied.json')


def exterior_triangles(geo, eye):
    out = []
    for bone in geo['minecraft:geometry'][0]['bones']:
        name = bone['name']
        if 'suspended' in name or 'landing_gear' in name or name.startswith('pilot_view_occluder'):
            continue
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        pos = pm['positions']; uv = pm['uvs']
        for poly in pm['polys']:
            P = np.array([T.to_local(pos[v[0]]) for v in poly])
            UV = np.array([uv[v[2]] for v in poly], float)
            c = P.mean(axis=0)
            rel = c - eye
            if np.linalg.norm(rel) < 1.4 and rel[1] < 0.4:
                continue    # cockpit tub, panel, seat
            for k in range(1, len(poly) - 1):
                out.append((P[[0, k, k + 1]], UV[[0, k, k + 1]]))
    return out


def detail(vid, tex):
    H, W = tex.shape[:2]
    eye = T.eyes(vid)[0]
    _, geo = T.load_geo(vid)
    tris = exterior_triangles(geo, eye)
    # fuselage half-width: the extent of faces whose normal is mostly sideways, near the centre line
    shade = np.ones((H, W), np.float32)
    covered = np.zeros((H, W), bool)
    for P, UV in tris:
        px = np.stack([UV[:, 0] * W, (1 - UV[:, 1]) * H], -1)
        x0, y0 = np.floor(px.min(axis=0)).astype(int)
        x1, y1 = np.ceil(px.max(axis=0)).astype(int)
        x0 = max(x0, 0); y0 = max(y0, 0); x1 = min(x1, W - 1); y1 = min(y1, H - 1)
        if x1 < x0 or y1 < y0:
            continue
        a, b, c = px
        d = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
        if abs(d) < 1e-9:
            continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        l1 = ((gx - a[0]) * (c[1] - a[1]) - (gy - a[1]) * (c[0] - a[0])) / d
        l2 = ((b[0] - a[0]) * (gy - a[1]) - (b[1] - a[1]) * (gx - a[0])) / d
        inside = (l1 >= -0.02) & (l2 >= -0.02) & (l1 + l2 <= 1.02)
        if not inside.any():
            continue
        p3 = P[0][None, None] + l1[..., None] * (P[1] - P[0]) + l2[..., None] * (P[2] - P[0])
        n = np.cross(P[1] - P[0], P[2] - P[0])
        area3 = np.linalg.norm(n) / 2
        n = n / max(np.linalg.norm(n), 1e-12)
        area_px = abs(d) / 2
        per_texel = math.sqrt(area3 / max(area_px, 1e-9))            # blocks per texel
        half = max(0.012, 0.65 * per_texel)
        ax = np.abs(p3[..., 0])
        z = p3[..., 2]
        # distance to the nearest seam plane
        def seam(v, spacing):
            r = np.mod(v, spacing)
            return np.minimum(r, spacing - r)
        dist = seam(z, FUSE_STATION)
        flat = abs(n[1]) > 0.7                                       # wing / tailplane skin
        fin = abs(n[0]) > 0.85 and np.mean(P[:, 1]) > 2.2            # vertical tail
        if flat and np.mean(np.abs(P[:, 0])) > 1.2:
            dist = np.minimum(seam(ax, WING_SPAN), seam(z, WING_CHORD))
        elif fin:
            dist = np.minimum(seam(p3[..., 1], WING_SPAN), seam(z, WING_CHORD))
        line = np.clip(1 - dist / half, 0, 1)
        f = 1 - (1 - LINE_DARK) * line
        sub = shade[y0:y1 + 1, x0:x1 + 1]
        sub[inside] = np.minimum(sub[inside], f[inside])
        covered[y0:y1 + 1, x0:x1 + 1] |= inside
    rng = np.random.default_rng(abs(hash(vid)) % (2 ** 32))
    grain = 1 + rng.normal(0, GRAIN, (H, W)).astype(np.float32)
    # low-frequency mottle: coarse noise upsampled
    coarse = rng.normal(0, 1, (max(2, H // 64), max(2, W // 64))).astype(np.float32)
    mottle = np.asarray(Image.fromarray(coarse).resize((W, H), Image.BICUBIC), np.float32)
    mottle = 1 + MOTTLE * mottle / max(np.abs(mottle).max(), 1e-6)
    factor = np.where(covered, shade * grain * mottle, 1.0)
    out = tex.copy()
    rgb = out[..., :3].astype(np.float32) * factor[..., None]
    out[..., :3] = np.clip(rgb, 0, 255).astype(np.uint8)
    return out, int(covered.sum())


def main(argv):
    write = '--write' in argv
    out_dir = argv[argv.index('--out') + 1] if '--out' in argv else None
    ids = [a for a in argv if not a.startswith('--') and a != out_dir]
    applied = json.load(open(APPLIED)) if os.path.exists(APPLIED) else {}
    for vid in ids:
        if write and vid in applied:
            print(f'{vid}: already detailed, skipped')
            continue
        path = os.path.join(T.GEN, 'assets/berts_vehicle_pack/textures/entity', vid + '.png')
        im = Image.open(path)
        mode = im.mode
        tex = np.asarray(im.convert('RGBA')).copy()
        out, n = detail(vid, tex)
        print(f'{vid}: {n} texels detailed')
        img = Image.fromarray(out, 'RGBA')
        if mode != 'RGBA':
            img = img.convert(mode)
        if out_dir:
            os.makedirs(out_dir, exist_ok=True)
            img.save(os.path.join(out_dir, vid + '.png'))
        if write:
            img.save(path, optimize=True)
            applied[vid] = True
            with open(APPLIED, 'w') as fh:
                json.dump(dict(sorted(applied.items())), fh, indent=1)
                fh.write('\n')


if __name__ == '__main__':
    main(sys.argv[1:])
