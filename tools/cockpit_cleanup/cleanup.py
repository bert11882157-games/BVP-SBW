#!/usr/bin/env python3
"""Removes the instruments the MTB models bring with them from the front instrument panel, so the cockpit instrument
system (steam gauges, flight/radar/stores displays) is the only instrumentation.

  * Modelled instruments: small exported boxes (dial bodies, bezels, needles, knobs, switches) in front of the pilot,
    below the eye line, when the panel has several of them (a lone small box there is usually structure).
  * Painted instruments: the texture of every panel face the pilot looks at (aft-facing, below the eye line, within
    reach) is repainted in the panel's dominant colour with a faint grain. Texels also used by faces elsewhere on the
    aircraft are left alone.
The stick (tools/control_stick/find.py), the HUD above the glare shield and the side consoles are kept.

Usage: python3 tools/cockpit_cleanup/cleanup.py [--write] [--sheet DIR] [id...]   (no ids: every aircraft)
"""
import json, math, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_frames'))
sys.path.insert(0, os.path.join(HERE, '..', 'control_stick'))
import thin as T  # noqa: E402
import find_parts as FP  # noqa: E402
import find as STICK  # noqa: E402

MIN_BOXES = 8
APPLIED = os.path.join(HERE, 'applied.json')


HELICOPTERS_WITH_COCKPITS = ['ah_64d', 'eurocopter_tiger', 'mi28n']


def aircraft():
    return sorted(f[:-5] for f in os.listdir(os.path.join(T.GEN, 'data/berts_vehicle_pack/flight_reference'))) \
        + HELICOPTERS_WITH_COCKPITS


def faces(geo):
    for bone in geo['minecraft:geometry'][0]['bones']:
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys') or bone['name'].startswith('pilot_view_occluder'):
            continue
        for pi, poly in enumerate(pm['polys']):
            yield bone, pi, poly


def panel_faces(geo, eye, exclude):
    """Faces of the front panel: aft-facing, upright-ish, in the cone in front of the pilot, facing the eye."""
    out = set()
    for bone, pi, poly in faces(geo):
        if (bone['name'], pi) in exclude or len(poly) < 3:
            continue
        P = np.array([T.to_local(bone['poly_mesh']['positions'][v[0]]) for v in poly])
        c = P.mean(axis=0)
        if not FP.in_cone(eye, c):
            continue
        n = np.cross(P[1] - P[0], P[2] - P[0])
        if len(poly) > 3:
            n = n + np.cross(P[2] - P[0], P[3] - P[0])
        ln = np.linalg.norm(n)
        if ln < 1e-12:
            continue
        n /= ln
        view = (c - eye) / np.linalg.norm(c - eye)
        if n @ view > 0:
            n = -n
        if n[2] > -0.3 or abs(n[1]) > 0.8 or -(n @ view) < 0.25:
            continue
        out.add((bone['name'], pi))
    return out


def uv_mask(geo, keys, W, H, invert=False):
    """Texel coverage (H, W) of the given faces (or of all other faces when invert)."""
    from PIL import ImageDraw
    img = Image.new('L', (W, H), 0)
    dr = ImageDraw.Draw(img)
    for bone, pi, poly in faces(geo):
        if ((bone['name'], pi) in keys) == invert:
            continue
        uv = bone['poly_mesh']['uvs']
        pts = [(uv[v[2]][0] * W, (1 - uv[v[2]][1]) * H) for v in poly]
        dr.polygon(pts, fill=255, outline=255)
    return np.asarray(img) > 0


def free_block(rgba, size):
    """Top-left corner of a size x size block of fully transparent texels (unused texture space), or None."""
    a = rgba[..., 3] == 0
    H, W = a.shape
    step = size
    for y in range(H - size, -1, -step):
        for x in range(W - size, -1, -step):
            if a[y:y + size, x:x + size].all():
                return x, y
    return None


def clean(vid, log=print):
    path, geo = T.load_geo(vid)
    eye = T.eyes(vid)[0]
    stick = STICK.find(vid)
    keep = set((b['name'], pi) for b, pi in stick['parts']) if stick else set()
    boxes = FP.instrument_boxes(geo, eye, keep)
    removed = set()
    if len(boxes) >= MIN_BOXES:
        removed = set((b, pi) for b, pis in boxes for pi in pis)
    panel = panel_faces(geo, eye, keep | removed)
    # Painted instruments: the panel faces are re-mapped to a small block of plain panel colour placed in unused
    # (fully transparent) texture space, so texels the panel shares with other faces are not disturbed.
    tex_path = os.path.join(T.GEN, 'assets/berts_vehicle_pack/textures/entity', vid + '.png')
    im = Image.open(tex_path)
    mode = im.mode
    rgba = np.asarray(im.convert('RGBA')).copy()
    H, W = rgba.shape[:2]
    mine = uv_mask(geo, panel, W, H) & (rgba[..., 3] > 0)
    remapped = 0
    if panel and mine.any():
        px = rgba[mine][:, :3].astype(int)
        q = (px // 24) * 24 + 12
        keys, counts = np.unique(q[:, 0] * 65536 + q[:, 1] * 256 + q[:, 2], return_counts=True)
        dom = keys[np.argmax(counts)]
        dom_rgb = np.array([dom // 65536, (dom // 256) % 256, dom % 256])
        close = np.abs(q - dom_rgb).max(axis=1) <= 12
        base = px[close].mean(axis=0)
        block = free_block(rgba, 8)
        if block is not None:
            bx, by = block
            rgba[by:by + 8, bx:bx + 8, :3] = np.clip(base, 0, 255).astype(np.uint8)
            rgba[by:by + 8, bx:bx + 8, 3] = 255
            u = (bx + 4) / W
            v = 1 - (by + 4) / H     # the mesh loader flips v
            for bone, pi, poly in faces(geo):
                if (bone['name'], pi) not in panel:
                    continue
                uvs = bone['poly_mesh']['uvs']
                for vert in poly:
                    uvs.append([round(u, 6), round(v, 6)])
                    vert[2] = len(uvs) - 1
                remapped += 1
    painted = remapped
    # geometry removal
    if removed:
        for bone in geo['minecraft:geometry'][0]['bones']:
            pm = bone.get('poly_mesh')
            if not pm or not pm.get('polys'):
                continue
            drop = {pi for (b, pi) in removed if b == bone['name']}
            if drop:
                pm['polys'] = [p for k, p in enumerate(pm['polys']) if k not in drop]
    log(f'{vid:34s} removed {len(boxes) if removed else 0} instrument boxes ({len(removed)} polys), '
        f're-coloured {remapped} of {len(panel)} panel faces')
    out_img = Image.fromarray(rgba, 'RGBA')
    if mode != 'RGBA':
        out_img = out_img.convert(mode)
    return path, geo, bool(removed) or bool(remapped), tex_path, out_img, bool(painted)


def main(argv):
    write = '--write' in argv
    sheet = argv[argv.index('--sheet') + 1] if '--sheet' in argv else None
    ids = [a for a in argv if not a.startswith('--') and a != sheet] or aircraft()
    applied = json.load(open(APPLIED)) if os.path.exists(APPLIED) else {}
    for vid in ids:
        if write and vid in applied:
            print(f'{vid}: already cleaned, skipped')
            continue
        path, geo, geo_changed, tex_path, img, tex_changed = clean(vid)
        if sheet:
            os.makedirs(sheet, exist_ok=True)
            img.save(os.path.join(sheet, vid + '_tex.png'))
            with open(os.path.join(sheet, vid + '.geo.json'), 'w') as fh:
                json.dump(geo, fh, separators=(',', ':'))
        if write:
            if geo_changed:
                with open(path, 'w') as fh:
                    json.dump(geo, fh, separators=(',', ':'))
                    fh.write('\n')
            if tex_changed:
                img.save(tex_path, optimize=True)
            applied[vid] = True
            with open(APPLIED, 'w') as fh:
                json.dump(dict(sorted(applied.items())), fh, indent=1)
                fh.write('\n')


if __name__ == '__main__':
    main(sys.argv[1:])
