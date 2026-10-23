#!/usr/bin/env python3
"""Free areas of a vehicle's texture: texels no UV triangle of its model (alive geo) samples.

The cockpit painters (mig_29.py's Atlas) need room in the texture for instrument faces and placards; this finds the
largest empty axis-aligned rectangles (greedy, each later one disjoint from the earlier ones), a margin of 2 texels
kept around every used texel.

Usage: python3 tools/cockpit_models/free_atlas.py VID [min_area]   -> prints [(x, y, w, h), ...]
"""
import json, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.environ.get('BVP_GEN') or os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')
TEX = os.environ.get('BVP_TEX_DIR') or os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity')


def used_mask(geo, w, h, skip=()):
    m = Image.new('L', (w, h), 0)
    d = ImageDraw.Draw(m)
    for b in geo['minecraft:geometry'][0]['bones']:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or b['name'].endswith(tuple(skip)):
            continue
        U = np.array(pm['uvs'], float)
        for p in pm['polys']:
            pts = [(float(U[v[2]][0]) * w, (1 - float(U[v[2]][1])) * h) for v in p]
            d.polygon(pts, fill=255, outline=255)
    a = np.asarray(m) > 0
    # grow by 2 texels: bilinear filtering and mip levels read neighbours
    g = a.copy()
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1), (2, 0), (-2, 0), (0, 2), (0, -2), (1, 1), (-1, -1), (1, -1), (-1, 1)):
        g |= np.roll(np.roll(a, dx, 1), dy, 0)
    return g


def largest_rect(free):
    """largest all-True rectangle (histogram method) -> (x, y, w, h, area)"""
    H, W = free.shape
    hist = np.zeros(W, int); best = (0, 0, 0, 0, 0)
    for y in range(H):
        hist = np.where(free[y], hist + 1, 0)
        stack = []
        for x in range(W + 1):
            hgt = hist[x] if x < W else 0
            start = x
            while stack and stack[-1][1] >= hgt:
                s, sh = stack.pop()
                area = sh * (x - s)
                if area > best[4]:
                    best = (s, y - sh + 1, x - s, sh, area)
                start = s
            stack.append((start, hgt))
    return best


ALSO_USED = []                # extra bones whose uvs count as used (see also_used)


def also_used(vid, ref, prefix):
    """counts the uvs of bones named [prefix]* in the geo at commit [ref] as used, whether or not this geo still has
    them: a helicopter's pylon bones (suspended_*), which main's geo no longer carries, so the painted tiles land in the
    same place on either branch (and a branch that keeps the pylons never paints over their texels)"""
    import subprocess
    git = os.environ.get('BVP_GIT', r'C:\Users\udstu\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\git\cmd\git.exe')
    repo = os.path.normpath(os.path.join(HERE, '..', '..'))
    txt = subprocess.run([git, '-C', repo, 'show', f'{ref}:bvp/src/generated/resources/assets/berts_vehicle_pack/'
                          f'custom_geo/{vid}.geo.json'], capture_output=True, check=True).stdout
    g = json.loads(txt.decode('utf-8'))
    ALSO_USED.extend(b for b in g['minecraft:geometry'][0]['bones'] if b['name'].startswith(prefix))


def free_rects(vid, min_area=4096, skip=('__cockpit',), n=6):
    geo = json.load(open(os.path.join(GEO, vid + '.geo.json'), encoding='utf-8'))
    if ALSO_USED:
        names = {b['name'] for b in geo['minecraft:geometry'][0]['bones']}
        geo = {'minecraft:geometry': [{'bones': geo['minecraft:geometry'][0]['bones'] +
                                       [b for b in ALSO_USED if b['name'] not in names]}]}
    im = Image.open(os.path.join(TEX, vid + '.png'))
    w, h = im.size
    free = ~used_mask(geo, w, h, skip)
    out = []
    for _ in range(n):
        x, y, rw, rh, a = largest_rect(free)
        if a < min_area:
            break
        out.append((x, y, rw, rh))
        free[y:y + rh, x:x + rw] = False
    return (w, h), out


if __name__ == '__main__':
    size, rects = free_rects(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 4096)
    print(size, rects, sum(r[2] * r[3] for r in rects))
