#!/usr/bin/env python3
"""Small nation flags at the top left of BVP vehicle icons (owner's rule, Elite Expansion 2: every BVP vehicle icon
carries the flag of its nation of origin; an export variant carries the nation it was exported to).

  python3 tools/icon_flags/flags.py [--write] [vehicle ids ...]

nations.json maps vehicle id -> nation code (and a note on why). Each icon (textures/vehicle_icon/<id>.png and
textures/item/vehicle_icons/<id>.png in the generated and main resource trees) is first copied unchanged to
tools/icon_flags/originals/ and the flag is always drawn on that original, so a second run changes nothing.
The flags are drawn here in flat colours, pixel-aligned for 64 x 64 icons (scaled for other sizes): 15 x 10 px with
a one-pixel dark outline, at (1, 1). Base Superb Warfare vehicles are never touched (BVP only).
"""
import json
import os
import shutil
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
ROOTS = [os.path.join(REPO, 'bvp', 'src', 'generated', 'resources', 'assets', 'berts_vehicle_pack'),
         os.path.join(REPO, 'bvp', 'src', 'main', 'resources', 'assets', 'berts_vehicle_pack')]
RELS = ['textures/vehicle_icon', 'textures/item/vehicle_icons']
W, H = 15, 10                      # flag size on a 64 px icon (outline excluded)
OUTLINE = (20, 20, 22, 255)

RED, WHITE, BLUE = (191, 10, 48), (255, 255, 255), (0, 40, 104)


def stripes_h(colours):
    def draw(im):
        d = ImageDraw.Draw(im)
        n = len(colours)
        for k, c in enumerate(colours):
            y0, y1 = round(k * H / n), round((k + 1) * H / n)
            d.rectangle([0, y0, W - 1, y1 - 1], fill=c)
    return draw


def stripes_v(colours):
    def draw(im):
        d = ImageDraw.Draw(im)
        n = len(colours)
        for k, c in enumerate(colours):
            x0, x1 = round(k * W / n), round((k + 1) * W / n)
            d.rectangle([x0, 0, x1 - 1, H - 1], fill=c)
    return draw


def usa(im):
    d = ImageDraw.Draw(im)
    for y in range(H):                                   # alternating stripes, red first and last
        d.line([(0, y), (W - 1, y)], fill=RED if y % 2 == 0 else WHITE)
    d.rectangle([0, 0, 6, 4], fill=BLUE)                 # the canton over the top half
    for x, y in ((1, 1), (3, 1), (5, 1), (2, 3), (4, 3)):
        d.point((x, y), fill=WHITE)


def gb(im):
    """Union Jack, drawn on a 6x supersampled 90 x 60 field and reduced (3:2 field, crosses 1/3 and 1/5 of the height)."""
    k = 6
    big = Image.new('RGBA', (W * k, H * k), (0, 40, 104, 255))
    d = ImageDraw.Draw(big)
    w, h = W * k, H * k

    def diag(x0, y0, x1, y1, width, colour):
        import math
        dx, dy = x1 - x0, y1 - y0
        n = math.hypot(dx, dy)
        nx, ny = -dy / n * width / 2, dx / n * width / 2
        d.polygon([(x0 + nx, y0 + ny), (x1 + nx, y1 + ny), (x1 - nx, y1 - ny), (x0 - nx, y0 - ny)], fill=colour)
    for x0, y0, x1, y1 in ((0, 0, w, h), (0, h, w, 0)):
        diag(x0, y0, x1, y1, h / 5, WHITE)
    # St Patrick's saltire: the red diagonals are 1/15 of the height (centred; the real flag offsets them)
    q = h / 15
    for x0, y0, x1, y1 in ((0, 0, w, h), (0, h, w, 0)):
        diag(x0, y0, x1, y1, q, RED)
    d.rectangle([0, h / 2 - h / 6, w, h / 2 + h / 6], fill=WHITE)
    d.rectangle([w / 2 - h / 6, 0, w / 2 + h / 6, h], fill=WHITE)
    d.rectangle([0, h / 2 - h / 10, w, h / 2 + h / 10], fill=RED)
    d.rectangle([w / 2 - h / 10, 0, w / 2 + h / 10, h], fill=RED)
    im.paste(big.resize((W, H), Image.LANCZOS), (0, 0))


FLAGS = {
    'GB': gb,
    'US': usa,
    'FR': stripes_v([(0, 35, 149), WHITE, (237, 41, 57)]),
    'DE': stripes_h([(0, 0, 0), (221, 0, 0), (255, 206, 0)]),
    'RU': stripes_h([WHITE, (0, 57, 166), (213, 43, 30)]),
    'UA': stripes_h([(0, 87, 183), (255, 215, 0)]),
    'IT': stripes_v([(0, 146, 70), WHITE, (206, 43, 55)]),
}


def flag_image(code):
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    FLAGS[code](im)
    out = Image.new('RGBA', (W + 2, H + 2), OUTLINE)
    out.paste(im, (1, 1))
    return out


def apply(path, code, original):
    icon = Image.open(original).convert('RGBA')
    f = flag_image(code)
    if icon.size != (64, 64):
        s = icon.height / 64              # by height: the ground vehicles' HUD icons are 256 x 64
        f = f.resize((max(3, round(f.width * s)), max(3, round(f.height * s))), Image.NEAREST)
    icon.alpha_composite(f, (round(icon.height / 64), round(icon.height / 64)))
    return icon


def main(argv):
    write = '--write' in argv
    only = [a for a in argv if not a.startswith('--')]
    nations = json.load(open(os.path.join(HERE, 'nations.json'), encoding='utf-8'))
    done = 0
    for vid, entry in nations.items():
        if vid.startswith('_') or (only and vid not in only):
            continue
        code = entry['nation']
        if code not in FLAGS:
            print(f'{vid}: no flag drawn for {code} yet')
            continue
        for root in ROOTS:
            for rel in RELS:
                path = os.path.join(root, rel, vid + '.png')
                if not os.path.exists(path):
                    continue
                orig = os.path.join(HERE, 'originals', os.path.relpath(path, REPO))
                if write and not os.path.exists(orig):
                    os.makedirs(os.path.dirname(orig), exist_ok=True)
                    shutil.copy2(path, orig)
                icon = apply(path, code, orig if os.path.exists(orig) else path)
                if write:
                    icon.save(path)
                done += 1
                print(f'{vid}: {code} -> {os.path.relpath(path, REPO)}')
    print(('wrote ' if write else 'would write ') + f'{done} icons')


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
