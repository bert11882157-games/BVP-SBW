#!/usr/bin/env python3
"""Finds the jet-pipe exits of an aircraft model, for the exhaust effects ("Outlets" of AfterburnerPresentation).

The model is ray-cast orthographically from straight behind (rays toward +Z). A jet pipe shows as a pit: an enclosed
patch where the first hit lies well forward of the rim around it (the pipe's inside, or the turbine face). Each pit
becomes one outlet: centre and radius from the pit's area, placed at the rim (the pit border's rearmost depth),
pointing aft. Engines whose pipe exits sit under the fuselage or on the wing (Yak-15, MiG-9, Tunnan) are found the
same way, since their pipe opening still faces aft.

Usage: python3 tools/engine_nozzles/nozzles.py [--check] [--write] id...
  --check  compare with the outlets already authored for afterburning jets (validation)
  --write  add an AfterburnerPresentation with "Afterburning": false to the asset JSON of each listed jet that has
           none (the dry-thrust heat haze only)
"""
import json, os, sys
import numpy as np
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_frames'))
import thin as T  # noqa: E402

STEP = 0.02         # blocks per ray
PIT_DEPTH = 0.25    # a pit's floor lies at least this far forward of its rim (blocks)
MIN_RADIUS = 0.08
MAX_RADIUS = 1.2
DARK = 0.16        # texel luminance of a painted pipe exit, at most
ROUND = 0.45        # pit area / area of its bounding circle, at least
ASSETS = os.path.join(T.GEN, 'assets/berts_vehicle_pack/sbw/vehicles')


def model_triangles(vid, with_uv=False):
    _, geo = T.load_geo(vid)
    tris, uvs = [], []
    for bone in geo['minecraft:geometry'][0]['bones']:
        name = bone['name']
        if name.startswith('pilot_view_occluder'):
            continue
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        pos = np.array(pm['positions'], float)
        uv = np.array(pm['uvs'], float)
        for poly in pm['polys']:
            idx = [v[0] for v in poly]
            tidx = [v[2] if len(v) > 2 else 0 for v in poly]
            for k in range(1, len(idx) - 1):
                tris.append(pos[[idx[0], idx[k], idx[k + 1]]])
                uvs.append(uv[[tidx[0], tidx[k], tidx[k + 1]]])
    local = T.to_local(np.array(tris))
    return (local, np.array(uvs)) if with_uv else local


def rear_depth(tris, with_index=False):
    """First-hit z (rearmost surface) for rays from behind on an x/y grid; +inf where nothing is hit."""
    lo = tris.reshape(-1, 3).min(0) - STEP * 2
    hi = tris.reshape(-1, 3).max(0) + STEP * 2
    nx = int((hi[0] - lo[0]) / STEP) + 1
    ny = int((hi[1] - lo[1]) / STEP) + 1
    depth = np.full((ny, nx), np.inf)
    index = np.full((ny, nx), -1)
    xs = lo[0] + np.arange(nx) * STEP
    ys = lo[1] + np.arange(ny) * STEP
    for ti, t in enumerate(tris):
        a, b, c = t
        x0, x1 = min(a[0], b[0], c[0]), max(a[0], b[0], c[0])
        y0, y1 = min(a[1], b[1], c[1]), max(a[1], b[1], c[1])
        i0, i1 = int(np.ceil((x0 - lo[0]) / STEP)), int(np.floor((x1 - lo[0]) / STEP))
        j0, j1 = int(np.ceil((y0 - lo[1]) / STEP)), int(np.floor((y1 - lo[1]) / STEP))
        if i1 < i0 or j1 < j0:
            continue
        X, Y = np.meshgrid(xs[i0:i1 + 1], ys[j0:j1 + 1])
        d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(d) < 1e-12:
            continue
        w0 = ((b[1] - c[1]) * (X - c[0]) + (c[0] - b[0]) * (Y - c[1])) / d
        w1 = ((c[1] - a[1]) * (X - c[0]) + (a[0] - c[0]) * (Y - c[1])) / d
        w2 = 1 - w0 - w1
        inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
        if not inside.any():
            continue
        z = w0 * a[2] + w1 * b[2] + w2 * c[2]
        block = depth[j0:j1 + 1, i0:i1 + 1]
        nearer = inside & (z < block)
        block[nearer] = z[nearer]
        index[j0:j1 + 1, i0:i1 + 1][nearer] = ti
    return (depth, xs, ys, index) if with_index else (depth, xs, ys)


def colours(vid, tris, uvs, xs, ys, index):
    """Texel colour (RGB 0..1) of the first hit per ray; black where nothing is hit."""
    tex = T.load_texture(vid)
    th, tw = tex.shape[:2]
    img = np.zeros(index.shape + (3,))
    jj, ii = np.nonzero(index >= 0)
    t = index[jj, ii]
    a, b, c = tris[t, 0], tris[t, 1], tris[t, 2]
    X, Y = xs[ii], ys[jj]
    d = (b[:, 1] - c[:, 1]) * (a[:, 0] - c[:, 0]) + (c[:, 0] - b[:, 0]) * (a[:, 1] - c[:, 1])
    d = np.where(np.abs(d) < 1e-12, 1e-12, d)
    w0 = ((b[:, 1] - c[:, 1]) * (X - c[:, 0]) + (c[:, 0] - b[:, 0]) * (Y - c[:, 1])) / d
    w1 = ((c[:, 1] - a[:, 1]) * (X - c[:, 0]) + (a[:, 0] - c[:, 0]) * (Y - c[:, 1])) / d
    w2 = 1 - w0 - w1
    uv = w0[:, None] * uvs[t, 0] + w1[:, None] * uvs[t, 1] + w2[:, None] * uvs[t, 2]
    u = np.clip((uv[:, 0] * tw).astype(int), 0, tw - 1)
    v = np.clip(((1 - uv[:, 1]) * th).astype(int), 0, th - 1)
    img[jj, ii] = tex[v, u, :3]
    return img


def rear_view(vid, path, marks=(), grid=True, window=None):
    """Textured, depth-shaded picture of the model seen from straight behind (for checking outlets by eye)."""
    from PIL import Image, ImageDraw
    tris, uvs = model_triangles(vid, with_uv=True)
    depth, xs, ys, index = rear_depth(tris, with_index=True)
    img = colours(vid, tris, uvs, xs, ys, index)
    jj, ii = np.nonzero(index >= 0)
    col = img[jj, ii]
    zmin = depth[np.isfinite(depth)].min()
    shade = np.clip(1.0 - (depth[jj, ii] - zmin) * 0.12, 0.25, 1.0)
    img[jj, ii] = col * shade[:, None]
    # x is +left in vehicle space; seen from behind, +left is on the viewer's left
    im = Image.fromarray((img[::-1, ::-1] * 255).astype(np.uint8)).resize((depth.shape[1] * 2, depth.shape[0] * 2))
    draw = ImageDraw.Draw(im)
    if grid:
        for gx in np.arange(np.ceil(xs[0] * 2) / 2, xs[-1], 0.5):
            px = (len(xs) - 1 - (gx - xs[0]) / STEP) * 2
            draw.line([px, 0, px, im.height], fill=(60, 60, 140) if gx % 1 else (120, 120, 255))
            if gx % 1 == 0:
                draw.text((px + 2, 2), f'{gx:+.0f}', fill=(160, 160, 255))
        for gy in np.arange(np.ceil(ys[0] * 2) / 2, ys[-1], 0.5):
            py = (len(ys) - 1 - (gy - ys[0]) / STEP) * 2
            draw.line([0, py, im.width, py], fill=(60, 60, 140) if gy % 1 else (120, 120, 255))
            if gy % 1 == 0:
                draw.text((2, py + 2), f'{gy:.0f}', fill=(160, 160, 255))
    for (x, y, r) in marks:
        px = (len(xs) - 1 - (x - xs[0]) / STEP) * 2
        py = (len(ys) - 1 - (y - ys[0]) / STEP) * 2
        rr = r / STEP * 2
        draw.ellipse([px - rr, py - rr, px + rr, py + rr], outline=(0, 255, 0))
    im.save(path)


def find(vid):
    tris, uvs = model_triangles(vid, with_uv=True)
    depth, xs, ys, index = rear_depth(tris, with_index=True)
    lum = colours(vid, tris, uvs, xs, ys, index).mean(axis=2)
    hit = np.isfinite(depth)
    # Rim depth around each pixel: the rearmost hit within a ring; a pit pixel lies PIT_DEPTH forward of it.
    finite = np.where(hit, depth, 1e9)
    outlets = []
    for radius_px in (4, 7, 11, 16, 24, 34, 48):
        rim = ndimage.minimum_filter(finite, size=2 * radius_px + 1)
        pit = hit & (depth - rim > PIT_DEPTH)
        labels, n = ndimage.label(pit)
        for k in range(1, n + 1):
            m = labels == k
            if m[0].any() or m[-1].any() or m[:, 0].any() or m[:, -1].any():
                continue
            area = m.sum() * STEP * STEP
            r = np.sqrt(area / np.pi)
            if not (MIN_RADIUS <= r <= MAX_RADIUS):
                continue
            jj, ii = np.nonzero(m)
            cx, cy = xs[ii].mean(), ys[jj].mean()
            span = max(np.hypot(xs[ii] - cx, ys[jj] - cy).max(), STEP)
            if area / (np.pi * span * span) < ROUND:
                continue
            # enclosed: the pit's border ring is all hits (rays escaping past it would be open air, not a pipe)
            ring = ndimage.binary_dilation(m, iterations=2) & ~m
            if hit[ring].mean() < 0.85:
                continue
            rim_z = depth[ring].min()
            outlets.append((cx, cy, rim_z, r, depth[m].mean() - rim_z))
    # see-through pipes (a straight duct from the intake: nothing is hit) show as enclosed holes
    # (thin parts crossing the opening, a fin trailing edge or an aerial, are opened away first)
    solid = ndimage.binary_opening(hit, iterations=2)
    holes, n = ndimage.label(ndimage.binary_fill_holes(solid) & ~solid)
    for k in range(1, n + 1):
        m = holes == k
        area = m.sum() * STEP * STEP
        r = np.sqrt(area / np.pi)
        if not (MIN_RADIUS <= r <= MAX_RADIUS):
            continue
        jj, ii = np.nonzero(m)
        cx, cy = xs[ii].mean(), ys[jj].mean()
        span = max(np.hypot(xs[ii] - cx, ys[jj] - cy).max(), STEP)
        if area / (np.pi * span * span) < ROUND:
            continue
        ring = ndimage.binary_dilation(m, iterations=3) & ~m & hit
        if not ring.any():
            continue
        outlets.append((cx, cy, np.percentile(depth[ring], 20), r, np.inf))
    # jet pipes closed by a dark, flat disc (a painted exit): a round dark patch facing aft, lighter all round it
    dark = hit & (lum < DARK)
    dark = ndimage.binary_opening(ndimage.binary_closing(dark, iterations=3), iterations=1)
    labels, n = ndimage.label(dark)
    for k in range(1, n + 1):
        m = labels == k
        area = m.sum() * STEP * STEP
        r = np.sqrt(area / np.pi)
        if not (MIN_RADIUS <= r <= MAX_RADIUS):
            continue
        jj, ii = np.nonzero(m)
        cx, cy = xs[ii].mean(), ys[jj].mean()
        span = max(np.hypot(xs[ii] - cx, ys[jj] - cy).max(), STEP)
        if area / (np.pi * span * span) < ROUND or depth[m].std() > 0.12:
            continue
        ring = ndimage.binary_dilation(m, iterations=3) & ~m
        if not hit[ring].mean() > 0.8 or (lum[ring & hit] < DARK).mean() > 0.3:
            continue
        outlets.append((cx, cy, depth[m].min(), r, 0.0))
    # merge the same pit found at several filter sizes: keep the largest
    merged = []
    for o in sorted(outlets, key=lambda o: -o[3]):
        if all(np.hypot(o[0] - p[0], o[1] - p[1]) > max(p[3], o[3]) * 0.8 for p in merged):
            merged.append(o)
    return merged


def probe(vid, x, y, tolerance=0.2):
    """Measures the pipe exit around a seed point (x, y) seen from behind: the connected patch of dark texels or
    open (unhit) rays containing it. Returns (x, y, z, radius) or None."""
    tris, uvs = model_triangles(vid, with_uv=True)
    depth, xs, ys, index = rear_depth(tris, with_index=True)
    hit = np.isfinite(depth)
    lum = colours(vid, tris, uvs, xs, ys, index).mean(axis=2)
    i = int(round((x - xs[0]) / STEP)); j = int(round((y - ys[0]) / STEP))
    seed_lum = lum[j, i] if hit[j, i] else 0.0
    patch = ~hit | (lum < max(DARK, seed_lum + 0.08))
    patch = ndimage.binary_closing(patch, iterations=3)
    labels, _ = ndimage.label(patch)
    k = labels[j, i]
    if k == 0:
        return None
    m = labels == k
    if m[0].any() or m[-1].any() or m[:, 0].any() or m[:, -1].any():
        return None
    jj, ii = np.nonzero(m)
    cx, cy = xs[ii].mean(), ys[jj].mean()
    r = np.sqrt(m.sum() / np.pi) * STEP
    ring = ndimage.binary_dilation(m, iterations=3) & ~m & hit
    inner = m & hit
    z = min(depth[inner].min() if inner.any() else np.inf, np.median(depth[ring]))
    return cx, cy, z, r


def existing(vid):
    path = os.path.join(ASSETS, vid + '.json')
    data = json.load(open(path))
    ab = data.get('AfterburnerPresentation')
    return [] if not ab else [(o['Position'], o.get('NozzleRadiusBlocks')) for o in ab['Outlets']]


def main(argv):
    for a in argv:
        if a.startswith('--probe='):
            vid, xy = a[8:].split(':')
            print(vid, xy, probe(vid, *map(float, xy.split(','))))
    ids = [a for a in argv if not a.startswith('--')]
    view_dir = next((a[7:] for a in argv if a.startswith('--view=')), None)
    for vid in ids:
        found = find(vid)
        if view_dir:
            os.makedirs(view_dir, exist_ok=True)
            marks = [(o[0], o[1], o[3]) for o in found] + [(p[0], p[1], 0.04) for p, _ in existing(vid)]
            rear_view(vid, os.path.join(view_dir, vid + '.png'), marks)
        print(vid)
        for cx, cy, z, r, deep in found:
            print(f'   found  ({cx:+.3f}, {cy:.3f}, {z:.3f}) r={r:.2f} depth={deep:.2f}')
        if '--check' in argv:
            for p, r in existing(vid):
                print(f'   authored ({p[0]:+.3f}, {p[1]:.3f}, {p[2]:.3f}) r={r}')


if __name__ == '__main__':
    main(sys.argv[1:])
