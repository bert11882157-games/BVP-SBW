#!/usr/bin/env python3
"""Side-view geometry check: frame bars (magenta), sill line (green), frame extents (yellow lines) against the
current glass ends (cyan lines). Usage: sidecheck.py outdir glass.json id..."""
import sys, os, json, numpy as np
from PIL import Image, ImageDraw
import ortho as O, evidence as E, sideev as SE

S = sys.argv[1]; glass = json.load(open(sys.argv[2])); ids = sys.argv[3:] or sorted(glass)
os.makedirs(S, exist_ok=True)
tiles = []; rows = []
for vid in ids:
    g = np.array(glass.get(vid, {}).get('Triangles', []), float).reshape(-1, 3, 3)
    ev = E.Evidence(vid)
    eye = ev.eye
    zmin = g[..., 2].min() if len(g) else eye[2] - 1.5
    zmax = g[..., 2].max() if len(g) else eye[2] + 1.5
    zc = (zmin + zmax) / 2; hl = (zmax - zmin) / 2 + 0.8
    hwid = float(np.abs(g[..., 0]).max()) if len(g) else 0.45
    se = SE.SideEvidence(vid, zc, eye[1] - 0.2, hwid, eye, hl=hl, hh=0.8)
    im = O.image(se.col, np.zeros(se.col.shape[:2]), 1).convert('RGB')
    a = np.array(im)
    fr = np.zeros(se.skin.shape, bool)
    for z, y in se.frames:
        ii = np.round((se.zs[0] - z) / SE.STEP).astype(int); jj = np.round((se.ys[0] - y) / SE.STEP).astype(int)
        fr[jj.clip(0, fr.shape[0] - 1), ii.clip(0, fr.shape[1] - 1)] = True
    a[fr] = (255, 0, 255)
    for i in range(len(se.zs)):
        if np.isfinite(se.sill[i]):
            j = int(round((se.ys[0] - se.sill[i]) / SE.STEP))
            if 0 <= j < a.shape[0]: a[j, i] = (0, 255, 0)
    im = Image.fromarray(a); d = ImageDraw.Draw(im)
    X = lambda z: (se.zs[0] - z) / SE.STEP
    fz = [(z.max(), z.min()) for z, y in se.frames]
    ff = max(f[0] for f in fz) if fz else None; fb = min(f[1] for f in fz) if fz else None
    for zz, c in ((zmax, (0, 255, 255)), (zmin, (0, 255, 255)), (ff, (255, 255, 0)), (fb, (255, 255, 0))):
        if zz is not None:
            d.line([(X(zz), 0), (X(zz), im.height)], fill=c)
    for k in range(int((zc - hl) * 10), int((zc + hl) * 10) + 1):
        x = X(k / 10); L = 8 if k % 5 == 0 else 4
        d.line([(x, 0), (x, L)], fill=(255, 0, 0))
        if k % 5 == 0: d.text((x + 2, 8), f'{k / 10:.1f}', fill=(255, 0, 0))
    d.text((3, im.height - 12), vid, fill=(255, 255, 0))
    rows.append((vid, zmax, zmin, ff, fb, [round(f[0], 2) for f in se.feet]))
    print(f'{vid:30s} glass {zmax:5.2f}..{zmin:5.2f}  frames {ff if ff is None else round(ff,2)}..{fb if fb is None else round(fb,2)}', flush=True)
    im.thumbnail((900, 420)); tiles.append(im)
for s0 in range(0, len(tiles), 4):
    grp = tiles[s0:s0 + 4]; w = max(i.width for i in grp); h = max(i.height for i in grp)
    sh = Image.new('RGB', (w * 2, h * 2), (30, 30, 30))
    for k, i in enumerate(grp): sh.paste(i, ((k % 2) * w, (k // 2) * h))
    sh.save(f'{S}/side{s0 // 4:02d}.png')
json.dump(rows, open(f'{S}/side.json', 'w'))
