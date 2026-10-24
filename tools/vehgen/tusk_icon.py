#!/usr/bin/env python3
"""The icons of the fitted M1A1 TUSK II: the 256 x 64 side icon (textures/vehicle_icon/<id>.png: the vehicle's left
side, nose to the left, like the other tank icons, fitted to the pack's icon box, rows 6-57) and the 32 x 32 item icon
(textures/item/vehicle_icons/<id>.png: right side, nose right, flat colours, 1 px dark outline, like vehgen's). Both
are the committed geometry and texture rendered with Blender on a transparent background. flags.py puts the nation
flag on them afterwards (delete tools/icon_flags/originals/.../<id>.png first, so the flag is drawn on the new icon).

    python3 tools/vehgen/tusk_icon.py
"""
import os
import subprocess
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
BLENDER = r'C:\Program Files\Blender Foundation\Blender 5.2\blender.exe'
VID = 'm1a1_tusk'
A = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources', 'assets', 'berts_vehicle_pack')
TEX = os.path.join(REPO, 'bvp', 'src', 'main', 'resources', 'assets', 'berts_vehicle_pack', 'textures')
OUT = os.path.join(TEX, 'vehicle_icon', VID + '.png')
OUT_ITEM = os.path.join(TEX, 'item', 'vehicle_icons', VID + '.png')


def render(obj, out, azimuth):
    """The model from `azimuth` degrees (90 = the vehicle's right side), cropped to its silhouette."""
    subprocess.run([BLENDER, '--background', '--factory-startup', '--python', os.path.join(HERE, 'blender_view.py'),
                    '--', obj, out, str(azimuth), '0', '14', '0', '0', '2.6', '2800', 'texture', 'transparent'],
                   check=True, stdout=subprocess.DEVNULL)
    im = Image.open(out).convert('RGBA')
    ys, xs = np.where(np.asarray(im)[..., 3] > 8)
    return im.crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))


def item_icon(im):
    size, inner, big = 32, 28, 8
    k = inner / max(im.size)
    small = im.resize((max(1, round(im.width * k * big)), max(1, round(im.height * k * big))), Image.LANCZOS)
    canvas = Image.new('RGBA', (size * big, size * big), (0, 0, 0, 0))
    canvas.paste(small, (((size * big) - small.width) // 2, ((size * big) - small.height) // 2), small)
    a = np.asarray(canvas.resize((size, size), Image.BOX)).astype(float)
    solid = a[..., 3] > 110
    rgb = np.where(solid[..., None], a[..., :3] / np.maximum(a[..., 3:4], 1) * 255, 0)
    out = np.zeros((32, 32, 4), np.uint8)
    out[..., :3] = np.clip(rgb, 0, 255)
    out[..., 3] = np.where(solid, 255, 0)
    mask = out[..., 3] > 0
    edge = np.zeros_like(mask)
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        edge |= np.roll(np.roll(mask, dy, 0), dx, 1)
    edge &= ~mask
    out[edge] = (12, 12, 12, 255)
    return Image.fromarray(out)


def main():
    tmp = os.path.join(os.environ.get('TEMP', '.'), 'tusk_icon')
    os.makedirs(tmp, exist_ok=True)
    obj, big = os.path.join(tmp, 'icon.obj'), os.path.join(tmp, 'icon.png')
    subprocess.run([sys.executable, os.path.join(HERE, 'geo_to_obj.py'),
                    os.path.join(A, 'custom_geo', VID + '.geo.json'), obj,
                    os.path.join(A, 'textures', 'entity', VID + '.png'), '--skip',
                    '^(brokenTrack|crudeTrack)'], check=True, stdout=subprocess.DEVNULL)
    im = render(obj, big, 270)
    box_w, box_h = 252, 52
    k = min(box_w / im.width, box_h / im.height)
    small = im.resize((max(1, round(im.width * k)), max(1, round(im.height * k))), Image.LANCZOS)
    icon = Image.new('RGBA', (256, 64), (0, 0, 0, 0))
    icon.paste(small, ((256 - small.width) // 2, 57 - small.height + 1), small)
    icon.save(OUT)
    item_icon(render(obj, big, 90)).save(OUT_ITEM)
    print(OUT, small.size, OUT_ITEM)


if __name__ == '__main__':
    main()
